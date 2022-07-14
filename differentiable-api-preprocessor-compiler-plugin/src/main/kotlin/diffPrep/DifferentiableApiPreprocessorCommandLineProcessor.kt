/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package diffPrep

import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.BOXED_PRIMITIVE_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.DTENSOR_ROOT_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.FORWARD_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.PULLBACK_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.RESOURCES_PATH_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.REVERSE_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.REVERSE_OPERATIONS_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.SCALAR_NOOP_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.SCALAR_ROOT_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.STACKIMPL_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.TO_REVERSE_ANNOTATION_CONFIGKEY
import diffPrep.DifferentiableApiPreprocessorConfigurationKeys.UNBOXED_FUNCTION_ANNOTATION_CONFIGKEY
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object DifferentiableApiPreprocessorConfigurationKeys {
    val REVERSE_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("ad reverse annotation.")
    val FORWARD_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("ad reverse annotation.")
    val PULLBACK_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("ad pullback annotation.")
    val STACKIMPL_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("stack implementation.")
    val BOXED_PRIMITIVE_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("boxed primitive type")
    val UNBOXED_FUNCTION_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("unboxed function annotation")
    val SCALAR_ROOT_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("unboxed function annotation")
    val RESOURCES_PATH_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("unboxed function annotation")
    val TO_REVERSE_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("to reverse node annotation")
    val DTENSOR_ROOT_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("root of the Tensor hierarchy")
    val REVERSE_OPERATIONS_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("implementation of reverse scalars")
    val SCALAR_NOOP_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("annotation to indicate that a function is a noop if the input is a scalar")
}

class DifferentiableApiPreprocessorCommandLineProcessor : CommandLineProcessor {
    companion object {
        val PLUGIN_ID = "differentiable-api-preprocessor"

        val REVERSE_ANNOTATION_OPTION: CliOption = CliOption(
            optionName = "reverse", valueDescription = "<fqname>",
            description = "fully qualified name of the annotation(s) to mark reverse node types",
            required = true, allowMultipleOccurrences = false
        )

        val FORWARD_ANNOTATION_OPTION: CliOption = CliOption(
            optionName = "forward", valueDescription = "<fqname>",
            description = "fully qualified name of the annotation to mark the forward node type",
            required = true, allowMultipleOccurrences = false
        )

        val RESOURCES_PATH_OPTION: CliOption = CliOption(
            optionName = "resourcesPath", valueDescription = "<absolutePath>",
            description = "absolute path to the main directory",
            required = true, allowMultipleOccurrences = false
        )

        val PULLBACK_ANNOTATION_OPTION: CliOption = CliOption(
            optionName = "primalAndPullback", valueDescription = "<fqname>",
            description = "fully qualified name of the annotation(s) to mark primal and pullback function. Signature: (input:T,function:(T) -> T) -> Pair<T,F>",
            required = true, allowMultipleOccurrences = false
        )

        val STACKIMPL_ANNOTATION_OPTION: CliOption = CliOption(
            optionName = "stackImpl", valueDescription = "<fqname>",
            description = "fully qualified name of the stack implementation",
            required = true, allowMultipleOccurrences = false
        )

        val BOXED_PRIMITIVE_OPTION: CliOption = CliOption(
            optionName = "boxedPrimitive", valueDescription = "<fqname>",
            description = "fully qualified name of the boxed primitive annotation"
        )

        val UNBOXED_FUNCTION_OPTION: CliOption = CliOption(
            optionName = "unboxedFunction", valueDescription = "<fqname>",
            description = "fully qualified name of the associated unboxed function annotation"
        )

        val SCALAR_ROOT_OPTION: CliOption = CliOption(
            optionName = "scalarRoot", valueDescription = "<fqname>",
            description = "fully qualified name of the scalar root of the diff hierarchy"
        )

        val TO_REVERSE_OPTION: CliOption = CliOption(
            optionName = "toReverseNode", valueDescription = "<fqname>",
            description = "fully qualified name of the annotation that marks a function as mappable to a reverse node."
        )

        val DTENSOR_ROOT_OPTION: CliOption = CliOption(
            optionName = "DTensorRoot", valueDescription = "<fqname>",
            description = "fully qualified name of the annotation that marks a class as the root of the differentiable tensor hierarchy."
        )

        val REVERSE_OPERATIONS_OPTION: CliOption = CliOption(
            optionName = "reverseOperations", valueDescription = "<fqname>",
            description = "fully qualified name of the annotation that marks a class as implementation of operations for reverse scalars."
        )

        val SCALAR_NOOP_OPTION: CliOption = CliOption(
            optionName = "scalarNoop", valueDescription = "<fqname>",
            description = "fully qualified name of the annotation that marks a function as a no-op if the input is a scalar"
        )
    }

    override val pluginId: String = PLUGIN_ID
    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        REVERSE_ANNOTATION_OPTION,
        FORWARD_ANNOTATION_OPTION,
        PULLBACK_ANNOTATION_OPTION,
        STACKIMPL_ANNOTATION_OPTION,
        BOXED_PRIMITIVE_OPTION,
        UNBOXED_FUNCTION_OPTION,
        SCALAR_ROOT_OPTION,
        RESOURCES_PATH_OPTION,
        TO_REVERSE_OPTION,
        DTENSOR_ROOT_OPTION,
        REVERSE_OPERATIONS_OPTION,
        SCALAR_NOOP_OPTION
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option) {
            REVERSE_ANNOTATION_OPTION -> configuration.appendList(REVERSE_ANNOTATION_CONFIGKEY, value)
            FORWARD_ANNOTATION_OPTION -> configuration.appendList(FORWARD_ANNOTATION_CONFIGKEY, value)
            PULLBACK_ANNOTATION_OPTION -> configuration.appendList(PULLBACK_ANNOTATION_CONFIGKEY, value)
            STACKIMPL_ANNOTATION_OPTION -> configuration.appendList(STACKIMPL_ANNOTATION_CONFIGKEY, value)
            BOXED_PRIMITIVE_OPTION -> configuration.appendList(BOXED_PRIMITIVE_ANNOTATION_CONFIGKEY, value)
            UNBOXED_FUNCTION_OPTION -> configuration.appendList(UNBOXED_FUNCTION_ANNOTATION_CONFIGKEY, value)
            SCALAR_ROOT_OPTION -> configuration.appendList(SCALAR_ROOT_ANNOTATION_CONFIGKEY, value)
            RESOURCES_PATH_OPTION -> configuration.appendList(RESOURCES_PATH_CONFIGKEY, value)
            TO_REVERSE_OPTION -> configuration.appendList(TO_REVERSE_ANNOTATION_CONFIGKEY, value)
            DTENSOR_ROOT_OPTION -> configuration.appendList(DTENSOR_ROOT_CONFIGKEY, value)
            REVERSE_OPERATIONS_OPTION -> configuration.appendList(REVERSE_OPERATIONS_CONFIGKEY, value)
            SCALAR_NOOP_OPTION -> configuration.appendList(SCALAR_NOOP_CONFIGKEY, value)
            else -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
        }
    }
}
