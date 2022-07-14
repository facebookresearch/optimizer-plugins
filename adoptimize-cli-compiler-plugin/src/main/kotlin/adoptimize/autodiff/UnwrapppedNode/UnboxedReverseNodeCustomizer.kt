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
import adoptimize.autodiff.UnwrapppedNode.BackpropReplacer
import adoptimize.autodiff.UnwrapppedNode.PrimalReplacer
import adoptimize.autodiff.UnwrapppedNode.PropertyCopier
import adoptimize.autodiff.diffIR.DiffIRFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrAnonymousInitializerImpl
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import pluginCommon.CopyAndReplacer
import pluginCommon.MapWrapper
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.IrPropertyGenerator
import pluginCommon.generators.ParameterInfo
import pluginCommon.generators.overrideRoot
import kotlin.collections.IndexedValue
import kotlin.collections.List
import kotlin.collections.filter
import kotlin.collections.firstOrNull
import kotlin.collections.map
import kotlin.collections.mutableListOf
import kotlin.collections.toMap
import kotlin.collections.toMutableMap
import kotlin.collections.withIndex

class UnboxedReverseNodeCustomizer(
    val differentiableApi: DifferentiableApi,
    val stackClass: StackClass,
    val boxedReverseScalarClass: ReverseScalarClass,
    val pluginContext: IrPluginContext,
    val callGenerator: IrBodyGenerator,
    val propertyGenerator: IrPropertyGenerator
) : ReverseNodeCustomizer {
    private val propertyCopier = PropertyCopier(callGenerator, differentiableApi, stackClass, propertyGenerator, pluginContext.irBuiltIns)
    private val backpropReplacer = BackpropReplacer(callGenerator, differentiableApi, stackClass, pluginContext.irBuiltIns)
    private val primalReplacer = PrimalReplacer(callGenerator, differentiableApi, stackClass, pluginContext.irBuiltIns)

    override fun buildParameterInfos(originValueParameterWithIndex: ParameterWithIndex): List<Pair<ParameterInfo, ParameterMap>> {
        val (originValueParameter, index) = originValueParameterWithIndex
        val parameterInfos = mutableListOf<Pair<ParameterInfo, ParameterMap>>()
        val reverseType = differentiableApi.reverseDiffScalarClass.clazz.defaultType
        val baseName = correctSpecializedNames(originValueParameterWithIndex.valueDescriptor.name.toString())
        when {
            reverseType.isSubtypeOfClass(originValueParameter.type.getClass()!!.symbol) -> {
                val parameterInfo1 = ParameterInfo(Name.identifier("${baseName}Node"), reverseType)
                val parameterInfo2 = ParameterInfo(Name.identifier("${baseName}Unboxed"), differentiableApi.boxedPrimitiveInfo.primitiveType)

                val parameterMap1 = ParameterMap(index, ParamMapType.CastToReverse, parameterInfo1.name, false)
                val parameterMap2 = ParameterMap(index, ParamMapType.Unbox, parameterInfo2.name, true)
                parameterInfos.add(Pair(parameterInfo1, parameterMap1))
                parameterInfos.add(Pair(parameterInfo2, parameterMap2))
            }
            originValueParameter.type.getClass() == differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass -> {
                val parameterInfo1 = ParameterInfo(Name.identifier("${baseName}Unboxed"), differentiableApi.boxedPrimitiveInfo.primitiveType)
                val parameterMap1 = ParameterMap(index, ParamMapType.Unbox, parameterInfo1.name, false)
                parameterInfos.add(Pair(parameterInfo1, parameterMap1))
            }
            else -> {
                parameterInfos.add(
                    Pair(
                        ParameterInfo(Name.identifier(baseName), originValueParameter.type),
                        ParameterMap(index, ParamMapType.NoOp, originValueParameter.name, false)
                    )
                )
            }
        }
        return parameterInfos
    }

    override fun typeRequirements(): List<ActiveParameterRequirement> = listOf(ActiveParameterRequirement.Reverse, ActiveParameterRequirement.Constant)

    override fun name(primalName: String) = "${primalName}ReverseUnboxed"

    // this method populates the shell class, using the boxed reverse node as a source
    override fun populate(primalFunction: DiffIRFunction, shellClass: ReverseScalarClass) {
        // start with an initial substitution map by mapping the constructor parameters of the boxed reverse node to the constructor parameters of the unboxed reverse node
        val boxedConstructorParamToOptUnboxedConstructorParams: MapWrapper<IrValueDeclaration, IrValueDeclaration> = MapWrapper(
            boxedReverseScalarClass.parameterMaps.withIndex().map { (index, boxedParameterMap) ->
                val src = boxedReverseScalarClass.clazz.primaryConstructor!!.valueParameters[index]
                val candidates = shellClass.parameterMaps.withIndex().filter { it.value.functionIndex == boxedParameterMap.functionIndex }
                if (candidates.size == 0) {
                    throw AutoDiffException("No parameter map found for index ${boxedParameterMap.functionIndex}")
                }
                val image = when (boxedParameterMap.type) {
                    ParamMapType.CastToReverse -> {
                        val imageParameterMap: IndexedValue<ParameterMap> = candidates.firstOrNull { indexedUnboxedParameterMap -> indexedUnboxedParameterMap.value.type == ParamMapType.Unbox }
                            ?: throw AutoDiffException("The shell class does not have an unboxed parameter that corresponds with the boxed parameter ${boxedReverseScalarClass.clazz.primaryConstructor!!.valueParameters[index].render()}")
                        shellClass.clazz.primaryConstructor!!.valueParameters[imageParameterMap.index]
                    }
                    ParamMapType.Unbox -> {
                        throw AutoDiffException("A Boxed Reverse Node does not unbox its arguments")
                    }
                    ParamMapType.NoOp -> {
                        if (candidates.size > 1) {
                            throw AutoDiffException("There should be exactly one shell class parameter corresponding to a non active parameter of the boxed node. Problem: ${boxedReverseScalarClass.clazz.primaryConstructor!!.valueParameters[index].render()}")
                        }
                        shellClass.clazz.primaryConstructor!!.valueParameters[candidates.first().index]
                    }
                    else -> throw AutoDiffException("${boxedParameterMap.type} not supported in unbox")
                }
                Pair(src, image)
            }.toMap().toMutableMap()
        )
        boxedConstructorParamToOptUnboxedConstructorParams[boxedReverseScalarClass.clazz.thisReceiver!!] = shellClass.clazz.thisReceiver!!
        val boxedConstructorParamToOptBoxedConstructorParams = boxedReverseScalarClass.parameterMaps.withIndex().filter { it.value.type == ParamMapType.CastToReverse }.map { boxedParameterMap ->
            val imageParameterMap: IndexedValue<ParameterMap> = shellClass.parameterMaps.withIndex().firstOrNull { it.value.functionIndex == boxedParameterMap.value.functionIndex && it.value.type == ParamMapType.CastToReverse }
                ?: throw AutoDiffException("No boxed version of the parameter was found in the optimized unboxed reverse node shell")
            val image = shellClass.clazz.primaryConstructor!!.valueParameters[imageParameterMap.index]
            val src = boxedReverseScalarClass.clazz.primaryConstructor!!.valueParameters[boxedParameterMap.index]
            Pair(src, image)
        }.toMap()

        val targetbackPropStatementMethod = shellClass.clazz.functions.firstOrNull { it.overrideRoot() == (differentiableApi.reverseDiffScalarClass.backpropMethod as IrSimpleFunction).overrideRoot() } ?: throw AutoDiffException("No backprop method found on the unboxed node")
        val propertyInfo = propertyCopier.copyBoxedPropertiesIntoUnboxedClass(boxedConstructorParamToOptUnboxedConstructorParams, boxedConstructorParamToOptBoxedConstructorParams, shellClass, boxedReverseScalarClass)
        val primalStatements = primalReplacer.copyAndUnboxStatements(propertyInfo, boxedReverseScalarClass, shellClass.clazz.thisReceiver!!, shellClass.clazz, boxedConstructorParamToOptUnboxedConstructorParams)
        val unboxedInit = IrAnonymousInitializerImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, IrDeclarationOrigin.DEFINED, IrAnonymousInitializerSymbolImpl(), false).also {
            it.parent = shellClass.clazz
            it.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, primalStatements)
        }
        shellClass.clazz.declarations.add(unboxedInit)
        val statements = backpropReplacer.copyAndUnboxStatements(propertyInfo, boxedReverseScalarClass, targetbackPropStatementMethod.dispatchReceiverParameter!!, targetbackPropStatementMethod, boxedConstructorParamToOptUnboxedConstructorParams)
        targetbackPropStatementMethod.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, statements)

        // IF_DEBUG
        shellClass.clazz.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitExpression(expression: IrExpression) {
                if (expression.type == CopyAndReplacer.errorType) {
                    throw AutoDiffException("An uncorrected error type was found in ${expression.type}")
                }
                expression.acceptChildrenVoid(this)
            }
        })
    }
}
