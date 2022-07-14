/*
 *
 *  Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 *  This source code is licensed under the MIT license found in the
 *  LICENSE file in the root directory of this source tree.
 *
 */

description = "client"

plugins {
    val ktVersion: String by System.getProperties()
    kotlin("jvm") version ktVersion
    id("meta-diffkt-adoptimize") version "0.0.1-SNAPSHOT"
    application
}

val diffKtVersion = "0.1.0-2d523b5"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    toolchain {
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    }
    maven {
        url = uri("https://maven.pkg.github.com/facebookresearch/diffkt")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

adOptimize {
    this.diffApi("org.diffkt.adopt", "api", diffKtVersion)
    this.optimizeAnnotation("config.Optimize")
    this.secondOrderAnnotation("config.SecondOrderOptimize")
    this.failOnADFail(true)
    this.reverseADFunction("config.ReverseAD")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation(group = "org.diffkt.adopt", name = "api", version = diffKtVersion)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.8.0-M1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.1")
    testImplementation("junit", "junit", "4.12")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.1")
    testImplementation(group = "org.diffkt.adopt", name = "api", version = diffKtVersion)
}

application {
    mainClass.set("client.MainKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
