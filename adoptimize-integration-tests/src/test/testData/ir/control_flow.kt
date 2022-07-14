// WITH_RUNTIME
// SKIP_KT_DUMP
package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun nested_if(a: DScalar, c: Float): DScalar {
    val temp0 = 0.0f
    val i0 = a * a
    var b = i0 * i0
    when {
        c > temp0 -> {
            b = b * i0
        }
        else -> {
            b = i0 * a
        }
    }
    val y = b * i0
    return y
}
