# The add-on catalogue

Rampart can add themes and message templates from a catalogue, so a new one can reach
people without a new release. The starter catalogue is
https://github.com/thejdubb02/rampart-catalogue. The index Rampart fetches is
https://raw.githubusercontent.com/thejdubb02/rampart-catalogue/main/index.json.

## An item is data

A catalogue item is colours or text. Rampart never runs it, never evaluates it, never
loads it as a class, and never treats it as a path on disk. An add-on that ran code
could read all your mail and your keys. The catalogue is not allowed to be that.

## When Rampart contacts it

Only when you open Catalogue in Settings, or press Browse catalogue on the Themes page.
Nothing is fetched at startup, nothing runs on a timer, and Rampart does not look for
updates in the background. The page names the host it is about to contact, then loads.

## How an item is checked

The index gives each item a size and a SHA-256. Rampart downloads the file, then refuses
it unless the number of bytes equals that size and the SHA-256 equals that hash. Only
then is it read as a theme or a template. A file that does not match is not added, and
nothing already installed is changed. The row says: "The download did not match the
catalogue, so it was not added."

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

An item's path in the index is relative, starts with `themes/` or `templates/`, and ends
with `.json`. How to add a file is in the catalogue repository's own README.

The checks live in `src/main/kotlin/org/rampart/Catalogue.kt`. `CatalogueTest.kt` covers
them.
