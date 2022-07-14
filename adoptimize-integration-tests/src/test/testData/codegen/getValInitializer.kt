package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(a: DScalar): DScalar {
    val x = a * a
    var s = a
    s = x
    val z1 = s * s
    val z2 = z1 * x
    return z2
}

fun vanilla_target(a: DScalar): DScalar {
    val x = a * a
    var s = a
    s = x
    val z1 = s * s
    val z2 = z1 * x
    return z2
}

fun box(): String {
    val x = FloatScalar(1.15f)
    val derivativePair = primalAndReverseDerivative(x, { y: DScalar -> target(y) })
    val expected_derivativePair = primalAndReverseDerivative(x, { y: DScalar -> vanilla_target(y) })
    val tol = 0.000001f

    if (Math.abs(derivativePair.first.basePrimal().value - expected_derivativePair.first.basePrimal().value) > tol) {
        return "PRIMAL FAIL: expected ${expected_derivativePair.first.basePrimal().value} but got ${derivativePair.first.basePrimal().value}"
    }
    if (Math.abs(derivativePair.second.basePrimal().value - expected_derivativePair.second.basePrimal().value) > tol) {
        return "DERIVATIVE FAIL: expected ${expected_derivativePair.second.basePrimal().value} but got ${derivativePair.second.basePrimal().value}"
    }
    return "OK"
}
