/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package adoptimize.gradle

open class ADOptimizeExtension {
    var optimize = ""
    var diffApi: String = ""
    var secondOrderOptimization = ""
        private set
    var failOnADFailFlag = false
        private set
    var reverseAD = ""
        private set

    open fun optimizeAnnotation(customOptimizeAnnotation: String) {
        this.optimize = customOptimizeAnnotation
    }

    open fun diffApi(group: String, artifactId: String, version: String) {
        diffApi = "$group:$artifactId:$version"
    }

    open fun secondOrderAnnotation(customSecondOrderAnnotation: String) {
        this.secondOrderOptimization = customSecondOrderAnnotation
    }

    open fun failOnADFail(failOnAdFail: Boolean) {
        this.failOnADFailFlag = failOnAdFail
    }

    open fun reverseADFunction(fqn: String) {
        this.reverseAD = fqn
    }
}
