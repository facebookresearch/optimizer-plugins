/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize

import adOptimizeCommon.propertiesFileName
import adoptimize.ADOptimizeConfigurationKeys.DIFF_API_ANNOTATION_CONFIGKEY
import adoptimize.ADOptimizeConfigurationKeys.FAIL_ON_ADEXCEPTION_CONFIGKEY
import adoptimize.ADOptimizeConfigurationKeys.OPT_ANNOTATION_CONFIGKEY
import adoptimize.ADOptimizeConfigurationKeys.OPT_SECOND_ORDER_ANNOTATION_CONFIGKEY
import adoptimize.ADOptimizeConfigurationKeys.REVERSE_AD_CONFIGKEY
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.extensions.CompilerConfigurationExtension
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.jar.JarEntry
import java.util.jar.JarFile

class ADOptimizeComponentRegistrar : ComponentRegistrar {
    override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
        fun stringFromList(key: CompilerConfigurationKey<List<String>>): String {
            val singletonList = configuration[key]
                ?: throw IllegalStateException("Plugin requires an `$key` annotation key")
            val annotationName = if (singletonList.size == 1) singletonList.first() else throw IllegalStateException("Plugin requires a single annotation $key but received ${singletonList.size}")
            return annotationName
        }
        val annotationName = stringFromList(OPT_ANNOTATION_CONFIGKEY)
        val secondOrderAnnotationName = configuration[OPT_SECOND_ORDER_ANNOTATION_CONFIGKEY]?.firstOrNull() ?: ""
        val failOnError = configuration[FAIL_ON_ADEXCEPTION_CONFIGKEY]?.firstOrNull().toBoolean() ?: false
        val reverseADFunction = configuration[REVERSE_AD_CONFIGKEY]?.firstOrNull().toString() ?: ""

        val apiName = stringFromList(DIFF_API_ANNOTATION_CONFIGKEY)
        val parts = apiName.split(":")
        if (parts.size != 3) {
            throw AutoDiffException("The $DIFF_API_ANNOTATION_CONFIGKEY value is malformed. Expected `group`:`name`:`version` but got $apiName")
        }
        val group = parts[0]
        val artifactId = parts[1]
        val version = parts[2]

        val contentRootsKey: CompilerConfigurationKey<MutableList<ContentRoot>> = CLIConfigurationKeys.CONTENT_ROOTS
        val differentiableLibrary = configuration[contentRootsKey]?.filterIsInstance<JvmClasspathRoot>()?.firstOrNull {
            if (it.isSdkRoot) false else {
                val isCachedVersion = it.file.absolutePath.contains("$group/$artifactId/$version")
                val isLocalVersion = it.file.absolutePath.contains("${group.replace(".", "/")}/$artifactId/$version")
                isCachedVersion || isLocalVersion
            }
        } ?: throw AutoDiffException("Differentiable API missing from dependencies. Expected an artifact for $apiName.")

        val jar = JarFile(differentiableLibrary.file)
        val apiSpecification = adOptimizePropertiesSpecification(jar)

        registerExtensions(project, apiSpecification, annotationName, apiName, secondOrderAnnotationName, failOnError, reverseADFunction)
    }

    fun registerExtensions(project: Project, apiSpecification: Map<String, String>, optimize: String, differentiableApiPackageName: String, secondOrderOptimize: String, failOnError: Boolean, reverseADFunction: String) {
        CompilerConfigurationExtension.registerExtension(project, ADOptimizeCompilerConfigurationExtension())
        IrGenerationExtension.registerExtension(project, ADOptimizeIrGenerationExtension(apiSpecification, differentiableApiPackageName, optimize, secondOrderOptimize, failOnError, reverseADFunction))
    }

    private fun adOptimizePropertiesSpecification(jar: JarFile): Map<String, String> {
        val adOptimizePropertiesMaybe: JarEntry? = jar.entries().toList().firstOrNull { it.name == propertiesFileName }
        if (adOptimizePropertiesMaybe == null) {
            throw AutoDiffException("Missing the properties file. Try publishing DiffKt after verifying the resources file is included in the repository")
        }
        val adOptimizeProperties = adOptimizePropertiesMaybe!!
        val inputStream = jar.getInputStream(adOptimizeProperties)
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))
        val apiSpecification = mutableMapOf<String, String>()
        while (true) {
            val line = bufferedReader.readLine()
            if (line == null) {
                break
            }
            val keyValue = line.split("=")
            if (keyValue.size != 2) {
                throw AutoDiffException("Encountered a malformed property: $line")
            }
            apiSpecification[keyValue[0]] = keyValue[1]
        }
        return apiSpecification
    }
}
