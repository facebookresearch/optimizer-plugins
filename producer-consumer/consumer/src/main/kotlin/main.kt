/*
 *
 *  Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 *  This source code is licensed under the MIT license found in the
 *  LICENSE file in the root directory of this source tree.
 *
 */

package consumer

import demo.*

annotation class Optimize

@Optimize
fun squared(a: DifferentiableDouble): DifferentiableDouble {
    return a * a
}

fun squaredVanilla(a: DifferentiableDouble): DifferentiableDouble {
    return a * a
}

fun main() {
    val x = DDouble(0.12)

    // reverse
    run {
        val optimized = primalAndReverseDerivative(x, ::squared)
        val vanilla = primalAndReverseDerivative(x, ::squaredVanilla)
        if (optimized.first.value() != vanilla.first.value()) {
            throw IllegalStateException("PRIMAL FAIL: expected ${vanilla.first.value()} but got ${optimized.first.value()}")
        }
        if (optimized.second.value() != vanilla.second.value()) {
            throw IllegalStateException("DERIVATIVE FAIL: expected ${vanilla.second.value()} but got ${optimized.second.value()}")
        }
    }

    // forward
    run {
        val optimized = primalAndForwardDerivative(x, ::squared)
        val vanilla = primalAndForwardDerivative(x, ::squaredVanilla)
        if (optimized.first.value() != vanilla.first.value()) {
            throw IllegalStateException("PRIMAL FAIL: expected ${vanilla.first.value()} but got ${optimized.first.value()}")
        }
        if (optimized.second.value() != vanilla.second.value()) {
            throw IllegalStateException("DERIVATIVE FAIL: expected ${vanilla.second.value()} but got ${optimized.second.value()}")
        }
    }
}
