package demo
import org.diffkt.*
annotation class Optimize

@Optimize
fun target(dead: FloatScalar, a: DScalar): DScalar {
    val c1 = 2f
    var c2 = c1
    val c = c2
    val i0 = a.pow(c)
    val i1 = i0 + i0
    val i2 = i1 * i1
    return i2
}

fun nonOptimal_target(dead: FloatScalar, a: DScalar): DScalar {
    val i0 = a.pow(2f)
    val i1 = i0 + i0
    val i2 = i1 * i1
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
