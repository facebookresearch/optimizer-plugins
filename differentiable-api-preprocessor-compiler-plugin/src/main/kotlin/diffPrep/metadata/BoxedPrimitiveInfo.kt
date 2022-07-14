/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep.metadata

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.IrMessageLogger
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.properties

class BoxedPrimitiveInfo(
    val boxedPrimitiveClass: IrClass,
    val valueProperty: IrProperty,
    val primitiveType: IrType,
    val companionZero: IrProperty,
    val companionOne: IrProperty,
    messageLogger: IrMessageLogger
) {
    init {
        if (!boxedPrimitiveClass.properties.contains(valueProperty)) {
            messageLogger.report(IrMessageLogger.Severity.WARNING, "The value property must be a property of the boxedPrimitive.", null)
        }
        val companionZeroIsInCompanion = boxedPrimitiveClass.companionObject()?.let { companion ->
            companion.properties.contains(companionZero)
        } ?: false
        if (!companionZeroIsInCompanion) {
            messageLogger.report(IrMessageLogger.Severity.WARNING, "The zero property must be a property of the boxedPrimitive's companion.", null)
        }
        val companionOneIsInCompanion = boxedPrimitiveClass.companionObject()?.let { companion ->
            companion.properties.contains(companionOne)
        } ?: false
        if (!companionOneIsInCompanion) {
            messageLogger.report(IrMessageLogger.Severity.WARNING, "The one property must be a property of the boxedPrimitive's companion.", null)
        }
    }
}
