/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

rootProject.name = "optimizer-plugins"

include(":adoptimize-cli-compiler-plugin")
include(":adoptimize-gradle-plugin")
include(":adoptimize-integration-tests")
include(":adoptimize-publish")
include(":adoptimize-common")

include(":differentiable-api-preprocessor-compiler-plugin")
include(":differentiable-api-preprocessor-gradle-plugin")
include(":differentiable-api-preprocessor-integration-tests")
include(":differentiable-api-preprocessor-publish")

include(":config")
include("plugin-generators-common")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
        }
    }
}
