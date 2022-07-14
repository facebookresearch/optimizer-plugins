/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.UnwrapppedNode

import adoptimize.AutoDiffException
import adoptimize.autodiff.*
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.lowerFunction
import adoptimize.autodiff.Metadata.primitiveZero
import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.overrideRoot

class CallLowerer(val differentiableApi: DifferentiableApi, val callGenerator: IrBodyGenerator) {
    val primalOverrideRoot = differentiableApi.reverseDiffScalarClass.primalProperty.getter!!.overrideRoot()
    val zeroRoot = differentiableApi.boxedPrimitiveInfo.scalarZeroObjectProperty.getter!!.symbol.owner.overrideRoot()
    val oneRoot = differentiableApi.boxedPrimitiveInfo.scalarOneObjectProperty.getter!!.symbol.owner.overrideRoot()

    fun IrCall.needsLower(): Boolean {
        val misMatchedArguments = allArgumentSimpleTypes().zip(symbol.owner.allParameterTypes()).filter { (argType, paramType) ->
            !(argType.isSubtypeOfType(paramType))
        }
        return if (misMatchedArguments.isNotEmpty()) {
            misMatchedArguments.all { (argType, paramType) -> argType.isPrimitiveVersionOf(paramType) }
        } else false
    }

    fun lowerFunction(original: IrCall, candidateCopy: IrCall, expectedReturnType: IrType): IrExpression? {
        val root = original.symbol.owner.overrideRoot()
        if (original.symbol.owner.hasAnnotation(differentiableApi.scalarNoopAnnotation)) {
            val candidateCopyReceiver = when {
                original.dispatchReceiver == null && original.extensionReceiver == null -> throw AutoDiffException("ScalarNoop used on a function with no implicit receiver: `${original.symbol.owner.fqNameForIrSerialization}`")
                original.dispatchReceiver != null && original.extensionReceiver != null -> throw AutoDiffException("ScalarNoop can only be used on a function with a single implicit receiver. `${original.symbol.owner.fqNameForIrSerialization}` has both a dispatch and an extension receiver")
                original.dispatchReceiver != null -> candidateCopy.dispatchReceiver!!
                original.extensionReceiver != null -> candidateCopy.extensionReceiver!!
                else -> throw AutoDiffException("Unreachable")
            }
            // The ScalarNoop annotation indicates that the operation is a noop only for scalars
            if (candidateCopyReceiver.type == primitiveType) {
                return callGenerator.generateGetValue(getVariable(candidateCopyReceiver))
            }
        }

        return when {
            candidateCopy.symbol.owner.isPropertyAccessor -> {
                val newReceiver = candidateCopy.dispatchReceiver?.let { getVariable(it) }
                val oldReceiver = original.dispatchReceiver?.let { getVariable(it) }
                when {
                    root == primalOverrideRoot && newReceiver?.type == primitiveType -> callGenerator.generateGetValue(newReceiver)
                    root == zeroRoot -> differentiableApi.primitiveZero()
                    newReceiver != null && oldReceiver != null && newReceiver.type != oldReceiver.type -> {
                        (newReceiver.type.classifierOrFail.owner as IrClass)
                            .simpleFunctions().firstOrNull { it.overrideRoot() == root }
                            ?.let { function -> callGenerator.generateCall(function, emptyList(), candidateCopy.dispatchReceiver, null) }
                    }
                    else -> null
                }
            }
            else -> differentiableApi.lowerFunction(original, candidateCopy, expectedReturnType)
        }
    }

    fun IrSimpleType.isPrimitiveVersionOf(boundParameter: IrSimpleType): Boolean = primitiveType == this && boundParameter.isSubtypeOfType(differentiableApi.rootDifferentiableType)

    val primitiveType = this.differentiableApi.boxedPrimitiveInfo.primitiveType
}
