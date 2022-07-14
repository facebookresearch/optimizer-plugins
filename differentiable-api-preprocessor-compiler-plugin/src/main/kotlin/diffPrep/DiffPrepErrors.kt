/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import org.jetbrains.kotlin.diagnostics.DiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass

class Errors {
    companion object {
        val NO_UNBOXEDFUNCTION_FOUND = DiagnosticFactory1.create<KtAnnotationEntry, String>(Severity.ERROR).also { it.initializeName("NO_UNBOXEDFUNCTION_FOUND") }
        val INVALID_SIGNATURE_ANNOTATION: DiagnosticFactory1<KtClass, String> = DiagnosticFactory1.create<KtClass, String>(Severity.ERROR).also { it.initializeName("INVALID_SIGNATURE_ANNOTATION") }
        val ANNOTATION_REFERENCES_UNRESOLVED_DECLARATIONS: DiagnosticFactory1<KtAnnotationEntry, String> = DiagnosticFactory1.create<KtAnnotationEntry, String>(Severity.ERROR).also { it.initializeName("ANNOTATION_REFERENCES_UNRESOLVED_DECLARATIONS") }
    }
}
