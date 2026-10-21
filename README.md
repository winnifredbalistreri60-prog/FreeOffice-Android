> **Status: Work in progress.** This is an Android office/document viewer candidate. Build and functional behavior require device testing; the project is not presented as a finished release.

# FreeOffice 0.2.6 — designated tester candidate

The reference is the reviewer's **0.2.5-autofit-alpha2**, not the earlier 0.2.3 release. Its source was reconstructed with the exact 0.2.4 → 0.2.5 reviewer patches; the reference APK SHA-256 is `00f8eda3261040601741de029c75146eb3722ffb94dd6c5fbdcdbda9840a51d7`. This candidate integrates the separately completed DOCX and PDF source patches, plus the retained spreadsheet/menu/CSV work. The historical 0.2.3 source, APK and source archive must remain untouched.

## Identity and safety

- Application ID: `ru.freeoffice.app.candidate026` — intentionally separate from the main app and any `.autofit` package.
- Version name: `0.2.6-structured-alpha1`; version code: `10`.
- Android API 26 minimum; target/compile API 35. ABIs: `arm64-v8a`, `x86_64`.
- Preserved 0.2.3 signing certificate SHA-256: `470436ea7e1f6ec97c3b84e2457c7e358a851f17aaf89986d4e375d0782c892c`.
- This is NOT the reviewer's 0.2.5 certificate (`3cced3291e9f19fcbdc398b04b2961133e500c4c146797cb867f90d2043af27b`) and is not an in-place update of that APK. The owner explicitly requested retaining the 0.2.3 key and isolated candidate ID.

Keys are not included. Do not generate a replacement key, install this APK over a historical app, uninstall an existing app or clear its data. Build/lint is not functional acceptance. Only the designated **DroidDeck Tester** may run app/functional/JVM tests or install/launch a test build under separate authorization. This builder did none of those actions and used no owner workbook or private user documents.

## Serial build

Required tools: JDK 17, Gradle Wrapper 8.10.2, Android Gradle Plugin 8.8.2, Android SDK 35 / build-tools 35.0.0, NDK 27.3.13750724, Rust 1.97.1 and Android targets `aarch64-linux-android` and `x86_64-linux-android`.

The source archive contains WordCraft/GridCraft/PdfCraft under `vendor/`, the local XLSX recovery parser under `native/vendor/gridcraft-xlsx/`, and relative Cargo paths. Cargo registry dependencies still require the normal registry/cache; the archive does not promise a completely offline build.

```bash
export JAVA_HOME=/absolute/path/to/jdk17
export ANDROID_SDK_ROOT=/absolute/path/to/Android/Sdk
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.3.13750724"
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
export FREEOFFICE_DEBUG_KEYSTORE=/private/path/to/preserved/debug.keystore
# Run only after other branch builds finish. Native ABIs are built sequentially.
CARGO_BUILD_JOBS=2 bash native/build-android.sh
./gradlew :app:assembleDebug :app:lintDebug --no-daemon --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Signing validation fails without the explicit key variable rather than silently creating a different signing identity.

```bash
"$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" dump badging app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Regenerate Rust license assets when dependencies change, before assembling:

```bash
cargo metadata --manifest-path native/Cargo.toml --format-version 1 --locked \
  --filter-platform aarch64-linux-android > /tmp/freeoffice-cargo-metadata.json
python3 tools/collect_rust_licenses.py /tmp/freeoffice-cargo-metadata.json
```

Native and Android generated output directories may be placed on sufficiently large temporary storage to avoid filling the root disk. Do not remove another worker's output/cache. Rebuilding on another machine is not claimed to be byte-identical; compare the actual APK/hash/certificate in the final handoff.

## Integrated behavior and limits

**Spreadsheets/CSV/TXT:** reviewer 0.2.5 spreadsheet auto-fit/overflow/menu behavior is retained. CSV/TSV remain strings, including leading zeros and formula-looking text. Ragged records are not padded in the underlying data model; warnings remain visible. Exactly one BOM is removed on decode, preserving a second literal U+FEFF. One-character custom output delimiters remain unchanged, invisible delimiters receive readable UI labels, and literal tab metadata selects tab instead of comma. TXT search uses offsets in the original Unicode string and explicit wrap; empty searches clear highlighting. These preservation claims are implementation intent/static evidence, not completed round-trip tests.

**DOCX:** a separate native `docx_view` module projects resolved runs, paragraphs, sections, lists and tables into the reviewed `DocxViewer`. The Activity explicitly uses this schema, not the earlier candidate projection. A compact menu exposes page/text mode, ruler, fit/explicit zoom and warned simplified editing. A simple-text fallback is shown if the UI model cannot be rendered. Search is visibly disabled rather than a no-op. Pagination is approximate; exact line spacing, complex/nested table appearance, images, header/footer stories, footnotes and Word-equivalent layout are not complete. UI limits and final known issues are in the handoff.

**PDF:** the completed continuous renderer uses a serial worker, bounded viewport raster cache, pinch/fit/zoom, navigation, and a compact menu with an optional search panel. Actual text search, next/previous matches and bounding-box highlights use the platform API on **Android 15 / API 35+ only**. On API 26–34 search is explicitly unavailable; there is no OCR. Scanned PDFs without usable text and password-protected PDFs are not made searchable. The earlier native `pdf_text`/`pdf_search` bridge is retained for source/API continuity but is not wired as the new UI's older-Android fallback. Crop/rotation highlight accuracy, gestures, rotation, race recovery, memory and performance require device testing.

**ODT:** retained text-view fallback; no claim of a structured DOCX-equivalent page renderer. Existing simplified DOCX/ODT and spreadsheet copy editors retain their pre-existing limitations. Source files are not automatically overwritten; URI equality cannot detect every alias exposed by third-party file providers.

## Fixtures, sources and notices

`tools/generate_viewer_fixtures.py` creates synthetic DOCX/PDF/TXT/CSV/TSV inputs only; it runs no tests. Its optional Python dependencies are python-docx, reportlab and Pillow, not Android app dependencies. `package_source.py` packages the candidate and licensed upstream sources; set `FREEOFFICE_SOURCE_STAGE_DIR` to roomy temporary storage if needed.

License assets include the upstream notices and 149 additional Cargo-registry notice files, plus the resolved dependency list. The license screen reads both flat notices and nested directories. The final APK must contain those notices; the older frozen intermediate APK omitted them and is superseded.

See the final `HANDOFF.md` accompanying the APK for artifact hashes, build/lint result, unresolved items and the designated tester checklist. Historical `FORMATS.md`, `BUILD-STATUS.md` and backlog files are not current acceptance evidence.
