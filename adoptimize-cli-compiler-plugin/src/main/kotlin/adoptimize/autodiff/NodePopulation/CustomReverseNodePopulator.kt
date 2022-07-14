/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.NodePopulation

import adoptimize.AutoDiffException
import adoptimize.autodiff.AutoDiffCodeWriterVendor
import adoptimize.autodiff.BackPropFunction.DiffIRCreator
import adoptimize.autodiff.GuardedScope
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.ReverseScalarClass
import adoptimize.autodiff.Metadata.StackClass
import adoptimize.autodiff.Metadata.zero
import adoptimize.autodiff.Primal
import adoptimize.autodiff.PrimalAndPullback
import adoptimize.autodiff.correctSpecializedNames
import adoptimize.autodiff.diffIR.*
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrAnonymousInitializerImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import pluginCommon.*
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.IrPropertyGenerator

// The custom reverse node populator adds properties and populates the constructor and backprop method.
// It's primary responsibility is defining the structure of the primal and backprop algorithm. The code for each statement
// is produced by the code writers. The reason for this is so that we can view the code when available or provide hard coded implementations
// for code that is unavailable.
// @param generator: the generator contains builders for creating IR.
// @param codeWriterVendor: The code writer vendor vends a code writer which will write the computations for the primal value and the derivative value into the context.
class CustomReverseNodePopulator(
    val callGenerator: IrBodyGenerator,
    val propertyGenerator: IrPropertyGenerator,
    val pluginContext: IrPluginContext,
    val differentiableApi: DifferentiableApi,
    val codeWriterVendor: AutoDiffCodeWriterVendor,
    val stackClass: StackClass
) {
    val differentiableType = differentiableApi.scalarRoot.defaultType
    val integerEqEq by lazy {
        pluginContext.irBuiltIns.intClass.owner.functions.first { it.isOperator && it.name.toString().contains("equals") }
    }

    private class ReverseNodeProperties(
        val intermediateVariables: IrProperty,
        val decisions: IrProperty,
        val valueParameterProperties: Map<IrValueDeclaration, IrProperty>
    )
    fun populate(reverseScalarClass: ReverseScalarClass, primalFunction: DiffIRFunction) {
        val localMap = mutableSetOf<IrValueDeclaration>()
        val runtimeMap = mutableMapOf<IrValueDeclaration, IrDeclarationWithName>()
        val codeWriter = codeWriterVendor.codeWriter(primalFunction)
        val generatedClass = reverseScalarClass.clazz

        // initialize properties:
        // (1) add a property for each parameter of the primal,
        // (2) add derivative id since its abstract (hack since we aren't peeking into superclasses to get property expressions)
        // (3) override the primal so that we may set it
        fun initializeProperties(): ReverseNodeProperties {
            val primalToTarget = mutableMapOf<IrValueDeclaration, IrProperty>()

            // active variables
            val propertyParameters = primalFunction.getParametersWithIndex()
            propertyParameters.forEach {
                val parameterMaps = reverseScalarClass.parameterMaps.filter { pm -> pm.functionIndex == it.index }
                if (parameterMaps.size != 1) {
                    throw AutoDiffException("Why are there multiple parameters corresponding to the same dispatch function parameter")
                }
                val parameterMap = parameterMaps.first()
                val property = generatedClass.properties.first { it.name == parameterMap.correspondingPropertyName }
                val valueParameter = when (it.index) {
                    -2 -> primalFunction.original.extensionReceiverParameter!!
                    -1 -> primalFunction.original.dispatchReceiverParameter!!
                    else -> primalFunction.original.valueParameters[it.index]
                }
                primalToTarget[valueParameter] = property
            }

            // abstract properties (requires no removal of a fake override)
            if (differentiableApi.reverseDiffScalarClass.derivativeId.modality == Modality.ABSTRACT) {
                // HACK
                val arbitraryActiveVariableProperty = primalToTarget.values.firstOrNull({ it.getter!!.returnType == differentiableApi.reverseDiffScalarClass.clazz.defaultType })!!
                val getDerivativeId = callGenerator.generateCall(
                    differentiableApi.reverseDiffScalarClass.derivativeId.getter!!,
                    listOf(),
                    callGenerator.generateGetProperty(arbitraryActiveVariableProperty, callGenerator.generateGetValue(generatedClass.thisReceiver!!))
                )
                propertyGenerator.generateProperty(
                    name = differentiableApi.reverseDiffScalarClass.derivativeId.name,
                    type = differentiableApi.reverseDiffScalarClass.derivativeId.getter!!.returnType as IrSimpleType,
                    containingClass = generatedClass,
                    isSettable = false,
                    initializer = getDerivativeId,
                    parent = differentiableApi.reverseDiffScalarClass.derivativeId
                )
            }

            // if there is not a setPrimal method, then we must override the primal in order to set it
            if (differentiableApi.reverseDiffScalarClass.setPrimal == null) {
                val fakeOverride = generatedClass.properties.filter { it.isFakeOverride }.first {
                    val sym = differentiableApi.reverseDiffScalarClass.primalProperty.getter!!.symbol
                    it.getter?.overriddenSymbols?.contains(sym) == true
                }

                generatedClass.declarations.remove(fakeOverride)
                propertyGenerator.generateProperty(
                    name = differentiableApi.reverseDiffScalarClass.primalProperty.name,
                    type = differentiableApi.reverseDiffScalarClass.primalProperty.getter!!.returnType as IrSimpleType,
                    containingClass = generatedClass,
                    isSettable = false,
                    initializer = null,
                    parent = differentiableApi.reverseDiffScalarClass.primalProperty
                )
            }

            return ReverseNodeProperties(reverseScalarClass.intermediateVariables, reverseScalarClass.decisions, primalToTarget)
        }

        fun populateBackpropMethod(primalToUnwrappedProperties: Map<IrValueDeclaration, IrProperty>, reverseNodeProperties: ReverseNodeProperties) {
            val backPropMethod = generatedClass.functions.first { it.overriddenSymbols.contains(differentiableApi.reverseDiffScalarClass.backpropMethod.symbol) }

            val context = GuardedScope()

            val upstreamDerivatives = object {
                private val substitutionScope = ScopeSubstitutionMap()
                fun push() = substitutionScope.push()
                fun pop() = substitutionScope.pop()
                operator fun get(src: IrValueDeclaration): IrVariable {
                    val needle: IrValueDeclaration? = substitutionScope[src]
                    needle?.let { return it as IrVariable } ?: throw AutoDiffException("Cannot find an upstream derivative that corresponds to ${src.name}. Ensure that it was identified as an active statement and it's derivative initialized in the correct block in the backprop method.")
                }

                fun contains(variable: IrValueDeclaration): Boolean {
                    return substitutionScope[variable] != null
                }

                operator fun set(src: IrValueDeclaration, target: IrVariable) {
                    substitutionScope[src] = target
                }

                fun updateDerivative(src: IrValueDeclaration, contribution: IrValueDeclaration): IrSetValue {
                    val derivative = this[src]
                    return callGenerator.generateSetVariable(
                        derivative,
                        callGenerator.generateCall(
                            differentiableApi.plusFunction(derivative.type, contribution.type),
                            listOf(callGenerator.generateGetValue(derivative)),
                            null,
                            callGenerator.generateGetValue(contribution)
                        )
                    )
                }
            }

            val popInteremediate: (String, IrType) -> IrVariable = { name, type ->
                val dispatchReceiver = callGenerator.generateGetProperty(reverseNodeProperties.intermediateVariables, callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!))
                val popCall = popExpression(dispatchReceiver)
                val cast = callGenerator.generateCast(popCall, type)
                callGenerator.generateVal(Name.identifier("${name}_local_${variableCounter++}"), backPropMethod, cast)
            }

            val decisions = java.util.ArrayDeque<IrVariable>()
            val primalToTargetLocals = ScopeSubstitutionMap()

            fun generateCodeForStatement(current: DiffIRStatement): IrElement? {
                return when (current) {
                    is CallVariable -> {
                        val upstreamKey = current.original
                        val upstreamDerivative = upstreamDerivatives[upstreamKey]
                        // pop the intermediates. Start with the pullback
                        val pullback: IrValueDeclaration? = if (runtimeMap.containsKey(current.original)) {
                            if (localMap.contains(current.original)) {
                                val localVar = popInteremediate("${current.name}_pb", (runtimeMap[current.original]!! as IrValueDeclaration).type)
                                context.putStatefulVariable(localVar)
                                localVar
                            } else {
                                val getProp = callGenerator.generateGetProperty(
                                    property = runtimeMap[current.original]!! as IrProperty,
                                    dispatchReceiver = callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!)
                                )
                                val localVar = context.tryPutStatelessVariable(callGenerator.generateVal(Name.identifier("temp${variableCounter++}"), backPropMethod, getProp))
                                localVar
                            }
                        } else null

                        if (current.isReferencedInBackprop && (!current.original.isImmutable || localMap.contains(current.original))) {
                            val targetVariable = popInteremediate(current.name.toString(), current.type)
                            primalToTargetLocals[current.original] = targetVariable
                            context.putStatefulVariable(targetVariable)
                        }

                        // all arguments to the differentiable statement should exist in the primalToLocal before backpropping
                        current.callInfo.allDiffIrArguments().filter { it.isReferencedInBackprop }.map { it.argument }.reversed().forEach {
                            if (!it.isImmutable || localMap.contains(it)) {
                                val targetVariable = popInteremediate(it.name.toString(), it.type)
                                primalToTargetLocals[it] = targetVariable
                                context.putStatefulVariable(targetVariable)
                            } else {
                                if (primalToTargetLocals[it] == null) {
                                    val correspondingProperty = primalToUnwrappedProperties[it] ?: throw AutoDiffException("Could not find ${it.name} in the primals to properties map.")
                                    val getProp = callGenerator.generateGetProperty(
                                        property = correspondingProperty,
                                        dispatchReceiver = callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!)
                                    )
                                    val targetVariable = context.tryPutStatelessVariable(callGenerator.generateVal(it.name, backPropMethod, getProp))
                                    primalToTargetLocals[it] = targetVariable
                                }
                            }
                        }
                        val derivativeContributions = codeWriter.writeBackpropCodeForLeaf(
                            current,
                            primalToTargetLocals,
                            upstreamDerivative,
                            backPropMethod,
                            context,
                            pullback
                        )

                        derivativeContributions.groupBy({ pair -> pair.first },).forEach { contributionListEntry: Map.Entry<IrValueDeclaration, List<Pair<IrValueDeclaration, IrValueDeclaration>>> ->
                            val componentsToAdd: List<IrValueDeclaration> = contributionListEntry.value.map { it.second }
                            val primalValue = contributionListEntry.key
                            val derivativeVariable = upstreamDerivatives[primalValue]
                            val updatedDerivativeExpression: IrExpression = componentsToAdd.fold(
                                initial = callGenerator.generateGetValue(derivativeVariable),
                                operation = { thusFar: IrExpression, next: IrValueDeclaration ->
                                    callGenerator.generateCall(
                                        differentiableApi.plusFunction(thusFar.type, next.type),
                                        listOf(thusFar),
                                        null,
                                        callGenerator.generateGetValue(next)
                                    )
                                }
                            )
                            val updateOldDerivative = callGenerator.generateSetVariable(derivativeVariable as IrVariable, updatedDerivativeExpression)
                            context.putSet(updateOldDerivative)
                        }
                        null
                    }
                    is LateInitVariable -> null
                    is GetValVariable -> {
                        if (current.isActive) {
                            context.putSet(upstreamDerivatives.updateDerivative(current.rhs, upstreamDerivatives[current.original]))
                        }
                        null
                    }
                    is SetValue -> {
                        if ((!current.referencedVariable.isImmutable || localMap.contains(current.referencedVariable)) && current.rhsIsUsedInBackProp) {
                            val targetVariable = popInteremediate(current.referencedVariable.name.toString(), current.referencedVariable.type)
                            primalToTargetLocals[current.referencedVariable] = targetVariable
                            context.putStatefulVariable(targetVariable)
                        }
                        if (current.rhsIsActive) {
                            val lhsDerivative = upstreamDerivatives[current.setVariable]
                            val updatedDerivative = upstreamDerivatives.updateDerivative(current.referencedVariable, lhsDerivative)
                            context.putSet(updatedDerivative)
                            context.putSet(callGenerator.generateSetVariable(lhsDerivative, differentiableApi.zero()))
                        }
                        null
                    }
                    is WhenStatement -> {
                        // pop decision
                        val dispatchReceiver = callGenerator.generateGetProperty(reverseNodeProperties.decisions, callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!))
                        val popCall = popExpression(dispatchReceiver)
                        val decision = callGenerator.generateVal(Name.identifier("decisions_${variableCounter++}"), backPropMethod, popCall)

                        decisions.push(decision)
                        context.putStatefulVariable(decision)
                        val branches = current.children.map { (generateCodeForStatement(it) ?: throw AutoDiffException("Expected a non null child when building a when statement")) as IrBranch }.toMutableList()
                        decisions.pop()
                        val trueStatement = IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.booleanType, true)
                        val throwStatement = IrThrowImpl(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            pluginContext.irBuiltIns.nothingType,
                            callGenerator.generateCall(pluginContext.irBuiltIns.noWhenBranchMatchedExceptionSymbol.owner, emptyList(), null)
                        )
                        val emptyElse = IrElseBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, trueStatement, IrThrowImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.throwableType, throwStatement))
                        branches.add(emptyElse)
                        val whenStatement = IrWhenImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.original.type, null, branches)
                        context.putWhen(whenStatement)
                        null
                    }
                    is LoopStatement -> {
                        val child = generateCodeForStatement(current.body) as IrBlock
                        val getDecisions = {
                            callGenerator.generateGetProperty(
                                reverseNodeProperties.decisions,
                                callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!)
                            )
                        }
                        val isNotEmpty = callGenerator.generateCall(stackClass.notEmptyMethod as IrSimpleFunction, emptyList(), getDecisions())
                        val topItem = callGenerator.generateCall(stackClass.topMethod as IrSimpleFunction, emptyList(), getDecisions())
                        val topItemIsEqToID = callGenerator.generateCall(
                            pluginContext.irBuiltIns.eqeqSymbol.owner,
                            listOf(topItem, IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.intType, current.identifier)),
                            null
                        )
                        val notEmptyAndIsEqual = callGenerator.generateCall(
                            pluginContext.irBuiltIns.andandSymbol.owner,
                            listOf(isNotEmpty, topItemIsEqToID),
                            null
                        )

                        // pop decision
                        val dispatchReceiver = callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!)
                        val getProperty = callGenerator.generateGetProperty(reverseNodeProperties.decisions, dispatchReceiver)
                        val popCall = popExpression(getProperty)
                        child.statements.add(0, popCall)
                        val loop = IrWhileLoopImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.original.type, current.original.origin).also {
                            it.body = child
                            it.condition = notEmptyAndIsEqual
                        }
                        context.putLoop(loop)
                        null
                    }
                    is AbstractBlockStatement -> {
                        // we start the block by ensuring that all primal values that are declared in this block have a derivative initialized
                        data class LocalInit(val localPrimal: IrVariable?, val initialDerivative: IrVariable?)
                        fun initializeLocalTargetVariablesForPrimalValue(primalValue: IrValueDeclaration): LocalInit {
                            val isTargetClassPrimalValue = primalValue == primalFunction.returnedVariable
                            val isValueParameter = reverseNodeProperties.valueParameterProperties[primalValue] != null
                            val isTopLevelIntermediateValue = primalToUnwrappedProperties[primalValue] != null
                            val prefix = if (primalValue.name.isSpecial) correctSpecializedNames(primalValue.name.toString()) else primalValue.name.toString()
                            val targetVariable = when {
                                // a value parameter should have a top level property associated with it and could potentially need unwrapping
                                isValueParameter -> {
                                    val localVariableMaybe = callGenerator.generateIrVariable(
                                        Name.identifier("${prefix}_local_${variableCounter++}"),
                                        backPropMethod,
                                        callGenerator.generateGetProperty(
                                            reverseNodeProperties.valueParameterProperties[primalValue]!!,
                                            callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!)
                                        ),
                                        false
                                    )
                                    val localVariable = context.tryPutStatelessVariable(localVariableMaybe)
                                    if (localVariable.type.classifierOrFail.owner == differentiableApi.reverseDiffScalarClass.clazz) {
                                        val unwrappedLocalVariableMaybe = callGenerator.generateVal(
                                            Name.identifier("${prefix}_local_${variableCounter++}_primal"),
                                            backPropMethod,
                                            callGenerator.generateGetProperty(callGenerator.generateGetValue(localVariable), differentiableApi.reverseDiffScalarClass.primalProperty.name)
                                        )
                                        context.tryPutStatelessVariable(unwrappedLocalVariableMaybe)
                                    } else {
                                        localVariable
                                    }
                                }
                                isTargetClassPrimalValue -> {
                                    val t = callGenerator.generateIrVariable(
                                        Name.identifier("${prefix}_local_${variableCounter++}"),
                                        backPropMethod,
                                        callGenerator.generateGetProperty(
                                            generatedClass.properties.first { it.getter?.overriddenSymbols?.contains(differentiableApi.reverseDiffScalarClass.primalProperty.getter!!.symbol) == true },
                                            callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!)
                                        ),
                                        false
                                    )
                                    context.tryPutStatelessVariable(t)
                                }
                                isTopLevelIntermediateValue -> {
                                    val t = callGenerator.generateIrVariable(
                                        Name.identifier("${prefix}_local_${variableCounter++}"),
                                        backPropMethod,
                                        callGenerator.generateGetProperty(
                                            primalToUnwrappedProperties[primalValue]!!,
                                            callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!)
                                        ),
                                        false
                                    )
                                    context.tryPutStatelessVariable(t)
                                }
                                else -> null
                            }

                            // This should only be done for active variables! For now let's assume it's active if its ReverseNode type or supertype
                            val derivative: IrVariable?

                            if (!isTargetClassPrimalValue && primalValue.type.isSubtypeOf(differentiableType, IrTypeSystemContextImpl(pluginContext.irBuiltIns))) {
                                derivative = callGenerator.generateVariable(Name.identifier(DiffIRCreator.gradientVariableName(prefix)), backPropMethod, differentiableApi.zero())
                                context.putStatefulVariable(derivative)
                            } else {
                                derivative = null
                            }
                            return LocalInit(targetVariable, derivative)
                        }
                        fun initializeLocalAndInitialDerivativeVariables(child: DiffIRStatement) {
                            when (child) {
                                is ActiveVariable -> {
                                    val variable: IrVariable = child.original
                                    val targetAndDerivative = initializeLocalTargetVariablesForPrimalValue(variable)
                                    targetAndDerivative.localPrimal?.let { primalToTargetLocals.set(variable, it) }
                                    targetAndDerivative.initialDerivative?.let { upstreamDerivatives[variable] = it }
                                }
                                is SetValue -> {
                                    if (child.setVariable.initializer is IrGetValue && !upstreamDerivatives.contains(child.setVariable)) {
                                        val targetAndDerivative = initializeLocalTargetVariablesForPrimalValue(child.setVariable)
                                        targetAndDerivative.localPrimal?.let { primalToTargetLocals.set(child.setVariable, it) }
                                        targetAndDerivative.initialDerivative?.let { upstreamDerivatives[child.setVariable] = it }
                                    }
                                }
                                is LoopStatement -> {
                                    initializeLocalAndInitialDerivativeVariables(child.body)
                                }
                                is WhenStatement -> {
                                    child.children.forEach { initializeLocalAndInitialDerivativeVariables(it) }
                                }
                                is ConditionBlock -> {
                                    initializeLocalAndInitialDerivativeVariables(child.result)
                                }
                                is BlockStatement -> {
                                    if (child.isVirtual) {
                                        child.children.reversed().forEach { initializeLocalAndInitialDerivativeVariables(it) }
                                    }
                                }
                            }
                        }

                        fun processAuthenticBlock(pushBackOutputs: Boolean, initializeParameters: Boolean): List<IrStatement> {
                            upstreamDerivatives.push()
                            context.pushScope()
                            primalToTargetLocals.push()
                            if (initializeParameters) {
                                primalFunction.getParameters().forEach { primalValue ->
                                    val targetAndDerivative = initializeLocalTargetVariablesForPrimalValue(primalValue)
                                    targetAndDerivative.localPrimal?.let { primalToTargetLocals.set(primalValue, it) }
                                    targetAndDerivative.initialDerivative?.let { initializedDerivative ->
                                        upstreamDerivatives[primalValue] = initializedDerivative
                                    }
                                }
                            }
                            current.children.reversed().forEach { initializeLocalAndInitialDerivativeVariables(it) }
                            current.children.reversed().forEach { generateCodeForStatement(it) }

                            if (pushBackOutputs) {
                                val derivativeOutputs = mutableListOf<IrValueDeclaration>()
                                reverseNodeProperties.valueParameterProperties.filter {
                                    differentiableApi.reverseDiffScalarClass.clazz.isSubclassOf(it.key.type.classifierOrFail.owner as IrClass)
                                }.forEach {
                                    val variable = callGenerator.generateVal(
                                        it.value.name,
                                        backPropMethod,
                                        callGenerator.generateGetProperty(it.value, callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!))
                                    )
                                    val getProperty = context.tryPutStatelessVariable(variable)
                                    val derivative = upstreamDerivatives[it.key] ?: throw AutoDiffException("Cannot find derivative for `${it.key.name}`")
                                    val pushback = callGenerator.generateCall(
                                        differentiableApi.reverseDiffScalarClass.pushbackMethod as IrSimpleFunction,
                                        listOf(callGenerator.generateGetValue(derivative)),
                                        callGenerator.generateGetValue(getProperty),
                                        null
                                    )
                                    context.putCall(pushback)
                                    derivativeOutputs.add(derivative)
                                }
                            }
                            primalToTargetLocals.pop()
                            upstreamDerivatives.pop()
                            return context.popScope()
                        }

                        when (current) {
                            is BlockStatement -> {
                                if (!current.isVirtual) {
                                    IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, null, processAuthenticBlock(false, false))
                                } else {
                                    current.children.reversed().forEach { generateCodeForStatement(it) }
                                    null
                                }
                            }
                            is BlockBodyStatement -> {
                                IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, processAuthenticBlock(true, true))
                            }
                        }
                    }
                    is ConditionBlock -> {
                        val block = (generateCodeForStatement(current.result) ?: throw AutoDiffException("Expected a nonnull child")) as IrBlock
                        val getDecision = callGenerator.generateGetValue(decisions.first())
                        val index = IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.intType, current.index)
                        IrBranchImpl(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            callGenerator.generateCall(integerEqEq, listOf(index), getDecision, null),
                            block
                        )
                    }
                    is ReturnStatement -> {
                        val rootUpstream = context.tryPutStatelessVariable(
                            callGenerator.generateVal(
                                differentiableApi.reverseDiffScalarClass.upstreamProperty.name,
                                backPropMethod,
                                callGenerator.generateGetProperty(callGenerator.generateGetValue(backPropMethod.dispatchReceiverParameter!!), differentiableApi.reverseDiffScalarClass.upstreamProperty.name)
                            )
                        )
                        upstreamDerivatives[primalFunction.returnedVariable] = rootUpstream
                        null
                    }
                    is ConstantStatement -> {
                        // Note that constants are only used in backprop by being popped if another statement needs it
                        null
                    }
                    else -> {
                        throw NotImplementedError("unrecognized statement in primal encountered.")
                    }
                }
            }

            val b = generateCodeForStatement(primalFunction.body) as IrBlockBody
            backPropMethod.body = b
        }

        fun populateInitializer(reverseNodeProperties: ReverseNodeProperties): Map<IrValueDeclaration, IrProperty> {
            val primalToTargetReverseNodeProperty: Map<IrValueDeclaration, IrProperty> = reverseNodeProperties.valueParameterProperties
            val primalToUnwrappedProperties = mutableMapOf<IrValueDeclaration, IrProperty>()
            val anonymousInitializer = IrAnonymousInitializerImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, IrDeclarationOrigin.DEFINED, IrAnonymousInitializerSymbolImpl(), false).also { it.parent = generatedClass }

            val addDecision: (Int) -> IrCall = { decision ->
                val argument = IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.intType, decision)
                val dispatchReceiver = callGenerator.generateGetProperty(reverseNodeProperties.decisions, callGenerator.generateGetValue(generatedClass.thisReceiver!!))
                callGenerator.generateCall(stackClass.pushMethod as IrSimpleFunction, listOf(argument), dispatchReceiver)
            }

            val pushIntermediate: (IrValueDeclaration) -> IrCall = { intermediateValue ->
                val dispatchReceiver = callGenerator.generateGetProperty(reverseNodeProperties.intermediateVariables, callGenerator.generateGetValue(generatedClass.thisReceiver!!))
                callGenerator.generateCall(stackClass.pushMethod as IrSimpleFunction, listOf(callGenerator.generateGetValue(intermediateValue)), dispatchReceiver)
            }

            val context = GuardedScope()
            val primalToTargetVariables = ScopeSubstitutionMap()
            val lateInitMap = mutableSetOf<IrValueDeclaration>()
            fun generateTargetCodeForPrimalStatement(current: DiffIRStatement): IrElement? {
                return when (current) {
                    is CallVariable -> {
                        current.callInfo.allDiffIrArguments().filter { it.isReferencedInBackprop }.map { it.argument }.forEach {
                            if (!it.isImmutable || localMap.contains(it)) {
                                val targetVariable = primalToTargetVariables[it] ?: throw AutoDiffException("Expected a target variable for primal `${it.name}` to have been added to the map")
                                context.putCall(pushIntermediate(targetVariable))
                            }
                        }
                        val primalImage: IrValueDeclaration
                        val pullBackImage: IrValueDeclaration?
                        when (val writtenDeclarations = codeWriter.writeInitCodeForLeaf(current, primalToTargetVariables, context, generatedClass)) {
                            is Primal -> {
                                primalImage = writtenDeclarations.primal
                                pullBackImage = null
                            }
                            is PrimalAndPullback -> {
                                primalImage = writtenDeclarations.primal
                                pullBackImage = writtenDeclarations.pullback
                            }
                            else -> {
                                throw AutoDiffException("Both primal and pullback are null. Check the code writer for variables.")
                            }
                        }
                        val persistedValueSrc = current.original
                        if (!context.isTopLevel()) {
                            localMap.add(persistedValueSrc)
                        }
                        val primalNeedsProperty = persistedValueSrc != primalFunction.returnedVariable && context.isTopLevel() && persistedValueSrc.isImmutable
                        val pullbackNeedsProperty = context.isTopLevel() && persistedValueSrc.isImmutable && pullBackImage != null
                        primalToTargetVariables[persistedValueSrc] = primalImage
                        if (primalNeedsProperty) {
                            val targetProperty = propertyGenerator.generateProperty(persistedValueSrc.name, current.type, generatedClass, false, null)
                            val setField = callGenerator.generateSetField(targetProperty.backingField!!, callGenerator.generateGetValue(primalImage), generatedClass)
                            context.putSetField(setField)
                            primalToUnwrappedProperties[persistedValueSrc] = targetProperty
                        }
                        if (pullBackImage != null) {
                            if (pullbackNeedsProperty) {
                                val targetPullbackProperty = propertyGenerator.generateProperty(
                                    Name.identifier("${persistedValueSrc.name}_pb"),
                                    pullBackImage.type,
                                    generatedClass,
                                    false,
                                    null
                                )
                                val setPbField = callGenerator.generateSetField(targetPullbackProperty.backingField!!, callGenerator.generateGetValue(pullBackImage), generatedClass)
                                context.putSetField(setPbField)
                                runtimeMap[persistedValueSrc] = targetPullbackProperty
                            } else {
                                context.putCall(pushIntermediate(pullBackImage))
                                runtimeMap[persistedValueSrc] = pullBackImage
                            }
                        }
                        if (current.isReferencedInBackprop && (!current.original.isImmutable || localMap.contains(current.original))) {
                            val targetVariable = primalToTargetVariables[current.original] ?: throw AutoDiffException("Expected a target variable for primal `${current.name}` to have been added to the map")
                            context.putCall(pushIntermediate(targetVariable))
                        }
                        null
                    }
                    is SetValue -> {
                        val rhs = current.referencedVariable
                        if (current.rhsIsUsedInBackProp && (!rhs.isImmutable || localMap.contains(rhs))) {
                            val targetVariable = primalToTargetVariables[rhs] ?: throw AutoDiffException("Expected a target variable for primal `${rhs.name}` to have been added to the map")
                            context.putCall(pushIntermediate(targetVariable))
                        }
                        val copiedStatement = CopyAndReplacer(
                            ScopeSubstitutionMapSubstitutor(primalToTargetVariables),
                            Substitutor.emptySubstitutor(),
                            pluginContext.irBuiltIns
                        )
                            .copyAndReplace(current.original, ReplaceDelegate.emptyReplacer, generatedClass) as IrSetValue

                        if (lateInitMap.contains(current.setVariable)) {
                            context.putSet(copiedStatement)
                            val rhs = primalToTargetVariables[current.referencedVariable] ?: throw AutoDiffException("The referenced variable ${current.referencedVariable.name} was not found in the context")
                            val setField = callGenerator.generateSetField(primalToUnwrappedProperties[current.setVariable]!!.backingField!!, callGenerator.generateGetValue(rhs), generatedClass)
                            context.putSetField(setField)
                        } else {
                            context.putSet(copiedStatement)
                        }
                        null
                    }
                    is DiffIRAtom -> {
                        val original = current.original
                        val type = current.type
                        val copiedStatement = CopyAndReplacer(
                            ScopeSubstitutionMapSubstitutor(primalToTargetVariables),
                            Substitutor.emptySubstitutor(),
                            pluginContext.irBuiltIns
                        )
                            .copyAndReplace(original, ReplaceDelegate.emptyReplacer, generatedClass)
                        when (copiedStatement) {
                            is IrCall -> context.putCall(copiedStatement)
                            is IrVariable -> context.putStatefulVariable(copiedStatement)
                            is IrSetValue -> context.putSet(copiedStatement)
                            else -> throw AutoDiffException("Encountered a constant statement of type ${original.render()}")
                        }
                        if (original is IrValueDeclaration && copiedStatement is IrVariable) {
                            primalToTargetVariables[original] = copiedStatement as IrValueDeclaration
                            if (context.isTopLevel() && original.isImmutable) {
                                val targetProperty = propertyGenerator.generateProperty(original.name, type, generatedClass, false, null)
                                primalToUnwrappedProperties[original] = targetProperty
                                if (copiedStatement.initializer == null) {
                                    lateInitMap.add(original)
                                } else {
                                    val setField = callGenerator.generateSetField(targetProperty.backingField!!, callGenerator.generateGetValue(copiedStatement), generatedClass)
                                    context.putSetField(setField)
                                }
                            } else {
                                localMap.add(original)
                            }
                        }
                        null
                    }
                    is WhenStatement -> {
                        val branches = current.children.map { generateTargetCodeForPrimalStatement(it) as IrBranch }
                        val whenStatement = IrWhenImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.original.type, null, branches)
                        context.putWhen(whenStatement)
                        null
                    }
                    is LoopStatement -> {
                        val child = generateTargetCodeForPrimalStatement(current.body) as IrBlock
                        val decision = addDecision(current.identifier)
                        child.statements.add(decision)
                        val whileStatement = IrWhileLoopImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.original.type, current.original.origin)
                        whileStatement.condition = CopyAndReplacer(
                            ScopeSubstitutionMapSubstitutor(primalToTargetVariables),
                            Substitutor.emptySubstitutor(),
                            pluginContext.irBuiltIns
                        )
                            .copyAndReplace(current.original.condition, ReplaceDelegate.emptyReplacer, generatedClass) as IrExpression
                        whileStatement.body = child
                        context.putLoop(whileStatement)
                        null
                    }
                    is AbstractBlockStatement -> {
                        when (current) {
                            is BlockStatement -> {
                                if (!current.isVirtual) {
                                    context.pushScope()
                                    primalToTargetVariables.push()
                                    current.children.forEach { generateTargetCodeForPrimalStatement(it) }
                                    if (current.type != pluginContext.irBuiltIns.unitType) {
                                        // HACK: everything in the primal should be mapped into a variable in the target
                                        val variableToReturn = context.topStatements().reversed().first {
                                            when (it) {
                                                is IrValueDeclaration -> it.type == current.type
                                                else -> false
                                            }
                                        } as IrValueDeclaration
                                        val finalStatement = callGenerator.generateGetValue(variableToReturn)
                                        context.putGet(finalStatement)
                                    }
                                    primalToTargetVariables.pop()
                                    IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, null, context.popScope())
                                } else {
                                    current.children.forEach { generateTargetCodeForPrimalStatement(it) }
                                    null
                                }
                            }
                            is BlockBodyStatement -> {
                                primalToTargetVariables.push()
                                context.pushScope()
                                // initialize the unwrapping
                                primalToTargetReverseNodeProperty.entries.filter { it.key is IrValueParameter }.forEach {
                                    val getPropertyMaybe = callGenerator.generateVal(
                                        it.value.name,
                                        generatedClass,
                                        callGenerator.generateGetProperty(it.value, callGenerator.generateGetValue(generatedClass.thisReceiver!!))
                                    )
                                    val getProperty = context.tryPutStatelessVariable(getPropertyMaybe)
                                    val targetVariable = if (getProperty.type.classifierOrFail.owner == differentiableApi.reverseDiffScalarClass.clazz) {
                                        val getPrimalMaybe = callGenerator.generateVal(
                                            Name.identifier("${it.value.name}_primal_$variableCounter"),
                                            generatedClass,
                                            callGenerator.generateGetProperty(callGenerator.generateGetValue(getProperty), differentiableApi.reverseDiffScalarClass.primalProperty.name)
                                        )
                                        context.tryPutStatelessVariable(getPrimalMaybe)
                                    } else {
                                        getProperty
                                    }
                                    primalToTargetVariables[it.key] = targetVariable
                                }
                                current.children.forEach { generateTargetCodeForPrimalStatement(it) }
                                primalToTargetVariables.pop()
                                IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.popScope())
                            }
                        }
                    }
                    is ConditionBlock -> {
                        val block = generateTargetCodeForPrimalStatement(current.result) as IrBlock
                        val copiedCondition = CopyAndReplacer(
                            ScopeSubstitutionMapSubstitutor(primalToTargetVariables),
                            Substitutor.emptySubstitutor(),
                            pluginContext.irBuiltIns
                        )
                            .copyAndReplace(current.condition, ReplaceDelegate.emptyReplacer, generatedClass) as IrExpression
                        copiedCondition.acceptVoid(object : IrElementVisitorVoid {
                            override fun visitElement(element: IrElement) {
                                element.acceptChildrenVoid(this)
                            }
                        })
                        block.statements.add(addDecision(current.index))
                        IrBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, copiedCondition, block)
                    }
                    is ReturnStatement -> {
                        val primalProperty = generatedClass.properties.first { it.getter?.overriddenSymbols?.contains(differentiableApi.reverseDiffScalarClass.primalProperty.getter!!.symbol) == true }
                        val primalTargetVariable = primalToTargetVariables[current.valueDeclaration]!!
                        if (differentiableApi.reverseDiffScalarClass.setPrimal != null) {
                            val setPrimalOverride = generatedClass.simpleFunctions().firstOrNull { it.overrides(differentiableApi.reverseDiffScalarClass.setPrimal) } ?: throw AutoDiffException("The generator failed because the fake override for ${differentiableApi.reverseDiffScalarClass.setPrimal.name} was not generated")
                            context.putCall(
                                callGenerator.generateCall(
                                    toFunction = setPrimalOverride,
                                    withArguments = listOf(callGenerator.generateGetValue(primalTargetVariable)),
                                    dispatchReciever = callGenerator.generateGetValue(generatedClass.thisReceiver!!)
                                )
                            )
                        } else {
                            context.putSetField(callGenerator.generateSetField(primalProperty.backingField!!, callGenerator.generateGetValue(primalTargetVariable), generatedClass))
                        }
                        null
                    }
                    else -> {
                        throw NotImplementedError("unrecognized statement in primal encountered.")
                    }
                }
            }
            anonymousInitializer.body = generateTargetCodeForPrimalStatement(primalFunction.body) as IrBlockBody
            generatedClass.declarations.add(anonymousInitializer)
            return primalToUnwrappedProperties
        }

        val reverseNodeProperties = initializeProperties()
        val primalToTargetProperties = populateInitializer(reverseNodeProperties)
        populateBackpropMethod(primalToTargetProperties, reverseNodeProperties)
    }

    private var variableCounter = 0

    // HACK
    private fun popExpression(dispatchReceiver: IrExpression): IrExpression {
        return callGenerator.generateCall(
            stackClass.popMethod as IrSimpleFunction,
            listOf(),
            dispatchReceiver
        ).also { it.type = (dispatchReceiver.type as IrSimpleType).arguments.first().typeOrNull!! }
    }
}
