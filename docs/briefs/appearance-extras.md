Repo: Rampart, a Kotlin Compose Desktop mail client (read CLAUDE.md first). Appearance settings: SettingsPane.kt (Theme section near line 479, Density near 539), Settings.kt (theme, dark, density, messageScale, order), Density.kt, Sorting.kt (Order enum; the list is sorted client-side over loaded rows).

Round out the Appearance page, modelled on another client the user likes. Only what is missing:

1. Theme mode: make sure there is a clear Light / Dark / System choice (System follows the OS and switches live). Settings.dark() is a nullable Boolean today; check whether null already means System and reuse it.
2. Font size for the whole interface: Small / Medium (default) / Large, scaling the app's typography (the MaterialTheme typography or a density fontScale at the root), separate from messageScale, which only scales message bodies. Stored and synced like the other appearance settings.
3. Density: add "Extra compact" below Compact in Density.kt, with tighter row padding than Compact everywhere Density is read (search usages of Density.COMPACT and match each with an EXTRA_COMPACT case). Existing saved values keep working.
4. Message list order: add a switch "Apply to all folders". Off means the chosen order applies to the Inbox only and every other folder stays newest first. Default on, which is today's behaviour.
5. Animations: a switch "Enable animations" (default on). When off, transitions are instant: provide the flag once (for example a CompositionLocal set at the root) and have the app's AnimatedVisibility, animate*AsState, Crossfade and similar use a zero-length spec when it is off. Cover the obvious ones (panels, dialogs, list changes); do not rewrite every file.

New settings go in Settings.kt with safe defaults and into the synced list in SettingsSyncMerge.kt. One test file for the pure parts (reading the new settings with unknown values falling back, the Inbox-only order rule). Match surrounding style: KDoc explaining why, plain English. No em dashes, en dashes or the ellipsis character. Small, focused. Do not run gradle. Do not commit. Only edit files.
