/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.lowerings

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.*
import pluginCommon.generators.allParameters
import pluginCommon.lowerings.RedundantVariableRemover.PotentiallyRemovableVariable.Companion.create
import java.util.*

class RedundantVariableRemover(val immutableTypes: Set<IrType>) : DeclarationWithBodyLowering {
    private val remover = object : ExpressionMapper {
        val scopes = Stack<MutableList<PotentiallyRemovableVariable>>()
        override fun didEnterScope() {
            scopes.push(mutableListOf())
        }

        override fun didLeaveScope() {
            scopes.pop()
        }

        override fun mapVariable(
            parent: IrElement,
            variable: IrVariable
        ): ExpressionMapper.ImageStatement<IrValueDeclaration>? {
            val candidate = create(variable, immutableTypes)
            return when {
                candidate is GetValVariable -> ExpressionMapper.ImageStatement(candidate.valueDeclaration, emptyList())
                candidate is CallVariable || candidate is ConstantVariable -> {
                    val existing = candidate.equivalentInScope()
                    if (existing != null) {
                        ExpressionMapper.ImageStatement(existing, emptyList())
                    } else {
                        scopes.peek().add(candidate)
                        null
                    }
                }
                else -> null
            }
        }

        private fun PotentiallyRemovableVariable.equivalentInScope(): IrVariable? {
            var existing: PotentiallyRemovableVariable? = null
            for (scope in scopes) {
                existing = scope.lastOrNull { it.isEqual(this) }
                if (existing != null) {
                    break
                }
            }
            return existing?.original
        }
    }

    override fun lower(declaration: IrFunction): IrFunction {
        return declaration.also {
            declaration.body?.let { declaration.body = it.accept(ShallowTransformer(remover), null) as IrBody }
        }
    }

    override fun lower(declaration: IrAnonymousInitializer): IrAnonymousInitializer {
        declaration.body = declaration.body.accept(ShallowTransformer(remover), null) as IrBlockBody
        return declaration
    }

    sealed class PotentiallyRemovableVariable(val original: IrVariable) {
        companion object {
            private fun IrExpression.maybeImmutableVariable() = when (this) {
                is IrGetValue -> {
                    when (val owner = this.symbol.owner) {
                        is IrVariable -> if (owner.isVar) null else owner
                        else -> owner
                    }
                }
                else -> null
            }

            fun create(variable: IrVariable, immutableTypes: Set<IrType>): PotentiallyRemovableVariable? {
                return if (variable.isVar) {
                    null
                } else {
                    when (val init = variable.initializer) {
                        is IrCall -> {
                            val function = init.symbol.owner
                            val dispatchReceiver = init.dispatchReceiver?.maybeImmutableVariable()

                            if (init.dispatchReceiver != null && dispatchReceiver == null) {
                                return null
                            }
                            val extensionReceiver = init.extensionReceiver?.maybeImmutableVariable()
                            if (init.extensionReceiver != null && extensionReceiver == null) {
                                return null
                            }
                            val valueArguments = (0 until init.valueArgumentsCount).map { init.getValueArgument(it)!!.maybeImmutableVariable() }.filterNotNull()
                            if (valueArguments.size < init.valueArgumentsCount) {
                                return null
                            }
                            CallVariable(variable, function, dispatchReceiver, extensionReceiver, valueArguments.filterNotNull(), immutableTypes)
                        }
                        is IrGetValue -> {
                            val rhs = init.symbol.owner
                            if (rhs is IrVariable && rhs.isVar) null else {
                                GetValVariable(variable, init.symbol.owner)
                            }
                        }
                        is IrConst<*> -> ConstantVariable(variable, init.value)
                        else -> null
                    }
                }
            }
        }

        fun isEqual(other: PotentiallyRemovableVariable): Boolean {
            return when {
                other is CallVariable && this is CallVariable -> this.isEqual(other)
                other is GetValVariable && this is GetValVariable -> this.isEqual(other)
                other is ConstantVariable && this is ConstantVariable -> this.isEqual(other)
                else -> false
            }
        }
    }

    class CallVariable(
        original: IrVariable,
        val function: IrSimpleFunction,
        val dispatchReceiver: IrValueDeclaration?,
        val extensionReceiver: IrValueDeclaration?,
        val valueArguments: List<IrValueDeclaration>,
        val immutableTypes: Set<IrType>
    ) : PotentiallyRemovableVariable(original) {
        fun isEqual(other: CallVariable): Boolean {
            val dispatchReceiversAreEqual = when {
                this.dispatchReceiver != null -> other.dispatchReceiver != null && other.dispatchReceiver.symbol == this.dispatchReceiver.symbol
                else -> other.dispatchReceiver == null
            }
            val extensionReceiversAreEqual = when {
                this.extensionReceiver != null -> other.extensionReceiver != null && other.extensionReceiver.symbol == this.extensionReceiver.symbol
                else -> other.extensionReceiver == null
            }
            return (other.function.symbol == this.function.symbol) &&
                this.function.isStateless() &&
                dispatchReceiversAreEqual &&
                extensionReceiversAreEqual &&
                (
                    other.valueArguments.size == this.valueArguments.size &&
                        other.valueArguments.zip(this.valueArguments).all { it.first.symbol == it.second.symbol }
                    )
        }

        private fun IrSimpleFunction.isStateless(): Boolean =
            this.allParameters().all {
                immutableTypes.contains(it.type) ||
                    it.type.isDouble() ||
                    it.type.isInt() ||
                    it.type.isFloat() ||
                    it.type.isBoolean() ||
                    it.type.isLong() ||
                    it.type.isChar() ||
                    it.type.isString() ||
                    it.type.isShort()
            } || (this.isPropertyAccessor && this.valueParameters.isEmpty())
    }

    class GetValVariable(original: IrVariable, val valueDeclaration: IrValueDeclaration) : PotentiallyRemovableVariable(original) {
        fun isEqual(other: GetValVariable): Boolean = this.valueDeclaration.symbol == other.valueDeclaration.symbol
    }

    class ConstantVariable(original: IrVariable, val value: Any?) : PotentiallyRemovableVariable(original) {
        fun isEqual(other: ConstantVariable): Boolean {
            return this.value == other.value
        }
    }
}
