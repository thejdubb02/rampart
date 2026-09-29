# Setting Rampart up for someone

**Most people do not need this file.** Type your email address and your password and
Rampart asks your domain where its mail server is. This is for the domains that publish
nothing, and for setting somebody else's machine up before you hand it over.

It lists which servers to offer on the sign-in screen. Fill it in once and signing in is a
click and a password, with no host names to remember and no settings to explain.

**The file never holds a password, and cannot be made to.** Rampart reads a fixed set of
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

Two fields are optional and are normally written by Rampart rather than by you, after a
sign-in that worked:

- `protocol`, either `"jmap"` or `"imap"`. Left out, it means JMAP. Setting it saves
  Rampart trying JMAP first and waiting for it to time out on a server that only speaks
  IMAP.
- `sendServer`, where mail is sent from, for IMAP accounts whose submission server is not
  the same host as the one they read from. A port that is not the usual one goes in the
  host, as `mail.example.org:465`.

## Handing this to an assistant

Copy the block below, replace the two lines at the top, and give it to whatever assistant
you use. It is deliberately dull: no account is created, nothing is sent anywhere, and
nothing it produces can contain a secret.

> I use Rampart, a desktop mail client. Write me the accounts file for it.
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
> If you know my server speaks IMAP rather than JMAP, add `"protocol": "imap"` to the
> account, and `"sendServer"` if mail is sent through a different host. Leave both out if
> you are not sure.
>
> Save it to `%APPDATA%\Rampart\accounts.json` on Windows, or
> `~/.config/rampart/accounts.json` on Linux and macOS. Create the folder if it is not
> there. Then tell me to open Rampart and type my password.

## What to use as the password

An app password, not the password you log into the server's admin console with. On
Stalwart that is *Settings > Passwords > App Passwords* in the account's own settings.
It can be revoked on its own if the machine is ever lost, and it cannot administer
anything.

## Gmail and Microsoft: signing in through the browser

Gmail, Google Workspace, Outlook.com and Microsoft 365 no longer take a password over IMAP
and SMTP. For those, the sign-in screen offers **Sign in with Google** or **Sign in with
Microsoft** instead. Rampart recognises gmail.com, googlemail.com, outlook.com, hotmail.com,
live.com and msn.com from the address, and a custom domain from where its MX records point.
Any other address can still pick either one by hand under *Signing in with Google or
Microsoft?*.

What happens is the standard flow for a desktop application (RFC 8252):

1. Rampart opens a listener on `127.0.0.1`, on a port the operating system picks, for this
   one sign-in and no longer than five minutes.
2. Your own browser opens the provider's sign-in page. Your password goes to Google or
   Microsoft on their page, never to Rampart. There is no web view inside Rampart.
3. The provider sends the browser back to the listener with a one-time code. Rampart checks
   it is the answer to the request it made (the `state`), trades the code for tokens using a
   PKCE verifier that never left the machine, and closes the listener.
4. The access token signs in to IMAP and SMTP with XOAUTH2. It lasts about an hour, and
   Rampart gets the next one with the refresh token a few minutes before it runs out.

Both tokens are kept in the operating system's credential store (DPAPI on Windows, the
secret service on Linux), under an entry of their own, and never in a file. accounts.json
records only which provider an account signs in through, as `"oauth": "google"` or
`"oauth": "microsoft"`. On a machine with no credential store the tokens are held in memory
for the session, the same as a password nobody chose to remember, and the browser step is
asked for again next time.

If the provider stops accepting the sign-in (you changed your password, revoked Rampart's
access, or an administrator did), the account shows **Sign in again** with a button that
runs the browser step again. A network failure while refreshing is shown as a network
failure and fixes itself when the network is back.

### The client IDs, and why they are not secret

To sign anybody in, Rampart has to be registered as an application with Google and with
Microsoft, which gives it a client ID. The IDs this build uses are in
`src/main/resources/oauth-clients.json`. **They are not secrets, and shipping them in the
build is correct, not a leak.** A program running on your computer cannot keep a secret from
you, which is exactly why the flow above uses PKCE: the protection is a verifier made fresh
for each sign-in, not a value baked into the build.

Google also issues a "client secret" to Desktop applications and asks for it when the code is
traded for tokens. Google's own documentation for installed applications says this value is
not treated as confidential, so it lives in the same file for the same reason. Please do not
"fix" either by moving them to an environment variable or a server: that protects nothing and
breaks the build for everyone else.

The repository ships the file with empty IDs. A build without them shows *Gmail sign-in is not
configured in this build* (or the Outlook and Microsoft 365 equivalent) rather than failing
somewhere less obvious. Whoever produces a release fills them in.

### Using your own client ID

You can register your own application and use it instead of the build's. Under the sign-in
button, *Use your own Google client ID* (or Microsoft) writes an override file beside
accounts.json:

| | |
|---|---|
| Windows | `%APPDATA%\Rampart\oauth-clients.json` |
| Linux and macOS | `~/.config/rampart/oauth-clients.json` |

```json
{
  "google": { "clientId": "1234-abc.apps.googleusercontent.com", "clientSecret": "GOCSPX-example" },
  "microsoft": { "clientId": "00000000-0000-0000-0000-000000000000", "tenant": "common" }
}
```

Each field overrides the build's on its own. An override with its own `clientId` never
borrows the build's `clientSecret`. Microsoft's `tenant` may be `common` (any account),
`consumers` (Outlook.com and other personal accounts), `organizations` (work and school), or
your own tenant's ID or domain. Either provider may also carry `"redirectHost"`, set to
`"127.0.0.1"` or `"localhost"` and nothing else, when your registration uses the other one.

**Google.**

1. In the Google Cloud console, create a project and enable the **Gmail API**, which is what
   lets you add the mail scope to the consent screen.
2. Configure the OAuth consent screen. Add the scope `https://mail.google.com/` (plus
   `openid` and `email`, which tell Rampart which account you picked).
3. Create an OAuth client ID of type **Desktop app**. Desktop clients need no redirect URI
   registered: Google accepts `http://127.0.0.1` on any port, which is what Rampart sends.
4. `https://mail.google.com/` is a *restricted* scope. While the app's publishing status is
   **Testing**, only the test users you list on the consent screen (up to 100) can sign in,
   and Google expires their refresh tokens after **seven days**, so Rampart will ask them to
   sign in again weekly. Publishing it for anyone requires Google's verification, which for a
   restricted scope includes a paid third-party security assessment. For yourself and your
   household, Testing with yourselves as test users is the practical choice.

**Microsoft.**

1. In the Microsoft Entra admin center, under App registrations, register a new application.
   For *Supported account types* choose accounts in any organisational directory and personal
   Microsoft accounts, which matches the `common` tenant.
2. Under Authentication, add the platform **Mobile and desktop applications** with the custom
   redirect URI `http://localhost`. It is a public client: do not create a client secret.
3. Under API permissions, add the delegated permissions `IMAP.AccessAsUser.All` and
   `SMTP.Send` from **Office 365 Exchange Online**, and `offline_access`, `openid` and `email`
   from Microsoft Graph. Without `offline_access` Microsoft issues no refresh token and the
   account would need the browser every hour.
4. Rampart sends Microsoft `http://localhost:<port>`, and Microsoft ignores the port when it
   matches a loopback redirect. The listener itself is bound to `127.0.0.1` only; browsers
   resolve `localhost` to the loopback address and fall back to `127.0.0.1` when nothing
   answers on the IPv6 one. Microsoft also accepts `http://127.0.0.1`, but at the time of
   writing only by editing `redirectUris` in the application manifest rather than through the
   portal, which is why `localhost` is the default here. If you register `127.0.0.1` that way,
   set `"redirectHost": "127.0.0.1"` in the override.
5. On Microsoft 365, the organisation has to allow IMAP and authenticated SMTP for your
   mailbox. If Microsoft signs you in and the mail server still refuses, that is the setting,
   and Rampart says so rather than asking for the sign-in again.
