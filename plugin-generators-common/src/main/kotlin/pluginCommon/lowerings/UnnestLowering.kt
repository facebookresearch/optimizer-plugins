/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.lowerings

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.Name
import pluginCommon.*
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.allParameters
import pluginCommon.generators.copy
import java.util.*

class UnnestLowering(val bodyGenerator: IrBodyGenerator) : DeclarationWithBodyLowering {
    private var counter = 0
    private val unnestOriginReplacements = mapOf(
        IrStatementOrigin.PLUSEQ to IrStatementOrigin.PLUS,
        IrStatementOrigin.MINUSEQ to IrStatementOrigin.MINUS,
        IrStatementOrigin.MULTEQ to IrStatementOrigin.MUL,
        IrStatementOrigin.DIVEQ to IrStatementOrigin.DIV
    )

    private fun lower(target: IrBody, container: IrDeclarationParent, valueParameters: List<IrValueParameter>): IrBody {
        fun varName() = Name.identifier("\$_unnester_iv_${counter++}")
        val transformer = object : IrElementTransformerVoid() {
            private var context = Stack<MutableList<IrStatement>>()
            private var parentElement = Stack<IrElement>()
            private var newVariables = mutableMapOf<IrValueDeclaration, IrValueDeclaration>().also { it.putAll(valueParameters.zip(valueParameters).toMap()) }

            override fun visitBlockBody(body: IrBlockBody): IrBody {
                val newBody = IrBlockBodyImpl(body.startOffset, body.endOffset, body.statements)
                parentElement.push(newBody)
                context.push(mutableListOf<IrStatement>())
                newBody.statements.forEach {
                    it.transform(this, null) as IrStatement
                }
                parentElement.pop()
                newBody.statements.clear()
                newBody.statements.addAll(context.pop())
                return newBody
            }

            override fun visitBlock(expression: IrBlock): IrExpression {
                val newExpression = IrBlockImpl(expression.startOffset, expression.endOffset, expression.type, expression.origin, expression.statements)
                parentElement.push(newExpression)
                context.push(mutableListOf<IrStatement>())
                newExpression.statements.forEach {
                    it.transform(this, null) as IrStatement
                }
                parentElement.pop()
                newExpression.statements.clear()
                newExpression.statements.addAll(context.pop())
                return newExpression
            }

            override fun visitWhileLoop(loop: IrWhileLoop): IrExpression {
                val newLoop = IrWhileLoopImpl(loop.startOffset, loop.endOffset, loop.type, loop.origin).also { it.condition = loop.condition; it.body = loop.body }
                parentElement.push(newLoop)

                // collect condition statements
                context.push(mutableListOf())
                val conditionValue: IrGetValue = when (val v = loop.condition.transform(this, null)) {
                    is IrCall -> {
                        val variable = bodyGenerator.generateVal(varName(), container, v)
                        context.peek().add(variable)
                        bodyGenerator.generateGetValue(variable)
                    }
                    is IrGetValue -> v
                    else -> throw NotImplementedError("Expected a call or get value for the condition of a while in the unnester")
                }
                val conditionStatements = context.pop()

                // add condition statements to parent scope
                context.peek().addAll(conditionStatements)

                when {
                    conditionStatements.isNotEmpty() -> {
                        val conditionVariable = bodyGenerator.generateIrVariable(varName(), container, conditionValue, isVar = true)
                        context.peek().add(conditionVariable)
                        newLoop.condition = bodyGenerator.generateGetValue(conditionVariable)

                        // add condition statements to the child scope and update the condition variable after the body
                        val copyAndReplacer = CopyAndReplacer(MapWrapper(newVariables.values.zip(newVariables.values).toMap().toMutableMap()), Substitutor.emptySubstitutor(), bodyGenerator.pluginContext.irBuiltIns)
                        val conditionStatementsChild = conditionStatements.map {
                            val image = copyAndReplacer.copyAndReplace(it, ReplaceDelegate.emptyReplacer, container) as IrStatement
                            Pair(it, image)
                        }.toMap()
                        val key = conditionStatements.firstOrNull { it is IrVariable && it.symbol == conditionValue.symbol }
                        val bodyConditionValue = when {
                            key != null -> bodyGenerator.generateGetValue(conditionStatementsChild[key]!! as IrValueDeclaration)
                            else -> copyAndReplacer.copyAndReplace(conditionValue, ReplaceDelegate.emptyReplacer, container) as IrExpression
                        }
                        val updateConditionVariable = listOf(
                            bodyGenerator.generateSetVariable(
                                variable = conditionVariable,
                                value = bodyConditionValue
                            )
                        )
                        val body = loop.body?.transform(this, null)
                        newLoop.body = when (val b = body) {
                            is IrBlock -> IrBlockImpl(b.startOffset, b.endOffset, b.type, b.origin, b.statements + conditionStatementsChild.values + updateConditionVariable)
                            is IrExpression -> IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, b.type, null, listOf(b) + conditionStatementsChild.values + updateConditionVariable)
                            else -> throw NotImplementedError("Unexpected value for copied body")
                        }
                    }
                    else -> {
                        newLoop.condition = conditionValue
                        newLoop.body = loop.body?.transform(this, null)
                    }
                }
                parentElement.pop()
                newLoop.addIfTopLevel()
                return newLoop
            }

            override fun visitWhen(expression: IrWhen): IrExpression {
                val newWhen = IrWhenImpl(expression.startOffset, expression.endOffset, expression.type, expression.origin, expression.branches)
                parentElement.push(expression)
                newWhen.branches.clear()
                newWhen.branches.addAll(expression.branches.map { it.transform(this, null) })
                parentElement.pop()
                if (!newWhen.type.isUnit() && !newWhen.branches.any { it.condition.isTrueConst() }) {
                    newWhen.branches.add(
                        IrElseBranchImpl(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, bodyGenerator.pluginContext.irBuiltIns.booleanType, true),
                            bodyGenerator.throwException("Not implemented")
                        )
                    )
                }
                newWhen.addIfTopLevel()
                return newWhen
            }

            override fun visitBranch(branch: IrBranch): IrBranch {
                val newBranch = IrBranchImpl(branch.condition, branch.result)
                parentElement.push(newBranch)
                newBranch.condition = branch.condition.transform(this, null)
                newBranch.result = branch.result.transform(this, null)
                parentElement.pop()
                return newBranch
            }

            override fun visitVariable(declaration: IrVariable): IrStatement = IrVariableImpl(
                declaration.startOffset,
                declaration.endOffset,
                declaration.origin,
                IrVariableSymbolImpl(),
                declaration.name,
                declaration.type,
                declaration.isVar,
                declaration.isConst,
                declaration.isLateinit
            ).also {
                parentElement.push(it)
                it.initializer = declaration.initializer?.transform(this, null)
                it.parent = container
                parentElement.pop()
                newVariables[declaration] = it
                it.addIfTopLevel()
            }

            override fun visitReturn(expression: IrReturn): IrExpression {
                parentElement.push(expression)
                val value = expression.value.transform(this, null)
                parentElement.pop()
                return IrReturnImpl(expression.startOffset, expression.endOffset, expression.type, expression.returnTargetSymbol, value).also { it.addIfTopLevel() }
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
                val newTypeOperator = IrTypeOperatorCallImpl(expression.startOffset, expression.endOffset, expression.type, expression.operator, expression.typeOperand, expression.argument).also {
                    parentElement.push(it)
                    it.argument = expression.argument.transform(this, null)
                    parentElement.pop()
                }
                val newExpression = if (parentElement.peek().shouldUnnest()) {
                    val variable = bodyGenerator.generateVal(varName(), container, newTypeOperator)
                    context.peek().add(variable)
                    bodyGenerator.generateGetValue(variable)
                } else newTypeOperator
                newExpression.addIfTopLevel()
                return newExpression
            }

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val transformedExpression = newVariables[expression.symbol.owner]?.let { IrGetValueImpl(expression.startOffset, expression.endOffset, it.symbol) }
                    ?: throw PluginCodegenException("a reference to a variable not in the copied context remains: ${expression.symbol.owner.name}")
                transformedExpression.addIfTopLevel()
                return transformedExpression
            }

            override fun visitCall(expression: IrCall): IrExpression {
                fun copyWithOrigin(origin: IrStatementOrigin?) = IrCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    expression.symbol,
                    expression.typeArgumentsCount,
                    expression.valueArgumentsCount,
                    origin,
                    expression.superQualifierSymbol
                ).also {
                    parentElement.push(it)
                    it.extensionReceiver = expression.extensionReceiver?.transform(this, null)
                    it.dispatchReceiver = expression.dispatchReceiver?.transform(this, null)
                    (0 until expression.valueArgumentsCount).forEach { i -> it.putValueArgument(i, expression.getValueArgument(i)!!.transform(this, null)) }
                    parentElement.pop()
                }
                val shouldUnnest = parentElement.peek().shouldUnnest()
                if (expression.symbol == bodyGenerator.pluginContext.irBuiltIns.andandSymbol) {
                    if (expression.valueArgumentsCount != 2) {
                        throw IllegalStateException("ANDAND has 2 value arguments")
                    }
                    // get expression for the first argument
                    val firstExpression = expression.getValueArgument(0)!!.transform(this, null)
                    val lateinitVar = bodyGenerator.generateIrVariable(varName(), container, bodyGenerator.pluginContext.irBuiltIns.booleanType, isVar = true)
                    context.peek().add(lateinitVar)
                    context.push(mutableListOf())
                    val secondExpression = when (val e = expression.getValueArgument(1)!!.transform(this, null)) {
                        is IrCall -> {
                            val variable = bodyGenerator.generateVal(varName(), container, e)
                            context.peek().add(variable)
                            bodyGenerator.generateGetValue(variable)
                        }
                        is IrGetValue -> e
                        else -> throw NotImplementedError("expected a call or get value in second argument of ANDAND for unnest")
                    }
                    val setValue = bodyGenerator.generateSetVariable(lateinitVar, secondExpression)
                    context.peek().add(setValue)
                    val secondExpressionStatements = context.pop()
                    val falseValue = IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, bodyGenerator.pluginContext.irBuiltIns.booleanType, false)
                    val whenStatement = bodyGenerator.whenStatementWithElse(
                        listOf(
                            IrBodyGenerator.Branch(
                                testExpression = firstExpression,
                                result = secondExpressionStatements
                            )
                        ),
                        elseStatements = listOf(bodyGenerator.generateSetVariable(lateinitVar, falseValue)),
                        type = setValue.type
                    )
                    context.peek().add(whenStatement)
                    return bodyGenerator.generateGetValue(lateinitVar)
                }

                val copy = copyWithOrigin(unnestOriginReplacements[expression.origin] ?: expression.origin)
                return when {
                    shouldUnnest -> {
                        val variable = bodyGenerator.generateVal(varName(), container, copy)
                        context.peek().add(variable)
                        bodyGenerator.generateGetValue(variable)
                    }
                    else -> copy
                }.also { it.addIfTopLevel() }
            }

            override fun visitSetValue(expression: IrSetValue): IrExpression {
                val newVariable = newVariables[expression.symbol.owner] ?: throw PluginCodegenException("Untracked source variable encountered: ${expression.symbol.owner}")
                fun copyWithOrigin(origin: IrStatementOrigin?) = IrSetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, expression.type, newVariable.symbol, expression.value, origin).also {
                    parentElement.push(it)
                    it.value = expression.value.transform(this, null)
                    parentElement.pop()
                }
                val origin = unnestOriginReplacements[expression.origin] ?: expression.origin
                return copyWithOrigin(origin).also { it.addIfTopLevel() }
            }

            override fun visitSetField(expression: IrSetField): IrExpression = IrSetFieldImpl(expression.startOffset, expression.endOffset, expression.symbol, expression.type, unnestOriginReplacements[expression.origin] ?: expression.origin).also {
                parentElement.push(it)
                it.value = expression.value.transform(this, null)
                it.receiver = expression.receiver?.transform(this, null)
                parentElement.pop()
                it.addIfTopLevel()
            }

            override fun visitGetObjectValue(expression: IrGetObjectValue): IrExpression {
                val newGetObject = IrGetObjectValueImpl(expression.startOffset, expression.endOffset, expression.type, expression.symbol)
                val transformedGetObject = when (parentElement.peek()) {
                    is IrBlock, is IrBlockBody, is IrVariable -> newGetObject
                    else -> {
                        val variable = bodyGenerator.generateVal(varName(), container, expression)
                        context.peek().add(variable)
                        bodyGenerator.generateGetValue(variable)
                    }
                }
                transformedGetObject.addIfTopLevel()
                return transformedGetObject
            }

            override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
                val newCall = IrConstructorCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    expression.symbol,
                    expression.typeArgumentsCount,
                    expression.constructorTypeArgumentsCount,
                    expression.valueArgumentsCount,
                    expression.origin
                ).also {
                    parentElement.push(it)
                    (0 until expression.valueArgumentsCount).forEach { i -> it.putValueArgument(i, expression.getValueArgument(i)!!.transform(this, null)) }
                    (0 until expression.constructorTypeArgumentsCount).forEach { i -> it.putConstructorTypeArgument(i, expression.getConstructorTypeArgument(i)) }
                    (0 until expression.typeArgumentsCount).forEach { i -> it.putTypeArgument(i, expression.getTypeArgument(i)) }
                    parentElement.pop()
                }
                return when {
                    parentElement.peek().shouldUnnest() -> {
                        val variable = bodyGenerator.generateVal(varName(), container, newCall)
                        context.peek().add(variable)
                        val getVal = bodyGenerator.generateGetValue(variable)
                        getVal.addIfTopLevel()
                        getVal
                    }
                    else -> {
                        newCall.addIfTopLevel()
                        newCall
                    }
                }
            }

            override fun <T> visitConst(expression: IrConst<T>): IrExpression {
                val newConst = expression.copy()
                val transformedConst = when (parentElement.peek()) {
                    is IrBlock, is IrBlockBody, is IrVariable -> newConst
                    else -> {
                        val variable = bodyGenerator.generateVal(varName(), container, expression)
                        context.peek().add(variable)
                        bodyGenerator.generateGetValue(variable)
                    }
                }
                transformedConst.addIfTopLevel()
                return transformedConst
            }

            private fun IrStatement.addIfTopLevel() {
                when (parentElement.peek()) {
                    is IrBlock, is IrBlockBody -> context.peek().add(this)
                }
            }

            private fun IrElement.shouldUnnest() = when (this) {
                is IrCall, is IrSetValue, is IrReturn, is IrSetField, is IrConstructorCall, is IrTypeOperatorCall -> true
                else -> false
            }
        }
        return target.accept(transformer, null) as IrBody
    }

    override fun lower(declaration: IrAnonymousInitializer): IrAnonymousInitializer {
        val parentClass = declaration.parentAsClass
        return declaration.also {
            val parameters: List<IrValueParameter> = when {
                parentClass.primaryConstructor != null -> {
                    val vp = parentClass.primaryConstructor!!.valueParameters
                    parentClass.thisReceiver?.let { vp + listOf(it) } ?: vp
                }
                else -> parentClass.thisReceiver?.let { listOf(it) } ?: emptyList()
            }
            it.body = lower(it.body, it.parent, parameters) as IrBlockBody
        }
    }

    override fun lower(function: IrFunction): IrFunction {
        if (function.body != null) {
            val target = function.body ?: throw PluginCodegenException("No Body to transform")
            val parameters = if (function is IrSimpleFunction) function.allParameters() else function.valueParameters
            function.body = lower(target, function, parameters)
        }
        return function
    }
}
