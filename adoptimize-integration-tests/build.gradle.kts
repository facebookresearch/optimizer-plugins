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
    val ADDiffKtVersion: String by System.getProperties()
    val intellijVersion: String by System.getProperties()

    kotlinStd("org.jetbrains.kotlin:kotlin-stdlib:$ktVersion")
    testImplementation(project(":adoptimize-cli-compiler-plugin", "shadowArtifact"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:$ktVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-scripting-compiler:$ktVersion")
    testImplementation(project(":config"))

    // necessary for populating the gradle cache the the configurator can access that jar at runtime
    testImplementation("org.diffkt.adopt", "api", ADDiffKtVersion)

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
