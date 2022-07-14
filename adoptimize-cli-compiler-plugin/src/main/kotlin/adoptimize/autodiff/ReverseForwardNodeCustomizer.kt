/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff

import adoptimize.AutoDiffException
import adoptimize.autodiff.BackPropFunction.DiffIRCreator
import adoptimize.autodiff.Metadata.ActiveParameterRequirement
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.ParamMapType
import adoptimize.autodiff.Metadata.ParameterMap
import adoptimize.autodiff.Metadata.ReverseScalarClass
import adoptimize.autodiff.Metadata.StackClass
import adoptimize.autodiff.Metadata.forwardsPropertyName
import adoptimize.autodiff.Metadata.matchProperties
import adoptimize.autodiff.diffIR.DiffIRFunction
import adoptimize.autodiff.diffIR.PopIntermediateStateVariable
import adoptimize.autodiff.diffIR.SetField
import adoptimize.autodiff.forwards.TangentRecorder
import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrAnonymousInitializerImpl
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrStatementContainer
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockBodyImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.Name
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.generators.IrPropertyGenerator
import pluginCommon.generators.ParameterInfo
import pluginCommon.generators.overrideRoot

// This class accepts an unboxed reverse scalar class and performs forward mode differentiation over the initializer
// and the backpropagation method. A forward mode differentiator is used to collect the differentiable code.
// Any operation that alters the state of the optimized class is contained in this class
class ReverseForwardNodeCustomizer(
    val propertyGenerator: IrPropertyGenerator,
    val bodyGenerator: IrBodyGenerator,
    val diffRepCreator: DiffIRCreator,
    val differentiableApi: DifferentiableApi,
    val reverseScalarClass: ReverseScalarClass,
    val forwardModePopulator: ForwardModePopulator,
    val stackClass: StackClass
) : ReverseNodeCustomizer {
    private fun ReverseScalarClass.backpropogateMethod(): IrSimpleFunction =
        this.clazz.simpleFunctions().firstOrNull { it.overrideRoot() == differentiableApi.reverseDiffScalarClass.backpropMethod.overrideRoot() }
            ?: throw AutoDiffException("Internal error: There should be a backprop method in a mappable class ${this.clazz.name}")

    override fun name(primalName: String): String = "${primalName}ReverseForward"

    override fun typeRequirements(): List<ActiveParameterRequirement> = listOf(ActiveParameterRequirement.Reverse, ActiveParameterRequirement.Forward, ActiveParameterRequirement.Constant)

    override fun populate(primalFunction: DiffIRFunction, shellClass: ReverseScalarClass) {
        val anonymousInits = reverseScalarClass.clazz.declarations.filterIsInstance<IrAnonymousInitializer>()
        if (reverseScalarClass.activeProperties.isEmpty()) {
            throw AutoDiffException("In order to forward differentiate the reverse node, there must be at least one reverse active property.")
        }
        val srcActiveProperties = reverseScalarClass.activeProperties.toSet().toMutableSet()
        val srcPropertyToTargetProperty = matchProperties(reverseScalarClass, shellClass, propertyGenerator)

        val forwardPrimalPropertyName = shellClass.parameterMaps.first { it.type == ParamMapType.ForwardPrimal }.correspondingPropertyName ?: throw AutoDiffException("Expected the forward parameter to be saved as a property")
        fun getForwardDerivativeID(receiver: IrValueDeclaration): IrExpression {
            val getForward = bodyGenerator.generateGetProperty(bodyGenerator.generateGetValue(receiver), forwardPrimalPropertyName)
            return bodyGenerator.generateGetProperty(getForward, differentiableApi.forwardDiffScalarClass.derivativeId.name)
        }

        val tangentRecorder = TangentRecorder()
        // initialize tangents: create a tangent property for each input active property
        srcActiveProperties.forEach {
            val targetProperty = srcPropertyToTargetProperty[it]!!
            val getTangent = bodyGenerator.generateGetProperty(
                dispatchReceiver = bodyGenerator.generateGetProperty(
                    dispatchReceiver = bodyGenerator.generateGetValue(shellClass.clazz.thisReceiver!!),
                    propertyName = shellClass.forwardsPropertyName(targetProperty.name)
                ),
                propertyName = differentiableApi.forwardDiffScalarClass.tangentProperty.name
            )
            val tangentAsPrimitive = bodyGenerator.generateGetProperty(
                dispatchReceiver = bodyGenerator.generateCast(
                    expressionToCast = getTangent,
                    newType = differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass.defaultType
                ),
                propertyName = differentiableApi.boxedPrimitiveInfo.valueProperty.name
            )
            val tangentProperty = propertyGenerator.generateProperty(Name.identifier("${targetProperty.name}_tangent"), tangentAsPrimitive.type, shellClass.clazz, false, tangentAsPrimitive)
            tangentRecorder[targetProperty] = tangentProperty
        }
        val tangentsStack: IrProperty = makeStackProperty(shellClass.clazz, shellClass.activeProperties.first().type() as IrSimpleTypeImpl, "\$tangentsStack")

        // differentiate the initializer and save tangents as properties
        if (anonymousInits.size == 1) {
            val srcBlock = anonymousInits.first().body
            val (diffIR, _) = diffRepCreator.createDifferentiableBlock(srcBlock, emptySet(), srcActiveProperties, { c -> reverseScalarClass.intermediateStateStackOperation(c) })
            val diffStatements = forwardModePopulator.forwardMode(
                differentiableBlock = diffIR,
                srcPropertyToTargetPropertyMap = srcPropertyToTargetProperty,
                srcValueDeclarationsToTargetValueDeclarations = mapOf<IrValueDeclaration, IrValueDeclaration>(reverseScalarClass.clazz.thisReceiver!! to shellClass.clazz.thisReceiver!!),
                delegate = object : ForwardModeDelegate {
                    override fun didAddTangent(
                        newTangentLocalVariable: IrVariable,
                        targetVariable: IrVariable,
                        parent: IrStatementContainer
                    ): List<IrStatement> {
                        // store tangent in the class state either by creating a property or pushing the variable to the intermediate stack
                        if (parent == srcBlock && !targetVariable.isVar) {
                            val tangentProperty = propertyGenerator.generateProperty(
                                newTangentLocalVariable.name,
                                newTangentLocalVariable.type,
                                shellClass.clazz,
                                false,
                                null,
                                null
                            )
                            val setField = bodyGenerator.generateSetField(tangentProperty.backingField!!, bodyGenerator.generateGetValue(newTangentLocalVariable), shellClass.clazz)
                            tangentRecorder[targetVariable] = tangentProperty
                            return listOf(setField)
                        } else {
                            return emptyList()
                        }
                    }

                    override fun didPushIntermediateState(targetTangent: IrValueDeclaration): List<IrStatement> {
                        val dispatchReceiver = bodyGenerator.generateGetProperty(tangentsStack, bodyGenerator.generateGetValue(shellClass.clazz.thisReceiver!!))
                        val pushTangentCall = bodyGenerator.generateCall(stackClass.pushMethod as IrSimpleFunction, listOf(bodyGenerator.generateGetValue(targetTangent)), dispatchReceiver)
                        return listOf(pushTangentCall)
                    }

                    override fun didAddSetField(original: SetField, targetSetField: IrSetField) {
                        when {
                            differentiableApi.frameworkPropertyNames.contains(original.correspondingProperty.name) -> {
                                // we only care about intermediate compute properties; the primal value and framework properties should be ignored
                            }
                            else -> tangentRecorder[getVariable(targetSetField.value)]?.let { targetTangentProperty ->
                                val targetProperty = srcPropertyToTargetProperty[original.correspondingProperty]!!
                                srcActiveProperties.add(original.correspondingProperty)
                                tangentRecorder[targetProperty] = targetTangentProperty
                            }
                        }
                    }

                    override fun tangentForSrcProperty(activeProperty: IrProperty): IrValueDeclaration? {
                        return if (srcActiveProperties.contains(activeProperty)) {
                            val targetActiveProperty = srcPropertyToTargetProperty[activeProperty]!!
                            tangentRecorder[targetActiveProperty]?.let { tangentProperty ->
                                bodyGenerator.generateVal(
                                    tangentProperty.name,
                                    shellClass.clazz,
                                    bodyGenerator.generateGetProperty(bodyGenerator.generateGetValue(shellClass.clazz.thisReceiver!!), tangentProperty.name)
                                )
                            }
                        } else null
                    }
                },
                targetForwardID = { getForwardDerivativeID(shellClass.clazz.thisReceiver!!) },
                parent = shellClass.clazz
            )
            shellClass.clazz.declarations.add(
                IrAnonymousInitializerImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, IrDeclarationOrigin.DEFINED, IrAnonymousInitializerSymbolImpl(), false).also {
                    it.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, diffStatements)
                    it.parent = shellClass.clazz
                }
            )
        } else {
            throw AutoDiffException("Internal error: There should be a single anonymous initializer for generated local reverse nodes.")
        }

        // differentiate the backprop method
        val targetBackpropogateMethod = shellClass.backpropogateMethod()
        val modelBackpropogateMethod = reverseScalarClass.backpropogateMethod()
        var count = 0
        val (diffIR, _) = diffRepCreator.createDifferentiableBlock(modelBackpropogateMethod.body as IrBody, emptySet(), srcActiveProperties) { call ->
            reverseScalarClass.intermediateStateStackOperation(call)
        }

        val newBody = forwardModePopulator.forwardMode(
            differentiableBlock = diffIR,
            srcPropertyToTargetPropertyMap = srcPropertyToTargetProperty,
            srcValueDeclarationsToTargetValueDeclarations = mapOf<IrValueDeclaration, IrValueDeclaration>(modelBackpropogateMethod.dispatchReceiverParameter!! to targetBackpropogateMethod.dispatchReceiverParameter!!),
            delegate = object : ForwardModeDelegate {
                override fun tangentForIntermediateState(activeIntermediateState: PopIntermediateStateVariable): IrValueDeclaration? {
                    val dispatchReceiver = bodyGenerator.generateGetProperty(tangentsStack, bodyGenerator.generateGetValue(targetBackpropogateMethod.dispatchReceiverParameter!!))
                    val popCall = bodyGenerator.generateCall(
                        stackClass.popMethod as IrSimpleFunction,
                        listOf(),
                        dispatchReceiver
                    ).also { it.type = (dispatchReceiver.type as IrSimpleType).arguments.first().typeOrNull!! }
                    return bodyGenerator.generateVal(Name.identifier("${DiffIRCreator.tangentVariableName(activeIntermediateState.name)}_local_${count++}"), targetBackpropogateMethod, popCall)
                }

                override fun tangentForSrcProperty(activeProperty: IrProperty): IrValueDeclaration? {
                    val targetProperty = srcPropertyToTargetProperty[activeProperty] ?: return null
                    val tangentRef = tangentRecorder[targetProperty]?.let { tangentProperty ->
                        bodyGenerator.generateVal(
                            Name.identifier(DiffIRCreator.tangentVariableName(targetProperty.name)), targetBackpropogateMethod,
                            bodyGenerator.generateGetProperty(bodyGenerator.generateGetValue(targetBackpropogateMethod.dispatchReceiverParameter!!), tangentProperty.name)
                        )
                    }
                    return tangentRef
                }
            },
            targetForwardID = { getForwardDerivativeID(targetBackpropogateMethod.dispatchReceiverParameter!!) },
            parent = targetBackpropogateMethod,
        )
        targetBackpropogateMethod.body = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, newBody)
    }

    override fun buildParameterInfos(originValueParameterWithIndex: ParameterWithIndex): List<Pair<ParameterInfo, ParameterMap>> {
        val (originValueParameter, index) = originValueParameterWithIndex
        val parameterInfos = mutableListOf<Pair<ParameterInfo, ParameterMap>>()
        val reverseType = differentiableApi.reverseDiffScalarClass.clazz.defaultType
        val forwardType = differentiableApi.forwardDiffScalarClass.clazz.defaultType
        val primitiveType = differentiableApi.boxedPrimitiveInfo.primitiveType
        when {
            reverseType.isSubtypeOfClass(originValueParameter.type.getClass()!!.symbol) -> {
                val parameterInfo1 = ParameterInfo(Name.identifier("${originValueParameter.name}Reverse"), reverseType)
                val parameterInfo2 = ParameterInfo(Name.identifier("${originValueParameter.name}ForwardPrimal"), forwardType)
                val parameterInfo3 = ParameterInfo(Name.identifier("${originValueParameter.name}Primal"), primitiveType)

                val parameterMap1 = ParameterMap(index, ParamMapType.CastToReverse, parameterInfo1.name, false)
                val parameterMap2 = ParameterMap(index, ParamMapType.ForwardPrimal, parameterInfo2.name, false)
                val parameterMap3 = ParameterMap(index, ParamMapType.ConstPrimalPrimal, parameterInfo3.name, true)
                parameterInfos.add(Pair(parameterInfo1, parameterMap1))
                parameterInfos.add(Pair(parameterInfo2, parameterMap2))
                parameterInfos.add(Pair(parameterInfo3, parameterMap3))
            }
            originValueParameter.type.getClass() == differentiableApi.boxedPrimitiveInfo.boxedPrimitiveClass -> {
                val parameterInfo1 = ParameterInfo(Name.identifier("${originValueParameter.name}Unboxed"), primitiveType)
                val parameterMap1 = ParameterMap(index, ParamMapType.Unbox, parameterInfo1.name, false)
                parameterInfos.add(Pair(parameterInfo1, parameterMap1))
            }
            else -> {
                parameterInfos.add(
                    Pair(
                        ParameterInfo(originValueParameter.name, originValueParameter.type),
                        ParameterMap(index, ParamMapType.NoOp, originValueParameter.name, false)
                    )
                )
            }
        }
        return parameterInfos
    }

    private fun makeStackProperty(generatedClass: IrClass, type: IrSimpleTypeImpl, name: String): IrProperty {
        val typeArguments = listOf(type)
        val decisionType = IrSimpleTypeImpl(stackClass.clazz.symbol, false, typeArguments, emptyList(), null)
        val constructorCall = bodyGenerator.generateConstructorCall(stackClass.clazz, emptyList(), typeArguments)
        return propertyGenerator.generateProperty(Name.identifier(name), decisionType, generatedClass, false, constructorCall)
    }
}
