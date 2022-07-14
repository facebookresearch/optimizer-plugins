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
    kotlin("jvm")
    val pluginVersions: String by System.getProperties()
    id("meta-diffkt-adoptimize") version pluginVersions
    application
}

application {
    mainClass.set("consumer.MainKt")
}

adOptimize {
    this.diffApi("test", "producer", "1.0-SNAPSHOT")
    this.optimizeAnnotation("consumer.Optimize")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation("test", "producer", "1.0-SNAPSHOT")
}
