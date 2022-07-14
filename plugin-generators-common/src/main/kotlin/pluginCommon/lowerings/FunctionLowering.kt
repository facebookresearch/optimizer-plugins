/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.lowerings

import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrFunction

interface FunctionLowering {
    fun lower(declaration: IrFunction): IrFunction
}

interface AnonymousInitializerLowering {
    fun lower(declaration: IrAnonymousInitializer): IrAnonymousInitializer
}

interface DeclarationWithBodyLowering : FunctionLowering, AnonymousInitializerLowering
