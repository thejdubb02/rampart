# Account security: your own password, app passwords and two-step login

Settings, Security. Built 2026-09-28 against Stalwart 0.16.24's source, not yet checked
against a live server. This page is the research behind it, so the next person does not
have to read the Rust again, and the list of what still has to be confirmed on a real one.

## Which class this is

Class 3 in `self-hosting.md`: it depends on the mail server. It works on a Stalwart account
signed in over JMAP, and on anything else the page is one sentence saying why not. It runs
entirely as the signed-in person with their own mail session. **No admin token, ever.**
Stalwart lets every account manage its own credentials, which is exactly what a mail
client should be using.

## Where it lives on the server

Stalwart 0.16 moved its management surface into JMAP. The old `/api/...` REST paths answer
404, and `/api/schema` now only describes the objects. The objects themselves are JMAP
methods with an `x:` prefix, sent to the ordinary JMAP API URL (`/jmap/`), under the
capability `urn:stalwart:jmap`.

**How Rampart knows it is talking to Stalwart.** The session names `urn:stalwart:jmap`
under `primaryAccounts` (and in the account's `accountCapabilities`). It is *not* in the
session-level `capabilities` object in 0.16.24, so checking there finds nothing. Source:
`crates/jmap/src/api/session.rs`, where `account_capabilities()` returns true for
`Capability::Stalwart` on every token, and `crates/common/src/config/mailstore/capabilities.rs`,
which never adds it to the session-level list. `Jmap.managementAccountId` is that value,
null on any other server, and it is the only test the page uses. Never a host name.

**Which account.** For `x:` methods Stalwart replaces a missing or invalid `accountId` with
the caller's own (`resolve_account_id` in `crates/jmap/src/api/request.rs`), and then checks
the caller is a member of it. Rampart sends the id from `primaryAccounts` anyway, so a
server that behaved differently would refuse rather than guess.

**`using`.** Stalwart does not require `urn:stalwart:jmap` in `using` for these methods
(`request.rs` skips the check for it), but Rampart sends it, which is correct JMAP.

## The objects

Names and fields from `crates/registry/src/schema/structs.rs` and the handlers in
`crates/jmap/src/registry/mapping/account.rs`. Every property below is exactly as it goes
on the wire.

### `x:AccountPassword`, a singleton

The id of a singleton is the literal string `singleton`. (It is the number 20080258862541
in Stalwart's own base32 alphabet, which happens to spell that word.)

| Property | Meaning |
|---|---|
| `secret` | The password. Always `****` when read. Set it to change the password. |
| `currentSecret` | The current password. Never read back; required on every change. |
| `otpAuth` | An object: `otpUrl` (the `otpauth://` URI, `****` when read, absent when two-step is off) and `otpCode` (the current six digit code, write only). |

- `x:AccountPassword/get` with `ids: ["singleton"]`. An account with no password credential
  (signing in through an external directory, say) comes back in `notFound`.
- `x:AccountPassword/set` with `update: { "singleton": { ... } }`. Keys may be JSON pointer
  paths, and Rampart uses `otpAuth/otpUrl` and `otpAuth/otpCode` so as not to overwrite the
  stored URL with a whole object it cannot read.

What the server demands, from `account_set`:

- **Any change needs `currentSecret`.** Without it: `forbidden`, "Current secret must be
  provided to change the password or OTP auth."
- **With two-step on, any change also needs `otpAuth/otpCode`.** Without it: `forbidden`,
  "Current OTP code is required to change the password or OTP auth." (It only says so when
  the password was right.)
- **Wrong password:** `forbidden`, "Current secret is incorrect." Repeated failures hit the
  server's authentication ban, which fails the whole request rather than the one update.
- **A weak new password:** `invalidProperties` on `secret`, with the server's reason.
- **Accounts from an external directory (LDAP, SQL, OIDC):** `forbidden`, "Operation not
  allowed." Their password lives in that directory.
- **Turning two-step off:** set `otpAuth/otpUrl` to null, with `currentSecret` and the
  current `otpAuth/otpCode`.

### The finding that shaped the design: the server never checks a new code

When `otpUrl` is set, Stalwart stores it. It does not ask for a code made from the new
secret, so a secret that was never scanned, or scanned wrongly, is accepted without
complaint and the next sign-in locks the person out. So **Rampart makes the secret, shows
it, and checks a code from the person's app against it locally** (RFC 6238, one step of
slack each way, as the server does) before anything is sent. `Totp.kt` is that, tested
against the RFC 4226 and RFC 6238 published vectors.

The URL has to be one the `totp-rs` crate (6.0, with `otpauth`) will parse: `otpauth://totp/`,
a label of `issuer:account`, an `issuer` parameter that agrees with it, an unpadded base32
secret of at least 128 bits. Rampart uses 160 bits, SHA-1, six digits, thirty seconds, and
the address's own domain as the issuer.

### The second finding: two-step login switches off password sign-in for mail apps

Stalwart's HTTP Basic, IMAP and SMTP sign-ins have nowhere to carry a code (`mfa_token` is
always `None` for them; see `crates/http/src/auth/authenticate.rs` and
`crates/directory/src/core/sasl.rs`), so once `otpUrl` is set **the mailbox password alone
stops working for every mail app, Rampart included**. The server also drops its cached
sign-in the moment any credential changes, so this happens on the very next request, not
the next restart.

So when two-step is turned on and Rampart is signing in with the mailbox password, it first
makes an app password for itself ("Rampart on <computer name>"), turns two-step on, and
switches its own session and the operating system's credential store to the app password.
If the server refuses two-step, that app password is revoked again. If the credential store
cannot keep it, the page shows it once so it can be typed at the next start.

The same reasoning covers a password change: Rampart follows the new password only when it
was signing in with the old one (it asks the credential store) and two-step is off.

### `x:AppPassword`, a collection

| Property | Meaning |
|---|---|
| `id` | The credential's id. |
| `description` | What it is for. |
| `secret` | Made by the server, returned **once** in the `created` response, `****` ever after. |
| `createdAt` | Server set. |
| `expiresAt` | Optional UTC date-time. |
| `permissions` | `{"@type": "Inherit"}`, `Disable` or `Replace` with a `permissions` list. |
| `allowedIps` | Optional list of addresses or CIDR ranges. |

- `x:AppPassword/get` with `ids: null` lists them.
- `x:AppPassword/set` `create: { "new": { "description", "permissions", "expiresAt"? } }`.
  The response's `created.new` carries `id` and `secret`, and that is the only time the
  secret exists in readable form anywhere. It starts `app`, which is how the server tells
  an app password from a real one at sign-in.
- `x:AppPassword/set` `destroy: [id]` revokes one.
- Over the account's quota (`MaxAppPasswords`): `overQuota` with a description.
- Rampart sends `Inherit`. A narrower set means choosing from several hundred server
  permissions, which is admin console work, and the server refuses any set wider than the
  caller's own anyway (`validate_credential_permissions`).

`x:ApiKey` is the same shape, with keys starting `API_`. Not on this page: an API key is for
scripts, and a mail client has no business minting one by default. It is the obvious next
addition if anybody asks.

### Permissions

Each object has its own permission, and an administrator can take any of them away:
`sysAccountPasswordGet` and `sysAccountPasswordUpdate`; `sysAppPasswordGet`, `Create`,
`Update`, `Destroy` and `Query`. A missing one is a method-level `forbidden`, which the page
turns into "this server does not let the account manage that itself. An administrator can
allow it."

## What works, what is stubbed, what needs a live server

**Works, as far as can be shown without a server.** The request builders and response
readers, the error sentences, the TOTP maths and the QR code are unit tested
(`AccountSecurityTest`, `TotpTest`, `QrCodeTest`, which decodes the QR code back with
ZXing's reader). The page gates itself on protocol and on `managementAccountId`.

**Deliberately not here.** API keys, per-credential permissions and allowed IPs, recovery
codes (Stalwart 0.16 has none), and anything on another person's account.

**Needs checking against a live Stalwart, in this order:**

1. That `primaryAccounts` really carries `urn:stalwart:jmap` for an ordinary user, and
   the page appears.
2. `x:AccountPassword/get` shape: `otpAuth` present as `{}` or absent when two-step is off.
3. A password change, then that mail keeps loading (the session switched credential).
4. Two-step on: scan the QR with a real app, confirm, and check the webmail then asks for a
   code, that Rampart kept working on its new app password, and that "Rampart on ..." is
   listed.
5. A patch key of `otpAuth/otpUrl` with a null value turns two-step off. If the server
   wants the whole `otpAuth` object instead, `disableTwoStepCall` is the one place to
   change.
6. Make, copy, sign in with, and revoke an app password in another mail app.
7. The wording of the authentication ban, which fails the request rather than one update.
