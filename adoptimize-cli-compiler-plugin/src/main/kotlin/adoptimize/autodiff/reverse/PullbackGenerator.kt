/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.reverse

import adoptimize.AutoDiffException
import adoptimize.autodiff.AutoDiffCodeWriterVendor
import adoptimize.autodiff.BackPropFunction.DiffIRCreator
import adoptimize.autodiff.GuardedScope
import adoptimize.autodiff.Metadata.StackClass
import adoptimize.autodiff.Primal
import adoptimize.autodiff.PrimalAndPullback
import adoptimize.autodiff.correctSpecializedNames
import adoptimize.autodiff.diffIR.*
import adoptimize.autodiff.plusFunction
import adoptimize.autodiff.zero
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import pluginCommon.*
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.IrFunctionGenerator
import pluginCommon.generators.ParameterInfo
import pluginCommon.generators.allParameters

// This version does not consider 'unwrapped' types.
class PullbackGenerator(
    val callGenerator: IrBodyGenerator,
    val functionGenerator: IrFunctionGenerator,
    val pluginContext: IrPluginContext,
    val codeWriterVendor: AutoDiffCodeWriterVendor,
    val stackClass: StackClass
) {
    val integerEqEq by lazy {
        pluginContext.irBuiltIns.intClass.owner.functions.first { it.isOperator && it.name.toString().contains("equals") }
    }

    fun createPullback(parent: IrDeclarationParent, primalFunction: DiffIRFunction): IrSimpleFunction {
        // create the pullback. Input is the same as the primal plus upstream. Output is a struct of the tangent types of the inputs. For Now we will assume that the
        // tangent types are always the same type and we will also assume that a parameter is active if it inherits from the differentiable interface.
        val parameters = primalFunction.getParameters()
        val activeParameters = primalFunction.diffIrParameters.filter { it.isActive }
        if (activeParameters.size > 1) {
            throw AutoDiffException("Only a single active derivative is currently supported")
        }
        // if active members are not primitives, the tangent type must be constructable from the tangent types of the members of the active type. I will not attempt to implement this
        // now but should be done along with class support
        val tangentType = activeParameters.first().tangentType
        // TODO: require that the tangent type is a dscalar
        if (!tangentType.isFloat()) {
            throw AutoDiffException("Classes not supported yet")
        }

        fun _pullbackPopulator(pullback: IrSimpleFunction) {
            val localMap = mutableSetOf<IrValueDeclaration>()
            val runtimeMap = mutableMapOf<IrValueDeclaration, IrVariable>()
            val codeWriter = codeWriterVendor.codeWriter(primalFunction)
            val context = GuardedScope().also { it.pushScope() }
            val primalToTargetVariables = ScopeSubstitutionMap().also { it.push() }
            primalFunction.getParameters().zip(pullback.allParameters().subList(0, primalFunction.getParameters().size)).forEach {
                primalToTargetVariables[it.first] = it.second
            }
            // Initialize control flow data structures
            val intermediateVariables = callGenerator.generateIrVariable(
                name = Name.identifier("\$intermediateValues"),
                containingDeclaration = pullback,
                initializer = callGenerator.generateConstructorCall(stackClass.clazz, listOf(), listOf(pluginContext.irBuiltIns.anyType as IrSimpleTypeImpl)),
                isVar = false
            )
            context.putStatefulVariable(intermediateVariables)
            val decisionsStack = callGenerator.generateIrVariable(
                name = Name.identifier("\$decisions"),
                containingDeclaration = pullback,
                initializer = callGenerator.generateConstructorCall(stackClass.clazz, listOf(), listOf(pluginContext.irBuiltIns.intType as IrSimpleTypeImpl)),
                isVar = false
            )
            context.putStatefulVariable(decisionsStack)
            val primalToTarget = mutableMapOf<IrValueDeclaration, IrValueDeclaration>()
            val valueParameters = primalFunction.getParameters()
            valueParameters.zip(pullback.valueParameters.subList(0, valueParameters.size)).forEach {
                primalToTarget.put(it.first, it.second)
            }

            // FORWARDS
            var primal: IrValueDeclaration? = null
            val addDecision: (Int) -> IrCall = { decision ->
                val argument = IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, pluginContext.irBuiltIns.intType, decision)
                callGenerator.generateCall(stackClass.pushMethod as IrSimpleFunction, listOf(argument), callGenerator.generateGetValue(decisionsStack))
            }
            val pushIntermediate: (IrValueDeclaration) -> IrCall = { intermediateValue ->
                callGenerator.generateCall(stackClass.pushMethod as IrSimpleFunction, listOf(callGenerator.generateGetValue(intermediateValue)), callGenerator.generateGetValue(intermediateVariables))
            }
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
                        when (val writtenDeclarations = codeWriter.writeInitCodeForLeaf(current, primalToTargetVariables, context, pullback)) {
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
                        primalToTargetVariables[persistedValueSrc] = primalImage
                        if (pullBackImage != null) {
                            if (!(context.isTopLevel() && persistedValueSrc.isImmutable)) {
                                context.putCall(pushIntermediate(pullBackImage))
                            }
                            runtimeMap[persistedValueSrc] = pullBackImage as IrVariable
                        }
                        null
                    }
                    is SetValue -> {
                        val copiedStatement = CopyAndReplacer(
                            ScopeSubstitutionMapSubstitutor(primalToTargetVariables),
                            Substitutor.emptySubstitutor(),
                            pluginContext.irBuiltIns
                        )
                            .copyAndReplace(current.original, ReplaceDelegate.emptyReplacer, pullback) as IrSetValue
                        // TODO: in the original version a property was set here if the current.setVariable was in the lateInitMap
                        //  but since there are no properties I think we do nothing?
                        context.putSet(copiedStatement)
                        null
                    }
                    is DiffIRAtom -> {
                        val original = current.original
                        val copiedStatement = CopyAndReplacer(
                            ScopeSubstitutionMapSubstitutor(primalToTargetVariables),
                            Substitutor.emptySubstitutor(),
                            pluginContext.irBuiltIns
                        )
                            .copyAndReplace(original, ReplaceDelegate.emptyReplacer, pullback)
                        when (copiedStatement) {
                            is IrCall -> context.putCall(copiedStatement)
                            is IrVariable -> context.putStatefulVariable(copiedStatement)
                            is IrSetValue -> context.putSet(copiedStatement)
                            else -> throw AutoDiffException("Encountered a constant statement of type ${original.render()}")
                        }
                        if (original is IrValueDeclaration && copiedStatement is IrVariable) {
                            primalToTargetVariables[original] = copiedStatement as IrValueDeclaration
                            if (!(context.isTopLevel() && original.isImmutable)) {
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
                            .copyAndReplace(current.original.condition, ReplaceDelegate.emptyReplacer, pullback) as IrExpression
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
                                throw AutoDiffException("Block bodies not supported yet in pullback generation because since a single body is used for both forward and backward, the scope should be handled outside of the forwards code generation.")
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
                            .copyAndReplace(current.condition, ReplaceDelegate.emptyReplacer, pullback) as IrExpression
                        copiedCondition.acceptVoid(object : IrElementVisitorVoid {
                            override fun visitElement(element: IrElement) {
                                element.acceptChildrenVoid(this)
                            }
                        })
                        block.statements.add(addDecision(current.index))
                        IrBranchImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, copiedCondition, block)
                    }
                    is ReturnStatement -> {
                        primal = primalToTargetVariables[current.valueDeclaration]!!
                        null
                    }
                    else -> {
                        throw NotImplementedError("unrecognized statement in primal encountered.")
                    }
                }
            }
            primalFunction.body.children.forEach { generateTargetCodeForPrimalStatement(it) }

            // REVERSE
            val upstreamParameter = pullback.valueParameters.last()
            val decisions = java.util.ArrayDeque<IrVariable>()
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
                            plusFunction(derivative.type, contribution.type),
                            listOf(callGenerator.generateGetValue(derivative)),
                            null,
                            callGenerator.generateGetValue(contribution)
                        )
                    )
                }
            }
            val popInteremediate: (String, IrType) -> IrVariable = { name, type ->
                val dispatchReceiver = callGenerator.generateGetValue(intermediateVariables)
                val popCall = popExpression(dispatchReceiver)
                val cast = callGenerator.generateCast(popCall, type)
                callGenerator.generateVal(Name.identifier("${name}_local_${variableCounter++}"), pullback, cast)
            }
            data class LocalInit(val localPrimal: IrValueDeclaration?, val initialDerivative: IrVariable?)
            fun initializeLocalTargetVariablesForPrimalValue(primalValueDiffIR: DiffIRAtom): LocalInit {
                // TODO: is 'isActive' ever false?
                val (primalValue, isActive) = when (primalValueDiffIR) {
                    is ActiveVariable -> {
                        Pair(primalValueDiffIR.original, true)
                    }
                    is SetValue -> {
                        Pair(primalValueDiffIR.setVariable, primalValueDiffIR.rhsIsActive)
                    }
                    is DiffIRParameter -> {
                        Pair(primalValueDiffIR.original, primalValueDiffIR.isActive)
                    }
                    else -> throw AutoDiffException("Not quite supported in reverse: $primalValueDiffIR")
                }
                val isTargetClassPrimalValue = primalValue == primalFunction.returnedVariable
                val prefix = if (primalValue.name.isSpecial) correctSpecializedNames(primalValue.name.toString()) else primalValue.name.toString()
                val targetVariable = primalToTargetVariables[primalValue]

                // This should only be done for active variables! For now let's assume it's active if its ReverseNode type or supertype
                val derivative: IrVariable?
                if (!isTargetClassPrimalValue && isActive) {
                    derivative = callGenerator.generateVariable(Name.identifier(DiffIRCreator.gradientVariableName(prefix)), pullback, tangentType.zero())
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
                        val targetAndDerivative = initializeLocalTargetVariablesForPrimalValue(child)
                        targetAndDerivative.localPrimal?.let { primalToTargetVariables.set(variable, it) }
                        targetAndDerivative.initialDerivative?.let { upstreamDerivatives[variable] = it }
                    }
                    is SetValue -> {
                        if (child.setVariable.initializer is IrGetValue && !upstreamDerivatives.contains(child.setVariable)) {
                            val targetAndDerivative = initializeLocalTargetVariablesForPrimalValue(child)
                            targetAndDerivative.localPrimal?.let { primalToTargetVariables.set(child.setVariable, it) }
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
            fun generateCodeForStatement(current: DiffIRStatement): IrElement? {
                return when (current) {
                    is CallVariable -> {
                        val upstreamKey = current.original
                        val upstreamDerivative = upstreamDerivatives[upstreamKey]
                        // pop the intermediates. Start with the pullback
                        val runtimePullback: IrValueDeclaration? = if (runtimeMap.containsKey(current.original)) {
                            if (localMap.contains(current.original)) {
                                val localVar = popInteremediate("${current.name}_pb", (runtimeMap[current.original]!! as IrValueDeclaration).type)
                                context.putStatefulVariable(localVar)
                                localVar
                            } else {
                                val localVar = context.tryPutStatelessVariable(callGenerator.generateVal(Name.identifier("temp${variableCounter++}"), pullback, callGenerator.generateGetValue(runtimeMap[current.original]!!)))
                                localVar
                            }
                        } else null

                        // all arguments to the differentiable statement should exist in the primalToLocal before backpropping
                        current.callInfo.allDiffIrArguments().filter { it.isReferencedInBackprop }.map { it.argument }.reversed().forEach {
                            if (!it.isImmutable || localMap.contains(it)) {
                                val targetVariable = popInteremediate(it.name.toString(), it.type)
                                primalToTargetVariables[it] = targetVariable
                                context.putStatefulVariable(targetVariable)
                            }
                        }
                        val derivativeContributions = codeWriter.writeBackpropCodeForLeaf(
                            current,
                            primalToTargetVariables,
                            upstreamDerivative,
                            pullback,
                            context,
                            runtimePullback
                        )

                        derivativeContributions.groupBy({ pair -> pair.first },).forEach { contributionListEntry: Map.Entry<IrValueDeclaration, List<Pair<IrValueDeclaration, IrValueDeclaration>>> ->
                            val componentsToAdd: List<IrValueDeclaration> = contributionListEntry.value.map { it.second }
                            val primalValue = contributionListEntry.key
                            val derivativeVariable = upstreamDerivatives[primalValue]
                            val updatedDerivativeExpression: IrExpression = componentsToAdd.fold(
                                initial = callGenerator.generateGetValue(derivativeVariable),
                                operation = { thusFar: IrExpression, next: IrValueDeclaration ->
                                    callGenerator.generateCall(
                                        plusFunction(thusFar.type, next.type),
                                        listOf(thusFar),
                                        null,
                                        callGenerator.generateGetValue(next)
                                    )
                                }
                            )
                            val updateOldDerivative = callGenerator.generateSetVariable(derivativeVariable, updatedDerivativeExpression)
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
                        if (current.rhsIsActive) {
                            val lhsDerivative = upstreamDerivatives[current.setVariable]
                            val updatedDerivative = upstreamDerivatives.updateDerivative(current.referencedVariable, lhsDerivative)
                            context.putSet(updatedDerivative)
                            context.putSet(callGenerator.generateSetVariable(lhsDerivative, tangentType.zero()))
                        }
                        null
                    }
                    is WhenStatement -> {
                        // pop decision
                        val dispatchReceiver = callGenerator.generateGetValue(decisionsStack)
                        val popCall = popExpression(dispatchReceiver)
                        val decision = callGenerator.generateVal(Name.identifier("decisions_${variableCounter++}"), pullback, popCall)

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
                            callGenerator.generateGetValue(decisionsStack)
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
                        val popCall = popExpression(getDecisions())
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
                        when (current) {
                            is BlockStatement -> {
                                if (!current.isVirtual) {
                                    upstreamDerivatives.push()
                                    context.pushScope()
                                    primalToTargetVariables.push()
                                    current.children.reversed().forEach { initializeLocalAndInitialDerivativeVariables(it) }
                                    current.children.reversed().forEach { generateCodeForStatement(it) }
                                    primalToTargetVariables.pop()
                                    upstreamDerivatives.pop()
                                    IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, current.type, null, context.popScope())
                                } else {
                                    current.children.reversed().forEach { generateCodeForStatement(it) }
                                    null
                                }
                            }
                            is BlockBodyStatement -> {
                                throw AutoDiffException("Block bodies not supported yet in pullback generation because since a single body is used for both forward and backward, the scope should be handled outside of the forwards code generation.")
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
                        val rootUpstream = callGenerator.generateVal(
                            upstreamParameter.name,
                            pullback,
                            callGenerator.generateGetValue(upstreamParameter)
                        )
                        context.putStatefulVariable(rootUpstream)
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
            upstreamDerivatives.push()
            primalFunction.diffIrParameters.forEach { primalValue ->
                val targetAndDerivative = initializeLocalTargetVariablesForPrimalValue(primalValue)
                targetAndDerivative.localPrimal?.let { primalToTargetVariables.set(primalValue.original, it) }
                targetAndDerivative.initialDerivative?.let { initializedDerivative ->
                    upstreamDerivatives[primalValue.original] = initializedDerivative
                }
            }
            primalFunction.body.children.reversed().forEach { initializeLocalAndInitialDerivativeVariables(it) }
            primalFunction.body.children.reversed().forEach { generateCodeForStatement(it) }
            primalToTargetVariables.pop()
            require(activeParameters.size == 1)
            val derivativeToReturn = upstreamDerivatives[activeParameters.first().original]
            upstreamDerivatives.pop()

            val returnStatement = IrReturnImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, tangentType, pullback.symbol, callGenerator.generateGetValue(derivativeToReturn))
            pullback.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.popScope() + returnStatement)
        }

        return functionGenerator.generateFunction(
            name = Name.identifier("${primalFunction.original.name}PB"),
            parent = parent,
            parameters = parameters.map { ParameterInfo(it.name, it.type) } + ParameterInfo(Name.identifier("upstream"), tangentType),
            returnType = tangentType,
            build = { fnc ->
                _pullbackPopulator(fnc as IrSimpleFunction)
            }
        )
    }

    private var variableCounter = 0

    private fun popExpression(dispatchReceiver: IrExpression): IrExpression {
        require(dispatchReceiver.type.classOrNull?.owner == stackClass.clazz)
        return callGenerator.generateCall(
            stackClass.popMethod as IrSimpleFunction,
            listOf(),
            dispatchReceiver
        ).also { it.type = (dispatchReceiver.type as IrSimpleType).arguments.first().typeOrNull!! }
    }
}
