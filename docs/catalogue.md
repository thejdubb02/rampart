# The add-on catalogue

Rampart can add themes, message templates, and filters from a catalogue, so a new one
can reach people without a new release. The starter catalogue is
https://github.com/thejdubb02/rampart-catalogue. The index Rampart fetches is
https://raw.githubusercontent.com/thejdubb02/rampart-catalogue/main/index.json.

## An item is data

A catalogue item is colours, text, or a mail rule in the same form Rampart already
stores. Rampart never runs it, never evaluates it, never loads it as a class, and
never treats it as a path on disk. An add-on that ran code could read all your mail
and your keys. The catalogue is not allowed to be that.

A filter is still data. Rampart writes it onto one account's server, and that server
runs it at delivery, with Rampart closed. Rampart does not run the rule itself.

## Filters

A filter goes onto one account, which you choose when you add it. A theme stays on
this computer. A filter does not: it is saved with that account's other rules, on
that account's server.

The row shows a one-line summary from the catalogue. Before anything is written, the
same row shows the rule in plain words built here (if the sender contains a word:
file into a folder, mark read, delete), the account, and Add filter or Cancel.

A recipe that forwards mail is refused, and the row says: "This recipe forwards mail,
so it was not added." A shared recipe that forwarded to an address its author chose
could send your mail there. That is the one way a data-only add-on can still leak
mail, so it is not added.

A recipe that deletes mail is allowed. The words in the row say delete before you
add it.

If that account already has a rule of the same name, the confirmation says so. The
Add button stays available, because the account is chosen when you add it.

A folder the recipe files into has to exist on that account. If it does not, the row
says "This account has no folder named" and the name, and the rule is not added.

## When Rampart contacts it

Only when you open Catalogue in Settings, or press Browse catalogue on the Themes page.
Nothing is fetched at startup, nothing runs on a timer, and Rampart does not look for
updates in the background. The page names the host it is about to contact, then loads.

## How an item is checked

The index gives each item a size and a SHA-256. Rampart downloads the file, then refuses
it unless the number of bytes equals that size and the SHA-256 equals that hash. Only
then is it read as a theme, a template, or a filter. A file that does not match is not
added, and nothing already installed is changed. The row says: "The download did not
match the catalogue, so it was not added."

The address has to be https, for the index and for every item. The index is capped at
256 KB and an item at 8 KB. An index entry that already claims to be larger than 8 KB is
not downloaded. Rampart counts the bytes as they arrive and stops if the file is one
byte over the cap. It does not trust the size the server announces.

The index version has to be 1. A newer catalogue says "This catalogue needs a newer
Rampart."

## Your own catalogue

The Catalogue page has an address at the bottom. Put the https address of your own index
there. Leave it blank, or press Use the default, to go back to the catalogue Rampart
ships with. Changing the address loads that catalogue.

An item's path in the index is relative, starts with `themes/`, `templates/`, or
`filters/`, and ends with `.json`. How to add a file is in the catalogue repository's
own README.

The checks live in `src/main/kotlin/org/rampart/Catalogue.kt`. `CatalogueTest.kt` covers
them.
