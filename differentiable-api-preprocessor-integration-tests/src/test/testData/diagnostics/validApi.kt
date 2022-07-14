// WITH_RUNTIME
// !DIAGNOSTICS: -UNUSED_PARAMETER -DEBUG_INFO_MISSING_UNRESOLVED -DEBUG_INFO_LEAKING_THIS
// SKIP_TXT
// FIR_IDENTICAL
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
}

@BoxedPrimitive("value")
class DDouble(val value: Double) : DifferentiableDouble() {
    override val derivativeID = zeroDerivativeID
    override val primal: DifferentiableDouble = this
    override fun value(): Double = this.value
    companion object {
        val ZERO = DDouble(0.0)
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
class ForwardNode(d: DerivativeID, override val primal: DifferentiableDouble, val tangent: DifferentiableDouble) : DifferentiableDouble() {
    override val derivativeID: DerivativeID = d
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
