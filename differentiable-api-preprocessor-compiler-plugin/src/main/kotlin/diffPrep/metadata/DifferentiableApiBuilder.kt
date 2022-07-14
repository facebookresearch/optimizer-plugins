/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep.metadata

import diffPrep.DiffApiPrepException
import org.jetbrains.kotlin.backend.common.ir.allParameters
import org.jetbrains.kotlin.backend.common.ir.allParametersCount
import org.jetbrains.kotlin.backend.jvm.ir.propertyIfAccessor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.utils.OperatorNames
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.interpreter.getAnnotation
import org.jetbrains.kotlin.ir.interpreter.hasAnnotation
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName
import pluginCommon.generators.overrideRoot

enum class MemberType { Function, Property }
class DifferentiableApiBuilder(
    val fqReverseNodeName: FqName,
    val fqForwardNodeName: FqName,
    val fqPrimalAndPullbackName: FqName,
    val boxedPrimitiveFqName: FqName,
    val scalarRootFqName: FqName,
    val dTensorAnnotationFqName: FqName,
    val operationsFqName: FqName,
    val stackImplName: FqName,
    val scalarNoopFqName: FqName
) {
    fun differentiableApi(module: IrModuleFragment, messageLogger: IrMessageLogger): DifferentiableApi? {
        var canForceUnwrapAllDifferenitableApiMembers = true
        fun logWarning(message: String) {
            messageLogger.report(IrMessageLogger.Severity.WARNING, message, null)
            if (canForceUnwrapAllDifferenitableApiMembers) {
                canForceUnwrapAllDifferenitableApiMembers = false
            }
        }

        var differentiableScalarRootTypeMaybe: IrType? = null
        val plusCandidates = mutableListOf<IrFunction>()
        var boxedPrimitiveClass: IrClass? = null
        var dTensorRootMaybe: IrClass? = null
        var operationsClass: IrClass? = null
        var primalAndPullbackFunction: IrSimpleFunction? = null
        var stackClass: StackClass? = null
        var scalarNoopAnnotationClass: IrClass? = null
        var reverseClass: IrClass? = null
        var forwardClass: IrClass? = null

        module.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
                if (declaration.name == OperatorNames.ADD && declaration.allParametersCount == 2) {
                    plusCandidates.add(declaration)
                }
                if (declaration.hasAnnotation(fqPrimalAndPullbackName)) {
                    // TODO: verification on expected signature
                    primalAndPullbackFunction = declaration as IrSimpleFunction
                }
            }

            override fun visitClass(declaration: IrClass) {
                when {
                    declaration.hasAnnotation(dTensorAnnotationFqName) -> {
                        if (dTensorRootMaybe != null) logWarning("Only one class may be marked as $dTensorAnnotationFqName")
                        dTensorRootMaybe = declaration
                    }
                    declaration.hasAnnotation(fqReverseNodeName) -> {
                        if (declaration.getAnnotation(fqReverseNodeName).valueArgumentsCount != 5) {
                            logWarning("Expected the reverse annotation $fqReverseNodeName to contain 5 arguments")
                        }
                        reverseClass = declaration
                    }
                    declaration.hasAnnotation(fqForwardNodeName) -> {
                        if (declaration.getAnnotation(fqForwardNodeName).valueArgumentsCount != 1) {
                            logWarning("Expected the forward annotation ${fqForwardNodeName}NodeName to contain 1 argument")
                        }
                        forwardClass = declaration
                    }
                    declaration.fqNameForIrSerialization.toString() == scalarNoopFqName.toString() -> {
                        scalarNoopAnnotationClass = declaration
                    }
                    declaration.hasAnnotation(stackImplName) -> {
                        if (stackClass != null) throw IllegalStateException("Only one class may be marked as $stackImplName")
                        val push = declaration.functions.firstOrNull { it.name.toString() == "push" }
                        val pop = declaration.functions.firstOrNull { it.name.toString() == "pop" }
                        val empty = declaration.functions.firstOrNull { it.name.toString() == "notEmpty" }
                        val top = declaration.functions.firstOrNull { it.name.toString() == "top" }
                        if (push != null && pop != null && empty != null && top != null) {
                            if (stackClass != null) logWarning("multiple stack classes annotated. Only one stack class should be annotated wit $stackImplName but found ${declaration.fqNameForIrSerialization} and ${stackClass!!.clazz.fqNameForIrSerialization}")
                            stackClass = StackClass(declaration, pop, push, empty, top)
                        } else {
                            logWarning("Stack implementation must have the following operations: pop, push, notEmpty, and top.")
                        }
                    }
                    declaration.hasAnnotation(scalarRootFqName) -> {
                        if (differentiableScalarRootTypeMaybe != null) logWarning("Only one class may be marked as $scalarRootFqName")
                        differentiableScalarRootTypeMaybe = declaration.defaultType
                    }
                    declaration.hasAnnotation(boxedPrimitiveFqName) -> {
                        if (boxedPrimitiveClass != null) logWarning("Expected exactly one scalar root but multiple classes are annotated")
                        boxedPrimitiveClass = declaration
                    }
                    declaration.hasAnnotation(operationsFqName) -> {
                        if (operationsClass != null) logWarning("Expected exactly one reverse operations class but multiple classes are annotated")
                        operationsClass = declaration
                    }
                }
            }
        })

        val (scalarPlus, boxedPrimitiveInfo) = differentiableScalarRootTypeMaybe?.let { differentiableScalarRoot ->
            val scalarPlus = plusCandidates.firstOrNull { plusCandidate -> plusCandidate.allParameters.all { it.type == differentiableScalarRoot } }?.let { it as? IrSimpleFunction }
            val boxedPrimitiveInfo: BoxedPrimitiveInfo? = boxedPrimitiveClass?.let { boxedPrimitiveClazz ->
                if (boxedPrimitiveClazz.isSubclassOf(differentiableScalarRoot.getClass() ?: throw DiffApiPrepException("Expected the root of the differentiable hierarchy to have a class: ${differentiableScalarRoot.render()}"))) {
                    val valueProperty: IrProperty = boxedPrimitiveClazz.extractMember(0, boxedPrimitiveClazz.getAnnotation(boxedPrimitiveFqName), MemberType.Property) as IrProperty
                    val primitiveType = valueProperty.backingField?.type ?: valueProperty.getter?.returnType ?: throw DiffApiPrepException("Could not infer property type ${valueProperty.nameForIrSerialization}")
                    when {
                        primitiveType.isFloat() || primitiveType.isDouble() -> {
                            // finds a property that is initialized with a value of 0
                            val zeroProperty = boxedPrimitiveClazz.primaryConstructor?.let { constructor ->
                                if (constructor.valueParameters.count() == 1 && constructor.valueParameters.all { it.type == primitiveType }) {
                                    fun IrProperty.isBoxedPrimitiveInitializedWithZero(): Boolean {
                                        val initializer = backingField?.initializer?.expression
                                        return if (initializer is IrConstructorCall && initializer.symbol == constructor.symbol) {
                                            val arg = initializer.getValueArgument(0)
                                            if (arg is IrConst<*>) {
                                                (arg.value as Number).toFloat() == 0f
                                            } else false
                                        } else false
                                    }
                                    boxedPrimitiveClazz.companionObject()?.properties?.firstOrNull { it.isBoxedPrimitiveInitializedWithZero() }
                                } else {
                                    logWarning("boxed primitive `${boxedPrimitiveClazz.name}`'s primary constructor must contain a single primitive value")
                                    null
                                }
                            }
                            val oneProperty = boxedPrimitiveClazz.primaryConstructor?.let { constructor ->
                                if (constructor.valueParameters.count() == 1 && constructor.valueParameters.all { it.type == primitiveType }) {
                                    fun IrProperty.isBoxedPrimitiveInitializedWithOne(): Boolean {
                                        val initializer = backingField?.initializer?.expression
                                        return if (initializer is IrConstructorCall && initializer.symbol == constructor.symbol) {
                                            val arg = initializer.getValueArgument(0)
                                            if (arg is IrConst<*>) {
                                                (arg.value as Number).toFloat() == 1f
                                            } else false
                                        } else false
                                    }
                                    boxedPrimitiveClazz.companionObject()?.properties?.firstOrNull { it.isBoxedPrimitiveInitializedWithOne() }
                                } else {
                                    logWarning("boxed primitive `${boxedPrimitiveClazz.name}`'s primary constructor must contain a single primitive value")
                                    null
                                }
                            }
                            if (zeroProperty != null && oneProperty != null) {
                                BoxedPrimitiveInfo(boxedPrimitiveClazz, valueProperty, primitiveType, zeroProperty, oneProperty, messageLogger)
                            } else {
                                logWarning("boxed primitive must be a subclass of the scalar root `${differentiableScalarRoot.render()}`")
                                null
                            }
                        }
                        else -> {
                            logWarning("Only double and float are supported in the box primitive, but encountered `${primitiveType.render()}`")
                            null
                        }
                    }
                } else {
                    logWarning("boxed primitive must be a subclass of the scalar root `${differentiableScalarRoot.render()}`")
                    null
                }
            }
            Pair(scalarPlus, boxedPrimitiveInfo)
        } ?: Pair(null, null)
        val tensorPlus = dTensorRootMaybe?.let { differentiableTensorRoot -> plusCandidates.firstOrNull { plusCandidate -> plusCandidate.allParameters.all { it.type == differentiableTensorRoot.defaultType } }?.let { it as? IrSimpleFunction } }
        if (scalarPlus == null) {
            logWarning("No plus operation function found in `${module.name}`. Be sure that addition is defined over the root differentiable scalar types")
        }
        if (tensorPlus == null) {
            logWarning("No plus operation function found in `${module.name}`. Be sure that addition is defined over the root differentiable tensor types")
        }
        if (primalAndPullbackFunction == null) {
            logWarning("No primalAndPullback function found in `${module.name}`. Be sure to use the annotation `$fqPrimalAndPullbackName` on the scalar primal and pullback function")
        }
        if (boxedPrimitiveInfo == null) {
            logWarning("No BoxedPrimitive found in `${module.name}`. Be sure to use the annotation `$boxedPrimitiveFqName` on the boxed primitive class")
        }
        if (dTensorRootMaybe == null) {
            logWarning("No DTensor found in `${module.name}`. Be sure to use the annotation `$dTensorAnnotationFqName` on the DTensor class")
        }
        if (operationsClass == null) {
            logWarning("No reverse operations found in `${module.name}`. Be sure to use the annotation `$operationsFqName` on the Reverse DScalar Operations class")
        }
        if (scalarNoopAnnotationClass == null) {
            logWarning("No scalar noop annotation class found in `${module.name}`. Be sure to use the annotation `$scalarNoopFqName` on the Reverse DScalar Operations class")
        }
        if (forwardClass == null) {
            logWarning("No forward class found. Annotate the class used to implement forward differentiation over scalars with the annotation `$forwardClass`")
        }
        if (stackClass == null) {
            logWarning("No stack class found. Annotate the stack class with `$stackImplName` annotation.")
        }
        if (differentiableScalarRootTypeMaybe == null) {
            logWarning("No scalar root found. Annotate the scalar root with `$scalarRootFqName` annotation")
        }

        try {
            val diffApiMaybe = if (canForceUnwrapAllDifferenitableApiMembers) {
                val reverseClass = reverseClass!!
                val forwardClass = forwardClass!!
                val stackClass = stackClass!!
                val scalarNoopAnnotationClass = scalarNoopAnnotationClass!!
                val scalarPlus = scalarPlus!!
                val tensorPlus = tensorPlus!!
                val boxedPrimitiveInfo = boxedPrimitiveInfo!!
                val primalAndPullbackFunction = primalAndPullbackFunction!!
                val dTensor = dTensorRootMaybe!!
                val operations = operationsClass!!
                val differentiableScalarRoot = differentiableScalarRootTypeMaybe!!

                val forwardNodeAnnotationInstance = forwardClass.getAnnotation(fqForwardNodeName)
                val reverseNodeAnnotationInstance = reverseClass.getAnnotation(fqReverseNodeName)
                val primalProperty = (
                    reverseClass.extractMember(
                        0,
                        reverseNodeAnnotationInstance,
                        MemberType.Property
                    ) as IrProperty
                    ).getter!!.overrideRoot().owner.propertyIfAccessor as IrProperty
                val derivativeId = (
                    reverseClass.extractMember(
                        4,
                        reverseNodeAnnotationInstance,
                        MemberType.Property
                    ) as IrProperty
                    ).getter!!.overrideRoot().owner.propertyIfAccessor as IrProperty

                DifferentiableApi(
                    reverseDifferentiableScalar = ReverseDifferentiableScalarMetadata(
                        clazz = reverseClass,
                        upstreamProperty = reverseClass.extractMember(
                            1,
                            reverseNodeAnnotationInstance,
                            MemberType.Property
                        ) as IrProperty,
                        backpropMethod = reverseClass.extractMember(
                            2,
                            reverseNodeAnnotationInstance,
                            MemberType.Function
                        ) as IrSimpleFunction,
                        pushbackMethod = reverseClass.extractMember(
                            3,
                            reverseNodeAnnotationInstance,
                            MemberType.Function
                        ) as IrSimpleFunction
                    ),
                    primalProperty = primalProperty,
                    derivativeId = derivativeId,
                    scalarPlusFunction = scalarPlus,
                    tensorPlusFunction = tensorPlus,
                    scalarRoot = differentiableScalarRoot.getClass()!!,
                    primalAndPullbackFunction = primalAndPullbackFunction,
                    boxedPrimitiveInfo = boxedPrimitiveInfo,
                    dTensorRoot = dTensor,
                    reverseOperations = operations,
                    stackClass = stackClass,
                    forwardDifferentiableScalar = ForwardDifferentiableScalarMetadata(
                        clazz = forwardClass,
                        tangentProperty = forwardClass.extractMember(
                            0,
                            forwardNodeAnnotationInstance,
                            MemberType.Property
                        ) as IrProperty
                    ),
                    scalarNoopClass = scalarNoopAnnotationClass
                )
            } else null
            return diffApiMaybe
        } catch (e: NullPointerException) {
            return null
        }
    }

    fun IrClass.extractMember(index: Int, annotationInstance: IrConstructorCall, memberType: MemberType): IrDeclaration {
        when (val expression = annotationInstance.getValueArgument(index)) {
            is IrConst<*> -> {
                val value = expression.value as String
                val needles = this.declarations.filter {
                    when (it) {
                        is IrAnonymousInitializer -> false
                        else -> {
                            it.nameForIrSerialization.asString() == value
                        }
                    }
                }

                val results = when {
                    memberType == MemberType.Function -> needles.filterIsInstance<IrFunction>()
                    memberType == MemberType.Property -> needles.filterIsInstance<IrProperty>()
                    else -> null
                }

                if (results == null || results.size > 1) {
                    throw DiffApiPrepException("The annotation instance `${annotationInstance.type.getClass()?.render() ?: "Unknown"}` failed to specify a unique member at index $index")
                }
                return results.first()
            }
            else -> {
                throw DiffApiPrepException("Expected constant expression for argument ${annotationInstance.getArguments()[index].first.name}")
            }
        }
    }
}
