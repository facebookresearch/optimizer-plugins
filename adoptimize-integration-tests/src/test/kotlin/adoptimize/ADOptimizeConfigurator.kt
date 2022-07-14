/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize

import config.BuildConfig
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
import java.lang.IllegalStateException

class ADOptimizeConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    private val rt: File

    companion object {
        private val diffKtGroup = "org.diffkt.adopt"
        private val diffKtArtifactId = "api"
        private val diffKtVersion = BuildConfig.AD_DIFFKT_VERSION

        fun mavenJar(group: String, artifactId: String, version: String): File {
            val usrHome = System.getProperty("user.home")
            val groupParts = group.replace(".", "/")
            val localMaven = File(usrHome, ".m2/repository/$groupParts/$artifactId/$version/$artifactId-$version.jar")
            val jarFile = when {
                localMaven.exists() -> localMaven
                else -> {
                    val gradleCache = File(usrHome, ".gradle/caches/modules-2/files-2.1/$group/$artifactId/$version")
                    val jarName = "$artifactId-$version.jar"
                    val needle = gradleCache.listFiles()?.firstOrNull { file ->
                        File(file, jarName).exists()
                    }
                    if (needle == null) {
                        throw IllegalArgumentException("missing diffkt dependency. Please install to local maven or gradle cache.")
                    } else {
                        File(needle, jarName)
                    }
                }
            }
            return jarFile
        }

        val diffApiJar: File = mavenJar(diffKtGroup, diffKtArtifactId, diffKtVersion)
        val kotlinReflect: File = mavenJar("org.jetbrains.kotlin/", "kotlin-reflect", BuildConfig.KOTLIN_VERSION)
    }

    init {
        val javaHome = System.getenv("JAVA_HOME")
        if (javaHome == null) {
            throw IllegalStateException("JAVA_HOME environment variable is not set. Please set the environment variable before running the integration tests")
        }
        rt = File(javaHome, "jre/lib/rt.jar")
        if (!rt.exists()) {
            throw AutoDiffException("runtime jar not found in $javaHome")
        }
    }

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        super.configureCompilerConfiguration(configuration, module)
        configuration.put(JVMConfigurationKeys.IR, true)
        configuration.put(JVMConfigurationKeys.SERIALIZE_IR, JvmSerializeIrMode.INLINE)
        val contentRoots: MutableList<ContentRoot> = configuration[CLIConfigurationKeys.CONTENT_ROOTS]?.let { val l = mutableListOf<ContentRoot>(); l.addAll(it); l } ?: mutableListOf<ContentRoot>()
        contentRoots.add(JvmClasspathRoot(rt))
        contentRoots.add(JvmClasspathRoot(diffApiJar))
        contentRoots.add(JvmClasspathRoot(kotlinReflect))
        configuration.put(CLIConfigurationKeys.CONTENT_ROOTS, contentRoots)
        if (configuration[ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS] == null) {
            configuration.put(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, mutableListOf(ADOptimizeComponentRegistrar()))
        } else {
            configuration[ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS]?.plusAssign(ADOptimizeComponentRegistrar())
        }
        configuration.put(ADOptimizeConfigurationKeys.OPT_ANNOTATION_CONFIGKEY, listOf("demo.Optimize"))
        configuration.put(ADOptimizeConfigurationKeys.OPT_SECOND_ORDER_ANNOTATION_CONFIGKEY, listOf("demo.SecondOrderOptimize"))
        configuration.put(ADOptimizeConfigurationKeys.DIFF_API_ANNOTATION_CONFIGKEY, listOf("$diffKtGroup:$diffKtArtifactId:$diffKtVersion"))
        configuration.put(ADOptimizeConfigurationKeys.FAIL_ON_ADEXCEPTION_CONFIGKEY, listOf("true"))
        configuration.put(ADOptimizeConfigurationKeys.REVERSE_AD_CONFIGKEY, listOf("demo.ReverseAD"))
    }
}
