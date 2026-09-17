## Windows

Paste this into PowerShell. It trusts our signing certificate once, installs Rampart, and
starts it.

```powershell
irm https://github.com/thejdubb02/rampart/releases/latest/download/install.ps1 | iex
```

One admin prompt, the first time on each machine. Every update after that is silent:
Windows fetches them in the background, and Rampart offers a restart when one is ready.

**Do not double-click the .msix file.** Windows will not install a package whose signer it
does not know yet, so App Installer opens, cannot finish, and its Cancel button does
nothing. The line above is the way in.

## Linux

```
sudo apt install ./justin-willhite-rampart_*_amd64.deb
```

## Already running it

Nothing to do.
