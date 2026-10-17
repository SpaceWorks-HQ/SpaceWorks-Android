# Owner-supplied Gilroy native fonts

The pinned Design submodule (`fd28f825086a2cac7fa297881f1860b9d332db69`) contains the owner's Gilroy Regular, Medium, SemiBold and Bold WOFF files. The owner requested their use for the full mobile brand implementation. Their origin and license distinction remain documented in `design/fonts/README.md`; the legacy free Light/ExtraBold EULA does not license these four supplied faces.

`scripts/brand_fonts.py` reconstructs a native sfnt/TTF container using Python's standard library. Every decompressed table is preserved byte for byte, including glyph outlines, character coverage, names, layout and metrics. Only the `head.checkSumAdjustment` changes to account for the native container directory. No timestamps, family names, weight metadata or license metadata are rewritten. The generated `app/build/generated/brand-fonts.json` records input/output SHA-256 hashes and explicit Android weight assignments.

The supplied OS/2 tables report weight 400 for all four distinct faces. `BrandFontFamily` explicitly registers their actual intended weights 400/500/600/700. Material's complete type scale retains its native sizes, line heights and letter spacing; system text scaling and bold-text adjustment remain active. Unbounded stays limited to short accent phrases.

No font is fetched at runtime. White-label owners replacing these assets must supply their own licensed WOFF1 faces and update the four `font_*` paths in `branding/brand.json`.
