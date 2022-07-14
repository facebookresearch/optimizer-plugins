package demo
import org.diffkt.*

annotation class Optimize

class Nodes(val singleNode: DScalar, val listOfSomething: List<Float>) {
    fun singleNode() = singleNode
}

@Optimize
fun target(a: DScalar, nodes: Nodes): DScalar {
    val x = nodes.listOfSomething[0]
    val operand = nodes.singleNode()
    val b = operand.pow(x)
    val c = a * b
    return c
}

fun nonOptimal_target(a: DScalar, nodes: Nodes): DScalar {
    val x = nodes.listOfSomething[0]
    val operand = nodes.singleNode()
    val b = operand.pow(x)
    val c = a * b
    return c
}

fun box(): String {
    val nodes = Nodes(FloatScalar(0.5f), listOf(2f))
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
