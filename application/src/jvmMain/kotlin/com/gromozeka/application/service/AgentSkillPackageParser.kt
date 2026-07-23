package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.shared.utils.sha256
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class AgentSkillPackageParser {
    private val yaml = Yaml(
        SafeConstructor(
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 50
                codePointLimit = MAX_SKILL_MD_BYTES.toInt()
            }
        )
    )

    fun parse(source: AgentSkillPackageSource): ParsedAgentSkillPackage {
        validateSkillName(source.directoryName, "Skill directory name")
        require(source.files.isNotEmpty()) { "Agent Skill package must contain files" }
        require(source.files.size <= MAX_FILES) {
            "Agent Skill package contains more than $MAX_FILES files"
        }

        val files = source.files.map { file ->
            val path = normalizeAgentSkillPath(file.path)
            require(file.content.size <= MAX_FILE_BYTES) {
                "Agent Skill file exceeds $MAX_FILE_BYTES bytes: $path"
            }
            AgentSkillFile(path, file.content.copyOf())
        }
        require(files.map(AgentSkillFile::path).distinct().size == files.size) {
            "Agent Skill package contains duplicate paths"
        }
        require(files.sumOf { it.content.size.toLong() } <= MAX_PACKAGE_BYTES) {
            "Agent Skill package exceeds $MAX_PACKAGE_BYTES bytes"
        }

        val skillFile = files.singleOrNull { it.path == SKILL_FILE }
            ?: error("Agent Skill package must contain exactly one root SKILL.md")
        require(skillFile.content.size <= MAX_SKILL_MD_BYTES) {
            "SKILL.md exceeds $MAX_SKILL_MD_BYTES bytes"
        }
        val skillMarkdown = decodeUtf8(SKILL_FILE, skillFile.content)
        val match = FRONTMATTER.matchEntire(skillMarkdown)
            ?: error("SKILL.md must start with YAML frontmatter delimited by --- lines")
        val frontmatter = parseFrontmatter(match.groupValues[1])
        val name = frontmatter.requiredString("name")
        validateSkillName(name, "Agent Skill name")
        require(name == source.directoryName) {
            "Agent Skill name '$name' must match directory '${source.directoryName}'"
        }
        val description = frontmatter.requiredString("description")
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "Agent Skill description exceeds $MAX_DESCRIPTION_LENGTH characters"
        }
        val license = frontmatter.optionalString("license")
        val compatibility = frontmatter.optionalString("compatibility")?.also {
            require(it.length <= MAX_COMPATIBILITY_LENGTH) {
                "Agent Skill compatibility exceeds $MAX_COMPATIBILITY_LENGTH characters"
            }
        }
        val metadata = frontmatter.optionalStringMap("metadata")
        val allowedTools = frontmatter.optionalString("allowed-tools")
        val instructions = match.groupValues[2].trim()

        return ParsedAgentSkillPackage(
            name = name,
            description = description,
            instructions = instructions,
            license = license,
            compatibility = compatibility,
            metadata = metadata,
            allowedTools = allowedTools,
            contentHash = packageHash(files),
            files = files.sortedBy { it.path },
        )
    }

    private fun parseFrontmatter(content: String): Map<String, Any?> {
        val parsed = yaml.load<Any?>(content)
            ?: error("SKILL.md frontmatter must be a YAML mapping")
        require(parsed is Map<*, *>) { "SKILL.md frontmatter must be a YAML mapping" }
        return parsed.entries.associate { (key, value) ->
            require(key is String && key.isNotBlank()) {
                "SKILL.md frontmatter keys must be non-blank strings"
            }
            key to value
        }
    }

    private fun Map<String, Any?>.requiredString(name: String): String =
        optionalString(name) ?: error("SKILL.md frontmatter requires non-empty '$name'")

    private fun Map<String, Any?>.optionalString(name: String): String? {
        val value = this[name] ?: return null
        require(value is String) { "SKILL.md frontmatter '$name' must be a string" }
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun Map<String, Any?>.optionalStringMap(name: String): Map<String, String> {
        val value = this[name] ?: return emptyMap()
        require(value is Map<*, *>) { "SKILL.md frontmatter '$name' must be a string map" }
        return value.entries.associate { (key, item) ->
            require(key is String && key.isNotBlank()) {
                "SKILL.md frontmatter '$name' keys must be non-blank strings"
            }
            require(item is String) {
                "SKILL.md frontmatter '$name.$key' must be a string"
            }
            key to item
        }
    }

    private fun validateSkillName(value: String, label: String) {
        require(value.length in 1..MAX_NAME_LENGTH) {
            "$label must contain 1-$MAX_NAME_LENGTH characters"
        }
        require(SKILL_NAME.matches(value)) {
            "$label must contain only lowercase ASCII letters, digits, and single hyphens"
        }
    }

    private fun packageHash(files: List<AgentSkillFile>): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            files.sortedBy { it.path }.forEach { file ->
                val pathBytes = file.path.toByteArray(StandardCharsets.UTF_8)
                data.writeInt(pathBytes.size)
                data.write(pathBytes)
                data.writeLong(file.content.size.toLong())
                data.write(file.content)
            }
        }
        return output.toByteArray().sha256()
    }

    private fun decodeUtf8(path: String, bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private companion object {
        const val SKILL_FILE = "SKILL.md"
        const val MAX_NAME_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 1024
        const val MAX_COMPATIBILITY_LENGTH = 500
        const val MAX_FILES = 2_000
        const val MAX_SKILL_MD_BYTES = 1_000_000L
        const val MAX_FILE_BYTES = 16_000_000
        const val MAX_PACKAGE_BYTES = 64_000_000L
        val SKILL_NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val FRONTMATTER = Regex(
            pattern = """\A---\r?\n(.*?)\r?\n---(?:\r?\n|\z)(.*)\z""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}

internal fun normalizeAgentSkillPath(rawPath: String): String {
    require(rawPath.isNotBlank()) { "Agent Skill file path must not be blank" }
    require(rawPath.length <= 1_000) { "Agent Skill file path is too long" }
    require('\\' !in rawPath) { "Agent Skill file paths must use forward slashes: $rawPath" }
    require(!rawPath.startsWith('/')) { "Agent Skill file path must be relative: $rawPath" }
    require(rawPath.none(Char::isISOControl)) {
        "Agent Skill file path contains a control character"
    }
    val segments = rawPath.split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) {
        "Agent Skill file path is not normalized: $rawPath"
    }
    return segments.joinToString("/")
}

data class ParsedAgentSkillPackage(
    val name: String,
    val description: String,
    val instructions: String,
    val license: String?,
    val compatibility: String?,
    val metadata: Map<String, String>,
    val allowedTools: String?,
    val contentHash: String,
    val files: List<AgentSkillFile>,
)
