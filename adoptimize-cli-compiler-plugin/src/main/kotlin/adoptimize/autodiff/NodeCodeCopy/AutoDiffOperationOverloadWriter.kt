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
import adoptimize.autodiff.diffIR.CallVariable
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name
import pluginCommon.ScopeSubstitutionMap
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.IrFunctionGenerator
import pluginCommon.generators.ParameterInfo
import pluginCommon.generators.TypeParameterContext

class AutoDiffOperationOverloadWriter(
    val callGenerator: IrBodyGenerator,
    val differentiableApi: DifferentiableApi,
    val functionGenerator: IrFunctionGenerator,
    val irBuiltIns: IrBuiltIns
) : AutoDiffCodeWriter {
    private val rootType = differentiableApi.scalarRoot.defaultType
    private val functionType by lazy {
        val functionClassSymbol: IrClassSymbol = irBuiltIns.functionN(1).symbol
        val tpe = IrSimpleTypeImpl(functionClassSymbol, false, listOf(rootType as IrSimpleTypeImpl, rootType), emptyList())
        tpe
    }

    override fun writeBackpropCodeForLeaf(
        leaf: CallVariable,
        primalToLocalMap: ScopeSubstitutionMap,
        currentUpstream: IrVariable,
        backPropMethod: IrFunction,
        guardedScope: GuardedScope,
        pullback: IrValueDeclaration?
    ): DerivativeContributions {
        if (pullback == null) throw AutoDiffException("Expected a custom pullback")
        val pullbackClassifier: IrClass = pullback.type.classifierOrFail.owner as IrClass
        val pullbackSimpleType = pullback.type as? IrSimpleType ?: throw AutoDiffException("Expected the pullback type to be a simple type but received ${pullback.type.render()}")
        val typeMap: Map<IrTypeParameterSymbol, IrType> = when (val typeMap = TypeParameterContext.invoke(pullbackClassifier, pullbackSimpleType.arguments, irBuiltIns)) {
            is TypeParameterContext.Success -> typeMap.typeParameterMap
            is TypeParameterContext.Failure -> throw AutoDiffException("Failed to collect argument map for pullback function: ${typeMap.errorMessage}")
        }
        val getPb: IrExpression = callGenerator.generateGetValue(pullback)
        val arguments = listOf(callGenerator.generateGetValue(currentUpstream))
        val invoke = pullbackClassifier.functions.firstOrNull { it.name.toString() == "invoke" }
            ?: throw AutoDiffException("Could not find the invoke operation on the pullback property ${pullback.name}")
        val invokePullback = callGenerator.generateCall(invoke, arguments, getPb, null).also {
            val symbol = it.type.classifierOrFail
            if (symbol is IrTypeParameterSymbol) {
                it.type = typeMap[symbol] ?: throw AutoDiffException("Unrecognized symbol encountered. No ${symbol.owner.render()} found.")
            }
        }
        val singleContribution = guardedScope.tryPutStatelessVariable(callGenerator.generateVal(Name.identifier("derivative_${counter++}"), backPropMethod, invokePullback))
        val activeArgument = leaf.singleActiveArgument() ?: throw AutoDiffException("The leaf ${leaf.name} did not have a single active argument")
        return listOf(Pair(activeArgument.second, singleContribution))
    }

    // instead of copying the primal code, we will call `primalAndPullback` and store them in properties in the generated class.
    // Note that the value declaration returned corresponds to the primal value. The pullback is stored as a property to be used in the
    // backprop
    override fun writeInitCodeForLeaf(
        activeLeaf: CallVariable,
        primalToLocalMap: ScopeSubstitutionMap,
        guardedScope: GuardedScope,
        declarationParent: org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
    ): WrittenDeclarations? {
        // for now we assume unary functions.
        val original = activeLeaf.original
        when (val exp = original.initializer) {
            is IrCall -> {
                val fnc = exp.symbol.owner
                if (fnc.modality != Modality.ABSTRACT) {
                    //  throw AutoDiffException("using OO for ${leaf.name} when it should be inlinable")
                    println("using OO for ${activeLeaf.name} when it should be inlinable")
                }
            }
        }
        val activeArgumentWithIndex = activeLeaf.singleActiveArgument()
        if (activeArgumentWithIndex == null) {
            throw AutoDiffException("Cannot call into OO framework with zero active arguments")
        } else {
            val (index, activeArgument) = activeArgumentWithIndex
            val imageActiveArgument = primalToLocalMap[activeArgument]
                ?: throw AutoDiffException("Could not find node image for primal ${activeArgument.name}")
            val getImageActiveArgument = callGenerator.generateGetValue(imageActiveArgument)
            val function = functionGenerator.generateLambda(
                declarationParent,
                listOf(ParameterInfo(Name.identifier("lambdaParameter"), imageActiveArgument.type)),
                activeLeaf.callInfo.dispatchFunction.returnType
            ) { lambda ->
                val args = activeLeaf.callInfo.arguments.withIndex().map {
                    if (it.index == index) {
                        lambda.valueParameters.first()
                    } else {
                        primalToLocalMap[it.value.argument]
                            ?: throw AutoDiffException("Could not find node image for primal ${it.value.argument.name}")
                    }
                }
                val dispatch: IrGetValue? = when {
                    index == -1 -> callGenerator.generateGetValue(lambda.valueParameters.first())
                    activeLeaf.callInfo.dispatchReceiver != null -> {
                        val d = primalToLocalMap[activeLeaf.callInfo.dispatchReceiver.argument]
                            ?: throw AutoDiffException("Could not find node image for primal ${activeLeaf.callInfo.dispatchReceiver.argument}")
                        callGenerator.generateGetValue(d)
                    }
                    else -> null
                }
                val extension = when {
                    index == -2 -> callGenerator.generateGetValue(lambda.valueParameters.first())
                    activeLeaf.callInfo.extensionReceiver != null -> {
                        val d = primalToLocalMap[activeLeaf.callInfo.extensionReceiver.argument]
                            ?: throw AutoDiffException("Could not find node image for primal ${activeLeaf.callInfo.extensionReceiver.argument}")
                        callGenerator.generateGetValue(d)
                    }
                    else -> null
                }
                val invoke = callGenerator.generateCall(
                    activeLeaf.callInfo.dispatchFunction as? IrSimpleFunction
                        ?: throw AutoDiffException("Expected the dispatch function to be a simple function"),
                    args.map { a -> callGenerator.generateGetValue(a) },
                    dispatch,
                    extension
                )
                val returnInvoke = IrReturnImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    irBuiltIns.nothingType,
                    lambda.symbol,
                    invoke
                )
                lambda.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, listOf(returnInvoke))
            }
            val functionExpression = IrFunctionExpressionImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                functionType,
                function,
                IrStatementOrigin.LAMBDA
            )
            val primalAndPullbackCall = callGenerator.generateCall(
                differentiableApi.primalAndPullbackFunction,
                listOf(getImageActiveArgument, functionExpression),
                null,
                null
            )
            val primalAndPullback = guardedScope.tryPutStatelessVariable(
                callGenerator.generateVal(
                    Name.identifier("primal_pullback_${activeLeaf.name}_${counter++}"),
                    declarationParent,
                    primalAndPullbackCall
                )
            )
            val primal = guardedScope.tryPutStatelessVariable(
                callGenerator.generateVal(
                    Name.identifier("${activeLeaf.name}_primal"),
                    declarationParent,
                    callGenerator.generateGetProperty(
                        callGenerator.generateGetValue(primalAndPullback),
                        Name.identifier("first")
                    )
                )
            )
            val pullback = guardedScope.tryPutStatelessVariable(
                callGenerator.generateVal(
                    Name.identifier("${activeLeaf.name}_pb"),
                    declarationParent,
                    callGenerator.generateGetProperty(
                        callGenerator.generateGetValue(primalAndPullback),
                        Name.identifier("second")
                    )
                )
            )
            return PrimalAndPullback(primal, pullback)
        }
    }

    private fun CallVariable.singleActiveArgument(): Pair<Int, IrValueDeclaration>? {
        val leaf = this
        val activeValueArguments = leaf.callInfo.arguments.withIndex().filter { it.value.isActive }.map { Pair(it.index, it.value.argument) }
        var activeArgument: Pair<Int, IrValueDeclaration>? = null
        when {
            activeValueArguments.size > 1 -> {
                throw AutoDiffException("Only unary non-optimizable functions are supported atm. Offender: ${leaf.callInfo.dispatchFunction.name}")
            }
            activeValueArguments.size == 0 -> {
                if (leaf.callInfo.dispatchReceiver?.isActive == true) {
                    activeArgument = Pair(-1, leaf.callInfo.dispatchReceiver!!.argument)
                    if (leaf.callInfo.extensionReceiver?.isActive == true) {
                        throw AutoDiffException("Only unary non-optimizable functions are supported atm. Offender: ${leaf.callInfo.dispatchFunction.name}")
                    }
                } else {
                    if (leaf.callInfo.extensionReceiver?.isActive == true) {
                        activeArgument = Pair(-2, leaf.callInfo.extensionReceiver!!.argument)
                    }
                }
            }
            else -> {
                if (leaf.callInfo.dispatchReceiver?.isActive == true || leaf.callInfo.extensionReceiver?.isActive == true) {
                    throw AutoDiffException("Only unary non-optimizable functions are supported atm. Offender: ${leaf.callInfo.dispatchFunction.name}")
                }
                activeArgument = activeValueArguments.first()
            }
        }
        return activeArgument
    }

    companion object {
        private var counter = 0
    }
}
