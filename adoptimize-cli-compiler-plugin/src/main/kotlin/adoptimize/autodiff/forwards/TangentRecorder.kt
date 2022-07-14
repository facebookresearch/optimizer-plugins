/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.forwards

import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration

class TangentRecorder {
    private val targetValueToTargetTangentProperty = mutableMapOf<IrValueDeclaration, IrProperty>()
    private val targetPropertyToTangentProperty = mutableMapOf<IrProperty, IrProperty>()
    operator fun set(targetValue: IrValueDeclaration, targetProperty: IrProperty) { targetValueToTargetTangentProperty[targetValue] = targetProperty }
    operator fun set(targetProperty: IrProperty, tangentProperty: IrProperty) { targetPropertyToTangentProperty[targetProperty] = tangentProperty }

    operator fun get(srcValue: IrValueDeclaration) = targetValueToTargetTangentProperty[srcValue]
    operator fun get(targetProperty: IrProperty) = targetPropertyToTangentProperty[targetProperty]
}
