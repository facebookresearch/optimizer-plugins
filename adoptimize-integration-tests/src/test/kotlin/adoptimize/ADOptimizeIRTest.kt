/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize

import org.junit.jupiter.api.Test

class ADOptimizeIRTest : AbstractADOptimizeIRTest() {
    val homeDir = "adoptimize-integration-tests/src/test/testData/ir"

    @Test
    fun testControlFlow() {
        runTest("$homeDir/control_flow.kt")
    }

    @Test
    fun testDerivative() {
        runTest("$homeDir/derivative.kt")
    }

    @Test
    fun secondOrderDerivative() {
        runTest("$homeDir/secondOrderDerivative.kt")
    }

    @Test
    fun firstAndSecondOrderDerivative() {
        runTest("$homeDir/firstAndSecondOrderDerivative.kt")
    }

    @Test
    fun testFloatFunction() {
        runTest("$homeDir/floatFunction.kt")
    }
}
