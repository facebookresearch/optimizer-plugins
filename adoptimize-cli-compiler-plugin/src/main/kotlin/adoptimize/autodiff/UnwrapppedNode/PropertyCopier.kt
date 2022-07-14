/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.UnwrapppedNode

import adoptimize.AutoDiffException
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.ParamMapType
import adoptimize.autodiff.Metadata.ReverseScalarClass
import adoptimize.autodiff.Metadata.StackClass
import adoptimize.autodiff.type
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrFakeOverrideProperty
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.Name
import pluginCommon.CopyAndReplacer
import pluginCommon.MapWrapper
import pluginCommon.ReplaceDelegate
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.IrPropertyGenerator
import pluginCommon.generators.overrideRoot

class PropertyCopier(
    val callGenerator: IrBodyGenerator,
    val differentiableApi: DifferentiableApi,
    val stackClass: StackClass,
    val propertyGenerator: IrPropertyGenerator,
    val irBuiltIns: IrBuiltIns
) {

    fun copyBoxedPropertiesIntoUnboxedClass(boxedConstructorParamToOptUnboxedConstructorParams: MapWrapper<IrValueDeclaration, IrValueDeclaration>, boxedConstructorParamToOptBoxedConstructorParams: Map<IrValueParameter, IrValueParameter>, mappableIntoClass: ReverseScalarClass, mappableFromClass: ReverseScalarClass): UnwrapPropertyInfo {
        val boxedPropertyToUnboxedProperty = MapWrapper(mutableMapOf<IrProperty, IrProperty>())
        val boxedNodePropertyToUnboxedNodeProperty = mutableMapOf<IrProperty, IrProperty>()
        val propertyCopier = CopyAndReplacer(boxedConstructorParamToOptUnboxedConstructorParams, boxedPropertyToUnboxedProperty, irBuiltIns)
        val intoClass = mappableIntoClass.clazz
        val fromClass = mappableFromClass.clazz
        val primitivePrimalProperty = propertyGenerator.generateProperty(Name.identifier("primitivePrimal"), differentiableApi.boxedPrimitiveInfo.primitiveType, intoClass, false, null)
        val propertiesToCopy = fromClass.properties.map { boxedProperty ->
            val propType = boxedProperty.type()
            val parameterMapMaybe = mappableFromClass.parameterMaps.firstOrNull { it.correspondingPropertyName == boxedProperty.name }
            val tpe = when {
                boxedProperty == mappableFromClass.intermediateVariables -> NodeParameterType.IntermediateValues
                boxedProperty == mappableFromClass.decisions -> NodeParameterType.Decisions
                parameterMapMaybe != null -> NodeParameterType.Injected
                differentiableApi.frameworkPropertyNames.contains(boxedProperty.name) || boxedProperty.isFakeOverride -> NodeParameterType.Framework
                propType == differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType -> NodeParameterType.BoxedPrimitive
                propType.isSubtypeOfClass(differentiableApi.rootDifferentiableType.classifier as IrClassSymbol) -> NodeParameterType.NotActiveDifferentiable
                else -> NodeParameterType.Constant
            }
            Pair(boxedProperty, tpe)
        }

        fun copyInit(original: IrProperty): IrExpression? {
            return original.backingField?.initializer?.let {
                propertyCopier.copyAndReplace(
                    it.expression,
                    object : ReplaceDelegate {
                        override fun replaceCandidateWith(
                            original: IrCall,
                            candidateCopy: IrCall
                        ): IrExpression? {
                            val otherBoxedProperty = original.symbol.owner.correspondingPropertySymbol?.owner
                            return if (original.symbol.owner.isPropertyAccessor && boxedNodePropertyToUnboxedNodeProperty.contains(otherBoxedProperty)) {
                                val unboxedProperty = boxedNodePropertyToUnboxedNodeProperty[otherBoxedProperty]!!
                                callGenerator.generateGetProperty(unboxedProperty, (candidateCopy as IrCall).dispatchReceiver!!)
                            } else {
                                null
                            }
                        }
                    },
                    intoClass
                ) as IrExpression
            }
        }

        propertiesToCopy.forEach { boxedPropertyPair ->
            val boxedProperty = boxedPropertyPair.first
            val propertyType = boxedPropertyPair.second
            when (propertyType) {
                NodeParameterType.Injected -> {
                    val srcParameterMap =
                        mappableFromClass.parameterMaps.first { it.correspondingPropertyName == boxedProperty.name }
                    val targetParameterMaps =
                        mappableIntoClass.parameterMaps.filter { it.functionIndex == srcParameterMap.functionIndex }
                    when (srcParameterMap.type) {
                        ParamMapType.CastToReverse -> {
                            val primitiveParameterMap = targetParameterMaps.first { it.type == ParamMapType.Unbox }
                            val wrappedParameterMap =
                                targetParameterMaps.first { it.type == ParamMapType.CastToReverse }
                            boxedNodePropertyToUnboxedNodeProperty[boxedProperty] =
                                intoClass.properties.first { it.name == wrappedParameterMap.correspondingPropertyName }
                            boxedPropertyToUnboxedProperty[boxedProperty] =
                                intoClass.properties.first { it.name == primitiveParameterMap.correspondingPropertyName }
                        }
                        else -> {
                            val targetParameterMap = if (targetParameterMaps.size == 1) {
                                targetParameterMaps.first()
                            } else {
                                throw AutoDiffException("${srcParameterMap.type} not found in ${intoClass.name}")
                            }
                            boxedPropertyToUnboxedProperty[boxedProperty] =
                                intoClass.properties.first { it.name == targetParameterMap.correspondingPropertyName }
                        }
                    }
                }
                NodeParameterType.NotActiveDifferentiable, NodeParameterType.BoxedPrimitive -> {
                    boxedPropertyToUnboxedProperty[boxedProperty] = propertyGenerator.generateProperty(boxedProperty.name, differentiableApi.boxedPrimitiveInfo.primitiveType, intoClass, false, copyInit(boxedProperty))
                }
                NodeParameterType.IntermediateValues -> {
                    boxedPropertyToUnboxedProperty[boxedProperty] = mappableIntoClass.intermediateVariables
                }
                NodeParameterType.Decisions -> {
                    boxedPropertyToUnboxedProperty[boxedProperty] = mappableIntoClass.decisions
                }
                NodeParameterType.Constant, NodeParameterType.Framework -> {
                    if (boxedProperty.isFakeOverride) {
                        val overridenProperty = boxedProperty.getter!!.overriddenSymbols.first().owner.correspondingPropertySymbol!!.owner
                        val copiedProperty = propertyGenerator.generateFakeOverrideProperty(intoClass, overridenProperty)
                        boxedPropertyToUnboxedProperty[boxedProperty] = copiedProperty
                    } else {
                        val overriddenProperty = boxedProperty.getter!!.overriddenSymbols.firstOrNull()?.owner?.correspondingPropertySymbol?.owner
                        val copiedProperty = propertyGenerator.generateProperty(boxedProperty.name, boxedProperty.type(), intoClass, false, copyInit(boxedProperty))
                        overriddenProperty?.let {
                            copiedProperty.getter!!.overriddenSymbols = listOf(it.getter!!.symbol)
                        }
                        // if there is an explicit override, remove it
                        val fakeOverride = intoClass.properties.filterIsInstance<IrFakeOverrideProperty>().firstOrNull { it.getter!!.overrideRoot() == boxedProperty.getter!!.overrideRoot() }
                        fakeOverride?.let {
                            intoClass.declarations.remove(fakeOverride)
                        }
                        boxedPropertyToUnboxedProperty[boxedProperty] = copiedProperty
                    }
                }
            }
        }
        return UnwrapPropertyInfo(primitivePrimalProperty, boxedPropertyToUnboxedProperty, boxedNodePropertyToUnboxedNodeProperty)
    }
}
