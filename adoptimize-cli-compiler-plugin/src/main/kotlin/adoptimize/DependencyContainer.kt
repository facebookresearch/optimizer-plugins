/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize

import adoptimize.autodiff.AutoDiffCodeWriterVendor
import adoptimize.autodiff.BackPropFunction.DiffIRCreator
import adoptimize.autodiff.BoxedReverseNodeCustomizer
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.StackClass
import adoptimize.autodiff.NodePopulation.CustomReverseNodePopulator
import adoptimize.autodiff.ReverseScalarClassCreator
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import pluginCommon.DependencyContainer
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.IrClassGenerator
import pluginCommon.generators.IrFunctionGenerator
import pluginCommon.generators.IrPropertyGenerator
import pluginCommon.lowerings.*

fun createAdOptimizeDependencyContainer(differentiableApi: DifferentiableApi, stackClass: StackClass, pluginContext: IrPluginContext): DependencyContainer {
    val container = DependencyContainer()
    with(container) {
        put<IrPluginContext>(pluginContext)
        put<IrBuiltIns>(pluginContext.irBuiltIns)
        val redundantVariableRemover = RedundantVariableRemover(
            setOf<IrType>(
                differentiableApi.reverseDiffScalarClass.clazz.defaultType,
                differentiableApi.rootDifferentiableType,
                differentiableApi.forwardDiffScalarClass.clazz.defaultType,
                differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType
            )
        )
        put(redundantVariableRemover)
        put(differentiableApi)
        put(stackClass)
        put<IrFunctionGenerator>()
        put<IrBodyGenerator>()
        put<IrPropertyGenerator>()
        put<IrClassGenerator>()
        put<ReverseScalarClassCreator>()
        put<DiffIRCreator>()
        put<AutoDiffCodeWriterVendor>()
        put<CustomReverseNodePopulator>()
        put<BoxedReverseNodeCustomizer>()
        put<UnnestLowering>()
        put<VariableWhenLowering>()
        put<UnitCastTransformer>()
        put<ElseBranchLowering>()
    }
    return container
}
