package com.jetbrains.plugin.structure.dotnet.beans

import com.jetbrains.plugin.structure.dotnet.DotNetDependency
import com.jetbrains.plugin.structure.dotnet.ReSharperPlugin
import javax.xml.bind.annotation.*

@XmlRootElement(name = "package")
class NuspecDocumentBean {
  @get:XmlElement(name = "metadata")
  var metadata: ReSharperPluginBean? = null
}


@XmlAccessorType(XmlAccessType.PROPERTY)
class ReSharperPluginBean {
  @get:XmlElement(name = "id")
  var id: String? = null
  @get:XmlElement(name = "title")
  var title: String? = null
  @get:XmlElement(name = "version")
  var version: String? = null
  @get:XmlElement(name = "authors")
  var authors: String? = null
  @get:XmlElement(name = "summary")
  var summary: String? = null
  @get:XmlElement(name = "description")
  var description: String? = null
  @get:XmlElement(name = "projectUrl")
  var url: String? = null
  @get:XmlElement(name = "releaseNotes")
  var changeNotes: String? = null
  @get:XmlElement(name = "licenseUrl")
  var licenseUrl: String? = null
  @get:XmlElement(name = "copyright")
  var copyright: String? = null

  @get:XmlElement(name = "dependencies")
  var dependenciesBean: ReSharperPluginDependenciesBean? = null

  fun getAllDependencies() = dependenciesBean?.getAllDependencies() ?: emptyList()
}

@XmlAccessorType(XmlAccessType.PROPERTY)
class ReSharperPluginDependenciesBean {
  @get:XmlElement(name = "dependency")
  var dependencies: List<DotNetDependencyBean> = ArrayList()
  @get:XmlElement(name = "group")
  var dependencyGroups: List<GroupDependencyBean> = ArrayList()

  fun getAllDependencies(): List<DotNetDependencyBean> {
    return dependencies + dependencyGroups.map { it.dependencies }.flatten()
  }
}

@XmlAccessorType(XmlAccessType.PROPERTY)
class GroupDependencyBean {
  @get:XmlElement(name = "dependency")
  var dependencies: List<DotNetDependencyBean> = ArrayList()
}

@XmlAccessorType(XmlAccessType.PROPERTY)
class DotNetDependencyBean {
  @get:XmlAttribute(name = "id")
  lateinit var id: String
  @get:XmlAttribute(name = "version")
  lateinit var version: String
}

fun ReSharperPluginBean.toPlugin(): ReSharperPlugin {
  val id = this.id!!
  val idParts = id.split('.')
  val vendor = if (idParts.size > 1) idParts[0] else null
  val authors = authors!!.split(',').map { it.trim() }
  val pluginName = when {
    title != null -> title!!
    idParts.size > 1 -> idParts[1]
    else -> id
  }
  return ReSharperPlugin(
    pluginId = id, pluginName = pluginName, vendor = vendor, nonNormalizedVersion = this.version!!, url = this.url,
    changeNotes = this.changeNotes, description = this.description, vendorEmail = null, vendorUrl = null,
    authors = authors, licenseUrl = licenseUrl, copyright = copyright, summary = summary,
    dependencies = getAllDependencies().map { DotNetDependency(it.id!!, it.version) }
  )
}