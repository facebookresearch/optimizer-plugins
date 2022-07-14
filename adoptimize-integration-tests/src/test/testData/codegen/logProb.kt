package demo
import org.diffkt.*
annotation class Optimize

class Normal {
    companion object {
        val lnSqrt2Pi = kotlin.math.ln(kotlin.math.sqrt(2.0 * 3.14159)).toFloat()
    }
}

@Optimize
fun logProbOf(value: DScalar, loc: FloatScalar, scale: FloatScalar): DScalar {
    val twoFloat = 2f
    val normal = Normal
    val variance = scale.pow(twoFloat)
    val constant = normal.lnSqrt2Pi
    val i0 = ln(scale)
    val i1 = i0 - constant
    val i2 = value - loc
    val i3 = i2.pow(twoFloat)
    val i4 = -i3
    val i5 = twoFloat * variance
    val i6 = i4 / i5
    val i7 = i6 - i1
    return i7
}

fun vanillaLogProbOf(value: DScalar, loc: FloatScalar, scale: FloatScalar): DScalar {
    val twoFloat = 2f
    val normal = Normal
    val variance = scale.pow(twoFloat)
    val constant = normal.lnSqrt2Pi
    val i0 = ln(scale)
    val i1 = i0 - constant
    val i2 = value - loc
    val i3 = i2.pow(twoFloat)
    val i4 = -i3
    val i5 = twoFloat * variance
    val i6 = i4 / i5
    val i7 = i6 - i1
    return i7
}

fun box(): String {
    val value = FloatScalar(1.715f)
    val loc = FloatScalar(3.0f)
    val scale = FloatScalar(2.5f)
    val (primal, derivative) = primalAndReverseDerivative(value, { y: DScalar -> logProbOf(y, loc, scale) })
    val (expected_primal, expected_derivative) = primalAndReverseDerivative(value, { y: DScalar -> vanillaLogProbOf(y, loc, scale) })
    val tol = 0.000001f
    if (Math.abs(primal.basePrimal().value - expected_primal.basePrimal().value) > tol) {
        return "PRIMAL FAIL: expected ${expected_primal.basePrimal().value} but got ${primal.basePrimal().value}"
    }
    if (Math.abs(derivative.basePrimal().value - expected_derivative.basePrimal().value) > tol) {
        return "DERIVATIVE FAIL: expected ${expected_derivative.basePrimal().value} but got ${derivative.basePrimal().value}."
    }
    return "OK"
}
