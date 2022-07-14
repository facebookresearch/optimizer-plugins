/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.Metadata

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction

class StackClass(val clazz: IrClass, val popMethod: IrFunction, val pushMethod: IrFunction, val notEmptyMethod: IrFunction, val topMethod: IrFunction)
