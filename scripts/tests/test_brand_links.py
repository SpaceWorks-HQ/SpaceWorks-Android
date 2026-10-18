"""Owner link validation and actual prepare.py integration; no network requests."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('brand_links', SCRIPTS / 'brand_links.py')
LINKS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LINKS)


class BrandLinksTest(unittest.TestCase):
    def test_missing_and_explicit_null_remain_nullable_without_fallback(self):
        for brand in ({}, {'support_url': None, 'privacy_url': None}):
            with self.subTest(brand=brand):
                self.assertIn('val supportURL: String? = null', LINKS.source(brand))
                self.assertIn('val privacyURL: String? = null', LINKS.source(brand))
                self.assertNotIn('https:', LINKS.source(brand))

    def test_legal_paths_queries_fragments_and_ports_are_preserved(self):
        for value in ('https://help.example.org/support/contact?topic=account&lang=en#email',
                      'https://privacy.example.org:8443/legal/a%20b?return=$account#retention',
                      'https://[2001:db8::1]:443/privacy', 'https://localhost/privacy'):
            with self.subTest(value=value):
                self.assertEqual(LINKS.optional_https_url(value, 'support_url'), value)

    def test_scheme_and_idna_host_are_normalized_for_android_java_uri_policy(self):
        self.assertEqual(LINKS.optional_https_url('HTTPS://B\u00dcCHER.example:0443/p%20a?q=$owner#legal', 'support_url'),
                         'https://xn--bcher-kva.example:0443/p%20a?q=$owner#legal')
        self.assertEqual(LINKS.optional_https_url('HTTPS://[2001:DB8::1]:443/policy', 'privacy_url'),
                         'https://[2001:db8::1]:443/policy')
        self.assertEqual(LINKS.optional_https_url('HTTPS://EXAMPLE.ORG/legal', 'support_url'),
                         'https://example.org/legal')

    def test_malformed_escapes_and_raw_java_uri_illegal_characters_are_rejected(self):
        for suffix in ('%ZZ', '%2', '%', 'a{b}', 'a}b', 'a"b', 'a<b', 'a>b', 'a|b',
                       'a^b', 'a`b', 'a[b]', '?q={bad}', '#bad#fragment'):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                LINKS.optional_https_url('https://example.org/' + suffix, 'privacy_url')
        self.assertEqual(LINKS.optional_https_url('https://example.org/a%7Bb%7D?q=%22ok%22#part%25', 'privacy_url'),
                         'https://example.org/a%7Bb%7D?q=%22ok%22#part%25')

    def test_insecure_relative_missing_host_credentials_and_malformed_authority_are_rejected(self):
        for value in ('', 'http://example.org', '//example.org/a', '/legal', 'https:example.org',
                      'https:///legal', 'https://', 'javascript:alert(1)',
                      'https://user:secret@example.org/legal', 'https://@example.org',
                      'https://example.org@attacker.example', 'https://example.org:bad',
                      'https://example.org:65536', 'https://example.org:',
                      'https://bad%2f.example/legal', 'https://.example.org', 'https://[invalid]/'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                LINKS.optional_https_url(value, 'privacy_url')

    def test_controls_whitespace_backslashes_and_nonstring_values_are_rejected(self):
        for value in (' https://example.org', 'https://example.org ', 'https://example.org/a b',
                      'https://example.org\n', 'https://example.org/\tpolicy',
                      'https://example.org/\x00policy', 'https://example.org/\x7f',
                      'https://example.org/\u200bpolicy', 'https://example.org/\u00a0policy',
                      'https://example.org\\@attacker.example', True, False, 42, [], {}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                LINKS.optional_https_url(value, 'support_url')

    def test_error_does_not_echo_private_value(self):
        with self.assertRaises(ValueError) as result:
            LINKS.optional_https_url('https://user:super-secret@example.org/private', 'support_url')
        self.assertIn('support_url', str(result.exception))
        self.assertNotIn('super-secret', str(result.exception))
        self.assertNotIn('example.org', str(result.exception))

    def test_kotlin_literals_escape_interpolation_quotes_backslashes_and_unicode(self):
        self.assertEqual(LINKS.kotlin_literal(None), 'null')
        self.assertEqual(LINKS.kotlin_literal('"\\\n$owner \u00e9'), '"\\"\\\\\\n\\$owner \\u00e9"')
        rendered = LINKS.source({'support_url': 'https://help.example.org/?q=$owner',
                                 'privacy_url': 'https://b\u00fccher.example/privacy'})
        self.assertIn('https://help.example.org/?q=\\$owner', rendered)
        self.assertIn('https://xn--bcher-kva.example/privacy', rendered)

    def test_generation_is_deterministic_and_failed_validation_preserves_output(self):
        with tempfile.TemporaryDirectory() as folder:
            brand = {'privacy_url': 'https://privacy.example.org/legal#retention'}
            output = LINKS.generate_brand_links(folder, brand)
            first = output.read_bytes()
            LINKS.generate_brand_links(folder, brand)
            self.assertEqual(output.read_bytes(), first)
            with self.assertRaises(ValueError):
                LINKS.generate_brand_links(folder, {'support_url': 'http://bad.example'})
            self.assertEqual(output.read_bytes(), first)

    def test_actual_prepare_script_emits_links_and_rejects_invalid_brand_before_outputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'scripts').mkdir()
            for name in ('prepare.py', 'brand_links.py', 'brand_fonts.py'):
                shutil.copyfile(SCRIPTS / name, root / 'scripts' / name)
            (root / 'branding').mkdir()
            (root / 'branding/icon.svg').write_text('<svg viewBox="0 0 16 16"><path d="M0,0h16v16z" fill="#142C2A"/></svg>')
            (root / 'tokens').mkdir()
            (root / 'tokens/theme.json').write_text(json.dumps({'light': {}, 'dark': {}}))
            (root / 'tokens/mobile-motion.json').write_text(json.dumps({
                'duration_ms': {}, 'easing': {'overlay_enter_cubic_bezier': [0.32, 0.72, 0, 1]},
                'onboarding': {'blocking_delay_ms': 0},
                'reduce_motion': {'opacity_duration_ms': 0, 'spatial_transitions': False}}))
            brand = {'theme_tokens': 'tokens/theme.json', 'app_icon': 'branding/icon.svg',
                     'support_url': 'https://help.example.org/contact?topic=$account#email', 'privacy_url': None}
            for key in ('logo_symbol', 'logo_symbol_reversed', 'logo_symbol_one_colour_ink',
                        'logo_stacked', 'logo_stacked_reversed', 'logo_stacked_one_colour_ink'):
                brand[key] = 'branding/icon.svg'
            for face, filename in [('regular', 'Regular'), ('medium', 'Medium'), ('semibold', 'SemiBold'), ('bold', 'Bold')]:
                source = SCRIPTS.parent / 'design/fonts' / ('Gilroy-' + filename + '.woff')
                target = root / ('branding/gilroy_' + face + '.woff')
                shutil.copyfile(source, target)
                brand['font_' + face] = str(target.relative_to(root))
            config = root / 'branding/brand.json'
            config.write_text(json.dumps(brand))
            command = [sys.executable, str(root / 'scripts/prepare.py')]
            subprocess.run(command, check=True, capture_output=True, text=True)
            output = root / 'app/build/generated/source/brand/org/spaceworks/app/theme/BrandLinks.kt'
            self.assertEqual(output.read_text(encoding='utf-8'), LINKS.source(brand))
            self.assertTrue(output.with_name('BrandTokens.kt').exists())
            before = output.read_bytes()
            brand['privacy_url'] = 'https://user:secret@example.org/private'
            config.write_text(json.dumps(brand))
            failed = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(failed.returncode, 0)
            self.assertNotIn('user:secret', failed.stderr)
            self.assertEqual(output.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
