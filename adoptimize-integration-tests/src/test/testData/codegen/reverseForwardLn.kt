package demo
import org.diffkt.*
annotation class Optimize
annotation class SecondOrderOptimize

@SecondOrderOptimize
@Optimize
fun lnOf(value: DScalar): DScalar {
    return ln(value)
}

fun vanillaLnOf(value: DScalar): DScalar {
    return ln(value)
}

fun box(): String {
    val value = FloatScalar(1.715f)
    val (primal, derivative) = primalAndForwardDerivative(
        x = value,
        f = { z: DScalar -> primalAndReverseDerivative(z, { xx: DScalar -> lnOf(xx) }).second }
    )
    val (expected_primal, expected_derivative) = primalAndForwardDerivative(
        x = value,
        f = { z: DScalar -> primalAndReverseDerivative(z, { xx: DScalar -> vanillaLnOf(xx) }).second }
    )
    val tol = 0.000001f
    if (Math.abs(primal.basePrimal().value - expected_primal.basePrimal().value) > tol) {
        return "PRIMAL FAIL: expected ${expected_primal.basePrimal().value} but got ${primal.basePrimal().value}"
    }
    if (Math.abs(derivative.basePrimal().value - expected_derivative.basePrimal().value) > tol) {
        return "DERIVATIVE FAIL: expected ${expected_derivative.basePrimal().value} but got ${derivative.basePrimal().value}."
    }
    return "OK"
}
