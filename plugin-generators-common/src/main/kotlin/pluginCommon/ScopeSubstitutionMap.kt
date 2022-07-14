/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon

import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration

class ScopeSubstitutionMap {
    private val scopeMaps = java.util.ArrayDeque<MutableMap<IrValueDeclaration, IrValueDeclaration>>()

    operator fun get(src: IrValueDeclaration): IrValueDeclaration? {
        for (scope in scopeMaps) {
            val maybeTarget = scope[src]
            if (maybeTarget != null) {
                return maybeTarget
            }
        }
        return null
    }

    operator fun set(src: IrValueDeclaration, target: IrValueDeclaration) {
        scopeMaps.first.put(src, target)
    }

    fun push() {
        scopeMaps.push(mutableMapOf())
    }

    fun pop(): Map<IrValueDeclaration, IrValueDeclaration> {
        return scopeMaps.pop()
    }

    fun top() = scopeMaps.first
}
