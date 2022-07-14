/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.gradle

import config.BuildConfig
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import javax.inject.Inject

class ADOptimizeGradleSubPlugin @Inject internal constructor(private val registry: ToolingModelBuilderRegistry) : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        target.extensions.create("adOptimize", ADOptimizeExtension::class.java)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        val adoptimize = project.extensions.getByType(ADOptimizeExtension::class.java)

        return project.provider {
            val options = mutableListOf<SubpluginOption>()
            options += SubpluginOption("optimize", adoptimize.optimize)
            options += SubpluginOption("diffApi", adoptimize.diffApi)
            options += SubpluginOption("secondOrderOptimize", adoptimize.secondOrderOptimization)
            options += SubpluginOption("failOnADFail", adoptimize.failOnADFailFlag.toString())
            options += SubpluginOption("reverseADFunction", adoptimize.reverseAD.toString())
            options
        }
    }

    override fun getCompilerPluginId() = BuildConfig.ADOPTIMIZE_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(groupId = BuildConfig.PLUGIN_GROUP, artifactId = BuildConfig.ADOPTIMIZE_ARTIFACT_ID, version = BuildConfig.PLUGIN_VERSION)
}
