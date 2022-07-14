/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.NodeCodeCopy

import adoptimize.AutoDiffException
import adoptimize.autodiff.*
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.isBackproppable
import adoptimize.autodiff.Metadata.isDerivativeID
import adoptimize.autodiff.Metadata.isDifferentiableScalar
import adoptimize.autodiff.diffIR.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import pluginCommon.generators.IrBodyGenerator

// TODO: https://github.com/facebookincubator/differentiable/issues/1643 (it assumes straightline immutable code in the source, which is probably the dispatch function in our use case)
class SourceCodeExtractor(val callGenerator: IrBodyGenerator) {
    fun fullTreeForSnippet(rootExpression: IrGetValue): List<IrValueDeclaration> {
        val root: IrValueDeclaration = rootExpression.symbol.owner

        return when (root) {
            is IrValueParameter -> listOf(root)
            else -> {
                val statements = mutableListOf<IrValueDeclaration>()
                val stack = java.util.ArrayDeque<IrValueDeclaration>()
                fun addArgToStack(arg: IrExpression) {
                    val ref = when (arg) {
                        is IrConst<*> -> { null }
                        is IrGetValue -> { arg.symbol.owner }
                        is IrCall -> { assert(arg.origin == IrStatementOrigin.GET_PROPERTY); null }
                        else -> {
                            throw AutoDiffException("Expected `${root.parent.kotlinFqName}` to be in reduced expression form")
                        }
                    }
                    ref?.let { stack.push(it) }
                }
                stack.push(root)
                while (stack.isNotEmpty()) {
                    when (val current = stack.pop()) {
                        is IrValueParameter -> {}
                        is IrVariable -> {
                            statements.add(current)
                            when (val init = current.initializer) {
                                is IrCall -> {
                                    init.dispatchReceiver?.let { addArgToStack(it) }
                                    init.extensionReceiver?.let { addArgToStack(it) }
                                    (0 until init.valueArgumentsCount).forEach { addArgToStack(init.getValueArgument(it)!!) }
                                }
                                is IrTypeOperatorCall -> addArgToStack(init.argument)
                                is IrGetValue -> stack.push(init.symbol.owner)
                                is IrConstructorCall -> (0 until init.valueArgumentsCount).forEach { addArgToStack(init.getValueArgument(it)!!) }
                                is IrConst<*> -> { }
                                else -> {
                                    throw NotImplementedError()
                                }
                            }
                        }
                        else -> {
                            throw NotImplementedError()
                        }
                    }
                }
                statements.reversed()
            }
        }
    }
}

// NodeParameterType is a property of ReverseNode properties. A Node type can be one of four types: Constant, Unwrapped, Active, or Framework.
// A `Framework` property should be ignored because the auto diff engine replaces them (for example, a node's upstream is substituted for the associated statement's derivative variable)
// A `Constant` property cannot be differentiable.
// An `Active` property is a property that represents a child of the backprop tree. There should be a pushback on this property in the backprop method
// An `Unwrapped` property is an unwrapped active property. It is passed in the event that the backprop method is dependent on an operand of the dispatch function,
// but that operand is not necessarily active. For example, the backprop for the lhs parameter of Times relies on the rhs parameter but the rhs parameter may not be active.
// TODO: Why distinguish between Constants and Unwrapped? Looks like only Active is used
enum class NodeParameterType { Active, Unwrapped, Constant, Framework, DerivativeID }

class NodePropertyToDispatchParameter(val property: IrProperty, val type: NodeParameterType, val dispatchFunctionParameterIndex: Int)
class ReverseNodePropertyInfo(
    val nodePropertyToDispatchParameters: List<NodePropertyToDispatchParameter>,
    val activePropertyToUpstreamExpression: Map<IrProperty, IrGetValue>
)
class ReverseNodeAnalyzer(
    val differentiableApi: DifferentiableApi,
    primalBody: DiffIRStatement
) {
    private val dependencyNodePropertyToPrimal = mutableMapOf<IrClass, ReverseNodePropertyInfo>()
    private val dependencyNodeTracer = ActivePropertyToUpstreamExpressionFinder(differentiableApi)
    private fun IrProperty.nodeParameterType(): NodeParameterType {
        require(differentiableApi.isDifferentiableScalar(this.parentAsClass.defaultType))
        val propType = type()
        return when {
            this.isFakeOverride || this.overriddenSymbols.isNotEmpty() -> NodeParameterType.Framework
            differentiableApi.isBackproppable(propType) -> NodeParameterType.Active
            differentiableApi.isDifferentiableScalar(propType) -> NodeParameterType.Unwrapped
            differentiableApi.isDerivativeID(propType) -> NodeParameterType.DerivativeID
            else -> NodeParameterType.Constant
        }
    }

    fun reverseNodePropertyInfo(dependencyNode: IrClass) = dependencyNodePropertyToPrimal[dependencyNode]

    init {
        val operationsToAnalyze = mutableMapOf<IrFunction, Pair<ConcreteReverseNode, Map<IrProperty, IrGetValue>>>()
        primalBody.acceptVisitor(object : DiffIRVisitor {
            override fun visitVariable(declaration: ActiveVariable) {
                if (declaration is CallVariable) {
                    declaration.callInfo.dependencyNode?.let { dependencyNode ->
                        operationsToAnalyze.put(
                            declaration.callInfo.dispatchFunction,
                            Pair(dependencyNode, dependencyNodeTracer.activePropertyToUpstreamExpression(dependencyNode))
                        )
                    }
                }
            }
        })

        operationsToAnalyze.forEach { dispatchFunctionAndDependencyInfo ->
            val (dispatchFunction, value) = dispatchFunctionAndDependencyInfo
            val (concreteDependencyNode, dependencyInfo) = value
            val indexedDispatchParameters = dispatchFunction.allParametersWithIndex()
            val nodeParameterInfos = mutableListOf<NodePropertyToDispatchParameter>()
            val valueParameters = concreteDependencyNode.clazz.primaryConstructor?.valueParameters ?: throw AutoDiffException("Could not find a primary constructor to ${concreteDependencyNode.clazz.name}")
            val nonFrameworkProperties = concreteDependencyNode.clazz.properties
                .map { property -> Pair(property, property.nodeParameterType()) }
                .filter { it.second != NodeParameterType.Framework && it.second != NodeParameterType.DerivativeID }
                .map { (property, nodeType) ->
                    Triple(
                        property,
                        valueParameters.firstOrNull { vp -> vp.name == property.name }
                            ?: throw AutoDiffException("Nonframework properties must be injected: ${property.name} of ${concreteDependencyNode.clazz.name}"),
                        nodeType
                    )
                }
                .sortedBy { it.second.index }.toList()
            var dispatchParameterIndex = 0
            val visitedParameters = mutableMapOf<Int, NodeParameterType>()
            val nonFrameworkPropertyCount = nonFrameworkProperties.count {
                when {
                    it.third == NodeParameterType.Active -> true
                    it.third == NodeParameterType.Constant -> true
                    else -> false
                }
            }
            if (nonFrameworkPropertyCount > indexedDispatchParameters.size) {
                throw AutoDiffException(
                    "The mapping from the dispatch function to the ReverseNode" +
                        " is ambiguous because there are more properties in the corresponding node than" +
                        " parameters in the dispatch function that maps to it: ${dispatchFunction.name}"
                )
            }
            nonFrameworkProperties.forEachIndexed { index, propertyWithType ->
                val (property, _, nodeParameterType) = propertyWithType
                val currentDispatchParameterWithIndex: ParameterWithIndex = indexedDispatchParameters[dispatchParameterIndex]
                when {
                    nodeParameterType == NodeParameterType.Active -> {
                        val hasUnwrappedAssociate = (index + 1 < nonFrameworkProperties.size && nonFrameworkProperties[index + 1].third == NodeParameterType.Unwrapped)
                        if (visitedParameters.containsKey(currentDispatchParameterWithIndex.index)) {
                            throw AutoDiffException("Expected active cases to be injected first. Problem parameter: $currentDispatchParameterWithIndex")
                        }
                        if (!hasUnwrappedAssociate) {
                            dispatchParameterIndex++
                        }
                    }
                    nodeParameterType == NodeParameterType.Unwrapped -> {
                        if (!visitedParameters.containsKey(currentDispatchParameterWithIndex.index) || visitedParameters[currentDispatchParameterWithIndex.index] != NodeParameterType.Active) {
                            throw AutoDiffException("Expected active cases to be injected before unwrapped cases. Problem parameter: $currentDispatchParameterWithIndex")
                        }
                        dispatchParameterIndex++
                    }
                    nodeParameterType == NodeParameterType.Constant -> {
                        if (differentiableApi.isDifferentiableScalar(currentDispatchParameterWithIndex.valueDescriptor.type)) {
                            throw AutoDiffException("Expected mapped dispatch function to be constant: ${property.name} does not map to ${currentDispatchParameterWithIndex.valueDescriptor.name}")
                        }
                        dispatchParameterIndex++
                    }
                }
                nodeParameterInfos.add(NodePropertyToDispatchParameter(property, nodeParameterType, currentDispatchParameterWithIndex.index))
                visitedParameters[currentDispatchParameterWithIndex.index] = nodeParameterType
            }
            if (indexedDispatchParameters.any { !visitedParameters.containsKey(it.index) }) {
                throw AutoDiffException("Not all the parameters of the dispatch function were mapped to a property.")
            }
            val reverseNodePropertyInfo = ReverseNodePropertyInfo(nodeParameterInfos, dependencyInfo)
            dependencyNodePropertyToPrimal[concreteDependencyNode.clazz] = reverseNodePropertyInfo
        }
    }

    class ActivePropertyToUpstreamExpressionFinder(val differentiableApi: DifferentiableApi) {
        val cache = mutableMapOf<IrClass, Map<IrProperty, IrGetValue>>()
        fun activePropertyToUpstreamExpression(concreteReverseNode: ConcreteReverseNode): Map<IrProperty, IrGetValue> {
            if (!cache.containsKey(concreteReverseNode.clazz)) {
                if (concreteReverseNode.backpropMethod.body == null) {
                    throw AutoDiffException("The body of ${concreteReverseNode.clazz.name}.${concreteReverseNode.backpropMethod.name} is null, which may mean it wasn't inlined in the source. Make sure it is inlined in the differentiable API and try cleaning.")
                }
                val dependencyNodeVariableToDependencyNodeProperty: Map<IrValueDeclaration, IrProperty> = VariableToPropertyMatcher(concreteReverseNode.clazz).propertiesToVariable(concreteReverseNode.backpropMethod)
                val dependentNodeActiveChildrenToUpstreamExpression = mutableMapOf<IrProperty, IrGetValue>()
                concreteReverseNode.backpropMethod.acceptVoid(object : IrElementVisitorVoid {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildrenVoid(this)
                    }

                    override fun visitCall(expression: IrCall) {
                        if (expression.symbol.owner.overriddenSymbols.contains(differentiableApi.reverseDiffScalarClass.pushbackMethod.symbol) || expression.symbol == differentiableApi.reverseDiffScalarClass.pushbackMethod.symbol) {
                            val localVariable = (expression.dispatchReceiver as? IrGetValue)?.symbol?.owner ?: throw AutoDiffException("The dispatch receiver of pushback was not a GetValue, which probably means expression lowering was not applied to the backpropagate method of ${concreteReverseNode.clazz.name}. Try cleaning and rebuilding if using a preprocessor to unnest the expressions.")
                            val dependencyProperty = dependencyNodeVariableToDependencyNodeProperty[localVariable]!!
                            when (val firstArgument = expression.getValueArgument(0) ?: throw AutoDiffException("Expected pushback to contain 1 argument")) {
                                is IrGetValue -> {
                                    dependentNodeActiveChildrenToUpstreamExpression.put(dependencyProperty, firstArgument)
                                }
                                else -> {
                                    throw AutoDiffException("Nested expressions not allowed: ${firstArgument.render()}. Sometimes the cause of this error is cached .class files. Try cleaning and recompiling the differentiable library to force the preprocessor to unnest the backpropogate methods.")
                                }
                            }
                        }
                        super.visitCall(expression)
                    }
                })
                cache[concreteReverseNode.clazz] = dependentNodeActiveChildrenToUpstreamExpression
            }
            return cache[concreteReverseNode.clazz]!!
        }
    }
}
