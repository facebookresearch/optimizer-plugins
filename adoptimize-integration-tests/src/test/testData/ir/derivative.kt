// WITH_RUNTIME
// SKIP_KT_DUMP
package demo
import org.diffkt.*

annotation class Optimize

@Optimize
fun target(a: DScalar): DScalar {
    val c = 2f
    val i0 = a.pow(c)
    val i1 = i0 + i0
    val i2 = i1 * i1
    return i2
}
