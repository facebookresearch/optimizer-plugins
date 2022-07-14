/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.utils.Printer
import java.lang.IllegalStateException

// DO NOT VEND MUTABLE FIELDS FROM OWNER; mutable fields are mutated in the JvmLowering stages and likely will not evaluate to what you expect by the time the methods are invoked
class GeneratedAuthenticClassDescriptor(
    val _containingDescriptor: DeclarationDescriptor,
    val _upperBounds: List<KotlinType>,
    receiverDescriptorCreator: () -> ReceiverParameterDescriptor,
    val owner: IrClass,
    override val annotations: Annotations
) : ClassDescriptor {
    val receiverDescriptor: ReceiverParameterDescriptor by lazy {
        receiverDescriptorCreator()
    }
    val propertyDescriptors: MutableList<PropertyDescriptor> = mutableListOf<PropertyDescriptor>()
    val constructorDescriptors: MutableList<ClassConstructorDescriptor> = mutableListOf<ClassConstructorDescriptor>()
    val simpleFunctionDescriptors: MutableList<SimpleFunctionDescriptor> = mutableListOf<SimpleFunctionDescriptor>()
    private val memberScope: MemberScope = object : MemberScope {
        override fun getClassifierNames(): Set<Name> = emptySet()
        override fun getContributedClassifier(name: Name, location: LookupLocation): ClassifierDescriptor? = null
        override fun getContributedDescriptors(
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean
        ): Collection<DeclarationDescriptor> {
            return when {
                kindFilter == DescriptorKindFilter.FUNCTIONS -> simpleFunctionDescriptors.filter { nameFilter(it.name) }.toList()
                kindFilter == DescriptorKindFilter.VARIABLES -> propertyDescriptors.filter { nameFilter(it.name) }.toList()
                else -> (propertyDescriptors + simpleFunctionDescriptors).filter { nameFilter(it.name) }
            }
        }
        override fun getContributedFunctions(
            name: Name,
            location: LookupLocation
        ): Collection<SimpleFunctionDescriptor> = simpleFunctionDescriptors.filter { it.name == name }.toList()
        override fun getContributedVariables(name: Name, location: LookupLocation): Collection<PropertyDescriptor> = propertyDescriptors.filter { it.name == name }.toList()
        override fun getFunctionNames(): Set<Name> = simpleFunctionDescriptors.map { it.name }.toSet()
        override fun getVariableNames(): Set<Name> = propertyDescriptors.map { it.name }.toSet()
        override fun printScopeStructure(p: Printer) {}
    }
    private val _typeConstructor: TypeConstructor by lazy {
        object : AbstractTypeConstructor(LockBasedStorageManager.NO_LOCKS) {
            override fun computeSupertypes() = _upperBounds
            override val supertypeLoopChecker = SupertypeLoopChecker.EMPTY
            override fun getParameters(): List<TypeParameterDescriptor> = emptyList()
            override fun isFinal() = false
            override fun isDenotable() = true
            override fun getDeclarationDescriptor() = this@GeneratedAuthenticClassDescriptor
            override fun getBuiltIns() = module.builtIns
            override fun isSameClassifier(classifier: ClassifierDescriptor): Boolean = declarationDescriptor === classifier
        }
    }
    private val liftedClassDefaultType: SimpleType by lazy {
        val typeConstructor = ClassTypeConstructorImpl(this, emptyList(), _typeConstructor.supertypes, LockBasedStorageManager("GeneratedAuthenticClassDescriptor"))
        KotlinTypeFactory.simpleTypeWithNonTrivialMemberScope(TypeAttributes.Empty, typeConstructor, emptyList(), false, memberScope)
    }
    override fun getName(): Name = owner.name
    override fun getOriginal(): ClassDescriptor = this
    override fun getContainingDeclaration(): DeclarationDescriptor = _containingDescriptor
    override fun <R : Any?, D : Any?> accept(p0: DeclarationDescriptorVisitor<R, D>?, p1: D): R = p0?.visitClassDescriptor(this, p1) ?: throw IllegalStateException("visitor was null")
    override fun acceptVoid(p0: DeclarationDescriptorVisitor<Void, Void>?) { p0?.visitClassDescriptor(this, null) ?: throw IllegalStateException("visitor was null") }
    override fun getSource(): SourceElement = SourceElement.NO_SOURCE
    override fun getTypeConstructor(): TypeConstructor = _typeConstructor
    override fun getDefaultType(): SimpleType = liftedClassDefaultType
    override fun getVisibility(): DescriptorVisibility = owner.visibility
    override fun getModality(): Modality = owner.modality
    override fun isExpect(): Boolean = owner.isExpect
    override fun isActual(): Boolean = false
    override fun isExternal(): Boolean = owner.isExternal
    override fun substitute(substitutor: TypeSubstitutor): ClassifierDescriptorWithTypeParameters = throw UnsupportedOperationException("IrBased descriptors SHOULD NOT be substituted")
    override fun isInner(): Boolean = false
    override fun getDeclaredTypeParameters(): List<TypeParameterDescriptor> = emptyList()
    override fun getMemberScope(typeArguments: MutableList<out TypeProjection>) = memberScope
    override fun getMemberScope(p0: TypeSubstitution): MemberScope = memberScope
    override fun getUnsubstitutedMemberScope(): MemberScope = memberScope
    override fun getUnsubstitutedInnerClassesScope(): MemberScope = memberScope
    override fun getStaticScope(): MemberScope = MemberScope.Empty
    override fun getConstructors(): List<ClassConstructorDescriptor> = constructorDescriptors
    override fun getCompanionObjectDescriptor(): ClassDescriptor? = null
    override fun getKind(): ClassKind = owner.kind
    override fun isCompanionObject(): Boolean = false
    override fun isData(): Boolean = owner.isData
    override fun isInline(): Boolean = true
    override fun isFun(): Boolean = owner.isFun
    override fun isValue(): Boolean = false
    override fun getThisAsReceiverParameter(): ReceiverParameterDescriptor = receiverDescriptor
    override fun getContextReceivers(): List<ReceiverParameterDescriptor> = listOf(receiverDescriptor)

    override fun getUnsubstitutedPrimaryConstructor(): ClassConstructorDescriptor? = constructorDescriptors.first { it.isPrimary }
    override fun getSealedSubclasses(): Collection<ClassDescriptor> = emptyList()
    override fun getInlineClassRepresentation(): InlineClassRepresentation<SimpleType>? = null
    override fun getDefaultFunctionTypeForSamInterface(): SimpleType? = null
    override fun isDefinitelyNotSamInterface(): Boolean = true
}
