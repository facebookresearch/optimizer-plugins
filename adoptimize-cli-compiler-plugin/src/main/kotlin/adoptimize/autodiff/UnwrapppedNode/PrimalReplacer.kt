/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.UnwrapppedNode

import adoptimize.AutoDiffException
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.ReverseScalarClass
import adoptimize.autodiff.Metadata.StackClass
import adoptimize.autodiff.Metadata.unboxDScalarExpression
import adoptimize.autodiff.allArgumentTypes
import adoptimize.autodiff.getVariable
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.*
import pluginCommon.CopyAndReplacer
import pluginCommon.MapWrapper
import pluginCommon.ReplaceDelegate
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.overrideRoot

class PrimalReplacer(
    callGenerator: IrBodyGenerator,
    differentiableApi: DifferentiableApi,
    stackClass: StackClass,
    irBuiltIns: IrBuiltIns
) : NodeUnwrapper<IrClass>(callGenerator, differentiableApi, stackClass, irBuiltIns) {

    override fun copyAndUnboxStatements(
        unwrapPropertyInfo: UnwrapPropertyInfo,
        boxedSrc: ReverseScalarClass,
        newDispatchReceiverParameter: IrValueParameter,
        newParent: IrClass,
        boxedConstructorParamToOptUnboxedConstructorParams: MapWrapper<IrValueDeclaration, IrValueDeclaration>
    ): List<IrStatement> {
        val primalCopier = CopyAndReplacer(valueParameterSubsitutor = boxedConstructorParamToOptUnboxedConstructorParams, unwrapPropertyInfo.boxedPropertyToUnboxedProperty, irBuiltIns)
        val initializers = boxedSrc.clazz.declarations.filterIsInstance<IrAnonymousInitializer>()
        if (initializers.size > 1) {
            throw AutoDiffException("The unboxed node customizer expected exactly one initilizer. TODO: Make the Mappable Class more structured so these are stored rather than found")
        }
        var setPrimitiveProp: IrSetField? = null

        val primalStatements = initializers.first().body.statements.map {
            try {
                primalCopier.copyAndReplace(
                    it,
                    object : ReplaceDelegate {
                        override fun replaceCandidateWith(original: IrSetField, candidateCopy: IrSetField): IrSetField? {
                            val boxedProperty = original.symbol.owner.correspondingPropertySymbol!!.owner
                            return when {
                                // we are setting the primal property
                                boxedProperty.name == differentiableApi.reverseDiffScalarClass.primalProperty.name -> {
                                    val value: IrExpression = candidateCopy.value
                                    val variable = getVariable(value)
                                    val primalProperty = newParent.properties.firstOrNull { it.name == differentiableApi.reverseDiffScalarClass.primalProperty.name } ?: throw AutoDiffException("The primal property is missing from the shell class")
                                    val newField = primalProperty.backingField ?: throw AutoDiffException("The backing field of the primal property is missing")
                                    val boxedValue = callGenerator.generateConstructorCall(differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass, listOf(callGenerator.generateGetValue(variable)), emptyList())
                                    // HACK!! also set the primitive property. TODO: remove statefulness
                                    setPrimitiveProp = callGenerator.generateSetField(
                                        unwrapPropertyInfo.primitivePrimalProperty.backingField!!,
                                        value = callGenerator.generateGetValue(variable),
                                        newParent
                                    )

                                    callGenerator.generateSetField(newField, boxedValue, newParent)
                                }
                                else -> null
                            }
                        }

                        override fun replaceConstructorCall(
                            original: IrConstructorCall,
                            candidateCopy: IrConstructorCall
                        ): IrExpression? {
                            return if (candidateCopy.type == differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType) {
                                candidateCopy.getValueArgument(0)
                            } else {
                                null
                            }
                        }

                        override fun replaceCandidateWith(original: IrVariable, candidateCopy: IrVariable): IrVariable {
                            if (candidateCopy.initializer == null && original.type.isSubtypeOfClass(differentiableApi.scalarRoot.symbol)) {
                                candidateCopy.type = callLowerer.primitiveType
                            }
                            return candidateCopy
                        }

                        override fun replaceCandidateWith(original: IrCall, candidateCopy: IrCall): IrExpression? {
                            val isOOCall = (candidateCopy.symbol.owner as? IrSimpleFunction)?.overrideRoot() == differentiableApi.primalAndPullbackFunction.overrideRoot()
                            val isSetPrimalCall = ((candidateCopy.symbol.owner as? IrSimpleFunction)?.overrideRoot() == differentiableApi.reverseDiffScalarClass.setPrimal?.overrideRoot()) == true && differentiableApi.reverseDiffScalarClass.setPrimal != null
                            val noDifferentiableParameters = original.allArgumentTypes().none { it.isSubtypeOfClass(differentiableApi.rootDifferentiableType.classOrNull!!) }
                            return when {
                                isOOCall -> {
                                    throw NotImplementedError("TODO: replace the active variable argument with a boxed version of the active variable in this lambda call")
                                }
                                isSetPrimalCall -> {
                                    val value = candidateCopy.getValueArgument(0)!!
                                    val boxedValue = callGenerator.generateConstructorCall(differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass, listOf(value), emptyList())
                                    setPrimitiveProp = callGenerator.generateSetField(
                                        unwrapPropertyInfo.primitivePrimalProperty.backingField!!,
                                        value = callGenerator.generateGetValue(getVariable(value)),
                                        newParent
                                    )
                                    // TODO: generate fake overrides for the unwrapped node and call that function instead
                                    callGenerator.generateCall(candidateCopy.symbol.owner, listOf(boxedValue), candidateCopy.dispatchReceiver, candidateCopy.extensionReceiver)
                                }
                                noDifferentiableParameters && candidateCopy.type.isSubtypeOfClass(differentiableApi.scalarRoot.symbol) -> differentiableApi.unboxDScalarExpression(candidateCopy, callGenerator)
                                else -> with(callLowerer) { lowerFunction(original, candidateCopy, if (original.type.isSubtypeOfClass(differentiableApi.rootDifferentiableType.classifierOrFail as IrClassSymbol)) primitiveType else original.type) }
                            }
                        }
                    },
                    newParent
                ) as IrStatement
            } catch (e: AutoDiffException) {
                throw AutoDiffException("unbox failed for `${newParent.parent.kotlinFqName}` because statement `${it.render()}` could not be unboxed. Error: `${e.message}`")
            }
        }
        return primalStatements + listOf(setPrimitiveProp!!)
    }
}
