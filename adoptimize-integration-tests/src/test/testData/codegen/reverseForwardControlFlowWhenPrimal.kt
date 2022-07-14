package demo
import org.diffkt.*

annotation class Optimize
annotation class SecondOrderOptimize

@Optimize
@SecondOrderOptimize
fun target(a: DScalar, c: Float): DScalar {
    var b = a
    if (c > 2.0f) {
        b = b * b
    } else {
        b = b * a
    }
    return b
}

fun nonOptimal_target(a: DScalar, c: Float): DScalar {
    var b = a
    if (c > 2.0f) {
        b = b * b
    } else {
        b = b * a
    }
    return b
}

fun box(): String {
    val x = FloatScalar(1.15f)
    val constant1 = 1.0f
    val constant2 = 3.0f
    val constant3 = -1f
    for (element in listOf(constant1, constant2, constant3).withIndex()) {
        val c = element.value
        val primal_derivative = primalAndForwardDerivative(
            x = x,
            f = { z: DScalar -> primalAndReverseDerivative(z, { xx: DScalar -> target(xx, c) }).first }
        )
        val primal = primal_derivative.first.basePrimal().value
        val firstOrderDerivative = primal_derivative.second.basePrimal().value
        val primal_derivative_expectation = primalAndForwardDerivative(
            x = x,
            f = { z: DScalar -> primalAndReverseDerivative(z, { xx: DScalar -> nonOptimal_target(xx, c) }).first }
        )
        val expectedFirstOrderDerivative = primal_derivative_expectation.second.basePrimal().value
        val expectedPrimal = primal_derivative_expectation.first.basePrimal().value
        val tol = 0.000001f

        if (Math.abs(primal - expectedPrimal) > tol) {
            return "PRIMAL FAIL: (index ${element.index}) expected $expectedPrimal but got $primal"
        }
        if (Math.abs(firstOrderDerivative - expectedFirstOrderDerivative) > tol) {
            return "DERIVATIVE FAIL: (index ${element.index}) expected $expectedFirstOrderDerivative but got $firstOrderDerivative"
        }
    }

    return "OK"
}
