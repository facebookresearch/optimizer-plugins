/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.lowerings

import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrSetValueImpl
import org.jetbrains.kotlin.ir.types.isUnit
import pluginCommon.PluginCodegenException

class UnitCastTransformer(val builtIns: IrBuiltIns) : FunctionLowering {
    override fun lower(function: IrFunction): IrFunction {
        val target = function.body ?: throw PluginCodegenException("No Body to transform")
        val unitCastExpressionMapper = object : ExpressionMapper {
            override fun mapTypeOperatorCall(
                parent: IrElement,
                expression: IrTypeOperatorCall
            ): ExpressionMapper.ImageStatement<IrExpression>? {
                return when {
                    expression.type.isUnit() -> {
                        val statementsToAddToParent = mutableListOf<IrStatement>()
                        when (val argument = expression.argument) {
                            is IrBlock -> {
                                val statements = argument.statements
                                statements.removeLast()

                                for (i in 0 until statements.size) {
                                    statementsToAddToParent.add(statements[i])
                                }

                                val newExpression = statements[statements.size - 1]
                                if (newExpression is IrSetValueImpl && newExpression.type.isUnit()) {
                                    return ExpressionMapper.ImageStatement(newExpression, statementsToAddToParent)
                                }

                                return null
                            }
                            else -> null
                        }
                    }
                    else -> null
                }
            }
        }
        function.body = target.accept(ShallowTransformer(unitCastExpressionMapper), null) as IrBody
        return function
    }
}
