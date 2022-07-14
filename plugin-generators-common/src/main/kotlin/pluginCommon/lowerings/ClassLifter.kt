/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.lowerings

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.simpleFunctions
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.transformStatement
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import pluginCommon.*
import pluginCommon.generators.*

abstract class ClassLifterDelegate {
    private var counter = 0
    open fun shouldLiftClass(clazz: IrClass): Boolean = true
    open fun liftedClassName(originalClass: IrClass): String = "${originalClass.parent.kotlinFqName.toString().replace('.', '_')}LiftedClass${counter++}"
    open fun customizeCopyOfMethod(oldMethod: IrSimpleFunction): ClassFunctionAttributes = ClassFunctionAttributes(oldMethod)
}

class ClassLifter(
    val pluginContext: IrPluginContext,
    val messageLogger: IrMessageLogger
) {
    val functionGenerator = IrFunctionGenerator(pluginContext)
    val bodyGenerator = IrBodyGenerator(pluginContext)
    val propertyGenerator = IrPropertyGenerator(pluginContext, bodyGenerator)
    val classGenerator = IrClassGenerator(pluginContext, functionGenerator, propertyGenerator)

    fun liftClassesIn(liftScopeDeclaration: IrDeclaration, delegate: ClassLifterDelegate) {
        fun createLiftedClass(nestedClass: IrClass): LiftedClass {
            if (nestedClass.parent is IrFile) {
                return LiftedClass(nestedClass, emptySet())
            }
            val parent = nestedClass.parent as? IrDeclarationWithName ?: throw IllegalStateException("Class Lifter assumes that if the parent of a class is not a File, then it is a class or a function. Instead found ${nestedClass.parent.render()}")
            val capturedMembers = nestedClass.capturedMembers()
            val parentFile = rootFile(parent)
            val primaryConstructor = nestedClass.primaryConstructor()
            val capturedConstructorParameterInfos = capturedMembers.map { ParameterInfo(it.originalValue.name, it.implicitType) }
            val constructorParameters = primaryConstructor.valueParameters.map { ParameterInfo(it.name, it.type) } + capturedConstructorParameterInfos
            val capturedValueToProperty = mutableMapOf<IrValueDeclaration, IrProperty>()
            val capturedMembersToConstructor = mutableSetOf<CapturedValue>()
            fun populateConstructor(newClass: GeneratedAuthenticClass, constructor: IrConstructor) {
                val srcToTarget = primaryConstructor.valueParameters.map { srcValueParameter -> Pair<IrValueDeclaration, IrValueDeclaration>(srcValueParameter, constructor.valueParameters.first { srcValueParameter.name == it.name }) }.toMap().toMutableMap()
                capturedValueToProperty.putAll(
                    capturedMembers.map { capturedMember: CapturedMember ->
                        val (originalLocalValue, _) = capturedMember
                        val valueParameter = constructor.valueParameters.first { it.name == originalLocalValue.name }
                        srcToTarget[originalLocalValue] = valueParameter
                        capturedMembersToConstructor.add(CapturedValue(originalLocalValue, valueParameter.index))
                        val value = propertyGenerator.generateProperty(valueParameter.name, valueParameter.type, newClass, false, bodyGenerator.generateGetValue(valueParameter))
                        Pair(capturedMembers.first { it.originalValue.name == originalLocalValue.name }.originalValue, value)
                    }.toMap()
                )
                if (primaryConstructor.body != null) {
                    val copyReplacer = CopyAndReplacer(MapWrapper(srcToTarget), Substitutor.emptySubstitutor(), pluginContext.irBuiltIns)
                    val statements = primaryConstructor.body!!.statements.map {
                        when (it) {
                            is IrInstanceInitializerCallImpl -> IrInstanceInitializerCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, newClass.symbol, pluginContext.irBuiltIns.unitType)
                            else -> copyReplacer.copyAndReplace(it, ReplaceDelegate.emptyReplacer, constructor) as IrStatement
                        }
                    }
                    constructor.body = bodyGenerator.generateBlockBody(statements)
                }
            }
            // populate the class with duplicates of the original nested class's properties and functions
            fun populateClassWithDeclarations(clazz: GeneratedAuthenticClass) {
                val srcToTargetProperties = MapWrapper(mutableMapOf<IrProperty, IrProperty>())
                val classScopeCopyAndReplacer = CopyAndReplacer(
                    MapWrapper(mutableMapOf(nestedClass.thisReceiver!! to clazz.thisReceiver!!)),
                    srcToTargetProperties,
                    pluginContext.irBuiltIns
                )
                val classScopeReplacer = LiftedClassReplacer(
                    bodyGenerator,
                    nestedClass,
                    clazz,
                    clazz.thisReceiver!!,
                    capturedValueToProperty
                )

                // copy over properties
                nestedClass.properties.forEach { srcProperty ->
                    srcToTargetProperties[srcProperty] = run {
                        val init = srcProperty.backingField?.initializer?.let { srcInit ->
                            classScopeCopyAndReplacer.copyAndReplace(
                                srcInit,
                                classScopeReplacer,
                                clazz
                            ) as IrExpression
                        }
                        propertyGenerator.duplicateProperty(srcProperty, clazz, init)
                    }
                }

                // copy over anonymous initializers
                nestedClass.declarations.filter { it is IrAnonymousInitializer }.forEach { srcDeclaration ->
                    val declarationCopyReplacer = CopyAndReplacer(
                        MapWrapper(mutableMapOf(nestedClass.thisReceiver!! to clazz.thisReceiver!!)),
                        srcToTargetProperties,
                        pluginContext.irBuiltIns
                    )
                    val imageDeclaration = declarationCopyReplacer.copyAndReplace(
                        srcDeclaration,
                        classScopeReplacer,
                        clazz
                    ) as IrAnonymousInitializer
                    clazz.declarations.add(imageDeclaration)
                }

                // copy over methods (ignore the property accessors because those are added with the properties above)
                nestedClass.simpleFunctions().filter { !it.isPropertyAccessor }.forEach { srcFunction ->
                    val duplicate = functionGenerator.duplicateSignature(srcFunction, clazz, { copiedFunction ->
                        copiedFunction.overriddenSymbols = srcFunction.overriddenSymbols
                        copiedFunction.body = srcFunction.body?.let { body ->
                            val srcToTargetValueParameters = mutableMapOf<IrValueDeclaration, IrValueDeclaration>()
                            srcToTargetValueParameters[srcFunction.dispatchReceiverParameter!!] =
                                copiedFunction.dispatchReceiverParameter!!
                            srcFunction.valueParameters.zip(copiedFunction.valueParameters)
                                .forEach { srcToTargetValueParameters[it.first] = it.second }
                            val declarationCopyReplacer = CopyAndReplacer(
                                MapWrapper(srcToTargetValueParameters),
                                srcToTargetProperties,
                                pluginContext.irBuiltIns
                            )
                            declarationCopyReplacer.copyAndReplace(
                                body,
                                LiftedClassReplacer(
                                    bodyGenerator,
                                    nestedClass,
                                    clazz,
                                    copiedFunction.dispatchReceiverParameter!!,
                                    capturedValueToProperty
                                ),
                                copiedFunction
                            ) as IrBody
                        }
                    }, delegate.customizeCopyOfMethod(srcFunction))
                    duplicate
                }
            }
            val newClass = classGenerator.authenticClass(
                parentFile,
                delegate.liftedClassName(nestedClass),
                nestedClass.superTypes,
                constructorParameters,
                delegatingConstructorBuilder = ::populateConstructor,
                visibilitity = DescriptorVisibilities.DEFAULT_VISIBILITY,
                build = ::populateClassWithDeclarations,
                generateFakeOverrides = false
            )
            parentFile.declarations.add(newClass)
            return LiftedClass(newClass, capturedMembersToConstructor)
        }
        liftScopeDeclaration.acceptVoid(object : IrElementVisitorVoid {
            val parents = java.util.Stack<IrElement>()
            override fun visitElement(element: IrElement) {
                parents.push(element)
                element.acceptChildrenVoid(this)
                parents.pop()
            }

            override fun visitClass(declaration: IrClass) {
                if (delegate.shouldLiftClass(declaration)) {
                    try {
                        val scope = parents.reversed().first { it is IrStatementContainer } as IrStatementContainer
                        val (newClass, capturedMembers) = createLiftedClass(declaration)
                        scope.statements.remove(declaration)
                        val oldClassReferenceReplacer = object : IrElementTransformerVoid() {
                            override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
                                return if (expression.type.classifierOrFail == declaration.symbol) {
                                    val oldArguments = (0 until expression.valueArgumentsCount).map { Pair(expression.getValueArgument(it)!!, it) }
                                    val newArguments: List<Pair<IrExpression, Int>> = capturedMembers.map {
                                        val tpe = newClass.primaryConstructor().valueParameters[it.constructorIndex].type
                                        val getCapturedValue = bodyGenerator.generateGetValue(it.valueInSrc)
                                        val argumentExpression = if (it.valueInSrc.type != tpe) {
                                            bodyGenerator.generateCast(getCapturedValue, tpe)
                                        } else {
                                            getCapturedValue
                                        }
                                        Pair(argumentExpression, it.constructorIndex)
                                    }
                                    val allArgs = oldArguments + newArguments
                                    val constructorCall = IrConstructorCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, newClass.defaultType, newClass.primaryConstructor().symbol, 0, 0, allArgs.size)
                                    allArgs.forEach { constructorCall.putValueArgument(it.second, it.first) }
                                    constructorCall
                                } else expression
                            }

                            override fun visitExpression(expression: IrExpression): IrExpression {
                                if (expression.type == declaration.defaultType) {
                                    when (expression) {
                                        is IrBlock -> {
                                            if (expression.origin == IrStatementOrigin.OBJECT_LITERAL) {
                                                val mappedStatements = expression.statements.map { s -> s.transformStatement(this) }
                                                return if (mappedStatements.size == 1) {
                                                    mappedStatements.first() as IrExpression
                                                } else {
                                                    IrBlockImpl(expression.startOffset, expression.endOffset, newClass.defaultType, null, mappedStatements)
                                                }
                                            }
                                        }
                                        else -> {
                                            expression.type = newClass.defaultType
                                        }
                                    }
                                }
                                return super.visitExpression(expression)
                            }
                        }
                        when (val parent = declaration.parent) {
                            is IrFunction -> parent.body = parent.body?.transform(oldClassReferenceReplacer, null)
                            is IrClass -> {
                                val declarations = parent.declarations.map { it.transformStatement(oldClassReferenceReplacer) as IrDeclaration }
                                parent.declarations.clear()
                                parent.declarations.addAll(declarations)
                            }
                        }
                    } catch (e: Exception) {
                        messageLogger.report(IrMessageLogger.Severity.WARNING, "Could not lift the anonymous class inside ${declaration.parent.kotlinFqName}. Class Lifting failed with message: `${e.message}`", null)
                    }
                } else {
                    super.visitClass(declaration)
                }
            }
        })
    }

    data class CapturedValue(val valueInSrc: IrValueDeclaration, val constructorIndex: Int)
    data class LiftedClass(val newClass: IrClass, val capturedValues: Set<CapturedValue>)

    private class LiftedClassReplacer(
        val bodyGenerator: IrBodyGenerator,
        val oldClass: IrClass,
        val newClass: IrClass,
        val dispatchReceiver: IrValueParameter,
        val captureToProperty: Map<IrValueDeclaration, IrProperty>
    ) : ReplaceDelegate {
        override fun replaceValueParameterWith(
            original: IrValueParameter,
            candidateCopy: IrValueParameter
        ): IrValueParameter? {
            if (original.type == oldClass.defaultType) {
                candidateCopy.type = newClass.defaultType
            }
            return candidateCopy
        }
        override fun replaceUntrackedGetValue(original: IrGetValue): IrExpression? {
            val valueDeclaration = original.symbol.owner
            return if (captureToProperty.contains(valueDeclaration)) {
                bodyGenerator.generateGetProperty(
                    captureToProperty[valueDeclaration]!!,
                    bodyGenerator.generateGetValue(dispatchReceiver)
                )
            } else null
        }
    }

    private data class CapturedMember(val originalValue: IrValueDeclaration, val implicitType: IrType)
    private fun IrClass.capturedMembers(): Set<CapturedMember> {
        val capturedMembers = mutableMapOf<IrValueDeclaration, CapturedMember>()
        val implicitlyTypedValues = mutableMapOf<IrValueDeclaration, IrType>()
        val nestedClass = this
        nestedClass.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitBlock(expression: IrBlock) {
                throw PluginCodegenException("captured variables only supported within single scope. Child scopes not supported")
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall) {
                when (val argument = expression.argument) {
                    is IrGetValue -> {
                        implicitlyTypedValues[argument.symbol.owner] = expression.type
                    }
                }
                super.visitTypeOperator(expression)
            }
            // when we find a get of a local variable, we should already have detected the implicit type
            override fun visitGetValue(expression: IrGetValue) {
                val value = expression.symbol.owner
                if (value.parent == nestedClass.parent) {
                    val capturedMember = implicitlyTypedValues[value]?.let { implicitType ->
                        CapturedMember(value, implicitType)
                    } ?: CapturedMember(value, value.type)
                    capturedMembers[value] = capturedMember
                }
            }
        })
        return capturedMembers.values.toSet()
    }

    private fun IrClass.primaryConstructor() = primaryConstructor ?: run { if (constructors.count() == 1) constructors.first() else throw PluginCodegenException("The class $name does not have a primary constructor") }
    private fun rootFile(ancestor: IrDeclaration): IrFile {
        return when (val p = ancestor.parent) {
            is IrFile -> p
            is IrDeclaration -> rootFile(p)
            else -> throw NotImplementedError("${ancestor.render()} does not have a parent that is a declaration or a file")
        }
    }
}
