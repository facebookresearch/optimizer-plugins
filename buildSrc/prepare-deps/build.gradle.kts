/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.text.SimpleDateFormat
import java.util.*

plugins {
    base
}

repositories {
    maven("https://www.jetbrains.com/intellij-repository/releases")
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    }
}

val intellijCore by configurations.creating
val ideaIC by configurations.creating
val distScriptForTests by configurations.creating
val distStdlib by configurations.creating
val kotlinTest by configurations.creating
val annotations by configurations.creating

dependencies {
    val ktVersion: String by System.getProperties()
    val intellijVersion: String by System.getProperties()
    intellijCore("com.jetbrains.intellij.idea:intellij-core:$intellijVersion")
    ideaIC("com.jetbrains.intellij.idea:ideaIC:$intellijVersion")
    distScriptForTests("org.jetbrains.kotlin:kotlin-script-runtime:$ktVersion")
    distStdlib("org.jetbrains.kotlin:kotlin-stdlib:$ktVersion")
    kotlinTest("org.jetbrains.kotlin:kotlin-test:$ktVersion")
    annotations("org.jetbrains:annotations:13.0")
}

val kotlinBuildDir: String by System.getProperties()
val repoDirName: String by System.getProperties()
val kotlinBuildGroupId: String by System.getProperties()
val intellijVersion: String by System.getProperties()

val dependenciesDir = rootProject.gradle.gradleUserHomeDir.resolve(kotlinBuildDir)
val repoDir: File = dependenciesDir.resolve(repoDirName)
val kotlinBuildDirectory = File(repoDir, kotlinBuildGroupId)

val distDir by extra("${rootDir.parent}/dist")

tasks.named<Delete>("clean") {
    delete(repoDir)
    delete(File(distDir))
}

val intellijCoreName = "intellij-core"
fun ivyRepoTask(configuration: Configuration, name: String) = tasks.register("buildIvyRepoFor${configuration.name}") {
    dependsOn(configuration)
    inputs.files(configuration)

    val moduleDirectory = kotlinBuildDirectory.resolve(name).resolve(intellijVersion)
    outputs.upToDateWhen {
        val repoMarker = moduleDirectory.resolve(".marker")
        repoMarker.exists()
    }

    doFirst {
        val artifact = configuration.resolvedConfiguration.resolvedArtifacts.single()
        val repoMarker = File(moduleDirectory, ".marker")
        if (repoMarker.exists()) {
            logger.info("Path ${repoMarker.absolutePath} already exists, skipping unpacking.")
            return@doFirst
        }
        with(artifact) {
            val artifactsDirectory = File(moduleDirectory, "artifacts")
            logger.info("Unpacking ${file.name} into ${artifactsDirectory.absolutePath}")
            copy {
                val fileTree = when (extension) {
                    "tar.gz" -> tarTree(file)
                    "zip" -> zipTree(file)
                    else -> error("Unsupported artifact extension: $extension")
                }
                from(
                    fileTree.matching {
                        exclude("**/plugins/Kotlin/**")
                    }
                )
                into(artifactsDirectory)
                includeEmptyDirs = false
            }

            val ivyDirectory = File(moduleDirectory, "ivy")
            val ivyFile = ivyDirectory.resolve("$intellijCoreName.ivy.xml")
            ivyFile.parentFile.mkdirs()
            val publication = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
            val fileContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ivy-module version="2.0" xmlns:m="http://ant.apache.org/ivy/maven">
                  <info organisation="$kotlinBuildGroupId" module="$name" revision="$intellijVersion" publication="$publication"/>
                  <configurations>
                    <conf name="default" visibility="public"/>
                    <conf name="sources" visibility="public"/>
                  </configurations>
                  <publications>
                  </publications>
                </ivy-module>
            """.trimIndent()
            ivyFile.appendText(fileContent)
            repoMarker.createNewFile()
        }
    }
}

val makeIntellijCore = ivyRepoTask(intellijCore, intellijCoreName)
val makeIdeaIC = ivyRepoTask(ideaIC, ideaIC.name)

val kotlincLibConfigs = arrayOf(distScriptForTests, kotlinTest, distStdlib)
val stdLibMinimalForTests = "kotlin-stdlib-jvm-minimal-for-test.jar"

// HACK: puts the stdlib minimal for tests jar in the dist directory so that the ForTestCompile class can find it.
val dist = tasks.register("dist") {
    dependsOn(kotlincLibConfigs)
    doFirst {
        val destinationDir = File(distDir)
        if (destinationDir.exists()) {
            logger.info("Path ${destinationDir.absolutePath} already exists, skipping copy.")
            return@doFirst
        } else {
            val ktVersion: String by System.getProperties()
            val kotlinc = File(destinationDir, "kotlinc")
            val lib = File(kotlinc, "lib")
            lib.mkdirs()
            kotlincLibConfigs.forEach {
                copy {
                    from(it)
                    into(lib)
                    rename { filename ->
                        filename.replace("-$ktVersion", "")
                    }
                }
            }
        }
    }
}

val stdLibMinimalForTestTask = tasks.register("stdlib") {
    dependsOn(distStdlib)
    doFirst {
        val stdLib = File(distDir, stdLibMinimalForTests)
        if (stdLib.exists()) {
            logger.info("Path ${stdLib.absolutePath} already exists, skipping copy.")
            return@doFirst
        } else {
            copy {
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                from(distStdlib)
                into(stdLib.parentFile)
                rename { filename: String ->
                    stdLibMinimalForTests
                }
            }
        }
    }
}

tasks.named("build") {
    dependsOn(
        makeIntellijCore,
        makeIdeaIC,
        dist,
        stdLibMinimalForTestTask
    )
}
