package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(dead: FloatScalar, a: DScalar): DScalar {
    var s: DScalar = dead
    val i0 = a.pow(2f)
    s = i0
    val i1 = s + s
    val i2 = i1 * i1
    return i2
}

fun nonOptimal_target(dead: FloatScalar, a: DScalar): DScalar {
    var s: DScalar = dead
    val i0 = a.pow(2f)
    s = i0
    val i1 = s + s
    val i2 = i1 * i1
    return i2
}

fun box(): String {
    val x = FloatScalar(2.15f)
    val dead = FloatScalar(1.11f)
    val primal_derivative = primalAndReverseDerivative(x, { y: DScalar -> target(dead, y) })
    val primal = primal_derivative.first
    val derivative = primal_derivative.second

    val expected_primal_derivative = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_target(dead, y) })
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
