/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff

import adoptimize.AutoDiffException
import adoptimize.autodiff.Metadata.ActiveParameterRequirement
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.ParamMapType
import adoptimize.autodiff.Metadata.ReverseScalarClass
import adoptimize.autodiff.Metadata.isConstantScalar
import adoptimize.autodiff.Metadata.isDifferentiableScalar
import adoptimize.autodiff.Metadata.primitiveOne
import adoptimize.autodiff.Metadata.primitiveZero
import adoptimize.autodiff.Metadata.unboxDScalarExpression
import adoptimize.autodiff.UnwrapppedNode.CallLowerer
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.name.Name
import pluginCommon.*
import pluginCommon.generators.*

/**
 * TODO
 * Given: a function of the form
 * fun foo(x:DType):DType {
 *   class UnboxedReverse : ReverseType { ... }
 *   class Reverse : ReverseType {...}
 *   val i0 = ...
 *   val i1 = ...
 *   ...
 *   return iN
 * }
 *
 * Expected transform:
 *
 * fun foo(x:DType):DType {
 *   class UnboxedReverse : ReverseType { ... }
 *   class Reverse : ReverseType {...}
 *   return when(x){
 *     is ReverseType -> {
 *          when(x.primal) {
 *              is BoxedPrimitive -> UnboxedReverse(x)
 *              else -> Reverse(x)
 *          }
 *     }
 *     is BoxedPrimitive -> {
 *        // unboxed old implementation here
 *     }
 *     else -> {
 *       // old implementation here
 *     }
 *   }
 * }
 *
 * but for now it just replaces the body with a call to instantiate the Reverse Node:
 *
 * fun foo(x:DType):DType {
 *   class Reverse(a:ReverseType) : ReverseType {...}
 *   return Reverse(x as ReverseType)
 *
 *   In order to transform the primal properly, we need the following pieces of information:
 *   1. the revese class info
 * }
 */
class PrimalFunctionTransformer(
    val irBodyGenerator: IrBodyGenerator,
    val irFunctionGenerator: IrFunctionGenerator,
    val differentiableApi: DifferentiableApi,
    val pluginContext: IrPluginContext
) {
    private fun IrExpression.primal() = irBodyGenerator.generateGetProperty(this, differentiableApi.reverseDiffScalarClass.primalProperty.name)
    private fun IrExpression.value() = irBodyGenerator.generateGetProperty(this, differentiableApi.boxedPrimitiveInfo.valueProperty.name)
    private fun IrExpression.typeTest(type: IrSimpleType) = IrTypeOperatorCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        pluginContext.irBuiltIns.booleanType,
        IrTypeOperator.INSTANCEOF,
        typeOperand = type,
        argument = this
    )
    private fun ActiveParameterRequirement.toType(): IrSimpleType {
        return when (this) {
            ActiveParameterRequirement.Reverse -> differentiableApi.reverseDiffScalarClass.clazz.defaultType
            ActiveParameterRequirement.Forward -> differentiableApi.forwardDiffScalarClass.clazz.defaultType
            ActiveParameterRequirement.Constant -> differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType
        }
    }
    private fun ReverseScalarClass.createInstance() = irBodyGenerator.generateConstructorCall(this.clazz, this.getArgs(differentiableApi), emptyList())
    fun invokeOptimized(primalFunction: IrSimpleFunction, indexOfActiveParameter: Int, reverseScalarClasses: List<ReverseScalarClass>) {
        val primalBody = primalFunction.body as? IrBlockBody ?: throw AutoDiffException("expected primal to have a body: ${primalFunction.name}")
        val activeParameter = primalFunction.allParametersWithIndex().first { it.index == indexOfActiveParameter }.valueDescriptor
        val localFunction = irFunctionGenerator.generateLambda(primalFunction, emptyList(), primalFunction.returnType) { localFunction ->
            val copyAndReplacer = CopyAndReplacer(
                MapWrapper(
                    primalFunction.valueParameters.zip(primalFunction.valueParameters).toMap().toMutableMap<IrValueDeclaration, IrValueDeclaration>().also {
                        primalFunction.dispatchReceiverParameter?.let { p -> it.put(p, p) }
                        primalFunction.extensionReceiverParameter?.let { p -> it.put(p, p) }
                    }
                ),
                Substitutor.emptySubstitutor(), pluginContext.irBuiltIns
            )
            localFunction.body = IrBlockBodyImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                primalBody.statements.map {
                    copyAndReplacer.copyAndReplace(
                        it,
                        object : ReplaceDelegate {
                            override fun replaceReturnStatement(
                                original: IrReturn,
                                candidateCopy: IrReturn
                            ): IrExpression? {
                                return IrReturnImpl(candidateCopy.startOffset, candidateCopy.endOffset, candidateCopy.type, localFunction.symbol, candidateCopy.value)
                            }
                        },
                        localFunction
                    ) as IrStatement
                }
            )
        }

        fun invokeOriginalFunction(): IrCall {
            return irBodyGenerator.generateCall(localFunction, emptyList(), null, null)
        }
        class TypeConditionalExpression(val activeParameterTypeRequirements: List<ActiveParameterRequirement>, val result: () -> List<IrStatement>)
        fun buildWhen(
            target: () -> IrExpression,
            typeConditions: Map<ActiveParameterRequirement, List<TypeConditionalExpression>>,
            typeCheckIndex: Int,
            elseCondition: () -> List<IrStatement>
        ): IrWhen {
            val updatedIndex = typeCheckIndex + 1
            val branches = typeConditions.map {
                val notsatisfied = it.value.filter {
                    val numberOfRemainingTypeChecks = it.activeParameterTypeRequirements.size - updatedIndex
                    numberOfRemainingTypeChecks > 0
                }
                val satisfied = it.value.minus(notsatisfied)
                if (satisfied.size > 1) {
                    throw AutoDiffException("Cannot have two nodes that satisfy the same type requirements")
                }
                val nextTypeConditions = notsatisfied.groupBy { it.activeParameterTypeRequirements[updatedIndex] }.toMap()
                val nextElseCondition: () -> List<IrStatement> = if (satisfied.isEmpty()) {
                    elseCondition
                } else {
                    satisfied.first().result
                }
                val branchResult = if (nextTypeConditions.isNotEmpty()) {
                    listOf(
                        buildWhen(
                            target = { target().primal() },
                            typeConditions = nextTypeConditions,
                            typeCheckIndex = updatedIndex,
                            elseCondition = nextElseCondition
                        )
                    )
                } else {
                    nextElseCondition()
                }
                IrBodyGenerator.Branch(
                    target().typeTest(it.key.toType()),
                    branchResult
                )
            }
            return irBodyGenerator.whenStatementWithElse(branches, elseCondition(), primalFunction.returnType)
        }

        val typeConditions = reverseScalarClasses.map { TypeConditionalExpression(it.activeInputTypeRequirements, { listOf<IrStatement>(it.createInstance()) }) }
            .groupBy { it.activeParameterTypeRequirements.first() }.toMap()
        fun createUnboxedForwardsBlock(): List<IrStatement> {
            val unboxedForwardsStatements = mutableListOf<IrStatement>()
            val substitutionMap: MapWrapper<IrValueDeclaration, IrValueDeclaration> =
                MapWrapper(
                    (primalFunction).allParameters().map {
                        val image = when {
                            differentiableApi.isDifferentiableScalar(it.type) -> {
                                // cast to boxed primal type
                                val cast = irBodyGenerator.generateCast(
                                    irBodyGenerator.generateGetValue(it),
                                    differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType
                                )
                                // get the value
                                val getValue = irBodyGenerator.generateGetProperty(
                                    cast,
                                    differentiableApi.boxedPrimitiveInfo.valueProperty.name
                                )
                                val valueDeclaration =
                                    irBodyGenerator.generateVal(Name.identifier("$${it.name}"), primalFunction, getValue)
                                unboxedForwardsStatements.add(valueDeclaration)
                                valueDeclaration
                            }
                            differentiableApi.isConstantScalar(it.type as IrSimpleType) -> {
                                // get the value
                                val getValue = irBodyGenerator.generateGetProperty(
                                    irBodyGenerator.generateGetValue(it),
                                    differentiableApi.boxedPrimitiveInfo.valueProperty.name
                                )
                                val valueDeclaration =
                                    irBodyGenerator.generateVal(Name.identifier("$${it.name}"), primalFunction, getValue)
                                unboxedForwardsStatements.add(valueDeclaration)
                                valueDeclaration
                            }
                            else -> {
                                it
                            }
                        }
                        Pair(it, image)
                    }.toMap().toMutableMap()
                )
            return unboxedForwardsStatements + unbox(
                differentiableApi,
                primalBody.statements,
                substitutionMap,
                primalFunction
            )
        }
        val forwardsTypeCondition = mapOf(
            ActiveParameterRequirement.Constant to
                listOf(TypeConditionalExpression(listOf(ActiveParameterRequirement.Constant), ::createUnboxedForwardsBlock))
        )
        val newBody = buildWhen(
            target = { irBodyGenerator.generateGetValue(activeParameter) },
            typeConditions = typeConditions + forwardsTypeCondition,
            typeCheckIndex = 0,
            elseCondition = { listOf<IrStatement>(invokeOriginalFunction()) }
        )
        pluginContext.symbolTable.buildWithScope(primalFunction) { function ->
            function.body = irBodyGenerator.generateBlockBody(
                statements = listOf(localFunction) + reverseScalarClasses.map { it.clazz },
                returnExpression = newBody,
                function = function
            )
        }
    }

    // TODO: get these in terms of the type requirements on the active variable.
    // if the ith parameter is active, then the argument bound the ith parameter should be unwrapped and casted according
    // to the index and value of the Active Parameter Requirement
    // https://github.com/facebookresearch/optimizer-plugins/issues/154
    private fun ReverseScalarClass.getArgs(differentiableApi: DifferentiableApi): List<IrExpression> {
        return parameterMaps.map {
            val getParameter = irBodyGenerator.generateGetValue(
                parent.allParametersWithIndex().first { p ->
                    p.index == it.functionIndex
                }.valueDescriptor
            )
            when (it.type) {
                ParamMapType.Unbox -> {
                    val getPrimal = getParameter.primal()
                    val getPrimalAsBox = when {
                        getPrimal.type == differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType -> getPrimal
                        else -> irBodyGenerator.generateCast(getPrimal, differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType)
                    }
                    getPrimalAsBox.value()
                }
                ParamMapType.CastToReverse -> {
                    irBodyGenerator.generateCast(getParameter, differentiableApi.reverseDiffScalarClass.clazz.defaultType)
                }
                ParamMapType.ConstPrimalPrimal -> {
                    val getPrimal = getParameter.primal().primal()
                    val getPrimalAsBox = when {
                        getPrimal.type == differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType -> getPrimal
                        else -> irBodyGenerator.generateCast(getPrimal, differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType)
                    }
                    getPrimalAsBox.value()
                }
                ParamMapType.ForwardPrimal -> irBodyGenerator.generateCast(
                    getParameter.primal(),
                    differentiableApi.forwardDiffScalarClass.clazz.defaultType
                )
                ParamMapType.NoOp -> getParameter
            }
        }
    }

    private fun unbox(differentiableApi: DifferentiableApi, statements: List<IrStatement>, substitutionMap: MapWrapper<IrValueDeclaration, IrValueDeclaration>, newParent: IrSimpleFunction): List<IrStatement> {
        val primalCopier = CopyAndReplacer(substitutionMap, Substitutor.emptySubstitutor(), pluginContext.irBuiltIns)
        val zeroRoot = differentiableApi.boxedPrimitiveInfo.scalarZeroObjectProperty.getter!!.symbol.owner.overrideRoot()
        val oneRoot = differentiableApi.boxedPrimitiveInfo.scalarOneObjectProperty.getter!!.symbol.owner.overrideRoot()
        val callLowerer = CallLowerer(differentiableApi, irBodyGenerator)
        return statements.map {
            primalCopier.copyAndReplace(
                it,
                object : ReplaceDelegate {
                    override fun replaceReturnStatement(original: IrReturn, candidateCopy: IrReturn): IrExpression {
                        return irBodyGenerator.generateConstructorCall(differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass, listOf(candidateCopy.value), emptyList())
                    }

                    override fun replaceCandidateWith(original: IrVariable, candidateCopy: IrVariable): IrVariable {
                        if (candidateCopy.initializer == null && original.type.isSubtypeOfClass(differentiableApi.scalarRoot.symbol)) {
                            candidateCopy.type = differentiableApi.boxedPrimitiveInfo.primitiveType
                        }
                        return candidateCopy
                    }

                    override fun replaceCandidateWith(original: IrCall, candidateCopy: IrCall): IrExpression? {
                        val noDifferentiableParameters = original.allArgumentTypes().none { it.isSubtypeOfClass(differentiableApi.rootDifferentiableType.classOrNull!!) }
                        val expectedReturnType = if (original.type.isSubtypeOfClass(differentiableApi.rootDifferentiableType.classifierOrFail as IrClassSymbol)) differentiableApi.boxedPrimitiveInfo.primitiveType else original.type
                        return when {
                            candidateCopy.symbol.owner.isPropertyAccessor && original.symbol.owner.overrideRoot() == zeroRoot -> differentiableApi.primitiveZero()
                            candidateCopy.symbol.owner.isPropertyAccessor && original.symbol.owner.overrideRoot() == oneRoot -> differentiableApi.primitiveOne()
                            noDifferentiableParameters && candidateCopy.type.isSubtypeOfClass(differentiableApi.scalarRoot.symbol) -> differentiableApi.unboxDScalarExpression(candidateCopy, irBodyGenerator)
                            else -> callLowerer.lowerFunction(original, candidateCopy, expectedReturnType)
                        }
                    }
                },
                newParent
            ) as IrStatement
        }
    }
}
