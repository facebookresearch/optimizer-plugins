/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import adOptimizeCommon.reverseNodeNameFromOperationsName
import diffPrep.metadata.DifferentiableApi
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import pluginCommon.generators.ClassFunctionAttributes
import pluginCommon.generators.overrideRoot
import pluginCommon.lowerings.ClassLifterDelegate

class DiffPrepClassLifterDelegate(
    val differentiableApi: DifferentiableApi
) : ClassLifterDelegate() {
    override fun shouldLiftClass(clazz: IrClass): Boolean {
        return clazz.parent is IrSimpleFunction && clazz.defaultType.isSubtypeOfClass(differentiableApi.reverseDifferentiableScalar.clazz.symbol)
    }

    override fun liftedClassName(originalClass: IrClass): String = reverseNodeNameFromOperationsName(originalClass.parent.kotlinFqName.toString())

    override fun customizeCopyOfMethod(oldMethod: IrSimpleFunction): ClassFunctionAttributes {
        val attributes = ClassFunctionAttributes(oldMethod)
        if (oldMethod.overrideRoot() == differentiableApi.reverseDifferentiableScalar.backpropMethod.overrideRoot()) {
            attributes.isInline = true
        }
        return attributes
    }
}
