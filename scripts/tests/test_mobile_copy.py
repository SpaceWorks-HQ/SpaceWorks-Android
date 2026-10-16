"""Generator safety tests; no Android or translated locale is simulated."""
from pathlib import Path
import importlib.util
import tempfile
import unittest

ROOT=Path(__file__).resolve().parent
if ROOT.name=='tests':ROOT=ROOT.parent
SPEC=importlib.util.spec_from_file_location('mobile_copy',ROOT/'generate_mobile_copy.py')
COPY=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COPY)


class MobileCopyTest(unittest.TestCase):
    def catalog(self, folder, contents):
        file=Path(folder)/'copy.xml';file.write_text('<resources>'+contents+'</resources>',encoding='utf-8');return file

    def test_quoted_android_text_numbered_args_and_kotlin_interpolation_are_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            catalog=self.catalog(folder,'<string name="message">"Hello %1$s. Don\'t change $value.\\nNext line."</string>')
            output=COPY.kotlin(catalog,'example.copy','Copy')
            self.assertIn('Hello %1\\$s. Don\'t change \\$value.\\nNext line.',output)
            self.assertIn('MobileCopyKey(R.string.message',output)

    def test_plural_other_and_one_are_explicit_and_argument_order_remains_numbered(self):
        with tempfile.TemporaryDirectory() as folder:
            catalog=self.catalog(folder,'<plurals name="items"><item quantity="other">%1$s tools for %2$s</item><item quantity="one">%1$s tool for %2$s</item></plurals>')
            output=COPY.kotlin(catalog,'example.copy','Copy')
            self.assertIn('R.plurals.items',output)
            self.assertIn('"%1\\$s tools for %2\\$s","%1\\$s tool for %2\\$s"',output)

    def test_duplicate_invalid_and_incomplete_catalogs_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            for xml in ('<string name="same">One</string><string name="same">Two</string>',
                        '<string name="Invalid-Name">One</string>',
                        '<plurals name="items"><item quantity="other">Many</item></plurals>'):
                with self.subTest(xml=xml),self.assertRaises(ValueError):COPY.entries(self.catalog(folder,xml))

    def test_check_mode_fails_on_drift_without_rewriting_the_reviewed_file(self):
        with tempfile.TemporaryDirectory() as folder:
            output=Path(folder)/'Keys.kt';output.write_text('reviewed',encoding='utf-8')
            with self.assertRaises(SystemExit):COPY.write_or_check(output,'changed',check=True)
            self.assertEqual('reviewed',output.read_text())
            COPY.write_or_check(output,'reviewed',check=True)


if __name__=='__main__':unittest.main()
