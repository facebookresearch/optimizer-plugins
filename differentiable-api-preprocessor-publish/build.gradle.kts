/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
description = "Differentiable API preprocessor cli compiler plugin"

plugins {
    id("com.github.johnrengelman.shadow") version "6.1.0"
}

dependencies {
    val ktVersion: String by System.getProperties()
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:$ktVersion")
    api("org.jetbrains.kotlin:kotlin-compiler-embeddable:$ktVersion")
    compileOnly(project(":differentiable-api-preprocessor-compiler-plugin"))
    compileOnly(project(":plugin-generators-common"))
    compileOnly(project(":adoptimize-common"))
}

val fatJarArtifact by configurations.creating
val shadowFatJar: ShadowJar = tasks.getByName<ShadowJar>("shadowJar") {
    val convention = project.convention.getPlugin<JavaPluginConvention>()
    from(convention.sourceSets.main.get().output)
    archiveClassifier.set("")
    configurations = mutableListOf(project.configurations.compileOnly.get())
    dependencies {
        include(project(":differentiable-api-preprocessor-compiler-plugin"))
        include(project(":plugin-generators-common"))
        include(project(":adoptimize-common"))
    }
}

val publishArtifact = artifacts.add(fatJarArtifact.name, shadowFatJar)

publishing {
    publications {
        create<MavenPublication>("diffkt-diffPrep-publishing") {
            artifactId = System.getProperty("diffPrepCompilerPluginArtifactID")
            artifact(publishArtifact)
        }
    }
}
