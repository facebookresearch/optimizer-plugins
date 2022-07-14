/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize

import adOptimizeCommon.stackImpl
import adoptimize.autodiff.BackPropFunction.DiffIRCreator
import adoptimize.autodiff.BoxedReverseNodeCustomizer
import adoptimize.autodiff.Metadata.BoxedPrimitiveInfo
import adoptimize.autodiff.Metadata.DifferentiableApi
import adoptimize.autodiff.Metadata.ForwardDifferentiableScalarMetadata
import adoptimize.autodiff.Metadata.ReverseDifferentiableScalarMetadata
import adoptimize.autodiff.Metadata.ReverseScalarClass
import adoptimize.autodiff.Metadata.StackClass
import adoptimize.autodiff.Metadata.isDifferentiableScalar
import adoptimize.autodiff.Metadata.primitiveOne
import adoptimize.autodiff.ParameterWithIndex
import adoptimize.autodiff.PrimalFunctionTransformer
import adoptimize.autodiff.ReverseForwardNodeCustomizer
import adoptimize.autodiff.ReverseScalarClassCreator
import adoptimize.autodiff.UnboxedReverseNodeCustomizer
import adoptimize.autodiff.allParametersWithIndex
import adoptimize.autodiff.diffIR.DiffIRFunction
import adoptimize.autodiff.isEqualTo
import adoptimize.autodiff.linker
import adoptimize.autodiff.reverse.PullbackGenerator
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.resolveClassByFqName
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.toKotlinType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf
import pluginCommon.generators.IrBodyGenerator
import pluginCommon.lowerings.ElseBranchLowering
import pluginCommon.lowerings.UnitCastTransformer
import pluginCommon.lowerings.UnnestLowering
import pluginCommon.lowerings.VariableWhenLowering
enum class DifferentiableOperation { FirstOrder, SecondOrder, FirstAndSecondOrder }
class ADOptimizeIrGenerationExtension(val properties: Map<String, String>, differentiableApiPackageName: String, optimize: String, secondOrderAnnotationNameOrEmpty: String, val failOnError: Boolean, reverseADFunctionName: String) : IrGenerationExtension {
    private val fqOptimizeAnnotationName = FqName(optimize)
    private val fqReverseADFunctionName = if (reverseADFunctionName.isEmpty()) null else FqName(reverseADFunctionName)
    private val fqSecondOrderAnnotationName = if (secondOrderAnnotationNameOrEmpty.isEmpty()) null else FqName(secondOrderAnnotationNameOrEmpty)
    private val differentiableApiPackageFqName = FqName(differentiableApiPackageName)

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val (differentiableApi, stackClass) = reverseInfoFromProperties(moduleFragment, pluginContext)
        val container = createAdOptimizeDependencyContainer(differentiableApi, stackClass, pluginContext)

        fun unboxedReverseNodeCustomizer(boxedReverseScalarClass: ReverseScalarClass): UnboxedReverseNodeCustomizer = container.get(boxedReverseScalarClass)
        fun reverseForwardNodeCustomizer(unboxedReverseScalarClass: ReverseScalarClass): ReverseForwardNodeCustomizer = container.get(unboxedReverseScalarClass)
        val primalFunctionTransformer: PrimalFunctionTransformer = container.get()
        val reverseScalarClassCreator: ReverseScalarClassCreator = container.get()
        val boxedReverseNodeCustomizer: BoxedReverseNodeCustomizer = container.get()
        val diffIRCreator: DiffIRCreator = container.get()
        val messageLogger = pluginContext.createDiagnosticReporter("ADOptimizer")
        val lowerings = listOf(
            container.get<VariableWhenLowering>(),
            container.get<UnnestLowering>(),
            container.get<ElseBranchLowering>(),
            container.get<UnitCastTransformer>()
        )

        val primalFunctions = mutableListOf<IrSimpleFunction>()
        val derivativeRequestCallsites = mutableListOf<IrVariable>()

        fun IrSimpleFunction.createMappleClasses(differentiableOperation: DifferentiableOperation, differentiableParameter: ParameterWithIndex): List<ReverseScalarClass> {
            lowerings.forEach { it.lower(this) }
            fun reverseAndUnwrappedReverseNestedClasses(backproppablePrimal: DiffIRFunction): Pair<ReverseScalarClass, ReverseScalarClass> {
                val boxedReverseMappableClass = reverseScalarClassCreator.createMappableReverseNode(
                    boxedReverseNodeCustomizer,
                    backproppablePrimal,
                    differentiableParameter.valueDescriptor
                )
                val unboxedReverseMappableClass = reverseScalarClassCreator.createMappableReverseNode(
                    unboxedReverseNodeCustomizer(boxedReverseMappableClass),
                    backproppablePrimal,
                    differentiableParameter.valueDescriptor
                )
                return Pair(boxedReverseMappableClass, unboxedReverseMappableClass)
            }
            val backproppablePrimal = diffIRCreator.createBackProppableFunction(this, differentiableParameter.index)
            return when (differentiableOperation) {
                DifferentiableOperation.FirstOrder -> {
                    val (boxed, unboxed) = reverseAndUnwrappedReverseNestedClasses(backproppablePrimal)
                    listOf(boxed, unboxed)
                }
                DifferentiableOperation.SecondOrder -> {
                    val (_, unboxed) = reverseAndUnwrappedReverseNestedClasses(backproppablePrimal)
                    val secondOrderNode = reverseScalarClassCreator.createMappableReverseNode(
                        reverseForwardNodeCustomizer(unboxed),
                        backproppablePrimal,
                        differentiableParameter.valueDescriptor
                    )
                    listOf(secondOrderNode)
                }
                DifferentiableOperation.FirstAndSecondOrder -> {
                    val (boxed, unboxed) = reverseAndUnwrappedReverseNestedClasses(backproppablePrimal)
                    val secondOrderNode = reverseScalarClassCreator.createMappableReverseNode(
                        reverseForwardNodeCustomizer(unboxed),
                        backproppablePrimal,
                        differentiableParameter.valueDescriptor
                    )
                    listOf(boxed, unboxed, secondOrderNode)
                }
            }
        }
        moduleFragment.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                fqReverseADFunctionName?.let {
                    if (expression.symbol.owner.hasAnnotation(fqReverseADFunctionName)) {
                        throw AutoDiffException("TODO: `$fqReverseADFunctionName` is only transformable in variables. Lower function bodies first to repair.")
                    }
                }
            }

            override fun visitVariable(declaration: IrVariable) {
                fqReverseADFunctionName?.let {
                    when (val init = declaration.initializer) {
                        is IrCall -> {
                            if (init.symbol.owner.hasAnnotation(fqReverseADFunctionName)) {
                                derivativeRequestCallsites.add(declaration)
                            }
                        }
                    }
                }
            }

            override fun visitFunction(declaration: IrFunction) {
                try {
                    val hasSecondOrderAnnotation = fqSecondOrderAnnotationName?.let { declaration.hasAnnotation(it) } ?: false
                    val operation = when {
                        declaration.hasAnnotation(fqOptimizeAnnotationName) && hasSecondOrderAnnotation -> DifferentiableOperation.FirstAndSecondOrder
                        declaration.hasAnnotation(fqOptimizeAnnotationName) -> DifferentiableOperation.FirstOrder
                        hasSecondOrderAnnotation -> DifferentiableOperation.SecondOrder
                        else -> null
                    }
                    val isNaked = declaration.allParametersWithIndex().none { differentiableApi.isDifferentiableScalar(it.valueDescriptor.type) }
                    if (isNaked && operation == DifferentiableOperation.FirstOrder && declaration is IrSimpleFunction) {
                        lowerings.forEach { it.lower(declaration) }
                        primalFunctions.add(declaration)
                    } else {
                        // Create DScalar optimization, compatible with framework, by transforming both primal and generating ReverseScalar implementation
                        operation?.let {
                            val differentiableParameters = declaration.allParametersWithIndex().filter { differentiableApi.isDifferentiableScalar(it.valueDescriptor.type) }
                            when (differentiableParameters.size) {
                                0 -> throw AutoDiffException("Cannot optimize ${declaration.name} because there are no differentiable parameters")
                                1 -> {
                                    val differentiableParameter = differentiableParameters.first()
                                    val simpleFunction = declaration as? IrSimpleFunction ?: throw AutoDiffException("Only Simple functions can be optimized. Ensure ${declaration.name} is a simple function and not a class contructor.")
                                    val optimizedClasses = simpleFunction.createMappleClasses(operation, differentiableParameter)
                                    primalFunctionTransformer.invokeOptimized(simpleFunction, differentiableParameter.index, optimizedClasses)
                                }
                                else -> throw AutoDiffException("Only single variable functions are supported for optimizations. ${declaration.name} contains multiple potentially differentiable parameters.")
                            }
                        }
                    }
                    declaration.body?.let { this.visitBody(it) }
                } catch (autoDiffException: AutoDiffException) {
                    val errorMessage = "AutoDiff optimization error on ${declaration.fqNameForIrSerialization}. " +
                        "Remove annotation to ignore optimization on this function. " +
                        "Error message: ${autoDiffException.message ?: ""}"
                    if (failOnError) {
                        throw AutoDiffException(errorMessage)
                    } else {
                        messageLogger.report(
                            IrMessageLogger.Severity.ERROR,
                            errorMessage,
                            null
                        )
                    }
                }
            }
        })

        // TODO: topologically sort the naked primals. Store dependency information during the traverse above
        val pullbackGenerator: PullbackGenerator = container.get()
        val callGenerator: IrBodyGenerator = container.get()
        val primalToPullback: Map<IrSimpleFunction, IrSimpleFunction> = primalFunctions.map { primal ->
            val diffIrFunction = diffIRCreator.createBackProppableFunction(primal, 0)
            val pullback = pullbackGenerator.createPullback(primal.parent, diffIrFunction)
            when (val parent = primal.parent) {
                is IrFile -> parent.declarations.add(pullback)
                is IrClass -> parent.declarations.add(pullback)
                else -> TODO()
            }
            Pair(primal, pullback)
        }.toMap()
        derivativeRequestCallsites.forEach {
            val call = it.initializer as IrCall
            val argument: IrExpression = call.getValueArgument(0)!!
            val primalFunction = call.getValueArgument(1)
            val newFunction = when (primalFunction) {
                is IrFunctionReference -> primalFunction.reflectionTarget!!.owner
                else -> throw AutoDiffException("Only function references supported at this stage in developement inside Differential Operators")
            }
            val pullback = primalToPullback[newFunction] ?: throw AutoDiffException("The primal does not have a pullback: ${newFunction.name}")
            it.initializer = callGenerator.generateCall(pullback, listOf(argument, differentiableApi.primitiveOne()), null)
        }
    }

    private fun valueForKey(key: String): String = properties[key] ?: throw AutoDiffException("The ad config file did not contain a value for the key $key")

    fun reverseInfoFromProperties(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext): Pair<DifferentiableApi, StackClass> {
        val apiPackage = moduleFragment.descriptor.getPackage(differentiableApiPackageFqName)
        fun getClassInDifferentiableApi(propertyName: String): IrClass {
            val fqName = valueForKey(propertyName)
            val clazzDescriptor = apiPackage.module.resolveClassByFqName(FqName(fqName), NoLookupLocation.FROM_BACKEND) ?: throw AutoDiffException("Could not resolve the class $fqName in $differentiableApiPackageFqName. Ensure the package matches the artifact")
            val clazzSymbol = pluginContext.symbolTable.referenceClass(clazzDescriptor)
            val irDeclaration: IrDeclaration = pluginContext.linker().getDeclaration(clazzSymbol) ?: throw AutoDiffException("Could not generate a stub for ${clazzSymbol.owner.name}")
            return irDeclaration as? IrClass ?: throw AutoDiffException("Expected a class stub for class symbol ${clazzSymbol.owner.name}")
        }
        fun getPropertyInDifferentiableApi(propertyName: String): IrProperty {
            val fqName = FqName(valueForKey(propertyName))
            val clazzDescriptor = apiPackage.module.resolveClassByFqName(fqName.parent(), NoLookupLocation.FROM_BACKEND) ?: throw AutoDiffException("Could not resolve the class $fqName in $differentiableApiPackageFqName. Ensure the package matches the artifact")
            val clazzSymbol = pluginContext.symbolTable.referenceClass(clazzDescriptor)
            val irDeclaration: IrDeclaration = pluginContext.linker().getDeclaration(clazzSymbol) ?: throw AutoDiffException("Could not generate a stub for ${clazzSymbol.owner.name}")
            val irClazz = irDeclaration as? IrClass ?: throw AutoDiffException("Expected a class stub for class symbol ${clazzSymbol.owner.name}")
            return irClazz.properties.firstOrNull { it.name == fqName.shortName() } ?: throw AutoDiffException("Expected a property ${fqName.shortName()} on ${clazzSymbol.owner.name}")
        }
        fun propertyOfClassWithKey(propertyName: String, clazz: IrClass): IrProperty {
            val propName = valueForKey(propertyName)
            return clazz.properties.firstOrNull { it.name.toString() == propName } ?: throw AutoDiffException("Could not find property $propName on class ${clazz.name}")
        }
        fun methodOfClassWithKey(propertyName: String, clazz: IrClass): IrSimpleFunction {
            val methodName = valueForKey(propertyName)
            return clazz.functions.firstOrNull { it.name.toString() == methodName } ?: throw AutoDiffException("Could not find method $methodName on class ${clazz.name}")
        }
        fun topLevelFunctionFromKey(key: String, additionalFilter: (FunctionDescriptor) -> Boolean): IrSimpleFunction {
            val fqName = FqName(this.valueForKey(key))
            val functionCandidates = apiPackage.module.getPackage(fqName.parent()).memberScope.getContributedFunctions(fqName.shortName(), NoLookupLocation.FROM_BACKEND)
            val hits = functionCandidates.filter(additionalFilter)
            if (hits.size != 1) {
                throw AutoDiffException("Filter was not sufficient at discovering a single function for $fqName. Hits: ${hits.joinToString(",")}")
            }
            val fncSymbol = pluginContext.symbolTable.referenceSimpleFunction(hits.first())
            val irDeclaration: IrDeclaration = pluginContext.linker().getDeclaration(fncSymbol) ?: throw AutoDiffException("Could not generate a stub for ${fncSymbol.owner.name}")
            return irDeclaration as? IrSimpleFunction ?: throw AutoDiffException("Expected a function but got ${irDeclaration::class}")
        }

        val reverseClass = getClassInDifferentiableApi(adOptimizeCommon.reverseClass)
        val forwardClass = getClassInDifferentiableApi(adOptimizeCommon.forwardClass)
        val boxedPrimitiveClass = getClassInDifferentiableApi(adOptimizeCommon.boxedPrimitive)
        val primitiveTypeValue = valueForKey(adOptimizeCommon.primitiveType)
        val primitiveType = when {
            primitiveTypeValue == "Float" -> pluginContext.irBuiltIns.floatType
            primitiveTypeValue == "Double" -> pluginContext.irBuiltIns.doubleType
            else -> throw AutoDiffException("expected primitive type to be either float or double but got $primitiveTypeValue")
        }

        val stackIrClass = getClassInDifferentiableApi(stackImpl)
        fun stackMethod(name: String) = stackIrClass.functions.firstOrNull { it.name.toString() == name } ?: throw AutoDiffException("Could not find method $name on class ${stackIrClass.name}")
        val stackClass = StackClass(stackIrClass, stackMethod("pop"), stackMethod("push"), stackMethod("notEmpty"), stackMethod("top"))
        val scalarRoot = getClassInDifferentiableApi(adOptimizeCommon.scalarRoot)
        val dTensorRoot = getClassInDifferentiableApi(adOptimizeCommon.dTensor)
        val kotlinTypeOfDTensor = dTensorRoot.defaultType.toKotlinType()
        val tensorFunction = (IrSimpleTypeImpl(pluginContext.irBuiltIns.functionN(1).symbol, false, listOf(dTensorRoot.defaultType as IrSimpleTypeImpl, dTensorRoot.defaultType as IrSimpleTypeImpl), emptyList())).toKotlinType()
        val differentiableApi = DifferentiableApi(
            reverseDiffScalarClass = ReverseDifferentiableScalarMetadata(
                clazz = reverseClass,
                primalProperty = propertyOfClassWithKey(adOptimizeCommon.primalProperty, reverseClass),
                upstreamProperty = propertyOfClassWithKey(adOptimizeCommon.upstreamProperty, clazz = reverseClass),
                backpropMethod = methodOfClassWithKey(adOptimizeCommon.backpropMethod, reverseClass),
                pushbackMethod = methodOfClassWithKey(adOptimizeCommon.pushbackMethod, reverseClass),
                derivativeId = propertyOfClassWithKey(adOptimizeCommon.derivativeId, reverseClass),
                setPrimal = propertyOfClassWithKey(adOptimizeCommon.primalProperty, reverseClass).setter
            ),
            forwardDiffScalarClass = ForwardDifferentiableScalarMetadata(
                clazz = forwardClass,
                primalProperty = propertyOfClassWithKey(adOptimizeCommon.primalProperty, forwardClass),
                tangentProperty = propertyOfClassWithKey(adOptimizeCommon.tangentProperty, forwardClass),
                derivativeId = propertyOfClassWithKey(adOptimizeCommon.derivativeId, forwardClass)
            ),
            scalarRoot = scalarRoot,
            dTensorRoot = dTensorRoot,
            primalAndPullbackFunction = topLevelFunctionFromKey(adOptimizeCommon.primalAndPullbackFunction) {
                if (it.valueParameters.size != 2) false else {
                    it.valueParameters.first().type.isSubtypeOf(kotlinTypeOfDTensor) &&
                        it.valueParameters[1].type.isEqualTo(tensorFunction)
                }
            },
            boxedPrimitiveInfo = BoxedPrimitiveInfo(
                boxedPrimitiveClass = boxedPrimitiveClass,
                valueProperty = propertyOfClassWithKey(adOptimizeCommon.valueProperty, boxedPrimitiveClass),
                primitiveType = primitiveType,
                scalarZeroObjectProperty = getPropertyInDifferentiableApi(adOptimizeCommon.scalarZero),
                scalarOneObjectProperty = getPropertyInDifferentiableApi(adOptimizeCommon.scalarOne)
            ),
            scalarPlus = topLevelFunctionFromKey(adOptimizeCommon.scalarPlusFunction) { candidate ->
                candidate.allParameters().all { pluginContext.typeTranslator.translateType(it.first.type) == scalarRoot.defaultType }
            },
            tensorPlus = topLevelFunctionFromKey(adOptimizeCommon.tensorPlusFunction) { candidate ->
                candidate.allParameters().all { pluginContext.typeTranslator.translateType(it.first.type) == dTensorRoot.defaultType }
            },
            toReverseAnnotation = FqName(valueForKey(adOptimizeCommon.toReverse)),
            scalarReverseOperations = getClassInDifferentiableApi(adOptimizeCommon.reverseOperations),
            scalarNoopAnnotation = FqName(valueForKey(adOptimizeCommon.scalarNoop)),
            toUnboxFunctionFqName = FqName(valueForKey(adOptimizeCommon.toUnboxFunction)),
            apiPackage = apiPackage,
            pluginContext = pluginContext
        )
        return Pair(differentiableApi, stackClass)
    }
}

fun FunctionDescriptor.allParameters(): List<Pair<ParameterDescriptor, Int>> {
    val params = mutableListOf<Pair<ParameterDescriptor, Int>>()
    if (this.extensionReceiverParameter != null) { params.add(Pair(this.extensionReceiverParameter!!, -2)) }
    if (this.dispatchReceiverParameter != null) { params.add(Pair(this.dispatchReceiverParameter!!, -1)) }
    this.valueParameters.forEachIndexed { i, v -> params.add(Pair(v, i)) }
    return params
}
