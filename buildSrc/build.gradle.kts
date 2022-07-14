/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        url = uri("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    }
}

dependencies {
    val ktVersion: String by System.getProperties()
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$ktVersion")
}

tasks["build"].dependsOn(":prepare-deps:build")

val adOptimizeArtifactID = "meta-diffkt-adoptimize-compiler-plugin"
val diffPrepCompilerPluginArtifactID = "meta-diffkt-differentiable-api-preprocessor-compiler-plugin"
System.setProperty("ADOptimizeArtifactID", adOptimizeArtifactID)
System.setProperty("diffPrepCompilerPluginArtifactID", diffPrepCompilerPluginArtifactID)
System.setProperty("ADDiffKtVersion", "0.1.0-2d523b5")
