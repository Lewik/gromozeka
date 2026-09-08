#!/usr/bin/env python3
import argparse
import json
import pathlib
import plistlib
import re
from xml.sax.saxutils import escape

from localization import LOCALES, read

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANDROID = ROOT / 'presentation-android/src/main/res'
IOS = ROOT / 'iosApp/iosApp'
WEB = ROOT / 'presentation/src/wasmJsMain/resources'
HEADER = 'Generated from localization/*.json by scripts/generate-native-localization.py.'
INFO_KEYS = {
    'NSMicrophoneUsageDescription': 'native.microphonePermission',
    'NSLocalNetworkUsageDescription': 'native.localNetworkPermission',
}
ANDROID_KEYS = {
    'quick_text_actions': 'settingsUi.quickTextActions',
    'quick_text_fix': 'quickText.fix',
    'quick_text_translate': 'quickText.translate',
}
LOCALIZABLE_KEYS = [
    'native.shortcuts.toggleRecording', 'native.shortcuts.toggleRecordingDescription',
    'native.shortcuts.textParameter', 'quickText.fix', 'quickText.fixDescription',
    'quickText.translate', 'quickText.translateDescription', 'common.unknownError',
]
SHORTCUTS = [
    ('ToggleConversationIntent', 'native.shortcuts.toggleRecording', 'mic.circle', ['togglePhrase', 'startPhrase']),
    ('FixTextIntent', 'quickText.fix', 'text.badge.checkmark', ['fixPhrase', 'correctPhrase']),
    ('TranslateTextIntent', 'quickText.translate', 'translate', ['translateTextPhrase', 'translatePhrase']),
]
SUMMARIES = [('FixTextIntent', 'native.shortcuts.fixSummary'), ('TranslateTextIntent', 'native.shortcuts.translateSummary')]


def quote(value):
    return json.dumps(value, ensure_ascii=False)


def native_parameters(value):
    return re.sub(r'\{(\w+)\}', r'${\1}', value)


def strings_table(values):
    return ('/* ' + HEADER + ' */\n' + ''.join(f'{quote(k)} = {quote(v)};\n' for k, v in values.items())).encode()


def android_text(value):
    return '"' + escape(value.replace('\\', '\\\\').replace('"', '\\"').replace("'", "\\'").replace('\n', '\\n')) + '"'


def android_strings(messages):
    rows = ''.join(f'    <string name="{name}" formatted="false">{android_text(messages[key])}</string>\n' for name, key in ANDROID_KEYS.items())
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- ' + HEADER + ' -->\n<resources>\n' + rows + '</resources>\n').encode()


def android_folders(locale):
    if locale == 'en': return ['values']
    if locale == 'pt-BR': return ['values-pt', 'values-pt-rBR']
    if locale in {'he', 'id', 'zh-Hans', 'zh-Hant'}: return ['values-b+' + locale.replace('-', '+')]
    return ['values-' + locale]


def swift_interpolation(value, parameter, interpolation):
    token = '__GROMOZEKA_INTERPOLATION__'
    assert value.count('{' + parameter + '}') == 1
    return quote(value.replace('{' + parameter + '}', token)).replace(token, interpolation)


def shortcut_source(english):
    parts = ['// ' + HEADER, 'import AppIntents', '', 'struct GromozekaShortcuts: AppShortcutsProvider {', '    static var appShortcuts: [AppShortcut] {']
    for intent, title, symbol, phrase_keys in SHORTCUTS:
        parts.extend(['        AppShortcut(', f'            intent: {intent}(),', '            phrases: ['])
        phrases = [swift_interpolation(english['native.shortcuts.' + key], 'applicationName', r'\(.applicationName)') for key in phrase_keys]
        parts.extend('                ' + value + (',' if index < len(phrases) - 1 else '') for index, value in enumerate(phrases))
        parts.extend(['            ],', f'            shortTitle: {quote(title)},', f'            systemImageName: {quote(symbol)}', '        )'])
    parts.extend(['    }', '}'])
    for intent, key in SUMMARIES:
        summary = swift_interpolation(english[key], 'text', r'\(\.$text)')
        parts.extend(['', f'extension {intent} {{', '    static var parameterSummary: some ParameterSummary {', f'        Summary({summary})', '    }', '}'])
    return ('\n'.join(parts) + '\n').encode()


def web_source(packages):
    data = {locale: {'locale': locale, 'name': package['name'], 'direction': package['direction'].lower(), 'initializing': package['messages']['bootstrap.initializing']} for locale, package in packages.items()}
    return ('''// ''' + HEADER + '''
(() => {
    const translations = ''' + json.dumps(data, ensure_ascii=False, separators=(',', ':')) + ''';
    function matchLocale(locale) {
        const normalized = locale.replaceAll("_", "-").toLowerCase();
        const exact = Object.keys(translations).find(key => key.toLowerCase() === normalized);
        if (exact) return exact;
        const parts = normalized.split("-");
        if (parts[0] === "zh") return parts.some(part => ["hant", "tw", "hk", "mo"].includes(part)) ? "zh-Hant" : "zh-Hans";
        if (parts[0] === "iw") return "he";
        return Object.keys(translations).find(key => key.split("-")[0].toLowerCase() === parts[0]) || "en";
    }
    let locale = navigator.language || "en";
    try {
        const settings = JSON.parse(localStorage.getItem("gromozeka.remoteClientSettings") || "null");
        if (settings && typeof settings.bootstrapLocale === "string") locale = settings.bootstrapLocale;
    } catch (_) {}
    const translation = translations[matchLocale(locale)];
    document.documentElement.lang = translation.locale;
    document.documentElement.dir = translation.direction;
    const loader = document.getElementById("bootstrapLoader");
    if (loader) loader.setAttribute("aria-label", translation.initializing);
})();
''').encode()


def outputs():
    packages = {locale: read(ROOT / 'localization' / f'{locale}.json') for locale in LOCALES}
    english = packages['en']['messages']
    result = {}
    for locale, package in packages.items():
        messages = package['messages']
        for folder in android_folders(locale):
            result[ANDROID / folder / 'localization.xml'] = android_strings(messages)
        result[IOS / (locale + '.lproj') / 'InfoPlist.strings'] = strings_table({key: messages[source] for key, source in INFO_KEYS.items()})
        localizable = {key: messages[key] for key in LOCALIZABLE_KEYS}
        localizable.update({native_parameters(english[key]): native_parameters(messages[key]) for _, key in SUMMARIES})
        result[IOS / (locale + '.lproj') / 'Localizable.strings'] = strings_table(localizable)
        phrases = {}
        for _, _, _, phrase_keys in SHORTCUTS:
            for suffix in phrase_keys:
                key = 'native.shortcuts.' + suffix
                phrases[native_parameters(english[key])] = native_parameters(messages[key])
        result[IOS / (locale + '.lproj') / 'AppShortcuts.strings'] = strings_table(phrases)
    result[ANDROID / 'xml/locales_config.xml'] = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- ' + HEADER + ' -->\n<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n' + ''.join(f'    <locale android:name="{locale}" />\n' for locale in LOCALES) + '</locale-config>\n').encode()
    plist_path = IOS / 'Info.plist'
    info = plistlib.loads(plist_path.read_bytes())
    info.update({key: english[source] for key, source in INFO_KEYS.items()})
    result[plist_path] = plistlib.dumps(info, sort_keys=False)
    result[IOS / 'GeneratedAppShortcuts.swift'] = shortcut_source(english)
    result[WEB / 'bootstrap-localization.js'] = web_source(packages)
    result[WEB / 'licenses/Unicode-CLDR.txt'] = (ROOT / 'localization/cldr/LICENSE').read_bytes()
    return result


def main():
    parser = argparse.ArgumentParser(description='Generate native platform strings and the web bootstrap label from the JSON catalogs.')
    parser.add_argument('--check', action='store_true', help='Fail when generated files differ from the catalogs.')
    arguments = parser.parse_args()
    stale = []
    generated = outputs()
    for path, content in generated.items():
        if arguments.check:
            if not path.exists() or path.read_bytes() != content: stale.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if stale:
        raise SystemExit('Regenerate native localization: python3 scripts/generate-native-localization.py\n' + '\n'.join(stale))
    print(f'{"Checked" if arguments.check else "Generated"} {len(generated)} files for {len(LOCALES)} locales')


if __name__ == '__main__':
    main()
