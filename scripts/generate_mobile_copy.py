"""Generate typed, English JVM fallback keys from the Android English XML source.

No translations, transport models, resource compilation, or production R IDs are generated.
Use --check in CI after the catalog/code has been reviewed. Xcode/app files are untouched.
"""
import argparse
import json
import pathlib
import re
import xml.etree.ElementTree as ET

def android_text(value):
    raw = ''.join(value.itertext())
    if raw.startswith('"') and raw.endswith('"'):
        # Quoted Android values used by these catalogs preserve spaces and apostrophes.
        return json.loads(raw)
    value = re.sub(r'\s+', ' ', raw).strip()
    return re.sub(r'\\([ntr\'"\\@?])', lambda m: {'n':'\n','t':'\t','r':'\r'}.get(m[1], m[1]), value)

def entries(catalog):
    result = []
    seen = set()
    for node in ET.parse(catalog).getroot():
        if node.tag not in {'string', 'plurals'}: continue
        key = node.attrib['name']
        if not re.fullmatch(r'[a-z][a-z0-9_]*', key) or key in seen:
            raise ValueError(f'Invalid or duplicate resource name: {key}')
        seen.add(key)
        if node.tag == 'string': result.append((key, android_text(node), None))
        else:
            quantities = {item.attrib['quantity']: android_text(item) for item in node}
            if 'one' not in quantities or 'other' not in quantities:
                raise ValueError(f'English plurals require one and other: {key}')
            result.append((key, quantities['other'], quantities['one']))
    return result

def kotlin(catalog, package, owner):
    quote = lambda value: json.dumps(value, ensure_ascii=False).replace('$', '\\$')
    lines = ['// Generated from '+pathlib.Path(catalog).name+' by generate_mobile_copy.py. Do not edit.',
             'package '+package, '', 'import org.spaceworks.app.R',
             'import org.spaceworks.app.platform.MobileCopyKey', '', 'object '+owner+' {']
    for key, other, one in entries(catalog):
        lines.append('    val '+key.upper()+'=MobileCopyKey(R.'+('plurals' if one else 'string')+'.'+key+','+
                     quote(other)+((','+quote(one)) if one else '')+')')
    return '\n'.join(lines+['}', ''])

def write_or_check(output, value, check=False):
    output = pathlib.Path(output)
    if check:
        if not output.is_file() or output.read_text(encoding='utf8') != value:
            raise SystemExit(f'Stale generated English copy: {output}')
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(value, encoding='utf8')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=pathlib.Path, required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--object', dest='owner', required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    write_or_check(args.output, kotlin(args.catalog, args.package, args.owner), args.check)

if __name__ == '__main__': main()
