/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep.gradle

import config.BuildConfig
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import javax.inject.Inject

class DifferentiableApiPreprocessorGradleSubPlugin @Inject internal constructor(private val registry: ToolingModelBuilderRegistry) : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        target.extensions.create("differentiableApiPreprocessor", DifferentiableApiPreprocessorExtension::class.java)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        val differentiableApiPreprocessor = project.extensions.getByType(DifferentiableApiPreprocessorExtension::class.java)

        return project.provider {
            val options = mutableListOf<SubpluginOption>()
            options += SubpluginOption("reverse", differentiableApiPreprocessor.reverse)
            options += SubpluginOption("stackImpl", differentiableApiPreprocessor.stackImpl)
            options += SubpluginOption("primalAndPullback", differentiableApiPreprocessor.primalAndPullback)
            options += SubpluginOption("boxedPrimitive", differentiableApiPreprocessor.boxedPrimitive)
            options += SubpluginOption("unboxedFunction", differentiableApiPreprocessor.unboxedFunction)
            options += SubpluginOption("scalarRoot", differentiableApiPreprocessor.scalarRoot)
            options += SubpluginOption("resourcesPath", differentiableApiPreprocessor.resourcesPath)
            options += SubpluginOption("toReverseNode", differentiableApiPreprocessor.toReverse)
            options += SubpluginOption("DTensorRoot", differentiableApiPreprocessor.dTensor)
            options += SubpluginOption("reverseOperations", differentiableApiPreprocessor.reverseScalarOperations)
            options += SubpluginOption("scalarNoop", differentiableApiPreprocessor.scalarNoop)
            options += SubpluginOption("forward", differentiableApiPreprocessor.forwardDifferentiable)
            options
        }
    }

    // we assume the gradle plugin id and the compiler plugin id are the same.
    override fun getCompilerPluginId() = BuildConfig.DIFFPREP_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = BuildConfig.PLUGIN_GROUP, artifactId = BuildConfig.DIFFPREP_ARTIFACT_ID, version = BuildConfig.PLUGIN_VERSION)
}
