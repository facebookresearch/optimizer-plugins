/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import java.io.File

interface DifferentiablePreprocessorBaseTest {
    val homeDir: String

    fun srcProjectRoot() = File(System.getProperty("user.dir"), homeDir)

    fun resourcesDirectoryFromTestFile(testFile: File) = testFile.resolveSibling("resources")
}
