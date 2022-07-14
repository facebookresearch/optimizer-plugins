package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(a: DScalar): DScalar {
    val i0 = a.pow(2f)
    var i1: DScalar = FloatScalar.ZERO
    if (i0 > 4f) {
        i1 = i1 + 3f
    } else {
        i1 = i1 + 4f
    }
    val i2 = i1 * i0
    return i2
}

fun nonOptimal_target(a: DScalar): DScalar {
    val i0 = a.pow(2f)
    var i1: DScalar = FloatScalar.ZERO
    if (i0 > 4f) {
        i1 = i1 + 3f
    } else {
        i1 = i1 + 4f
    }
    val i2 = i1 * i0
    return i2
}

fun box(): String {
    val x = FloatScalar(2.15f)
    val primal_derivative = primalAndReverseDerivative(x, { y: DScalar -> target(y) })
    val primal = primal_derivative.first
    val derivative = primal_derivative.second
    val expected_primal_derivative = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_target(y) })
    val expected_derivative = expected_primal_derivative.second
    val expected_primal = expected_primal_derivative.first
    val tol = 0.000001f
    if (Math.abs(derivative.basePrimal().value - expected_derivative.basePrimal().value) > tol) {
        return "FAIL: expected ${expected_derivative.basePrimal().value} but got ${derivative.basePrimal().value}"
    }
    if (Math.abs(primal.basePrimal().value - expected_primal.basePrimal().value) > tol) {
        return "FAIL: expected ${expected_primal.basePrimal().value} but got ${primal.basePrimal().value}"
    }
    return "OK"
}
