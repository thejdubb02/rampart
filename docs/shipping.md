# Shipping Rampart

One install, then it keeps itself current. A push to `main` is the release.

## What happens on a push

GitHub Actions runs on Linux and does all of it, for every platform:

1. `./gradlew test jar` runs the tests and renders the screenshots.
2. Conveyor cross-builds the Windows and Linux packages, signs them, and replaces
   the GitHub release that installed copies read.

There is no Windows machine anywhere in the chain. Conveyor builds Windows packages
from Linux, which is the reason it is here rather than jpackage on a Windows runner.

## The version number is the commit count

`build.gradle.kts` sets `version = "0.1.$(git rev-list --count HEAD)"`. Every commit is
a higher version than the one installed, so there is no number to remember to bump and
no way to ship a build the update check ignores. CI checks out with `fetch-depth: 0`
for the same reason: a shallow clone counts one commit and would pin the version.

## How the update actually reaches the machine

Windows installs Rampart as an MSIX registered against `rampart.appinstaller`, a small
XML file listing the current version and where to get it. Windows re-reads it on its own
schedule and in the background, whether or not the app is running, and downloads only the
blocks that changed.

On top of that, Rampart asks GitHub every half hour whether there is a newer release, and
fetches it as soon as there is one. It is a mail client: it stays open for days, so the
moment somebody finally agrees to restart is the worst possible moment to begin a download.
`Updates.stage()` runs `Add-AppxPackage -AppInstallerFile ... -DeferRegistrationWhenPackagesAreInUse`,
which puts the new version on the machine and tells Windows to apply it when the app is
next closed. Nothing in that path can close the app, which is what makes it safe to run
behind somebody reading their mail.

So there are three ways the same update lands, and none of them needs anybody to act:
Windows' own background check, closing Rampart once the package is staged, and the next
launch, because the manifest carries `OnLaunch` with `HoursBetweenUpdateChecks="0"`.
Pressing the button only makes it sooner.

The defer flag is not on every Windows this might run on, so a second attempt without it
follows, and that one is allowed to refuse while the app is in use. What is lost then is
the head start, not the update.

What the person sees: a card in the corner, once per version, saying it is downloaded and
goes in when they next close Rampart. "Later" means the card does not come back for that
version; the bottom of the sidebar carries one quiet line instead, and clicking it brings
the card back.

Linux gets a `.deb` from an apt repository in the same release, so `apt upgrade` carries it.

## The first install needs one elevated step, and only the first

The package is signed with our own key, not a certificate from a public authority. Windows
will not install an MSIX whose signer it does not trust, so the generated `install.ps1`
downloads the certificate, elevates once to add it, then installs the app. That is one UAC
prompt, once per machine. Every update after it is silent.

A real code signing certificate removes that step and costs a few hundred dollars a year.
Worth it when Rampart is something other people install, pointless while it is us.

## The signing key

Every package identity derives from one root key, held in two places and nowhere else:

- `/etc/hydraulic/conveyor/defaults.conf` on the devbox, mode 600.
- the password manager, as *Rampart - Conveyor signing root key*.
- GitHub Actions reads it from the `SIGNING_KEY` secret.

Losing it means a new app identity, which means every installed copy stops updating and
has to be reinstalled by hand. It is not in this repo and must never be.

## Building a package by hand

    ./gradlew jar && conveyor make site

`make site` writes the whole update site to `output/`. Build the jar first: Conveyor reads
the jar Gradle produced rather than building one itself, so a stale jar fails the build with
a version mismatch rather than silently packaging old code.
