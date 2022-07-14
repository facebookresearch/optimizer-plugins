// WITH_RUNTIME
package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(a: DScalar): DScalar {
    val i2 = a * a
    return i2
}

fun targetVanilla(a: DScalar): DScalar {
    val i2 = a * a
    return i2
}

fun multipleOutputsUsesOptimized(a: DScalar): Pair<DScalar, DScalar> {
    val i0 = target(a)
    val i3 = a * cos(a)
    return Pair(i0, i3)
}

fun multipleOutputsUsesVanilla(a: DScalar): Pair<DScalar, DScalar> {
    val i0 = targetVanilla(a)
    val i3 = a * cos(a)
    return Pair(i0, i3)
}

typealias Extractor = (input: DTensor, output: DTensor) -> DTensor
fun box(): String {
    val x = FloatScalar(0.15f)
    val primal_derivative = primalAndReverseDerivative(
        x = x,
        f = ::multipleOutputsUsesOptimized,
        extractDerivative = {
            input: DScalar,
            output: Pair<DScalar, DScalar>,
            extractDerivatives: Extractor ->
            val dxdy1 = extractDerivatives(input, output.first)
            val dxdy2 = extractDerivatives(input, output.second)
            Pair(dxdy1, dxdy2)
        }
    )
    val primal = primal_derivative.first
    val derivative = primal_derivative.second
    val expected_primal_derivative = primalAndReverseDerivative(
        x = x,
        f = ::multipleOutputsUsesVanilla,
        extractDerivative = {
            input: DScalar, output: Pair<DScalar, DScalar>, extractDerivatives: (input: DTensor, output: DTensor) -> DTensor ->
            val dxdy1 = extractDerivatives(input, output.first)
            val dxdy2 = extractDerivatives(input, output.second)
            Pair(dxdy1, dxdy2)
        }
    )
    val expected_derivative = expected_primal_derivative.second
    val expected_primal = expected_primal_derivative.first
    val tol = 0.000001f
    for (output in listOf(Pair(primal.first, expected_primal.first), Pair(primal.second, expected_primal.second))) {
        if (Math.abs(output.first.basePrimal().value - output.second.basePrimal().value) > tol) {
            return "PRIMAL FAIL: expected ${output.first.basePrimal().value} but got ${output.second.basePrimal().value}"
        }
    }

    for (output in listOf(Pair(derivative.first as DScalar, expected_derivative.first as DScalar), Pair(derivative.second as DScalar, expected_derivative.second as DScalar))) {
        if (Math.abs(output.first.basePrimal().value - output.second.basePrimal().value) > tol) {
            return "PRIMAL FAIL: expected ${output.first.basePrimal().value} but got ${output.second.basePrimal().value}"
        }
    }
    return "OK"
}
