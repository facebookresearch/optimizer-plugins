package demo
import org.diffkt.*

annotation class Optimize
annotation class SecondOrderOptimize

@Optimize
@SecondOrderOptimize
fun target(a: DScalar, L: Int): DScalar {
    var i = 0
    var s = a
    while (i < L) {
        val z = s * s
        s = z
        i = i + 1
    }
    return s
}

fun nonOptimal_target(a: DScalar, L: Int): DScalar {
    var i = 0
    var s = a
    while (i < L) {
        val z = s * s
        s = z
        i = i + 1
    }
    return s
}

fun box(): String {
    val x = FloatScalar(1.15f)
    val constant1 = 4
    val constant2 = 7
    for (element in listOf(constant1, constant2).withIndex()) {
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
