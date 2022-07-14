/*
 *
 *  Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 *  This source code is licensed under the MIT license found in the
 *  LICENSE file in the root directory of this source tree.
 *
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
plugins {
    val ktVersion: String by System.getProperties()
    kotlin("jvm") version ktVersion
}

allprojects {
    group = "test"
    apply(plugin = "java")
    apply(plugin = "kotlin")

    dependencies {
        implementation(kotlin("stdlib"))
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<KotlinCompile>() {
        kotlinOptions.jvmTarget = "1.8"
        kotlinOptions.freeCompilerArgs += "-Xserialize-ir=inline"
        kotlinOptions.freeCompilerArgs += "-XXLanguage:+ProperCheckAnnotationsTargetInTypeUsePositions"
    }

    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
        }
    }
}
