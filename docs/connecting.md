# Setting Rampart up for someone

Rampart reads a small file listing which servers to offer on the sign-in screen. Fill it
in once and signing in is a click and a password, with no host names to remember and no
settings to explain.

**The file never holds a password, and cannot be made to.** Rampart reads exactly three
fields per account and ignores everything else, so a `password` key put there by a person
or by an assistant is read as nothing, and is gone the next time Rampart writes the file.
The password is typed in, held in memory for that session, and never written to disk.

## Where it goes

| | |
|---|---|
| Windows | `%APPDATA%\Rampart\accounts.json` |
| Linux | `~/.config/rampart/accounts.json` |
| macOS | `~/.config/rampart/accounts.json` |

## The file

```json
{
  "version": 1,
  "accounts": [
    {
      "name": "Work",
      "server": "mail.example.org",
      "email": "you@example.org"
    }
  ]
}
```

`server` accepts whatever form is to hand: a bare host name, a base URL, a `/jmap/` URL or
the full `/.well-known/jmap` address. Rampart works out the rest.

`name` is only a label on the sign-in screen. Leave it out and the email address is used.

## Handing this to an assistant

Copy the block below, replace the two lines at the top, and give it to whatever assistant
you use. It is deliberately dull: no account is created, nothing is sent anywhere, and
nothing it produces can contain a secret.

> I use Rampart, a desktop mail client for JMAP servers. Write me the accounts file for it.
>
> My mail server is: **<host name, or the web address you use for webmail>**
> My email address is: **<your address>**
>
> The file is JSON, of this shape, and must contain only these fields. Do not add a
> password field: Rampart ignores it and it would put my password in a plain text file
> for nothing.
>
> ```json
> {
>   "version": 1,
>   "accounts": [
>     { "name": "Work", "server": "mail.example.org", "email": "you@example.org" }
>   ]
> }
> ```
>
> Save it to `%APPDATA%\Rampart\accounts.json` on Windows, or
> `~/.config/rampart/accounts.json` on Linux and macOS. Create the folder if it is not
> there. Then tell me to open Rampart and type my password.

## What to use as the password

An app password, not the password you log into the server's admin console with. On
Stalwart that is *Settings > Passwords > App Passwords* in the account's own settings.
It can be revoked on its own if the machine is ever lost, and it cannot administer
anything.
