/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.BackPropFunction

import adoptimize.AutoDiffException
import adoptimize.autodiff.*
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.isPrimalGetter
import adoptimize.autodiff.Metadata.primitiveZero
import adoptimize.autodiff.NodeCodeCopy.ReverseNodeAnalyzer
import adoptimize.autodiff.NodeCodeCopy.SourceCodeExtractor
import adoptimize.autodiff.diffIR.*
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import pluginCommon.generators.IrBodyGenerator

data class DifferentiablePair(val differentiableBlock: DiffIRStatement, val output: IrValueDeclaration?)
enum class StackOperation { Push, Pop }
class DiffIRCreator(val differentiableApi: DifferentiableApi, val callGenerator: IrBodyGenerator, pluginContext: IrPluginContext) {
    val builtins = pluginContext.irBuiltIns
    fun createDifferentiableBlock(
        container: IrBody,
        initialActiveParameterList: Set<IrValueDeclaration>,
        activePropertyList: Set<IrProperty>,
        isImplicitActiveState: ((IrCall) -> StackOperation?)? = null
    ): DifferentiablePair {
        val activeType = when {
            initialActiveParameterList.isNotEmpty() -> initialActiveParameterList.first().type
            activePropertyList.isNotEmpty() -> activePropertyList.first().type()
            else -> throw AutoDiffException("Expected at least one active parameter")
        }
        if (initialActiveParameterList.any { it.type != activeType } || activePropertyList.any { it.type() != activeType }) {
            throw AutoDiffException("The active variables and properties must be of the same type")
        }
        fun IrType.isPotentiallyActive() = this == activeType
        val activeList = mutableSetOf<IrValueDeclaration>()
        activeList.addAll(initialActiveParameterList)
        fun IrCall.isActive(): Boolean {
            val isDifferentiableFunction = this.symbol.owner.allParameterTypes().any { it.isPotentiallyActive() } && this.symbol.owner.returnType.isPotentiallyActive()
            val hasActiveArgument = this.allArguments().any { activeList.contains(it) }
            return isDifferentiableFunction && hasActiveArgument
        }
        var loopID = -1
        var returnedVariable: IrValueDeclaration? = null
        val root: DiffIRStatement = container.accept(
            object : IrElementVisitor<DiffIRStatement, Unit?> {
                val primalReferencedInBackpropCach = mutableMapOf<IrSimpleFunction, Boolean>()
                val variableToUsedInBackprop = mutableMapOf<IrVariable, Boolean>()
                override fun visitBlock(expression: IrBlock, data: Unit?): DiffIRStatement {
                    return BlockStatement(boxedChildren(expression.statements), false, expression.type, expression)
                }

                override fun visitBlockBody(body: IrBlockBody, data: Unit?): DiffIRStatement {
                    return BlockBodyStatement(boxedChildren(body.statements), body)
                }

                private fun boxedChildren(unboxedChildren: List<IrStatement>): List<DiffIRStatement> {
                    val backpropStatements = mutableListOf<DiffIRStatement>()
                    var virtualBlock = mutableListOf<DiffIRStatement>()
                    unboxedChildren.forEach { statement ->
                        val newBackPropStatement = statement.accept(this, null)
                        when (newBackPropStatement) {
                            is DiffIRAtom -> virtualBlock.add(newBackPropStatement)
                            else -> {
                                if (virtualBlock.isNotEmpty()) {
                                    backpropStatements.add(BlockStatement(virtualBlock, true, builtins.unitType, null))
                                    virtualBlock = mutableListOf<DiffIRStatement>()
                                }
                                backpropStatements.add(newBackPropStatement)
                            }
                        }
                    }
                    if (virtualBlock.isNotEmpty()) {
                        if (backpropStatements.isEmpty()) {
                            return virtualBlock
                        } else {
                            backpropStatements.add(BlockStatement(virtualBlock, true, builtins.unitType, null))
                        }
                    }
                    return backpropStatements
                }

                override fun visitSetValue(expression: IrSetValue, data: Unit?): DiffIRStatement {
                    return when (val rhs: IrExpression = expression.value) {
                        is IrCall -> {
                            if (!rhs.isActive()) {
                                ConstantStatement(expression, expression.type)
                            } else {
                                throw AutoDiffException("Only support statements of the form `x = foo(y1,..,yN)` where y[i] is a value not an expression. Ensure the lowering pass was executed")
                            }
                        }
                        is IrGetValue -> {
                            if (rhs.type.isPotentiallyActive()) {
                                val isActive = activeList.contains(rhs.symbol.owner)
                                val isReferencedInBackprop = variableToUsedInBackprop[rhs.symbol.owner] ?: true
                                val newExpression = SetValue(
                                    setVariable = expression.symbol.owner as IrVariable, referencedVariable = rhs.symbol.owner, original = expression, rhsIsActive = isActive,
                                    rhsIsUsedInBackProp = isReferencedInBackprop
                                )
                                if (isActive) {
                                    activeList.add(newExpression.setVariable)
                                }
                                newExpression
                            } else {
                                ConstantStatement(expression, expression.type)
                            }
                        }
                        else -> throw AutoDiffException("Unsupported format encountered. Set a variable to a `${rhs.render()}`")
                    }
                }

                override fun visitCall(expression: IrCall, data: Unit?): DiffIRStatement {
                    return if (isImplicitActiveState?.invoke(expression) == StackOperation.Push) {
                        PushIntermediateStateVariable(expression, getVariable(expression.getValueArgument(0)!!))
                    } else Call(expression)
                }

                override fun visitVariable(declaration: IrVariable, data: Unit?): DiffIRStatement {
                    if (declaration.initializer == null) {
                        if (declaration.type.isPotentiallyActive()) {
                            activeList.add(declaration)
                            return LateInitVariable(declaration.name, declaration)
                        } else {
                            return ConstantStatement(declaration, declaration.type)
                        }
                    }
                    return when (val c = declaration.initializer) {
                        is IrCall -> {
                            val function = c.symbol.owner
                            when {
                                function.isPropertyAccessor -> {
                                    if (function.returnType.isPotentiallyActive()) {
                                        val isActive = activePropertyList.contains(function.correspondingPropertySymbol!!.owner)
                                        if (isActive) {
                                            activeList.add(declaration)
                                        }
                                        GetPropertyVariable(declaration.name, declaration, function.correspondingPropertySymbol!!.owner, isActive)
                                    } else {
                                        ConstantStatement(declaration, c.type)
                                    }
                                }
                                c.isActive() -> {
                                    activeList.add(declaration)
                                    val callInfo = callToInfo(c)
                                    primalReferencedInBackpropCach[callInfo.dispatchFunction]?.let {
                                        variableToUsedInBackprop[declaration] = it
                                    }
                                    return CallVariable(
                                        declaration.name,
                                        callInfo,
                                        declaration
                                    )
                                }
                                else -> {
                                    if (isImplicitActiveState?.invoke(c) == StackOperation.Pop) {
                                        activeList.add(declaration)
                                        PopIntermediateStateVariable(declaration.name, declaration)
                                    } else {
                                        ConstantStatement(declaration, declaration.type)
                                    }
                                }
                            }
                        }
                        is IrConstructorCall -> {
                            val acceptsVariables = (0 until c.valueArgumentsCount).any {
                                when (c.getValueArgument(it)) {
                                    is IrConst<*> -> false
                                    else -> true
                                }
                            }
                            if (acceptsVariables) {
                                ConstructorCallVariable(declaration)
                            } else {
                                ConstantStatement(declaration, declaration.type)
                            }
                        }
                        is IrGetValue -> {
                            // TODO: https://github.com/facebookresearch/optimizer-plugins/issues/36
                            val rhs = c.symbol.owner
                            if (rhs.type.isPotentiallyActive()) {
                                val isActive = activeList.contains(rhs)
                                if (isActive) {
                                    activeList.add(declaration)
                                }
                                GetValVariable(declaration.name, declaration, rhs, isActive)
                            } else {
                                ConstantStatement(declaration, declaration.type)
                            }
                        }
                        is IrTypeOperatorCall -> {
                            val arg = getVariable(c.argument)
                            if (activeList.contains(arg)) {
                                activeList.add(declaration)
                            }
                            TypeOperatorVariable(declaration.name, declaration, arg.type, c.type)
                        }
                        is IrConst<*> -> {
                            if (isGradientVariable(declaration.name, c, differentiableApi)) {
                                activeList.add(declaration)
                                GradientVariable(declaration.name, declaration)
                            } else {
                                ConstantStatement(declaration, declaration.type)
                            }
                        }
                        is IrGetObjectValue -> {
                            ConstantStatement(declaration, declaration.type)
                        }
                        else -> throw AutoDiffException("Only support statements of the form val x = foo(y1,..,yN) where y[i] is a value not an expression")
                    }
                }

                private fun callToInfo(call: IrCall): DiffIRCallInfo {
                    val dispatchfunction = call.symbol.owner
                    differentiableApi.reverseNodeForFunction(dispatchfunction)?.let { dependencyNode ->
                        val backpropMethod = dependencyNode.functions.first { it.overriddenSymbols.contains(differentiableApi.reverseDiffScalarClass.backpropMethod.symbol) }
                        primalReferencedInBackpropCach[dispatchfunction] ?: run {
                            var referencesPrimal = false
                            backpropMethod.accept(
                                object : IrElementVisitorVoid {
                                    override fun visitElement(element: IrElement) {
                                        element.acceptChildrenVoid(this)
                                    }

                                    override fun visitCall(expression: IrCall) {
                                        if (expression.symbol.owner.isPrimalGetter(differentiableApi)) {
                                            if (getVariable(expression.dispatchReceiver!!) == backpropMethod.dispatchReceiverParameter) {
                                                referencesPrimal = true
                                            }
                                        }
                                    }
                                },
                                null
                            )
                            primalReferencedInBackpropCach[dispatchfunction] = referencesPrimal
                            referencesPrimal
                        }
                        val upstream = dependencyNode.properties.first { it.getter?.overriddenSymbols?.contains(differentiableApi.reverseDiffScalarClass.upstreamProperty.getter!!.symbol) == true }
                        val primal = dependencyNode.properties.first { it.getter?.overriddenSymbols?.contains(differentiableApi.reverseDiffScalarClass.primalProperty.getter!!.symbol) == true }
                        val derivativeId = dependencyNode.properties.first { it.getter?.overriddenSymbols?.contains(differentiableApi.reverseDiffScalarClass.derivativeId.getter!!.symbol) == true }
                        return DiffIRCallInfo(
                            dispatchfunction,
                            call.dispatchReceiver?.let {
                                val declaration = getVariable(it)
                                DiffIRArgument(declaration, activeList.contains(declaration))
                            },
                            call.extensionReceiver?.let {
                                val declaration = getVariable(it)
                                DiffIRArgument(declaration, activeList.contains(declaration))
                            },
                            (0 until call.valueArgumentsCount).map {
                                val declaration = getVariable(call.getValueArgument(it)!!)
                                DiffIRArgument(declaration, activeList.contains(declaration))
                            }.toList(),
                            ConcreteReverseNode(dependencyNode, backpropMethod, upstream, primal, derivativeId)
                        )
                    }

                    return DiffIRCallInfo(
                        dispatchfunction,
                        call.dispatchReceiver?.let {
                            val declaration = getVariable(it)
                            DiffIRArgument(declaration, activeList.contains(declaration))
                        },
                        call.extensionReceiver?.let {
                            val declaration = getVariable(it)
                            DiffIRArgument(declaration, activeList.contains(declaration))
                        },
                        (0 until call.valueArgumentsCount).map {
                            val declaration = getVariable(call.getValueArgument(it)!!)
                            DiffIRArgument(declaration, activeList.contains(declaration))
                        }.toList(),
                        null
                    )
                }

                private fun callToInfo(call: IrConstructorCall): DiffIRConstructorCallInfo = DiffIRConstructorCallInfo(
                    call.symbol.owner,
                    (0 until call.valueArgumentsCount).map {
                        val declaration = getVariable(call.getValueArgument(it)!!)
                        DiffIRArgument(declaration, activeList.contains(declaration))
                    }.toList()
                )

                override fun visitWhen(expression: IrWhen, data: Unit?): DiffIRStatement {
                    val children = expression.branches.mapIndexed { index, branch ->
                        val body = when (val r = branch.result) {
                            is IrBlock -> r
                            else -> IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, r.type, null, listOf(r))
                        }
                        val image = body.accept(this, null) as BlockStatement
                        ConditionBlock(branch.condition, image, index)
                    }
                    return WhenStatement(expression, children)
                }

                override fun visitWhileLoop(loop: IrWhileLoop, data: Unit?): DiffIRStatement {
                    val loopBody = loop.body?.accept(this, null) ?: throw AutoDiffException("Only loops with bodies are accepted")
                    return LoopStatement(loopID--, loop, loopBody)
                }

                override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: Unit?): DiffIRStatement {
                    throw NotImplementedError("Do while is not yet supported")
                }

                override fun visitReturn(expression: IrReturn, data: Unit?): DiffIRStatement {
                    if (returnedVariable != null) {
                        throw AutoDiffException("Currently only a single return statement is permitted")
                    } else {
                        returnedVariable = getVariable(expression.value)
                    }
                    return ReturnStatement(expression, returnedVariable!!)
                }

                override fun visitSetField(expression: IrSetField, data: Unit?): DiffIRStatement {
                    val correspondingProperty = expression.symbol.owner.correspondingPropertySymbol?.owner ?: throw AutoDiffException("Expected a property to be associated with a field")
                    return SetField(expression, correspondingProperty)
                }

                override fun visitThrow(expression: IrThrow, data: Unit?): DiffIRStatement {
                    return ConstantStatement(expression, expression.type)
                }

                override fun visitElement(element: IrElement, data: Unit?): DiffIRStatement {
                    throw NotImplementedError("All elements must fall into the following categories: {When, While, Variable, SetVal}. Found ${element.render()}")
                }
            },
            null
        )
        val dependencyNodeAnalyzer = ReverseNodeAnalyzer(differentiableApi, root)
        val sourceCodeExtractor = SourceCodeExtractor(callGenerator)
        val transformer = object : DiffIRToDiffIRTransformer() {
            override fun transformCallVariable(callVariable: CallVariable): CallVariable {
                val nodePropertiesToDispatchParameters = dependencyNodeAnalyzer.reverseNodePropertyInfo(callVariable.callInfo.dependencyNode!!.clazz)!!
                val propertyMap = nodePropertiesToDispatchParameters.nodePropertyToDispatchParameters.map {
                    val property = it.property
                    val srcValueDeclaration: IrValueDeclaration =
                        callVariable.callInfo.valueForIndex(it.dispatchFunctionParameterIndex).argument
                    Pair(property, srcValueDeclaration)
                }.toMap()
                val isReferenced = mutableMapOf<IrValueDeclaration, Boolean>()
                var primalIsReferenced = false
                nodePropertiesToDispatchParameters.nodePropertyToDispatchParameters.forEach {
                    // if the property's primal is reference in the back prop, the argument needs to be pushed.
                    val rootExpressionOfDerivativeStatement =
                        nodePropertiesToDispatchParameters.activePropertyToUpstreamExpression[it.property]
                    if (rootExpressionOfDerivativeStatement != null) {
                        val sourceCode: List<IrValueDeclaration> =
                            sourceCodeExtractor.fullTreeForSnippet(rootExpressionOfDerivativeStatement)
                        sourceCode.forEach {
                            it.acceptVoid(object : IrElementVisitorVoid {
                                override fun visitElement(element: IrElement) {
                                    element.acceptChildrenVoid(this)
                                }
                                override fun visitCall(expression: IrCall) {
                                    val getter = expression.symbol.owner.correspondingPropertySymbol?.owner?.getter
                                    if (getter != null) {
                                        val propertyMatch: Map<IrProperty, IrValueDeclaration> = propertyMap.filterKeys { it.getter == getter }
                                        if (propertyMatch.isNotEmpty()) {
                                            isReferenced[propertyMatch.values.first()] = true
                                        }
                                    }
                                    if (expression.symbol.owner.isPrimalGetter(differentiableApi)) {
                                        if (getVariable(expression.dispatchReceiver!!) == callVariable.callInfo.dependencyNode!!.backpropMethod.dispatchReceiverParameter) {
                                            primalIsReferenced = true
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
                return CallVariable(
                    callVariable.name,
                    callInfo = DiffIRCallInfo(
                        dispatchReceiver = callVariable.callInfo.dispatchReceiver?.let { DiffIRArgument(it.argument, it.isActive, isReferenced[it.argument] ?: false) },
                        dispatchFunction = callVariable.callInfo.dispatchFunction,
                        extensionReceiver = callVariable.callInfo.extensionReceiver?.let { DiffIRArgument(it.argument, it.isActive, isReferenced[it.argument] ?: false) },
                        arguments = callVariable.callInfo.arguments.map { DiffIRArgument(it.argument, it.isActive, isReferenced[it.argument] ?: false) },
                        dependencyNode = callVariable.callInfo.dependencyNode
                    ),
                    callVariable.original, primalIsReferenced
                )
            }
        }
        val newRoot = with(transformer) {
            root.transform()
        }
        return DifferentiablePair(newRoot, returnedVariable)
    }

    fun createBackProppableFunction(function: IrFunction, activeParameterIndex: Int): DiffIRFunction {
        if (function.allParametersWithIndex().any { !(it.valueDescriptor.type is IrSimpleType) }) {
            throw AutoDiffException("Only functions whose parameters are simple types are differentiable.")
        }
        val activeList = mutableSetOf<IrValueDeclaration>(function.allParametersWithIndex().first { it.index == activeParameterIndex }.valueDescriptor)
        val parameters = function.allParametersWithIndex().map { DiffIRParameter(it.valueDescriptor, it.valueDescriptor.type as IrSimpleType, activeList.contains(it.valueDescriptor), it.index) }

        val body = function.body ?: throw AutoDiffException("Cannot optimize function $function because it does not have a body")
        val (root, returnedVariable) = createDifferentiableBlock(body, activeList, emptySet())
        if (returnedVariable == null) throw AutoDiffException("Expected a return value from the primal function ${function.name}")
        return DiffIRFunction(function, root, returnedVariable, parameters)
    }

    companion object {
        fun gradientVariableName(originalVariableName: String): String {
            return "$${originalVariableName}_derivative"
        }
        fun tangentVariableName(originalVariableName: Name) = "$${originalVariableName}_tangent"
        fun isGradientVariable(variableName: Name, initializer: IrConst<*>, differentiableApi: DifferentiableApi): Boolean {
            val isGeneratedByCompiler = variableName.toString().startsWith("$")
            val isGeneratedByMe = variableName.toString().endsWith("_derivative")
            val isZero = initializer.value == differentiableApi.primitiveZero().value
            return isGeneratedByCompiler && isGeneratedByMe && isZero
        }
    }
}
