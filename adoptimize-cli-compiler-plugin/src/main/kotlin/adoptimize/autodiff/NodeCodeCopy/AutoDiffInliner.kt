/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.NodeCodeCopy

import adoptimize.AutoDiffException
import adoptimize.autodiff.*
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.isPrimalGetter
import adoptimize.autodiff.UnwrapppedNode.CallLowerer
import adoptimize.autodiff.diffIR.CallVariable
import adoptimize.autodiff.diffIR.DiffIRFunction
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.parentAsClass
import pluginCommon.*
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.overrideRoot

/*
* Given a map from the primal to the target and a dispatch function, generate the derivative code by peeking inside the ReverseNode associated with the dispatch function by
* 1) matching the properties of the node to the parameters of the dispatch function
* 2) copying the backprop code out of the node, substituting the local variables associated with the dispatch parameters for the properties associated with the dispatch parameters.
* */
class AutoDiffInliner(
    val callGenerator: IrBodyGenerator,
    val differentiableApi: DifferentiableApi,
    primalFunction: DiffIRFunction
) : AutoDiffCodeWriter {
    private val sourceCodeExtractor = SourceCodeExtractor(callGenerator)
    private val reverseNodeAnalyzer = ReverseNodeAnalyzer(differentiableApi, primalFunction.body)
    val callLowerer = CallLowerer(differentiableApi, callGenerator)

    override fun writeBackpropCodeForLeaf(
        leaf: CallVariable,
        primalToLocalMap: ScopeSubstitutionMap,
        currentUpstream: IrVariable,
        backPropMethod: IrFunction,
        guardedScope: GuardedScope,
        pullback: IrValueDeclaration?
    ): DerivativeContributions {
        if (leaf.callInfo.dependencyNode == null) throw AutoDiffException("The inliner was called but there is no node provided to inline for `${leaf.callInfo.dispatchFunction.name}`")
        val dependencyNode = leaf.callInfo.dependencyNode!!
        val nodePropertiesToDispatchParameters = reverseNodeAnalyzer.reverseNodePropertyInfo(dependencyNode.clazz) ?: throw AutoDiffException("Could not find dependency node info for reverse node property")
        val dependencyPropertyToTargetVariable = mutableMapOf<IrProperty, IrValueDeclaration>()

        // match the properties of the node to the local variables of the target function
        nodePropertiesToDispatchParameters.nodePropertyToDispatchParameters.forEach {
            val primalVariable = leaf.callInfo.valueForIndex(it.dispatchFunctionParameterIndex).argument
            // only map properties whose corresponding primal is active locally
            primalToLocalMap[primalVariable]?.let { x -> dependencyPropertyToTargetVariable[it.property] = x }
        }
        dependencyPropertyToTargetVariable[dependencyNode.upstream] = currentUpstream
        primalToLocalMap[leaf.original]?.let { localVariable -> dependencyPropertyToTargetVariable[dependencyNode.primal] = localVariable }
        val srcToTarget = ScopeSubstitutionMap().also { it.push() }
        val derivativeContributions = mutableListOf<Pair<IrValueDeclaration, IrValueDeclaration>>()

        // write the derivatives into the backprop. Note that we iterate through the active properties because that is how we retrieve the snippet of code to copy.
        // As long as the dependencyPropertyToTargetVariable map is complete, we should be able to perform all the necessary substitutions.
        // Also note that we need to consider whether or not the target variable associated with the active property is active because although it's possible for that
        // variable to be active (we have code for it), it's not necessarily active in the context of the primal function we are optimizing
        nodePropertiesToDispatchParameters.nodePropertyToDispatchParameters.filter { it.type == NodeParameterType.Active }.forEach {
            val unwrappedPrimalValueArgument = leaf.callInfo.valueForIndex(it.dispatchFunctionParameterIndex)
            if (unwrappedPrimalValueArgument.isActive) {
                val unwrappedPrimalValue = unwrappedPrimalValueArgument.argument
                val rootExpressionOfDerivativeStatement = nodePropertiesToDispatchParameters.activePropertyToUpstreamExpression[it.property]
                if (rootExpressionOfDerivativeStatement != null) {
                    val sourceCode = sourceCodeExtractor.fullTreeForSnippet(rootExpressionOfDerivativeStatement)
                    val copyAndReplacer = CopyAndReplacer(ScopeSubstitutionMapSubstitutor(srcToTarget), Substitutor.emptySubstitutor(), callGenerator.pluginContext.irBuiltIns)
                    val targetStatements = sourceCode.map { sourceStatement ->
                        copyAndReplacer.copyAndReplace(
                            sourceStatement,
                            object : ReplaceDelegate {
                                override fun replaceCandidateWith(
                                    original: IrVariable,
                                    candidateCopy: IrVariable
                                ): IrVariable {
                                    val rhs = original.initializer
                                    return when {
                                        rhs is IrCall &&
                                            rhs.symbol.owner.isPropertyAccessor &&
                                            dependencyPropertyToTargetVariable[rhs.symbol.owner.correspondingPropertySymbol!!.owner] == currentUpstream -> currentUpstream
                                        // if the original statement in the pullback code is 'val x = this.primal', replace occurrences of 'x' in the pullback with the output of the primal function. To test that this code is needed,
                                        // add a test that includes a call to a pullback that references the primal property.
                                        rhs is IrCall && rhs.symbol.owner.isPrimalGetter(differentiableApi) && rhs.dispatchReceiver?.let { getVariable(it) == dependencyNode.backpropMethod.dispatchReceiverParameter!! } == true -> {
                                            primalToLocalMap[leaf.original]?.let { it as IrVariable } ?: throw AutoDiffException("${leaf.original.name} not found")
                                        }
                                        else -> guardedScope.tryPutStatelessVariable(candidateCopy)
                                    }
                                }

                                override fun replaceCandidateWith(original: IrCall, candidateCopy: IrCall): IrExpression? {
                                    val function = original.symbol.owner
                                    return if (function.isPropertyAccessor) {
                                        val correspondingLocalVariable = dependencyPropertyToTargetVariable[original.symbol.owner.correspondingPropertySymbol!!.owner]
                                        when {
                                            correspondingLocalVariable != null -> callGenerator.generateGetValue(correspondingLocalVariable)
                                            function.overrideRoot() == differentiableApi.reverseDiffScalarClass.primalProperty.getter!!.overrideRoot() -> candidateCopy.dispatchReceiver
                                            else -> null
                                        }
                                    } else {
                                        with(callLowerer) {
                                            if (candidateCopy.needsLower()) {
                                                lowerFunction(original, candidateCopy, original.type)
                                            } else null
                                        }
                                    }
                                }
                            },
                            backPropMethod
                        )
                    }
                    val targetDerivativeForChild = targetStatements.last() as IrValueDeclaration
                    derivativeContributions.add(Pair(unwrappedPrimalValue, targetDerivativeForChild))
                } else {
                    throw AutoDiffException("No upstream derivative code found for active property ${it.property.name} of ${it.property.parentAsClass.name}")
                }
            }
        }
        return derivativeContributions
    }

    override fun writeInitCodeForLeaf(
        leaf: CallVariable,
        primalToLocalMap: ScopeSubstitutionMap,
        guardedScope: GuardedScope,
        declarationParent: org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
    ): WrittenDeclarations {
        val copyAndReplacer = CopyAndReplacer(ScopeSubstitutionMapSubstitutor(primalToLocalMap), Substitutor.emptySubstitutor(), callGenerator.pluginContext.irBuiltIns)
        val targetStatement = copyAndReplacer.copyAndReplace(
            leaf.original,
            object : ReplaceDelegate {
                override fun replaceCandidateWith(
                    original: IrVariable,
                    candidateCopy: IrVariable
                ): IrVariable = guardedScope.tryPutStatelessVariable(candidateCopy)
            },
            declarationParent
        ) as IrValueDeclaration
        return Primal(targetStatement)
    }
}
