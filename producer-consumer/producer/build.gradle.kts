/*
 *
 *  Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 *  This source code is licensed under the MIT license found in the
 *  LICENSE file in the root directory of this source tree.
 *
 */

plugins {
    `maven-publish`
    val pluginVersions: String by System.getProperties()
    id("meta-diffkt-differentiable-api-preprocessor") version pluginVersions
}

group = "org.example"
version = "1.0-SNAPSHOT"

differentiableApiPreprocessor {
    this.stackImplAnnotation("demo.StackImpl")
    this.boxedPrimitive("demo.BoxedPrimitive")
    this.scalarRoot("demo.ScalarRoot")
    this.primalAndPullbackAnnotation("demo.PrimalAndPullback")
    this.reverseAnnotation("demo.ReverseDifferentiable")
    this.unboxedFunction("demo.ToUnboxedFunction")
    val userDir = System.getProperty("user.dir")
    val pathToResources = "$userDir/src/main/resources"
    this.resourcesPath(pathToResources)
    this.toReverseAnnotation("demo.ToReverse")
    this.dTensorAnnotation("demo.DTensorRoot")
    this.reverseScalarOperationsAnnotation("demo.ReverseOperations")
    this.scalarNoop("demo.ScalarNoop")
    this.forwardDifferentiable("demo.ForwardDifferentiable")
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }

    withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
        kotlinOptions {
            freeCompilerArgs = freeCompilerArgs + "-Xserialize-ir=inline" + "-opt-in=org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI"
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "test"
            artifactId = "producer"
            version = project.version.toString()
            from(components["java"])
        }
    }
}
