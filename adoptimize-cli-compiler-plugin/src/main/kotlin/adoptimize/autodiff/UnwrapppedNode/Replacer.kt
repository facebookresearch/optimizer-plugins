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
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import pluginCommon.MapWrapper
import pluginCommon.ReplaceDelegate
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.overrideRoot

/*
* Active Node Parameter type corresponds to an active parameter that needs backprop
*
* NotActiveDifferentiable Node Parameter corresponds to a parameter of differentiable type that is not active.
*   It will not be backpropped
*
* BoxedPrimitive Node Parameter type corresponds to a parameter of differenitable type that is known to have
*   no differentiable data structures (it is purely a wrapper)
*
* Constant Node Parameter type corresponds to a type unrelated to the differentiable hierarchy
*
* Framework Node Parameter type corresponds to a type that has differentiable plumbing responsibility. It is handled
*   specially in the mapping process
*
**/
enum class NodeParameterType { Injected, NotActiveDifferentiable, BoxedPrimitive, Constant, Framework, IntermediateValues, Decisions }

class UnwrapPropertyInfo(
    val primitivePrimalProperty: IrProperty,
    val boxedPropertyToUnboxedProperty: MapWrapper<IrProperty, IrProperty>,
    val boxedNodePropertyToUnboxedNodeProperty: Map<IrProperty, IrProperty>,

)

abstract class NodeUnwrapper<T : IrDeclarationParent>(
    val callGenerator: IrBodyGenerator,
    val differentiableApi: DifferentiableApi,
    val stackClass: StackClass,
    val irBuiltIns: IrBuiltIns
) {
    val upstreamOverrideRoot = differentiableApi.reverseDiffScalarClass.upstreamProperty.getter!!.overrideRoot()
    val diffRoot = differentiableApi.scalarRoot.defaultType
    val stackPopRoot = (stackClass.popMethod as IrSimpleFunction).overrideRoot()
    val pushbackRoot = differentiableApi.reverseDiffScalarClass.pushbackMethod.overrideRoot()
    val callLowerer = CallLowerer(differentiableApi, callGenerator)

    abstract fun copyAndUnboxStatements(
        unwrapPropertyInfo: UnwrapPropertyInfo,
        boxedSrc: ReverseScalarClass,
        newDispatchReceiverParameter: IrValueParameter,
        newParent: T,
        boxedConstructorParamToOptUnboxedConstructorParams: MapWrapper<IrValueDeclaration, IrValueDeclaration>
    ): List<IrStatement>
    inner class PrimalUnwrapperReplaceDelegate(
        val unwrapPropertyInfo: UnwrapPropertyInfo,
        val originalParent: IrDeclaration,
        val newDispatchReceiverParameter: IrValueParameter
    ) : ReplaceDelegate {

        override fun replaceCandidateWith(original: IrCall, candidateCopy: IrCall): IrExpression? {
            val root = original.symbol.owner.overrideRoot()
            return when {
                root == callLowerer.primalOverrideRoot && isOriginalThis(original.dispatchReceiver!!) -> callGenerator.generateCall(unwrapPropertyInfo.primitivePrimalProperty.getter!!, emptyList(), targetThis(), null)
                root == pushbackRoot -> {
                    val oldDispatchReceiver = getVariable(original.dispatchReceiver!!) as IrVariable
                    val property: IrProperty = (oldDispatchReceiver.initializer!! as IrCall).symbol.owner.correspondingPropertySymbol!!.owner
                    val imageNodeProperty = unwrapPropertyInfo.boxedNodePropertyToUnboxedNodeProperty[property] ?: throw AutoDiffException("Could not find the node property that corresponds with ${property.name}")
                    val dispatchReciever = callGenerator.generateGetProperty(imageNodeProperty, callGenerator.generateGetValue(newDispatchReceiverParameter))
                    callGenerator.generateCall(candidateCopy.symbol.owner, listOf(differentiableApi.boxPrimitive(candidateCopy.getValueArgument(0)!!)), dispatchReciever, null)
                }
                root == callLowerer.zeroRoot -> differentiableApi.primitiveZero()
                root == callLowerer.oneRoot -> differentiableApi.primitiveOne()
                root == upstreamOverrideRoot -> callGenerator.generateGetProperty(
                    dispatchReceiver = callGenerator.generateCast(candidateCopy, differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType),
                    propertyName = differentiableApi.boxedPrimitiveInfo.valueProperty.name
                )
                else -> {
                    with(callLowerer) {
                        when {
                            candidateCopy.needsLower() -> lowerFunction(original, candidateCopy, if (original.type.isSubtypeOfClass(differentiableApi.rootDifferentiableType.classifierOrFail as IrClassSymbol)) primitiveType else original.type)
                            else -> null
                        }
                    }
                }
            }
        }

        override fun replaceCandidateWith(original: IrVariable, candidateCopy: IrVariable): IrVariable {
            if (candidateCopy.initializer == null && original.type.isSubtypeOfClass(differentiableApi.scalarRoot.symbol)) {
                candidateCopy.type = callLowerer.primitiveType
            }
            return candidateCopy
        }

        override fun replaceCandidateWith(
            original: IrTypeOperatorCall,
            candidateCopy: IrTypeOperatorCall
        ): IrExpression? {
            return if (candidateCopy.typeOperand.isSubtypeOf(differentiableApi.rootDifferentiableType, IrTypeSystemContextImpl(irBuiltIns))) {
                callGenerator.generateCast(candidateCopy.argument, differentiableApi.boxedPrimitiveInfo.primitiveType)
            } else {
                null
            }
        }

        private fun isOriginalThis(getCall: IrExpression) = getVariable(getCall) == when (originalParent) {
            is IrFunction -> originalParent.dispatchReceiverParameter
            is IrClass -> originalParent.thisReceiver
            else -> throw NotImplementedError("new parent must be either a class or a function")
        }

        private fun targetThis() = callGenerator.generateGetValue(newDispatchReceiverParameter)
    }

    fun DifferentiableApi.boxPrimitive(primitive: IrExpression): IrExpression {
        if (primitive.type != boxedPrimitiveInfo.primitiveType) {
            throw AutoDiffException("Cannot box $primitive because it is not a ${boxedPrimitiveInfo.primitiveType.render()}")
        }
        return callGenerator.generateConstructorCall(boxedPrimitiveInfo.boxedPrimitiveClass, listOf(primitive), null)
    }
}
