#!/usr/bin/env python3
"""Check this documentation contract bundle; not an Android/runtime test suite.

The schema walker supports only keywords currently used by these schemas and
fails on unknown keywords. It is deliberately not a general JSON Schema engine.
"""
from pathlib import Path
from datetime import datetime, date
from urllib.parse import unquote, urlsplit
import argparse
import json
import re
import sys
import uuid


def require(ok, message):
    if not ok:
        raise ValueError(message)


def validate(value, schema, base, at='$'):
    allowed = {'$schema', 'title', '$ref', 'type', 'properties', 'required',
               'additionalProperties', 'items', 'minItems', 'uniqueItems',
               'enum', 'const', 'format', 'minimum', 'maximum', 'pattern', 'oneOf'}
    require(not set(schema) - allowed, f'{at}: unsupported schema keywords')
    if '$ref' in schema:
        target = (base / schema['$ref']).resolve()
        require(target.is_relative_to(base.resolve()), f'{at}: external ref forbidden')
        return validate(value, json.loads(target.read_text()), target.parent, at)
    if 'oneOf' in schema:
        successes = 0
        for candidate in schema['oneOf']:
            try:
                validate(value, candidate, base, at)
                successes += 1
            except ValueError:
                pass
        require(successes == 1, f'{at}: must match exactly one block variant')
    if 'const' in schema:
        expected = schema['const']
        require(type(value) is type(expected) and value == expected, f'{at}: const mismatch')
    if 'enum' in schema:
        require(value in schema['enum'], f'{at}: enum mismatch')
    kind = schema.get('type')
    checks = {'object': lambda: isinstance(value, dict),
              'array': lambda: isinstance(value, list),
              'string': lambda: isinstance(value, str),
              'integer': lambda: type(value) is int,
              'boolean': lambda: type(value) is bool}
    if kind:
        require(kind in checks and checks[kind](), f'{at}: expected {kind}')
    if kind == 'object':
        require(set(schema.get('required', [])) <= set(value), f'{at}: required field missing')
        props = schema.get('properties', {})
        if schema.get('additionalProperties') is False:
            require(not set(value) - set(props), f'{at}: unknown fields')
        for key, child in value.items():
            if key in props:
                validate(child, props[key], base, at + '.' + key)
    if kind == 'array':
        require(len(value) >= schema.get('minItems', 0), f'{at}: too few items')
        if schema.get('uniqueItems'):
            require(len(value) == len({json.dumps(v, sort_keys=True) for v in value}), f'{at}: duplicates')
        for i, child in enumerate(value):
            validate(child, schema['items'], base, f'{at}[{i}]')
    if kind == 'integer':
        require(value >= schema.get('minimum', value), f'{at}: below minimum')
        require(value <= schema.get('maximum', value), f'{at}: above maximum')
    if kind == 'string':
        if 'pattern' in schema:
            require(re.search(schema['pattern'], value), f'{at}: pattern mismatch')
        fmt = schema.get('format')
        try:
            if fmt == 'uuid':
                uuid.UUID(value)
            elif fmt == 'date':
                date.fromisoformat(value)
            elif fmt == 'date-time':
                require(datetime.fromisoformat(value.replace('Z', '+00:00')).tzinfo is not None,
                        f'{at}: timezone missing')
            elif fmt:
                raise ValueError('unsupported format')
        except ValueError as error:
            raise ValueError(f'{at}: invalid {fmt}') from error


def run(root):
    root = root.resolve()
    files = [p for p in root.rglob('*') if p.is_file() and '.git' not in p.parts]
    markdown = [p for p in files if p.suffix == '.md']
    documents = {}
    for path in files:
        if path.suffix == '.json':
            documents[str(path.relative_to(root))] = json.loads(path.read_text())
    for path in markdown:
        text = re.sub(r'```.*?```', '', path.read_text(), flags=re.S)
        for link in re.findall(r'\[[^\]]*\]\(([^)]+)\)', text):
            target = link.strip().split(' "')[0].strip('<>')
            if not target or target.startswith('#') or urlsplit(target).scheme:
                continue
            local = (path.parent / unquote(target.split('#')[0])).resolve()
            require(local.is_relative_to(root), f'{path.name}: link escapes repository: {target}')
            require(local.exists(), f'{path.relative_to(root)}: broken link: {target}')
    catalog = documents['contracts/feature-catalog.json']
    features = catalog['features']
    require([f['id'] for f in features] == [f'F{i:02}' for i in range(1, 21)], 'F01-F20 incomplete or reordered')
    screen_text = (root / 'docs/design/screens.md').read_text()
    test_text = (root / 'docs/quality/test-cases.md').read_text()
    for feature in features:
        require((root / feature['spec']).is_file(), f"{feature['id']}: missing spec")
        require(feature['status'] in catalog['status_values'], 'invalid feature status')
        if feature['status'] == 'verified':
            require(feature['evidence'], f"{feature['id']}: verified without evidence")
        for screen in feature['screens']:
            require(f'## {screen} ·' in screen_text, f'missing screen {screen}')
        for case in feature['tests']:
            require(f'| {case} |' in test_text, f'missing test {case}')
    tasks = documents['contracts/task-catalog.json']['tasks']
    graph = {t['id']: t['dependencies'] for t in tasks}
    require(len(graph) == len(tasks), 'duplicate task ID')
    visiting, visited = set(), set()
    def visit(node):
        require(node in graph, f'unknown dependency {node}')
        require(node not in visiting, f'dependency cycle at {node}')
        if node in visited:
            return
        visiting.add(node)
        for dep in graph[node]:
            visit(dep)
        visiting.remove(node)
        visited.add(node)
    for node in graph:
        visit(node)
    for name, schema_name in [('scope', 'note-scope'), ('note-document', 'note-document'), ('evidence', 'evidence')]:
        validate(documents[f'examples/{name}.json'], documents[f'contracts/{schema_name}.schema.json'], root / 'contracts')
    note = documents['examples/note-document.json']
    blocks = note['blocks']
    require(len({b['id'] for b in blocks}) == len(blocks), 'duplicate block IDs')
    for block in blocks:
        if 'text' in block:
            encoded = block['text'].encode('utf-16-le')
            for mark in block.get('marks', []):
                start, end = mark['start'], mark['end']
                require(0 <= start < end <= len(encoded)//2, 'invalid mark range')
                try:
                    encoded[:start*2].decode('utf-16-le')
                    encoded[:end*2].decode('utf-16-le')
                except UnicodeDecodeError as error:
                    raise ValueError('mark splits surrogate pair') from error
    evidence = documents['examples/evidence.json']
    citations = [s['citation_id'] for s in evidence['sources']]
    require(len(set(citations)) == len(citations), 'duplicate citation IDs')
    coverage = evidence['coverage']
    require(coverage['processed_count'] + coverage['failed_count'] <= coverage['eligible_count'], 'coverage exceeds eligible set')
    for scope in [documents['examples/scope.json'], evidence['scope']]:
        require(not (scope['untagged_only'] and scope['include_tag_ids']), 'contradictory scope')
        if 'from_inclusive' in scope and 'to_exclusive' in scope:
            require(datetime.fromisoformat(scope['from_inclusive'].replace('Z', '+00:00')) <
                    datetime.fromisoformat(scope['to_exclusive'].replace('Z', '+00:00')), 'empty time range')
    events = documents['examples/agent-events.json']['events']
    require([e['event_seq'] for e in events] == list(range(1, len(events)+1)), 'event sequence gap/duplicate')
    for event in events:
        if event['type'] == 'citation.ready':
            require(event['payload']['citation_id'] in citations, 'event cites missing evidence')
    tokens = documents['contracts/design-tokens.json']
    require(tokens['touch_target_min'] >= 48, 'touch target below specification')
    require(tokens['motion']['interruptible'] and not tokens['motion']['background_loop'], 'motion contract mismatch')
    require(not tokens['glass']['G2']['foreground_blur'], 'foreground must remain clear')
    tools = documents['contracts/tool-registry.json']['tools']
    require(len({t['name'] for t in tools}) == len(tools), 'duplicate tool')
    require(all(t['scope_enforced'] for t in tools), 'tool without scope enforcement')
    require(all(t['idempotency_required'] for t in tools if t['kind'] == 'write'), 'write tool without idempotency')
    print(f'PASS: {len(markdown)} Markdown files, {len(documents)} JSON files, {len(features)} features, {len(tasks)} tasks')
    print('Checked file links, contract examples, dependency DAG, feature mappings and key invariants.')
    print('Not checked: external URLs, Markdown anchors, Android/server behavior, device performance, full JSON Schema compliance.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        run(args.root)
    except (ValueError, KeyError, OSError, TypeError) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        sys.exit(1)
