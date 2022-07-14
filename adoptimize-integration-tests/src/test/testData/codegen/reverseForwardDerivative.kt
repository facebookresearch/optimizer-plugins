

package demo
import org.diffkt.*

annotation class Optimize
annotation class SecondOrderOptimize

@SecondOrderOptimize
fun target(x: DScalar): DScalar {
    val i0 = x * x
    val i1 = i0 * i0
    return i1
}

fun nonOptimal_target(x: DScalar): DScalar {
    val i0 = x * x
    val i1 = i0 * i0
    return i1
}

fun box(): String {
    val x = FloatScalar(2f)
    val primal_derivative = primalAndForwardDerivative(
        x = x,
        f = { z: DScalar -> primalAndReverseDerivative(z, ::target).second }
    )
    val firstOrderDerivative = primal_derivative.first.basePrimal().value
    val secondOrderDerivative = primal_derivative.second.basePrimal().value
    val primal_derivative_expectation = primalAndForwardDerivative(
        x = x,
        f = { z: DScalar -> primalAndReverseDerivative(z, ::nonOptimal_target).second }
    )
    val expectedSecondOrderDerivative = primal_derivative_expectation.second.basePrimal().value
    val expectedFirstOrderDerivative = primal_derivative_expectation.first.basePrimal().value
    val tol = 0.000001f
    return if (Math.abs(expectedFirstOrderDerivative - firstOrderDerivative) > tol || Math.abs(secondOrderDerivative - expectedSecondOrderDerivative) > tol) {
        "FAIL: should have instantiated an empty node: df: $firstOrderDerivative , expected: $expectedFirstOrderDerivative df2: $secondOrderDerivative, $expectedSecondOrderDerivative"
    } else "OK"
}
