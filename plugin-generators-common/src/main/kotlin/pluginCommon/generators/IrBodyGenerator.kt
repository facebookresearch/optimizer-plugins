/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name
import pluginCommon.PluginCodegenException

class IrBodyGenerator(val pluginContext: IrPluginContext) {
    class Branch<T : IrExpression>(val testExpression: T, val result: List<IrStatement>)
    fun <T : IrExpression> whenStatementWithElse(branches: List<Branch<T>>, elseStatements: List<IrStatement>, type: IrType): IrWhen {
        val irBranches = branches.map {
            IrBranchImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                it.testExpression,
                result = IrBlockImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    type,
                    null,
                    it.result
                )
            )
        }

        val otherCondition = IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.booleanType, true)
        val otherResult = IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, null, elseStatements)
        val otherBranch = IrBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, otherCondition, otherResult)
        return IrWhenImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, null).also { it.branches.addAll(irBranches + listOf(otherBranch)) }
    }

    fun throwException(message: String): IrThrow {
        val constructor = pluginContext.irBuiltIns.throwableClass.owner.constructors.firstOrNull {
            it.valueParameters.size == 1 && it.valueParameters.first().type.classOrNull == pluginContext.irBuiltIns.stringClass
        } ?: throw PluginCodegenException("Could not find a constructor for throwable that accepts a string")
        val constructorCall = IrConstructorCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.throwableClass.owner.defaultType, constructor.symbol, 0, 0, 1, null)
        constructorCall.putValueArgument(0, IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.stringType, message))
        return IrThrowImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            pluginContext.irBuiltIns.nothingType,
            constructorCall
        )
    }

    fun generateVariable(name: Name, containingDeclaration: IrDeclarationParent, initializer: IrExpression): IrVariable =
        generateIrVariable(name, containingDeclaration, initializer, true)

    fun generateVal(name: Name, containingDeclaration: IrDeclarationParent, initializer: IrExpression): IrVariable =
        generateIrVariable(name, containingDeclaration, initializer, false)

    fun generateIrVariable(
        name: Name,
        containingDeclaration: IrDeclarationParent,
        initializer: IrExpression,
        isVar: Boolean
    ): IrVariable {
        val variable = IrVariableImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            IrVariableSymbolImpl(),
            name,
            initializer.type,
            isVar, false, false
        ).also { it.initializer = initializer }
        variable.parent = containingDeclaration
        return variable
    }

    fun generateIrVariable(
        name: Name,
        containingDeclaration: IrDeclarationParent,
        type: IrType,
        isVar: Boolean = false
    ): IrVariable {
        val variable = IrVariableImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            IrVariableSymbolImpl(),
            name,
            type,
            isVar, false, false
        )
        variable.parent = containingDeclaration
        return variable
    }

    fun generateIrParameter(
        name: Name,
        index: Int,
        type: IrType
    ): IrValueParameter {
        return IrValueParameterImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, IrDeclarationOrigin.DEFINED, IrValueParameterSymbolImpl(), name, index, type, null, false, false, false, false)
    }

    fun generateCast(expressionToCast: IrExpression, newType: IrType) =
        IrTypeOperatorCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, newType, IrTypeOperator.CAST, newType, expressionToCast)

    fun generateGetValue(value: IrValueDeclaration): IrGetValue {
        return IrGetValueImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            value.type,
            value.symbol,
            null
        )
    }

    fun generateConstructorCall(
        irClass: IrClass,
        arguments: List<IrExpression>,
        typeArguments: List<IrTypeArgument>?
    ): IrConstructorCall {
        val constructorInfo = constructorInfo(irClass, arguments, typeArguments)

        val constructorCall = IrConstructorCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            constructorInfo.instanceType,
            constructorInfo.constructor.symbol,
            typeArgumentsCount = constructorInfo.constructor.typeParameters.size + irClass.typeParameters.size,
            constructorTypeArgumentsCount = constructorInfo.constructor.typeParameters.size,
            valueArgumentsCount = constructorInfo.constructor.valueParameters.size
        )

        arguments.forEachIndexed { index, irExpression ->
            constructorCall.putValueArgument(index, irExpression)
        }
        constructorInfo.typeParameters.forEachIndexed { index, irType ->
            constructorCall.putTypeArgument(index, irType)
        }
        return constructorCall
    }

    fun generateGetProperty(dispatchReceiver: IrExpression, propertyName: Name): IrCall {
        return dispatchReceiver.type.getClass()?.let { clazz ->
            val property = clazz.getPropertyDeclaration(propertyName)
                ?: throw PluginCodegenException("${clazz.name} does not have a property '$propertyName'")
            val typeMap = clazz.typeParameters.zip((dispatchReceiver.type as IrSimpleType).arguments)
                .map { Pair(it.first.symbol, it.second.typeOrNull ?: throw IllegalStateException("Cannot infer type of argument")) }.toMap()
            val type = IrUtil.propertyType(property).substitute(typeMap)

            val getter = property.getter as IrSimpleFunction
            IrCallImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                type = type,
                symbol = getter.symbol,
                typeArgumentsCount = 0,
                valueArgumentsCount = 0,
                origin = IrStatementOrigin.GET_PROPERTY
            ).also {
                it.dispatchReceiver = dispatchReceiver
            }
        } ?: throw PluginCodegenException("The dispatch receiver provided is not of class type. Found ${dispatchReceiver.type}")
    }

    fun generateGetProperty(property: IrProperty, dispatchReceiver: IrExpression): IrCall {
        return dispatchReceiver.type.getClass()?.let { clazz ->
            val typeMap = clazz.typeParameters.zip((dispatchReceiver.type as IrSimpleType).arguments)
                .map { Pair(it.first.symbol, it.second.typeOrNull ?: throw IllegalStateException("Cannot infer type of argument")) }.toMap()
            val type = IrUtil.propertyType(property).substitute(typeMap)

            val getter = property.getter as IrSimpleFunction
            IrCallImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                type = type,
                symbol = getter.symbol,
                typeArgumentsCount = 0,
                valueArgumentsCount = 0,
                origin = IrStatementOrigin.GET_PROPERTY
            ).also {
                it.dispatchReceiver = dispatchReceiver
            }
        } ?: throw PluginCodegenException("The dispatch receiver provided is not of class type. Found ${dispatchReceiver.type}")
    }

    class ConstructorInfo(val instanceType: IrType, val constructor: IrConstructor, val typeParameters: List<IrType>)
    private fun constructorInfo(irClass: IrClass, arguments: List<IrExpression>, typeArguments: List<IrTypeArgument>?): ConstructorInfo {
        val matches = irClass.constructors.map { constructor ->
            val unverifiedContext = typeArguments?.let { TypeParameterContext(irClass, it, pluginContext.irBuiltIns) } ?: TypeParameterContext(
                constructor,
                arguments,
                pluginContext.irBuiltIns
            )
            val verifiedContext = when (unverifiedContext) {
                is TypeParameterContext.Success -> unverifiedContext.verifiedTypeParameterContext(constructor, arguments)
                else -> unverifiedContext
            }
            Pair(constructor, verifiedContext)
        }
        val successfulMatches = matches.filter { it.second is TypeParameterContext.Success }.toList()

        if (successfulMatches.size != 1) {
            if (matches.count() == 0) throw PluginCodegenException("No constructor for class ${irClass.name} found.")
            val unsuccessfulMatches = matches.map { it.second }.filterIsInstance<TypeParameterContext.Failure>()
            val errorMessage = unsuccessfulMatches.joinToString(separator = ",\n") { it.errorMessage }.let {
                if (it.isEmpty()) "" else "\nPossible Errors:\n$it}"
            }
            throw PluginCodegenException(
                "There does not exist a unique constructor for class ${irClass.kotlinFqName} that accepts the arguments provided.$errorMessage"
            )
        }

        val (constructorFunction, verifiedContext) = successfulMatches.first()
        val typeParameterMap = (verifiedContext as TypeParameterContext.Success).typeParameterMap

        val typeParameters: List<IrType> = constructorFunction.parentAsClass.typeParameters.map {
            typeParameterMap[it.symbol]
                ?: throw PluginCodegenException("Internal error occurred: an unbound constructor parameter was encountered: ${it.name}")
        }
        val instanceType =
            if (irClass.typeParameters.isNotEmpty()) irClass.typeWith(typeParameters)
            else irClass.defaultType
        return ConstructorInfo(instanceType, constructorFunction, typeParameters)
    }

    fun generateDelegatingConstructorCall(
        irClass: IrClass,
        arguments: List<IrExpression>,
        typeArguments: List<IrTypeArgument>?
    ): IrDelegatingConstructorCall {
        val constructorInfo = constructorInfo(irClass, arguments, typeArguments)
        val constructorCall = IrDelegatingConstructorCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            constructorInfo.instanceType,
            constructorInfo.constructor.symbol,
            constructorInfo.constructor.typeParameters.size,
            constructorInfo.constructor.valueParameters.size
        )

        arguments.forEachIndexed { index, irExpression ->
            constructorCall.putValueArgument(index, irExpression)
        }
        constructorInfo.typeParameters.forEachIndexed { index, irType ->
            constructorCall.putTypeArgument(index, irType)
        }
        return constructorCall
    }

    fun generateSetField(field: IrField, value: IrExpression, clazz: IrClass): IrSetField {
        val receiver = clazz.thisReceiver?.let { generateGetValue(it) }
        return IrSetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, field.symbol, receiver, value, pluginContext.irBuiltIns.unitType, null)
    }

    fun generateSetVariable(variable: IrVariable, value: IrExpression): IrSetValue {
        return IrSetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.unitType, variable.symbol, value, null)
    }

    fun generateBlockBody(statements: List<IrStatement>, returnExpression: IrExpression, function: IrFunction): IrBlockBody {
        val ret = IrReturnImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.nothingType, function.symbol, returnExpression)
        return pluginContext.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, statements + listOf(ret))
    }

    fun generateBlockBody(statements: List<IrStatement>): IrBlockBody {
        return pluginContext.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, statements)
    }

    // TODO: handle generic functions
    fun generateCall(toFunction: IrSimpleFunction, withArguments: List<IrExpression>, dispatchReciever: IrExpression?, extensionReceiver: IrExpression? = null): IrCall {
        return IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, toFunction.returnType, toFunction.symbol, toFunction.typeParameters.size, toFunction.valueParameters.size).also { call ->
            withArguments.forEachIndexed { i, arg ->
                call.putValueArgument(i, arg)
            }
            dispatchReciever?.let { call.dispatchReceiver = dispatchReciever }
            extensionReceiver?.let { call.extensionReceiver = extensionReceiver }
        }
    }

    private fun IrClass.getPropertyDeclaration(name: Name): IrProperty? {
        val properties = declarations.filterIsInstance<IrProperty>().filter { it.name == name }
        if (properties.size > 1) {
            error(
                "More than one property with name $name in class $fqNameWhenAvailable:\n" +
                    properties.joinToString("\n", transform = IrProperty::render)
            )
        }
        val prop = properties.firstOrNull()
        return if (prop == null) {
            val superTypeMatches = superTypes.map { it.classifierOrFail.owner as IrClass }.mapNotNull { it.getPropertyDeclaration(name) }
            if (superTypeMatches.size > 1) {
                error(
                    "More than one property with name $name in class $fqNameWhenAvailable:\n" +
                        superTypeMatches.joinToString("\n", transform = IrProperty::render)
                )
            } else {
                superTypeMatches.firstOrNull()
            }
        } else prop
    }
}
