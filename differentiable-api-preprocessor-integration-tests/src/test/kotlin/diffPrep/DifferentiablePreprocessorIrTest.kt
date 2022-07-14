/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import org.junit.jupiter.api.Test

class DifferentiablePreprocessorIrTest : AbstractDifferentiablePreprocessorIrTest() {
    override val homeDir: String = "differentiable-api-preprocessor-integration-tests/src/test/testData/ir"

    @Test
    fun testApi() {
        runTest("$homeDir/api.kt")
    }

    @Test
    fun testTypeOperator() {
        runTest("$homeDir/typeOperator.kt")
    }
}
