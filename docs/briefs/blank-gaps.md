# Brief: Collapse large blank gaps in HTML messages (RAM-138)

Rampart is a Kotlin Compose Desktop mail client. Read CLAUDE.md first and match the
surrounding code: comment density, naming, the explanatory comments that say why.

## The problem

Some HTML mail draws a screen-tall empty gap between two paragraphs (seen in a plain
marketing pitch: text, a link, then ~700px of nothing, then more text). Typical causes:
runs of `<p>&nbsp;</p>`, `<div><br></div>`, long `<br>` runs, empty spacer elements or
table cells with a large fixed `height`, `min-height`, `padding` or `margin`.

HTML messages are drawn in a JavaFX WebView: WebBody.kt (load listener ~260-310 runs
`executeScript` after SUCCEEDED, then measures content height, see CONTENT_HEIGHT ~386),
document assembled in EmailDocument.kt, prepared in Pictures.kt `prepareReading`.

## What to build

Two passes, both conservative: a designed newsletter must look the same.

1. Before drawing (string level, in the existing cleaning/preparing step; find where the
   HTML is cleaned and add one small pure function there, eg `collapseBlankRuns(html)`):
   - A run of 3 or more `<br>` (any spacing/attributes/self-closing) becomes 2.
   - A run of 2 or more consecutive empty `<p>` or `<div>` elements (content only
     whitespace, `&nbsp;`, `&#160;`, `<br>`) becomes one.
   - Regex is acceptable; do not add a parser dependency. Never touch anything inside
     `<pre>`, `<textarea>` or `<style>`.
2. After layout (JS, run once in WebBody after the document loads and before the height is
   measured): walk `document.body.querySelectorAll('*')`; for an element whose
   `getBoundingClientRect().height` is over 160px, that has no visible text
   (`innerText.trim()` empty), contains no `img, svg, video, canvas, iframe, object, embed,
   input, button, hr`, and whose computed `background-image` is `none` on itself and every
   descendant, set `style.height = 'auto'`, `minHeight = '0'`, `paddingTop/Bottom = '0'`,
   and if still over 160px set `maxHeight = '24px'` with `overflow = 'hidden'`. Skip
   `html`, `body`, and elements whose computed `display` is `none`. Process outermost
   matches only (skip descendants of an element already capped). Keep the script a `const`
   string next to the other scripts in WebBody.kt, in the same style. The content height
   must be measured after it runs.
3. Tests: unit tests for `collapseBlankRuns` covering each rule and the `<pre>` exemption,
   plus one case asserting ordinary paragraphs with text and two `<br>`s are unchanged.
   Each test must fail if its rule is removed.

## Rules

- Do not run gradle. Do not commit. Touch only the files named above plus the test.
- No new dependencies. No em dashes or en dashes in comments or strings.
- List every file changed with one line on each.
