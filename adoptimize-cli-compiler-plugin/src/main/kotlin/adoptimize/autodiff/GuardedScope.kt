/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff

import adoptimize.AutoDiffException
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name
import java.util.*

class GuardedScope {
    private var counter = 0
    private var rootScope: GuardedStatements? = null
    private val scopes = ArrayDeque<GuardedStatements>()
    fun root(): GuardedStatements? = rootScope
    fun isTopLevel() = top() == root()
    fun pushScope() {
        if (rootScope == null) {
            rootScope = GuardedStatements()
            scopes.push(rootScope!!)
        } else {
            scopes.push(GuardedStatements())
        }
    }

    fun popScope(): List<IrStatement> {
        return scopes.pop().getStatements()
    }

    fun putStatefulVariable(variable: IrVariable) = top().add(variable)
    fun putSet(setVal: IrSetValue) = top().add(setVal)
    fun putWhen(whenStatement: IrWhen) = top().add(whenStatement)
    fun putLoop(whileLoop: IrWhileLoop) = top().add(whileLoop)
    fun putCall(call: IrCall) = top().add(call)
    fun putSetField(field: IrSetField) = top().add(field)
    fun putGet(get: IrGetValue) = top().add(get)

    fun tryPutStatelessVariable(variable: IrVariable): IrVariable {
        if (variable.containsNestedExpressions()) {
            throw AutoDiffException("`tryPutStatelessVariable` is only for adding unnested expressions whose call is stateless: ${variable.render()}:${variable.initializer?.render()}")
        }
        return when {
            variable.isVar || variable.hasMutableArguments() -> addNewUniquelyNamed(variable)
            else -> {
                val nonUselessVariable = variable.rootOfUselessVariableChain()
                if (nonUselessVariable.isVar) {
                    nonUselessVariable
                } else {
                    when {
                        nonUselessVariable != variable -> nonUselessVariable
                        else -> equivalentVariableInScope(nonUselessVariable) ?: run { addNewUniquelyNamed(nonUselessVariable) }
                    }
                }
            }
        }
    }

    fun topStatements() = top().getStatements()

    private fun IrVariable.hasMutableArguments() = initializer is IrCall && (initializer as IrCall).allArguments().filterIsInstance<IrVariable>().any { it.isVar }

    private fun top(): GuardedStatements = scopes.firstOrNull() ?: throw IllegalStateException("No more scopes to pop!")

    private fun IrVariable.containsNestedExpressions(): Boolean {
        fun IrExpression.isComposite() = when (this) {
            is IrGetValue, is IrGetObjectValue, is IrConst<*> -> false
            else -> true
        }

        return when (val init = initializer!!) {
            is IrCall -> init.dispatchReceiver?.isComposite() ?: false || init.extensionReceiver?.isComposite() ?: false || (0 until init.valueArgumentsCount).any { init.getValueArgument(it)!!.isComposite() }
            else -> init.isComposite()
        }
    }

    private fun equivalentVariableInScope(variable: IrVariable): IrVariable? {
        val initializer = variable.initializer
        var existing: IrVariable? = null
        if (initializer != null) {
            for (scope in scopes) {
                val candidate = scope.variableWithEquivalentInitialization(initializer)
                if (candidate != null) {
                    existing = candidate
                    break
                }
            }
        }
        return existing
    }

    private fun findStatementInScope(criteria: (IrStatement) -> Boolean): IrStatement? {
        for (scope in scopes) {
            val candidate = scope.getStatements().firstOrNull(criteria)
            if (candidate != null) {
                return candidate
            }
        }
        return null
    }

    class GuardedStatements {
        private val statements = mutableListOf<IrStatement>()

        fun add(statement: IrStatement) {
            statements.add(statement)
        }

        fun getStatements(): List<IrStatement> {
            return statements
        }

        fun variableWithEquivalentInitialization(initialization: IrExpression): IrVariable? {
            return statements.firstOrNull {
                when (it) {
                    is IrVariable -> it.initializer?.isEquivalent(initialization) == true
                    else -> false
                }
            } as IrVariable?
        }
    }

    /** suppose the following code is in a block: 'val x = a*a; val x2 = x; val x3 = x2'
     * Given `val x3 = x2`, this function will return `val x = a*a`. Note that if the rhs of the variable
     * is a mutable variable the chain will halt, since the given variable is used as a capture and should not be replaced with the mutable
     * version.
     */
    private fun IrVariable.rootOfUselessVariableChain(): IrVariable {
        var nonUselessVariable: IrVariable = this
        if (nonUselessVariable.isVar) {
            return nonUselessVariable
        }
        while (true) {
            when (val init = nonUselessVariable.initializer) {
                is IrGetValue -> {
                    val potentialReplacement = init.symbol.owner
                    when (potentialReplacement) {
                        is IrVariable -> {
                            if (!potentialReplacement.isVar) {
                                nonUselessVariable = potentialReplacement
                            } else break
                        }
                        is IrValueParameter -> TODO()
                    }
                }
                else -> break
            }
        }
        return nonUselessVariable
    }

    private fun addNewUniquelyNamed(newVar: IrVariable): IrVariable {
        val isDuplicateName = findStatementInScope { it is IrVariable && it.name == newVar.name } != null
        val uniqueVariable = if (isDuplicateName) IrVariableImpl(
            newVar.startOffset,
            newVar.endOffset,
            newVar.origin,
            IrVariableSymbolImpl(),
            Name.identifier("\$${newVar.name}_${counter++}"),
            newVar.type,
            newVar.isVar,
            newVar.isConst,
            newVar.isLateinit
        ).also { it.initializer = newVar.initializer; it.parent = newVar.parent } else newVar
        top().add(uniqueVariable)
        return uniqueVariable
    }
}
