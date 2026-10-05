# Attachment scanning

Added 2026-10-05 (RAM-116, the Windows half). Not yet checked on a real Windows machine.

On Windows, every attachment Rampart writes to disk is handed to Windows Defender before it
is opened, saved or dragged out. `Defender.check` runs `MpCmdRun.exe -Scan -ScanType 3 -File
<path> -DisableRemediation` and reads the exit code:

- **2, a threat found:** the file is deleted and the person is told, in one sentence, that
  Defender found a threat and the file was not opened or saved.
- **Anything else** (clean, scanner missing, scanner failed, 60 second timeout): the file is
  left alone. Real-time protection still runs underneath, so a broken scan must never stop a
  clean file.

Defender's real-time protection already watches every file written to disk. What this adds
is an answer Rampart can show: without it a blocked file fails to open, or vanishes from
Downloads, and nobody is told why. It needs nothing installed and works with any mail server.
On Linux there is no scanner and nothing changes.

`MpCmdRun.exe` is looked for in the newest `%ProgramData%\Microsoft\Windows Defender\Platform\*`
folder first, because Defender updates itself there, then in `%ProgramFiles%\Windows Defender`.

## Where it runs

All through one choke point, `Session.fetchAttachment`, which covers Save, drag out, attached
messages and `winmail.dat`, plus the three paths that write bytes they already hold: saving a
part of an attached message (and dragging it), saving a decrypted file, and opening a PDF in
the system viewer.

## Known gaps

- A PDF blocked on its way to the system viewer is deleted silently; that path has no place to
  show the message yet.
- Checking it on Windows: save the EICAR test file as an attachment and confirm the message
  appears and the file is gone from Downloads.
- The server half of RAM-116 (ClamAV in front of the mailbox) is not built. It waits on where
  it runs.
