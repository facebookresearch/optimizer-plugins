/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import pluginCommon.PluginCodegenException

fun <T : IrDeclaration> ReferenceSymbolTable.buildWithScope(declaration: T, fnc: (T) -> Unit) {
    this.enterScope(declaration.symbol)
    fnc(declaration)
    this.leaveScope(declaration.symbol)
}

object IrUtil {
    fun propertyType(property: IrProperty): IrType {
        return property.backingField?.type
            ?: property.getter?.returnType
            ?: throw PluginCodegenException("${property.render()} does not have a backing field nor getter")
    }
}

fun IrClass.overrides(p: IrOverridableMember): Boolean {
    fun IrSimpleFunctionSymbol.overrides(parent: IrSimpleFunctionSymbol): Boolean {
        return if (this == parent) {
            true
        } else {
            this.owner.overriddenSymbols.any { it.overrides(parent) }
        }
    }
    return when (p) {
        is IrSimpleFunction -> {
            this.simpleFunctions().any { it.overriddenSymbols.any { it.overrides(p.symbol) } }
        }
        is IrProperty -> {
            val parentSymbol: IrSimpleFunctionSymbol = p.getter?.symbol ?: throw PluginCodegenException("Property has no getter so cannot infer if its been overridden")
            this.properties.filter { it.getter != null }.any { it.getter!!.overriddenSymbols.any { it.overrides(parentSymbol) } }
        }
        else -> throw NotImplementedError("Only functions and properties are supported for override checks")
    }
}

fun IrSimpleFunction.overrides(p: IrSimpleFunction): Boolean {
    fun IrSimpleFunctionSymbol.overrides(parent: IrSimpleFunctionSymbol): Boolean {
        return if (this == parent) {
            true
        } else {
            this.owner.overriddenSymbols.any { it.overrides(parent) }
        }
    }
    return this.overriddenSymbols.any { it.overrides(p.symbol) }
}
/*
 * TypeParameterContext is in charge of taking in IrFunctions along with their calling arguments and generating
 * a map from type parameters to their type arguments.
 */
sealed class TypeParameterContext {
    data class Success(val typeParameterMap: Map<IrTypeParameterSymbol, IrType>, private val irBuiltIns: IrBuiltIns) :
        TypeParameterContext() {
        private fun check(parameter: IrValueParameter, argument: IrExpression): Boolean {
            val parameterType = parameter.type
            val argumentType = argument.type
            val substitutedType = (parameterType.substitute(typeParameterMap) as? IrSimpleType) ?: throw PluginCodegenException("Only Simple types are supported but encountered ${parameterType.render()}")
            when (parameterType.classifierOrFail) {
                is IrTypeParameterSymbol -> return substitutedType == argumentType
                is IrClassSymbol -> return argumentType.isSubtypeOf(substitutedType, IrTypeSystemContextImpl(irBuiltIns))
                else -> throw IllegalStateException("Invalid Classifier Symbol")
            }
        }

        fun verifiedTypeParameterContext(function: IrFunction, arguments: List<IrExpression>): TypeParameterContext {
            if (function.valueParameters.zip(arguments).all { check(it.first, it.second) }) {
                return this
            } else {
                return Failure("Function arguments ${arguments.map{ it.type.render()}.joinToString(", ")} not compatible under type parameter context ${this.typeParameterMap.map{ "${it.key.owner.name} : ${it.value.render()}" }.joinToString(", ")}. Expected types or subtypes of ${function.valueParameters.map{it.type.render()}.joinToString(",")}")
            }
        }
    }

    data class Failure(val errorMessage: String) : TypeParameterContext()

    companion object {
        // Assumes the type parameters come from the same parent.
        private fun typeArgumentForTypeParameter(
            typeParameter: IrTypeParameterSymbol,
            currentParameterType: IrSimpleType,
            currentArgumentType: IrSimpleType
        ): IrType? {
            return when (val classifier = currentParameterType.classifier) {
                is IrTypeParameterSymbol -> if (classifier == typeParameter) currentArgumentType else null
                is IrClassSymbol -> {
                    if (currentParameterType.arguments.size != currentArgumentType.arguments.size) return null
                    currentParameterType.arguments.zip(currentArgumentType.arguments).map { pair ->
                        val nextParameterType = pair.first.typeOrNull.safeAs<IrSimpleType>() ?: return@map null
                        val nextArgumentType = pair.second.typeOrNull.safeAs<IrSimpleType>() ?: return@map null
                        typeArgumentForTypeParameter(typeParameter, nextParameterType, nextArgumentType)
                    }.filterNotNull().firstOrNull()
                }
                else -> null
            }
        }

        // Assumes typeArguments are ordered correctly.
        operator fun invoke(clazz: IrClass, typeArguments: List<IrTypeArgument>, irBuiltins: IrBuiltIns): TypeParameterContext {
            if (clazz.typeParameters.size != typeArguments.size) {
                return Failure("Incorrect number of type arguments for class ${clazz.name}")
            }
            val typeParameterMap = clazz.typeParameters.zip(typeArguments).map { pair ->
                val typeArgumentType =
                    pair.second.typeOrNull ?: return Failure("Type argument ${pair.second.render()} does not have a type")
                Pair(pair.first.symbol, typeArgumentType)
            }.toMap()
            return Success(typeParameterMap, irBuiltins)
        }

        operator fun invoke(constructor: IrConstructor, arguments: List<IrExpression>, irBuiltins: IrBuiltIns): TypeParameterContext {
            if (constructor.valueParameters.size != arguments.size) {
                return Failure("Incorrect number of arguments passed to constructor of ${constructor.constructedClass.name}")
            }
            val paramArgumentPairs = constructor.valueParameters.zip(arguments)
            val typeParameterMap = constructor.constructedClass.typeParameters.map { typeParameter ->
                val typeArgument = paramArgumentPairs.mapNotNull { pair ->
                    val valueParameterType =
                        pair.first.type.safeAs<IrSimpleType>() ?: return Failure("Value Parameter must be a simple type")
                    val argumentType = pair.second.type.safeAs<IrSimpleType>() ?: return Failure("Argument must be a simple type")
                    typeArgumentForTypeParameter(typeParameter.symbol, valueParameterType, argumentType)
                }.firstOrNull() ?: return Failure("Cannot infer type for type parameter ${typeParameter.render()}")
                Pair(typeParameter.symbol, typeArgument)
            }.toMap()
            return Success(typeParameterMap, irBuiltins)
        }

        operator fun invoke(
            function: IrSimpleFunction,
            arguments: List<IrExpression>,
            dispatchReceiver: IrExpression?,
            extensionReceiver: IrExpression?,
            expectedType: IrSimpleType?,
            irBuiltins: IrBuiltIns
        ): TypeParameterContext {
            val argumentTypes = arguments.map { it.type as? IrSimpleType ?: return Failure("Argument ${it.render()} not a simple type") }
            val dispatchReceiverType =
                dispatchReceiver?.let { it.type as? IrSimpleType ?: return Failure("Dispatch receiver ${it.render()} not a simple type") }
            val extensionReceiverType =
                extensionReceiver?.let { it.type as? IrSimpleType ?: return Failure("Extension receiver ${it.render()} not a simple type") }
            return TypeParameterContext(function, argumentTypes, dispatchReceiverType, extensionReceiverType, expectedType, irBuiltins)
        }

        operator fun invoke(
            function: IrSimpleFunction,
            argumentTypes: List<IrSimpleType>,
            dispatchReceiverType: IrSimpleType?,
            extensionReceiverType: IrSimpleType?,
            expectedType: IrSimpleType?,
            irBuiltins: IrBuiltIns
        ): TypeParameterContext {
            val dispatchReceiverTypeMap = function.dispatchReceiverParameter?.let { receiverParameter ->
                if (dispatchReceiverType == null) {
                    return Failure("Function ${function.render()} expects a dispatch receiver, but none was provided.")
                }
                val targetReceiverType = receiverParameter.type as? IrSimpleType ?: return Failure(
                    "Dispatch Receiver Parameter ${receiverParameter.render()} is not a simple type."
                )
                val targetReceiverClass = targetReceiverType.getClass() ?: return Failure(
                    "Type Parameters not found because ${receiverParameter.render()} does not have a class value"
                )

                var argumentReceiverType: IrSimpleType = dispatchReceiverType
                var typeParameters: List<IrClassifierSymbol> = targetReceiverClass.typeParameters.map { it.symbol }.toList()
                if (argumentReceiverType.classifier != targetReceiverType.classifier) {
                    for (supertype in dispatchReceiverType.allSuperTypes()) {
                        if (targetReceiverType.classifier == argumentReceiverType.classifier) {
                            break
                        } else {
                            argumentReceiverType = supertype as IrSimpleType
                        }
                    }
                    if (argumentReceiverType.classifier != targetReceiverType.classifier) {
                        return Failure("There is a mismatch between the receiver type of the target function and the type of the provided receiver argument. Expected ${targetReceiverType.render()} but got ${dispatchReceiverType.render()}")
                    }
                    val ancestorMap = targetReceiverClass.typeParameters.map { it.symbol }.zip(argumentReceiverType.arguments.map { it.typeOrNull!! as IrSimpleType }).toMap()
                    val typeMap = (dispatchReceiverType.classifier as IrClassSymbol).owner.typeParameters.map { it.symbol }.zip(dispatchReceiverType.arguments.map { it.typeOrNull!! }).toMap()
                    argumentReceiverType = argumentReceiverType.substitute(typeMap) as IrSimpleType
                    typeParameters = typeParameters.map { ancestorMap[it]!!.classifier }
                }

                typeParameters.map { image ->
                    when (image) {
                        is IrTypeParameterSymbol -> {
                            val typeArgument = typeArgumentForTypeParameter(image, targetReceiverType, argumentReceiverType)
                                ?: return Failure("Cannot infer the type argument for class type parameter ${image.owner.render()}")
                            Pair(image, typeArgument)
                        }
                        else -> {
                            return Failure("Expected a type parameter but got ${image.owner.render()}")
                        }
                    }
                }.toMap()
            } ?: emptyMap()

            val extensionReceiverTypeMap = function.extensionReceiverParameter?.let { receiverParameter ->
                if (extensionReceiverType == null) {
                    return Failure("Function ${function.render()} expects an extension receiver, but none was provided.")
                }
                val receiverParameterType = receiverParameter.type as? IrSimpleType ?: return Failure(
                    "Extension Receiver Parameter ${receiverParameter.render()} is not a simple type."
                )
                receiverParameterType.arguments.map { typeArg ->
                    val key = typeArg.typeOrNull!!.classifierOrFail
                    if (key is IrTypeParameterSymbol) {
                        val value = typeArgumentForTypeParameter(key, receiverParameterType, extensionReceiverType) ?: return Failure("Cannot infer the type argument for class type parameter ${key.owner.render()}")
                        Pair(key, value)
                    } else {
                        null
                    }
                }.filterNotNull().toMap()
            } ?: emptyMap()

            if (function.valueParameters.size != argumentTypes.size) {
                return Failure(
                    "Function ${function.name} expecting ${function.valueParameters.size} arguments but ${argumentTypes.size} arguments provided."
                )
            }
            val paramArgumentTypePairs = function.valueParameters.zip(argumentTypes)
            val functionTypeMap = function.typeParameters.mapNotNull { typeParameter ->
                if (extensionReceiverTypeMap.containsKey(typeParameter.symbol)) {
                    null
                } else {
                    val typeArgument = paramArgumentTypePairs.map { pair ->
                        pair.first.type.safeAs<IrSimpleType>()?.let { parameterType ->
                            typeArgumentForTypeParameter(typeParameter.symbol, parameterType, pair.second)
                        }
                    }.filterNotNull().firstOrNull() ?: function.returnType.safeAs<IrSimpleType>()?.let { returnType ->
                        expectedType?.let {
                            typeArgumentForTypeParameter(typeParameter.symbol, returnType, it)
                                ?: return Failure("Cannot infer the type for function type parameter ${typeParameter.render()}")
                        } ?: return Failure(
                            "Ambiguous call request: Cannot infer the value for type parameter ${typeParameter.render()}"
                        )
                    }
                        ?: return Failure("Expected function return type ${function.render()} to be a simple type")
                    Pair(typeParameter.symbol, typeArgument)
                }
            }.toMap()
            return Success(dispatchReceiverTypeMap + extensionReceiverTypeMap + functionTypeMap, irBuiltins)
        }
    }
}

fun IrType.allSuperTypes(): List<IrType> {
    val allSuperTypes = mutableSetOf<IrType>()
    allSuperTypes.add(this)
    for (s in this.superTypes()) {
        if (!allSuperTypes.contains(s)) {
            val theirSuperTypes = s.allSuperTypes()
            allSuperTypes.addAll(theirSuperTypes)
        }
    }
    return allSuperTypes.toList()
}

fun bindTypes(template: IrSimpleType, typeContext: Map<IrTypeParameterSymbol, IrType>): IrSimpleType {
    val tpe = when (val classifier = template.classifierOrFail) {
        is IrTypeParameterSymbol -> (typeContext[classifier] as? IrSimpleType) ?: throw PluginCodegenException("Expected a simple type but either not found or not simple type: ${typeContext[classifier]?.render()}")
        is IrClassSymbol -> {
            val tpeArgs: List<IrTypeArgument> = template.arguments.map { bindTypes((it.typeOrNull as? IrSimpleType) ?: throw PluginCodegenException("Unexpected null type: ${it.render()}"), typeContext) as IrSimpleTypeImpl }
            IrSimpleTypeImpl(classifier, false, tpeArgs, emptyList(), null)
        }
        else -> throw PluginCodegenException("Expected either a type parameter type or class")
    }
    return tpe
}

fun IrSimpleFunction.overrideRoot(): IrFunctionSymbol {
    val overrides = this.overriddenSymbols.firstOrNull()
    return if (overrides != null) {
        (overrides.owner).overrideRoot()
    } else {
        this.symbol
    }
}

fun IrConst<*>.copy() = when (val value = this.value) {
    is Double -> IrConstImpl.double(this.startOffset, this.endOffset, this.type, value)
    is Float -> IrConstImpl.float(this.startOffset, this.endOffset, this.type, value)
    is Int -> IrConstImpl.int(this.startOffset, this.endOffset, this.type, value)
    is String -> IrConstImpl.string(this.startOffset, this.endOffset, this.type, value)
    is Short -> IrConstImpl.short(this.startOffset, this.endOffset, this.type, value)
    is Long -> IrConstImpl.long(this.startOffset, this.endOffset, this.type, value)
    is Boolean -> IrConstImpl.boolean(this.startOffset, this.endOffset, this.type, value)
    else -> {
        if (value == null) {
            IrConstImpl.constNull(this.startOffset, this.endOffset, this.type)
        } else {
            throw PluginCodegenException("Unexpected primitive type encountered: ${this.render()}")
        }
    }
}

fun IrSimpleFunction.allParameters(): List<IrValueParameter> {
    val params = mutableListOf<IrValueParameter>()
    this.extensionReceiverParameter?.let { params.add(it) }
    this.dispatchReceiverParameter?.let { params.add(it) }
    this.valueParameters.forEach { params.add(it) }
    return params
}
