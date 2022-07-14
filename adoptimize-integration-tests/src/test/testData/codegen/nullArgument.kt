package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(dead: FloatScalar, a: DScalar): DScalar {
    val constant = 5.0f
    val i2 = constant * a
    return i2
}

fun nonOptimal_target(dead: FloatScalar, a: DScalar): DScalar {
    val constant = 5.0f
    val i2 = constant * a
    return i2
}

fun box(): String {
    val x = FloatScalar(2.15f)
    val dead = FloatScalar(3.0f)
    val derivative = primalAndReverseDerivative(x, { y: DScalar -> target(dead, y) }).second
    val expected_derivative = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_target(dead, y) }).second
    val tol = 0.000001
    if (Math.abs(derivative.basePrimal().value - expected_derivative.basePrimal().value) > tol) {
        return "FAIL: expected ${expected_derivative.basePrimal().value} but got ${derivative.basePrimal().value}"
    }
    return "OK"
}
