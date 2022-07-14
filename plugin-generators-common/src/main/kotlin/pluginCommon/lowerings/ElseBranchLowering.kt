/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.lowerings

import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

class ElseBranchLowering(val builtIns: IrBuiltIns) : FunctionLowering {
    override fun lower(function: IrFunction): IrFunction {
        function.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitWhen(expression: IrWhen) {
                if (expression.branches.filterIsInstance<IrElseBranch>().isEmpty() && expression.type.isUnit()) {
                    expression.branches.add(
                        IrElseBranchImpl(
                            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                            IrConstImpl.boolean(
                                UNDEFINED_OFFSET, UNDEFINED_OFFSET, builtIns.booleanType, true
                            ),
                            IrBlockImpl(
                                UNDEFINED_OFFSET,
                                UNDEFINED_OFFSET, builtIns.unitType, null, emptyList()
                            )
                        )
                    )
                }
            }
        })
        return function
    }
}
