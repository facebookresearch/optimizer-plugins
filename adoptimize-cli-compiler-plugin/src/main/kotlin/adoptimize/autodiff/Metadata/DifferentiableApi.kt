/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.Metadata

import adOptimizeCommon.reverseNodeNameFromOperationsName
import adoptimize.AutoDiffException
import adoptimize.allParameters
import adoptimize.autodiff.*
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.TypeSubstitution
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.TypeParameterContext
import pluginCommon.generators.overrideRoot

sealed class UnboxMap
class OverloadMap(val targetFunction: IrFunction, val parameterMap: Map<Int, Int>) : UnboxMap()
class IdentityMap : UnboxMap()

class ReverseDifferentiableScalarMetadata(
    val clazz: IrClass,
    val upstreamProperty: IrProperty,
    val backpropMethod: IrSimpleFunction,
    val pushbackMethod: IrSimpleFunction,
    val primalProperty: IrProperty,
    val derivativeId: IrProperty,
    val setPrimal: IrSimpleFunction?
)

class ForwardDifferentiableScalarMetadata(
    val clazz: IrClass,
    val tangentProperty: IrProperty,
    val primalProperty: IrProperty,
    val derivativeId: IrProperty
)

class DifferentiableApi(
    val reverseDiffScalarClass: ReverseDifferentiableScalarMetadata,
    val forwardDiffScalarClass: ForwardDifferentiableScalarMetadata,
    private val scalarPlus: IrSimpleFunction,
    private val tensorPlus: IrSimpleFunction,
    val scalarRoot: IrClass,
    private val dTensorRoot: IrClass,
    val primalAndPullbackFunction: IrSimpleFunction,
    val boxedPrimitiveInfo: BoxedPrimitiveInfo,
    val toReverseAnnotation: FqName,
    val scalarReverseOperations: IrClass,
    val scalarNoopAnnotation: FqName,
    private val toUnboxFunctionFqName: FqName,
    private val apiPackage: PackageViewDescriptor,
    private val pluginContext: IrPluginContext,
) {
    private val boxedToUnboxMap = mutableMapOf<IrFunction, MutableMap<List<IrType>, UnboxMap>>()
    private val dispatchToReverseNode = mutableMapOf<IrFunction, IrClass>()
    val frameworkPropertyNames by lazy {
        listOf(reverseDiffScalarClass.primalProperty.name, reverseDiffScalarClass.upstreamProperty.name, reverseDiffScalarClass.derivativeId.name)
    }
    val rootDifferentiableType = dTensorRoot.defaultType

    fun plusFunction(rhs: IrType, lhs: IrType): IrSimpleFunction {
        require(rhs.isSubtypeOf(dTensorRoot.defaultType, IrTypeSystemContextImpl(pluginContext.irBuiltIns)))
        require(lhs.isSubtypeOf(dTensorRoot.defaultType, IrTypeSystemContextImpl(pluginContext.irBuiltIns)))
        return if (rhs == dTensorRoot.defaultType || lhs == dTensorRoot.defaultType) tensorPlus else scalarPlus
    }

    // first priority: annotation
    // second priority: search the ReverseScalarOperations for a function of the same name
    fun reverseNodeForFunction(function: IrFunction): IrClass? {
        return dispatchToReverseNode[function] ?: run {
            val explicitlyLinkedNode = function.dependencyNodeMaybe(toReverseAnnotation, pluginContext)
            val operationsCandidates = scalarReverseOperations.functions.filter {
                it.name == function.name
            }
            val rootPackage = scalarReverseOperations.packageFqName ?: throw AutoDiffException("the scalar reverse operations has no package fq name and we need that to find the lifted class")
            val reverseNode = when {
                explicitlyLinkedNode != null -> explicitlyLinkedNode
                operationsCandidates.count() == 1 -> {
                    val explicitlySpecifiedNode = operationsCandidates.first().dependencyNodeMaybe(toReverseAnnotation, pluginContext)
                    explicitlySpecifiedNode ?: run {
                        val baseName = reverseNodeNameFromOperationsName(operationsCandidates.first().kotlinFqName.toString())
                        val fqName = FqName("$rootPackage.$baseName")
                        pluginContext.irClassForFqName(fqName)
                    }
                }
                else -> null
            }
            reverseNode?.let { dispatchToReverseNode[function] = it }
            reverseNode
        }
    }

    /*
    * Given a function and a list of argument types, identify a function that overloads the given function by comparing the parameters of candidate functions with the argument types.
    * A candidate function is one of the following (with descending priority):
    * a) a function corresponding to a candidate explicitly specified in an annotation on the given function.
    * b) a function in the same module with the same name and matching non-changed arguments
    * c) a function in the builtins with the same name and primitive arguments. (Only applies to the case where the given function is an operation with all its instances lowered)
    *
    * If an eligible function is not identified from those options, an exception is thrown.
    * When the function does not need to be lowered, an IdentityMap is returned.
    * */
    fun unboxMap(boxedFunction: IrSimpleFunction, argumentTypes: List<IrSimpleType>, expectedReturnType: IrType): UnboxMap {
        if (boxedToUnboxMap[boxedFunction]?.get(argumentTypes) != null) {
            return boxedToUnboxMap[boxedFunction]!![argumentTypes]!!
        }
        if (boxedToUnboxMap[boxedFunction] == null) boxedToUnboxMap[boxedFunction] = mutableMapOf()
        val boxedFunctionAllParameters = boxedFunction.allParametersWithIndex()
        val hasExtensionReceiver = boxedFunction.extensionReceiverParameter != null
        val hasDispatchReceiver = boxedFunction.dispatchReceiverParameter != null

        fun argumentAt(index: Int): IrSimpleType = when {
            hasExtensionReceiver && hasDispatchReceiver -> argumentTypes[index + 2]
            hasExtensionReceiver && !hasDispatchReceiver -> if (index == -2) argumentTypes[0] else argumentTypes[index + 1]
            !hasExtensionReceiver && hasDispatchReceiver -> argumentTypes[index + 1]
            else -> argumentTypes[index]
        }
        fun IrSimpleType.canBeBoundTo(other: ParameterDescriptor): Boolean {
            val irType = pluginContext.typeTranslator.translateType(other.type) as? IrSimpleType ?: throw AutoDiffException("Expected only simple types in diff api but encounted ${pluginContext.typeTranslator.translateType(other.type).render()}")
            return when (val otherDescriptor = other.type.constructor.declarationDescriptor) {
                is TypeParameterDescriptor -> otherDescriptor.upperBounds.map { pluginContext.typeTranslator.translateType(it) }.all { isSubtypeOf(it, IrTypeSystemContextImpl(pluginContext.irBuiltIns)) }
                else -> isSubtypeOfType(irType)
            }
        }
        fun unboxedMatchForCandidate(unboxedMatch: SimpleFunctionDescriptor): UnboxMap {
            val unboxedIrFunction = (
                (
                    pluginContext.linker().getDeclaration(pluginContext.symbolTable.referenceSimpleFunction(unboxedMatch))
                        ?: throw AutoDiffException("Could not generate a stub for ${unboxedMatch.name}")
                    ) as? IrSimpleFunction
                ) ?: throw AutoDiffException("Expected a function for ${unboxedMatch.name}")
            return OverloadMap(unboxedIrFunction, boxedFunctionAllParameters.zip(unboxedIrFunction.allParametersWithIndex()).map { Pair(it.first.index, it.second.index) }.toMap())
        }
        fun findMatchFromFqName(fqName: FqName): SimpleFunctionDescriptor? {
            val parentClass = pluginContext.moduleDescriptor.resolveClassByFqName(fqName.parent(), NoLookupLocation.FROM_BACKEND)
            val unboxedFunctionCandidates = if (parentClass != null) {
                parentClass.getMemberScope(TypeSubstitution.EMPTY)
            } else {
                apiPackage.module.getPackage(fqName.parent()).memberScope
            }.getContributedFunctions(fqName.shortName(), NoLookupLocation.FROM_BACKEND)
                .map { Pair(it, it.allParameters()) }
                .filter { it.second.size == boxedFunctionAllParameters.size }
            return unboxedFunctionCandidates.firstOrNull {
                val returnIsCompatible = it.first.returnType?.let { returnType -> pluginContext.typeTranslator.translateType(returnType) == expectedReturnType } ?: false
                returnIsCompatible && boxedFunctionAllParameters.zip(it.second).all { (boxedFunctionParameter, unboxedParam) ->
                    argumentAt(boxedFunctionParameter.index).canBeBoundTo(unboxedParam.first)
                }
            }?.first
        }

        if (argumentTypes.size != boxedFunctionAllParameters.size) throw AutoDiffException("A lowered version of $boxedFunction was requested but not enough arguments were provided.")
        val typeContext = run {
            val (extensionReceiverArgumentType, dispatchReceiverArgumentType, valueArgumentTypes: List<IrSimpleType>) = when {
                hasExtensionReceiver && hasDispatchReceiver -> Triple(argumentTypes[0], argumentTypes[1], argumentTypes.subList(2, argumentTypes.size))
                hasExtensionReceiver && !hasDispatchReceiver -> Triple(argumentTypes[0], null, argumentTypes.subList(1, argumentTypes.size))
                !hasExtensionReceiver && hasDispatchReceiver -> Triple(null, argumentTypes[0], argumentTypes.subList(1, argumentTypes.size))
                else -> Triple(null, null, argumentTypes)
            }
            TypeParameterContext.invoke(boxedFunction, valueArgumentTypes, dispatchReceiverArgumentType, extensionReceiverArgumentType, null, pluginContext.irBuiltIns)
        }
        val unboxMap = when (typeContext) {
            is TypeParameterContext.Failure -> throw AutoDiffException(typeContext.errorMessage)
            is TypeParameterContext.Success -> {
                when {
                    boxedFunctionAllParameters.zip(argumentTypes).all { (parameterWithIndex, argumentType) ->
                        argumentType.isSubtypeOf(
                            superType = typeContext.buildType(parameterWithIndex.valueDescriptor.type as? IrSimpleTypeImpl ?: throw AutoDiffException("cannot build types of non simple types. Found: ${parameterWithIndex.valueDescriptor.type.render()}")),
                            typeSystem = IrTypeSystemContextImpl(pluginContext.irBuiltIns)
                        )
                    } && typeContext.buildType(boxedFunction.returnType as IrSimpleTypeImpl) == expectedReturnType -> IdentityMap()
                    else -> {
                        val annotationConstructorCall = boxedFunction.getAnnotation(toUnboxFunctionFqName)
                        val unboxFqName = when {
                            annotationConstructorCall != null -> ((annotationConstructorCall.getValueArgument(0) as? IrConstImpl<*>)?.value as? String)?.let { FqName(it) } ?: throw AutoDiffException("Expected the unbox annotation to have a single String constant argument")
                            else -> boxedFunction.kotlinFqName
                        }
                        val unboxedMatch = findMatchFromFqName(unboxFqName)
                        val notFoundMessage by lazy { "no unbox candidates were found for `${boxedFunction.kotlinFqName}(${boxedFunctionAllParameters.map{"${it.valueDescriptor.type.render()} at ${it.index}"}.joinToString(",")})`. The new arguments were ${argumentTypes.map{it.render()}.joinToString(",")} and expected return ${expectedReturnType.render()}" }
                        when {
                            unboxedMatch != null -> unboxedMatchForCandidate(unboxedMatch)
                            argumentTypes.all { it == boxedPrimitiveInfo.primitiveType } -> {
                                findMatchFromFqName(FqName("${boxedPrimitiveInfo.primitiveType.classFqName!!}.${unboxFqName.shortName()}"))?.let {
                                    primitiveMatch ->
                                    unboxedMatchForCandidate(primitiveMatch)
                                } ?: throw AutoDiffException(notFoundMessage)
                            }
                            else -> throw AutoDiffException(notFoundMessage)
                        }
                    }
                }
            }
        }
        boxedToUnboxMap[boxedFunction]!![argumentTypes] = unboxMap
        return unboxMap
    }
}

fun DifferentiableApi.isConstantScalar(type: IrSimpleType): Boolean = type.isSubtypeOfType(boxedPrimitiveInfo.boxedPrimitiveClass.defaultType)
fun DifferentiableApi.isDifferentiableScalar(type: IrType): Boolean = type.classOrNull == scalarRoot.symbol || type.isSubtypeOfClass(reverseDiffScalarClass.clazz.symbol)
fun DifferentiableApi.isBackproppable(type: IrType) = type.isSubtypeOfClass(reverseDiffScalarClass.clazz.symbol)
fun DifferentiableApi.isDerivativeID(type: IrType) = type.isSubtypeOfClass(reverseDiffScalarClass.derivativeId.getter!!.overrideRoot().owner.returnType.classifierOrFail as IrClassSymbol)
fun IrSimpleFunction.isPushbackMethod(diffApi: DifferentiableApi) = this.overrideRoot() == diffApi.reverseDiffScalarClass.pushbackMethod.overrideRoot()
fun IrSimpleFunction.isPrimalGetter(diffApi: DifferentiableApi) = this.overrideRoot() == diffApi.reverseDiffScalarClass.primalProperty.getter!!.overrideRoot()

// unbox keeps the same arguments and finds a new function
fun DifferentiableApi.lowerFunction(original: IrCall, candidateCopy: IrCall, expectedReturnType: IrType): IrCall {
    return when (val unboxMap: UnboxMap = unboxMap(original.symbol.owner, candidateCopy.allArgumentSimpleTypes(), expectedReturnType)) {
        is OverloadMap -> {
            assert(unboxMap.targetFunction.typeParameters.size == 0)
            IrCallImpl(candidateCopy.startOffset, candidateCopy.endOffset, unboxMap.targetFunction.returnType, unboxMap.targetFunction.symbol as IrSimpleFunctionSymbol, unboxMap.targetFunction.typeParameters.size, unboxMap.targetFunction.valueParameters.size).also {
                unboxMap.parameterMap.forEach { (indexOfSrc, indexOfTarget) ->
                    it.putArgument(indexOfTarget, candidateCopy.argumentExpression(indexOfSrc)!!)
                }
            }
        }
        is IdentityMap -> candidateCopy
    }
}

fun DifferentiableApi.zero(): IrCall {
    return IrCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        rootDifferentiableType,
        boxedPrimitiveInfo.scalarZeroObjectProperty.getter!!.symbol, 0, 0
    )
        .also {
            it.dispatchReceiver = IrGetObjectValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, boxedPrimitiveInfo.boxedPrimitiveClass.companionObject()!!.defaultType, boxedPrimitiveInfo.boxedPrimitiveClass.companionObject()!!.symbol)
        }
}

fun DifferentiableApi.scalarZero(): IrCall = IrCallImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    scalarRoot.defaultType,
    boxedPrimitiveInfo.scalarZeroObjectProperty.getter!!.symbol, 0, 0
)
    .also {
        it.dispatchReceiver = IrGetObjectValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, boxedPrimitiveInfo.boxedPrimitiveClass.companionObject()!!.defaultType, boxedPrimitiveInfo.boxedPrimitiveClass.companionObject()!!.symbol)
    }

fun DifferentiableApi.primitiveZero(): IrConst<*> = when {
    boxedPrimitiveInfo.primitiveType.isFloat() -> IrConstImpl.float(UNDEFINED_OFFSET, UNDEFINED_OFFSET, boxedPrimitiveInfo.primitiveType, 0f)
    boxedPrimitiveInfo.primitiveType.isDouble() -> IrConstImpl.double(UNDEFINED_OFFSET, UNDEFINED_OFFSET, boxedPrimitiveInfo.primitiveType, 0.0)
    else -> throw AutoDiffException("Only floats and doubles supported: ${boxedPrimitiveInfo.primitiveType.render()}")
}

fun DifferentiableApi.primitiveOne(): IrConst<*> = when {
    boxedPrimitiveInfo.primitiveType.isFloat() -> IrConstImpl.float(UNDEFINED_OFFSET, UNDEFINED_OFFSET, boxedPrimitiveInfo.primitiveType, 1f)
    boxedPrimitiveInfo.primitiveType.isDouble() -> IrConstImpl.double(UNDEFINED_OFFSET, UNDEFINED_OFFSET, boxedPrimitiveInfo.primitiveType, 1.0)
    else -> throw AutoDiffException("Only floats and doubles supported: ${boxedPrimitiveInfo.primitiveType.render()}")
}

fun DifferentiableApi.unboxDScalarExpression(dscalarExpression: IrExpression, callGenerator: IrBodyGenerator): IrExpression {
    require(dscalarExpression.type.isSubtypeOfClass(scalarRoot.symbol))
    val cast = IrTypeOperatorCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, boxedPrimitiveInfo.boxedPrimitiveClass.defaultType, IrTypeOperator.CAST, boxedPrimitiveInfo.boxedPrimitiveClass.defaultType, dscalarExpression)
    val primitiveExpression = callGenerator.generateGetProperty(boxedPrimitiveInfo.valueProperty, cast)
    return primitiveExpression
}
