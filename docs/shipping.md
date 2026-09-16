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
blocks that changed. Nothing in our code polls for updates, because the operating system
already does.

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
