# English resource readiness

The application currently ships English. This checkpoint separates app-owned copy from user/server data so translations can be added without changing the API or workflow identities.

`app/src/main/res/values/mobile_ui_strings.xml` owns screen labels, accessibility descriptions, onboarding, notification text and known-status display labels. `core_strings.xml` owns local validation, notices and shared HTTP fallback/special-code copy. Quantity-sensitive messages use Android plurals. User names, product descriptions, server field errors, dates/amount values, API/action keys, provider identities and test tags are not translated as resource keys.

Maintain the XML, then regenerate the corresponding Kotlin fallback keys with `scripts/generate_mobile_copy.py`. `tokens/mobile-copy.json` lists the catalog/package/object/output pairs. For example:

```powershell
python scripts/generate_mobile_copy.py --catalog app/src/main/res/values/mobile_ui_strings.xml --package org.spaceworks.app.ui --object UiStrings --output app/src/main/java/org/spaceworks/app/ui/UiStrings.kt
python scripts/check_mobile_copy.py
```

`preBuild` verifies generated files against the catalogs and runs four generator safety tests. Formats use numbered arguments, allowing a translator to reorder them; plural forms retain compatible arguments. The generator supports Android quoted strings, punctuation, newlines and Kotlin interpolation escaping. Generated fallback files are reviewed and committed but never maintained separately.

Compose resolves via `rememberMobileText()`. Factories inject `MobileText.resources(applicationContext)` into state holders and gateways; the closure retains Resources rather than an Activity. Pure JVM tests default to English and can inject a synthetic resolver. Local validation carries a `MobileCopyException` key until presentation. `ApiFailure.localCopy` is set only when the networking layer chose an app-owned fallback or special code. A nonblank server detail or nested field dictionary retains `localCopy=null`, even when its text matches an English fallback exactly.

Keep routing and authorization independent of visible copy. Preview provider IDs and staff workflow steps remain stable internal values; localize their display labels only. Unknown server statuses remain readable values rather than being guessed into a known state. Reduced motion, large text, theme controls and semantic touch targets remain part of the device checks.

Payment, document, staff and account catalogs are added with their respective phases. Store translations and additional languages require their own review and device validation; this checkpoint does not claim them.

The A7 account catalog is `values/account_strings.xml`. Its stable `AccountCopy` identifiers map
through `Resources.accountText`; production account gateways/holders receive that resolver and
`MobileText.resources` for tagged shared HTTP fallbacks. Resource IDs cover screen labels,
accessibility, plurals, management notices and the Debug deletion preview. Dynamic server details,
provider IDs, opaque session IDs and workflow enum values remain data, not translated keys.
