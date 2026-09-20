#!/usr/bin/env python3
"""Make Windows look for a new Rampart every time Rampart starts.

Conveyor's `updates = background` writes an `.appinstaller` whose UpdateSettings say
nothing but `<AutomaticBackgroundTask />`. That task runs on Windows' own schedule, at
most once every eight hours and only when the machine is idle enough to suit it, so a
build published in the morning can still not be installed that evening. Rampart's own
check offers a restart within half an hour of noticing, but that is a button somebody has
to press, and the complaint that produced this file was that updates were not arriving.

`OnLaunch` with `HoursBetweenUpdateChecks="0"` makes Windows ask on every start.

`UpdateBlocksActivation` stays false and `ShowPrompt` stays off deliberately. The package
is a hundred and thirty megabytes, and blocking the window on that download would put the
wait in front of somebody's mail every time we publish, which is several times a day. Left
in the background it is in place the next time Rampart opens, so closing and reopening is
always enough to be current.

Run against the file Conveyor produced, in place:

    python3 deploy/onlaunch.py rampart.appinstaller
"""
import pathlib
import re
import sys

ON_LAUNCH = (
    '<OnLaunch HoursBetweenUpdateChecks="0" ShowPrompt="false" '
    'UpdateBlocksActivation="false" />'
)


def add(manifest: str) -> str:
    """The manifest with an OnLaunch in it, or an explanation of why there is not one."""
    if "OnLaunch" in manifest:
        raise SystemExit("the manifest already has an OnLaunch: check what Conveyor changed")
    out, found = re.subn(r"<UpdateSettings>", "<UpdateSettings>\n        " + ON_LAUNCH, manifest, count=1)
    if found != 1:
        raise SystemExit("no UpdateSettings element in the manifest Conveyor produced")
    return out


def main() -> None:
    path = pathlib.Path(sys.argv[1])
    path.write_text(add(path.read_text()))
    print("OnLaunch added to", path)


if __name__ == "__main__":
    main()
