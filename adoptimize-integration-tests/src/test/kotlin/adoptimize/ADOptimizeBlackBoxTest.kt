/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize

import org.junit.jupiter.api.Test

class ADOptimizeBlackBoxTest : AbstractADOptimizeBlackBoxTest() {
    val homeDir: String = "adoptimize-integration-tests/src/test/testData/codegen"

    @Test
    fun testActiveArgumentKt() {
        runTest("$homeDir/activeArgument.kt")
    }

    @Test
    fun testConstArgKt() {
        runTest("$homeDir/constArg.kt")
    }

    @Test
    fun testControlFlowDerivative() {
        runTest("$homeDir/control_flow_derivative.kt")
    }

    @Test
    fun testGetValInitializer() {
        runTest("$homeDir/getValInitializer.kt")
    }

    @Test
    fun testControlFlowNestedIf() {
        runTest("$homeDir/control_flow_nested_if.kt")
    }

    @Test
    fun testUnwrapZERO() {
        runTest("$homeDir/unwrapZERO.kt")
    }

    @Test
    fun testUnwrapONE() {
        runTest("$homeDir/unwrapONE.kt")
    }

    @Test
    fun testElseLower() {
        runTest("$homeDir/elseLower.kt")
    }

    @Test
    fun testSwitchAssign() {
        runTest("$homeDir/switchAssign.kt")
    }

    @Test
    fun testWhileLoop() {
        runTest("$homeDir/while_statement.kt")
    }

    @Test
    fun testNonActiveArgument() {
        runTest("$homeDir/nonActiveArgument.kt")
    }

    @Test
    fun testSimpleWhileLoop() {
        runTest("$homeDir/simpleWhileLoop.kt")
    }

    @Test
    fun testLogProbOf() {
        runTest("$homeDir/logProb.kt")
    }

    @Test
    fun testDiffKt() {
        runTest("$homeDir/diffkt.kt")
    }

    @Test
    fun testControlFlow() {
        runTest("$homeDir/control_flow.kt")
    }

    @Test
    fun testNullArgument() {
        runTest("$homeDir/nullArgument.kt")
    }

    @Test
    fun testForwardsUnbox() {
        runTest("$homeDir/forwardsUnbox.kt")
    }

    @Test
    fun testInitialization() {
        runTest("$homeDir/initialization.kt")
    }

    @Test
    fun testIfStatement() {
        runTest("$homeDir/if_statement.kt")
    }

    @Test
    fun testMultipleOutputs() {
        runTest("$homeDir/multipleOutputs.kt")
    }

    @Test
    fun testMultipleOutputsControlFlow() {
        runTest("$homeDir/multipleOutputsControlFlow.kt")
    }

    @Test
    fun testImplicitParameter() {
        runTest("$homeDir/implicitParameter.kt")
    }

    @Test
    fun testParameterWithNonParameterizedTypeArg() {
        runTest("$homeDir/parameterWithNonParameterizedTypeArg.kt")
    }

    @Test
    fun testGetterNoExplicitUnbox() {
        runTest("$homeDir/getterNoExplicitUnbox.kt")
    }

    @Test
    fun testScalarNoop() {
        runTest("$homeDir/scalarNoop.kt")
    }

    @Test
    fun testAssignOperations() {
        runTest("$homeDir/assignOperations.kt")
    }

    @Test
    fun testReverseForwardPrimal() {
        runTest("$homeDir/reverseForwardPrimal.kt")
    }

    @Test
    fun testReverseForwardDerivative() {
        runTest("$homeDir/reverseForwardDerivative.kt")
    }

    @Test
    fun testReverseForwardControlFlow() {
        runTest("$homeDir/reverseForwardControlFlow.kt")
    }

    @Test
    fun testReverseForwardControlFlowPrimal() {
        runTest("$homeDir/reverseForwardControlFlowPrimal.kt")
    }

    @Test
    fun testReverseForwardControlFlowWhen() {
        runTest("$homeDir/reverseForwardControlFlowWhen.kt")
    }

    @Test
    fun testReverseForwardControlFlowWhenPrimal() {
        runTest("$homeDir/reverseForwardControlFlowWhenPrimal.kt")
    }

    @Test
    fun testReverseForwardControlFlowNested() {
        runTest("$homeDir/reverseForwardControlFlowNested.kt")
    }

    @Test
    fun testReverseForwardLogProb() {
        runTest("$homeDir/reverseForwardLogProb.kt")
    }

    @Test
    fun testReverseForwardLn() {
        runTest("$homeDir/reverseForwardLn.kt")
    }

    @Test
    fun testReverseForwardNonActiveIntermediateValues() {
        runTest("$homeDir/reverseForwardNonActiveIntermediateValues.kt")
    }

    @Test
    fun testNestedWhenVariable() {
        runTest("$homeDir/nestedWhenVariable.kt")
    }

    @Test
    fun testExp() {
        runTest("$homeDir/exp.kt")
    }

    @Test
    fun testFloatFunction() {
        runTest("$homeDir/floatFunction.kt")
    }
}
