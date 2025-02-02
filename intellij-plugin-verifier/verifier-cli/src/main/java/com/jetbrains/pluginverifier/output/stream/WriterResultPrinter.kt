/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.pluginverifier.output.stream

import com.jetbrains.pluginverifier.PluginVerificationResult
import com.jetbrains.pluginverifier.output.ResultPrinter
import com.jetbrains.pluginverifier.tasks.InvalidPluginFile
import com.jetbrains.pluginverifier.dymamic.DynamicPluginStatus
import java.io.PrintWriter

class WriterResultPrinter(private val out: PrintWriter) : ResultPrinter {

  private companion object {
    private const val INDENT = "    "
  }

  override fun printResults(results: List<PluginVerificationResult>) {
    results.forEach { result ->
      val plugin = result.plugin
      val verificationTarget = result.verificationTarget
      out.println("Plugin $plugin against $verificationTarget: ${result.verificationVerdict}")
      if (result is PluginVerificationResult.Verified) {
        out.println(result.printVerificationResult())
      }
    }
  }

  fun printInvalidPluginFiles(invalidPluginFiles: List<InvalidPluginFile>) {
    if (invalidPluginFiles.isNotEmpty()) {
      out.println("The following files specified for the verification are not valid plugins:")
      for ((pluginFile, pluginErrors) in invalidPluginFiles) {
        out.println("    $pluginFile")
        for (pluginError in pluginErrors) {
          out.println("        $pluginError")
        }
      }
    }
  }

  private fun PluginVerificationResult.Verified.printVerificationResult(): String = buildString {
    if (pluginStructureWarnings.isNotEmpty()) {
      appendLine("Plugin structure warnings (${pluginStructureWarnings.size}): ")
      for (warning in pluginStructureWarnings) {
        appendLine("$INDENT${warning.message}")
      }
    }

    val directMissingDependencies = dependenciesGraph.getDirectMissingDependencies()
    if (directMissingDependencies.isNotEmpty()) {
      appendLine("Missing dependencies: ")
      for (missingDependency in directMissingDependencies) {
        appendLine("$INDENT${missingDependency.dependency}: ${missingDependency.missingReason}")
      }
    }

    if (compatibilityWarnings.isNotEmpty()) {
      appendLine("Compatibility warnings (${compatibilityWarnings.size}): ")
      appendShortAndFullDescriptions(compatibilityWarnings.groupBy({ it.shortDescription }, { it.fullDescription }))
    }

    if (compatibilityProblems.isNotEmpty()) {
      appendLine("Compatibility problems (${compatibilityProblems.size}): ")
      appendShortAndFullDescriptions(compatibilityProblems.groupBy({ it.shortDescription }, { it.fullDescription }))
    }

    if (deprecatedUsages.isNotEmpty()) {
      appendLine("Deprecated API usages (${deprecatedUsages.size}): ")
      appendShortAndFullDescriptions(deprecatedUsages.groupBy({ it.shortDescription }, { it.fullDescription }))
    }

    if (experimentalApiUsages.isNotEmpty()) {
      appendLine("Experimental API usages (${experimentalApiUsages.size}): ")
      appendShortAndFullDescriptions(experimentalApiUsages.groupBy({ it.shortDescription }, { it.fullDescription }))
    }

    if (internalApiUsages.isNotEmpty()) {
      appendLine("Internal API usages (${internalApiUsages.size}): ")
      appendShortAndFullDescriptions(internalApiUsages.groupBy({ it.shortDescription }, { it.fullDescription }))
    }

    if (overrideOnlyMethodUsages.isNotEmpty()) {
      appendLine("Override-only API usages (${overrideOnlyMethodUsages.size}): ")
      appendShortAndFullDescriptions(overrideOnlyMethodUsages.groupBy({ it.shortDescription }, { it.fullDescription }))
    }

    if (nonExtendableApiUsages.isNotEmpty()) {
      appendLine("Non-extendable API usages (${nonExtendableApiUsages.size}): ")
      appendShortAndFullDescriptions(nonExtendableApiUsages.groupBy({ it.shortDescription }, { it.fullDescription }))
    }

    when (val dynamicPluginStatus = dynamicPluginStatus) {
      is DynamicPluginStatus.MaybeDynamic -> appendLine("${INDENT}Plugin can probably be enabled or disabled without IDE restart")
      is DynamicPluginStatus.NotDynamic -> appendLine("${INDENT}Plugin probably cannot be enabled or disabled without IDE restart: " + dynamicPluginStatus.reasonsNotToLoadUnloadWithoutRestart.joinToString())
      null -> Unit
    }

  }

  private fun StringBuilder.appendShortAndFullDescriptions(shortToFullDescriptions: Map<String, List<String>>) {
    val indent = "    "
    shortToFullDescriptions.forEach { (shortDescription, fullDescriptions) ->
      appendLine("$indent#$shortDescription")
      for (fullDescription in fullDescriptions) {
        fullDescription.lines().forEach { line ->
          appendLine("$indent$indent$line")
        }
      }
    }
  }

}
