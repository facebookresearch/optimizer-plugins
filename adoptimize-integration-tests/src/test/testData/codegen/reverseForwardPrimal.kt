

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
        f = { z: DScalar -> primalAndReverseDerivative(z, ::target).first }
    )
    val primal = primal_derivative.first.basePrimal().value
    val firstOrderDerivative = primal_derivative.second.basePrimal().value
    val primal_derivative_expectation = primalAndForwardDerivative(
        x = x,
        f = { z: DScalar -> primalAndReverseDerivative(z, ::nonOptimal_target).first }
    )
    val expectedPrimal = primal_derivative_expectation.first.basePrimal().value
    val expectedFirstDerivative = primal_derivative_expectation.second.basePrimal().value
    val tol = 0.000001f
    return if (Math.abs(expectedPrimal - primal) > tol || Math.abs(firstOrderDerivative - expectedFirstDerivative) > tol) {
        "FAIL: should have instantiated an empty node: df: $primal , expected: $expectedPrimal df2: $firstOrderDerivative, $expectedFirstDerivative"
    } else "OK"
}
