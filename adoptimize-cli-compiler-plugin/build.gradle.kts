/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
description = "AD optimize cli compiler plugin"

plugins {
    id("com.github.johnrengelman.shadow") version "6.1.0"
}

dependencies {
    val ktVersion: String by System.getProperties()
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:$ktVersion")
    api("org.jetbrains.kotlin:kotlin-compiler-embeddable:$ktVersion")
    compileOnly(project(":adoptimize-common"))
    compileOnly(project(":plugin-generators-common"))
}

sourceSets {
    main {}
    test { java.srcDirs("test", "tests") }
}

val shadowArtifact by configurations.creating
val shadowJar: ShadowJar = tasks.getByName<ShadowJar>("shadowJar") {
    val convention = project.convention.getPlugin<JavaPluginConvention>()
    archiveClassifier.set("sources")
    from(convention.sourceSets.main.get().output)
    configurations = mutableListOf(project.configurations.compileOnly.get())
    relocate("org.jetbrains.org.objectweb.asm.tree.analysis", "org.objectweb.asm.tree.analysis")
    relocate("org.jetbrains.kotlin.com.intellij", "com.intellij")
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        // and its transitive dependencies:
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-common"))
        exclude(dependency("org.jetbrains:annotations"))

        exclude(dependency("com.intellij:openapi"))
        // and its transitive dependencies:
        exclude(dependency("com.intellij:extensions"))
        exclude(dependency("com.intellij:annotations"))
    }
}

artifacts {
    add(shadowArtifact.name, shadowJar)
}
