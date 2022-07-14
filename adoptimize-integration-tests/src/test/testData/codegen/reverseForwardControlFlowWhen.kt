package demo
import org.diffkt.*

annotation class Optimize
annotation class SecondOrderOptimize

@Optimize
@SecondOrderOptimize
fun target(a: DScalar, c: Float): DScalar {
    var b = a + a
    when {
        c > 2.0f -> { b = b * b }
        else -> { b = b + a }
    }
    return b * a
}

fun nonOptimal_target(a: DScalar, c: Float): DScalar {
    var b = a + a
    when {
        c > 2.0f -> b = b * b
        else -> b = b + a
    }
    return b * a
}

fun box(): String {
    val x = FloatScalar(2f)
    val constant1 = 1.0f
    val constant2 = 3.0f
    val constant3 = -1f
    val tol = 0.000001f
    for (element in listOf(constant1, constant2, constant3).withIndex()) {
        val c = element.value
        val primal_derivative1 = primalAndForwardDerivative(
            x = x,
            f = { z: DScalar -> target(z, c) }
        )
        val primal1 = primal_derivative1.first.basePrimal().value
        val firstOrderDerivative = primal_derivative1.second.basePrimal().value
        val primal_derivative_expectation1 = primalAndForwardDerivative(
            x = x,
            f = { z: DScalar -> nonOptimal_target(z, c) }
        )
        val expectedDerivative1 = primal_derivative_expectation1.second.basePrimal().value
        val expectedPrimal1 = primal_derivative_expectation1.first.basePrimal().value

        if (Math.abs(primal1 - expectedPrimal1) > tol) {
            return "PRIMAL FAIL: (index ${element.index}) expected $expectedPrimal1 but got $primal1"
        }
        if (Math.abs(firstOrderDerivative - expectedDerivative1) > tol) {
            return "FIRST ORDER DERIVATIVE FAIL: (index ${element.index}) expected $expectedDerivative1 but got $firstOrderDerivative"
        }
        val primal_derivative = primalAndForwardDerivative(
            x = x,
            f = { z: DScalar -> primalAndReverseDerivative(z, { xx: DScalar -> target(xx, c) }).second }
        )
        val primal = primal_derivative.first.basePrimal().value
        val secondOrderDerivative = primal_derivative.second.basePrimal().value
        val primal_derivative_expectation = primalAndForwardDerivative(
            x = x,
            f = { z: DScalar -> primalAndReverseDerivative(z, { xx: DScalar -> nonOptimal_target(xx, c) }).second }
        )
        val expectedSecondOrderDerivative = primal_derivative_expectation.second.basePrimal().value
        val expectedPrimal = primal_derivative_expectation.first.basePrimal().value
        val tol = 0.000001f

        if (Math.abs(primal - expectedPrimal) > tol) {
            return "1 DERIVATIVE FAIL: (index ${element.index}) expected $expectedPrimal but got $primal"
        }
        if (Math.abs(secondOrderDerivative - expectedSecondOrderDerivative) > tol) {
            return "2 DERIVATIVE FAIL: (index ${element.index}) expected $expectedSecondOrderDerivative but got $secondOrderDerivative"
        }
    }

    return "OK"
}
