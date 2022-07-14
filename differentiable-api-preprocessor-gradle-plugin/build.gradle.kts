/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

description = "differentiable api gradle plugin"

plugins {
    id("java-gradle-plugin")
}

dependencies {
    val ktVersion: String by System.getProperties()
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:$ktVersion")
    compileOnly(kotlin("gradle-plugin-api"))
    implementation(project(":config"))
}

group = "org.meta.diffkt.adoptimize"
// generate plugin descriptors in the resulting JAR's META-INF directory
gradlePlugin {
    plugins {
        create("meta-diffkt-differentiable-api-preprocessor-gradle-plugin") {
            id = "meta-diffkt-differentiable-api-preprocessor"
            implementationClass = "diffPrep.gradle.DifferentiableApiPreprocessorGradleSubPlugin"
        }
    }
}
