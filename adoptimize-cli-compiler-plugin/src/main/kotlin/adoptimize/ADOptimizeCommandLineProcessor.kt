/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package adoptimize

import adoptimize.ADOptimizeConfigurationKeys.DIFF_API_ANNOTATION_CONFIGKEY
import adoptimize.ADOptimizeConfigurationKeys.FAIL_ON_ADEXCEPTION_CONFIGKEY
import adoptimize.ADOptimizeConfigurationKeys.OPT_ANNOTATION_CONFIGKEY
import adoptimize.ADOptimizeConfigurationKeys.OPT_SECOND_ORDER_ANNOTATION_CONFIGKEY
import adoptimize.ADOptimizeConfigurationKeys.REVERSE_AD_CONFIGKEY
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

object ADOptimizeConfigurationKeys {
    val OPT_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("ad optimize annotation.")
    val DIFF_API_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("diff api")
    val OPT_SECOND_ORDER_ANNOTATION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("second order optimization")
    val FAIL_ON_ADEXCEPTION_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("second order optimization")
    val REVERSE_AD_CONFIGKEY: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey<List<String>>("sct function")
}

class ADOptimizeCommandLineProcessor : CommandLineProcessor {
    companion object {
        val PLUGIN_ID = "adoptimize"

        val OPT_ANNOTATION_OPTION: CliOption = CliOption(
            optionName = "optimize", valueDescription = "<fqname>",
            description = "fully qualified name of the annotation(s) to mark ad code to optimize",
            required = true, allowMultipleOccurrences = false
        )

        val OPT_SECOND_ORDER_ANNOTATION_OPTION: CliOption = CliOption(
            optionName = "secondOrderOptimize", valueDescription = "<fqname>",
            description = "fully qualified name of the annotation to mark ad code to optimize to the second degree. Single variable only",
            required = false, allowMultipleOccurrences = false
        )

        val DIFF_API_ANNOTATION_OPTION: CliOption = CliOption(
            optionName = "diffApi", valueDescription = "<fqname>",
            description = "Name of the differentiable library dependency. Expected format: {group}.{name}:{version}",
            required = true, allowMultipleOccurrences = false
        )

        val FAIL_ON_ADEXCEPTION_OPTION: CliOption = CliOption(
            optionName = "failOnADFail", valueDescription = "boolean",
            description = "When true, fail compilation when an exception is thrown from the AD extension point.",
            required = true, allowMultipleOccurrences = false
        )

        val REVERSE_AD_OPTION: CliOption = CliOption(
            optionName = "reverseADFunction", valueDescription = "string",
            description = "name of function that accepts a function (Float) -> Float and returns the derivative, a Float",
            required = true, allowMultipleOccurrences = false
        )
    }

    override val pluginId: String = PLUGIN_ID
    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        OPT_ANNOTATION_OPTION,
        OPT_SECOND_ORDER_ANNOTATION_OPTION,
        DIFF_API_ANNOTATION_OPTION,
        FAIL_ON_ADEXCEPTION_OPTION,
        REVERSE_AD_OPTION
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option) {
            OPT_ANNOTATION_OPTION -> configuration.appendList(OPT_ANNOTATION_CONFIGKEY, value)
            OPT_SECOND_ORDER_ANNOTATION_OPTION -> configuration.appendList(OPT_SECOND_ORDER_ANNOTATION_CONFIGKEY, value)
            DIFF_API_ANNOTATION_OPTION -> configuration.appendList(DIFF_API_ANNOTATION_CONFIGKEY, value)
            FAIL_ON_ADEXCEPTION_OPTION -> configuration.appendList(FAIL_ON_ADEXCEPTION_CONFIGKEY, value)
            REVERSE_AD_OPTION -> configuration.appendList(REVERSE_AD_CONFIGKEY, value)
            else -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
        }
    }
}
