#!/usr/bin/env python3
import argparse
import json
import pathlib
import re
import sys
from collections import Counter
from functools import cache

ROOT = pathlib.Path(__file__).resolve().parent.parent
DIRECTORY = ROOT / 'localization'
LOCALES = {
    'en': ('English', 'LTR'),
    'ru': ('Русский', 'LTR'),
    'he': ('עברית', 'RTL'),
    'es': ('Español', 'LTR'),
    'pt-BR': ('Português (Brasil)', 'LTR'),
    'ja': ('日本語', 'LTR'),
    'zh-Hans': ('简体中文', 'LTR'),
    'zh-Hant': ('繁體中文', 'LTR'),
    'de': ('Deutsch', 'LTR'),
    'fr': ('Français', 'LTR'),
    'ko': ('한국어', 'LTR'),
    'ar': ('العربية', 'RTL'),
    'id': ('Bahasa Indonesia', 'LTR'),
}
NAMED = re.compile(r'\{([A-Za-z][A-Za-z0-9_]*)\}')
PRINTF = re.compile(r'%%|%(?:([0-9]+)\$)?(?:\.([0-9]+))?([sdf])')
BIDI = re.compile('[\u202a-\u202e\u2066-\u2069]')
RELATION = re.compile(r'([nivwftec])(?: % (\d+))? (!=|=) ([0-9.,]+)')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate key: {key}')
        result[key] = value
    return result


def read(path):
    return json.loads(path.read_text(), object_pairs_hook=unique_object)


def signatures(value):
    named = Counter(NAMED.findall(value))
    positional = {}
    next_index = 1
    for match in PRINTF.finditer(value):
        if match[0] == '%%':
            continue
        index = int(match[1]) if match[1] else next_index
        next_index += match[1] is None
        if not 1 <= index <= 2**31 - 1:
            raise ValueError('printf argument position must be between 1 and 2147483647')
        precision = int(match[2]) if match[2] is not None else None
        if precision is not None and not 0 <= precision <= 9:
            raise ValueError('printf argument precision must be between 0 and 9')
        kind = (match[3], precision)
        previous = positional.get(index)
        if previous is not None and previous[:2] != kind:
            raise ValueError(f'inconsistent printf argument {index}')
        positional[index] = (*kind, (previous[2] if previous else 0) + 1)
    return named, positional


@cache
def plural_rules(locale):
    data = {
        key.lower(): value
        for key, value in read(DIRECTORY / 'cldr/plurals.json')['supplemental']['plurals-type-cardinal'].items()
    }
    normalized = locale.replace('_', '-').lower()
    rules = data.get(normalized) or data.get(normalized.split('-')[0])
    return {
        key.removeprefix('pluralRule-count-'): value.split('@')[0].strip()
        for key, value in (rules or {'pluralRule-count-other': ''}).items()
    }


def plural_categories(locale):
    return set(plural_rules(locale))


def parse_plural_rule(rule):
    branches = []
    for branch in rule.split(' or '):
        relations = []
        for text in branch.split(' and '):
            match = RELATION.fullmatch(text)
            if match is None:
                raise ValueError(f'unsupported CLDR relation: {text}')
            operand, modulo, operator, values = match.groups()
            intervals = []
            for value in values.split(','):
                bounds = value.split('..')
                intervals.append((int(bounds[0]), int(bounds[-1])))
            relations.append((operand, int(modulo) if modulo else None, operator, tuple(intervals)))
        branches.append(tuple(relations))
    return tuple(branches)


def integer_relation_matches(relation, count):
    operand, modulo, operator, intervals = relation
    value = count if operand in {'n', 'i'} else 0
    if modulo is not None:
        value %= modulo
    within = any(low <= value <= high for low, high in intervals)
    return within if operator == '=' else not within


@cache
def allows_implicit_count(locale, category):
    fixed = {'zero': 0, 'one': 1, 'two': 2}.get(category)
    rule = plural_rules(locale).get(category)
    if fixed is None or not rule:
        return False
    reachable = False
    for branch in parse_plural_rule(rule):
        if any(relation[0] not in {'n', 'i'} and not integer_relation_matches(relation, fixed)
               for relation in branch):
            continue
        bounded = any(operand in {'n', 'i'} and modulo is None and operator == '='
                      and intervals == ((fixed, fixed),)
                      for operand, modulo, operator, intervals in branch)
        if not bounded:
            return False
        reachable |= all(integer_relation_matches(relation, fixed) for relation in branch)
    return reachable


def arguments_match(source, translated, locale, category=None):
    expected, actual = signatures(source), signatures(translated)
    if expected == actual:
        return True
    if allows_implicit_count(locale, category):
        named = expected[0].copy()
        named.pop('count', None)
        return actual == (named, expected[1])
    return False


def validate_context():
    source = read(DIRECTORY / 'en.json')['messages']
    context = read(DIRECTORY / 'context.json')
    errors = []
    for key in source.keys() - context.keys():
        errors.append(f'{key}: missing translation context')
    for key in context.keys() - source.keys():
        errors.append(f'{key}: context references an unknown message')
    for key in source.keys() & context.keys():
        entry = context[key]
        if not isinstance(entry.get('description'), str) or not entry['description'].strip():
            errors.append(f'{key}: missing semantic description')
        if entry.get('description') in {'Settings panel', 'Existing typed UI translation'}:
            errors.append(f'{key}: generic description does not explain meaning')
        value = source[key]
        sample = value['other'] if isinstance(value, dict) else value
        named, positional = signatures(sample)
        expected_args = set(named) | {f'%{kind[0]}' for kind in positional.values()}
        if set(entry.get('args', [])) != expected_args:
            errors.append(f'{key}: context arguments differ from message arguments')
        if not isinstance(entry.get('source'), list):
            errors.append(f'{key}: missing source references')
        for anchor in entry.get('source', []):
            path = re.sub(r':\d+$', '', anchor)
            if not (ROOT / path).is_file():
                errors.append(f'{key}: source path does not exist: {anchor}')
    for error in errors:
        print(error, file=sys.stderr)
    print(f'{len(context)} message contexts checked')
    return 1 if errors else 0


def validate(selected):
    source = read(DIRECTORY / 'en.json')
    errors = []
    for locale in selected or LOCALES:
        path = DIRECTORY / f'{locale}.json'
        if not path.exists():
            errors.append(f'{locale}: catalog is missing')
            continue
        try:
            package = read(path)
            if set(package) != {'schemaVersion', 'locale', 'name', 'direction', 'messages'}:
                raise ValueError('unexpected package fields')
            if type(package['schemaVersion']) is not int or package['schemaVersion'] != 1 or package['locale'] != locale:
                raise ValueError('invalid schema version or locale')
            if package['direction'] != LOCALES[locale][1] or not package['name'].strip():
                raise ValueError('invalid direction or name')
            messages = package['messages']
            for missing in source['messages'].keys() - messages.keys():
                errors.append(f'{locale}:{missing}: missing message')
            for extra in messages.keys() - source['messages'].keys():
                errors.append(f'{locale}:{extra}: unknown message')
            for key in messages.keys() & source['messages'].keys():
                original, translated = source['messages'][key], messages[key]
                if isinstance(original, dict) != isinstance(translated, dict):
                    errors.append(f'{locale}:{key}: message/plural shape differs')
                    continue
                expected = signatures(original['other'] if isinstance(original, dict) else original)
                if isinstance(translated, dict):
                    if set(translated) != plural_categories(locale):
                        errors.append(f'{locale}:{key}: expected plural categories {sorted(plural_categories(locale))}, got {sorted(translated)}')
                    variants = translated.items()
                else:
                    variants = [(None, translated)]
                for category, text in variants:
                    if not isinstance(text, str) or not text.strip():
                        errors.append(f'{locale}:{key}: empty or non-string value')
                    elif BIDI.search(text):
                        errors.append(f'{locale}:{key}: contains bidi control characters')
                    elif not arguments_match(original['other'] if isinstance(original, dict) else original,
                                             text, locale, category):
                        errors.append(f'{locale}:{key}: argument signature differs: {signatures(text)} != {expected}')
            print(f'{locale}: {len(messages)} messages')
        except (ValueError, KeyError, TypeError, AttributeError) as error:
            errors.append(f'{locale}: {error}')
    for error in errors:
        print(error, file=sys.stderr)
    return 1 if errors else 0


def audit(selected):
    source = read(DIRECTORY / 'en.json')['messages']
    findings = {}
    for locale in selected or (key for key in LOCALES if key != 'en'):
        path = DIRECTORY / f'{locale}.json'
        if not path.exists():
            continue
        translated = read(path)['messages']
        unchanged = {
            key: original for key, original in source.items()
            if key in translated and translated[key] == original
            and len(re.findall(r'[A-Za-z]{2,}', str(original))) >= 2
        }
        findings[locale] = unchanged
        print(f'{locale}: {len(unchanged)} unchanged messages need language review', file=sys.stderr)
    print(json.dumps(findings, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['validate', 'audit', 'context'])
    parser.add_argument('locales', nargs='*', choices=list(LOCALES))
    args = parser.parse_args()
    if args.command == 'audit':
        audit(args.locales)
        return 0
    if args.command == 'context':
        return validate_context()
    return validate(args.locales)


if __name__ == '__main__':
    sys.exit(main())
