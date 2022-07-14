/*
 *
 *  Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 *  This source code is licensed under the MIT license found in the
 *  LICENSE file in the root directory of this source tree.
 *
 */

package client

import config.Optimize
import config.ReverseAD
import config.SecondOrderOptimize
import org.diffkt.*
import java.lang.IllegalStateException
import kotlin.math.cos
import kotlin.math.pow

@ReverseAD
fun jacobian_transposed_vector_product(x: Float, f: (Float) -> Float): Float {
    TODO()
}

@Optimize
fun target(a: Float): Float {
    val i0 = a.pow(2f)
    val i1 = i0 + i0
    val i2 = cos(i1)
    return i2
}

@SecondOrderOptimize
@Optimize
fun target(a: DScalar): DScalar {
    val i0 = a.pow(2f)
    val i1 = i0 + i0
    val i2 = cos(i1)
    return i2
}

fun nonOptimal_target(a: DScalar): DScalar {
    val i0 = a.pow(2f)
    val i1 = i0 + i0
    val i2 = cos(i1)
    return i2
}

fun box(): String {
    val x = FloatScalar(2.15f)
    val floatDerivative = jacobian_transposed_vector_product(x.value, ::target)
    val derivative = primalAndReverseDerivative(x, { t: DScalar -> target(t) })
    val secondOrderDerivative = primalAndForwardDerivative(
        x = x,
        f = { z: DScalar -> primalAndReverseDerivative(z, ::target).second }
    )
    val secondOrderDerivativeExpectation: Pair<DScalar, DScalar> = primalAndForwardDerivative(
        x = x,
        f = { z: DScalar -> primalAndReverseDerivative(z, ::nonOptimal_target).second }
    )
    val expected_derivative = primalAndReverseDerivative(x, { t: DScalar -> nonOptimal_target(t) })
    val tol = 0.000001

    if (Math.abs(derivative.first.basePrimal().value - expected_derivative.first.basePrimal().value) > tol) {
        return "PRIMAL FAIL: expected ${expected_derivative.first.basePrimal().value} but got ${derivative.first.basePrimal().value}"
    }
    if (Math.abs(secondOrderDerivative.first.basePrimal().value - secondOrderDerivativeExpectation.first.basePrimal().value) > tol) {
        return "FIRST Derivative FAIL: expected ${secondOrderDerivativeExpectation.first.basePrimal().value} but got ${secondOrderDerivative.first.basePrimal().value}"
    }
    if (Math.abs(secondOrderDerivative.second.basePrimal().value - secondOrderDerivativeExpectation.second.basePrimal().value) > tol) {
        return "Second Derivative FAIL: expected ${secondOrderDerivativeExpectation.second.basePrimal().value} but got ${secondOrderDerivative.second.basePrimal().value}"
    }
    if (Math.abs(derivative.second.basePrimal().value - expected_derivative.second.basePrimal().value) > tol) {
        return "DERIVATIVE FAIL: expected ${expected_derivative.second.basePrimal().value} but got ${derivative.second.basePrimal().value}"
    }
    if (Math.abs(derivative.second.basePrimal().value - floatDerivative) > tol) {
        return "Float derivative FAIL: expected ${derivative.first.basePrimal().value} but got $floatDerivative"
    }

    return "OK"
}

fun main() {
    val outcome = box()
    if (outcome != "OK") {
        throw IllegalStateException("Box test failed: $outcome")
    }
}
