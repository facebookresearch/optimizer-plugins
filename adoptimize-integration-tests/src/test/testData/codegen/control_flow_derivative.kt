package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(a: DScalar, c: Double): DScalar {
    val i0 = a * a
    var b = i0 + i0
    when {
        c > 0.0 -> {
            val localVar = b + b
            b = localVar * b
        }
        else -> {
            b = b * b
        }
    }
    val y = b * i0
    return y
}

fun nonOptimal_target(a: DScalar, c: Double): DScalar {
    val i0 = a * a
    var b = i0 + i0
    when {
        c > 0.0 -> {
            val localVar = b + b
            b = localVar * b
        }
        else -> {
            b = b * b
        }
    }
    val y = b * i0
    return y
}

fun box(): String {
    val x = FloatScalar(1.15f)
    val constant1 = 0.5
    val constant2 = 0.0
    for (element in listOf(constant1, constant2).withIndex()) {
        val c = element.value
        val derivative = primalAndReverseDerivative(x, { y: DScalar -> target(y, c) }).second
        val expected_derivative = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_target(y, c) }).second
        val tol = 0.000001f
        if (Math.abs(derivative.basePrimal().value - expected_derivative.basePrimal().value) > tol) {
            return "FAIL: (index ${element.index}) expected ${expected_derivative.basePrimal().value} but got ${derivative.basePrimal().value}"
        }
    }

    return "OK"
}
