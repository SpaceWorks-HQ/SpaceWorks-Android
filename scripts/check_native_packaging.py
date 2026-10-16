"""Check 64-bit ELF LOAD alignment and official zipalign for Android 16 KB packaging.

https://developer.android.com/guide/practices/page-sizes
This is binary/package validation, not a claim of runtime testing on a 16 KB device.
"""
import argparse
import json
import os
import pathlib
import struct
import subprocess
import zipfile

PAGE = 16 * 1024


def elf_load_alignments(data):
    if len(data) < 64 or data[:5] != b'\x7fELF\x02' or data[5] not in (1, 2):
        raise ValueError('Expected a valid ELF64 library')
    endian = '<' if data[5] == 1 else '>'
    offset = struct.unpack_from(endian + 'Q', data, 32)[0]
    stride, count = struct.unpack_from(endian + 'HH', data, 54)
    if stride < 56 or not count or offset + stride * count > len(data):
        raise ValueError('Invalid ELF64 program header table')
    loads = []
    for index in range(count):
        entry = offset + index * stride
        if struct.unpack_from(endian + 'I', data, entry)[0] != 1:
            continue
        file_offset, virtual_address = struct.unpack_from(endian + 'QQ', data, entry + 8)
        alignment = struct.unpack_from(endian + 'Q', data, entry + 48)[0]
        if alignment < PAGE or alignment & (alignment - 1) or (virtual_address - file_offset) % PAGE:
            raise ValueError(f'LOAD segment {index} is not 16 KB aligned (alignment={alignment})')
        loads.append(alignment)
    if not loads:
        raise ValueError('ELF64 library has no LOAD segments')
    return loads


def inspect_apk(path):
    libraries = []
    with zipfile.ZipFile(path) as apk:
        for item in apk.infolist():
            if not item.filename.startswith(('lib/arm64-v8a/', 'lib/x86_64/')) or not item.filename.endswith('.so'):
                continue
            try:
                alignments = elf_load_alignments(apk.read(item))
            except ValueError as error:
                raise ValueError(f'{item.filename}: {error}') from error
            libraries.append({'path': item.filename, 'load_alignments': alignments})
    return {'apk': str(path), 'elf64_libraries': libraries, 'elf64_count': len(libraries)}


def find_zipalign():
    root = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not root:
        raise ValueError('Set ANDROID_HOME or provide --zipalign from SDK Build Tools 35+')
    executable = 'zipalign.exe' if os.name == 'nt' else 'zipalign'
    candidates = list((pathlib.Path(root) / 'build-tools').glob('*/' + executable))
    def version(path):
        return tuple(int(value) for value in path.parent.name.split('.') if value.isdigit())
    candidates = [path for path in candidates if version(path) >= (35,)]
    if not candidates:
        raise ValueError('SDK Build Tools 35+ zipalign is required')
    return max(candidates, key=version)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks', nargs='+', type=pathlib.Path)
    parser.add_argument('--zipalign', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('validation/native-packaging.json'))
    args = parser.parse_args()
    zipalign = args.zipalign or find_zipalign()
    results = []
    for path in args.apks:
        result = inspect_apk(path)
        subprocess.run([str(zipalign), '-c', '-P', '16', '4', str(path)], check=True)
        result['zipalign_16kb'] = 'passed'
        results.append(result)
        print(f'{path}: {result["elf64_count"]} ELF64 libraries and APK zip alignment passed')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'page_bytes': PAGE, 'artifacts': results}, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
