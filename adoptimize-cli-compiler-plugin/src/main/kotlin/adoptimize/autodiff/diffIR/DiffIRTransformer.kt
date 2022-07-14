/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.diffIR
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.*

interface DiffIRTransformer {
    fun transformSetVariable(expression: SetValue): IrExpression
    fun transformBlockStatement(expression: BlockStatement): IrBlock?
    fun transformBlockBodyStatement(expression: BlockBodyStatement): IrBlockBody
    fun transformWhenStatement(expression: WhenStatement): IrWhen
    fun transformLoopStatement(expression: LoopStatement): IrWhileLoop
    fun transformConditionStatement(expression: ConditionBlock): IrBranch
    fun transformReturn(expression: ReturnStatement): IrReturn
    fun transformConstant(constantStatement: ConstantStatement): IrElement
    fun transformSetField(expression: SetField): IrSetField
    fun transformGetProperty(expression: GetPropertyVariable): IrStatement
    fun transformPopIntermediateVariable(expression: PopIntermediateStateVariable): IrStatement
    fun transformPushIntermediateVariable(expression: PushIntermediateStateVariable): IrStatement
    fun transformTypeOperatorVariable(expression: TypeOperatorVariable): IrStatement
    fun transformCallVariable(callVariable: CallVariable): IrStatement
    fun transformLateInitVariable(lateInitVariable: LateInitVariable): IrStatement
    fun transformGetValVariable(getValVariable: GetValVariable): IrStatement
    fun transformCall(call: Call): IrExpression
    fun transformConstructorCall(constructorCallVariable: ConstructorCallVariable): IrStatement
    fun transformDerivativeVariable(gradientVariable: GradientVariable): IrStatement
}

abstract class DiffIRToDiffIRTransformer() {
    fun DiffIRStatement.transform(): DiffIRStatement {
        return when (this) {
            is SetValue -> transformSetVariable(this)
            is BlockStatement -> transformBlockStatement(this)
            is WhenStatement -> transformWhenStatement(this)
            is BlockBodyStatement -> transformBlockBodyStatement(this)
            is LoopStatement -> transformLoopStatement(this)
            is ConditionBlock -> transformConditionStatement(this)
            is ReturnStatement -> transformReturn(this)
            is ConstantStatement -> transformConstant(this)
            is SetField -> transformSetField(this)
            is GetPropertyVariable -> transformGetProperty(this)
            is PopIntermediateStateVariable -> transformPopIntermediateVariable(this)
            is PushIntermediateStateVariable -> transformPushIntermediateVariable(this)
            is CallVariable -> transformCallVariable(this)
            is LateInitVariable -> transformLateInitVariable(this)
            is GetValVariable -> transformGetValVariable(this)
            is Call -> transformCall(this)
            is ConstructorCallVariable -> transformConstructorCall(this)
            is GradientVariable -> transformDerivativeVariable(this)
            is TypeOperatorVariable -> transformTypeOperatorVariable(this)
            else -> TODO()
        }
    }
    open fun transformSetVariable(expression: SetValue) = expression
    open fun transformBlockStatement(expression: BlockStatement) = BlockStatement(expression.children.map { it.transform() }, expression.isVirtual, expression.type, expression.original)
    open fun transformBlockBodyStatement(expression: BlockBodyStatement) = BlockBodyStatement(expression.children.map { it.transform() }, expression.original)
    open fun transformWhenStatement(expression: WhenStatement) = WhenStatement(expression.original, expression.children.map { it.transform() as ConditionBlock })
    open fun transformLoopStatement(expression: LoopStatement) = LoopStatement(expression.identifier, expression.original, expression.body.transform())
    open fun transformConditionStatement(expression: ConditionBlock) = ConditionBlock(expression.condition, transformBlockStatement(expression.result), expression.index)
    open fun transformReturn(expression: ReturnStatement) = expression
    open fun transformConstant(constantStatement: ConstantStatement) = constantStatement
    open fun transformSetField(expression: SetField) = expression
    open fun transformGetProperty(expression: GetPropertyVariable) = expression
    open fun transformPopIntermediateVariable(expression: PopIntermediateStateVariable) = expression
    open fun transformPushIntermediateVariable(expression: PushIntermediateStateVariable) = expression
    open fun transformTypeOperatorVariable(expression: TypeOperatorVariable) = expression
    open fun transformCallVariable(callVariable: CallVariable) = callVariable
    open fun transformLateInitVariable(lateInitVariable: LateInitVariable) = lateInitVariable
    open fun transformGetValVariable(getValVariable: GetValVariable) = getValVariable
    open fun transformCall(call: Call) = call
    open fun transformConstructorCall(constructorCallVariable: ConstructorCallVariable) = constructorCallVariable
    open fun transformDerivativeVariable(gradientVariable: GradientVariable) = gradientVariable
}
