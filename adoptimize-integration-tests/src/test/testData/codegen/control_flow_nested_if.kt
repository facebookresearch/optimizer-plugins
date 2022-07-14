package demo
import org.diffkt.*
annotation class Optimize

@Optimize
fun target(a: DScalar, c: Double): DScalar {
    val i0 = a * a
    var b = i0 * i0
    var g = i0 + i0
    when {
        c > 2.25 -> {
            b = b * i0
        }
        else -> {
            when {
                c > 0.5 -> {
                    val localVar = g + g
                    g = localVar + g
                }
                else -> {
                    g = g * g
                }
            }
            b = g * b
        }
    }
    val y = b * g
    return y
}

fun nonOptimal_target(a: DScalar, c: Double): DScalar {
    val i0 = a * a
    var b = i0 * i0
    var g = i0 + i0
    when {
        c > 2.25 -> {
            b = b * i0
        }
        else -> {
            when {
                c > 0.5 -> {
                    val localVar = g + g
                    g = localVar + g
                }
                else -> {
                    g = g * g
                }
            }
            b = g * b
        }
    }
    val y = b * g
    return y
}

fun box(): String {
    val x = FloatScalar(1.15f)
    val constant1 = 2.5
    val constant2 = 0.25
    val constant3 = 1.5
    for (element in listOf(constant1, constant2, constant3).withIndex()) {
        val c = element.value
        val derivativePair = primalAndReverseDerivative(x, { y: DScalar -> target(y, c) })
        val expected_derivativePair = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_target(y, c) })
        val tol = 0.000001
        if (Math.abs(derivativePair.second.basePrimal().value - expected_derivativePair.second.basePrimal().value) > tol) {
            return "DERIVATIVE FAIL: (index ${element.index}) expected ${expected_derivativePair.second.basePrimal().value} but got ${derivativePair.second.basePrimal().value}"
        }
        if (Math.abs(derivativePair.first.basePrimal().value - expected_derivativePair.first.basePrimal().value) > tol) {
            return "PRIMAL FAIL: (index ${element.index}) expected ${expected_derivativePair.first.basePrimal().value} but got ${derivativePair.first.basePrimal().value}"
        }
    }

    return "OK"
}
