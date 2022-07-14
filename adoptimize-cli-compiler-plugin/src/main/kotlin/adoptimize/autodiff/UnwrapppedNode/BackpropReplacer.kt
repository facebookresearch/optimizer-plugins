/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.UnwrapppedNode

import adoptimize.AutoDiffException
import adoptimize.autodiff.*
import adoptimize.autodiff.Metadata.*
import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.Name
import pluginCommon.*
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.overrideRoot

class BackpropReplacer(
    callGenerator: IrBodyGenerator,
    differentiableApi: DifferentiableApi,
    stackClass: StackClass,
    irBuiltIns: IrBuiltIns
) : NodeUnwrapper<IrSimpleFunction>(callGenerator, differentiableApi, stackClass, irBuiltIns) {
    private val typeIntersections = object {
        val areSiblingsCache = mutableMapOf<Pair<IrSimpleType, IrSimpleType>, Boolean>()
        fun shareAnAncestor(left: IrSimpleType, right: IrSimpleType): Boolean {
            val key = Pair(left, right)
            if (!areSiblingsCache.contains(key)) {
                val intersection = left.intersect(right)
                areSiblingsCache[key] = intersection.isNotEmpty()
            }
            return areSiblingsCache[key]!!
        }
    }

    override fun copyAndUnboxStatements(
        unwrapPropertyInfo: UnwrapPropertyInfo,
        boxedSrc: ReverseScalarClass,
        newDispatchReceiverParameter: IrValueParameter,
        newParent: IrSimpleFunction,
        boxedConstructorParamToOptUnboxedConstructorParams: MapWrapper<IrValueDeclaration, IrValueDeclaration>
    ): List<IrStatement> {
        val backPropStatementMethod = boxedSrc.clazz.functions.firstOrNull { it.overrideRoot() == (differentiableApi.reverseDiffScalarClass.backpropMethod as IrSimpleFunction).overrideRoot() } ?: throw AutoDiffException("No backprop method found on the boxed node")

        fun isOriginalThis(getCall: IrExpression) = (getCall as? IrGetValue)?.let { it.symbol.owner == backPropStatementMethod.dispatchReceiverParameter } ?: false
        fun targetThis() = callGenerator.generateGetValue(newDispatchReceiverParameter)

        val boxedBodyStatements = backPropStatementMethod.body!!.statements
        val newStatements = GuardedScope().also { it.pushScope() }
        val upstreamStatement = newStatements.tryPutStatelessVariable(callGenerator.generateVal(Name.identifier("\$up"), newParent, callGenerator.generateGetProperty(differentiableApi.reverseDiffScalarClass.upstreamProperty, callGenerator.generateGetValue(newParent.dispatchReceiverParameter!!))))

        // Generate the scalar block of the when
        val isDScalarCondition = IrTypeOperatorCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            irBuiltIns.booleanType,
            IrTypeOperator.INSTANCEOF,
            typeOperand = differentiableApi.scalarRoot.defaultType,
            argument = callGenerator.generateGetValue(upstreamStatement)
        )

        val dScalarStatements = run {
            val scalarBlockSrcToTarget = MapWrapper(mutableMapOf<IrValueDeclaration, IrValueDeclaration>())
            val scalarBlockCopier = CopyAndReplacer(scalarBlockSrcToTarget, unwrapPropertyInfo.boxedPropertyToUnboxedProperty, irBuiltIns)
            scalarBlockSrcToTarget[backPropStatementMethod.dispatchReceiverParameter!!] = newDispatchReceiverParameter
            boxedBodyStatements.map { scalarBlockCopier.copyAndReplace(it, PrimalUnwrapperReplaceDelegate(unwrapPropertyInfo, backPropStatementMethod, newDispatchReceiverParameter), newParent) as IrStatement }
        }
        // Generate the Tensor block of the when branch
        val dTensorStatements = run {
            val srcToTargetTensors = MapWrapper(mutableMapOf<IrValueDeclaration, IrValueDeclaration>())
            srcToTargetTensors[backPropStatementMethod.dispatchReceiverParameter!!] = newDispatchReceiverParameter

            // lift 'root' arguments by wrapping them in the boxed primitive
            val liftedPropertyVariables = mutableMapOf<IrProperty, IrVariable>()
            val srcPropertyToTargetReference: Map<IrProperty, () -> IrExpression> = unwrapPropertyInfo.boxedPropertyToUnboxedProperty.map.entries.map { entry ->
                val originalProperty = entry.key
                val targetProperty = entry.value
                if (originalProperty.type().isSubtypeOf(differentiableApi.rootDifferentiableType, IrTypeSystemContextImpl(irBuiltIns)) && targetProperty.type() == callLowerer.primitiveType) {
                    val init = callGenerator.generateConstructorCall(
                        differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass,
                        listOf(callGenerator.generateGetProperty(targetProperty, callGenerator.generateGetValue(newParent.dispatchReceiverParameter!!))),
                        emptyList()
                    )
                    liftedPropertyVariables[originalProperty] = callGenerator.generateVal(Name.identifier("${originalProperty.name}_local"), newParent, init)
                }
                Pair(originalProperty) {
                    if (liftedPropertyVariables.containsKey(originalProperty)) {
                        callGenerator.generateGetValue(liftedPropertyVariables[originalProperty]!!)
                    } else {
                        callGenerator.generateGetProperty(targetProperty, targetThis())
                    }
                }
            }.toMap()
            val generalTypedVariables = mutableSetOf<IrValueDeclaration>()
            backPropStatementMethod.accept(
                object : IrElementVisitorVoid {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildrenVoid(this)
                    }

                    override fun visitSetValue(expression: IrSetValue) {
                        if (differentiableApi.rootDifferentiableType == expression.value.type) {
                            generalTypedVariables.add(expression.symbol.owner)
                        }
                    }
                },
                null
            )
            val tensorBlockCopier = CopyAndReplacer(srcToTargetTensors, Substitutor.emptySubstitutor(), irBuiltIns)
            val statements = boxedBodyStatements.map {
                tensorBlockCopier.copyAndReplace(
                    it,
                    object : ReplaceDelegate {
                        override fun replaceCandidateWith(original: IrVariable, candidateCopy: IrVariable): IrVariable? {
                            if (generalTypedVariables.contains(original) && candidateCopy.type != differentiableApi.rootDifferentiableType) {
                                candidateCopy.type = differentiableApi.rootDifferentiableType
                            }
                            return null
                        }

                        override fun replaceCandidateWith(
                            original: IrTypeOperatorCall,
                            candidateCopy: IrTypeOperatorCall
                        ): IrExpression? {
                            return if (candidateCopy.typeOperand.isSubtypeOf(differentiableApi.rootDifferentiableType, IrTypeSystemContextImpl(irBuiltIns))) {
                                differentiableApi.boxPrimitive(callGenerator.generateCast(candidateCopy.argument, differentiableApi.boxedPrimitiveInfo.primitiveType))
                            } else {
                                null
                            }
                        }

                        override fun replaceCandidateWith(original: IrCall, candidateCopy: IrCall): IrExpression? {
                            val function = original.symbol.owner
                            val root = function.overrideRoot()
                            fun dispatchReceiver() = function.dispatchReceiverParameter ?: throw AutoDiffException("The plugin assumes that the ${candidateCopy.symbol.owner.name} function is a method but no dispatch receiver found: ${original.symbol.owner.render()}")
                            fun targetReceiverArg() = candidateCopy.dispatchReceiver ?: throw AutoDiffException("The Copier failed to populate the implicit receiver field for ${candidateCopy.symbol.owner.name}: ${candidateCopy.render()}")
                            fun liftFunction(): IrExpression? {
                                return when {
                                    root == callLowerer.primalOverrideRoot -> {
                                        val targetReceiverArgType = targetReceiverArg().type
                                        if (dispatchReceiver().type != targetReceiverArgType) {
                                            (targetReceiverArgType.classifierOrFail.owner as IrClass)
                                                .simpleFunctions().firstOrNull { it.overrideRoot() == callLowerer.primalOverrideRoot }
                                                ?.let { function -> callGenerator.generateCall(function, emptyList(), candidateCopy.dispatchReceiver, null) }
                                        } else null
                                    }
                                    else -> {
                                        throw AutoDiffException("Attempted to lift ${original.symbol.owner.render()} but I do not know how to lift it. Provide a search space or rule for lifting this function.")
                                    }
                                }
                            }

                            return when {
                                root == stackPopRoot -> {
                                    val typeArgument = (targetReceiverArg().type as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull ?: throw AutoDiffException("Expected the stack dispatch receiver to have a single type argument")
                                    when {
                                        typeArgument.isFloat() && candidateCopy.type.isFloat() -> differentiableApi.boxPrimitive(candidateCopy)
                                        else -> null
                                    }
                                }
                                function.isPropertyAccessor && isOriginalThis(original.dispatchReceiver!!) -> srcPropertyToTargetReference[original.symbol.owner.correspondingPropertySymbol!!.owner]?.invoke()
                                root == pushbackRoot -> {
                                    val targetReceiverArg = targetReceiverArg()
                                    val variable = getVariable(targetReceiverArg) as IrVariable
                                    val setTo = if (variable.initializer is IrGetValue) (variable.initializer as IrGetValue).symbol.owner else null
                                    if (dispatchReceiver().type != targetReceiverArg.type) {
                                        val entry = liftedPropertyVariables.entries.firstOrNull { if (setTo != null) { it.value == setTo } else { it.value == variable } }
                                        val originalProperty = entry?.key ?: throw AutoDiffException("Expected any node properties to be stored in the unwrapped map. Offender: ${getVariable(targetReceiverArg).render()}")
                                        val unwrappedProperty = unwrapPropertyInfo.boxedNodePropertyToUnboxedNodeProperty[originalProperty] ?: throw AutoDiffException("Could not find the corresponding unboxed node property of ${originalProperty.render()}")
                                        candidateCopy.dispatchReceiver = callGenerator.generateGetProperty(unwrappedProperty, targetThis())
                                        callGenerator.generateCall(candidateCopy.symbol.owner, (0 until candidateCopy.valueArgumentsCount).map { candidateCopy.getValueArgument(it)!! }, callGenerator.generateGetProperty(unwrappedProperty, targetThis()))
                                    } else null
                                }
                                candidateCopy.needsLift() -> liftFunction()
                                else -> {
                                    with(callLowerer) {
                                        when {
                                            candidateCopy.needsLower() -> {
                                                val hasDifferentiableArgs = candidateCopy.allArgumentSimpleTypes().any { it.isSubtypeOfType(differentiableApi.rootDifferentiableType) }
                                                val expectedReturnType = if (hasDifferentiableArgs) original.type else callLowerer.primitiveType
                                                lowerFunction(original, candidateCopy, expectedReturnType)
                                            }
                                            else -> null
                                        }
                                    }
                                }
                            }
                        }
                    },
                    newParent
                ) as IrStatement
            }
            liftedPropertyVariables.values + statements
        }

        newStatements.putWhen(
            callGenerator.whenStatementWithElse(
                listOf(
                    IrBodyGenerator.Branch(isDScalarCondition, dScalarStatements),
                ),
                elseStatements = dTensorStatements, irBuiltIns.unitType
            )
        )
        return newStatements.popScope()
    }

    private fun IrSimpleType.hasCommonAncestor(boundParameter: IrSimpleType): Boolean {
        if (callLowerer.primitiveType == boundParameter && this.isSubtypeOfType(differentiableApi.rootDifferentiableType)) return true
        return typeIntersections.shareAnAncestor(this, boundParameter)
    }

    private fun IrCall.needsLift(): Boolean {
        val function = symbol.owner
        val misMatchedArguments = allArgumentSimpleTypes().zip(function.allParameterTypes()).filter { (argType, paramType) ->
            !(argType.isSubtypeOfType(paramType))
        }
        return if (misMatchedArguments.isNotEmpty()) {
            misMatchedArguments.all { (argType, paramType) -> argType.hasCommonAncestor(paramType) }
        } else false
    }
}
