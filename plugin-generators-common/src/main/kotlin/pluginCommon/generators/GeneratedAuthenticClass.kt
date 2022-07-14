/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.descriptors.toIrBasedKotlinType
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.name.Name

// A synthetic class cannot be loaded into deserialized descriptors from a consuming module
// An authentic class has it's member scope written into a protobuf that is then serialized into an annotation persisted to the .class file,
// allowing consuming modules to load representations and in some cases the containing code at compile time.
// If you create an IrClass with the IrFactory provided by the compiler, it is considered a synthetic class (see ClassHeader in Kotlin compiler)
// and is not visible to consumers.
class GeneratedAuthenticClass(
    override val startOffset: Int,
    override val endOffset: Int,
    override var origin: IrDeclarationOrigin,
    override val name: Name,
    override val kind: ClassKind,
    override var visibility: DescriptorVisibility,
    override var modality: Modality,
    override var parent: IrDeclarationParent,
    override var superTypes: List<IrType>,
    receiverCreator: (IrClass) -> IrValueParameter,
    override val isCompanion: Boolean = false,
    override val isInner: Boolean = false,
    override val isData: Boolean = false,
    override val isExternal: Boolean = false,
    override val isInline: Boolean = false,
    override val isExpect: Boolean = false,
    override val isFun: Boolean = false,
    override val source: SourceElement = SourceElement.NO_SOURCE
) : IrClass() {
    override val descriptor: GeneratedAuthenticClassDescriptor
    override val symbol: IrClassSymbol
    override var thisReceiver: IrValueParameter? = null

    class AuthenticClassSymbol(override val descriptor: GeneratedAuthenticClassDescriptor) : IrClassSymbol {
        override val hasDescriptor: Boolean = true
        override val isBound: Boolean = true
        override val owner: IrClass = descriptor.owner
        override val signature: IdSignature? = null
        override var privateSignature: IdSignature? = null
        override fun bind(owner: IrClass) {
            throw IllegalStateException("generated authentic classes are not bound by calling bind, they are bound by injecting the owner")
        }
    }

    init {
        val parentDescriptor = when (val p = parent) {
            is IrFile -> p.packageFragmentDescriptor
            is IrDeclaration -> p.descriptor
            else -> throw NotImplementedError("only declarations and files are recognized as parents of classes")
        }
        this.descriptor = GeneratedAuthenticClassDescriptor(
            parentDescriptor, superTypes.map { it.toIrBasedKotlinType() }, { this.thisReceiver!!.descriptor as ReceiverParameterDescriptor }, this,
            Annotations.EMPTY
        )
        this.symbol = AuthenticClassSymbol(this.descriptor)
        this.thisReceiver = receiverCreator(this)
    }

    override var annotations: List<IrConstructorCall> = emptyList()
    override var attributeOwnerId: IrAttributeContainer = this
    override val factory: IrFactory = IrFactoryImpl
    override var inlineClassRepresentation: InlineClassRepresentation<IrSimpleType>? = null
    override var metadata: MetadataSource? = null
        set(value) {
            if (field == null) {
                field = value
            } else {
                throw IllegalStateException("Cannot reset metadata")
            }
        }
    override var sealedSubclasses: List<IrClassSymbol> = emptyList()
    override var typeParameters: List<IrTypeParameter> = emptyList()
    fun hasMetadata() = metadata != null
    fun setMetadata() {
        metadata = DescriptorMetadataSource.Class(this.descriptor)
    }

    // we have to enable additions and removals after the metadata is set because IrProperties are removed in a lowering phase and IrFields are added
    override val declarations: MutableList<IrDeclaration> = WatchableMutableList(object : WatchableMutableListDelegate<IrDeclaration> {
        override fun didRemoveAll(collection: Collection<IrDeclaration>) {
            if (!hasMetadata()) {
                collection.forEach { didRemove(it) }
            }
        }
        override fun didRemove(item: IrDeclaration) {
            if (!hasMetadata()) {
                when (item) {
                    is IrProperty -> descriptor.propertyDescriptors.remove(item.descriptor)
                    is IrSimpleFunction -> descriptor.simpleFunctionDescriptors.remove(item.descriptor as SimpleFunctionDescriptor)
                    is IrConstructor -> descriptor.constructorDescriptors.remove(item.descriptor)
                }
            }
        }
        override fun didAdd(collection: Collection<IrDeclaration>) {
            if (!hasMetadata()) {
                collection.forEach { didAdd(it) }
            }
        }

        override fun didAdd(item: IrDeclaration) {
            if (!hasMetadata()) {
                when (item) {
                    is IrProperty -> descriptor.propertyDescriptors.add(item.descriptor)
                    is IrSimpleFunction -> descriptor.simpleFunctionDescriptors.add(item.descriptor as SimpleFunctionDescriptor)
                    is IrConstructor -> descriptor.constructorDescriptors.add(item.descriptor)
                }
            }
        }
    })
}
