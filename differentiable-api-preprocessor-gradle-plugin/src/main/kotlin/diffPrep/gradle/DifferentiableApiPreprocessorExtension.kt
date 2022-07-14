/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep.gradle

open class DifferentiableApiPreprocessorExtension {
    var reverse = ""
    var stackImpl = ""
    var primalAndPullback = ""
    var boxedPrimitive = ""
    var unboxedFunction = ""
    var scalarRoot = ""
    var resourcesPath = ""
    var toReverse = ""
    var dTensor = ""
    var reverseScalarOperations = ""
    var scalarNoop = ""
    var forwardDifferentiable = ""

    open fun reverseAnnotation(customReverseAnnotation: String) {
        this.reverse = customReverseAnnotation
    }

    open fun stackImplAnnotation(customStackImpl: String) {
        this.stackImpl = customStackImpl
    }

    open fun primalAndPullbackAnnotation(customPrimalAndPullback: String) {
        this.primalAndPullback = customPrimalAndPullback
    }

    open fun boxedPrimitive(customBoxedPrimitive: String) {
        this.boxedPrimitive = customBoxedPrimitive
    }

    open fun unboxedFunction(customUnboxedFunction: String) {
        this.unboxedFunction = customUnboxedFunction
    }

    open fun scalarRoot(customScalarRoot: String) {
        this.scalarRoot = customScalarRoot
    }

    open fun resourcesPath(resourcesPath: String) {
        this.resourcesPath = resourcesPath
    }

    open fun toReverseAnnotation(toReverseAnnotation: String) {
        this.toReverse = toReverseAnnotation
    }

    open fun dTensorAnnotation(dTensorAnnotation: String) {
        this.dTensor = dTensorAnnotation
    }

    open fun reverseScalarOperationsAnnotation(operationsAnnotationFqn: String) {
        this.reverseScalarOperations = operationsAnnotationFqn
    }

    open fun scalarNoop(scalarNoopFqn: String) {
        this.scalarNoop = scalarNoopFqn
    }

    open fun forwardDifferentiable(forwardDifferentiableFqn: String) {
        this.forwardDifferentiable = forwardDifferentiableFqn
    }
}
