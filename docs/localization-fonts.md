# Web font fallback

Compose Multiplatform 1.11.1 renders the Web interface through Skia. Browser CSS
font families and fonts installed on the computer do not provide glyph fallback
inside its canvas. `TranslationProvider` installs the Web-only
`PlatformFontFallback`, which registers bundled fonts through the public
`FontFamily.Resolver.preload()` and `androidx.compose.ui.text.platform.Font` APIs.
Android, iOS, and JVM continue using their platform font resolver.

The generated Noto catalog is served from `fonts/` beside the Web application.
Font loading never contacts Google Fonts or another external font service.

- Every client loads Noto Sans, Hebrew, Arabic, Symbols, Symbols 2, and one full
  CJK font. These six fonts cover all characters in the 13 bundled translations,
  including the language names displayed together in the selector.
- The selected translation locale determines the CJK variant: Japanese, Korean,
  Simplified Chinese, or Traditional Chinese. Other locales use Simplified
  Chinese as the fallback for Han characters.
- The loader examines the selected package's name and every text and plural
  form. Additional fonts are fetched only when their Unicode coverage is needed.
  A personal translation can therefore use scripts outside the built-in locale
  list without changing application code or contacting an external font service.
- The actual shaping and missing-glyph fallback remain Skia's responsibility.
  The coverage index only selects which existing font files to register.

The complete Web asset set contains 177 fonts and is about 48.4 MiB. A normal
client initially fetches about 11.4 MiB, dominated by the full CJK font. Font
bytes are reused within the page, while each completed load installs a fresh
resolver so that previously laid-out text is invalidated correctly. Native
application packages do not include these Web-only assets.

Content remains in composition while a loading overlay covers it. Changing the
language does not discard screen state or text fields. A failed download or font
decode ends loading and displays a retry dialog; failed assets are evicted from
the byte cache so a corrected server response can be fetched again.
Before registration, `FontMgr.makeFromData()` explicitly decodes each asset and
checks that it contains glyphs. This check is necessary because Compose's font
resolver can suppress a decode failure while trying its normal fallback chain.
The temporary Skia data and typeface handles are closed after validation.

The bundled fonts currently cover 71,225 Unicode codepoints. This is broad
script coverage, not a promise to render every Unicode character or every emoji
sequence. Characters outside the selected package and the six core fonts are
not an additional trigger for downloading fonts in Compose 1.11.1. For example,
an unrelated script pasted only into a conversation may require selecting a
translation that uses that script. The native targets retain the wider fallback
available from the fonts installed on their operating system.

## Source and licenses

`scripts/generate-font-resources.py` downloads pinned upstream source files and
converts their containers to WOFF2 without subsetting glyphs or changing the
font's shaping tables. The coverage catalog records each source URL, its SHA-256,
the generated asset SHA-256, Unicode ranges, and license filename.

- [Noto fonts](https://github.com/notofonts/noto-fonts/tree/ffebf8c1ee449e544955a7e813c54f9b73848eac)
  supplies the script, symbol, music, and Tibetan fonts.
- [Noto CJK](https://github.com/notofonts/noto-cjk/tree/f8d157532fbfaeda587e826d4cd5b21a49186f7c)
  supplies the four regional CJK variants.

Both are distributed under SIL Open Font License 1.1. The original copyright and
license metadata remains inside the font files, and `noto-fonts-OFL.txt` and
`noto-cjk-OFL.txt` are included beside the generated assets. The license permits
bundling and distribution with commercial software and does not relicense the
application.

To regenerate the checked-in assets, use Python with `fonttools==4.64.0` and
`brotli==1.2.0`, then run:

```sh
python3 scripts/generate-font-resources.py --download
```

Normal offline verification needs only the Python standard library:

```sh
python3 scripts/generate-font-resources.py --check
```

This checks every font hash and license file and fails if a built-in translation
or its autonym contains characters outside the bundled fonts. Runtime selection uses the full font
coverage, so catalog edits do not require resubsetting or rebuilding the fonts.
