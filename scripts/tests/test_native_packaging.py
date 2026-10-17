import importlib.util
import pathlib
import struct
import unittest

spec = importlib.util.spec_from_file_location('packaging_check', pathlib.Path(__file__).parents[1] / 'check_native_packaging.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def elf(segments):
    data = bytearray(64 + 56 * len(segments))
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, len(segments))
    for index, (offset, virtual, alignment) in enumerate(segments):
        entry = 64 + index * 56
        struct.pack_into('<I', data, entry, 1)
        struct.pack_into('<QQ', data, entry + 8, offset, virtual)
        struct.pack_into('<Q', data, entry + 48, alignment)
    return data


class NativePackagingTests(unittest.TestCase):
    def test_valid_16kb_and_larger_load_segments_pass(self):
        self.assertEqual(check.elf_load_alignments(elf([(0, 0, 16384), (32768, 65536, 32768)])), [16384, 32768])

    def test_later_4kb_segment_cannot_hide_behind_valid_first_segment(self):
        with self.assertRaisesRegex(ValueError, 'segment 1'):
            check.elf_load_alignments(elf([(0, 0, 16384), (4096, 4096, 4096)]))

    def test_claimed_alignment_does_not_override_incongruent_load_address(self):
        with self.assertRaisesRegex(ValueError, 'not 16 KB aligned'):
            check.elf_load_alignments(elf([(4096, 8192, 16384)]))

    def test_truncated_invalid_or_empty_program_headers_fail(self):
        for data in [b'', b'not ELF', elf([]), elf([(0, 0, 16384)])[:-1]]:
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                check.elf_load_alignments(data)


class DeviceFeatureTests(unittest.TestCase):
    BASE = "uses-feature-not-required: name='android.hardware.camera.any'\nsupports-screens: 'small' 'normal' 'large' 'xlarge'\n"

    def test_optional_camera_front_only_and_wifi_tablets_are_supported(self):
        result = check.device_features(self.BASE + "uses-feature-not-required: name='android.hardware.camera'\nuses-feature-not-required: name='android.hardware.nfc'\nuses-feature: name='android.hardware.faketouch'\n")
        self.assertEqual(result['required_features'], ['android.hardware.faketouch'])
        self.assertIn('xlarge', result['screen_sizes'])

    def test_permission_inferred_rear_camera_autofocus_and_telephony_are_rejected(self):
        for name in ['android.hardware.camera', 'android.hardware.camera.autofocus', 'android.hardware.telephony', 'android.hardware.telephony.gsm']:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'must remain optional'):
                check.device_features(self.BASE + "uses-feature: name='" + name + "'\n")

    def test_missing_tablet_screen_sizes_or_badging_cannot_silently_pass(self):
        for text in ['', self.BASE.replace(" 'xlarge'", ''), self.BASE.replace(" 'large'", '')]:
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, 'screens'):
                check.device_features(text)

    def test_implicit_camera_does_not_replace_explicit_optional_declaration(self):
        with self.assertRaisesRegex(ValueError, 'explicitly optional'):
            check.device_features("supports-screens: 'small' 'normal' 'large' 'xlarge'\n")


if __name__ == '__main__':
    unittest.main()
