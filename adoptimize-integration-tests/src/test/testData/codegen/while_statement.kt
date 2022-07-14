
package demo
import org.diffkt.*
annotation class Optimize

@Optimize
fun target(a: DScalar, L: Int): DScalar {
    var b = a * a
    val i0 = b + a
    var i = 0
    var s = i0 + i0
    while (i < L) {
        val j0 = i % 3
        val j1 = i % 2
        when {
            j0 == 0 -> {
                when {
                    j1 == 0 -> {
                        s = s * b
                    }
                    else -> {
                        s = s * i0
                    }
                }
            }
            else -> {
                s = s * a
            }
        }
        i++
    }
    return s
}

fun nonOptimal_target(a: DScalar, L: Int): DScalar {
    var b = a * a
    val i0 = b + a
    var i = 0
    var s = i0 + i0
    while (i < L) {
        val j0 = i % 3
        val j1 = i % 2
        when {
            j0 == 0 -> {
                when {
                    j1 == 0 -> {
                        s = s * b
                    }
                    else -> {
                        s = s * i0
                    }
                }
            }
            else -> {
                s = s * a
            }
        }
        i++
    }
    return s
}

fun box(): String {
    val x = FloatScalar(1.15f)
    val constant1 = 4
    val constant2 = 7
    for (element in listOf(constant1, constant2).withIndex()) {
        val c = element.value
        val derivativePair = primalAndReverseDerivative(x, { y: DScalar -> target(y, c) })
        val expected_derivativePair = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_target(y, c) })
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
