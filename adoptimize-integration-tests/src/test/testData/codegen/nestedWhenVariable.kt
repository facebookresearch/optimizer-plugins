package demo
import org.diffkt.*
annotation class Optimize
annotation class SecondOrderOptimize

@Optimize
@SecondOrderOptimize
fun target(a: DScalar, c: Double): DScalar {
    val u = FloatScalar.ZERO
    val g1 = if (c > 0) {
        if (c > 0.5) {
            val b = if (c > 0.56) {
                a
            } else {
                a + a
            }
            b
        } else {
            if (c >= 0.1) {
                a + a
            } else {
                -a
            }
        }
    } else u
    return g1
}

fun nonOptimal_target(a: DScalar, c: Double): DScalar {
    val u = FloatScalar.ZERO
    val g1 = if (c > 0) {
        if (c > 0.5) {
            val b = if (c > 0.56) {
                a
            } else {
                a + a
            }
            b
        } else {
            if (c >= 0.1) {
                a + a
            } else {
                -a
            }
        }
    } else u
    return g1
}

fun box(): String {
    val x = FloatScalar(1.15f)
    val constant1 = 0.01
    val constant4 = 0.2
    val constant2 = 1.0
    val constant3 = -0.5
    for (element in listOf(constant1, constant2, constant3, constant4, 0.57).withIndex()) {
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
