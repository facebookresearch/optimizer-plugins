package demo
import org.diffkt.*
import org.diffkt.adOptimize.ToUnboxedFunction
annotation class Optimize

interface NodeType {
    val x: DScalar
}
class NodeTypeImpl(y: Float) : NodeType {
    override val x: DScalar = FloatScalar(y)
}
class Nodes(val children: List<NodeType>)

@ToUnboxedFunction("demo.getChildToFloat")
fun List<NodeType>.getChild(index: Int): DScalar = this[index].x
fun List<NodeType>.getChildToFloat(index: Int): Float = this[index].x.basePrimal().value

@Optimize
fun target(a: DScalar, nodes: Nodes): DScalar {
    var i = 0
    var s = a
    while (i < nodes.children.size) {
        val i0 = nodes.children.getChild(i)
        val z = s * i0
        s = z
        i = i + 1
    }
    return s
}

fun nonOptimal_target(a: DScalar, nodes: Nodes): DScalar {
    var i = 0
    var s = a
    while (i < nodes.children.size) {
        val i0 = nodes.children.getChild(i)
        val z = s * i0
        s = z
        i = i + 1
    }
    return s
}

fun box(): String {
    val nodes = Nodes((0 until 3).map { NodeTypeImpl(it.toFloat() / 100f) })
    val x = FloatScalar(1.15f)

    val derivativePair = primalAndReverseDerivative(x, { y: DScalar -> target(y, nodes) })
    val expected_derivativePair = primalAndReverseDerivative(x, { y: DScalar -> nonOptimal_target(y, nodes) })
    val tol = 0.000001f

    if (Math.abs(derivativePair.first.basePrimal().value - expected_derivativePair.first.basePrimal().value) > tol) {
        return "PRIMAL FAIL: expected ${expected_derivativePair.first.basePrimal().value} but got ${derivativePair.first.basePrimal().value}"
    }
    if (Math.abs(derivativePair.second.basePrimal().value - expected_derivativePair.second.basePrimal().value) > tol) {
        return "DERIVATIVE FAIL:expected ${expected_derivativePair.second.basePrimal().value} but got ${derivativePair.second.basePrimal().value}"
    }

    return "OK"
}
