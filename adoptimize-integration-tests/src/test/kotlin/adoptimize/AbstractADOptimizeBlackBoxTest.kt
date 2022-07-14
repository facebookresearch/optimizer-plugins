/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize

import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.runners.codegen.AbstractIrBlackBoxCodegenTest
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

abstract class AbstractADOptimizeBlackBoxTest : AbstractIrBlackBoxCodegenTest() {
    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)
        with(builder) {
            useCustomRuntimeClasspathProviders({ testServices: TestServices ->
                object : RuntimeClasspathProvider(testServices) {
                    override fun runtimeClassPaths(module: TestModule): List<File> {
                        return listOf(ADOptimizeConfigurator.diffApiJar, ADOptimizeConfigurator.kotlinReflect)
                    }
                }
            })

            useConfigurators({ testServices: TestServices ->
                ADOptimizeConfigurator(testServices)
            }
            )
        }
    }
}
