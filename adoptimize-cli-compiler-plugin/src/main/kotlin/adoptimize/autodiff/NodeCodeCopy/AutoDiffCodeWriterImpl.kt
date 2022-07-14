/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.NodeCodeCopy

import adoptimize.autodiff.*
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.diffIR.CallVariable
import adoptimize.autodiff.diffIR.DiffIRFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.util.isSubclassOf
import pluginCommon.ScopeSubstitutionMap
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.IrFunctionGenerator
import pluginCommon.generators.overrideRoot

class AutoDiffCodeWriterImpl(
    val callGenerator: IrBodyGenerator,
    val differentiableApi: DifferentiableApi,
    primalFunction: DiffIRFunction,
    functionGenerator: IrFunctionGenerator,
    context: IrPluginContext
) : AutoDiffCodeWriter {
    val inliner = AutoDiffInliner(callGenerator, differentiableApi, primalFunction)
    val inlinerCustomTypes = AutoDiffCustomTypeInliner(callGenerator, differentiableApi, primalFunction)
    val runtime = AutoDiffOperationOverloadWriter(
        callGenerator,
        differentiableApi,
        functionGenerator,
        context.irBuiltIns
    )
    override fun writeBackpropCodeForLeaf(
        leaf: CallVariable,
        primalToLocalMap: ScopeSubstitutionMap,
        currentUpstream: IrVariable,
        backPropMethod: IrFunction,
        guardedScope: GuardedScope,
        pullback: IrValueDeclaration?
    ): DerivativeContributions {
        return when {
            leaf.callInfo.dependencyNode == null -> runtime.writeBackpropCodeForLeaf(leaf, primalToLocalMap, currentUpstream, backPropMethod, guardedScope, pullback)
            (backPropMethod as IrSimpleFunction).overrideRoot() == differentiableApi.reverseDiffScalarClass.backpropMethod.overrideRoot() -> inliner.writeBackpropCodeForLeaf(leaf, primalToLocalMap, currentUpstream, backPropMethod, guardedScope, pullback)
            else -> inlinerCustomTypes.writeBackpropCodeForLeaf(leaf, primalToLocalMap, currentUpstream, backPropMethod, guardedScope, pullback)
        }
    }

    override fun writeInitCodeForLeaf(
        leaf: CallVariable,
        primalToLocalMap: ScopeSubstitutionMap,
        guardedScope: GuardedScope,
        declarationParent: IrDeclarationParent
    ): WrittenDeclarations? {
        return when {
            leaf.callInfo.dependencyNode == null -> runtime.writeInitCodeForLeaf(leaf, primalToLocalMap, guardedScope, declarationParent)
            declarationParent is IrClass && (declarationParent as IrClass).isSubclassOf(differentiableApi.reverseDiffScalarClass.clazz) -> inliner.writeInitCodeForLeaf(
                leaf, primalToLocalMap, guardedScope,
                declarationParent
            )
            else -> inlinerCustomTypes.writeInitCodeForLeaf(leaf, primalToLocalMap, guardedScope, declarationParent)
        }
    }
}
