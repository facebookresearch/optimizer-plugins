/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmSerializeIrMode
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

class DifferentiablePreprocessorConfigurator(testServices: TestServices, val resourcesDirectoryFromTestFile: (File) -> File) : EnvironmentConfigurator(testServices) {
    private val rt: File
    private val annotations: File
    init {
        val javaHome = System.getenv("JAVA_HOME")
        rt = File(javaHome, "jre/lib/rt.jar")
        annotations = File(javaHome, "jre/lib/annotations.jar")
    }

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        super.configureCompilerConfiguration(configuration, module)
        configuration.put(JVMConfigurationKeys.IR, true)
        configuration.put(JVMConfigurationKeys.SERIALIZE_IR, JvmSerializeIrMode.INLINE)
        val contentRoots: MutableList<ContentRoot> = configuration[CLIConfigurationKeys.CONTENT_ROOTS]?.let { val l = mutableListOf<ContentRoot>(); l.addAll(it); l } ?: mutableListOf<ContentRoot>()
        contentRoots.add(JvmClasspathRoot(rt))
        contentRoots.add(JvmClasspathRoot(annotations))
        configuration.put(CLIConfigurationKeys.CONTENT_ROOTS, contentRoots)
        if (configuration[ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS] == null) {
            configuration.put(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, mutableListOf(DifferentiableApiPreprocessorComponentRegistrar()))
        } else {
            configuration[ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS]?.plusAssign(DifferentiableApiPreprocessorComponentRegistrar())
        }

        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.BOXED_PRIMITIVE_ANNOTATION_CONFIGKEY, listOf("demo.BoxedPrimitive"))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.PULLBACK_ANNOTATION_CONFIGKEY, listOf("demo.PrimalAndPullback"))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.REVERSE_ANNOTATION_CONFIGKEY, listOf("demo.ReverseDifferentiable"))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.SCALAR_ROOT_ANNOTATION_CONFIGKEY, listOf("demo.ScalarRoot"))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.STACKIMPL_ANNOTATION_CONFIGKEY, listOf("demo.StackImpl"))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.UNBOXED_FUNCTION_ANNOTATION_CONFIGKEY, listOf("demo.ToUnboxedFunction"))
        val resourceDirectory = resourcesDirectoryFromTestFile(module.files.first().originalFile)
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.RESOURCES_PATH_CONFIGKEY, listOf(resourceDirectory.absolutePath))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.TO_REVERSE_ANNOTATION_CONFIGKEY, listOf("demo.ToReverse"))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.DTENSOR_ROOT_CONFIGKEY, listOf("demo.DTensorRoot"))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.REVERSE_OPERATIONS_CONFIGKEY, listOf("demo.ReverseOperations"))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.SCALAR_NOOP_CONFIGKEY, listOf("demo.ScalarNoop"))
        configuration.put(DifferentiableApiPreprocessorConfigurationKeys.FORWARD_ANNOTATION_CONFIGKEY, listOf("demo.ForwardDifferentiable"))
    }
}
