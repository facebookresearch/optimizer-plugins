/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep.analysisHandler

import diffPrep.Errors
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassMemberScope
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.TypeSubstitution
import org.jetbrains.kotlin.types.asSimpleType

class DifferentiableApiPreprocessorAnalysisHandlerExtension(
    boxedPrimitiveAnnotationName: String,
    reverseAnnotationName: String,
    toUnboxAnnotationName: String,
    scalarRoot: String,
    tensorRoot: String
) : AnalysisHandlerExtension {
    private val scalarRootAnnotationFqName = FqName(scalarRoot)
    private val boxedPrimitiveAnnotationFqName = FqName(boxedPrimitiveAnnotationName)
    private val reverseAnnotationFqName = FqName(reverseAnnotationName)
    private val toUnboxAnnotationFqName = FqName(toUnboxAnnotationName)
    private val tensorRootAnnotationFqName = FqName(tensorRoot)
    private val annotationParameterExpectations = mapOf(
        scalarRootAnnotationFqName to 0,
        boxedPrimitiveAnnotationFqName to 1,
        reverseAnnotationFqName to 5,
        toUnboxAnnotationFqName to 1,
        tensorRootAnnotationFqName to 0
    )

    private fun AnnotationDescriptor.valueParameterNamesIfAvailable(): List<Name>? = (type.constructor.declarationDescriptor as? ClassDescriptor)?.let { classDescriptor ->
        (classDescriptor.unsubstitutedMemberScope as? LazyClassMemberScope).let { memberScope ->
            memberScope?.getPrimaryConstructor()?.valueParameters?.map { it.name }
        }
    }

    private fun ClassDescriptor.properties(): List<PropertyDescriptor> = unsubstitutedMemberScope.getVariableNames().mapNotNull {
        unsubstitutedMemberScope.getContributedDescriptors(DescriptorKindFilter.ALL, { true }).filterIsInstance<PropertyDescriptor>().firstOrNull { member -> member.name == it }
    }

    private fun SimpleFunctionDescriptor.allParameters(): List<ParameterDescriptor> {
        val allParameters = mutableListOf<ParameterDescriptor>()
        if (extensionReceiverParameter != null) {
            allParameters.add(extensionReceiverParameter!!)
        }
        if (dispatchReceiverParameter != null) {
            allParameters.add(dispatchReceiverParameter!!)
        }
        allParameters.addAll(valueParameters)
        return allParameters
    }

    override fun analysisCompleted(
        project: Project,
        module: ModuleDescriptor,
        bindingTrace: BindingTrace,
        files: Collection<KtFile>
    ): AnalysisResult {
        class AnnotatedDeclaration<T>(val declarationDescriptor: T, val annotation: AnnotationDescriptor, val ktAnnotationEntry: KtAnnotationEntry)

        class DiffApiCollector : KtVisitorVoid() {
            var scalarRootClass: ClassDescriptor? = null
            var tensorRoot: ClassDescriptor? = null
            var reverseClass: AnnotatedDeclaration<ClassDescriptor>? = null
            var boxedPrimitiveName: AnnotatedDeclaration<ClassDescriptor>? = null
            var primitiveType: SimpleType? = null
            val unboxableFunctions = mutableListOf<AnnotatedDeclaration<SimpleFunctionDescriptor>>()

            override fun visitNamedFunction(function: KtNamedFunction) {
                (bindingTrace.bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, function) as? SimpleFunctionDescriptor)?.let { descriptor ->
                    if (descriptor.annotations.hasAnnotation(toUnboxAnnotationFqName)) {
                        val (index, annotation: AnnotationDescriptor) = descriptor.annotations.withIndex()
                            .first { it.value.fqName == toUnboxAnnotationFqName }
                        val annotationKtElement: KtAnnotationEntry = function.annotationEntries[index]
                        unboxableFunctions.add(AnnotatedDeclaration(descriptor, annotation, annotationKtElement))
                    }
                }
            }

            override fun visitClass(klass: KtClass) {
                (bindingTrace.bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, klass) as? ClassDescriptor)?.let { descriptor ->
                    when {
                        descriptor.annotations.hasAnnotation(scalarRootAnnotationFqName) -> {
                            scalarRootClass = descriptor
                        }
                        descriptor.annotations.hasAnnotation(tensorRootAnnotationFqName) -> {
                            tensorRoot = descriptor
                        }
                        descriptor.annotations.hasAnnotation(boxedPrimitiveAnnotationFqName) -> {
                            val (index, annotation) = descriptor.annotations.withIndex().first { it.value.fqName == boxedPrimitiveAnnotationFqName }
                            val annotationKtElement: KtAnnotationEntry = klass.annotationEntries[index]
                            boxedPrimitiveName = AnnotatedDeclaration(descriptor, annotation, annotationKtElement)
                            annotation.valueParameterNamesIfAvailable()?.let { orderedPropertyNames ->
                                if (orderedPropertyNames.size == 1) {
                                    val name = orderedPropertyNames.first()
                                    val arguments: Map<Name, ConstantValue<*>> = annotation.allValueArguments
                                    val memberName = arguments[name]?.value
                                    val candidates = descriptor.properties().filter { it.name.toString() == memberName }
                                    val memberDescriptor = when {
                                        candidates.isEmpty() || candidates.size > 1 -> {
                                            bindingTrace.report(Errors.ANNOTATION_REFERENCES_UNRESOLVED_DECLARATIONS.on(annotationKtElement, "`${descriptor.name}` contains either no member `$memberName` or multiple members"))
                                            null
                                        }
                                        else -> candidates.first()
                                    }
                                    primitiveType = memberDescriptor?.getter?.returnType?.asSimpleType()
                                }
                            }
                        }
                        descriptor.annotations.hasAnnotation(reverseAnnotationFqName) -> {
                            val (index, annotation) = descriptor.annotations.withIndex().first { it.value.fqName == reverseAnnotationFqName }
                            val annotationKtElement: KtAnnotationEntry = klass.annotationEntries[index]
                            reverseClass = AnnotatedDeclaration(descriptor, annotation, annotationKtElement)
                        }
                        else -> {
                            for (annotation in annotationParameterExpectations) {
                                if (descriptor.fqNameSafe == annotation.key) {
                                    val propertyDescriptors = descriptor.properties()
                                    if (propertyDescriptors.size != annotation.value) {
                                        bindingTrace.report(Errors.INVALID_SIGNATURE_ANNOTATION.on(klass, "Expected `${annotation.key}` to have ${annotation.value} properties"))
                                    } else if (propertyDescriptors.any { it.type != module.builtIns.stringType }) {
                                        bindingTrace.report(Errors.INVALID_SIGNATURE_ANNOTATION.on(klass, "Expected `${annotation.key}` to have ${annotation.value} String fields"))
                                    }
                                }
                            }
                        }
                    }
                }
                klass.declarations.forEach { it.accept(this) }
            }
        }

        val collector = DiffApiCollector()
        files.forEach {
            for (declaration in it.declarations) {
                declaration.accept(collector)
            }
        }

        collector.reverseClass?.let { annotatedReverseClassDeclaration ->
            collector.primitiveType?.let { primitiveType ->
                collector.scalarRootClass?.let { scalarRootClass ->
                    collector.tensorRoot?.let { tensorRoot ->
                        for (annotatedUnboxableFunction in collector.unboxableFunctions) {
                            val allValueArguments = annotatedUnboxableFunction.annotation.allValueArguments
                            val orderedProperties = annotatedUnboxableFunction.annotation.valueParameterNamesIfAvailable()
                            if (orderedProperties == null) continue
                            if (allValueArguments.size == 1 && orderedProperties.size == 1) {
                                val target = allValueArguments.get(orderedProperties.first())
                                if (target == null) {
                                    break
                                }
                                val stringArgument = target.value as? String
                                if (stringArgument == null) {
                                    break
                                }
                                val fqName = FqName(stringArgument)
                                val targetCandidates = module.resolveClassByFqName(fqName.parent(), NoLookupLocation.FOR_ALREADY_TRACKED)
                                    ?.getMemberScope(TypeSubstitution.EMPTY)
                                    ?.getContributedFunctions(fqName.shortName(), NoLookupLocation.FOR_ALREADY_TRACKED)
                                    ?: module.getPackage(fqName.parent()).memberScope.getDescriptorsFiltered(DescriptorKindFilter.FUNCTIONS) { name -> name == fqName.shortName() }.filterIsInstance<SimpleFunctionDescriptor>()

                                val boxedParameters = annotatedUnboxableFunction.declarationDescriptor.allParameters()
                                fun SimpleType.canBeUnboxedTo(unboxedType: SimpleType): Boolean {
                                    val boxedType = this
                                    return if (boxedType.constructor.declarationDescriptor == scalarRootClass || boxedType.constructor.declarationDescriptor == tensorRoot) {
                                        unboxedType == primitiveType
                                    } else {
                                        boxedType == unboxedType
                                    }
                                }
                                val compatibleCandidates = targetCandidates.filter {
                                    val unboxedCandidateParameters = it.allParameters()
                                    if (unboxedCandidateParameters.size == boxedParameters.size) {
                                        val parametersCompatible = boxedParameters.zip(unboxedCandidateParameters).none { (boxedParameter, unboxedParameter) ->
                                            !boxedParameter.type.asSimpleType().canBeUnboxedTo(unboxedParameter.type.asSimpleType())
                                        }
                                        val boxedReturnType = annotatedUnboxableFunction.declarationDescriptor.returnType?.asSimpleType()
                                        val unboxedReturnType = it.returnType?.asSimpleType()
                                        val returnTypesCompatible = if (boxedReturnType != null && unboxedReturnType != null) boxedReturnType.canBeUnboxedTo(unboxedReturnType) else false
                                        parametersCompatible && returnTypesCompatible
                                    } else {
                                        false
                                    }
                                }
                                when {
                                    compatibleCandidates.size > 1 -> {
                                        TODO()
                                    }
                                    compatibleCandidates.isEmpty() -> {
                                        bindingTrace.report(Errors.NO_UNBOXEDFUNCTION_FOUND.on(annotatedUnboxableFunction.ktAnnotationEntry, "No function named '$stringArgument' is compatible with the boxed function ${annotatedUnboxableFunction.declarationDescriptor.name}"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return AnalysisResult.success(
            bindingContext = bindingTrace.bindingContext,
            module = module,
            shouldGenerateCode = bindingTrace.bindingContext.diagnostics.filter { it.severity == Severity.ERROR }.isEmpty()
        )
    }
}
