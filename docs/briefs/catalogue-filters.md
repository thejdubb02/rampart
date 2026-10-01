# Brief: filter recipes in the catalogue (RAM-135, kind 3)

The catalogue (src/main/kotlin/org/rampart/Catalogue.kt, docs/briefs/catalogue.md,
docs/catalogue.md) adds themes and templates from a hosted index without a new release.
Add a third kind, `filter`: a ready-made mail rule the server runs (Sieve).

## The file

A filter item is one JSON object in exactly the shape a rule already has in the metadata
block of our Sieve scripts: read it with the existing `ruleOf` in Sieve.kt and write it with
`addFilter` in Filters.kt. Do not invent a second rule format. Give it a fresh `id` on
install (a copied id would collide when the same recipe is added twice or to two accounts),
and force `global = false`.

Reject (the row says why, nothing changes):
- anything `ruleOf` returns null for, or that comes back with `understood == false`;
- any `Forward` action. A shared recipe that forwards mail to an address its author chose
  is the one way a data-only add-on can still leak mail. Say so in the row: "This recipe
  forwards mail, so it was not added."
- `Delete` is allowed but must be shown plainly in the preview (below).

Path rule: `filters/<name>.json`, extending `safePath` the same way themes and templates are.
Index preview for a filter: `{"summary": "<one line>"}`; the row also shows the rule in plain
words built locally from the parsed rule (when X contains Y: file into Z, mark read...). Reuse
whatever already turns a Rule into words on the Filters page if there is one (look in
FiltersPage.kt and FilterTools.kt); only write a small describer if nothing exists.

## Installing

A filter goes onto one account's server, unlike a theme. The Add button asks which account
when there is more than one that can hold filters (`noFiltersBecause` in FilterTools.kt says
which cannot, and its sentence is shown for those). Before anything is written, a
confirmation in the row shows the rule in words, the account, and "Add filter" / "Cancel".
Folder names in `FileInto` that do not exist on that account: say "This account has no
folder named X" and do not add (look for the existing missing-folder handling in Filters).
Work happens off the window thread; results land on it via the same pattern the page already
uses for Add.

"Added" state for a filter: a rule with the same name already on that account. Since the
account is chosen at Add time, show Add always and say "Already on <account>" in the confirm
step instead of disabling the button.

## Screen

A third group, "Filters", under Themes and Templates, same row layout.

## Tests (CatalogueTest.kt)

- parseIndex accepts a `filter` item with a summary preview; `filters/x.json` is a safe path,
  `filters/../x.json` is not.
- A recipe JSON that files into a folder and marks read parses and installs into a Script
  (pure function: whatever builds the new Script, e.g. `scriptWithNewRule`) with a new id and
  global false.
- A recipe with Forward is refused with the sentence above.
- A recipe that does not parse, or parses as not understood, is refused.
- Installing the same recipe twice gives two different ids (or is refused as already there,
  whichever the code does; test what it does).

## Docs

docs/catalogue.md: add filters, the no-forwarding rule and why, and that a filter goes onto one
account's server. Match surrounding style: KDoc that explains why, plain English. No em dashes,
en dashes or the ellipsis character anywhere. Do not run gradle. Do not commit. Only edit files.
At the end, list the files you changed and anything you left out.
