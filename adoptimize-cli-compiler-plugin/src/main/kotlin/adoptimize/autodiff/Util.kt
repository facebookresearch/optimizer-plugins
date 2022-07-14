/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff

import adoptimize.AutoDiffException
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContextImpl
import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.descriptors.resolveClassByFqName
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.utils.OperatorNames
import org.jetbrains.kotlin.ir.backend.jvm.serialization.JvmIrLinker
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.KotlinType
import pluginCommon.generators.TypeParameterContext
import pluginCommon.generators.allParameters

fun getVariable(exp: IrExpression) = when (exp) {
    is IrGetValue -> exp.symbol.owner
    else -> {
        throw AutoDiffException("Expected ANF form: ${exp.render()}")
    }
}

fun IrCall.allArguments(): List<IrValueDeclaration> {
    val args = mutableListOf<IrValueDeclaration>()
    this.extensionReceiver?.let { args.add(getVariable(it)) }
    this.dispatchReceiver?.let { args.add(getVariable(it)) }
    (0 until this.valueArgumentsCount).forEach { args.add(getVariable(this.getValueArgument(it)!!)) }
    return args
}

fun IrCall.allArgumentTypes(): List<IrType> {
    val args = mutableListOf<IrType>()
    this.extensionReceiver?.let { args.add(it.type) }
    this.dispatchReceiver?.let { args.add(it.type) }
    (0 until this.valueArgumentsCount).forEach { args.add(this.getValueArgument(it)!!.type) }
    return args
}

fun IrCall.allArgumentSimpleTypes(): List<IrSimpleType> {
    val args = mutableListOf<IrSimpleType>()
    this.extensionReceiver?.let { args.add(it.type as? IrSimpleType ?: throw AutoDiffException("Expected a simple type but got ${it.type.render()}")) }
    this.dispatchReceiver?.let { args.add(it.type as? IrSimpleType ?: throw AutoDiffException("Expected a simple type but got ${it.type.render()}")) }
    (0 until this.valueArgumentsCount).forEach { args.add(this.getValueArgument(it)!!.type as? IrSimpleType ?: throw AutoDiffException("Expected a simple type but got ${this.getValueArgument(it)!!.type.render()}")) }
    return args
}

fun IrSimpleFunction.allParameterTypes() = this.allParameters().map { it.type as? IrSimpleType ?: throw AutoDiffException("Cannot cast to IrSimpleType, which is an error since ") }

data class ParameterWithIndex(val valueDescriptor: IrValueParameter, val index: Int) {
    override fun toString(): String = "${valueDescriptor.name} of ${valueDescriptor.parent.kotlinFqName} : ${valueDescriptor.index}"
}

fun TypeParameterContext.Success.buildType(parameterType: IrSimpleTypeImpl): IrSimpleTypeImpl {
    return when (val classifierSymbol = parameterType.classifier) {
        is IrTypeParameterSymbol -> this.typeParameterMap[classifierSymbol]?.let { it as? IrSimpleTypeImpl } ?: parameterType
        is IrClassSymbol -> {
            val argumentForType: Map<IrTypeParameter, IrTypeArgument> = classifierSymbol.owner.typeParameters.zip(parameterType.arguments).toMap()
            IrSimpleTypeImpl(classifierSymbol, false, classifierSymbol.owner.typeParameters.map { buildType(argumentForType[it]!!.typeOrNull!! as IrSimpleTypeImpl) }, emptyList(), null)
        }
        else -> throw NotImplementedError("${classifierSymbol.javaClass.name} not handled in the unboxing code")
    }
}

fun IrSimpleType.isSubtypeOfType(maybeParentType: IrSimpleType): Boolean {
    if (this == maybeParentType) return true
    val stack = java.util.Stack<IrSimpleType>()
    stack.push(this)
    while (stack.isNotEmpty()) {
        val top = stack.pop()
        for (supertype in top.superTypes().map { it as IrSimpleType }) {
            if (supertype == maybeParentType) {
                return true
            }
            if (!supertype.isAny()) {
                stack.push(supertype)
            }
        }
    }
    return false
}

fun IrSimpleType.intersect(that: IrSimpleType): Set<IrSimpleType> {
    fun allSupertypes(tpe: IrSimpleType): Set<IrSimpleType> {
        val alltypes = mutableSetOf<IrSimpleType>(tpe)
        val stack = java.util.Stack<IrSimpleType>()
        stack.push(tpe)
        while (stack.isNotEmpty()) {
            val top = stack.pop()
            for (supertype in top.superTypes().map { it as IrSimpleType }) {
                if (!supertype.isAny()) {
                    alltypes.add(supertype)
                    stack.push(supertype)
                }
            }
        }
        return alltypes
    }
    return allSupertypes(this).intersect(allSupertypes(that))
}

fun IrFunction.allParametersWithIndex(): List<ParameterWithIndex> {
    val parameters = mutableListOf<ParameterWithIndex>()
    if (this.extensionReceiverParameter != null) {
        parameters.add(ParameterWithIndex(this.extensionReceiverParameter!!, -2))
    }
    if (this.dispatchReceiverParameter != null) {
        parameters.add(ParameterWithIndex(this.dispatchReceiverParameter!!, -1))
    }
    parameters.addAll(this.valueParameters.map { ParameterWithIndex(it, it.index) })
    return parameters
}

fun IrCall.argumentExpression(index: Int): IrExpression? {
    return when {
        index == -1 -> this.dispatchReceiver
        index == -2 -> this.extensionReceiver
        else -> this.getValueArgument(index)
    }
}

fun IrCall.putArgument(index: Int, value: IrExpression) {
    when {
        index == -1 -> this.dispatchReceiver = value
        index == -2 -> this.extensionReceiver = value
        else -> this.putValueArgument(index, value)
    }
}

fun IrCall.allArgumentSymbols(): List<IrSymbol> {
    fun getSymbolOf(expression: IrExpression): IrSymbol = when (expression) {
        is IrGetValue -> expression.symbol
        is IrGetObjectValue -> expression.symbol
        else -> {
            throw AutoDiffException("Cannot find the symbol of the expression ${expression.render()} because it is not a Get")
        }
    }
    val args = mutableListOf<IrSymbol>()
    this.extensionReceiver?.let { args.add(getSymbolOf(it)) }
    this.dispatchReceiver?.let { args.add(getSymbolOf(it)) }
    (0 until this.valueArgumentsCount).forEach { args.add(getSymbolOf(this.getValueArgument(it)!!)) }
    return args
}

fun IrExpression.isEquivalent(other: IrExpression): Boolean {
    return when (this) {
        is IrCall -> if (other is IrCall) {
            other.symbol == this.symbol && !(other.allArgumentSymbols().zip(this.allArgumentSymbols()).any { it.first != it.second })
        } else false
        is IrGetValue -> other is IrGetValue && other.symbol == this.symbol
        is IrTypeOperatorCall -> if (other is IrTypeOperatorCall) {
            val attributesEqual = other.operator == this.operator &&
                other.type == this.type &&
                other.typeOperand == this.typeOperand
            attributesEqual && this.argument.isEquivalent(other.argument)
        } else false
        is IrConst<*> -> other is IrConst<*> && other.value == this.value
        is IrGetObjectValue -> other is IrGetObjectValue && other.symbol == this.symbol
        is IrConstructorCall -> if (other is IrConstructorCall) {
            if (other.symbol != this.symbol) {
                false
            } else {
                (0 until this.valueArgumentsCount).all {
                    val thisArg = this.getValueArgument(it)!!
                    val thatArg = other.getValueArgument(it)!!
                    when (thisArg) {
                        is IrGetValue -> if (thatArg is IrGetValue) {
                            thatArg.symbol == thisArg.symbol
                        } else false
                        is IrConst<*> -> if (thatArg is IrConst<*>) {
                            thisArg.value == thatArg.value
                        } else false
                        else -> { throw NotImplementedError() }
                    }
                }
            }
        } else false
        else -> {
            throw AutoDiffException("Expected expressions to not contain nested expressions but received: ${this.render()}")
        }
    }
}

fun IrProperty.type(): IrType = (this.backingField?.type ?: this.getter?.returnType) ?: throw AutoDiffException("Could not infere rproperty type")

// https://github.com/facebookincubator/differentiable/issues/1812
fun IrPluginContext.linker(): JvmIrLinker {
    val contextImpl = this as? IrPluginContextImpl ?: throw AutoDiffException("AdOptimize depends on the IrPluginContext being an IrPluginContextImpl. Either add the linker to the interface or implement your own IrProvider for stub implementation")
    val linker = contextImpl.linker as? JvmIrLinker ?: throw AutoDiffException("AdOptimize depends on the IrPluginContext being an IrPluginContextImpl. Either add the linker to the interface or implement your own IrProvider for stub implementation")
    return linker
}

fun IrPluginContext.irClassForFqName(fqName: FqName): IrClass {
    val clazzDescriptor = this.moduleDescriptor.resolveClassByFqName(fqName, NoLookupLocation.FROM_BACKEND) ?: throw AutoDiffException("Could not resolve the class $fqName in ${moduleDescriptor.stableName}")
    val clazzSymbol = this.symbolTable.referenceClass(clazzDescriptor)
    val irDeclaration = if (clazzSymbol.isBound) {
        clazzSymbol.owner
    } else linker().getDeclaration(clazzSymbol) ?: throw AutoDiffException("Could not generate a stub for ${clazzSymbol.owner.name}")
    return irDeclaration as? IrClass ?: throw AutoDiffException("Expected a class stub for class symbol ${clazzSymbol.owner.name}")
}

fun IrFunction.dependencyNodeMaybe(toReverseAnnotation: FqName, context: IrPluginContext): IrClass? {
    return this.getAnnotation(toReverseAnnotation)?.let { annotation ->
        when (val argument = annotation.getValueArgument(0)) {
            is IrConst<*> -> argument.value as? String
            else -> null
        }?.let {
            context.irClassForFqName(FqName(it))
        }
    }
}

class VariableToPropertyMatcher(val targetClass: IrClass) {
    fun propertiesToVariable(function: IrFunction): Map<IrValueDeclaration, IrProperty> {
        val properties = mutableMapOf<IrValueDeclaration, IrProperty>()
        val variableToParameter = mutableMapOf<IrValueDeclaration, IrValueDeclaration>()
        function.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                when (val expression = declaration.initializer) {
                    is IrCall -> {
                        if (expression.symbol.owner.isGetter && expression.symbol.owner.parent == targetClass) {
                            expression.symbol.owner.correspondingPropertySymbol?.owner?.let { property ->
                                if (properties.containsKey(declaration)) {
                                    throw AutoDiffException("Expected a single assignent for property ${property.name}")
                                } else {
                                    properties[declaration] = property
                                }
                            }
                        }
                    }
                }
                super.visitVariable(declaration)
            }

            override fun visitCall(expression: IrCall) {
                val targetFunc = expression.symbol.owner
                // TODO: better identification of let
                if (targetFunc.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB && targetFunc.name.toString() == "let") {
                    val lambda = expression.getValueArgument(0) as? IrFunctionExpression ?: throw IllegalStateException("Expected a let function top accept a function expression")
                    expression.extensionReceiver?.let {
                        val variable = getVariable(it)
                        variableToParameter[variable] = lambda.function.valueParameters[0]
                    }
                }
                super.visitExpression(expression)
            }
        })
        variableToParameter.forEach {
            if (properties.containsKey(it.key)) {
                properties[it.value] = properties[it.key]!!
            }
        }
        return properties
    }
}

fun KotlinType.isEqualTo(simpleType: KotlinType): Boolean {
    return simpleType.constructor.declarationDescriptor == this.constructor.declarationDescriptor
}

fun correctSpecializedNames(name: String): String {
    return "$${name.trim('<').trimEnd('>')}"
}

fun IrType.zero(): IrExpression {
    return when {
        this.isFloat() -> IrConstImpl.float(UNDEFINED_OFFSET, UNDEFINED_OFFSET, this, 0f)
        this.isDouble() -> IrConstImpl.double(UNDEFINED_OFFSET, UNDEFINED_OFFSET, this, 0.0)
        else -> throw AutoDiffException("TODO: support arbitrary types")
    }
}

fun plusFunction(rhs: IrType, lhs: IrType): IrSimpleFunction {
    return when {
        rhs.isFloat() && lhs.isFloat() -> (rhs.classifierOrFail.owner as IrClass).simpleFunctions().first { it.name == OperatorNames.ADD && it.allParameters().all { it.type.isFloat() } }
        rhs.isDouble() && lhs.isDouble() -> (rhs.classifierOrFail.owner as IrClass).simpleFunctions().first { it.name == OperatorNames.ADD && it.allParameters().all { it.type.isFloat() } }
        else -> throw AutoDiffException("TODO: support arbitrary types")
    }
}
