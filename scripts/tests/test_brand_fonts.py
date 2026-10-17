import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('brand_fonts', ROOT/'scripts/brand_fonts.py')
FONTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FONTS)
BRAND = json.loads((ROOT/'branding/brand.json').read_text())


class BrandFontsTest(unittest.TestCase):
    def test_all_supplied_tables_names_outlines_and_metrics_survive_native_conversion(self):
        for face in FONTS.FACES:
            source = (ROOT/BRAND['font_'+face]).read_bytes()
            flavor, total, original = FONTS.woff_tables(source)
            converted = FONTS.native_font(source)
            self.assertEqual(len(converted), total)
            self.assertEqual(FONTS.checksum(converted), 0xb1b0afba)
            self.assertEqual(struct.unpack('>I', converted[:4])[0], flavor)
            count = struct.unpack('>H', converted[4:6])[0]
            self.assertEqual(count, len(original))
            for index in range(count):
                tag, expected, offset, length = struct.unpack('>4sIII', converted[12+16*index:28+16*index])
                actual = converted[offset:offset+length]
                before = original[tag]
                if tag == b'head':
                    # The sfnt container's only changed table bytes: its whole-file checksum.
                    actual = actual[:8] + b'\0'*4 + actual[12:]
                    before = before[:8] + b'\0'*4 + before[12:]
                self.assertEqual(actual, before, (face, tag))
                self.assertEqual(FONTS.checksum(actual), expected)
            self.assertTrue({b'name', b'cmap', b'glyf', b'hmtx', b'OS/2'} <= original.keys())

    def test_generated_four_weights_and_manifest_are_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            brand = {key: str((ROOT/value).resolve()) for key,value in BRAND.items() if key.startswith('font_')}
            first = FONTS.generate_brand_fonts(directory, brand)
            second = FONTS.generate_brand_fonts(directory, brand)
            self.assertEqual(first, second)
            self.assertEqual([first[face]['weight'] for face in FONTS.FACES], [400,500,600,700])
            self.assertEqual(len(list(Path(directory).rglob('*.ttf'))), 4)
            self.assertEqual(len({record['ttf_sha256'] for record in first.values()}), 4)
            from xml.etree import ElementTree as ET
            family=ET.parse(Path(directory)/'app/build/generated/res/brand/font/gilroy.xml').getroot()
            self.assertEqual(len(family),4)
            for node,(face,weight) in zip(family,FONTS.FACES.items()):
                for namespace in ('http://schemas.android.com/apk/res/android','http://schemas.android.com/apk/res-auto'):
                    self.assertEqual(node.get('{'+namespace+'}fontWeight'),str(weight))
                    self.assertEqual(node.get('{'+namespace+'}font'),'@font/gilroy_'+face)

    def test_invalid_font_cannot_replace_an_existing_native_font_set(self):
        with tempfile.TemporaryDirectory() as directory:
            brand = {key: str((ROOT/value).resolve()) for key,value in BRAND.items() if key.startswith('font_')}
            FONTS.generate_brand_fonts(directory, brand)
            output = Path(directory)/'app/build/generated/res/brand/font/gilroy_regular.ttf'
            before = output.read_bytes()
            bad = Path(directory)/'broken.woff'
            bad.write_bytes(b'not a font')
            brand['font_bold'] = str(bad)
            with self.assertRaises(ValueError):
                FONTS.generate_brand_fonts(directory, brand)
            self.assertEqual(before, output.read_bytes())


if __name__ == '__main__':
    unittest.main()
