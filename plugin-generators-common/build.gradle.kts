/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

description = "Shared codegen utilities among plugins"

dependencies {
    val ktVersion: String by System.getProperties()
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:$ktVersion")
    api("org.jetbrains.kotlin:kotlin-compiler-embeddable:$ktVersion")

    api("org.jetbrains.kotlin:kotlin-reflect:$ktVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.8.0-M1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.1")
    testImplementation("junit", "junit", "4.12")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
