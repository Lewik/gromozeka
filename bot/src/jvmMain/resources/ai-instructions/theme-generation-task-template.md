# AI Theme Generation Task

I need you to analyze a screenshot and generate a color theme that **uses the exact same colors as what's in the
screenshot**. Your goal is color matching, not improvement.

## Available Files

- **Target screenshot**: `{SCREENSHOT_PATH}` - analyze this image
- **Light theme example**: `light-theme-example.png` - reference screenshot
- **Dark theme example**: `dark-theme-example.png` - reference screenshot

## Reference Theme Examples

### Light Theme JSON Structure

```json
{LIGHT_THEME_JSON}
```

### Dark Theme JSON Structure

```json
{DARK_THEME_JSON}
```

## Your Task

1. **Read and analyze the target screenshot**: `{SCREENSHOT_PATH}`
2. **Extract exact colors** from the image (don't interpret or improve)
3. **Generate a theme JSON** that uses the screenshot's exact colors
4. **Save the result** to: `{OUTPUT_PATH}/ai_generated_theme_{TIMESTAMP}.json`

## Detailed Instructions

{DETAILED_GUIDE_CONTENT}

## Final Requirements

- Use the Read tool to analyze the target screenshot
- **Extract and copy colors directly** - don't interpret or "improve" them
- Generate a theme that **uses the screenshot's colors**
- Save the final JSON theme to the themes directory using the Write tool
- Ensure all colors use hex format (e.g., "#BB86FC" for purple)
- Focus on color matching over standards compliance

Please start by reading the target screenshot and begin the analysis.