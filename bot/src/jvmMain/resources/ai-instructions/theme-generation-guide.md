# AI Theme Generation Guide for Gromozeka

## Task Overview

You are an AI agent tasked with analyzing a screenshot and generating a color theme that **uses the exact same colors as the captured content**. The primary goal is color matching - the user wants their application to use the same colors they see in the screenshot.

**Priority order:**
1. **Color matching** - Use the exact colors from the screenshot
2. **Basic usability** - Only fix critical readability issues if necessary  
3. **Material Design standards** - Use only as fallback for missing colors

## Step-by-Step Process

Follow these steps in order:

### Step 1: Screenshot Analysis - SYSTEMATIC COLOR SAMPLING

**ANALYSIS ORDER** (follow exactly):

1. **Identify the main background** (largest plain area) → `background` color
2. **Find panel/card backgrounds** (secondary surfaces) → `surface` color  
3. **Locate primary interactive elements** (buttons, toggles, active tabs) → `primary` color
4. **Sample main text color** (most common text) → `onBackground` color
5. **Find secondary interactive elements** (if any) → `secondary` color

**SAMPLING RULES**:
- **Click on the actual element**, not hover states or highlights
- **Avoid gradients** - pick the main solid color
- **Ignore very small elements** - focus on areas user sees most
- **Copy EXACT hex values** - no "improvements"

**FORBIDDEN**:
- ❌ Taking hover/focus highlight colors as primary
- ❌ Using the brightest color you see  
- ❌ Picking colors from tiny UI elements

### Step 2: Direct Color Extraction with Precise Mapping

**CRITICAL**: Sample exact colors and map them to correct roles:

#### A. Background & Surface Colors (Large Areas):
- **Background**: Main app background (largest dark/light area)
- **Surface**: Panels, cards, secondary backgrounds  
- **SAMPLE**: Click on large plain areas, avoid text/buttons

#### B. Interactive Element Colors:
- **Primary**: Buttons, toggles, active tabs (NOT the brightest color!)
- **Secondary**: Less prominent interactive elements
- **SAMPLE**: Click on actual button/toggle colors, not highlights

#### C. Text Colors:
- **OnBackground**: Main text on background
- **OnSurface**: Text on panels/cards
- **SAMPLE**: Click on actual text, not selection highlights

**CRITICAL RULE**: Use the EXACT color from where you click, not similar/brighter versions

### Step 3: Color Mapping Strategy

**TWO DIFFERENT APPROACHES:**

#### A. For colors visible in screenshot:
- **COPY EXACTLY** - No interpretation, no improvements
- **Background** → Use exact background color from screenshot
- **Primary elements** → Use exact colors from buttons, highlights, active elements
- **Text colors** → Use exact text colors you see
- **DON'T** adjust for accessibility - copy what user has

#### B. For missing elements (not visible in screenshot):
- **Error colors** → Use Material Design standards (red family) if no errors visible
- **Tertiary colors** → Set to null OR derive from screenshot colors if needed
- **Missing surfaces** → Create logical variations of screenshot colors
- **THIS is where** you can apply Material Design principles

### Step 4: Color Role Assignment
- Assign primary color (most prominent/brand color)
- Assign secondary color (complementary or supporting color)
- Generate surface/background colors from neutral tones
- Use standard error colors (red family) with proper contrast
- Set all optional colors to null unless specifically beneficial

### Step 5: Minimal Quality Assurance
- **Priority**: Preserve exact colors from screenshot
- **Only check**: Critical readability issues (e.g., white text on white background)
- **Fix sparingly**: Only obvious usability problems, keep changes minimal
- **When in doubt**: Prefer screenshot color accuracy over standards compliance

### Step 6: JSON Output
- Create the final JSON structure with exact formatting
- Use hex color format (e.g., "#BB86FC" for purple)
- Include unique theme ID with timestamp
- Provide descriptive theme name based on color analysis
- Return ONLY the JSON in a code block, no additional text

## Critical Requirements

### 1. Response Format
- **RETURN ONLY VALID JSON** in a code block
- **NO additional text, explanations, or commentary**
- The JSON will be extracted from your message and used directly
- The JSON must be deserializable as a Gromozeka Theme object
- Use the exact structure shown in the examples below

### 2. Color Format
- All colors must be specified as **hex string values**
- Format: `"#RRGGBB"` (e.g., `"#BB86FC"` for purple)
- **ALWAYS** use 6-digit uppercase hex format with # prefix
- **Examples**: `"#BB86FC"`, `"#000000"`, `"#FFFFFF"`

### 3. Quality Balance
- **PRIMARY**: Match the exact colors from the screenshot
- **SECONDARY**: Only address obvious readability problems
- **AVOID**: Forcing strict accessibility standards if they break color matching
- **ACCEPTABLE**: User's chosen colors may not be perfectly accessible

## Material Design 3 Guidelines (FALLBACK ONLY)

**USE THESE ONLY** when elements are not visible in the screenshot.

### When to apply Material Design:
1. **Error colors** - if no error states visible in screenshot
2. **Missing optional colors** - tertiary, outlineVariant, etc.
3. **Logical derivatives** - when you need surface variants not shown

### Core Principles for fallbacks:
1. **Stay consistent** with screenshot colors
2. **Derive from screenshot colors** when possible
3. **Use Material standards** only when no screenshot guidance exists

### Required Color Roles

#### Primary Colors (Brand Identity)
- `primary`: Main brand color, used for key actions and highlights
- `onPrimary`: Text/content color that contrasts well with primary
- `primaryContainer`: Tinted container background using primary
- `onPrimaryContainer`: Content color for primary containers

#### Secondary Colors (Supporting Elements)
- `secondary`: Supporting color that complements primary
- `onSecondary`: Content color that contrasts with secondary
- `secondaryContainer`: Tinted container using secondary
- `onSecondaryContainer`: Content color for secondary containers

#### Error Colors (Status Indication)
- `error`: Red family color for error states
- `onError`: Content color for error backgrounds
- `errorContainer`: Light error background
- `onErrorContainer`: Content color for error containers

#### Surface Colors (Backgrounds and Containers)
- `background`: Main app background color
- `onBackground`: Primary content color for backgrounds
- `surface`: Component surfaces (cards, sheets, etc.)
- `onSurface`: Primary content color for surfaces
- `surfaceVariant`: Alternative surface color for variety
- `onSurfaceVariant`: Content color for surface variants
- `outline`: Border and divider colors

#### Optional Colors (Enhanced Experience)
- `tertiary`: Third accent color (nullable)
- `onTertiary`: Content color for tertiary (nullable)
- `tertiaryContainer`: Tertiary container background (nullable)
- `onTertiaryContainer`: Content for tertiary containers (nullable)
- `outlineVariant`: Secondary outline color (nullable)
- `scrim`: Overlay color for modals/sheets (nullable)
- `inverseSurface`: High-contrast surface (nullable)
- `inverseOnSurface`: Content for inverse surfaces (nullable)
- `inversePrimary`: Inverse primary color (nullable)

## Color Extraction Strategy

### 1. Analyze Screenshot Content
- Identify the **most prominent colors** in the image
- Look for **brand colors** if present (logos, UI elements)
- Consider **background and foreground elements** separately
- Note the **overall mood and tone** (warm/cool, bright/muted)

### 2. Select Source Colors
- Choose **1-3 dominant colors** as source colors
- Prefer colors that appear in **key UI elements** or **focal points**
- Avoid colors that are too dark/light for accessibility
- Consider both **saturated and neutral tones**

### 3. Generate Color Palettes
For each selected source color:
- Create a **tonal palette** by varying lightness (10, 20, 30... 90, 95)
- Maintain **consistent hue and chroma** across the palette
- Generate both **light and dark theme variations**

### 4. Assign Color Roles
- **Primary**: Use the most prominent/brand color
- **Secondary**: Use a complementary or secondary prominent color
- **Surface/Background**: Derive from neutral tones in the image
- **Error**: Use standard red tones (maintain accessibility)

## JSON Structure Requirements

Your final output must follow this exact structure:
- **themeId**: Format `"ai_generated_{timestamp}"` (e.g., `"ai_generated_1692345678901"`)
- **themeName**: Descriptive name based on colors/content (e.g., `"Ocean Sunset"`, `"Corporate Blue"`)
- **Color Format**: All colors as hex strings (e.g., "#BB86FC", "#000000", "#FFFFFF")
- **Required Fields**: All non-optional fields must be present with valid color values
- **Optional Fields**: Set to `null` unless you have a specific beneficial color to use

*Note: Concrete JSON examples with actual theme data will be provided separately as reference.*

## Accessibility Guidelines (WCAG 2.1)

### Contrast Ratio Requirements
- **Level AA (Required)**:
  - Normal text: 4.5:1 minimum
  - Large text (18pt+/14pt+ bold): 3:1 minimum
  - UI components: 3:1 minimum

- **Level AAA (Enhanced)**:
  - Normal text: 7:1 minimum
  - Large text: 4.5:1 minimum

### Critical Color Pairs to Validate
1. `onPrimary` vs `primary`
2. `onSecondary` vs `secondary`
3. `onBackground` vs `background`
4. `onSurface` vs `surface`
5. `onSurfaceVariant` vs `surfaceVariant`
6. `onError` vs `error`
7. All container color pairs

### Contrast Calculation Formula
```
Contrast Ratio = (L1 + 0.05) / (L2 + 0.05)
where L1 is the lighter color's relative luminance
and L2 is the darker color's relative luminance

Relative Luminance = 0.2126 * R + 0.7152 * G + 0.0722 * B
(where R, G, B are the linearized color values)
```

## Theme Naming Convention

- **themeId**: Use format `"ai_generated_{timestamp}"` (e.g., `"ai_generated_1692345678901"`)
- **themeName**: Use descriptive name based on dominant colors or image content (e.g., `"Ocean Sunset"`, `"Forest Green"`, `"Corporate Blue"`)

## Quality Checklist

Before outputting the theme JSON, verify:

1. ✅ **All required color fields** are present and non-null
2. ✅ **Color format** uses hex strings (e.g., "#BB86FC")
3. ✅ **Contrast ratios** meet WCAG AA standards (4.5:1 for text, 3:1 for UI)
4. ✅ **Color harmony** creates a cohesive visual experience
5. ✅ **JSON structure** matches exactly the examples provided
6. ✅ **Theme ID** follows naming convention
7. ✅ **No syntax errors** in JSON formatting

## Error Prevention

### Common Mistakes to Avoid
- ❌ **Over-interpreting**: Changing colors to "improve" them instead of copying
- ❌ **Being too creative**: The user wants their screenshot aesthetic, not your improvements
- ❌ **Ignoring screenshot**: Using standard Material colors instead of extracted ones
- ❌ **Format errors**: Wrong hex format, missing # prefix, lowercase letters
- ❌ **JSON errors**: Invalid syntax, missing required fields
- ❌ **Including explanations**: Only return JSON, no additional text

### Strategy Examples

#### Example 1: IDE Screenshot Analysis
**What you see in screenshot:**
- Background: Dark gray `#2D2D30`
- Active tab: Blue `#0078D4`  
- Text: White `#FFFFFF`
- NO error states visible

**Your mapping:**
- `background`: `#2D2D30` ← COPY EXACTLY
- `primary`: `#0078D4` ← COPY EXACTLY  
- `onBackground`: `#FFFFFF` ← COPY EXACTLY
- `error`: `#F44336` ← FALLBACK (Material Design red)

#### Example 2: Correct Color Role Mapping
**Settings panel screenshot:**
- Large dark areas: `#2D2D30` 
- Toggle switches: `#40A0C0`
- Panel backgrounds: `#3C3C3C`
- Text: `#FFFFFF`

**Correct mapping:**
- `background`: `#2D2D30` ← Main background area
- `surface`: `#3C3C3C` ← Panel backgrounds  
- `primary`: `#40A0C0` ← Toggle switch color (actual button color)
- `onBackground`: `#FFFFFF` ← Text color

#### Example 3: What NOT to do
❌ **Wrong**: Taking `primary`: `#6CC4F5` (bright highlight that appears on hover)
✅ **Correct**: Taking `primary`: `#40A0C0` (actual toggle switch color)

❌ **Wrong**: "The background `#2D2D30` is too dark, I'll use `#3C3C3C`"
✅ **Correct**: Use `#2D2D30` exactly - that's the main background

### If Color Extraction Fails
- Use **neutral grays** with appropriate contrast ratios
- Base primary color on **most prominent non-neutral color** in image
- Ensure **logical color relationships** (containers lighter/darker than base colors)
- Maintain **semantic meaning** of color roles

## Understanding Provided Examples

You will receive reference examples showing how themes look in practice:

### Light Theme Reference
- **Screenshot**: Shows how the light theme appears in the actual application
- **JSON Structure**: Complete theme definition with all color roles
- **Purpose**: Use this to understand the visual impact of theme colors and the required JSON format

### Dark Theme Reference  
- **Screenshot**: Shows how the dark theme appears in the actual application
- **JSON Structure**: Complete theme definition with all color roles
- **Purpose**: Use this to understand contrast relationships and dark theme principles

**Important**: Focus on the **structure and format** of the JSON, not the specific color values. Your task is to generate completely new colors based on the target screenshot while following the same structural pattern.

## Final Instructions

Execute the 6-step process outlined above:
1. **Screenshot Analysis** - Sample exact colors from visible elements
2. **Direct Color Extraction** - Copy colors exactly as they appear  
3. **Smart Color Mapping** - Screenshot colors exactly + fallbacks for missing
4. **Role Assignment** - Map visible colors to appropriate theme roles
5. **Minimal Quality Assurance** - Only fix critical readability problems
6. **JSON Output** - Return only valid JSON in code block

**Remember**: 
- **Priority 1**: Use exact colors from the screenshot
- **Priority 2**: Use standards only for missing elements  
- **Priority 3**: Basic usability (only if critical issues)