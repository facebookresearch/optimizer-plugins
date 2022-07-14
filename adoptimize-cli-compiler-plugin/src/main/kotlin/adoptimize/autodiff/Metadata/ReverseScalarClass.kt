/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.Metadata

import adoptimize.AutoDiffException
import adoptimize.autodiff.BackPropFunction.StackOperation
import adoptimize.autodiff.getVariable
import adoptimize.autodiff.type
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.Name
import pluginCommon.CopyAndReplacer
import pluginCommon.ReplaceDelegate
import pluginCommon.Substitutor
import pluginCommon.generators.IrPropertyGenerator
import pluginCommon.generators.overrideRoot
import java.lang.IllegalStateException

enum class ParamMapType { Unbox, CastToReverse, NoOp, ForwardPrimal, ConstPrimalPrimal }
enum class ActiveParameterRequirement { Reverse, Forward, Constant }
class ParameterMap(val functionIndex: Int, val type: ParamMapType, val correspondingPropertyName: Name?, val isActive: Boolean)

// https://github.com/facebookresearch/optimizer-plugins/issues/155
// We should be able to instantiate a mappable class from the value parameters of the parent function
class ReverseScalarClass(
    val parent: IrFunction,
    val clazz: IrClass,
    val parameterMaps: List<ParameterMap>,
    val activeProperties: List<IrProperty>,
    val intermediateVariables: IrProperty,
    val decisions: IrProperty,
    val activeInputTypeRequirements: List<ActiveParameterRequirement>
) {
    init {
        if (parent != clazz.parent) {
            throw IllegalStateException("The parent provided was not the parent inferred")
        }
    }

    private val popFunction = intermediateVariables.type().getClass()?.functions?.firstOrNull { it.name.toString() == "pop" } ?: throw AutoDiffException("Expected the intermediate variables to contain a pop function")
    private val pushFunction = intermediateVariables.type().getClass()?.functions?.firstOrNull { it.name.toString() == "push" } ?: throw AutoDiffException("Expected the intermediate variables to contain a pop function")

    fun intermediateStateStackOperation(call: IrCall): StackOperation? {
        val callReceiver = call.dispatchReceiver ?: return null
        return when (val dispatchReceiver: IrValueDeclaration = getVariable(callReceiver)) {
            is IrVariable -> {
                val receiver = dispatchReceiver.initializer
                if (receiver is IrCall && receiver.symbol.owner.correspondingPropertySymbol?.owner == intermediateVariables) {
                    when {
                        call.symbol.owner == popFunction -> StackOperation.Pop
                        call.symbol.owner == pushFunction -> StackOperation.Push
                        else -> null
                    }
                } else null
            }
            else -> null
        }
    }
}

fun matchProperties(source: ReverseScalarClass, target: ReverseScalarClass, propertyGenerator: IrPropertyGenerator): Map<IrProperty, IrProperty> {
    val copyAndReplacer = CopyAndReplacer(Substitutor.emptySubstitutor(), Substitutor.emptySubstitutor(), propertyGenerator.pluginContext.irBuiltIns)
    if (source.parent != target.parent) {
        throw AutoDiffException("The source mappable class cannot be used to populate the target mappable class because they must have the same parent. Source parent: ${source.parent.name}, target parent: ${target.parent.name}")
    }
    return source.clazz.properties.map { reverseProperty ->
        val srcParameterMap = source.parameterMaps.firstOrNull { it.correspondingPropertyName == reverseProperty.name }
        val image: IrProperty = when {
            reverseProperty == source.intermediateVariables -> target.intermediateVariables
            reverseProperty == source.decisions -> target.decisions
            srcParameterMap != null -> {
                val targetParamMapCandidates = target.parameterMaps.filter { it.functionIndex == srcParameterMap.functionIndex }
                val targetParameterMap = when {
                    targetParamMapCandidates.size == 1 -> targetParamMapCandidates.first()
                    srcParameterMap.type == ParamMapType.Unbox -> targetParamMapCandidates.firstOrNull { it.type == ParamMapType.ConstPrimalPrimal }
                    else -> targetParamMapCandidates.firstOrNull { it.type == srcParameterMap.type }
                } ?: throw AutoDiffException("The parameter maps of the reverse forward should be a super set of the reverse. Problem: ${srcParameterMap.correspondingPropertyName}")
                target.clazz.properties.first { it.name == targetParameterMap.correspondingPropertyName }
            }
            reverseProperty.isFakeOverride -> target.clazz.properties.first { it.getter!!.overrideRoot() == reverseProperty.getter!!.overrideRoot() }
            else -> {
                val existingPropertyMaybe = target.clazz.properties.firstOrNull { it.getter!!.overrideRoot() == reverseProperty.getter!!.overrideRoot() }
                when {
                    reverseProperty.isFakeOverride && existingPropertyMaybe != null -> existingPropertyMaybe
                    !reverseProperty.isFakeOverride && existingPropertyMaybe != null && existingPropertyMaybe.isFakeOverride -> {
                        target.clazz.declarations.remove(existingPropertyMaybe)
                        propertyGenerator.duplicateProperty(
                            reverseProperty,
                            target.clazz,
                            reverseProperty.backingField?.initializer?.expression?.let {
                                copyAndReplacer.copyAndReplace(it, ReplaceDelegate.emptyReplacer, target.clazz) as IrExpression
                            }
                        )
                    }
                    else -> {
                        propertyGenerator.duplicateProperty(
                            reverseProperty,
                            target.clazz,
                            reverseProperty.backingField?.initializer?.expression?.let {
                                copyAndReplacer.copyAndReplace(it, ReplaceDelegate.emptyReplacer, target.clazz) as IrExpression
                            }
                        )
                    }
                }
            }
        }
        Pair(reverseProperty, image)
    }.toMap()
}

fun ReverseScalarClass.forwardsPropertyName(activePropertyName: Name): Name {
    val targetPropertyParameterMapCandidates = parameterMaps.filter { it.correspondingPropertyName == activePropertyName }
    if (targetPropertyParameterMapCandidates.size != 1) {
        throw AutoDiffException("Expected exactly one parameter map associated with $activePropertyName")
    }
    val targetPropertyParameterMap = targetPropertyParameterMapCandidates.first()
    val correspondingForwardCandidates = parameterMaps.filter { it.functionIndex == targetPropertyParameterMap.functionIndex && it.type == ParamMapType.ForwardPrimal }
    if (correspondingForwardCandidates.size != 1) {
        throw AutoDiffException("Expected a forward associated with active property $activePropertyName")
    }
    return correspondingForwardCandidates.first().correspondingPropertyName ?: throw AutoDiffException("the forward parameter was not added as a property")
}
