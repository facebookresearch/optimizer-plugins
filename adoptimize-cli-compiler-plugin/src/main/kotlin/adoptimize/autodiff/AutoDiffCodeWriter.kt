/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff

import adoptimize.autodiff.diffIR.CallVariable
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import pluginCommon.ScopeSubstitutionMap

typealias DerivativeContributions = List<Pair<IrValueDeclaration, IrValueDeclaration>>
interface WrittenDeclarations
class PrimalAndPullback(val primal: IrValueDeclaration, val pullback: IrValueDeclaration) : WrittenDeclarations
class Primal(val primal: IrValueDeclaration) : WrittenDeclarations

interface AutoDiffCodeWriter {
    fun writeBackpropCodeForLeaf(
        leaf: CallVariable,
        primalToLocalMap: ScopeSubstitutionMap,
        currentUpstream: IrVariable,
        backPropMethod: IrFunction,
        guardedScope: GuardedScope,
        pullback: IrValueDeclaration?
    ): DerivativeContributions

    fun writeInitCodeForLeaf(
        leaf: CallVariable,
        primalToLocalMap: ScopeSubstitutionMap,
        guardedScope: GuardedScope,
        declarationParent: org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
    ): WrittenDeclarations?
}
