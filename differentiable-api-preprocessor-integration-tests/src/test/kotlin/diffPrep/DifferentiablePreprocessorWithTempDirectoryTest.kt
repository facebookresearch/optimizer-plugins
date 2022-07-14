/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import org.junit.jupiter.api.Test
import java.io.File

class DifferentiablePreprocessorWithTempDirectoryTest : AbstractDifferentiablePreprocessorBlackBoxTest() {
    override val homeDir: String = "differentiable-api-preprocessor-integration-tests/src/test/testData/withTmpDir"

    @Test
    fun singleModule() {
        runAdOptimizePropertiesTest("singleModule/validApi.kt")
    }

    @Test
    fun multiModule() {
        runAdOptimizePropertiesTest("multiModule/validApi.kt")
    }

    private fun runAdOptimizePropertiesTest(testPath: String) {
        val relativePath = "$homeDir/$testPath"
        val testFile = File(srcProjectRoot(), testPath)
        runTest(relativePath)
        val adOptimizePropertiesFile = File(resourcesDirectoryFromTestFile(testFile), adOptimizeCommon.propertiesFileName)
        assert(adOptimizePropertiesFile.exists(), { "The properties file was not written to the source directory" })
        val contents = adOptimizePropertiesFile.readText()
        val expectation = """
                reverseClass=demo.ReverseNode
                forwardScalarClass=demo.ForwardNode
                primalProperty=primal
                upstreamProperty=upstream
                tangentProperty=tangent
                backpropMethod=backpropogate
                pushbackMethod=pushback
                derivativeId=derivativeID
                scalarRoot=demo.DifferentiableDouble
                primalAndPullbackFunction=demo.primalAndPullback
                boxedPrimitive=demo.DDouble
                valueProperty=value
                primitiveType=Double
                stackImpl=demo.Stack
                scalarPlusFunction=demo.plus
                tensorPlusFunction=demo.plus
                toUnboxFunction=demo.ToUnboxedFunction
                toReverse=demo.ToReverse
                dTensor=demo.DiffTensor
                reverseOperations=demo.ReverseScalarOperations
                scalarZero=demo.DDouble.Companion.ZERO
                scalarOne=demo.DDouble.Companion.ONE
                scalarNoop=demo.ScalarNoop
                
        """.trimIndent()
        val diff = expectation.compareTo(contents)
        assert(diff == 0, { "The properties file did not contain the expected contents. Expected \n`$expectation` but got \n`$contents`" })
        adOptimizePropertiesFile.delete()
    }
}
