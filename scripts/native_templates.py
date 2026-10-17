"""Extract two audited OpenAPI Generator7.26.0 overrides; never mutate the jar/schema.

Usage: python scripts/native_templates.py --jar GENERATOR_JAR --output BUILD_TEMPLATE_DIR
Gradle templateDir must point to BUILD_TEMPLATE_DIR. Only the two minimal templates
are overridden; every other resource remains the generator's pinned builtin.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import zipfile

VERSION = "7.26.0"
ARTIFACT = "META-INF/maven/org.openapitools/openapi-generator/pom.properties"
MODEL_RESOURCE = "kotlin-client/data_class.mustache"
API_RESOURCE = "kotlin-client/libraries/jvm-retrofit2/api.mustache"
HASHES = {
    MODEL_RESOURCE: "b85cc571810944a21ba3e62034325c0e9750c2b248c933b919b8ccbd8614f94e",
    API_RESOURCE: "22c857f2d23a1b4f52665b2c196d83d4c584ffbb85415476569b228b82cc71f0",
}
ORIGINAL_PARENT_SUFFIX = (
    "{{#isMap}}(){{/isMap}}{{#kotlinx_serialization}}(){{/kotlinx_serialization}}"
    "{{#multiplatform}}(){{/multiplatform}}{{#isArray}}(){{/isArray}}"
)
# Preserve the original condition's logical OR, emitting exactly one constructor.
# Non-map/array model parents still invoke their constructor for the same original
# serialization/multiplatform flags. Serializable/Parcelable branches stay intact.
CORRECT_PARENT_SUFFIX = (
    "{{#isMap}}(){{/isMap}}{{^isMap}}"
    "{{#isArray}}(){{/isArray}}{{^isArray}}"
    "{{#kotlinx_serialization}}(){{/kotlinx_serialization}}"
    "{{^kotlinx_serialization}}{{#multiplatform}}(){{/multiplatform}}{{/kotlinx_serialization}}"
    "{{/isArray}}{{/isMap}}"
)
API_ANNOTATION_ANCHOR = '    @{{httpMethod}}("{{{path}}}")\n'
API_STREAMING_ANNOTATION = "    {{#isResponseFile}}\n    @Streaming\n    {{/isResponseFile}}\n"
API_INTERFACE_ANCHOR = (
    "{{#nonPublicApi}}internal {{/nonPublicApi}}{{^nonPublicApi}}{{#explicitApi}}public {{/explicitApi}}{{/nonPublicApi}}interface {{classname}} {\n"
)
# Retrofit rejects Java wildcards in @Body map values. Suppress only JVM signature
# variance on generated service interfaces; Kotlin types and wire models stay intact.
API_WILDCARD_ANNOTATION = "@JvmSuppressWildcards\n"



class TemplateMismatch(ValueError):
    """Fail closed when dependency version or source templates need a fresh audit."""


def replace_once(source: str, before: str, after: str, name: str) -> str:
    if source.count(before) != 1:
        raise TemplateMismatch(f"Unexpected {name} anchor; audit the generator templates.")
    return source.replace(before, after, 1)


def patched_templates(jar: Path) -> dict[str, str]:
    with zipfile.ZipFile(jar) as archive:
        properties = dict(
            line.split("=", 1) for line in archive.read(ARTIFACT).decode("utf-8").splitlines()
            if "=" in line and not line.lstrip().startswith("#")
        )
        if properties.get("version") != VERSION or properties.get("artifactId") != "openapi-generator" or properties.get("groupId") != "org.openapitools":
            raise TemplateMismatch(f"Expected org.openapitools:openapi-generator:{VERSION}; audit before upgrading.")
        originals: dict[str, str] = {}
        for name, expected in HASHES.items():
            raw = archive.read(name)
            if hashlib.sha256(raw).hexdigest() != expected:
                raise TemplateMismatch(f"Unexpected bytes for {name}; audit before generating overrides.")
            originals[name] = raw.decode("utf-8")

    api = replace_once(originals[API_RESOURCE], API_INTERFACE_ANCHOR,
        API_WILDCARD_ANNOTATION + API_INTERFACE_ANCHOR, "Retrofit interface")
    return {
        "data_class.mustache": replace_once(originals[MODEL_RESOURCE], ORIGINAL_PARENT_SUFFIX, CORRECT_PARENT_SUFFIX, "parent constructor"),
        "libraries/jvm-retrofit2/api.mustache": replace_once(api, API_ANNOTATION_ANCHOR,
            API_STREAMING_ANNOTATION + API_ANNOTATION_ANCHOR, "Retrofit operation annotation"),
    }


def generate(jar: Path, output: Path) -> None:
    # Validate every input and prepare both overrides before creating/changing files.
    templates = patched_templates(jar)
    for relative, content in templates.items():
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    generate(args.jar, args.output)
    print(f"Prepared2 audited native templates from OpenAPI Generator{VERSION}.")


if __name__ == "__main__":
    main()
