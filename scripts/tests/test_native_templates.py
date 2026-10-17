"""Run with --jar after Gradle resolves its pinned generator; failures never skip."""
import argparse
import hashlib
import itertools
from pathlib import Path
import re
import tempfile
import unittest
import zipfile

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] if Path(__file__).parent.name == "tests" else Path(__file__).parent))
import native_templates as templates

GENERATOR_JAR: Path


def sections(source: str, flags: dict[str, bool]) -> str:
    """Evaluate only boolean Mustache sections in the audited constructor fragment."""
    pattern = re.compile(r"{{([#^/])([A-Za-z_]+)}}")
    output = []
    stack = [True]
    names = []
    end = 0
    for match in pattern.finditer(source):
        if stack[-1]:
            output.append(source[end:match.start()])
        action, name = match.groups()
        if action == "/":
            if not names or names.pop() != name:
                raise AssertionError("Unbalanced template section")
            stack.pop()
        else:
            names.append(name)
            stack.append(stack[-1] and (flags[name] if action == "#" else not flags[name]))
        end = match.end()
    if names:
        raise AssertionError("Unclosed template section")
    if stack[-1]:
        output.append(source[end:])
    return "".join(output)


class NativeTemplateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(GENERATOR_JAR) as archive:
            cls.originals = {name: archive.read(name) for name in (templates.ARTIFACT, *templates.HASHES)}

    def fixture(self, destination: Path, *, version=None, altered=None) -> Path:
        with zipfile.ZipFile(destination, "w") as archive:
            for name, content in self.originals.items():
                if version and name == templates.ARTIFACT:
                    content = content.replace(templates.VERSION.encode(), version.encode())
                if altered == name:
                    content += b"\nmodified\n"
                archive.writestr(name, content)
        return destination

    def test_both_overrides_preserve_every_unmodified_byte_and_import(self):
        patched = templates.patched_templates(GENERATOR_JAR)
        self.assertEqual(self.originals[templates.MODEL_RESOURCE].decode(),
            patched["data_class.mustache"].replace(templates.CORRECT_PARENT_SUFFIX, templates.ORIGINAL_PARENT_SUFFIX))
        self.assertEqual(self.originals[templates.API_RESOURCE].decode(),
            patched["libraries/jvm-retrofit2/api.mustache"].replace(templates.API_STREAMING_ANNOTATION, "").replace(templates.API_WILDCARD_ANNOTATION, ""))
        self.assertIn("import retrofit2.http.*", patched["libraries/jvm-retrofit2/api.mustache"])

    def test_constructor_invoked_once_iff_any_original_condition_is_true(self):
        keys = ["isMap", "isArray", "kotlinx_serialization", "multiplatform"]
        for values in itertools.product([False, True], repeat=4):
            flags = dict(zip(keys, values))
            with self.subTest(**flags):
                self.assertEqual("()" if any(values) else "", sections(templates.CORRECT_PARENT_SUFFIX, flags))

    def test_streaming_is_per_binary_operation_only(self):
        self.assertIn("@Streaming", sections(templates.API_STREAMING_ANNOTATION, {"isResponseFile": True}))
        self.assertNotIn("@Streaming", sections(templates.API_STREAMING_ANNOTATION, {"isResponseFile": False}))

    def test_retrofit_interface_suppresses_nested_jvm_wildcards_once(self):
        api = templates.patched_templates(GENERATOR_JAR)["libraries/jvm-retrofit2/api.mustache"]
        self.assertEqual(1, api.count(templates.API_WILDCARD_ANNOTATION))
        self.assertIn(templates.API_WILDCARD_ANNOTATION + templates.API_INTERFACE_ANCHOR, api)
        # Public/internal/explicit API flags retain the generator's exact declaration.
        self.assertEqual(1, api.count(templates.API_INTERFACE_ANCHOR))

    def test_wrong_version_or_template_hash_fails_before_output_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "out"
            output.mkdir()
            sentinel = output / "data_class.mustache"
            sentinel.write_text("existing", encoding="utf-8")
            for version, altered in [("7.27.0", None), (None, templates.MODEL_RESOURCE), (None, templates.API_RESOURCE)]:
                jar = self.fixture(root / "generator.jar", version=version, altered=altered)
                with self.assertRaises(templates.TemplateMismatch):
                    templates.generate(jar, output)
                self.assertEqual("existing", sentinel.read_text(encoding="utf-8"))
                self.assertFalse((output / "libraries").exists())

    def test_generation_is_deterministic_and_overrides_only_two_correct_lookup_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "templates"
            templates.generate(GENERATOR_JAR, output)
            first = {str(path.relative_to(output)).replace("\\", "/"): path.read_bytes() for path in output.rglob("*") if path.is_file()}
            templates.generate(GENERATOR_JAR, output)
            second = {str(path.relative_to(output)).replace("\\", "/"): path.read_bytes() for path in output.rglob("*") if path.is_file()}
            self.assertEqual(first, second)
            self.assertEqual({"data_class.mustache", "libraries/jvm-retrofit2/api.mustache"}, set(first))

    def test_source_hashes_pin_exact_published_resource_bytes(self):
        for name, expected in templates.HASHES.items():
            self.assertEqual(expected, hashlib.sha256(self.originals[name]).hexdigest())
        with self.assertRaises(templates.TemplateMismatch):
            templates.replace_once("missing", "anchor", "new", "test")
        with self.assertRaises(templates.TemplateMismatch):
            templates.replace_once("anchoranchor", "anchor", "new", "test")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True, type=Path)
    arguments = parser.parse_args()
    GENERATOR_JAR = arguments.jar
    unittest.main(argv=[__file__])
