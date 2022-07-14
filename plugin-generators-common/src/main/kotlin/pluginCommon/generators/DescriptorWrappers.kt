/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBasedClassConstructorDescriptor
import org.jetbrains.kotlin.ir.descriptors.IrBasedFieldDescriptor
import org.jetbrains.kotlin.ir.descriptors.IrBasedPropertyDescriptor
import org.jetbrains.kotlin.ir.descriptors.IrBasedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.resolve.constants.ConstantValue

internal open class LateInitIRSymbol<TD : DeclarationDescriptor, TIR : IrDeclaration>(val descriptorCreator: (TIR) -> TD) : IrBindableSymbol<TD, TIR> {
    @ObsoleteDescriptorBasedAPI
    override val descriptor: TD
        get() = lateInitDescriptor ?: throw IllegalStateException("the descriptor of the symbol has not yet been bound")
    private var lateInitDescriptor: TD? = null
    private var _owner: TIR? = null

    @ObsoleteDescriptorBasedAPI
    override val hasDescriptor: Boolean
        get() = lateInitDescriptor != null
    override val isBound: Boolean
        get() = _owner != null
    override val owner: TIR
        get() = _owner ?: throw IllegalStateException("The symbol is unbound!")
    override val signature: IdSignature? = null

    override fun bind(owner: TIR) {
        _owner = owner
        lateInitDescriptor = descriptorCreator(owner)
    }

    override var privateSignature: IdSignature? = null
}

internal class LateInitFunctionSymbol : LateInitIRSymbol<FunctionDescriptor, IrSimpleFunction>(::FunctionIrBasedDescriptorWrapper), IrSimpleFunctionSymbol
internal class LateInitPropertySymbol : LateInitIRSymbol<PropertyDescriptor, IrProperty>(::PropertyIrBasedDescriptorWrapper), IrPropertySymbol
internal class LateInitFieldSymbol : LateInitIRSymbol<PropertyDescriptor, IrField>(::FieldIrBasedDescriptorWrapper), IrFieldSymbol
internal class LateInitConstructorSymbol : LateInitIRSymbol<ClassConstructorDescriptor, IrConstructor>(::ConstructorIrBasedDescriptorWrapper), IrConstructorSymbol

internal class ConstructorIrBasedDescriptorWrapper(c: IrConstructor) : IrBasedClassConstructorDescriptor(c) {
    override fun hasStableParameterNames(): Boolean {
        return true
    }
}

internal class FunctionIrBasedDescriptorWrapper(f: IrSimpleFunction) : IrBasedSimpleFunctionDescriptor(f) {
    override fun hasStableParameterNames(): Boolean {
        return true
    }
}

internal class PropertyIrBasedDescriptorWrapper(p: IrProperty) : IrBasedPropertyDescriptor(p) {
    private var backingField: FieldDescriptor? = null
    internal fun initialize() {
        backingField = owner.backingField?.descriptor as? FieldDescriptor
    }
    override fun getCompileTimeInitializer(): ConstantValue<*>? = null
    override fun getBackingField(): FieldDescriptor? {
        return backingField
    }

    override fun getDelegateField(): FieldDescriptor? {
        return null
    }

    override fun getContextReceiverParameters(): List<ReceiverParameterDescriptor> = emptyList()
}

internal class FieldIrBasedDescriptorWrapper(f: IrField) : IrBasedFieldDescriptor(f), FieldDescriptor {
    override fun getCompileTimeInitializer(): ConstantValue<*>? {
        return null
    }

    override val correspondingProperty: PropertyDescriptor get() = owner.descriptor
}
