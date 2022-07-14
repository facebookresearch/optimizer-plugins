/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize.autodiff.Metadata

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.properties

class BoxedPrimitiveInfo(
    val boxedPrimitiveClass: IrClass,
    val valueProperty: IrProperty,
    val primitiveType: IrType,
    val scalarZeroObjectProperty: IrProperty,
    val scalarOneObjectProperty: IrProperty
) {
    init {
        if (!boxedPrimitiveClass.properties.contains(valueProperty)) {
            throw IllegalStateException("The value property must be a property of the boxedPrimitive")
        }
    }
}
