package com.jetbrains.plugin.structure.dotnet

import com.jetbrains.plugin.structure.base.decompress.DecompressorSizeLimitExceededException
import com.jetbrains.plugin.structure.base.plugin.*
import com.jetbrains.plugin.structure.base.problems.*
import com.jetbrains.plugin.structure.base.utils.*
import com.jetbrains.plugin.structure.dotnet.beans.ReSharperPluginBeanExtractor
import com.jetbrains.plugin.structure.dotnet.beans.toPlugin
import com.jetbrains.plugin.structure.dotnet.problems.createIncorrectDotNetPluginFileProblem
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import org.xml.sax.SAXParseException
import java.io.File
import java.nio.file.Files

@Suppress("unused")
object ReSharperPluginManager : PluginManager<ReSharperPlugin> {

  private val LOG = LoggerFactory.getLogger(ReSharperPluginManager::class.java)

  override fun createPlugin(pluginFile: File): PluginCreationResult<ReSharperPlugin> {
    require(pluginFile.exists()) { "Plugin file $pluginFile does not exist" }
    return when (pluginFile.extension) {
      "nupkg" -> loadDescriptorFromNuPkg(pluginFile)
      else -> PluginCreationFail(createIncorrectDotNetPluginFileProblem(pluginFile.name))
    }
  }

  private fun loadDescriptorFromNuPkg(pluginFile: File): PluginCreationResult<ReSharperPlugin> {
    val sizeLimit = Settings.RE_SHARPER_PLUGIN_SIZE_LIMIT.getAsLong()
    if (FileUtils.sizeOf(pluginFile) > sizeLimit) {
      return PluginCreationFail(PluginFileSizeIsTooLarge(sizeLimit))
    }

    val extractDirectory = Settings.EXTRACT_DIRECTORY.getAsFile().toPath().createDir()
    val tempDirectory = Files.createTempDirectory(extractDirectory, pluginFile.nameWithoutExtension).toFile()
    return try {
      val extractedDirectory = tempDirectory.resolve("content")
      val withZipExtension = pluginFile.copyTo(tempDirectory.resolve("plugin.zip"))
      withZipExtension.extractTo(extractedDirectory, sizeLimit)
      loadDescriptorFromDirectory(extractedDirectory)
    } catch (e: DecompressorSizeLimitExceededException) {
      return PluginCreationFail(PluginFileSizeIsTooLarge(e.sizeLimit))
    } catch (e: Exception) {
      return PluginCreationFail(UnableToExtractZip())
    } finally {
      tempDirectory.deleteLogged()
    }
  }

  private fun loadDescriptorFromDirectory(pluginDirectory: File): PluginCreationResult<ReSharperPlugin> {
    val candidateDescriptors = pluginDirectory.listRecursivelyAllFilesWithExtension("nuspec")
    var descriptorFile: File? = null
    for (entry in candidateDescriptors) {
      if (descriptorFile != null) {
        return PluginCreationFail(MultiplePluginDescriptors(descriptorFile.name, "plugin.nupkg", entry.name, "plugin.nupkg"))
      }
      descriptorFile = entry
    }

    if (descriptorFile == null) {
      return PluginCreationFail(PluginDescriptorIsNotFound("*.nuspec"))
    }
    return loadDescriptor(descriptorFile)
  }

  private fun loadDescriptor(descriptorFile: File): PluginCreationResult<ReSharperPlugin> {
    try {
      val bean = descriptorFile.inputStream().buffered().use { ReSharperPluginBeanExtractor.extractPluginBean(it) }
      val beanValidationResult = validateDotNetPluginBean(bean)
      if (beanValidationResult.any { it.level == PluginProblem.Level.ERROR }) {
        return PluginCreationFail(beanValidationResult)
      }
      return PluginCreationSuccess(bean.toPlugin(), beanValidationResult)
    } catch (e: SAXParseException) {
      val lineNumber = e.lineNumber
      val message = if (lineNumber != -1) "unexpected element on line $lineNumber" else "unexpected elements"
      return PluginCreationFail(UnexpectedDescriptorElements(message))
    } catch (e: Exception) {
      e.rethrowIfInterrupted()
      LOG.info("Unable to read plugin descriptor: ${descriptorFile.name}", e)
      return PluginCreationFail(UnableToReadDescriptor(descriptorFile.name, e.localizedMessage))
    }
  }
}