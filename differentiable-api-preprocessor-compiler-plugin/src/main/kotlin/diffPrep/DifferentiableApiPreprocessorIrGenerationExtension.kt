/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import diffPrep.metadata.DifferentiableApiBuilder
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.lowerings.ClassLifter
import java.io.File

class DifferentiableApiPreprocessorIrGenerationExtension(
    reverse: String,
    forward: String,
    stackImpl: String,
    primalAndPullback: String,
    boxedPrimitiveName: String,
    unboxedFunctionName: String,
    scalarRoot: String,
    toReverse: String,
    rootPath: String,
    dTensor: String,
    operations: String,
    val scalarNoop: String
) : IrGenerationExtension {
    private val unboxedFunctionName = FqName(unboxedFunctionName)
    private val toReverseFqName = FqName(toReverse)
    private val rootPath = File(rootPath)

    private val differentiableApiBuilder = DifferentiableApiBuilder(
        fqReverseNodeName = FqName(reverse),
        fqForwardNodeName = FqName(forward),
        fqPrimalAndPullbackName = FqName(primalAndPullback),
        boxedPrimitiveFqName = FqName(boxedPrimitiveName),
        scalarRootFqName = FqName(scalarRoot),
        dTensorAnnotationFqName = FqName(dTensor),
        operationsFqName = FqName(operations),
        stackImplName = FqName(stackImpl),
        scalarNoopFqName = FqName(scalarNoop)
    )

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val messageLogger = pluginContext.createDiagnosticReporter("diffPrep")
        val differentiableApi = differentiableApiBuilder.differentiableApi(moduleFragment, messageLogger)
        if (differentiableApi == null) {
            messageLogger.report(
                IrMessageLogger.Severity.WARNING,
                "No differentiable API found in module ${moduleFragment.name}.",
                IrMessageLogger.Location("", UNDEFINED_OFFSET, UNDEFINED_OFFSET)
            )
        } else {
            val stackClass = differentiableApi.stackClass
            if (!rootPath.exists()) {
                rootPath.mkdirs()
            }
            val file = File(rootPath, adOptimizeCommon.propertiesFileName)
            file.printWriter().use { out ->
                out.println("${adOptimizeCommon.reverseClass}=${differentiableApi.reverseDifferentiableScalar.clazz.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.forwardClass}=${differentiableApi.forwardDifferentiableScalar.clazz.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.primalProperty}=${differentiableApi.primalProperty.name}")
                out.println("${adOptimizeCommon.upstreamProperty}=${differentiableApi.reverseDifferentiableScalar.upstreamProperty.name}")
                out.println("${adOptimizeCommon.tangentProperty}=${differentiableApi.forwardDifferentiableScalar.tangentProperty.name}")
                out.println("${adOptimizeCommon.backpropMethod}=${differentiableApi.reverseDifferentiableScalar.backpropMethod.name}")
                out.println("${adOptimizeCommon.pushbackMethod}=${differentiableApi.reverseDifferentiableScalar.pushbackMethod.name}")
                out.println("${adOptimizeCommon.derivativeId}=${differentiableApi.derivativeId.name}")
                out.println("${adOptimizeCommon.scalarRoot}=${differentiableApi.scalarRoot.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.primalAndPullbackFunction}=${differentiableApi.primalAndPullbackFunction.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.boxedPrimitive}=${differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.valueProperty}=${differentiableApi.boxedPrimitiveInfo.valueProperty.name}")
                out.println("${adOptimizeCommon.primitiveType}=${(differentiableApi.boxedPrimitiveInfo.primitiveType.classifierOrFail.owner as IrClass).name}")
                out.println("${adOptimizeCommon.stackImpl}=${stackClass.clazz.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.scalarPlusFunction}=${differentiableApi.tensorPlusFunction.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.tensorPlusFunction}=${differentiableApi.scalarPlusFunction.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.toUnboxFunction}=$unboxedFunctionName")
                out.println("${adOptimizeCommon.toReverse}=$toReverseFqName")
                out.println("${adOptimizeCommon.dTensor}=${differentiableApi.dTensorRoot.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.reverseOperations}=${differentiableApi.reverseOperations.fqNameForIrSerialization}")
                out.println("${adOptimizeCommon.scalarZero}=${differentiableApi.boxedPrimitiveInfo.companionZero.fqNameWhenAvailable}")
                out.println("${adOptimizeCommon.scalarOne}=${differentiableApi.boxedPrimitiveInfo.companionOne.fqNameWhenAvailable}")
                out.println("${adOptimizeCommon.scalarNoop}=${this.scalarNoop}")
            }

            // perform lowerings
            val bodyGenerator = IrBodyGenerator(pluginContext)
            ClassLifter(pluginContext, messageLogger).liftClassesIn(
                differentiableApi.reverseOperations,
                DiffPrepClassLifterDelegate(
                    differentiableApi
                )
            )
            val backpropMethodLowerings = listOf(pluginCommon.lowerings.UnnestLowering(bodyGenerator))
            moduleFragment.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitClass(declaration: IrClass) {
                    if (declaration.isSubclassOf(differentiableApi.reverseDifferentiableScalar.clazz)) {
                        val backpropMethod = declaration.simpleFunctions().firstOrNull { it.overrides(differentiableApi.reverseDifferentiableScalar.backpropMethod) }
                            ?: throw DiffApiPrepException("${declaration.name} inherits from ${differentiableApi.reverseDifferentiableScalar.clazz.name} but does not implement ${differentiableApi.reverseDifferentiableScalar.backpropMethod.name}")
                        if (!backpropMethod.isInline) {
                            messageLogger.report(
                                IrMessageLogger.Severity.WARNING,
                                "Please inline ${declaration.name}.${backpropMethod.name} to enable AD optimization.",
                                IrMessageLogger.Location("", UNDEFINED_OFFSET, UNDEFINED_OFFSET)
                            )
                        } else {
                            backpropMethodLowerings.forEach { it.lower(backpropMethod) }
                            messageLogger.report(
                                IrMessageLogger.Severity.INFO,
                                "Lowered ${declaration.name}.${backpropMethod.name}",
                                IrMessageLogger.Location("", UNDEFINED_OFFSET, UNDEFINED_OFFSET)
                            )
                        }
                    }
                }
            })
        }
    }
}
