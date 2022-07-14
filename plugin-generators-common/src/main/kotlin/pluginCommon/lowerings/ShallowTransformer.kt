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
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import java.util.*

interface ExpressionMapper {
    data class ImageStatement<T : IrElement>(val transformedExpression: T, val statementsToAddToContext: List<IrStatement>)
    fun mapConst(parent: IrElement, expression: IrConst<*>): ImageStatement<IrExpression>? = null
    fun mapCall(parent: IrElement, expression: IrCall): ImageStatement<IrExpression>? = null
    fun mapSetVal(parent: IrElement, expression: IrSetValue): ImageStatement<IrExpression>? = null
    fun mapVariable(parent: IrElement, variable: IrVariable): ImageStatement<IrValueDeclaration>? = null
    fun mapGetObject(parent: IrElement, expression: IrGetObjectValue): ImageStatement<IrExpression>? = null
    fun mapWhen(parent: IrElement, expression: IrWhen): ImageStatement<IrExpression>? = null
    fun mapTypeOperatorCall(parent: IrElement, expression: IrTypeOperatorCall): ImageStatement<IrExpression>? = null
    fun mapSetField(parent: IrElement, expression: IrSetField): ImageStatement<IrExpression>? = null
    fun mapConstructorCall(parent: IrElement, expression: IrConstructorCall): ImageStatement<IrExpression>? = null
    fun didEnterScope() {}
    fun didLeaveScope() {}
}

/**
 * This version of Copy and Replacer allows clients to add multiple statements for a given statement or none.
 * This is achieved via maintaining a set of statements populated upon each transform, rather than a 1-1 mapping usually achieved by
 * just transforming the statements of a block.
 * For non-statement container transforms, the transformation may be the result of a call to the expression mapper.
 * If the expression mapper returns a nonnull ImageStatement, the statements returned are unconditionally added to the context.
 * Otherwise, the default behavior is to add it to the context if it is top level.
 */
class ShallowTransformer(private val expressionMapper: ExpressionMapper) : IrElementTransformerVoid() {
    private var context = Stack<MutableList<IrStatement>>()
    private var parentElement = Stack<IrElement>()
    private var newVariables = mutableMapOf<IrValueDeclaration, IrValueDeclaration>()

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        parentElement.push(body)
        context.push(mutableListOf<IrStatement>())
        expressionMapper.didEnterScope()
        body.statements.forEach {
            it.transform(this, null) as IrStatement
        }
        parentElement.pop()
        body.statements.clear()
        body.statements.addAll(context.pop())
        expressionMapper.didLeaveScope()
        return body
    }

    override fun visitBlock(expression: IrBlock): IrExpression {
        parentElement.push(expression)
        context.push(mutableListOf<IrStatement>())
        expressionMapper.didEnterScope()
        expression.statements.forEach {
            it.transform(this, null) as IrStatement
        }
        parentElement.pop()
        expression.statements.clear()
        expression.statements.addAll(context.pop())
        expressionMapper.didLeaveScope()
        return expression
    }

    override fun visitWhileLoop(loop: IrWhileLoop): IrExpression {
        parentElement.push(loop)
        loop.condition = loop.condition.transform(this, null)
        loop.body = loop.body?.transform(this, null)
        parentElement.pop()
        loop.addIfTopLevel()
        return loop
    }

    override fun visitWhen(expression: IrWhen): IrExpression {
        parentElement.push(expression)
        val branches = expression.branches.map { it.transform(this, null) }
        parentElement.pop()
        expression.branches.clear()
        expression.branches.addAll(branches)
        val transformedExpression = expressionMapper.mapWhen(parentElement.peek(), expression)
        val newWhen = when {
            transformedExpression == null -> expression
            else -> {
                val (image, addedStatements) = transformedExpression
                addedStatements.forEach { context.peek().add(it) }
                image
            }
        }
        newWhen.addIfTopLevel()
        return newWhen
    }

    override fun visitBranch(branch: IrBranch): IrBranch {
        parentElement.push(branch)
        branch.condition = branch.condition.transform(this, null)
        branch.result = branch.result.transform(this, null)
        parentElement.pop()
        return branch
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        parentElement.push(declaration)
        declaration.initializer = declaration.initializer?.transform(this, null)
        parentElement.pop()
        val resultMaybe = expressionMapper.mapVariable(parentElement.peek(), declaration)
        val variable = if (resultMaybe != null) {
            val (image, addedStatements) = resultMaybe
            addedStatements.forEach { context.peek().add(it) }
            if (image.symbol != declaration.symbol) newVariables[declaration] = image
            image
        } else {
            declaration.addIfTopLevel()
            declaration
        }
        return variable
    }

    override fun visitReturn(expression: IrReturn): IrExpression {
        parentElement.push(expression)
        expression.value = expression.value.transform(this, null)
        parentElement.pop()
        expression.addIfTopLevel()
        return expression
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
        parentElement.push(expression)
        expression.argument = expression.argument.transform(this, null)
        parentElement.pop()
        val mappedExpressionMaybe = expressionMapper.mapTypeOperatorCall(parentElement.peek(), expression)
        return if (mappedExpressionMaybe != null) {
            val (image, otherStatements) = mappedExpressionMaybe
            otherStatements.forEach { context.peek().add(it) }
            image
        } else {
            expression.addIfTopLevel()
            expression
        }
    }

    override fun visitGetValue(expression: IrGetValue): IrExpression {
        val transformedExpression = newVariables[expression.symbol.owner]?.let { IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, it.symbol) } ?: expression
        transformedExpression.addIfTopLevel()
        return transformedExpression
    }

    override fun visitCall(call: IrCall): IrExpression {
        parentElement.push(call)
        call.extensionReceiver = call.extensionReceiver?.transform(this, null)
        call.dispatchReceiver = call.dispatchReceiver?.transform(this, null)
        (0 until call.valueArgumentsCount).forEach { call.putValueArgument(it, call.getValueArgument(it)!!.transform(this, null)) }
        parentElement.pop()
        val resultMaybe = expressionMapper.mapCall(parentElement.peek(), call)
        return if (resultMaybe != null) {
            val (image, otherStatements) = resultMaybe
            otherStatements.forEach { context.peek().add(it) }
            image
        } else {
            call.addIfTopLevel()
            call
        }
    }

    override fun visitSetValue(expression: IrSetValue): IrExpression {
        parentElement.push(expression)
        expression.value = expression.value.transform(this, null)
        parentElement.pop()
        val resultMaybe = expressionMapper.mapSetVal(parentElement.peek(), expression)
        return if (resultMaybe != null) {
            val (image, otherStatements) = resultMaybe
            otherStatements.forEach { context.peek().add(it) }
            image
        } else {
            expression.addIfTopLevel()
            expression
        }
    }

    override fun visitSetField(expression: IrSetField): IrExpression {
        parentElement.push(expression)
        expression.value = expression.value.transform(this, null)
        parentElement.pop()
        val resultMaybe = expressionMapper.mapSetField(parentElement.peek(), expression)
        return if (resultMaybe != null) {
            val (image, otherStatements) = resultMaybe
            otherStatements.forEach { context.peek().add(it) }
            image
        } else {
            expression.addIfTopLevel()
            expression
        }
    }

    override fun visitConstructorCall(call: IrConstructorCall): IrExpression {
        parentElement.push(call)
        (0 until call.valueArgumentsCount).forEach { call.putValueArgument(it, call.getValueArgument(it)!!.transform(this, null)) }
        parentElement.pop()
        val resultMaybe = expressionMapper.mapConstructorCall(parentElement.peek(), call)
        return if (resultMaybe != null) {
            val (image, otherStatements) = resultMaybe
            otherStatements.forEach { context.peek().add(it) }
            image
        } else {
            call.addIfTopLevel()
            call
        }
    }

    override fun visitGetObjectValue(expression: IrGetObjectValue): IrExpression {
        val resultMaybe = expressionMapper.mapGetObject(parentElement.peek(), expression)
        return if (resultMaybe != null) {
            val (image, addedStatements) = resultMaybe
            addedStatements.forEach { context.peek().add(it) }
            image
        } else {
            expression.addIfTopLevel()
            expression
        }
    }

    override fun <T> visitConst(expression: IrConst<T>): IrExpression {
        val resultMaybe = expressionMapper.mapConst(parentElement.peek(), expression)
        return if (resultMaybe != null) {
            val (image, addedStatements) = resultMaybe
            addedStatements.forEach { context.peek().add(it) }
            image
        } else {
            expression.addIfTopLevel()
            expression
        }
    }

    private fun IrStatement.addIfTopLevel() {
        when (parentElement.peek()) {
            is IrBlock, is IrBlockBody -> context.peek().add(this)
        }
    }
}
