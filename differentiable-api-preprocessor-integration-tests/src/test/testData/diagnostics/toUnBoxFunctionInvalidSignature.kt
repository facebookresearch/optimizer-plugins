// WITH_RUNTIME
// !DIAGNOSTICS: -UNUSED_PARAMETER -DEBUG_INFO_MISSING_UNRESOLVED -DEBUG_INFO_LEAKING_THIS
// SKIP_TXT
// FIR_IDENTICAL
package demo

annotation class ReverseDifferentiable(
    val primalField:String,
    val upstreamField:String,
    val backpropogateMethod:String,
    val pushbackMethod:String,
    val derivativeID:String)

annotation class ScalarRoot
annotation class PrimalAndPullback
annotation class StackImpl
annotation class BoxedPrimitive(val valueField:String)
annotation class ToUnboxedFunction(val functionName:String)
annotation class DTensorRoot
annotation class ScalarNoop
annotation class ForwardDifferentiable(val tangentProperty:String)

@DTensorRoot
open class DiffTensor

@ForwardDifferentiable("tangent")
class ForwardNode(d: DerivativeID, override val primal: DifferentiableDouble, val tangent:DifferentiableDouble) : DifferentiableDouble() {
    override val derivativeID: DerivativeID = d
}

open class DerivativeID(private val seq:Int) : Comparable<DerivativeID> {
    override fun compareTo(other: DerivativeID): Int {
        return this.seq.compareTo(other.seq)
    }
}

class ReverseDerivativeID(s:Int) : DerivativeID(s) {
    val backpropogateWorkList: java.util.Stack<DifferentiableDouble> = java.util.Stack()
}

val zeroDerivativeID = DerivativeID(0)

@ScalarRoot
sealed class DifferentiableDouble : DiffTensor() {
    abstract val primal:DifferentiableDouble
    open fun value():Double = primal.value()
    abstract val derivativeID: DerivativeID
    fun zero():DifferentiableDouble = DDouble(0.0)
}

@BoxedPrimitive("value")
class DDouble(val value:Double) : DifferentiableDouble() {
    override val derivativeID = zeroDerivativeID
    override val primal: DifferentiableDouble = this
    override fun value(): Double = this.value
}

@ReverseDifferentiable("primal", "upstream", "backpropogate", "pushback", "derivativeID")
abstract class ReverseNode(d: ReverseDerivativeID) : DifferentiableDouble() {
    var upstream:DifferentiableDouble = DDouble(0.0)
    abstract override val derivativeID:ReverseDerivativeID
    abstract fun backpropogate()
    open override val primal: DifferentiableDouble  = DDouble(0.0)
    fun pushback(value:DifferentiableDouble) {}
}

@StackImpl
class StackForCompiler<T> {
    fun pop():T {TODO()}
    fun push(d:T){}
    fun top():T {TODO()}
    fun notEmpty():Boolean {TODO()}
}

operator fun DifferentiableDouble.plus(other:DifferentiableDouble):DifferentiableDouble {TODO()}

@PrimalAndPullback
fun primalAndPullback(operand:DifferentiableDouble, operator:(DifferentiableDouble) -> DifferentiableDouble):DifferentiableDouble {TODO()}

<!NO_UNBOXEDFUNCTION_FOUND!>@ToUnboxedFunction("demo.referencedUnboxableReturnType")<!>
fun referencesUnboxableReturnType(x:DifferentiableDouble):DifferentiableDouble{TODO()}
fun referencedUnboxableReturnType(y:Double):Int {TODO()}

<!NO_UNBOXEDFUNCTION_FOUND!>@ToUnboxedFunction("demo.referencedUnboxableParameter")<!>
fun referencesUnboxableParameter(x:DifferentiableDouble, x2:DifferentiableDouble):DifferentiableDouble{TODO()}
fun referencedUnboxableParameter(y:Char, x:Double):Double {TODO()}

<!NO_UNBOXEDFUNCTION_FOUND!>@ToUnboxedFunction("demo.referencedMismatchParameter")<!>
fun referencesMismatchParameter(x:DifferentiableDouble, z:Double):DifferentiableDouble{TODO()}
fun referencedMismatchParameter(y:Double, z:Int):Double {TODO()}

<!NO_UNBOXEDFUNCTION_FOUND!>@ToUnboxedFunction("demo.referencedMismatchParameterCount")<!>
fun referencesMismatchParameterCount(x:DifferentiableDouble, z:Double):DifferentiableDouble{TODO()}
fun referencedMismatchParameterCount():Double {TODO()}

@ToUnboxedFunction("demo.correct1Target")
fun correct1(a:DifferentiableDouble):DifferentiableDouble{TODO()}
fun correct1Target(a:Double):Double {TODO()}

@ToUnboxedFunction("demo.correct2Target")
fun DifferentiableDouble.correct2(a:DifferentiableDouble):DifferentiableDouble{TODO()}
fun correct2Target(a:Double, b:Double):Double {TODO()}

@ToUnboxedFunction("demo.correct3Target")
fun DiffTensor.correct3(a:DifferentiableDouble):DifferentiableDouble{TODO()}
fun correct3Target(a:Double, b:Double):Double {TODO()}