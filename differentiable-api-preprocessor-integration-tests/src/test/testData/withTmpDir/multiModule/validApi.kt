// WITH_RUNTIME

// MODULE: MAIN
// FILE: lib.kt
package demo
import demo.*

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
annotation class ToReverse(val fqClass: String)
annotation class DTensorRoot
annotation class ReverseOperations
annotation class ScalarNoop
annotation class ForwardDifferentiable(val tangentProperty: String)

open class DerivativeID(private val seq: Int) : Comparable<DerivativeID> {
    override fun compareTo(other: DerivativeID): Int {
        return this.seq.compareTo(other.seq)
    }
}

class ReverseDerivativeID(s: Int) : DerivativeID(s) {
    val backpropogateWorkList: Stack<DifferentiableDouble> = Stack()
}

@DTensorRoot
interface DiffTensor
operator fun DiffTensor.plus(other: DiffTensor): DiffTensor { TODO() }

val zeroDerivativeID = DerivativeID(0)

@ScalarRoot
sealed class DifferentiableDouble : DiffTensor {
    abstract val primal: DifferentiableDouble
    open fun value(): Double {
        return primal.value()
    }
    abstract val derivativeID: DerivativeID
    fun zero(): DifferentiableDouble = DDouble(0.0)
    fun one(): DifferentiableDouble = DDouble(1.0)
}

@BoxedPrimitive("value")
class DDouble(val value: Double) : DifferentiableDouble() {
    override val derivativeID = zeroDerivativeID
    override val primal: DifferentiableDouble = this
    override fun value(): Double {
        return this.value
    }
    companion object {
        val ZERO = DDouble(0.0)
        val ONE = DDouble(1.0)
    }
}

@ReverseDifferentiable("primal", "upstream", "backpropogate", "pushback", "derivativeID")
abstract class ReverseNode(d: ReverseDerivativeID) : DifferentiableDouble() {
    var upstream: DifferentiableDouble = DDouble(0.0)
    override val derivativeID: ReverseDerivativeID = d
    init {
        d.backpropogateWorkList.push(this)
    }

    abstract fun backpropogate()
    open override val primal: DifferentiableDouble = DDouble(0.0)
    fun pushback(value: DifferentiableDouble) {
        upstream = upstream + value
    }
}

@ForwardDifferentiable("tangent")
class ForwardNode(d: DerivativeID, override val primal: DifferentiableDouble, val tangent: DifferentiableDouble) : DifferentiableDouble() {
    override val derivativeID: DerivativeID = d
}

operator fun DifferentiableDouble.times(that: DifferentiableDouble): DifferentiableDouble {
    return ReverseScalarOperations().times(this, that, this.derivativeID)
}

operator fun DifferentiableDouble.plus(that: DifferentiableDouble): DifferentiableDouble {
    return ReverseScalarOperations().plus(this, that, this.derivativeID)
}

class LeafNode(override var primal: DifferentiableDouble, override val derivativeID: ReverseDerivativeID) : ReverseNode(derivativeID) {
    override fun backpropogate() {
    }
}

@StackImpl
class Stack<T> {
    val data = arrayListOf<T>()
    var endPtr: Int = -1
    fun push(d: T) {
        if (endPtr < data.size) {
            data.add(d)
        } else {
            data[endPtr] = d
        }
        endPtr++
    }
    fun pop(): T {
        val x = data[endPtr]
        endPtr--
        return x
    }
    fun top(): T = data[endPtr]
    fun notEmpty() = endPtr >= 0
}

object DifferentialID {
    private var count = 0
    fun next() = count++
}

fun primalAndReverseDerivative(x: DifferentiableDouble, f: (DifferentiableDouble) -> DifferentiableDouble): Pair<DifferentiableDouble, DifferentiableDouble> {
    val derivativeID = ReverseDerivativeID(DifferentialID.next())
    val reverseX = LeafNode(x, derivativeID)
    val result = f(reverseX)
    var primalResult = result
    while (primalResult.derivativeID > derivativeID)
        primalResult = primalResult.primal
    if (primalResult.derivativeID == derivativeID) {
        val initialUpstream: DifferentiableDouble = DDouble(1.0)
        (primalResult as ReverseNode).pushback(initialUpstream)
        while (derivativeID.backpropogateWorkList.notEmpty()) {
            val r = derivativeID.backpropogateWorkList.pop()
            when (r) {
                is ReverseNode -> r.backpropogate()
                else -> throw Error()
            }
        }
        primalResult = primalResult.primal
    }
    val dx = reverseX.upstream
    return Pair(primalResult, dx)
}

@PrimalAndPullback
fun primalAndPullback(x: DifferentiableDouble, f: (DifferentiableDouble) -> DifferentiableDouble): Pair<DifferentiableDouble, (DifferentiableDouble) -> DifferentiableDouble> {
    val derivativeID = ReverseDerivativeID(DifferentialID.next())
    val reverseX = LeafNode(x, derivativeID)
    val result = f(reverseX)

    fun pullback(initialUpstream: DifferentiableDouble): DifferentiableDouble {
        var primalResult = result
        while (primalResult.derivativeID > derivativeID)
            primalResult = primalResult.primal
        if (primalResult.derivativeID == derivativeID) {
            (primalResult as ReverseNode).pushback(initialUpstream)
            val stack = derivativeID.backpropogateWorkList
            while (stack.notEmpty()) {
                (stack.pop() as ReverseNode).backpropogate()
            }
        }
        return reverseX.upstream
    }
    return Pair(result, ::pullback)
}

@ReverseOperations
class ReverseScalarOperations {
    fun times(left: DifferentiableDouble, right: DifferentiableDouble, derivativeId: DerivativeID): DifferentiableDouble {
        left as ReverseNode
        right as ReverseNode
        derivativeId as ReverseDerivativeID
        return object : ReverseNode(derivativeId) {
            override val primal: DifferentiableDouble
            init {
                val x = left.primal
                val y = right.primal
                this.primal = x * y
            }
            override inline fun backpropogate() {
                left.pushback(this.upstream * right.primal)
                right.pushback(this.upstream * left.primal)
            }
        }
    }
    fun plus(left: DifferentiableDouble, right: DifferentiableDouble, derivativeId: DerivativeID): DifferentiableDouble {
        left as ReverseNode
        right as ReverseNode
        derivativeId as ReverseDerivativeID
        return object : ReverseNode(derivativeId) {
            override val primal: DifferentiableDouble
            init {
                val x = left.primal
                val y = right.primal
                this.primal = x + y
            }
            override inline fun backpropogate() {
                left.pushback(this.upstream)
                right.pushback(this.upstream)
            }
        }
    }
}

// MODULE: TEST(MAIN)
// FILE: test.kt

fun box(): String {
    return "OK"
}
