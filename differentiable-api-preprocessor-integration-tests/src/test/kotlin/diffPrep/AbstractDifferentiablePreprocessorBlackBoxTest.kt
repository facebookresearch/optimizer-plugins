/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.runners.codegen.AbstractIrBlackBoxCodegenTest

abstract class AbstractDifferentiablePreprocessorBlackBoxTest : AbstractIrBlackBoxCodegenTest(), DifferentiablePreprocessorBaseTest {
    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)
        with(builder) {
            useConfigurators({ ts -> DifferentiablePreprocessorConfigurator(ts, ::resourcesDirectoryFromTestFile) })
        }
    }
}
