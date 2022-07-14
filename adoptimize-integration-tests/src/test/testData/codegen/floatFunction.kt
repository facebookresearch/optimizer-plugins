package demo
import org.diffkt.*

annotation class Optimize
annotation class ReverseAD

@Optimize
fun foo(a: Float): Float {
    val i0 = a + a
    val x: Float
    if (i0 < 2f) {
        x = a * a
    } else {
        x = a
    }
    return i0 * x
}

fun nonOptimal_foo(a: DScalar): DScalar {
    val i0 = a + a
    val x: DScalar
    if (i0 < 2f) {
        x = a * a
    } else {
        x = a
    }
    return i0 * x
}

@ReverseAD
fun jacobian_transposed_vector_product(x: Float, f: (Float) -> Float): Float {
    TODO()
}

fun box(): String {
    val x = FloatScalar(2.15f)
    val derivative: Float = jacobian_transposed_vector_product(x.basePrimal().value, ::foo)
    val expected_primal_derivative = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_foo(y) })
    val expected_derivative = expected_primal_derivative.second
    val expected_primal = expected_primal_derivative.first
    val tol = 0.000001f
    if (Math.abs(derivative - expected_derivative.basePrimal().value) > tol) {
        return "FAIL: expected ${expected_derivative.basePrimal().value} but got $derivative"
    }
    return "OK"
}
