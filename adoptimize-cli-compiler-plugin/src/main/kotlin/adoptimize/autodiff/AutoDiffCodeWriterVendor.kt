/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff

import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.NodeCodeCopy.AutoDiffCodeWriterImpl
import adoptimize.autodiff.diffIR.DiffIRFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.IrFunctionGenerator

class AutoDiffCodeWriterVendor(
    val callGenerator: IrBodyGenerator,
    val differentiableApi: DifferentiableApi,
    val functionGenerator: IrFunctionGenerator,
    val context: IrPluginContext
) {
    fun codeWriter(primalFunction: DiffIRFunction): AutoDiffCodeWriter = AutoDiffCodeWriterImpl(
        callGenerator, differentiableApi, primalFunction,
        functionGenerator, context
    )
}
