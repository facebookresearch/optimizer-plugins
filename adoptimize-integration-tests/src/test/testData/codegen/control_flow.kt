package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun nested_if(a: DScalar, c: Float): DScalar {
    val i0 = a * a
    var b = i0 * i0
    when {
        c > 0.0 -> {
            b = b * i0
        }
        else -> {
            b = i0 * a
        }
    }
    val y = b * i0
    return y
}

fun manual_if(a: DScalar, c: Float): DScalar {
    val i0 = a * a
    var b = i0 * i0
    when {
        c > 0.0 -> {
            b = b * i0
        }
        else -> {
            b = i0 * a
        }
    }
    val y = b * i0
    return y
}

fun box(): String {
    val x = FloatScalar(1.15f)
    val constant = 0.0f
    val derivativePair = primalAndReverseDerivative(x, { c: DScalar -> manual_if(c, constant) })
    val primal = derivativePair.first
    val derivative = derivativePair.second
    val expected_derivativePair = primalAndReverseDerivative(x, { c: DScalar -> nested_if(c, constant) })
    val expected_derivative = expected_derivativePair.second
    val expectedPrimal = expected_derivativePair.first
    val tol = 0.000001f
    if (Math.abs(primal.basePrimal().value - expectedPrimal.basePrimal().value) > tol) {
        return "Primal FAIL: expected ${expectedPrimal.basePrimal().value} but got ${primal.basePrimal().value}"
    }
    if (Math.abs(derivative.basePrimal().value - expected_derivative.basePrimal().value) > tol) {
        return "Derivative FAIL: expected ${expected_derivative.basePrimal().value} but got ${derivative.basePrimal().value}"
    }
    return "OK"
}
