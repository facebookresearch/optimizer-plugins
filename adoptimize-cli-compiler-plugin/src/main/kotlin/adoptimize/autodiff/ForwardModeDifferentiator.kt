/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff

import adoptimize.AutoDiffException
import adoptimize.autodiff.BackPropFunction.DiffIRCreator
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.isPushbackMethod
import adoptimize.autodiff.Metadata.primitiveZero
import adoptimize.autodiff.NodeCodeCopy.NodeParameterType
import adoptimize.autodiff.NodeCodeCopy.ReverseNodeAnalyzer
import adoptimize.autodiff.NodeCodeCopy.SourceCodeExtractor
import adoptimize.autodiff.UnwrapppedNode.CallLowerer
import adoptimize.autodiff.diffIR.BlockBodyStatement
import adoptimize.autodiff.diffIR.BlockStatement
import adoptimize.autodiff.diffIR.Call
import adoptimize.autodiff.diffIR.CallVariable
import adoptimize.autodiff.diffIR.ConditionBlock
import adoptimize.autodiff.diffIR.ConstantStatement
import adoptimize.autodiff.diffIR.ConstructorCallVariable
import adoptimize.autodiff.diffIR.DiffIRStatement
import adoptimize.autodiff.diffIR.DiffIRTransformer
import adoptimize.autodiff.diffIR.GetPropertyVariable
import adoptimize.autodiff.diffIR.GetValVariable
import adoptimize.autodiff.diffIR.GradientVariable
import adoptimize.autodiff.diffIR.LateInitVariable
import adoptimize.autodiff.diffIR.LoopStatement
import adoptimize.autodiff.diffIR.PopIntermediateStateVariable
import adoptimize.autodiff.diffIR.PushIntermediateStateVariable
import adoptimize.autodiff.diffIR.ReturnStatement
import adoptimize.autodiff.diffIR.SetField
import adoptimize.autodiff.diffIR.SetValue
import adoptimize.autodiff.diffIR.TypeOperatorVariable
import adoptimize.autodiff.diffIR.WhenStatement
import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.utils.OperatorNames
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name
import pluginCommon.*
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.overrideRoot
import java.util.*

interface ForwardModeDelegate {
    fun didAddTangent(newTangentLocalVariable: IrVariable, targetVariable: IrVariable, parent: IrStatementContainer): List<IrStatement> = emptyList()
    fun didAddSetField(original: SetField, targetSetField: IrSetField) {}
    fun tangentForSrcProperty(activeProperty: IrProperty): IrValueDeclaration?
    fun tangentForIntermediateState(activeIntermediateState: PopIntermediateStateVariable): IrValueDeclaration? = null
    fun didPushIntermediateState(targetTangent: IrValueDeclaration): List<IrStatement> = emptyList()
}

class ForwardModePopulator(
    val differentiableApi: DifferentiableApi,
    val callGenerator: IrBodyGenerator,
    val irBuiltIns: IrBuiltIns
) {
    class ScopedTangents {
        val targetValueToTangent = Stack<MutableMap<IrValueDeclaration, IrValueDeclaration>>()
        fun push() { targetValueToTangent.push(mutableMapOf()) }
        fun pop() { targetValueToTangent.pop() }
        fun tangent(primal: IrValueDeclaration): IrValueDeclaration? {
            for (scope in targetValueToTangent.reversed()) {
                val tangentMaybe = scope[primal]
                if (tangentMaybe != null) {
                    return tangentMaybe
                }
            }
            return null
        }
        operator fun set(primal: IrValueDeclaration, tangent: IrValueDeclaration) {
            targetValueToTangent.peek().put(primal, tangent)
        }
    }

    private val sourceCodeExtractor = SourceCodeExtractor(callGenerator)
    private val callLowerer = CallLowerer(differentiableApi, callGenerator)
    private val floatPlus = differentiableApi.boxedPrimitiveInfo.primitiveType.getClass()!!.simpleFunctions().first { it.isOperator && it.name == OperatorNames.ADD && it.valueParameters.all { it.type == differentiableApi.boxedPrimitiveInfo.primitiveType } }

    fun forwardMode(
        differentiableBlock: DiffIRStatement,
        srcPropertyToTargetPropertyMap: Map<IrProperty, IrProperty>,
        srcValueDeclarationsToTargetValueDeclarations: Map<IrValueDeclaration, IrValueDeclaration>,
        delegate: ForwardModeDelegate,
        targetForwardID: () -> IrExpression,
        parent: IrDeclarationParent
    ): List<IrStatement> {
        fun zeroTangent(prefix: Name): IrVariable = callGenerator.generateIrVariable(
            name = Name.identifier(DiffIRCreator.tangentVariableName(prefix)),
            containingDeclaration = parent,
            initializer = differentiableApi.primitiveZero(),
            isVar = true
        )
        val reverseNodeAnalyzer = ReverseNodeAnalyzer(differentiableApi, differentiableBlock)

        val copyAndReplacer = CopyAndReplacer(
            MapWrapper(mutableMapOf<IrValueDeclaration, IrValueDeclaration>().also { it.putAll(srcValueDeclarationsToTargetValueDeclarations) }),
            MapWrapper(mutableMapOf<IrProperty, IrProperty>().also { it.putAll(srcPropertyToTargetPropertyMap) }),
            irBuiltIns
        )

        val transformer = object : DiffIRTransformer {
            val context = Stack<MutableList<IrStatement>>()
            val parentElements = Stack<IrStatementContainer>()
            val targetValueToTangent = ScopedTangents()

            override fun transformCallVariable(callVariable: CallVariable): IrStatement {
                val srcToTargetMap = copyAndReplacer.valueParameterSubsitutor
                val copy = copyAndReplacer.copyAndReplace(callVariable.original, ReplaceDelegate.emptyReplacer, parent) as IrVariable
                context.peek().add(copy)

                // map target local variables to dependency node properties
                val dependencyNode = callVariable.callInfo.dependencyNode!!
                val dependencyNodeDispatchReceiver = dependencyNode.backpropMethod.dispatchReceiverParameter!!
                val parentDispatchParameter = when (parent) {
                    is IrClass -> parent.thisReceiver
                    is IrSimpleFunction -> parent.dispatchReceiverParameter
                    else -> null
                }
                val mapFromDependencyNodeToTarget = parentDispatchParameter?.let { mutableMapOf<IrValueDeclaration, IrValueDeclaration>(dependencyNodeDispatchReceiver to it) } ?: mutableMapOf<IrValueDeclaration, IrValueDeclaration>()
                val nodePropertiesToDispatchParameters = reverseNodeAnalyzer.reverseNodePropertyInfo(dependencyNode.clazz) ?: throw AutoDiffException("Could not find dependency node info for reverse node property")
                val dependencyPropertyToTargetVariable = nodePropertiesToDispatchParameters.nodePropertyToDispatchParameters.map {
                    val srcValueDeclaration = callVariable.callInfo.valueForIndex(it.dispatchFunctionParameterIndex).argument
                    Pair(
                        it.property,
                        srcToTargetMap[srcValueDeclaration]
                            ?: throw AutoDiffException(
                                "Failed to write backprop for `${callVariable.name}`. A target variable has not yet been generated for" +
                                    " `${callVariable.callInfo.valueForIndex(it.dispatchFunctionParameterIndex).argument.name}`"
                            )
                    )
                }.toMap().toMutableMap()
                targetValueToTangent.tangent(callVariable.original)?.let { localVariable -> dependencyPropertyToTargetVariable[dependencyNode.primal] = localVariable }

                // collect a contribution for each input by mapping the active parameters of the reverse scalar implementation to a derivative
                val activeNodePropertyToDispatchParameters = nodePropertiesToDispatchParameters.nodePropertyToDispatchParameters.filter { it.type == NodeParameterType.Active }
                val tangentContributions = activeNodePropertyToDispatchParameters.mapNotNull {
                    val unwrappedPrimalValueArgument = callVariable.callInfo.valueForIndex(it.dispatchFunctionParameterIndex)
                    val targetArgument = srcToTargetMap[unwrappedPrimalValueArgument.argument] ?: throw AutoDiffException("no target found for ${unwrappedPrimalValueArgument.argument.name}")
                    val tangentOfTargetArgument = targetValueToTangent.tangent(targetArgument)
                    if (tangentOfTargetArgument == null && unwrappedPrimalValueArgument.isActive) {
                        throw AutoDiffException("The active arguments should have a tangent")
                    }
                    targetValueToTangent.tangent(targetArgument)?.let { currentTangent ->
                        dependencyPropertyToTargetVariable[dependencyNode.upstream] = currentTangent
                        val rootExpressionOfDerivativeStatement = nodePropertiesToDispatchParameters.activePropertyToUpstreamExpression[it.property]
                        if (rootExpressionOfDerivativeStatement != null) {
                            val sourceCode = sourceCodeExtractor.fullTreeForSnippet(rootExpressionOfDerivativeStatement)
                            val copyAndReplaceFromDependencyNode = CopyAndReplacer(MapWrapper(mapFromDependencyNodeToTarget), Substitutor.emptySubstitutor(), callGenerator.pluginContext.irBuiltIns)
                            val targetStatements = sourceCode.map { sourceStatement ->
                                val targetStatement = copyAndReplaceFromDependencyNode.copyAndReplace(
                                    sourceStatement,
                                    object : ReplaceDelegate {
                                        // the copier can't map src properties to target variables so we implement that via a custom replacer.
                                        // there are two cases: (1) it's a property we've tracked and paired to a local variable
                                        // and (2) it's a call to primal(), in which case we drop the call since we have already unwrapped all inputs.
                                        override fun replaceCandidateWith(original: IrCall, candidateCopy: IrCall): IrExpression? {
                                            val function = original.symbol.owner
                                            return if (function.isPropertyAccessor) {
                                                val property = function.correspondingPropertySymbol!!.owner
                                                val correspondingLocalVariable = dependencyPropertyToTargetVariable[property]
                                                when {
                                                    correspondingLocalVariable != null -> callGenerator.generateGetValue(correspondingLocalVariable)
                                                    property.isPrimal() -> {
                                                        when (getVariable(candidateCopy.dispatchReceiver!!)) {
                                                            parentDispatchParameter -> callGenerator.generateGetValue(copy)
                                                            else -> candidateCopy.dispatchReceiver
                                                        }
                                                    }
                                                    else -> null
                                                }
                                            } else {
                                                // The code in the reverse scalars is over differentiable types while our Diff Block may be over primitive types
                                                // As a result, we may need to lower the function being called so that it maps the argument types
                                                with(callLowerer) {
                                                    if (candidateCopy.needsLower()) {
                                                        val expectedType = when {
                                                            original.type.isDifferentiableWrapper() &&
                                                                candidateCopy.allArgumentSimpleTypes().none { it.isDifferentiableWrapper() } -> differentiableApi.boxedPrimitiveInfo.primitiveType
                                                            else -> original.type
                                                        }
                                                        lowerFunction(original, candidateCopy, expectedType)
                                                    } else null
                                                }
                                            }
                                        }
                                    },
                                    parent
                                ) as IrStatement
                                context.peek().add(targetStatement)
                                targetStatement
                            }
                            val tangentContribution = targetStatements.last() as IrValueDeclaration
                            tangentContribution
                        } else {
                            throw AutoDiffException("No derivative code found for active property ${it.property.name} of ${it.property.parentAsClass.name}")
                        }
                    }
                }
                // since the forward mode derivative is the derivative of the function with respect to the output,
                // we add all the derivatives that are with respect to the input together
                var thusFar: IrExpression = callGenerator.generateGetValue(tangentContributions.first())
                for (next in tangentContributions.subList(1, tangentContributions.size)) {
                    thusFar = callGenerator.generateCall(floatPlus, listOf(thusFar), dispatchReciever = callGenerator.generateGetValue(next))
                }
                val tangent = callGenerator.generateIrVariable(Name.identifier(DiffIRCreator.tangentVariableName(copy.name)), parent, thusFar, copy.isVar)
                targetValueToTangent[copy] = tangent
                context.peek().add(tangent)
                val additionalStatements = delegate.didAddTangent(tangent, copy, parentElements.peek())
                context.peek().addAll(additionalStatements)
                return copy
            }

            override fun transformBlockBodyStatement(expression: BlockBodyStatement): IrBlockBody {
                context.push(mutableListOf())
                parentElements.push(expression.original)
                targetValueToTangent.push()
                expression.children.forEach { it.transform(this) }
                parentElements.pop()
                targetValueToTangent.pop()
                return IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.pop())
            }

            var counter = 0
            override fun transformPushIntermediateVariable(expression: PushIntermediateStateVariable): IrStatement {
                val copy = copyAndReplacer.copyAndReplace(expression.original, ReplaceDelegate.emptyReplacer, parent) as IrExpression
                context.peek().add(copy)
                val targetValue = copyAndReplacer.valueParameterSubsitutor[expression.pushedVariable] ?: throw AutoDiffException("Missing ${expression.pushedVariable.name}")
                // TODO: https://github.com/facebookresearch/optimizer-plugins/issues/172
                val tangent = targetValueToTangent.tangent(targetValue) ?: run {
                    val tangent = zeroTangent(Name.identifier("fake_tangent_${counter++}"))
                    context.peek().add(tangent)
                    tangent
                }
                val extraStatements = delegate.didPushIntermediateState(tangent)
                context.peek().addAll(extraStatements)
                return copy
            }

            override fun transformPopIntermediateVariable(expression: PopIntermediateStateVariable): IrStatement {
                val copy = copyAndReplacer.copyAndReplace(expression.original, ReplaceDelegate.emptyReplacer, parent) as IrVariable
                delegate.tangentForIntermediateState(expression)?.let { tangent ->
                    targetValueToTangent[copy] = tangent
                    context.peek().add(copy)
                    context.peek().add(tangent)
                }
                return copy
            }

            override fun transformBlockStatement(expression: BlockStatement): IrBlock? {
                if (!expression.isVirtual) {
                    expression.original?.let { parentElements.push(it) }
                    context.push(mutableListOf())
                    targetValueToTangent.push()
                }
                expression.children.forEach { it.transform(this) }
                if (!expression.isVirtual) {
                    expression.original?.let { parentElements.pop() }
                    targetValueToTangent.pop()
                    return IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, expression.type, null, context.pop())
                }
                return null
            }

            // Given a call, extra transformation is needed if the call is `x.pushback(y)`:
            // We must wrap 'y' in a forward scalar
            override fun transformCall(call: Call): IrExpression {
                val copy = copyAndReplacer.copyAndReplace(call.original, ReplaceDelegate.emptyReplacer, parent) as IrCall
                val function = call.original.symbol.owner
                if (function.isPushbackMethod(differentiableApi)) {
                    val derivativeExpression = copy.getValueArgument(0) ?: throw AutoDiffException("pushback expects exactly one argument")
                    copy.putValueArgument(0, forwardWrap(getVariable(derivativeExpression)))
                }
                context.peek().add(copy)
                return copy
            }

            // Given a statement 'val x = y', we must also assign the tangents accordingly:
            // tangent of x now equals the tangent of y
            override fun transformGetValVariable(getValVariable: GetValVariable): IrStatement {
                val targetRhs = copyAndReplacer.valueParameterSubsitutor[getValVariable.rhs] ?: throw AutoDiffException("No target found for ${getValVariable.rhs.name}")
                val copy = copyAndReplacer.copyAndReplace(getValVariable.original, ReplaceDelegate.emptyReplacer, parent) as IrVariable
                context.peek().add(copy)
                val tangentToAssignToLhs = targetValueToTangent.tangent(targetRhs) ?: throw AutoDiffException("No tangent found for ${targetRhs.name}. If parameter, note that active parameters are not yet supported (because we do not yet have a use case)")
                when {
                    copy.isVar -> {
                        val newTangent = callGenerator.generateIrVariable(Name.identifier(DiffIRCreator.tangentVariableName(copy.name)), parent, tangentToAssignToLhs.type, true).also {
                            it.initializer = callGenerator.generateGetValue(tangentToAssignToLhs)
                        }
                        context.peek().add(newTangent)
                        targetValueToTangent[copy] = newTangent
                    }
                    else -> {
                        targetValueToTangent[copy] = tangentToAssignToLhs
                    }
                }
                return copy
            }

            // Given a statement 'val y = obj.<get-P>()',
            // if P is active, we must assign the tangent of P to y
            override fun transformGetProperty(expression: GetPropertyVariable): IrStatement {
                val copy = copyAndReplacer.copyAndReplace(expression.original, ReplaceDelegate.emptyReplacer, parent) as IrVariable
                context.peek().add(copy)
                val tangent = delegate.tangentForSrcProperty(expression.property) ?: zeroTangent(expression.name)
                targetValueToTangent[copy] = tangent
                context.peek().add(tangent)
                return copy
            }

            override fun transformConditionStatement(expression: ConditionBlock): IrBranch {
                val result = transformBlockStatement(expression.result) as IrBlock
                val branch = IrBranchImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    condition = copyAndReplacer.copyAndReplace(expression.condition, ReplaceDelegate.emptyReplacer, parent) as IrExpression,
                    result = result
                )
                return branch
            }

            override fun transformConstant(constantStatement: ConstantStatement): IrElement {
                val copy = copyAndReplacer.copyAndReplace(constantStatement.original, ReplaceDelegate.emptyReplacer, parent)
                if (copy is IrStatement) context.peek().add(copy)
                return copy
            }

            override fun transformDerivativeVariable(gradientVariable: GradientVariable): IrStatement {
                val copy = copyAndReplacer.copyAndReplace(gradientVariable.original, ReplaceDelegate.emptyReplacer, parent) as IrVariable
                context.peek().add(copy)
                val tangent = zeroTangent(copy.name)
                context.peek().add(tangent)
                targetValueToTangent[copy] = tangent
                return copy
            }

            override fun transformConstructorCall(constructorCallVariable: ConstructorCallVariable): IrStatement {
                val copy = copyAndReplacer.copyAndReplace(constructorCallVariable.original, ReplaceDelegate.emptyReplacer, parent) as IrVariable
                context.peek().add(copy)
                return copy
            }

            override fun transformLateInitVariable(lateInitVariable: LateInitVariable): IrStatement {
                val copy = copyAndReplacer.copyAndReplace(
                    original = lateInitVariable.original,
                    delegate = ReplaceDelegate.emptyReplacer,
                    newParent = parent
                ) as IrVariable
                context.peek().add(copy)
                val tangent = zeroTangent(lateInitVariable.name)
                context.peek().add(tangent)
                targetValueToTangent[copy] = tangent
                return copy
            }

            // Given 's = z', we assign the tangent of z to the tangent of 's'.
            // If 's' has a tangent and that tangent is mutable, instead set the value of the tangent to the tangent of z so that child scope changes are persisted
            override fun transformSetVariable(expression: SetValue): IrExpression {
                val copy = copyAndReplacer.copyAndReplace(expression.original, ReplaceDelegate.emptyReplacer, parent) as IrSetValue
                context.peek().add(copy)
                val rhs = getVariable(copy.value)
                val lhs = copyAndReplacer.valueParameterSubsitutor[expression.setVariable] ?: throw AutoDiffException("no target for source variable ${expression.setVariable.name}")
                val existingTangent = targetValueToTangent.tangent(lhs)
                val rhsTangent = targetValueToTangent.tangent(rhs)
                when {
                    existingTangent != null && existingTangent is IrVariable && existingTangent.isVar -> {
                        val newValue = rhsTangent?.let { callGenerator.generateGetValue(it) } ?: differentiableApi.primitiveZero()
                        val setValue = callGenerator.generateSetVariable(existingTangent, newValue)
                        context.peek().add(setValue)
                    }
                    else -> {
                        rhsTangent?.let {
                            targetValueToTangent[copy.symbol.owner] = it
                        }
                    }
                }
                return copy
            }

            override fun transformSetField(expression: SetField): IrSetField {
                val copy = copyAndReplacer.copyAndReplace(expression.original, ReplaceDelegate.emptyReplacer, parent) as IrSetField
                val setFieldValue = if (expression.correspondingProperty.isPrimal()) {
                    val forwardPrimal = copy.value
                    when {
                        forwardPrimal.type.isConstScalar() -> {
                            callGenerator.generateSetField(
                                field = copy.symbol.owner,
                                value = forwardWrap(getVariable(forwardPrimal)),
                                clazz = copy.symbol.owner.parentAsClass
                            )
                        }
                        forwardPrimal.type.isForwardScalar() -> copy
                        forwardPrimal.type.isReverseScalar() -> throw AutoDiffException("reverse primal not implemented yet")
                        else -> throw AutoDiffException("Only forward, reverse, and constant primals are supported")
                    }
                } else {
                    copy
                }
                context.peek().add(setFieldValue)
                delegate.didAddSetField(expression, targetSetField = setFieldValue)
                return setFieldValue
            }

            override fun transformLoopStatement(expression: LoopStatement): IrWhileLoop {
                val condition = copyAndReplacer.copyAndReplace(expression.original.condition, ReplaceDelegate.emptyReplacer, parent) as IrExpression
                val body = expression.body.transform(this) as IrBlock
                val copy = IrWhileLoopImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, expression.original.type, null).also {
                    it.body = body
                    it.condition = condition
                }
                context.peek().add(copy)
                return copy
            }

            override fun transformReturn(expression: ReturnStatement): IrReturn {
                TODO("return statements are not yet implemented; forward wrapping needed")
            }

            override fun transformTypeOperatorVariable(expression: TypeOperatorVariable): IrStatement {
                val copy = copyAndReplacer.copyAndReplace(expression.original, ReplaceDelegate.emptyReplacer, parent) as IrVariable
                // if the variable being casted has a tangent, cast the tangent also
                val srcRHS = getVariable((expression.original.initializer!! as IrTypeOperatorCall).argument)
                val targetRHS = copyAndReplacer.valueParameterSubsitutor[srcRHS]!!
                targetValueToTangent.tangent(targetRHS)?.let {
                    targetValueToTangent[copy] = if (it.type != expression.targetType) {
                        val tangentTyped = callGenerator.generateCast(callGenerator.generateGetValue(it), expression.targetType)
                        val tangent = callGenerator.generateVal(Name.identifier(DiffIRCreator.tangentVariableName(it.name)), parent, tangentTyped)
                        context.peek().add(tangent)
                        tangent
                    } else it
                }
                context.peek().add(copy)
                return copy
            }

            override fun transformWhenStatement(expression: WhenStatement): IrWhen {
                val branches = expression.children.map { transformConditionStatement(it) }
                val copy = IrWhenImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, expression.original.type, null).also { it.branches.addAll(branches) }
                context.peek().add(copy)
                return copy
            }

            private fun IrType.isDifferentiableWrapper() = this.isSubtypeOfClass(differentiableApi.rootDifferentiableType.classifierOrFail as IrClassSymbol)
            private fun IrType.isReverseScalar() = this == differentiableApi.reverseDiffScalarClass.clazz.defaultType
            private fun IrType.isForwardScalar() = this == differentiableApi.forwardDiffScalarClass.clazz.defaultType
            private fun IrType.isConstScalar() = this == differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType
            private fun IrProperty.isPrimal() = this.getter!!.overrideRoot() == differentiableApi.forwardDiffScalarClass.primalProperty.getter!!.overrideRoot()

            private fun forwardWrap(targetPrimalAsFloatScalar: IrValueDeclaration): IrExpression {
                try {
                    val referencedPrimal: IrValueDeclaration = when (targetPrimalAsFloatScalar.type) {
                        differentiableApi.boxedPrimitiveInfo.primitiveType -> throw AutoDiffException("expected a FloatScalar")
                        differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType -> {
                            when (val init = (targetPrimalAsFloatScalar as IrVariable).initializer) {
                                is IrConstructorCall -> getVariable(init.getValueArgument(0)!!)
                                else -> throw AutoDiffException("Expected no nested statements. Check the unnester for bugs")
                            }
                        }
                        else -> TODO()
                    }
                    val tangentValueOfForward = targetValueToTangent.tangent(referencedPrimal) ?: throw AutoDiffException("Missing A tangent for ${referencedPrimal.name}")
                    // primal, derivativeID, tangent
                    val forwardPrimal = callGenerator.generateConstructorCall(
                        differentiableApi.forwardDiffScalarClass.clazz,
                        listOf(
                            callGenerator.generateGetValue(targetPrimalAsFloatScalar),
                            targetForwardID(),
                            callGenerator.generateConstructorCall(
                                differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass,
                                listOf(callGenerator.generateGetValue(tangentValueOfForward)),
                                null
                            )
                        ),
                        null
                    )
                    return forwardPrimal
                } catch (e: Exception) {
                    return callGenerator.throwException(e.message ?: "Could not generate forward mode code")
                }
            }
        }

        return when (val image = differentiableBlock.transform(transformer)) {
            is IrBlock -> image.statements
            is IrBlockBody -> image.statements
            is IrStatement -> listOf(image)
            else -> throw NotImplementedError("expected a block, block body, or singular statement")
        }
    }
}
