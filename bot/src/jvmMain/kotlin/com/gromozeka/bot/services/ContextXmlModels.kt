package com.gromozeka.bot.services

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("contexts")
data class ContextsXml(
    @XmlElement(true)
    val context: List<ContextItemXml>,
)

@Serializable
@XmlSerialName("context")
data class ContextItemXml(
    @XmlElement(true) val name: String,
    val files: FilesXml? = null,
    val links: LinksXml? = null,
    @XmlElement(true) val content: String,
)

@Serializable
@XmlSerialName("files")
data class FilesXml(
    @XmlElement(true)
    val file: List<FileXml> = emptyList(),
)

@Serializable
@XmlSerialName("file")
data class FileXml(
    @XmlSerialName("path", "", "")
    val path: String = "",
    @XmlElement(true)
    val item: List<String> = emptyList(),
    val value: String? = null,
) {
    val content: String? get() = value?.takeIf { it.isNotBlank() }
    val isReadFull: Boolean get() = content?.trim() == "readfull"
    val specificItems: List<String> get() = if (item.isNotEmpty()) item else emptyList()
}

@Serializable
@XmlSerialName("links")
data class LinksXml(
    @XmlElement(true)
    val link: List<String> = emptyList(),
)

data class ContextItem(
    val name: String,
    val projectPath: String,
    val files: Map<String, ContextFileSpec>,
    val links: List<String> = emptyList(),
    val content: String,
    val extractedAt: String? = null,
)

sealed class ContextFileSpec {
    object ReadFull : ContextFileSpec()
    data class Specific(val items: List<String>) : ContextFileSpec()
}

data class ExtractedContexts(
    val contexts: List<ContextItem>,
)