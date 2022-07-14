package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(a: DScalar): DScalar {
    val b = cos(a)
    var x = a
    if (b >= 0f) {
        x = a * b
    }
    return x
}

fun nonOptimal_target(a: DScalar): DScalar {
    val b = cos(a)
    var x = a
    if (b >= 0f) {
        x = a * b
    }
    return x
}

fun box(): String {
    val x1 = FloatScalar(2.15f)
    val x2 = FloatScalar(0.5f)
    for (input in listOf(x1, x2)) {
        val primal_derivative = primalAndReverseDerivative(input, { y: DScalar -> target(y) })
        val primal = primal_derivative.first
        val derivative = primal_derivative.second
        val expected_primal_derivative = primalAndReverseDerivative(input, { y: DScalar -> nonOptimal_target(y) })
        val expected_derivative = expected_primal_derivative.second
        val expected_primal = expected_primal_derivative.first
        val tol = 0.000001f
        if (Math.abs(derivative.basePrimal().value - expected_derivative.basePrimal().value) > tol) {
            return "FAIL: expected ${expected_derivative.basePrimal().value} but got ${derivative.basePrimal().value}"
        }
        if (Math.abs(primal.basePrimal().value - expected_primal.basePrimal().value) > tol) {
            return "FAIL: expected ${expected_primal.basePrimal().value} but got ${primal.basePrimal().value}"
        }
    }

    return "OK"
}
