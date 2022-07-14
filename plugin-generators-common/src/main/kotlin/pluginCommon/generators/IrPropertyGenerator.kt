/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name
import pluginCommon.PluginCodegenException

class IrPropertyGenerator(val pluginContext: IrPluginContext, val bodyGenerator: IrBodyGenerator) {
    fun duplicateProperty(srcProperty: IrProperty, newClass: IrClass, newInit: IrExpression?): IrProperty {
        val parent = srcProperty.getter?.overriddenSymbols?.firstOrNull()?.owner?.correspondingPropertySymbol?.owner
        val hasAbstractParent = parent?.let { it.modality == Modality.ABSTRACT } ?: false
        val isFakeOverride = parent != null && !hasAbstractParent
        val property = pluginContext.irFactory.createProperty(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            srcProperty.origin,
            LateInitPropertySymbol(),
            srcProperty.name,
            srcProperty.visibility,
            srcProperty.modality,
            srcProperty.isVar,
            srcProperty.isConst,
            srcProperty.isLateinit,
            srcProperty.isDelegated,
            srcProperty.isExternal,
            srcProperty.isExpect,
            srcProperty.isFakeOverride
        )
        property.parent = newClass
        property.metadata = DescriptorMetadataSource.Property(property.descriptor)
        newClass.declarations.add(property)
        srcProperty.backingField?.let { srcField ->
            val backingField = pluginContext.irFactory.createField(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                srcField.origin,
                LateInitFieldSymbol(),
                srcField.name,
                srcField.type,
                DescriptorVisibilities.PRIVATE,
                srcField.isFinal,
                srcField.isExternal,
                srcField.isStatic
            )
            newInit?.let { backingField.initializer = IrExpressionBodyImpl(it) }
            property.backingField = backingField
            backingField.correspondingPropertySymbol = property.symbol
            backingField.parent = newClass
        }

        val clazzType = newClass.defaultType
        srcProperty.setter?.let { srcSetter ->
            val setter = pluginContext.irFactory.createFunction(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                srcSetter.origin,
                LateInitFunctionSymbol(),
                name = Name.special("<set-${property.name}>"),
                visibility = srcSetter.visibility,
                modality = srcSetter.modality,
                returnType = srcSetter.returnType,
                isInline = srcSetter.isInline,
                isExternal = srcSetter.isExternal,
                isTailrec = srcSetter.isTailrec,
                isSuspend = srcSetter.isSuspend,
                isOperator = srcSetter.isOperator,
                isInfix = srcSetter.isInfix,
                isExpect = srcSetter.isExpect
            ).also { setter ->
                setter.parent = property.parent
                setter.correspondingPropertySymbol = property.symbol
                setter.metadata = DescriptorMetadataSource.Function(setter.descriptor)

                pluginContext.symbolTable.buildWithScope(setter) { setter ->
                    val implicitReceiverParameter = pluginContext.irFactory.createValueParameter(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        IrDeclarationOrigin.DEFINED,
                        IrValueParameterSymbolImpl(),
                        Name.special("<this>"),
                        -1,
                        clazzType,
                        null, false, false, false, false
                    )
                    val valueParameter = pluginContext.irFactory.createValueParameter(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        IrDeclarationOrigin.DEFINED,
                        IrValueParameterSymbolImpl(),
                        Name.special("<set-?>"),
                        0,
                        srcSetter.valueParameters.first().type,
                        null, false, false, false, false
                    ).also {
                        it.parent = setter
                    }
                    setter.valueParameters = listOf(valueParameter)
                    setter.overriddenSymbols = srcSetter.overriddenSymbols
                    implicitReceiverParameter.parent = setter
                    setter.dispatchReceiverParameter = implicitReceiverParameter
                    if (srcSetter.body != null) {
                        val getThis = bodyGenerator.generateGetValue(setter.dispatchReceiverParameter!!)
                        val setField = IrSetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, property.backingField!!.symbol, receiver = getThis, value = bodyGenerator.generateGetValue(valueParameter), pluginContext.irBuiltIns.unitType)
                        setter.body = bodyGenerator.generateBlockBody(listOf(), setField, setter)
                    }
                }
            }
            property.setter = setter
        }
        srcProperty.getter?.let { srcGetter ->
            val getter = pluginContext.irFactory.createFunction(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                srcGetter.origin,
                LateInitFunctionSymbol(),
                name = Name.special("<get-${property.name}>"),
                visibility = srcGetter.visibility,
                modality = srcGetter.modality,
                returnType = srcGetter.returnType,
                isInline = srcGetter.isInline,
                isExternal = srcGetter.isExternal,
                isTailrec = srcGetter.isTailrec,
                isSuspend = srcGetter.isSuspend,
                isOperator = srcGetter.isOperator,
                isInfix = srcGetter.isInfix,
                isExpect = srcGetter.isExpect
            ).also { getter ->
                getter.metadata = DescriptorMetadataSource.Function(getter.descriptor)
                getter.parent = property.parent
                getter.correspondingPropertySymbol = property.symbol

                pluginContext.symbolTable.buildWithScope(getter) { getter ->
                    val implicitReceiverParameter = pluginContext.irFactory.createValueParameter(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        IrDeclarationOrigin.DEFINED,
                        IrValueParameterSymbolImpl(),
                        Name.special("<this>"),
                        -1,
                        clazzType,
                        null, false, false, false, false
                    )
                    getter.overriddenSymbols = srcGetter.overriddenSymbols
                    implicitReceiverParameter.parent = getter
                    getter.dispatchReceiverParameter = implicitReceiverParameter
                    if (srcGetter.body != null) {
                        val getThis = bodyGenerator.generateGetValue(getter.dispatchReceiverParameter!!)
                        val getField = IrGetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, property.backingField!!.symbol, property.backingField!!.type, getThis, null, null)
                        getter.body = bodyGenerator.generateBlockBody(listOf(), getField, getter)
                    }
                }
            }
            property.getter = getter
        }

        (property.descriptor as PropertyIrBasedDescriptorWrapper).initialize()
        return property
    }
    fun generateProperty(
        name: Name,
        type: IrType,
        containingClass: IrClass,
        isSettable: Boolean,
        initializer: IrExpression?,
        parent: IrProperty? = null,
    ): IrProperty {
        val hasAbstractParent = parent?.let { it.modality == Modality.ABSTRACT } ?: false
        val isFakeOverride = parent != null && !hasAbstractParent
        val modality = when {
            hasAbstractParent || parent == null -> Modality.FINAL
            else -> parent.modality
        }
        val property = pluginContext.irFactory.createProperty(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            LateInitPropertySymbol(),
            name,
            DescriptorVisibilities.DEFAULT_VISIBILITY,
            modality,
            isSettable,
            false,
            false,
            false,
            false,
            false,
            false
        )
        property.parent = containingClass
        property.metadata = DescriptorMetadataSource.Property(property.descriptor)
        containingClass.declarations.add(property)

        val backingField = pluginContext.irFactory.createField(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            LateInitFieldSymbol(),
            property.name,
            type,
            DescriptorVisibilities.PRIVATE,
            false,
            false,
            false
        )
        initializer?.let { backingField.initializer = IrExpressionBodyImpl(it) }
        property.backingField = backingField
        backingField.correspondingPropertySymbol = property.symbol
        backingField.parent = containingClass
        val clazzType = containingClass.defaultType

        if (isSettable) {
            val setter = pluginContext.irFactory.createFunction(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                if (isFakeOverride) IrDeclarationOrigin.FAKE_OVERRIDE else IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR,
                LateInitFunctionSymbol(),
                name = Name.special("<set-${property.name}>"),
                visibility = DescriptorVisibilities.PUBLIC,
                modality = modality,
                returnType = pluginContext.irBuiltIns.unitType,
                isInline = false,
                isExternal = false,
                isTailrec = false,
                isSuspend = false,
                isOperator = false,
                isInfix = false,
                isExpect = false
            ).also { setter ->
                setter.parent = property.parent
                setter.correspondingPropertySymbol = property.symbol
                setter.metadata = DescriptorMetadataSource.Function(setter.descriptor)

                pluginContext.symbolTable.buildWithScope(setter) { setter ->
                    val implicitReceiverParameter = pluginContext.irFactory.createValueParameter(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        IrDeclarationOrigin.DEFINED,
                        IrValueParameterSymbolImpl(),
                        Name.special("<this>"),
                        -1,
                        clazzType,
                        null, false, false, false, false
                    )
                    val valueParameter = pluginContext.irFactory.createValueParameter(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        IrDeclarationOrigin.DEFINED,
                        IrValueParameterSymbolImpl(),
                        Name.special("<set-?>"),
                        0,
                        backingField.type,
                        null, false, false, false, false
                    ).also { it.parent = setter }
                    setter.valueParameters = listOf(valueParameter)
                    parent?.let {
                        if (it.setter == null) {
                            throw IllegalStateException("cannot generate a setter for an overridden property that has no setter: ${property.name}")
                        }
                        setter.overriddenSymbols = listOf(it.setter!!.symbol)
                    }
                    implicitReceiverParameter.parent = setter
                    setter.dispatchReceiverParameter = implicitReceiverParameter
                    if (!isFakeOverride) {
                        val getThis = bodyGenerator.generateGetValue(setter.dispatchReceiverParameter!!)
                        val setField = IrSetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, backingField.symbol, receiver = getThis, value = bodyGenerator.generateGetValue(valueParameter), pluginContext.irBuiltIns.unitType)
                        setter.body = bodyGenerator.generateBlockBody(listOf(), setField, setter)
                    }
                }
            }
            property.setter = setter
        }

        val getter = pluginContext.irFactory.createFunction(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            if (isFakeOverride) IrDeclarationOrigin.FAKE_OVERRIDE else IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR,
            LateInitFunctionSymbol(),
            name = Name.special("<get-${property.name}>"),
            visibility = parent?.visibility ?: DescriptorVisibilities.PUBLIC,
            modality = modality,
            returnType = backingField.type,
            isInline = false,
            isExternal = false,
            isTailrec = false,
            isSuspend = false,
            isOperator = false,
            isInfix = false,
            isExpect = false
        ).also { getter ->
            getter.metadata = DescriptorMetadataSource.Function(getter.descriptor)
            getter.parent = property.parent
            getter.correspondingPropertySymbol = property.symbol

            pluginContext.symbolTable.buildWithScope(getter) { getter ->
                val implicitReceiverParameter = pluginContext.irFactory.createValueParameter(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    IrDeclarationOrigin.DEFINED,
                    IrValueParameterSymbolImpl(),
                    Name.special("<this>"),
                    -1,
                    clazzType,
                    null, false, false, false, false
                )
                parent?.let {
                    if (it.getter == null) {
                        throw IllegalStateException("cannot generate a getter for an overridden property that has no getter: ${property.name}")
                    }
                    getter.overriddenSymbols = listOf(it.getter!!.symbol)
                }
                implicitReceiverParameter.parent = getter
                getter.dispatchReceiverParameter = implicitReceiverParameter
                if (!isFakeOverride) {
                    val getThis = bodyGenerator.generateGetValue(getter.dispatchReceiverParameter!!)
                    val getField = IrGetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, backingField.symbol, backingField.type, getThis, null, null)
                    getter.body = bodyGenerator.generateBlockBody(listOf(), getField, getter)
                }
            }
        }
        property.getter = getter
        (property.descriptor as PropertyIrBasedDescriptorWrapper).initialize()
        return property
    }

    fun generateFakeOverrideProperty(
        containingClass: IrClass,
        parentProperty: IrProperty
    ): IrProperty {
        val property = pluginContext.irFactory.createFakeOverrideProperty(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.FAKE_OVERRIDE,
            parentProperty.name,
            parentProperty.visibility,
            parentProperty.modality,
            parentProperty.isVar,
            parentProperty.isConst,
            parentProperty.isLateinit,
            parentProperty.isDelegated,
            parentProperty.isExternal,
            parentProperty.isExpect
        )
        property.parent = containingClass
        when (property) {
            is IrFakeOverrideProperty -> property.acquireSymbol(LateInitPropertySymbol())
        }
        parentProperty.getter?.let {
            copyParentAccessorToProperty(it, property)
        }
        parentProperty.setter?.let {
            copyParentAccessorToProperty(it, property)
        }
        return property
    }

    private fun copyParentAccessorToProperty(parentAccessor: IrSimpleFunction, property: IrProperty) {
        if (parentAccessor.visibility == DescriptorVisibilities.PROTECTED || parentAccessor.visibility == DescriptorVisibilities.PUBLIC) {
            val childAccessor = pluginContext.irFactory.createFakeOverrideFunction(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                IrDeclarationOrigin.FAKE_OVERRIDE,
                parentAccessor.name,
                parentAccessor.visibility,
                parentAccessor.modality,
                parentAccessor.returnType,
                parentAccessor.isInline,
                parentAccessor.isExternal,
                parentAccessor.isTailrec,
                parentAccessor.isSuspend,
                parentAccessor.isOperator,
                parentAccessor.isInfix,
                parentAccessor.isExpect
            )
            when (childAccessor) {
                is IrFakeOverrideFunction -> childAccessor.acquireSymbol(LateInitFunctionSymbol())
            }
            pluginContext.symbolTable.buildWithScope(childAccessor) { childAccessor ->
                parentAccessor.dispatchReceiverParameter?.let { dr ->
                    val implicitReceiverParameter = pluginContext.irFactory.createValueParameter(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        IrDeclarationOrigin.DEFINED,
                        IrValueParameterSymbolImpl(),
                        dr.name,
                        -1,
                        dr.type,
                        null, false, false, false, false
                    )
                    implicitReceiverParameter.parent = childAccessor
                    childAccessor.dispatchReceiverParameter = implicitReceiverParameter
                }
                parentAccessor.valueParameters.forEach { vp ->
                    val valueParameter = pluginContext.irFactory.createValueParameter(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        IrDeclarationOrigin.DEFINED,
                        IrValueParameterSymbolImpl(),
                        vp.name,
                        vp.index,
                        vp.type,
                        null, false, false, false, false
                    )
                    valueParameter.parent = childAccessor
                    childAccessor.valueParameters += listOf(valueParameter)
                }
            }
            childAccessor.overriddenSymbols += listOf(parentAccessor.symbol)
            childAccessor.parent = property.parentAsClass
            childAccessor.correspondingPropertySymbol = property.symbol
            if (parentAccessor.correspondingPropertySymbol!!.owner.setter == parentAccessor) {
                property.setter = childAccessor
            } else {
                property.getter = childAccessor
            }
        } else {
            throw PluginCodegenException("Only implemented for parent properties with getters that are protected or public")
        }
    }
}
