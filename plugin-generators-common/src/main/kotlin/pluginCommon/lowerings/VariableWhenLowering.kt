/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.lowerings

import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.name.Name
import pluginCommon.PluginCodegenException
import pluginCommon.generators.IrBodyGenerator

class VariableWhenLowering(val bodyGenerator: IrBodyGenerator, val builtIns: IrBuiltIns) : FunctionLowering {
    var counter = 0
    var shallowTransformer: ShallowTransformer? = null
    override fun lower(function: IrFunction): IrFunction {
        val target = function.body ?: throw PluginCodegenException("No Body to transform")
        val switchExpressionMapper = object : ExpressionMapper {
            override fun mapVariable(parent: IrElement, variable: IrVariable): ExpressionMapper.ImageStatement<IrValueDeclaration>? {
                val rhs = variable.initializer
                return if (rhs is IrWhen) {
                    rhs.branches.forEach { branch ->
                        branch.result = when (val result = branch.result) {
                            is IrBlock -> {
                                // If the last expression is not a GetVal, we want to unnest it into a get val
                                val lastStatement = result.statements.last()
                                val newLastStatements = when (lastStatement) {
                                    is IrWhen -> {
                                        // introduce variable
                                        // set the rhs to the when
                                        // call the shallow transformer again to transform it
                                        val newVariable = bodyGenerator.generateVariable(Name.identifier("\$whenLowering${counter++}"), function, lastStatement)
                                        listOf(newVariable, bodyGenerator.generateGetValue(newVariable))
                                    }
                                    else -> listOf(lastStatement)
                                }
                                result.statements.removeLast()
                                result.statements.addAll(newLastStatements)
                                val imageResult = shallowTransformer!!.visitBlock(result) as IrBlock

                                val setVariableStatement = bodyGenerator.generateSetVariable(variable, imageResult.statements.last() as IrExpression)
                                imageResult.type = builtIns.unitType
                                imageResult.statements.removeLast()
                                imageResult.statements.add(setVariableStatement)
                                result
                            }
                            else -> bodyGenerator.generateSetVariable(variable, result)
                        }
                    }
                    rhs.type = builtIns.unitType
                    variable.initializer = null
                    ExpressionMapper.ImageStatement(variable, listOf(variable, rhs))
                } else null
            }
        }
        shallowTransformer = ShallowTransformer(switchExpressionMapper)
        function.body = target.accept(shallowTransformer!!, null) as IrBody
        return function
    }
}
