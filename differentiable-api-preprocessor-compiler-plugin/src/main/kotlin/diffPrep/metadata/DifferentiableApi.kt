/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep.metadata

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

class ReverseDifferentiableScalarMetadata(
    val clazz: IrClass,
    val upstreamProperty: IrProperty,
    val backpropMethod: IrSimpleFunction,
    val pushbackMethod: IrSimpleFunction,
)

class ForwardDifferentiableScalarMetadata(
    val clazz: IrClass,
    val tangentProperty: IrProperty
)

class DifferentiableApi(
    val reverseDifferentiableScalar: ReverseDifferentiableScalarMetadata,
    val forwardDifferentiableScalar: ForwardDifferentiableScalarMetadata,
    val derivativeId: IrProperty,
    val primalProperty: IrProperty,
    val scalarPlusFunction: IrSimpleFunction,
    val tensorPlusFunction: IrSimpleFunction,
    val scalarRoot: IrClass,
    val primalAndPullbackFunction: IrSimpleFunction,
    val boxedPrimitiveInfo: BoxedPrimitiveInfo,
    val dTensorRoot: IrClass,
    val reverseOperations: IrClass,
    val stackClass: StackClass,
    val scalarNoopClass: IrClass
)
