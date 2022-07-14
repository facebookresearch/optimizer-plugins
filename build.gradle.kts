/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

plugins {
    kotlin("jvm")
    `maven-publish`
}

allprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "kotlin")
    configurations.compileOnly.configure {
        isCanBeResolved = true
    }
    java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        toolchain {
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    group = System.getProperty("group")
    version = if (project.version == "unspecified") System.getProperty("version") else project.version

    repositories {
        mavenCentral()
        mavenLocal()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
        maven("https://maven.google.com/")
        maven {
            url = uri("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
        }
        maven {
            name = "DiffKtPackages"
            url = uri("https://maven.pkg.github.com/facebookresearch/diffkt")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/facebookresearch/optimizer-plugins")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }

    publishing {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/facebookresearch/diffkt")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }

    tasks {
        withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions.jvmTarget = "1.8"
        }

        // turns out we still need this to use the ReferenceSymbol table, which we need for editing authentic functions
        withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
            kotlinOptions {
                freeCompilerArgs += "-Xopt-in=org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI"
                freeCompilerArgs += "-Xserialize-ir=inline"
            }
        }
    }
}

val ktVersion: String by System.getProperties()
val junit4Version: String by System.getProperties()
val junitPlatformLauncherVersion: String by System.getProperties()
val junitJupiterVersion: String by System.getProperties()
val trove4jVersion: String by System.getProperties()
val intellijVersion: String by System.getProperties()

extra["testDependencies"] = arrayOf(
    "org.jetbrains:annotations:13.0",
    "com.android.tools:r8:2.1.96",
    "javax.inject:javax.inject:1",
    "javax.annotation:jsr250-api:1.0",
    "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.1",
    "io.javaslang:javaslang:2.0.6",
    "org.jetbrains.intellij.deps:trove4j:$trove4jVersion",
    "org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion",
    "junit:junit:$junit4Version"
)

extra["testRuntimeDependencies"] = arrayOf(
    "org.junit.platform:junit-platform-launcher:$junitPlatformLauncherVersion",
    "org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion"
)

extra["platformDependencies"] = arrayOf(
    "util",
    "jps-model",
    "test-framework-core",
    "util-rt",
    "util-strings",
    "util-text-matching",
    "util-diagnostic",
    "util-collections",
    "util-class-loader",
    "statistics-config",
    "jps-model-impl",
    "jps-model-serialization",
    "jps-build",
    "jps-build-javac-rt"
)

// subset of potential. See kotlin.build
extra["coreDependencies"] = arrayOf(
    "annotations",
    "asm-all-9.0",
    "intellij-core",
    "jna-5.6.0",
    "jna-platform-5.6.0"
)

val integrationTests = tasks.create("integrationTests") {
    dependsOn(":adoptimize-integration-tests:test")
    dependsOn(":differentiable-api-preprocessor-integration-tests:test")
    dependsOn(":plugin-generators-common:test")
}

val publishRemote = tasks.create("publishRemote") {
    dependsOn(":differentiable-api-preprocessor-gradle-plugin:publish")
    dependsOn(":adoptimize-gradle-plugin:publish")
    dependsOn(":config:publish")
    dependsOn(":adoptimize-publish:publish")
    dependsOn(":differentiable-api-preprocessor-publish:publish")
}

val publishLocal = tasks.create("publishLocal") {
    dependsOn(":differentiable-api-preprocessor-gradle-plugin:publishToMavenLocal")
    dependsOn(":adoptimize-gradle-plugin:publishToMavenLocal")
    dependsOn(":config:publishToMavenLocal")
    dependsOn(":adoptimize-publish:publishToMavenLocal")
    dependsOn(":differentiable-api-preprocessor-publish:publishToMavenLocal")
}
