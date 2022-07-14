/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.diffIR

interface DiffIRVisitor {
    fun visitVariable(declaration: ActiveVariable) {}
    fun visitSetVariable(expression: SetValue) {}
    fun visitBlockStatement(expression: BlockStatement) {}
    fun visitBlockBodyStatement(expression: BlockBodyStatement) {}
    fun visitWhenStatement(expression: WhenStatement) {}
    fun visitLoopStatement(expression: LoopStatement) {}
    fun visitConditionStatement(expression: ConditionBlock) {}
    fun visitReturn(returnStatement: ReturnStatement) {}
    fun visitConstant(constantStatement: ConstantStatement) {}
    fun visitSetField(setField: SetField) {}
    fun visitCall(call: Call) {}
    fun visitConstructorCallVariable(constructorCallVariable: ConstructorCallVariable) {}
    fun visitTypeOperatorVariable(typeOperatorVariable: TypeOperatorVariable) {}
    fun visitPushIntermediateState(pushIntermediateStateVariable: PushIntermediateStateVariable) {}
}
