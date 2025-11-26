# Logo Generation

This directory contains generated PNG logos from the source SVG file.

## Files

- `logo-256x256.png` - Generated 256x256 PNG logo used by the application
- `logo-{size}x{size}.png` - Additional sizes (32x32, 64x64, 128x128, 512x512)
- `logo.svg` - Source SVG file (located in parent resources directory)

## Regenerating Logo

To regenerate the PNG logos from SVG source using Apache Batik:

**Generate all sizes (32x32, 64x64, 128x128, 256x256, 512x512):**

```bash
./gradlew :presentation:jvmTest --tests "LogoGenerationTest.generateLogos"
```

This single command generates all logo sizes at once.

**Quick reference (shows commands):**

```bash
./gradlew :presentation:convertLogo
```

The tests will:

1. Read the source SVG from `src/jvmMain/resources/logo.svg`
2. Convert it to PNG using Apache Batik transcoder
3. Save the PNG files to `src/jvmMain/resources/logos/`

## Logo Concept

The logo features a lamp bulb design with interchangeable elements:

- **Base layer**: Orange head/bulb shape (always visible)
- **Tool layers**: Blue icons for different modes (search, code, etc.)
- **Philosophy**: Represents the "bright ideas" and multi-tool nature of Gromozeka

The SVG uses Inkscape layers that can be toggled on/off for different contexts:

- `base` - Main logo shape
- `search` - Magnifying glass icon
- `code` - Code brackets `< >` icon

Currently, the build generates a static PNG with only the base layer visible.