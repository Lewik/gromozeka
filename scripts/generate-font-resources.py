#!/usr/bin/env python3
import argparse
import concurrent.futures
import hashlib
import io
import json
import pathlib
import urllib.request

from localization import LOCALES

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUTPUT = ROOT / 'presentation/src/wasmJsMain/resources/fonts'
SOURCES = {
    'noto-fonts': 'ffebf8c1ee449e544955a7e813c54f9b73848eac',
    'noto-cjk': 'f8d157532fbfaeda587e826d4cd5b21a49186f7c',
}
CJK = {'jp': 'ja', 'kr': 'ko', 'sc': 'zh-Hans', 'tc': 'zh-Hant'}


def fetch(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()


def source_url(repository, path):
    return f'https://raw.githubusercontent.com/notofonts/{repository}/{SOURCES[repository]}/{path}'


def ranges(codepoints):
    result = []
    for point in sorted(codepoints):
        if result and result[-1][1] + 1 == point:
            result[-1][1] = point
        else:
            result.append([point, point])
    return result


def generate():
    from fontTools.ttLib import TTFont

    selected = []
    for repository, revision in SOURCES.items():
        tree = json.loads(fetch(f'https://api.github.com/repos/notofonts/{repository}/git/trees/{revision}?recursive=1'))
        for item in tree['tree']:
            path = item['path']
            if repository == 'noto-fonts':
                include = path.startswith('unhinted/ttf/') and path.endswith('-Regular.ttf') and len(path.split('/')) == 4
                include = include and (path.split('/')[2].startswith('NotoSans') or path.split('/')[2] in {'NotoMusic', 'NotoSerifTibetan'})
                include = include and not any(value in path for value in ('UI/', 'Condensed', 'Extra', 'Unjoined'))
            else:
                include = path.startswith('Sans/OTF/') and pathlib.Path(path).stem in {f'NotoSansCJK{suffix}-Regular' for suffix in CJK}
            if include:
                selected.append((repository, path))

    OUTPUT.mkdir(parents=True, exist_ok=True)

    def convert(item):
        repository, path = item
        original = fetch(source_url(repository, path))
        font = TTFont(io.BytesIO(original), recalcTimestamp=False)
        coverage = set(font.getBestCmap())
        font.flavor = 'woff2'
        filename = pathlib.Path(path).stem + '.woff2'
        font.save(OUTPUT / filename)
        metadata = {
            'id': pathlib.Path(path).stem,
            'file': filename,
            'ranges': ranges(coverage),
            'sha256': hashlib.sha256((OUTPUT / filename).read_bytes()).hexdigest(),
            'source': source_url(repository, path),
            'sourceSha256': hashlib.sha256(original).hexdigest(),
            'license': repository + '-OFL.txt',
        }
        for suffix, locale in CJK.items():
            if f'CJK{suffix}-' in filename:
                metadata['locale'] = locale
        return metadata

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        fonts = list(pool.map(convert, selected))
    fonts.sort(key=lambda value: value['id'])
    (OUTPUT / 'catalog.json').write_text(json.dumps({'fonts': fonts}, ensure_ascii=False, separators=(',', ':')) + '\n')
    for repository, path in [('noto-fonts', 'LICENSE'), ('noto-cjk', 'Sans/LICENSE')]:
        (OUTPUT / f'{repository}-OFL.txt').write_bytes(fetch(source_url(repository, path)))
    print(f'Generated {len(fonts)} local fallback fonts')


def check():
    catalog = json.loads((OUTPUT / 'catalog.json').read_text())['fonts']
    coverage = set()
    for font in catalog:
        path = OUTPUT / font['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == font['sha256'], path
        assert (OUTPUT / font['license']).is_file(), font['license']
        for start, end in font['ranges']:
            coverage.update(range(start, end + 1))
    missing = {}
    for locale in LOCALES:
        path = ROOT / 'localization' / f'{locale}.json'
        package = json.loads(path.read_text())
        texts = [package['name']]
        for value in package['messages'].values():
            texts.extend([value] if isinstance(value, str) else value.values())
        uncovered = {ord(char) for text in texts for char in text if ord(char) not in coverage and not char.isspace()}
        if uncovered:
            missing[locale] = uncovered
    if missing:
        raise SystemExit('Built-in translation characters outside bundled fonts:\n' + '\n'.join(
            locale + ': ' + ' '.join(f'U+{point:04X}' for point in sorted(points))
            for locale, points in missing.items()
        ))
    print(f'Checked {len(catalog)} font hashes and {len(coverage)} Unicode codepoints')


def main():
    parser = argparse.ArgumentParser(description='Generate self-hosted Noto WOFF2 fonts and their Unicode coverage index.')
    parser.add_argument('--download', action='store_true', help='Download pinned upstream fonts; requires fonttools and brotli.')
    parser.add_argument('--check', action='store_true', help='Check bundled font hashes and report catalog glyph coverage.')
    args = parser.parse_args()
    if args.download:
        generate()
    check()


if __name__ == '__main__':
    main()
