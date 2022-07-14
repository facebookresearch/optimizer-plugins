/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.Name

class IrClassGenerator(val pluginContext: IrPluginContext, val irFunctionGenerator: IrFunctionGenerator, val irPropertyGenerator: IrPropertyGenerator) {
    fun generateLocalClass(
        parent: IrDeclarationParent,
        name: String,
        supertypes: List<IrType>,
        constructorParameters: List<ParameterInfo>,
        delegatingConstructorBuilder: (IrConstructor) -> IrDelegatingConstructorCall,
        build: (IrClass) -> Unit
    ): IrClass {
        val clazz = pluginContext.irFactory.createClass(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            IrClassSymbolImpl(),
            Name.identifier(name),
            ClassKind.CLASS,
            DescriptorVisibilities.LOCAL,
            Modality.FINAL
        )
        clazz.parent = parent
        clazz.superTypes = supertypes
        val type = IrSimpleTypeImpl(null, clazz.symbol, false, emptyList(), emptyList(), null)
        clazz.thisReceiver = pluginContext.irFactory.createValueParameter(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            IrDeclarationOrigin.INSTANCE_RECEIVER,
            IrValueParameterSymbolImpl(), Name.special("<this>"), -1, type, null, false, false, false, false
        ).also { it.parent = clazz }
        pluginContext.symbolTable.buildWithScope(clazz) { clazz ->
            supertypes.forEach {
                val superClass = it.classifierOrFail.owner as IrClass
                val needsFakeOverride: (IrOverridableMember) -> Boolean = { member -> (member.visibility == DescriptorVisibilities.PUBLIC || member.visibility == DescriptorVisibilities.PROTECTED) && member.modality != Modality.ABSTRACT }
                val properties = superClass.properties.filter { needsFakeOverride(it) }
                properties.forEach {
                    if (!clazz.overrides(it)) {
                        val fakeOverride: IrProperty = irPropertyGenerator.generateFakeOverrideProperty(clazz, it)
                        clazz.declarations.add(fakeOverride)
                    }
                }
                val functions = superClass.functions.filter { needsFakeOverride(it) }
                functions.forEach {
                    if (!clazz.overrides(it)) {
                        irFunctionGenerator.generateFakeOverride(it, clazz)
                    }
                }
            }
            val constructor = irFunctionGenerator.generateConstructor(clazz, constructorParameters, true) { constructor ->
                val statements = listOf(
                    delegatingConstructorBuilder(constructor),
                    IrInstanceInitializerCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, clazz.symbol, pluginContext.irBuiltIns.unitType)
                )
                constructor.body = pluginContext.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, statements)
            }
            constructor.parent = clazz
            build(clazz)
        }
        return clazz
    }

    fun authenticClass(
        parent: IrDeclarationParent,
        name: String,
        supertypes: List<IrType>,
        constructorParameters: List<ParameterInfo>,
        delegatingConstructorBuilder: (GeneratedAuthenticClass, IrConstructor) -> Unit,
        visibilitity: org.jetbrains.kotlin.descriptors.DescriptorVisibility = DescriptorVisibilities.DEFAULT_VISIBILITY,
        build: (GeneratedAuthenticClass) -> Unit,
        generateFakeOverrides: Boolean = false
    ): IrClass {
        val clazz = GeneratedAuthenticClass(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            Name.identifier(name),
            ClassKind.CLASS,
            visibilitity,
            Modality.FINAL,
            parent,
            supertypes,
            { clazz ->
                pluginContext.irFactory.createValueParameter(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    IrDeclarationOrigin.INSTANCE_RECEIVER,
                    IrValueParameterSymbolImpl(), Name.special("<this>"), -1,
                    IrSimpleTypeImpl(null, clazz.symbol, false, emptyList(), emptyList(), null), null, false, false, false, false
                ).also { it.parent = clazz }
            }
        )
        pluginContext.symbolTable.buildWithScope(clazz) { clazz: GeneratedAuthenticClass ->
            supertypes.forEach {
                val superClass = it.classifierOrFail.owner as IrClass
                val needsFakeOverride: (IrOverridableMember) -> Boolean = { member -> if (generateFakeOverrides) (member.visibility == DescriptorVisibilities.PUBLIC || member.visibility == DescriptorVisibilities.PROTECTED) && member.modality != Modality.ABSTRACT else false }
                val properties = superClass.properties.filter { needsFakeOverride(it) }
                properties.forEach {
                    if (!clazz.overrides(it)) {
                        val fakeOverride: IrProperty = irPropertyGenerator.generateFakeOverrideProperty(clazz, it)
                        clazz.declarations.add(fakeOverride)
                    }
                }
                val functions = superClass.functions.filter { needsFakeOverride(it) }
                functions.forEach {
                    if (!clazz.overrides(it)) {
                        irFunctionGenerator.generateFakeOverride(it, clazz)
                    }
                }
            }
            val constructor = irFunctionGenerator.generateConstructor(clazz, constructorParameters, true) { constructor ->
                delegatingConstructorBuilder(clazz, constructor)
            }
            constructor.parent = clazz
            build(clazz)
            clazz.setMetadata()
        }
        return clazz
    }
}
