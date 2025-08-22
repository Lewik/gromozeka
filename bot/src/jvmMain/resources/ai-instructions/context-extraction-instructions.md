# Context Extraction Instructions

**Task**: Analyze this conversation and extract separate semantic areas (contexts) from it.

**Goal**: Get structured information for each topic in XML format for further work or transfer to other tabs.

**Required result**: XML with a list of contexts following this structure:

```xml
<contexts>
  <context>
    <name>brief topic name</name>
    <files>
      <file path="path/to/file.kt">readfull</file>
      <file path="path/to/other.kt">
        <item>fun methodName</item>
        <item>class ClassName</item>
        <item>142:156</item>
      </file>
    </files>
    <links>
      <link>https://example.com/relevant-doc</link>
    </links>
    <content>key and sufficient information for this context</content>
  </context>
</contexts>
```

## Field Specifications

### Field `files` - what to read:
- `"readfull"` - entire file
- `["fun methodName"]` - specific functions
- `["class ClassName"]` - specific classes
- `["142:156"]` - line ranges (start:end)
- `["property propertyName"]` - properties/fields

### Field `links` - external resources:
- Documentation
- GitHub issues/PRs
- Articles, blogs
- Any links mentioned in conversation

### Field `content` includes:
- **Key decisions**: Any important decisions made during work (not just architectural)
- **Dead ends**: What didn't work and why - to avoid revisiting failed approaches
- **Current status**: What's done, where we stopped
- **Next steps**: Action plan for continuation

### Field `content` does NOT include:
- Code details (they're in files)
- Detailed reasoning chains about how we reached decisions
- Information duplicated from files
- Intermediate experiments (except dead ends)

## Instructions

1. Identify separate semantic areas/topics discussed in the conversation
2. For each context, provide all required fields in the XML structure above
3. Ensure `content` contains key and sufficient information for understanding this context
4. Focus on practical information needed to continue or understand the work
5. Keep file paths relative to the project root when possible (project path is set automatically)
6. **IMPORTANT**: After creating the XML, call the MCP tool `mcp__gromozeka-self-control__save_contexts` with parameter `xml_content` containing the XML to save the contexts
7. **ERROR HANDLING**: If the MCP tool returns an error (invalid XML structure), fix the XML and try calling the tool again