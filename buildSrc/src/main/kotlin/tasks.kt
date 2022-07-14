/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.provideDelegate
import java.io.File

fun Project.projectTest(
    taskName: String = "test",
    body: Test.() -> Unit = {}
): TaskProvider<Test> = getOrCreateTask(taskName) {
    val version = System.getProperty("intellijVersion")
    systemProperty("idea.home.path", "${System.getProperty("user.home")}/.gradle/kotlin-build-dependencies/repo/kotlin.build/ideaIC/$version/artifacts")
    systemProperty("idea.ignore.disabled.plugins", "true")
    body()
}

inline fun <reified T : Task> Project.getOrCreateTask(taskName: String, noinline body: T.() -> Unit): TaskProvider<T> =
    if (tasks.names.contains(taskName)) tasks.named(taskName, T::class.java).apply { configure(body) }
    else tasks.register(taskName, T::class.java, body)

val kotlinBuildDir: String by System.getProperties()
val repoDirName: String by System.getProperties()

private fun Project.kotlinBuildLocalRepoDir(): File = rootProject.gradle.gradleUserHomeDir.resolve(kotlinBuildDir).resolve(repoDirName)

fun RepositoryHandler.kotlinBuildLocalRepo(project: Project): IvyArtifactRepository = ivy {
    url = project.kotlinBuildLocalRepoDir().toURI()

    patternLayout {
        ivy("[organisation]/[module]/[revision]/[module].ivy.xml")
        ivy("[organisation]/[module]/[revision]/ivy/[module].ivy.xml")
        ivy("[organisation]/ideaIC/[revision]/ivy/[module].ivy.xml") // bundled plugins

        artifact("[organisation]/[module]/[revision]/artifacts/lib/[artifact](-[classifier]).[ext]")
        artifact("[organisation]/[module]/[revision]/artifacts/[artifact](-[classifier]).[ext]")
        artifact("[organisation]/intellij-core/[revision]/artifacts/[artifact](-[classifier]).[ext]")
        artifact("[organisation]/ideaIC/[revision]/artifacts/plugins/[module]/lib/[artifact](-[classifier]).[ext]") // bundled plugins
        artifact("[organisation]/sources/[artifact]-[revision](-[classifier]).[ext]")
        artifact("[organisation]/[module]/[revision]/[artifact](-[classifier]).[ext]")
    }

    metadataSources {
        ivyDescriptor()
    }
}
