# Brief: the add-on catalogue

Rampart can add themes and message templates from a catalogue hosted on GitHub, so new
ones reach people without a new release. The starter catalogue is live at
https://github.com/thejdubb02/rampart-catalogue. Read its README.md, index.json, one file
in themes/ (a hybrid one such as themes/harbour-fog.json) and one in templates/ before
starting. Read CLAUDE.md first.

## Rules that are not negotiable

- **Data only.** An item is colours or text. Nothing downloaded is ever executed,
  evaluated, loaded as a class, or treated as a path on disk.
- **Fetch only when the person opens the catalogue.** Nothing at startup, nothing on a
  timer, no background check for updates.
- **Trust nothing until it is verified.** The index gives each item's `size` and `sha256`.
  Download the item, then reject it unless its byte length equals `size` and its SHA-256
  equals `sha256`. Only then parse it.
- HTTPS only, for the index and every item. Refuse an http:// catalogue address.
- Caps: index 256 KB, item 8 KB (and refuse any index entry whose `size` is over 8 KB
  before downloading it). Read at most cap + 1 bytes and fail if exceeded, rather than
  trusting Content-Length.
- Item `path` must be relative, start with `themes/` or `templates/`, end with `.json`,
  and contain no `..`, no backslash, no `//`, no scheme and no query. Resolve it against
  the index URL. Anything else is skipped, not fatal to the whole index.
- Index `version` must be 1. A higher version shows "This catalogue needs a newer
  Rampart." Unknown `kind` values are skipped silently (future kinds).
- No host names, tailnet addresses or credentials anywhere.

## 1. Theme JSON learns the light page

`ThemeJson` in CustomThemes.kt must round-trip the optional `page` object that hybrid
themes have (fields: background, surface, surfaceVariant, selection, text, muted, line,
accent, onAccent, all "#RRGGBB"). Decode: when `page` is present, `dark` must be true, and
the page becomes `Theme(key = "<key>-page", label = same, dark = false, art = null, ...)`.
Encode: write `page` only when the theme has one. A `description` field is allowed and
ignored. Existing files without `page` load exactly as before. This also fixes a custom
theme losing its page on save (see Settings.customThemes and the theme editor; check the
editor does not drop `page` when it copies a theme, and keep that one-line if it does).

## 2. Catalogue.kt (new)

- `const val DEFAULT_CATALOGUE =
  "https://raw.githubusercontent.com/thejdubb02/rampart-catalogue/main/index.json"`.
- A setting for the address, blank meaning the default (follow how other string
  settings are stored in Settings.kt).
- `data class CatalogueItem(kind, id, name, description, author, path, size, sha256, preview)`
  where preview keeps what the list needs: for a theme dark, background, surface, text,
  accent and optional page background/text/accent; for a template subject and firstLine.
- Pure, testable functions with no network: `parseIndex(text): List<CatalogueItem>`
  (throws on bad version or bad JSON, skips bad entries), `safeItemUrl(index: URI, path): URI?`,
  `verified(bytes, item): Boolean`.
- Network: reuse the HTTP client pattern already in the codebase (look at RemoteImages.kt
  or Diagnostics.kt; same User-Agent style, 15 s timeouts, redirects allowed but never
  from https to http). Runs on Dispatchers.IO.
- Install:
  - theme: `ThemeJson.decode`, then add to Settings custom themes, replacing any custom
    theme with the same key. Do not switch to it automatically.
  - template: parse {name, subject, body}; placeholders are left as text. Add to
    `Templates`, replacing one with the same name.
  - "Added" state: a theme is added if a custom theme has its key; a template if one has
    its name.

## 3. The screen

A "Catalogue" section in Settings (find how sections are listed in SettingsPane.kt and
RookSettingsLive.kt, and add it to settings search). Also a "Browse catalogue" button next
to the existing "Import theme" button (SettingsPane.kt around line 582) that opens it.

- On open: one line saying "Opening this contacts <host of the catalogue address>.",
  then load. Show a spinner while loading, and a plain error with Retry on failure.
- Two groups, Themes and Templates. Each row: name, description, author, and an Add
  button that becomes "Added" (disabled) once installed. Theme rows show a small preview
  built from `preview` (background with the accent and text colours; a second swatch for
  the page on a hybrid). Template rows show the subject and first line.
- A failed install shows the reason inline on that row ("The download did not match the
  catalogue, so it was not added.") and changes nothing.
- Below the list, the catalogue address in a text field with a "Use the default" link.
  Changing it reloads.

## Tests (src/test/kotlin/org/rampart/CatalogueTest.kt)

- parseIndex reads the real shape (inline a small sample with one theme and one
  template); version 2 is rejected; an unknown kind and an entry with a bad path are
  skipped while the rest load.
- safeItemUrl: accepts `themes/x.json`; rejects `../x.json`, `/themes/x.json`,
  `https://evil/x.json`, `themes/../../x.json`, `themes/x.json?a=1`, `themes\\x.json`,
  `templates/x.txt`.
- verified: true for matching bytes; false for one changed byte; false for right hash
  wrong size.
- ThemeJson: a hybrid with `page` round-trips (decode, encode, decode, equal colours and
  page present); a light theme with `page` is rejected; a file without `page` still loads
  with page == null.
- Installing a theme twice leaves one entry; installing a template with an existing name
  replaces it (use a temp path for Templates, as existing tests do).

## Docs

- docs/catalogue.md: what the catalogue is, the data-only rule and why (an add-on that ran
  code could read all your mail and keys), when Rampart contacts the address, how items
  are verified, and how to point Rampart at your own catalogue. Plain and short.
- One line in README.md where features are listed, if there is such a list.

Match surrounding style: KDoc that explains why, plain English. No em dashes, en dashes
or the ellipsis character anywhere. Do not run gradle. Do not commit. Only edit files. At
the end, list the files you changed and anything you left out.
