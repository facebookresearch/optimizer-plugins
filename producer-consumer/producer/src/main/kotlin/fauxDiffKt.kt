/*
 *
 *  Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 *  This source code is licensed under the MIT license found in the
 *  LICENSE file in the root directory of this source tree.
 *
 */

package demo

import java.util.*

annotation class ReverseDifferentiable(
    val primalField: String,
    val upstreamField: String,
    val backpropogateMethod: String,
    val pushbackMethod: String,
    val derivativeID: String
)

annotation class ScalarRoot
annotation class PrimalAndPullback
annotation class StackImpl
annotation class BoxedPrimitive(val valueField: String)
annotation class ToUnboxedFunction(val functionName: String)
annotation class DTensorRoot
annotation class ToReverse(val fqClass: String)
annotation class ReverseOperations
annotation class ScalarNoop
annotation class ForwardDifferentiable(val tangent: String)

@DTensorRoot
interface DiffTensor {
    val shape: IntArray get() = primal.shape
    val primal: DiffTensor
    val rank: Int get() = 0
    val size: Int get() = 0
}
operator fun DiffTensor.plus(other: DiffTensor): DiffTensor { TODO() }

open class DerivativeID(val seq: Int) : Comparable<DerivativeID> {
    override fun compareTo(other: DerivativeID): Int {
        return this.seq.compareTo(other.seq)
    }
}
class ActiveLeaf(value: DifferentiableDouble, d: ReverseDerivativeID) : ReverseNode(value, d) {
    override fun backpropagate() {}
}
class ReverseDerivativeID(s: Int) : DerivativeID(s) {
    private val backpropagateWorkList: Stack<ReverseNode> = Stack()
    fun addNode(t: ReverseNode) = backpropagateWorkList.add(t)
    fun reversePass(): Map<ReverseNode, DifferentiableDouble> {
        val result = HashMap<ReverseNode, DifferentiableDouble>()
        while (!backpropagateWorkList.empty()) {
            val node = backpropagateWorkList.pop()
            node.backpropagate()
            if (node is ActiveLeaf) {
                result.put(node, node.upstream)
            }
        }
        return result
    }
}

val zeroDerivativeID = DerivativeID(0)

@ScalarRoot
sealed class DifferentiableDouble(p: DifferentiableDouble) : DiffTensor {
    override val shape: IntArray = IntArray(1, { 1 })
    open override val primal: DifferentiableDouble = p
    open fun value(): Double = primal.value()
    abstract val derivativeID: DerivativeID
    fun zero(): DifferentiableDouble = DDouble(0.0)
    fun one(): DifferentiableDouble = DDouble(1.0)
}

@BoxedPrimitive("value")
class DDouble(val value: Double) : DifferentiableDouble(DDouble.ZERO) {
    override val derivativeID = zeroDerivativeID
    override val primal: DifferentiableDouble = this
    override fun value(): Double = this.value
    companion object {
        val ZERO = DDouble(0.0)
        val ONE = DDouble(1.0)
    }
}

@ReverseDifferentiable("primal", "upstream", "backpropagate", "pushback", "derivativeID")
abstract class ReverseNode(p: DifferentiableDouble, d: ReverseDerivativeID) : DifferentiableDouble(p) {
    init {
        d.addNode(this)
    }
    var upstream: DifferentiableDouble = DDouble(0.0)
    override val derivativeID: ReverseDerivativeID = d
    abstract fun backpropagate()
    open override val primal: DifferentiableDouble = p
    fun pushback(value: DifferentiableDouble) { this.upstream = value + this.upstream }
}

@ForwardDifferentiable("tangent")
class ForwardNode(p: DifferentiableDouble, override val derivativeID: DerivativeID) : DifferentiableDouble(p) {
    open var tangent: DifferentiableDouble = DDouble(1.0)
    constructor(p: DifferentiableDouble, d: DerivativeID, t: DifferentiableDouble) : this(p, d) {
        this.tangent = t
    }
}

@StackImpl
class StackForCompiler<T> {
    fun pop(): T { TODO() }
    fun push(d: T) {}
    fun top(): T { TODO() }
    fun notEmpty(): Boolean { TODO() }
}

operator fun DifferentiableDouble.plus(other: DifferentiableDouble): DifferentiableDouble {
    return when (this) {
        is DDouble -> {
            when (other) {
                is DDouble -> DDouble(other.value + this.value)
                else -> TODO()
            }
        }
        is ReverseNode -> {
            when (other) {
                is ReverseNode -> ReverseScalarOperations().plus(this, other, this.derivativeID)
                else -> TODO()
            }
        }
        is ForwardNode -> {
            when (other) {
                is ForwardNode -> ForwardNode(this.primal + other.primal, this.derivativeID, this.tangent + other.tangent)
                else -> TODO()
            }
        }
    }
}

operator fun DifferentiableDouble.times(other: DifferentiableDouble): DifferentiableDouble {
    return when (this) {
        is DDouble -> {
            when (other) {
                is DDouble -> DDouble(other.value * this.value)
                else -> TODO()
            }
        }
        is ReverseNode -> {
            when (other) {
                is ReverseNode -> ReverseScalarOperations().times(this, other, this.derivativeID)
                else -> TODO()
            }
        }
        is ForwardNode -> {
            when (other) {
                is ForwardNode -> ForwardNode(this.primal * other.primal, this.derivativeID, this.tangent * other.primal + other.tangent * this.primal)
                else -> TODO()
            }
        }
    }
}

@PrimalAndPullback
fun primalAndPullback(operand: DifferentiableDouble, operator: (DifferentiableDouble) -> DifferentiableDouble): DifferentiableDouble {
    TODO()
}

fun primalAndReverseDerivative(x: DifferentiableDouble, f: (DifferentiableDouble) -> DifferentiableDouble): Pair<DifferentiableDouble, DifferentiableDouble> {
    val derivativeID = ReverseDerivativeID(1)
    val reverseX = ActiveLeaf(x, derivativeID)
    val result = f(reverseX)
    val primalResult0 = result.primal
    result as ReverseNode
    result.pushback(DDouble(1.0))
    val map = derivativeID.reversePass()
    return Pair(primalResult0, map.get(reverseX)!!)
}

fun primalAndForwardDerivative(x: DifferentiableDouble, f: (DifferentiableDouble) -> DifferentiableDouble): Pair<DifferentiableDouble, DifferentiableDouble> {
    val derivativeID = DerivativeID(1)
    val primalAndTangent = f(ForwardNode(x, derivativeID, DDouble(1.0))) as ForwardNode
    return Pair(primalAndTangent.primal, primalAndTangent.tangent)
}

@ReverseOperations
class ReverseScalarOperations {
    fun times(left: DifferentiableDouble, right: DifferentiableDouble, derivativeId: DerivativeID): DifferentiableDouble {
        left as ReverseNode
        right as ReverseNode
        derivativeId as ReverseDerivativeID
        return object : ReverseNode(left.primal * right.primal, derivativeId) {
            override fun backpropagate() {
                left.pushback(this.upstream * right.primal)
                right.pushback(this.upstream * left.primal)
            }
        }
    }

    fun plus(left: DifferentiableDouble, right: DifferentiableDouble, derivativeId: DerivativeID): DifferentiableDouble {
        left as ReverseNode
        right as ReverseNode
        derivativeId as ReverseDerivativeID
        return object : ReverseNode(left.primal + right.primal, derivativeId) {
            override fun backpropagate() {
                left.pushback(this.upstream)
                right.pushback(this.upstream)
            }
        }
    }
}
