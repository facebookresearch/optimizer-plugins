/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adOptimizeCommon

const val propertiesFileName = "adoptimize.properties"
const val reverseClass = "reverseClass"
const val forwardClass = "forwardScalarClass"
const val tangentProperty = "tangentProperty"
const val boxedPrimitive = "boxedPrimitive"
const val primitiveType = "primitiveType"
const val primalProperty = "primalProperty"
const val backpropMethod = "backpropMethod"
const val upstreamProperty = "upstreamProperty"
const val pushbackMethod = "pushbackMethod"
const val derivativeId = "derivativeId"
const val scalarRoot = "scalarRoot"
const val primalAndPullbackFunction = "primalAndPullbackFunction"
const val valueProperty = "valueProperty"
const val scalarPlusFunction = "scalarPlusFunction"
const val tensorPlusFunction = "tensorPlusFunction"
const val scalarZero = "scalarZero"
const val scalarOne = "scalarOne"
const val stackImpl = "stackImpl"
const val toUnboxFunction = "toUnboxFunction"
const val toReverse = "toReverse"
const val dTensor = "dTensor"
const val reverseOperations = "reverseOperations"
const val scalarNoop = "scalarNoop"

fun reverseNodeNameFromOperationsName(operationsFunctionFqName: String) = "${operationsFunctionFqName.replace('.','_')}LiftedReverseNode"
