/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff

import adoptimize.autodiff.Metadata.ActiveParameterRequirement
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.ParamMapType
import adoptimize.autodiff.Metadata.ParameterMap
import adoptimize.autodiff.Metadata.ReverseScalarClass
import adoptimize.autodiff.NodePopulation.CustomReverseNodePopulator
import adoptimize.autodiff.diffIR.DiffIRFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.Name
import pluginCommon.generators.ParameterInfo

class BoxedReverseNodeCustomizer(val differentiableApi: DifferentiableApi, val populator: CustomReverseNodePopulator) : ReverseNodeCustomizer {
    override fun buildParameterInfos(originValueParameter: ParameterWithIndex): List<Pair<ParameterInfo, ParameterMap>> {
        val type = originValueParameter.valueDescriptor.type.classifierOrFail as IrClassSymbol
        val baseName = correctSpecializedNames(originValueParameter.valueDescriptor.name.toString())
        return if (differentiableApi.reverseDiffScalarClass.clazz.defaultType.isSubtypeOfClass(type)) {
            val parameterInfo = ParameterInfo(Name.identifier("${baseName}Node"), differentiableApi.reverseDiffScalarClass.clazz.defaultType)
            val parameterMap = ParameterMap(originValueParameter.index, ParamMapType.CastToReverse, parameterInfo.name, true)
            listOf(Pair(parameterInfo, parameterMap))
        } else {
            val parameterInfo = ParameterInfo(Name.identifier(baseName), originValueParameter.valueDescriptor.type)
            val parameterMap = ParameterMap(originValueParameter.index, ParamMapType.NoOp, parameterInfo.name, false)
            listOf(Pair(parameterInfo, parameterMap))
        }
    }

    override fun typeRequirements(): List<ActiveParameterRequirement> = listOf(ActiveParameterRequirement.Reverse)

    override fun name(primalName: String): String = "${primalName}Reverse"

    override fun populate(primalFunction: DiffIRFunction, shellClass: ReverseScalarClass) {
        populator.populate(shellClass, primalFunction)
    }
}
