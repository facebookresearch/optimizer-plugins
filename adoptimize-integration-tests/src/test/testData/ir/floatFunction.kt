// WITH_RUNTIME
// SKIP_KT_DUMP
package demo
import org.diffkt.*

annotation class Optimize
annotation class ReverseAD

@Optimize
fun foo(a: Float): Float {
    return a * a
}

@ReverseAD
fun jacobian_transposed_vector_product(x: Float, f: (Float) -> Float): Float {
    TODO()
}

fun box() {
    val derivative = jacobian_transposed_vector_product(2.15f, ::foo)
}
