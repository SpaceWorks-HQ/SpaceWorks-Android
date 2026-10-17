"""Repackage supplied WOFF1 sfnt tables as Android TTF; no font editing or dependencies."""
from pathlib import Path
import hashlib
import json
import struct
import zlib

FACES = {'regular': 400, 'medium': 500, 'semibold': 600, 'bold': 700}


def checksum(data):
    padded = data + b'\0' * (-len(data) % 4)
    return sum(struct.unpack('>' + 'I' * (len(padded) // 4), padded)) & 0xffffffff


def woff_tables(data):
    if len(data) < 44 or data[:4] != b'wOFF':
        raise ValueError('Brand font must be a WOFF1 sfnt')
    _, flavor, length, count, reserved, total = struct.unpack('>4sIIHHI', data[:20])
    if length != len(data) or reserved or not count or 44 + count * 20 > length:
        raise ValueError('Invalid WOFF directory')
    tables = {}
    for i in range(count):
        tag, offset, compressed, original, expected = struct.unpack('>4sIIII', data[44+i*20:64+i*20])
        if tag in tables or offset + compressed > length or compressed > original:
            raise ValueError('Invalid WOFF table')
        raw = data[offset:offset+compressed]
        if compressed != original:
            raw = zlib.decompress(raw)
        verified = raw[:8] + b'\0' * 4 + raw[12:] if tag == b'head' else raw
        if len(raw) != original or checksum(verified) != expected:
            raise ValueError('WOFF table checksum mismatch')
        tables[tag] = raw
    return flavor, total, tables


def native_font(data):
    flavor, total, tables = woff_tables(data)
    count = len(tables)
    selector = count.bit_length() - 1
    search = (1 << selector) * 16
    header = struct.pack('>IHHHH', flavor, count, search, selector, count*16-search)
    directory = bytearray()
    body = bytearray()
    head_offset = None
    for tag, raw in sorted(tables.items()):
        offset = 12 + count*16 + len(body)
        if tag == b'head':
            raw = raw[:8] + b'\0'*4 + raw[12:]
            head_offset = offset
        directory.extend(struct.pack('>4sIII', tag, checksum(raw), offset, len(raw)))
        body.extend(raw + b'\0' * (-len(raw) % 4))
    result = bytearray(header + directory + body)
    if head_offset is None or len(result) != total:
        raise ValueError('Invalid sfnt size or missing head table')
    struct.pack_into('>I', result, head_offset+8, (0xb1b0afba-checksum(result)) & 0xffffffff)
    return bytes(result)


def generate_brand_fonts(root, brand):
    root = Path(root)
    prepared = {}
    manifest = {}
    for face, weight in FACES.items():
        source = root / brand['font_' + face]
        original = source.read_bytes()
        prepared[face] = native_font(original)
        manifest[face] = {'source': brand['font_' + face], 'weight': weight,
                          'source_sha256': hashlib.sha256(original).hexdigest(),
                          'ttf_sha256': hashlib.sha256(prepared[face]).hexdigest()}
    output = root/'app/build/generated/res/brand/font'
    output.mkdir(parents=True, exist_ok=True)
    for face, data in prepared.items():
        (output/('gilroy_' + face + '.ttf')).write_bytes(data)
    report = root/'app/build/generated/brand-fonts.json'
    report.write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
    return manifest
