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
class DiffTensor
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
sealed class DifferentiableDouble {
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
    abstract override val derivativeID: ReverseDerivativeID
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

@PrimalAndPullback
fun primalAndPullback(operand: DifferentiableDouble, operator: (DifferentiableDouble) -> DifferentiableDouble): DifferentiableDouble { TODO() }
@ToReverse("demo.ReverseTimes")
operator fun DifferentiableDouble.times(that: DifferentiableDouble): DifferentiableDouble = that

class ReverseTimes(
    val left: ReverseNode?,
    val right: ReverseNode?,
    val leftPrimal: DifferentiableDouble,
    val rightPrimal: DifferentiableDouble,
    override val derivativeID: ReverseDerivativeID
) : ReverseNode(derivativeID) {
    override val primal: DifferentiableDouble
    init {
        val x = this.leftPrimal
        val y = this.rightPrimal
        this.primal = x * y
    }
    override inline fun backpropogate() {
        left?.pushback(this.upstream * this.rightPrimal)
        right?.pushback(this.upstream * this.leftPrimal)
    }
}

@ReverseOperations
class ReverseScalarOperations
