/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.diagnostics.rendering.DiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.rendering.Renderers

class DiffPrepErrorMessagesExtension : DefaultErrorMessages.Extension {
    private val _map: DiagnosticFactoryToRendererMap by lazy {
        val renderMap = DiagnosticFactoryToRendererMap()
        renderMap.put(Errors.NO_UNBOXEDFUNCTION_FOUND, "The annotation references a function with whose signature is not compatible: {0}", Renderers.TO_STRING)
        renderMap.put(Errors.ANNOTATION_REFERENCES_UNRESOLVED_DECLARATIONS, "The annotation references unresolved declarations: {0}", Renderers.TO_STRING)
        renderMap.put(Errors.INVALID_SIGNATURE_ANNOTATION, "The annotation does not have the expected members: {0}", Renderers.TO_STRING)
        renderMap
    }

    override fun getMap(): DiagnosticFactoryToRendererMap {
        return _map
    }
}
