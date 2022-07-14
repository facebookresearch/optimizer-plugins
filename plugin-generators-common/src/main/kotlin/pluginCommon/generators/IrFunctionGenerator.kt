/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.Name
import pluginCommon.PluginCodegenException

class ClassFunctionAttributes(var visibility: DescriptorVisibility, var modality: Modality, var isInline: Boolean, var isExternal: Boolean, var isTailRec: Boolean, var isSuspend: Boolean, var isOperator: Boolean, var isInfix: Boolean, var isExpect: Boolean) {
    constructor(model: IrSimpleFunction) : this(model.visibility, model.modality, model.isInline, model.isExternal, model.isTailrec, model.isSuspend, model.isOperator, model.isInfix, model.isExpect)
}

class IrFunctionGenerator(val pluginContext: IrPluginContext) {
    fun generateOverride(superMethod: IrFunction, containingClass: IrClass, build: (IrFunction) -> Unit): IrSimpleFunction {
        val fnc = generateMethod(
            superMethod.name,
            Modality.OPEN,
            containingClass,
            superMethod.valueParameters.map { ParameterInfo(it.name, it.type) },
            superMethod.returnType,
            build
        )
        fnc.overriddenSymbols = listOf(
            superMethod.symbol as? IrSimpleFunctionSymbol
                ?: throw PluginCodegenException("Only simple functions can be overridden")
        )
        return fnc
    }

    fun generateFakeOverride(superMethod: IrSimpleFunction, containingClass: IrClass): IrSimpleFunction {
        val fnc = generateMethod(
            superMethod.name,
            superMethod.modality,
            containingClass,
            superMethod.valueParameters.map { ParameterInfo(it.name, it.type) },
            superMethod.returnType,
            null,
            IrDeclarationOrigin.FAKE_OVERRIDE
        )
        fnc.overriddenSymbols = listOf(
            superMethod.symbol as? IrSimpleFunctionSymbol
                ?: throw PluginCodegenException("Only simple functions can be overridden")
        )
        return fnc
    }

    fun duplicateSignature(oldMethod: IrSimpleFunction, newOwner: IrClass, build: ((IrSimpleFunction) -> Unit)?, customize: ClassFunctionAttributes = ClassFunctionAttributes(oldMethod)): IrSimpleFunction {
        val clazzType = newOwner.defaultType
        val method = pluginContext.irFactory.createFunction(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            oldMethod.origin,
            LateInitFunctionSymbol(),
            name = oldMethod.name,
            visibility = customize.visibility,
            modality = customize.modality,
            returnType = oldMethod.returnType,
            isInline = customize.isInline,
            isExternal = customize.isExternal,
            isTailrec = customize.isTailRec,
            isSuspend = customize.isSuspend,
            isOperator = customize.isOperator,
            isInfix = customize.isInfix,
            isExpect = customize.isExpect
        ).also {
            it.metadata = DescriptorMetadataSource.Function(it.descriptor)
        }
        method.parent = newOwner
        pluginContext.symbolTable.buildWithScope(method) { method ->
            val implicitReceiverParameter = pluginContext.irFactory.createValueParameter(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                IrDeclarationOrigin.DEFINED,
                IrValueParameterSymbolImpl(),
                Name.special("<this>"),
                -1,
                clazzType,
                null, false, false, false, false
            )
            implicitReceiverParameter.parent = method
            method.dispatchReceiverParameter = implicitReceiverParameter
            oldMethod.valueParameters.forEachIndexed { i: Int, v ->
                val vp = pluginContext.irFactory.createValueParameter(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    IrDeclarationOrigin.DEFINED,
                    IrValueParameterSymbolImpl(),
                    v.name,
                    i,
                    v.type,
                    v.varargElementType, v.isCrossinline, v.isNoinline, v.isHidden, v.isAssignable
                )
                vp.parent = method
                method.valueParameters += vp
            }
            implicitReceiverParameter.parent = method
            build?.invoke(method)
        }
        newOwner.declarations.add(method)

        return method
    }

    fun generateMethod(
        name: Name,
        modality: Modality,
        containingClass: IrClass,
        parameters: List<ParameterInfo>,
        returnType: IrType,
        build: ((IrFunction) -> Unit)?,
        origin: IrDeclarationOrigin = IrDeclarationOrigin.DEFINED
    ): IrSimpleFunction {
        val clazzType = containingClass.defaultType
        val method = pluginContext.irFactory.createFunction(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            origin,
            LateInitFunctionSymbol(),
            name = name,
            visibility = DescriptorVisibilities.DEFAULT_VISIBILITY,
            modality = modality,
            returnType = returnType,
            isInline = false,
            isExternal = false,
            isTailrec = false,
            isSuspend = false,
            isOperator = false,
            isInfix = false,
            isExpect = false
        ).also {
            it.metadata = DescriptorMetadataSource.Function(it.descriptor)
        }
        method.parent = containingClass
        pluginContext.symbolTable.buildWithScope(method) { method ->
            val implicitReceiverParameter = pluginContext.irFactory.createValueParameter(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                IrDeclarationOrigin.DEFINED,
                IrValueParameterSymbolImpl(),
                Name.special("<this>"),
                -1,
                clazzType,
                null, false, false, false, false
            )
            implicitReceiverParameter.parent = method
            method.dispatchReceiverParameter = implicitReceiverParameter
            parameters.forEachIndexed { i: Int, v ->
                val vp = pluginContext.irFactory.createValueParameter(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    IrDeclarationOrigin.DEFINED,
                    IrValueParameterSymbolImpl(),
                    v.name,
                    i,
                    v.tpe,
                    null, false, false, false, false
                )
                vp.parent = method
                method.valueParameters += vp
            }
            implicitReceiverParameter.parent = method
            build?.invoke(method)
        }
        containingClass.declarations.add(method)
        return method
    }

    fun generateConstructor(
        clazz: IrClass,
        parameters: List<ParameterInfo>,
        isPrimary: Boolean,
        build: (IrConstructor) -> Unit
    ): IrConstructor {
        val clazzType = clazz.defaultType
        val constructor = pluginContext.irFactory.createConstructor(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            LateInitConstructorSymbol(),
            Name.special("<init>"),
            DescriptorVisibilities.DEFAULT_VISIBILITY,
            clazzType, false, false, isPrimary, false
        )
        pluginContext.symbolTable.buildWithScope(constructor) { constructor ->
            // TODO: why does constructor need an implicit receiver parameter?
            val valueParameter = pluginContext.irFactory.createValueParameter(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                IrDeclarationOrigin.DEFINED,
                IrValueParameterSymbolImpl(),
                Name.special("<this>"),
                -1,
                clazzType,
                null, false, false, false, false
            )
            valueParameter.parent = constructor
            parameters.forEachIndexed { i: Int, v ->
                val vp = pluginContext.irFactory.createValueParameter(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    IrDeclarationOrigin.DEFINED,
                    IrValueParameterSymbolImpl(),
                    v.name,
                    i,
                    v.tpe,
                    null, false, false, false, false
                )
                vp.parent = constructor
                constructor.valueParameters += vp
            }

            valueParameter.parent = constructor
            build(constructor)
        }
        clazz.declarations.add(constructor)
        return constructor
    }

    fun generateLambda(
        parent: IrDeclarationParent,
        parameters: List<ParameterInfo>,
        returnType: IrType,
        build: (IrFunction) -> Unit
    ): IrSimpleFunction {
        val function = IrFunctionImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
            LateInitFunctionSymbol(),
            Name.special("<anonymous>"),
            DescriptorVisibilities.LOCAL,
            Modality.FINAL,
            returnType,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false
        )
        function.parent = parent
        pluginContext.symbolTable.buildWithScope(function) { fc ->
            parameters.forEachIndexed { i: Int, v ->
                val vp = pluginContext.irFactory.createValueParameter(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    IrDeclarationOrigin.DEFINED,
                    IrValueParameterSymbolImpl(),
                    v.name,
                    i,
                    v.tpe,
                    null, false, false, false, false
                )
                vp.parent = fc
                fc.valueParameters += vp
            }
            build(fc)
        }
        return function
    }

    fun generateFunction(
        name: Name,
        parent: IrDeclarationParent,
        parameters: List<ParameterInfo>,
        returnType: IrType,
        build: (IrFunction) -> Unit,
        visibility: DescriptorVisibility = DescriptorVisibilities.DEFAULT_VISIBILITY
    ): IrSimpleFunction {
        val function = IrFunctionImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            LateInitFunctionSymbol(),
            name,
            visibility,
            Modality.FINAL,
            returnType,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false
        )
        function.parent = parent
        pluginContext.symbolTable.buildWithScope(function) { fc ->
            parameters.forEachIndexed { i: Int, v ->
                val vp = pluginContext.irFactory.createValueParameter(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    IrDeclarationOrigin.DEFINED,
                    IrValueParameterSymbolImpl(),
                    v.name,
                    i,
                    v.tpe,
                    null, false, false, false, false
                )
                vp.parent = fc
                fc.valueParameters += vp
            }
            build(fc)
        }
        return function
    }
}
