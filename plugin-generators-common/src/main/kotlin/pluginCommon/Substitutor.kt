/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon

import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration

interface Substitutor<TKey, TValue> {
    operator fun get(key: TKey): TValue?
    operator fun set(key: TKey, value: TValue)

    companion object {
        fun <TK, TV> emptySubstitutor() = object : Substitutor<TK, TV> {
            override fun get(key: TK): TV? = null
            override fun set(key: TK, value: TV) {}
        }
    }
}

class MapWrapper<TKey, TValue>(val map: MutableMap<TKey, TValue>) : Substitutor<TKey, TValue> {
    override fun get(key: TKey): TValue? = map[key]
    override fun set(key: TKey, value: TValue) { map[key] = value }
    fun contains(key: TKey): Boolean = get(key) != null
}

class ScopeSubstitutionMapSubstitutor(val substitutionMap: ScopeSubstitutionMap) : Substitutor<IrValueDeclaration, IrValueDeclaration> {
    override fun get(key: IrValueDeclaration): IrValueDeclaration? = substitutionMap[key]
    override fun set(key: IrValueDeclaration, value: IrValueDeclaration) { substitutionMap[key] = value }
}
