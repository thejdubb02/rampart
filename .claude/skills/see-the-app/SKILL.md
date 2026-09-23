---
name: see-the-app
description: Run Rampart on a headless Linux box and take screenshots of it, to see a change working instead of guessing. Load before checking any UI, rendering, dark mode or message-display fix.
---

# Seeing the app on a Linux box with no screen

Unit tests do not show what a message looks like. Every rendering bug fixed in September
2026 (every message after the first falling back to plain text, dark text on a dark page,
boxes in Outlook mail) was found by looking, not by a test.

## One-time setup

    apt-get install -y xvfb openbox xdotool imagemagick

`openbox` matters: without a window manager, keyboard focus never reaches the window and
typed text goes nowhere.

## Run it

    Xvfb :77 -screen 0 1600x1000x24 &
    DISPLAY=:77 openbox &
    DISPLAY=:77 ./gradlew runLinux -Drampart.config.dir=/tmp/rampart-profile -Dprism.order=sw

- `runLinux` adds the JavaFX engine that the ordinary `run` task leaves out.
- `-Drampart.config.dir` gives a throwaway profile, so a test never touches a real one.
- `-Dprism.order=sw` is required under Xvfb. Without it JavaFX picks a GL pipeline that has
  no GL context, and every message body is blank.

If something else is compiling in the same tree, snapshot the classpath and run `java`
directly rather than competing for the Gradle daemon; two compilers on one box have run the
Kotlin daemon out of memory.

## Drive it

    W=$(xdotool search --name Rampart | tail -1)
    xdotool windowactivate $W; xdotool windowsize $W 1600 1000 windowmove $W 0 0
    xdotool mousemove X Y click 1; xdotool type --delay 30 "text"; xdotool key Return
    DISPLAY=:77 import -window root shot.png

Pipe a password into `xdotool type --file -` from the credential store rather than putting
it on a command line. There is no credential store on a bare box, so the app asks for the
password on every start.

Screenshot a message at about 1.5 seconds and again at about 6: a difference between the
two is a message that painted twice or jumped. Tile several with `convert +append` and
`-append` to look at them in one image.

## Use a mailbox made for it

Never a real one: opening marks mail read, and replying, archiving and discarding change it.
Make a test account on your own server and copy a mix of real mail into it (designed
newsletters, Outlook mail, inline pictures, attachments, long threads, a few drafts).
