/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import org.junit.jupiter.api.Test

class DifferentiablePreprocessorDiagnosticTest : AbstractDifferentiablePrepropessorDiagnosticTests() {
    val homeDir = "differentiable-api-preprocessor-integration-tests/src/test/testData/diagnostics"

    @Test
    fun testValidApi() {
        runTest("$homeDir/validApi.kt")
    }

    @Test
    fun testToUnBoxFunctionInvalidSignature() {
        runTest("$homeDir/toUnBoxFunctionInvalidSignature.kt")
    }

    @Test
    fun testToUnboxClassMethods() {
        runTest("$homeDir/toUnboxClassMethods.kt")
    }
}
