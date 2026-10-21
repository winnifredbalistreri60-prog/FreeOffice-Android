# Text / CSV viewer handoff

## Scope and integration

Only `app/src/main/java/ru/freeoffice/app/TextViewer.java` (new), `SheetViewer.java`, `PlainFormats.java`, and `PlainFileUi.java` were changed. `MainActivity` was not touched.

`TextViewer` API:

```java
new TextViewer(Activity activity, LinearLayout host, String text, String name,
               JSONObject settings, Runnable back, Runnable edit, Runnable encoding)
    .start();
viewer.close();
```

Call `start()` on the UI thread. It clears/reuses the supplied host, renders read-only text with a dark 48dp header (back, middle-ellipsized title, search, overflow), and a selectable/wrapping text body. Overflow provides wrap toggle, font size down/up/reset, encoding callback, and edit callback (only when non-null). Search highlights and scrolls to a match; “Далее” wraps around. `settings.warning` is shown in the status footer. `close()` marks the instance closed; no worker/session is owned by this viewer.

`SheetViewer` additions:

```java
viewer.setImportOptions(Runnable callback); // set before start(); non-null exposes menu item
viewer.setWarning(String warning);          // persists warning in status across page requests
```

`setWarning` keeps the supplied warning visible during page loading, on rendered pages, and on request errors. Call both before `start()` where applicable. `setImportOptions` does not replace existing callbacks or methods.

## CSV preservation fix

An import may recognize an Excel-style `sep=<character>` directive and report its delimiter in `delimiter` / `sepDirective`. Previously export parameter UI could silently map an unrecognized single-character delimiter to comma, and output delimiter resolution rejected arbitrary characters. The export UI now retains an arbitrary single-character source delimiter as an explicit choice, and `PlainFormats` permits valid one-character separators when writing. Double quote, CR, LF, and NUL are rejected; quoted-field escaping remains intact. The directive itself is metadata and is not automatically emitted on export; the chosen delimiter is used for field separation.

CSV/TSV remains string-only import/export. No formatting or column-width metadata is used to mutate cell data. Existing parser behavior preserves ragged row lengths and reports them as warnings; missing cells are only displayed as empty by the viewer, not inserted into the imported arrays.

## Limitations

- `MainActivity` integration remains for the parent: route plain `.txt` content to `TextViewer`; set `SheetViewer.setImportOptions(...)` and `.setWarning(...)` before `start()`; provide callback behavior and lifecycle/back handling.
- Viewer search is plain case-insensitive substring search, not regex; it highlights the current hit only.
- Encoding callback is delegated to the owner; this view does not reopen/re-decode the file itself.
- Text is rendered in a single `TextView`; extremely large text is subject to platform memory/layout constraints and the import limits in `PlainFormats`.
- Delimiter option in standard import UI remains auto/comma/semicolon/tab. Arbitrary directive delimiters are picked up in auto mode; explicit import selection remains limited to those options.

## Validation

Only compile/static validation is permitted for this task; no functional tests, JVM tests, adb, or installation were run.
