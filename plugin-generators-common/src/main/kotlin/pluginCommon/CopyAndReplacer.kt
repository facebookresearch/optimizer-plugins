/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon

import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrAnonymousInitializerImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.impl.IrErrorTypeImpl
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.isSetter
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.types.Variance
import pluginCommon.generators.TypeParameterContext
import pluginCommon.generators.bindTypes
import pluginCommon.generators.copy

interface ReplaceDelegate {
    fun replaceCandidateWith(original: IrSetField, candidateCopy: IrSetField): IrSetField? = null
    fun replaceCandidateWith(original: IrSetValue, candidateCopy: IrSetValue): IrSetValue? = null
    fun replaceCandidateWith(original: IrVariable, candidateCopy: IrVariable): IrVariable? = null
    fun replaceCandidateWith(original: IrCall, candidateCopy: IrCall): IrExpression? = null
    fun replaceCandidateWith(original: IrTypeOperatorCall, candidateCopy: IrTypeOperatorCall): IrExpression? = null
    fun replaceConstructorCall(original: IrConstructorCall, candidateCopy: IrConstructorCall): IrExpression? = null
    fun replaceReturnStatement(original: IrReturn, candidateCopy: IrReturn): IrExpression? = null
    fun replaceGetObject(original: IrGetObjectValue, candidateCopy: IrGetObjectValue): IrExpression? = null
    fun replaceValueParameterWith(original: IrValueParameter, candidateCopy: IrValueParameter): IrValueParameter? = null
    fun replaceUntrackedGetValue(original: IrGetValue): IrExpression? = null
    fun replaceGetField(original: IrGetField, candidateCopy: IrGetField): IrGetField? = null
    companion object {
        val emptyReplacer = object : ReplaceDelegate {}
    }
}

class CopyAndReplacer(
    val valueParameterSubsitutor: Substitutor<IrValueDeclaration, IrValueDeclaration>,
    val propertySubsitution: Substitutor<IrProperty, IrProperty>,
    val irBuiltIns: IrBuiltIns
) {
    fun copyAndReplace(original: IrElement, delegate: ReplaceDelegate, newParent: IrDeclarationParent): IrElement {
        val s = ArrayDeque<IrElement>()
        original.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                s.push(element)
                element.acceptChildrenVoid(this)
            }

            override fun visitBlock(expression: IrBlock) {
                s.push(expression)
                expression.statements.reversed().forEach { it.acceptVoid(this) }
            }

            override fun visitBlockBody(body: IrBlockBody) {
                s.push(body)
                body.statements.reversed().forEach { it.acceptVoid(this) }
            }

            override fun visitFunction(declaration: IrFunction) {
                s.push(declaration)
                declaration.body?.acceptVoid(this)
                declaration.valueParameters.forEach { it.acceptVoid(this) }
                declaration.dispatchReceiverParameter?.acceptVoid(this)
                declaration.extensionReceiverParameter?.acceptVoid(this)
                declaration.typeParameters.forEach { it.acceptVoid(this) }
            }
        })
        val copies = ArrayDeque<IrElement>()
        while (!s.isEmpty()) {
            val current = s.pop()
            val copy: IrElement =
                when (current) {
                    is IrVariable -> {
                        val expression = if (current.initializer != null) copies.pop() as IrExpression else null
                        val variableCandidate = IrVariableImpl(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            IrDeclarationOrigin.DEFINED,
                            IrVariableSymbolImpl(),
                            current.name,
                            expression?.type ?: current.type,
                            current.isVar, false, false
                        ).also {
                            it.initializer = expression
                            it.parent = newParent
                        }
                        val variable = delegate.replaceCandidateWith(current, variableCandidate) ?: variableCandidate
                        valueParameterSubsitutor[current] = variable
                        variable
                    }
                    is IrSetField -> {
                        val receiver = copies.pop() as IrExpression
                        val expression = copies.pop() as IrExpression
                        val originalProperty = current.symbol.owner.correspondingPropertySymbol!!.owner
                        val fieldSymbol = propertySubsitution[originalProperty]?.let {
                            it.backingField?.symbol
                        } ?: current.symbol
                        val candidateSetField = IrSetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, fieldSymbol, current.type, null).also { it.value = expression; it.receiver = receiver }
                        val setField = delegate.replaceCandidateWith(current, candidateSetField) ?: candidateSetField
                        setField
                    }
                    is IrConstructorCall -> {
                        val originalConstructor = current.symbol.owner
                        val args = (0 until current.valueArgumentsCount).map { copies.pop() as IrExpression }
                        val callType = when (val result = TypeParameterContext.invoke(originalConstructor, args, irBuiltIns)) {
                            is TypeParameterContext.Success -> {
                                val tpeParameterMap = result.typeParameterMap
                                bindTypes(originalConstructor.returnType as? IrSimpleType ?: throw PluginCodegenException("Expected a simple type: ${originalConstructor.returnType.render()}"), tpeParameterMap)
                            }
                            is TypeParameterContext.Failure -> {
                                val result2 = TypeParameterContext(originalConstructor.parentAsClass, (0 until current.typeArgumentsCount).map { current.getTypeArgument(it)!! as? IrSimpleTypeImpl ?: throw PluginCodegenException("Only simple type arguments supported: ${current.render()}") }, irBuiltIns)
                                if (result2 is TypeParameterContext.Success) {
                                    bindTypes(originalConstructor.returnType as? IrSimpleType ?: throw PluginCodegenException("Expected a simple type: ${originalConstructor.returnType.render()}"), result2.typeParameterMap)
                                } else {
                                    errorType
                                }
                            }
                        }
                        val candidateCopy = IrConstructorCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, callType, current.symbol, current.typeArgumentsCount, current.constructorTypeArgumentsCount, current.valueArgumentsCount, current.origin).also { me ->
                            args.forEachIndexed { i, v -> me.putValueArgument(i, v) }
                            (0 until current.typeArgumentsCount).forEach { i -> me.putTypeArgument(i, current.getTypeArgument(i)) }
                        }
                        val result = delegate.replaceConstructorCall(current, candidateCopy) ?: candidateCopy
                        result
                    }
                    is IrCall -> {
                        val originalFunctionSymbol = current.symbol
                        val dispatchReceiverArgument = if (originalFunctionSymbol.owner.dispatchReceiverParameter != null) copies.pop() as IrExpression else null
                        val extensionArgument = if (originalFunctionSymbol.owner.extensionReceiverParameter != null) copies.pop() as IrExpression else null
                        val args = (0 until current.valueArgumentsCount).map { copies.pop() as IrExpression }
                        val functionSymbol = if (originalFunctionSymbol.owner.isPropertyAccessor) {
                            val property = originalFunctionSymbol.owner.correspondingPropertySymbol!!.owner
                            propertySubsitution[property]?.let { imageProperty ->
                                if (originalFunctionSymbol.owner.isSetter) {
                                    imageProperty.setter!!.symbol
                                } else {
                                    imageProperty.getter!!.symbol
                                }
                            } ?: originalFunctionSymbol
                        } else originalFunctionSymbol
                        val callType = when (val result = TypeParameterContext.invoke(functionSymbol.owner, args, dispatchReceiverArgument, extensionArgument, null, irBuiltIns)) {
                            is TypeParameterContext.Success -> {
                                val tpeParameterMap = result.typeParameterMap
                                bindTypes(functionSymbol.owner.returnType as? IrSimpleType ?: throw PluginCodegenException("Expected a simple type: ${functionSymbol.owner.returnType.render()}"), tpeParameterMap)
                            }
                            is TypeParameterContext.Failure -> {
                                errorType
                            }
                        }
                        val candidateCall = IrCallImpl(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            callType,
                            functionSymbol,
                            current.typeArgumentsCount,
                            current.valueArgumentsCount,
                            current.origin
                        ).also {
                            it.dispatchReceiver = dispatchReceiverArgument
                            it.extensionReceiver = extensionArgument
                            args.forEachIndexed { i, arg -> it.putValueArgument(i, arg) }
                        }
                        val call = delegate.replaceCandidateWith(current, candidateCall) ?: candidateCall
                        call
                    }
                    is IrConst<*> -> current.copy()
                    is IrTypeOperatorCall -> {
                        val operand = copies.pop() as IrExpression
                        if (current.type == operand.type) {
                            operand
                        } else {
                            val typeOperandCandidate = IrTypeOperatorCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, current.operator, current.typeOperand, operand)
                            delegate.replaceCandidateWith(current, typeOperandCandidate) ?: typeOperandCandidate
                        }
                    }
                    is IrDelegatingConstructorCall -> {
                        val args = (0 until current.valueArgumentsCount).map { copies.pop() as IrExpression }
                        val copyCandidate = IrDelegatingConstructorCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, current.symbol, current.typeArgumentsCount, current.valueArgumentsCount)
                        args.forEachIndexed { i, arg -> copyCandidate.putValueArgument(i, arg) }
                        copyCandidate
                    }
                    is IrSetValue -> {
                        val expression = copies.pop() as IrExpression
                        val originalValue = current.symbol.owner
                        val newSymbol = valueParameterSubsitutor[originalValue]?.symbol ?: current.symbol
                        val candidateCopy = IrSetValueImpl(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            expression.type,
                            newSymbol,
                            expression,
                            current.origin
                        )
                        val setVal = delegate.replaceCandidateWith(current, candidateCopy) ?: candidateCopy
                        setVal
                    }
                    is IrBranch -> {
                        val condition = copies.pop() as IrExpression
                        val result = copies.pop() as IrExpression
                        IrBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, condition, result)
                    }
                    is IrWhen -> {
                        val branches = (0 until current.branches.size).map { copies.pop() as IrBranch }
                        IrWhenImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, current.origin).also { it.branches.addAll(branches) }
                    }
                    is IrWhileLoop -> {
                        val condition = copies.pop() as IrExpression
                        val body = copies.pop() as IrExpression
                        IrWhileLoopImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, current.origin).also { it.body = body; it.condition = condition }
                    }
                    is IrBlock -> {
                        val statements = (0 until current.statements.size).map { copies.pop() as IrStatement }.reversed()
                        IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, current.origin, statements)
                    }
                    is IrBlockBody -> {
                        val statements = (0 until current.statements.size).map { copies.pop() as IrStatement }.reversed()
                        IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, statements)
                    }
                    is IrValueParameter -> {
                        val candidateCopy = IrValueParameterImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.origin, IrValueParameterSymbolImpl(), current.name, current.index, current.type, current.varargElementType, current.isCrossinline, current.isNoinline, current.isHidden, current.isAssignable).also { it.parent = newParent }
                        val newValueParameter = delegate.replaceValueParameterWith(current, candidateCopy) ?: candidateCopy
                        valueParameterSubsitutor[current] = newValueParameter
                        newValueParameter
                    }
                    is IrSimpleFunction -> {
                        IrFunctionImpl(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            current.origin,
                            IrSimpleFunctionSymbolImpl(),
                            current.name,
                            current.visibility,
                            current.modality,
                            current.returnType,
                            current.isInline,
                            current.isExternal,
                            current.isTailrec,
                            current.isSuspend,
                            current.isOperator,
                            current.isInfix,
                            current.isExpect,
                            current.isFakeOverride
                        ).also { thisFunc ->
                            thisFunc.parent = newParent
                            thisFunc.overriddenSymbols = current.overriddenSymbols
                            thisFunc.body = if (current.body != null) copies.pop() as IrBody else null
                            thisFunc.valueParameters = current.valueParameters.map { (copies.pop() as IrValueParameter).also { it.parent = thisFunc } }
                            thisFunc.dispatchReceiverParameter = if (current.dispatchReceiverParameter == null) null else { (copies.pop() as IrValueParameter).also { it.parent = thisFunc } }
                            thisFunc.extensionReceiverParameter = if (current.extensionReceiverParameter == null) null else { (copies.pop() as IrValueParameter).also { it.parent = thisFunc } }
                            thisFunc.typeParameters = current.typeParameters.map { copies.pop() as IrTypeParameter }
                        }
                    }
                    is IrAnonymousInitializer -> {
                        val init = IrAnonymousInitializerImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.origin, IrAnonymousInitializerSymbolImpl(), current.isStatic)
                        init.parent = newParent
                        init.body = copies.pop() as IrBlockBody
                        init
                    }
                    is IrFunctionExpression -> {
                        val func = copies.pop() as IrSimpleFunction
                        IrFunctionExpressionImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, func, current.origin)
                    }
                    is IrGetValue -> {
                        val target = valueParameterSubsitutor[current.symbol.owner]
                        val customGenerated = if (target == null) delegate.replaceUntrackedGetValue(current) else null
                        val getValue = when {
                            target != null -> IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, target.symbol, null)
                            customGenerated != null -> customGenerated
                            else -> {
                                IrMissingTargetDeclaration(current.symbol.owner)
                            }
                        }
                        getValue
                    }
                    is IrGetObjectValue -> {
                        val candidate = IrGetObjectValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, current.symbol)
                        delegate.replaceGetObject(current, candidate) ?: candidate
                    }
                    is IrReturn -> {
                        val result = copies.pop() as IrExpression
                        val candidate = IrReturnImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, current.returnTargetSymbol, result)
                        delegate.replaceReturnStatement(current, candidate) ?: candidate
                    }
                    is IrThrow -> {
                        val value = copies.pop() as IrExpression
                        IrThrowImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, value)
                    }
                    is IrGetField -> {
                        val imageField: IrFieldSymbol = current.symbol.owner.correspondingPropertySymbol?.owner?.let { originalProperty ->
                            propertySubsitution[originalProperty]?.backingField?.symbol
                        } ?: current.symbol
                        val candidateCopy = IrGetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, imageField, imageField.owner.type)
                        delegate.replaceGetField(current, candidateCopy) ?: candidateCopy
                    }
                    else -> {
                        throw NotImplementedError("${current.render()} is not a supported copy element")
                    }
                }
            if (s.isEmpty()) {
                return copy
            } else {
                copies.push(copy)
            }
        }
        throw IllegalStateException("Should be unreachable")
    }
    companion object {
        val errorType = IrErrorTypeImpl(null, emptyList(), Variance.INVARIANT)
        /**
         * IrMissingTargetDeclaration is used when a composite statement will be replaced by a smaller statement.
         * For example, suppose we encounter a property access in a copy from a class into
         * a top level function with no implicit parameter. The format of the original statement
         * is '.myPropertyGetter()`. The copier will attempt to find a target variable that corresponds
         * with the source variable . It's likely that one hasn't been defined because there is no implicit
         * variable that matches the type in the target. However, we assume the caller has a plan for
         * handling this situation. However, the caller may need more context. Instead of erroring out
         * when we can't find a corresponding variable in the target map, we insert the missing declaration
         * IrElement and continue copying. This means that the caller will receive a delegate call when the
         * IrCall is processed. At this time it will handle the substitution, likely responding by returning
         * a 'GetVal' of a local declaration that it wants to substitute property accesses with.
         */
        class IrMissingTargetDeclaration(val src: IrValueDeclaration) : IrErrorExpression() {
            override val endOffset: Int = UNDEFINED_OFFSET
            override val startOffset: Int = UNDEFINED_OFFSET
            override var type: IrType = errorType
            override val description: String = "A variable in the source could not be mapped to a variable in the target: ${src.name}"
            override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R = visitor.visitErrorExpression(this, data)
        }
    }
}
