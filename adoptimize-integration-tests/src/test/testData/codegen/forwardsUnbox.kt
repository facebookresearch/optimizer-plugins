package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(dead: FloatScalar, a: DScalar): DScalar {
    val c = 2f
    val i0 = a.pow(c)
    val i1 = i0 + i0
    val i2 = i1 * i1
    return i2
}

fun nonOptimal_target(dead: FloatScalar, a: DScalar): DScalar {
    val c = 2f
    val i0 = a.pow(c)
    val i1 = i0 + i0
    val i2 = i1 * i1
    return i2
}

fun box(): String {
    val y = FloatScalar(2.15f)
    val dead = FloatScalar(3.0f)
    val result = target(dead, y)
    val expected = nonOptimal_target(dead, y)
    val tol = 0.000001f
    if (Math.abs(result.basePrimal().value - expected.basePrimal().value) > tol) {
        return "FAIL: expected ${expected.basePrimal().value} but got ${result.basePrimal().value}"
    }
    return "OK"
}
