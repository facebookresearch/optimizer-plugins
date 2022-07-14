/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

repositories {
    kotlinBuildLocalRepo(project)
}

val platformDependencies: Array<String> by rootProject.extra
val testDependencies: Array<String> by rootProject.extra
val testRuntimeDependencies: Array<String> by rootProject.extra
val kotlinStd by configurations.creating
val coreDependencies: Array<String> by rootProject.extra

dependencies {
    val ktVersion: String by System.getProperties()
    val intellijVersion: String by System.getProperties()

    kotlinStd("org.jetbrains.kotlin:kotlin-stdlib:$ktVersion")
    testImplementation(project(":differentiable-api-preprocessor-compiler-plugin", "shadowArtifact"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:$ktVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-scripting-compiler:$ktVersion")
    testImplementation("one.util:streamex:0.7.3")
    testImplementation(project(":adoptimize-common"))

    testDependencies.forEach {
        testImplementation(it)
    }

    testRuntimeDependencies.forEach {
        testRuntimeOnly(it)
    }

    platformDependencies.forEach {
        testImplementation("com.jetbrains.intellij.platform:$it:$intellijVersion")
    }

    coreDependencies.forEach { artifactName ->
        testImplementation("kotlin.build:intellij-core:$intellijVersion") {
            artifact {
                name = artifactName
                type = "jar"
                extension = "jar"
            }
        }
    }
}

val testArtifact by configurations.creating
val testJar = tasks.register<Jar>("testJar") {
    val convention = project.convention.getPlugin<JavaPluginConvention>()
    archiveClassifier.set("sources")
    from(convention.sourceSets.test.get().output)
}

artifacts {
    add(testArtifact.name, testJar)
}

projectTest {
    workingDir = rootDir
    useJUnitPlatform()
}
