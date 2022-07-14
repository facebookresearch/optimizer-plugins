// WITH_RUNTIME
// SKIP_KT_DUMP
package demo

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
annotation class ForwardDifferentiable(val tangentProperty: String)

@DTensorRoot
interface DiffTensor
operator fun DiffTensor.plus(other: DiffTensor): DiffTensor { TODO() }

open class DerivativeID(private val seq: Int) : Comparable<DerivativeID> {
    override fun compareTo(other: DerivativeID): Int {
        return this.seq.compareTo(other.seq)
    }
}

class ReverseDerivativeID(s: Int) : DerivativeID(s) {
    val backpropogateWorkList: java.util.Stack<DifferentiableDouble> = java.util.Stack()
}

val zeroDerivativeID = DerivativeID(0)

@ScalarRoot
sealed class DifferentiableDouble : DiffTensor {
    abstract val primal: DifferentiableDouble
    open fun value(): Double = primal.value()
    abstract val derivativeID: DerivativeID
    fun zero(): DifferentiableDouble = DDouble(0.0)
    fun one(): DifferentiableDouble = DDouble(1.0)
}

@BoxedPrimitive("value")
class DDouble(val value: Double) : DifferentiableDouble() {
    override val derivativeID = zeroDerivativeID
    override val primal: DifferentiableDouble = this
    override fun value(): Double = this.value
    companion object {
        val ZERO = DDouble(0.0)
        val ONE = DDouble(1.0)
    }
}

@ReverseDifferentiable("primal", "upstream", "backpropogate", "pushback", "derivativeID")
abstract class ReverseNode(d: ReverseDerivativeID) : DifferentiableDouble() {
    var upstream: DifferentiableDouble = DDouble(0.0)
    override val derivativeID: ReverseDerivativeID = d
    abstract fun backpropogate()
    open override val primal: DifferentiableDouble = DDouble(0.0)
    fun pushback(value: DifferentiableDouble) {}
}

@ForwardDifferentiable("tangent")
class ForwardNode(val tangent: DifferentiableDouble, d: DerivativeID) : DifferentiableDouble() {
    override val derivativeID: DerivativeID = d
    override val primal: DifferentiableDouble = DDouble(0.0)
}

@StackImpl
class StackForCompiler<T> {
    fun pop(): T { TODO() }
    fun push(d: T) {}
    fun top(): T { TODO() }
    fun notEmpty(): Boolean { TODO() }
}

operator fun DifferentiableDouble.plus(other: DifferentiableDouble): DifferentiableDouble { TODO() }
operator fun DifferentiableDouble.times(other: DifferentiableDouble): DifferentiableDouble { TODO() }

@PrimalAndPullback
fun primalAndPullback(operand: DifferentiableDouble, operator: (DifferentiableDouble) -> DifferentiableDouble): DifferentiableDouble { TODO() }

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
        derivativeId as ReverseDerivativeID
        return object : ReverseNode(derivativeId) {
            override val primal: DifferentiableDouble
            init {
                val x = left.primal
                val y = right.primal
                this.primal = x + y
            }
            override inline fun backpropogate() {
                (left as ReverseNode).pushback(this.upstream)
                (right as ReverseNode).pushback(this.upstream)
            }
        }
    }
}
