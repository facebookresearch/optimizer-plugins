package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(a: DScalar, b: FloatScalar): DScalar {
    val scalarNoop = b.publicFauxExpandToTangent(b)
    val i0 = sigmoid(a)
    return i0
}

fun nonOptimal_target(a: DScalar, b: FloatScalar): DScalar {
    val scalarNoop = b.publicFauxExpandToTangent(b)
    val i0 = sigmoid(a)
    return i0
}

@org.diffkt.adOptimize.ScalarNoop
fun DTensor.publicFauxExpandToTangent(tangent: DTensor): DTensor {
    return this
}

fun box(): String {
    val w = FloatScalar(1.23f)
    val x = FloatScalar(1.15f)
    val primal_derivative = primalAndReverseDerivative(x, { y: DScalar -> target(y, w) })
    val primal = primal_derivative.first
    val derivative = primal_derivative.second
    val expected_primal_derivative = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_target(y, w) })
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
