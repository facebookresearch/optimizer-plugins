/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.diffIR

import adoptimize.AutoDiffException
import adoptimize.autodiff.allParametersWithIndex
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name
import pluginCommon.generators.allParameters

class ConcreteReverseNode(
    val clazz: IrClass,
    val backpropMethod: IrFunction,
    val upstream: IrProperty,
    val primal: IrProperty,
    val derivativeId: IrProperty
)

interface DiffIRStatement {
    fun acceptVisitor(visitor: DiffIRVisitor)
    fun transform(transformer: DiffIRTransformer): IrElement?
    val children: List<DiffIRStatement>
}

sealed class DiffIRAtom : DiffIRStatement {
    abstract val type: IrType
    abstract val original: IrElement
    override val children: List<DiffIRStatement> = emptyList()
}

class DiffIRParameter(override val original: IrValueParameter, val tangentType: IrSimpleType, val isActive: Boolean, val index: Int) : DiffIRAtom() {
    override val type: IrType = original.type
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        TODO("Not yet implemented")
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? {
        TODO("Not yet implemented")
    }
}

class DiffIRArgument(val argument: IrValueDeclaration, val isActive: Boolean, val isReferencedInBackprop: Boolean = true)

class DiffIRConstructorCallInfo(
    val constructor: IrConstructor,
    val arguments: List<DiffIRArgument>
)

class DiffIRCallInfo(
    val dispatchFunction: IrSimpleFunction,
    val dispatchReceiver: DiffIRArgument?,
    val extensionReceiver: DiffIRArgument?,
    val arguments: List<DiffIRArgument>,
    val dependencyNode: ConcreteReverseNode?
) {
    fun allDiffIrArguments(): List<DiffIRArgument> {
        val args = mutableListOf<DiffIRArgument>()
        dispatchReceiver?.let { args.add(it) }
        extensionReceiver?.let { args.add(it) }
        args.addAll(arguments.map { it })
        return args
    }

    fun valueForIndex(index: Int): DiffIRArgument {
        return when (index) {
            -1 -> dispatchReceiver ?: throw AutoDiffException("value for index failed because there is no dispatch receiver argument for ${dispatchFunction.name}")
            -2 -> extensionReceiver ?: throw AutoDiffException("value for index failed because there is no extension receiver argument for ${dispatchFunction.name}")
            else -> arguments[index]
        }
    }
}

sealed class ActiveVariable(val name: Name, override val original: IrVariable) : DiffIRAtom() {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitVariable(this)
    }

    override val type: IrType
        get() = original.type
}

class GetPropertyVariable(name: Name, original: IrVariable, val property: IrProperty, val isActive: Boolean) : ActiveVariable(name, original) {
    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformGetProperty(this)
}

class PopIntermediateStateVariable(name: Name, original: IrVariable) : ActiveVariable(name, original) {
    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformPopIntermediateVariable(this)
}

class PushIntermediateStateVariable(override val original: IrCall, val pushedVariable: IrValueDeclaration) : DiffIRAtom() {
    override val type: IrType = original.type

    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitPushIntermediateState(this)
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformPushIntermediateVariable(this)
}

class TypeOperatorVariable(name: Name, original: IrVariable, val srcType: IrType, val targetType: IrType) : ActiveVariable(name, original) {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitTypeOperatorVariable(this)
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformTypeOperatorVariable(this)
}

class CallVariable(
    name: Name,
    val callInfo: DiffIRCallInfo,
    original: IrVariable,
    val isReferencedInBackprop: Boolean = false
) : ActiveVariable(name, original) {
    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformCallVariable(this)
}

class GradientVariable(
    name: Name,
    original: IrVariable
) : ActiveVariable(name, original) {
    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformDerivativeVariable(this)
}

class LateInitVariable(name: Name, original: IrVariable) : ActiveVariable(name, original) {
    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformLateInitVariable(this)
}

class GetValVariable(name: Name, original: IrVariable, val rhs: IrValueDeclaration, val isActive: Boolean) : ActiveVariable(name, original) {
    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformGetValVariable(this)
}

class SetValue(
    val setVariable: IrVariable,
    val referencedVariable: IrValueDeclaration,
    val rhsIsActive: Boolean,
    val rhsIsUsedInBackProp: Boolean,
    override val original: IrSetValue
) : DiffIRAtom() {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitSetVariable(this)
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformSetVariable(this)

    override val type: IrType
        get() = setVariable.type
}

class SetField(override val original: IrSetField, val correspondingProperty: IrProperty) : DiffIRAtom() {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitSetField(this)
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformSetField(this)

    override val type: IrType = original.type
}

class Call(override val original: IrCall) : DiffIRAtom() {
    override val type: IrType = original.type

    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitCall(this)
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformCall(this)
}

class ConstructorCallVariable(override val original: IrVariable) : DiffIRAtom() {
    override val type: IrType = original.type
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitConstructorCallVariable(this)
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformConstructorCall(this)
}

class ConstantStatement(
    override val original: IrElement,
    override val type: IrType
) : DiffIRAtom() {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitConstant(this)
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformConstant(this)
}

sealed class AbstractBlockStatement(override val children: List<DiffIRStatement>) : DiffIRStatement

open class BlockStatement(c: List<DiffIRStatement>, val isVirtual: Boolean, val type: IrType, val original: IrBlock?) : AbstractBlockStatement(c) {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitBlockStatement(this)
        this.children.forEach { it.acceptVisitor(visitor) }
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformBlockStatement(this)
}

open class BlockBodyStatement(c: List<DiffIRStatement>, val original: IrBlockBody) : AbstractBlockStatement(c) {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitBlockBodyStatement(this)
        this.children.forEach { it.acceptVisitor(visitor) }
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformBlockBodyStatement(this)
}

class ConditionBlock(val condition: IrExpression, val result: BlockStatement, val index: Int) : DiffIRStatement {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitConditionStatement(this)
        this.children.forEach { it.acceptVisitor(visitor) }
    }

    override val children: List<DiffIRStatement> = listOf(result)

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformConditionStatement(this)
}

class WhenStatement(val original: IrWhen, override val children: List<ConditionBlock>) : DiffIRStatement {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitWhenStatement(this)
        this.children.forEach { it.acceptVisitor(visitor) }
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformWhenStatement(this)
}

class LoopStatement(val identifier: Int, val original: IrWhileLoop, val body: DiffIRStatement) : DiffIRStatement {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitLoopStatement(this)
        this.children.forEach { it.acceptVisitor(visitor) }
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformLoopStatement(this)

    override val children: List<DiffIRStatement> = listOf(body)
}

class ReturnStatement(val original: IrReturn, val valueDeclaration: IrValueDeclaration) : DiffIRStatement {
    override fun acceptVisitor(visitor: DiffIRVisitor) {
        visitor.visitReturn(this)
    }

    override fun transform(transformer: DiffIRTransformer): IrElement? = transformer.transformReturn(this)
    override val children: List<DiffIRStatement> = emptyList()
}

class DiffIRFunction(val original: IrFunction, val body: DiffIRStatement, val returnedVariable: IrValueDeclaration, val diffIrParameters: List<DiffIRParameter>) {
    fun getParameters() = (original as IrSimpleFunction).allParameters()
    fun getParametersWithIndex() = (original as IrSimpleFunction).allParametersWithIndex()
}
