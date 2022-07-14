/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

plugins {
    id("com.github.gmazzo.buildconfig") version "3.0.2"
}

buildConfig {
    packageName("config")
    buildConfigField("String", "PLUGIN_GROUP", "\"$group\"")

    buildConfigField("String", "ADOPTIMIZE_ID", "\"adoptimize\"")
    buildConfigField("String", "DIFFPREP_ID", "\"differentiable-api-preprocessor\"")

    buildConfigField("String", "ADOPTIMIZE_ARTIFACT_ID", "\"${System.getProperty("ADOptimizeArtifactID")}\"")
    buildConfigField("String", "DIFFPREP_ARTIFACT_ID", "\"${System.getProperty("diffPrepCompilerPluginArtifactID")}\"")

    buildConfigField("String", "PLUGIN_VERSION", "\"$version\"")

    buildConfigField("String", "AD_DIFFKT_VERSION", "\"${System.getProperty("ADDiffKtVersion")}\"")
    buildConfigField("String", "KOTLIN_VERSION", "\"${System.getProperty("ktVersion")}\"")
}

publishing {
    publications {
        create<MavenPublication>("meta-adoptimize-config") {
            from(components["java"])
            artifactId = "meta-adoptimize-config"
        }
    }
}
