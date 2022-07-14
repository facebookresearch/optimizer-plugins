package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(a: DScalar): DScalar {
    val b = cos(a)
    val x = if (b >= 0f) {
        val i0 = a * a
        val i3 = i0 * b
        i3
    } else {
        a
    }
    val w = when {
        b >= 0f -> {
            val i1 = a * a
            i1
        }
        else -> {
            val i2 = a + a
            i2
        }
    }
    val z = w * x
    return z
}

fun nonOptimal_target(a: DScalar): DScalar {
    val b = cos(a)
    val x = if (b >= 0f) {
        val i0 = a * a
        val i3 = i0 * b
        i3
    } else {
        a
    }
    val w = when {
        b >= 0f -> {
            val i1 = a * a
            i1
        }
        else -> {
            val i2 = a + a
            i2
        }
    }
    val z = w * x
    return z
}

fun box(): String {
    val x1 = FloatScalar(2.15f)
    val x2 = FloatScalar(0.5f)
    for (input in listOf(x1, x2)) {
        val primal_derivative = primalAndReverseDerivative(input, { y: DScalar -> target(y) })
        val primal = primal_derivative.first
        val derivative = primal_derivative.second
        val expected_primal_derivative = primalAndReverseDerivative(input, { y: DScalar -> nonOptimal_target(y) })
        val expected_derivative = expected_primal_derivative.second
        val expected_primal = expected_primal_derivative.first
        val tol = 0.000001f
        if (Math.abs(derivative.basePrimal().value - expected_derivative.basePrimal().value) > tol) {
            return "FAIL: expected ${expected_derivative.basePrimal().value} but got ${derivative.basePrimal().value}"
        }
        if (Math.abs(primal.basePrimal().value - expected_primal.basePrimal().value) > tol) {
            return "FAIL: expected ${expected_primal.basePrimal().value} but got ${primal.basePrimal().value}"
        }
    }

    return "OK"
}
