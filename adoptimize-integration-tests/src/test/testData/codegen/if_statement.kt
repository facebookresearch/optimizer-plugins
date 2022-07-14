package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun if_statement(a: DScalar, c: Float): DScalar {
    val i0 = a * a
    var b = i0 * i0
    if (c > 2.0f) {
        b = b * i0
    } else if (c == 1.0f) {
        b = b * b
    } else {
        b = b * a
    }
    return b * i0
}

fun nonOptimal_if_statement(a: DScalar, c: Float): DScalar {
    val i0 = a * a
    var b = i0 * i0
    if (c > 2.0f) {
        b = b * i0
    } else if (c == 1.0f) {
        b = b * b
    } else {
        b = b * a
    }
    return b * i0
}

fun box(): String {
    val x = FloatScalar(1.15f)
    val constant1 = 1.0f
    val constant2 = 3.0f
    val constant3 = -1f
    for (element in listOf(constant1, constant2, constant3).withIndex()) {
        val c = element.value
        val derivativePair = primalAndReverseDerivative(x, { y: DScalar -> if_statement(y, c) })
        val expected_derivativePair = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_if_statement(y, c) })
        val tol = 0.000001f

        if (Math.abs(derivativePair.first.basePrimal().value - expected_derivativePair.first.basePrimal().value) > tol) {
            return "PRIMAL FAIL: (index ${element.index}) expected ${expected_derivativePair.first.basePrimal().value} but got ${derivativePair.first.basePrimal().value}"
        }
        if (Math.abs(derivativePair.second.basePrimal().value - expected_derivativePair.second.basePrimal().value) > tol) {
            return "DERIVATIVE FAIL: (index ${element.index}) expected ${expected_derivativePair.second.basePrimal().value} but got ${derivativePair.second.basePrimal().value}"
        }
    }

    return "OK"
}
