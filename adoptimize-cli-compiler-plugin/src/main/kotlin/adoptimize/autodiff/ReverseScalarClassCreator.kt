/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff

import adoptimize.AutoDiffException
import adoptimize.autodiff.Metadata.ActiveParameterRequirement
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.ParamMapType
import adoptimize.autodiff.Metadata.ParameterMap
import adoptimize.autodiff.Metadata.ReverseScalarClass
import adoptimize.autodiff.Metadata.StackClass
import adoptimize.autodiff.Metadata.scalarZero
import adoptimize.autodiff.diffIR.DiffIRFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name
import pluginCommon.generators.*
import pluginCommon.lowerings.RedundantVariableRemover
import pluginCommon.lowerings.UnnestLowering

interface ReverseNodeCustomizer {
    fun name(primalName: String): String
    fun populate(primalFunction: DiffIRFunction, shellClass: ReverseScalarClass)
    fun buildParameterInfos(originValueParameter: ParameterWithIndex): List<Pair<ParameterInfo, ParameterMap>>
    fun typeRequirements(): List<ActiveParameterRequirement>
}

class ReverseScalarClassCreator(
    val classGenerator: IrClassGenerator,
    val propertyGenerator: IrPropertyGenerator,
    val callGenerator: IrBodyGenerator,
    val functionGenerator: IrFunctionGenerator,
    val differentiableApi: DifferentiableApi,
    val unnestLowering: UnnestLowering,
    val redundantVariableRemover: RedundantVariableRemover,
    val pluginContext: IrPluginContext,
    val stackClass: StackClass
) {
    private val lowerings = listOf(unnestLowering, redundantVariableRemover)

    fun createMappableReverseNode(reverseNodeCustomizer: ReverseNodeCustomizer, primalFunction: DiffIRFunction, activeParameter: IrValueParameter): ReverseScalarClass {
        val parentReverseClass = differentiableApi.reverseDiffScalarClass.clazz

        val constructorParameters = mutableListOf<ParameterInfo>()
        val parameterMaps = mutableListOf<ParameterMap>()
        primalFunction.getParametersWithIndex().forEach { originValueParameter ->
            val parameterInfos = reverseNodeCustomizer.buildParameterInfos(originValueParameter)
            parameterInfos.forEach {
                val parameterInfo = ParameterInfo(if (it.first.name.isSpecial) Name.identifier(correctSpecializedNames(it.first.name.toString())) else it.first.name, it.first.tpe)
                constructorParameters.add(parameterInfo)
                parameterMaps.add(it.second)
            }
        }
        if (parameterMaps.filter { it.type == ParamMapType.CastToReverse }.size > 1) {
            throw AutoDiffException("Only one potentially active parameter type currently supported. Problem: ${primalFunction.original.name}")
        }

        var reverseScalarClass: ReverseScalarClass? = null
        classGenerator.generateLocalClass(
            primalFunction.original,
            reverseNodeCustomizer.name(primalFunction.original.name.toString()),
            listOf(parentReverseClass.defaultType),
            constructorParameters,
            { constructor ->
                val getDerivativeID = callGenerator.generateGetProperty(
                    callGenerator.generateGetValue(activeParameter),
                    Name.identifier("derivativeID")
                )
                val derivativeIDType = differentiableApi.reverseDiffScalarClass.derivativeId.getter!!.returnType
                val derivativeIDArgument = if (getDerivativeID.type != derivativeIDType &&
                    derivativeIDType.isSubtypeOfClass(getDerivativeID.type.classifierOrFail as IrClassSymbol)
                ) {
                    callGenerator.generateCast(getDerivativeID, derivativeIDType)
                } else { getDerivativeID }
                val dummyPrimal = differentiableApi.scalarZero()
                callGenerator.generateDelegatingConstructorCall(
                    differentiableApi.reverseDiffScalarClass.clazz,
                    listOf(dummyPrimal, derivativeIDArgument),
                    emptyList()
                )
            },
            { generatedReverseClass: IrClass ->
                val backprop = functionGenerator.generateOverride(
                    differentiableApi.reverseDiffScalarClass.backpropMethod,
                    generatedReverseClass
                ) { }
                val activeProperties = mutableListOf<IrProperty>()
                backprop.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, emptyList())
                val primaryConstructor = generatedReverseClass.primaryConstructor ?: throw AutoDiffException("The synthetic class ${generatedReverseClass.name} was created without a primary constructor.")
                parameterMaps
                    .filter { it.correspondingPropertyName != null }
                    .forEachIndexed { index, parameterMap ->
                        val parentParameter = when (parameterMap.functionIndex) {
                            -2 -> primalFunction.original.extensionReceiverParameter!!
                            -1 -> primalFunction.original.dispatchReceiverParameter!!
                            else -> primalFunction.original.valueParameters[parameterMap.functionIndex]
                        }
                        val propertyType = when (parameterMap.type) {
                            ParamMapType.CastToReverse -> differentiableApi.reverseDiffScalarClass.clazz.defaultType
                            ParamMapType.ConstPrimalPrimal, ParamMapType.Unbox -> differentiableApi.boxedPrimitiveInfo.primitiveType
                            ParamMapType.ForwardPrimal -> differentiableApi.forwardDiffScalarClass.clazz.defaultType
                            ParamMapType.NoOp -> parentParameter.type
                        }
                        val property = propertyGenerator.generateProperty(
                            name = parameterMap.correspondingPropertyName!!,
                            type = propertyType,
                            containingClass = generatedReverseClass,
                            isSettable = false,
                            initializer = callGenerator.generateGetValue(primaryConstructor.valueParameters[index]),
                            parent = null
                        )
                        if (parameterMap.isActive) {
                            activeProperties.add(property)
                        }
                    }
                fun makeStackProperty(type: IrSimpleTypeImpl, name: String): IrProperty {
                    val typeArguments = listOf(type)
                    val decisionType = IrSimpleTypeImpl(stackClass.clazz.symbol, false, typeArguments, emptyList(), null)
                    val constructorCall = callGenerator.generateConstructorCall(stackClass.clazz, emptyList(), typeArguments)
                    return propertyGenerator.generateProperty(Name.identifier(name), decisionType, generatedReverseClass, false, constructorCall)
                }

                val decisionsProperty = makeStackProperty(pluginContext.irBuiltIns.intType as IrSimpleTypeImpl, "\$decisions")
                val intermediateVariables = makeStackProperty(pluginContext.irBuiltIns.anyType as IrSimpleTypeImpl, "\$intermediateValues")
                reverseScalarClass = ReverseScalarClass(
                    parent = primalFunction.original,
                    clazz = generatedReverseClass,
                    parameterMaps = parameterMaps,
                    activeProperties = activeProperties,
                    intermediateVariables = intermediateVariables,
                    decisions = decisionsProperty,
                    activeInputTypeRequirements = reverseNodeCustomizer.typeRequirements()
                )
                reverseNodeCustomizer.populate(primalFunction, reverseScalarClass!!)
                generatedReverseClass.declarations.forEach {
                    when (it) {
                        is IrAnonymousInitializer -> lowerings.forEach { lowering -> lowering.lower(it) }
                        is IrSimpleFunction -> lowerings.forEach { lowering -> lowering.lower(it) }
                    }
                }
            }
        )
        return reverseScalarClass!!
    }
}
