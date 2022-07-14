/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

class ParameterInfo(val name: Name, val tpe: IrType)
