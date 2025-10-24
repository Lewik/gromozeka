package org.springframework.ai.claudecode.xml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Enhances system prompts with XML tool descriptions for Claude Code CLI.
 *
 * <p>Injects XML-formatted tool schemas into system prompt so Claude generates
 * tool calls as structured XML tags instead of JSON.</p>
 *
 * @see XmlToolParser
 */
public class SystemPromptEnhancer {

    private static final Logger logger = LoggerFactory.getLogger(SystemPromptEnhancer.class);

    public String enhanceWithXmlTools(String originalPrompt, List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            logger.debug("No tools to add to system prompt");
            return originalPrompt;
        }

        logger.debug("Enhancing system prompt with {} XML tool descriptions", tools.size());

        StringBuilder enhanced = new StringBuilder();
        if (originalPrompt != null && !originalPrompt.isEmpty()) {
            enhanced.append(originalPrompt);
            enhanced.append("\n\n");
        }

        enhanced.append("## Available Tools\n\n");
        enhanced.append("You can use tools by generating XML tags in your response. ");
        enhanced.append("Each tool must be properly formatted with opening and closing tags.\n\n");

        for (ToolDefinition tool : tools) {
            enhanced.append(generateXmlToolDescription(tool));
        }

        enhanced.append("\n## Tool Usage Rules\n\n");
        enhanced.append("- Use exactly ONE tool per message\n");
        enhanced.append("- Wait for tool result before continuing\n");
        enhanced.append("- Use proper XML formatting with correct opening/closing tags\n");
        enhanced.append("- Parameter names must match exactly as specified\n");
        enhanced.append("- Do not nest tool calls\n");
        enhanced.append("- Output XML directly in your text - do NOT wrap in markdown code blocks (no ```xml)\n");
        enhanced.append("- After using a tool, wait for the result message before responding\n");

        return enhanced.toString();
    }

    private String generateXmlToolDescription(ToolDefinition tool) {
        String name = tool.name();
        String description = tool.description();

        StringBuilder xml = new StringBuilder();
        xml.append("### ").append(name).append("\n");
        if (description != null && !description.isEmpty()) {
            xml.append(description).append("\n");
        }
        xml.append("\n");

        xml.append("Usage:\n");
        xml.append("<").append(name).append(">\n");

        try {
            String inputSchemaJson = tool.inputSchema();
            Map<String, Object> schema = ModelOptionsUtils.jsonToMap(inputSchemaJson);
            if (schema != null) {
                Object propertiesObj = schema.get("properties");
                if (propertiesObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> properties = (Map<String, Object>) propertiesObj;

                    Object requiredObj = schema.get("required");
                    List<String> required = requiredObj instanceof List
                            ? (List<String>) requiredObj
                            : List.of();

                    for (Map.Entry<String, Object> entry : properties.entrySet()) {
                        String paramName = entry.getKey();
                        Object paramSchemaObj = entry.getValue();

                        if (paramSchemaObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> paramSchema = (Map<String, Object>) paramSchemaObj;

                            String paramDesc = (String) paramSchema.get("description");
                            String paramType = (String) paramSchema.get("type");
                            boolean isRequired = required.contains(paramName);

                            xml.append("  <").append(paramName).append(">");

                            if (paramDesc != null && !paramDesc.isEmpty()) {
                                xml.append(paramDesc);
                            } else if (paramType != null) {
                                xml.append(paramType).append(" value");
                            } else {
                                xml.append("value");
                            }

                            if (isRequired) {
                                xml.append(" (required)");
                            }

                            xml.append("</").append(paramName).append(">\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract schema for tool {}: {}", name, e.getMessage());
            xml.append("  <!-- parameters not available -->\n");
        }

        xml.append("</").append(name).append(">\n\n");

        return xml.toString();
    }
}
