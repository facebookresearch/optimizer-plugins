/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.BOXED_PRIMITIVE_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.DTENSOR_ROOT_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.FORWARD_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.PULLBACK_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.RESOURCES_PATH_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.REVERSE_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.REVERSE_OPERATIONS_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.SCALAR_NOOP_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.SCALAR_ROOT_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.STACKIMPL_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.TO_REVERSE_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.UNBOXED_FUNCTION_ANNOTATION_CONFIGKEY
import diffPrep.analysisHandler.DifferentiableApiPreprocessorAnalysisHandlerExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.extensions.CompilerConfigurationExtension
import org.jetbrains.kotlin.resolve.extensions.AnalysisHandlerExtension

class DifferentiableApiPreprocessorComponentRegistrar : ComponentRegistrar {
    override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
        fun stringFromList(key: CompilerConfigurationKey<List<String>>): String {
            val singletonList = configuration[key]
                ?: throw IllegalStateException("Plugin requires an `$key` annotation key")
            val annotationName = if (singletonList.size == 1) singletonList.first() else throw IllegalStateException("Plugin requires a single annotation $key but received ${singletonList.size}")
            return annotationName
        }

        val pb_annotationName = stringFromList(PULLBACK_ANNOTATION_CONFIGKEY)
        val rev_annotationName = stringFromList(REVERSE_ANNOTATION_CONFIGKEY)
        val forward_annotationName = stringFromList(FORWARD_ANNOTATION_CONFIGKEY)
        val stackImplName = stringFromList(STACKIMPL_ANNOTATION_CONFIGKEY)
        val boxedPrimitiveName = stringFromList(BOXED_PRIMITIVE_ANNOTATION_CONFIGKEY)
        val unboxedFunctionName = stringFromList(UNBOXED_FUNCTION_ANNOTATION_CONFIGKEY)
        val scalarRootName = stringFromList(SCALAR_ROOT_ANNOTATION_CONFIGKEY)
        val toReverseName = stringFromList(TO_REVERSE_ANNOTATION_CONFIGKEY)
        val rootPath = stringFromList(RESOURCES_PATH_CONFIGKEY)
        val dTensor = stringFromList(DTENSOR_ROOT_CONFIGKEY)
        val operations = stringFromList(REVERSE_OPERATIONS_CONFIGKEY)
        val scalarNoop = stringFromList(SCALAR_NOOP_CONFIGKEY)

        registerExtensions(project, rev_annotationName, forward_annotationName, stackImplName, pb_annotationName, boxedPrimitiveName, unboxedFunctionName, scalarRootName, toReverseName, rootPath, dTensor, operations, scalarNoop)
    }

    fun registerExtensions(
        project: Project,
        reverse: String,
        forward: String,
        stackImplFqName: String,
        primalAndPullbackFunctionName: String,
        boxedPrimitive: String,
        unboxedFunctionName: String,
        scalarRoot: String,
        toReverseName: String,
        rootPath: String,
        dTensor: String,
        operations: String,
        scalarNoop: String
    ) {
        CompilerConfigurationExtension.registerExtension(project, DifferentiableApiPreprocessorCompilerConfigurationExtension())
        AnalysisHandlerExtension.registerExtension(project, DifferentiableApiPreprocessorAnalysisHandlerExtension(boxedPrimitive, reverse, unboxedFunctionName, scalarRoot, dTensor))
        IrGenerationExtension.registerExtension(project, DifferentiableApiPreprocessorIrGenerationExtension(reverse, forward, stackImplFqName, primalAndPullbackFunctionName, boxedPrimitive, unboxedFunctionName, scalarRoot, toReverseName, rootPath, dTensor, operations, scalarNoop))
    }
}
