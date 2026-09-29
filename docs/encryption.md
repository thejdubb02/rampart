# Encryption: OpenPGP and S/MIME in Rampart, and Stalwart's encryption at rest

Kaneo RAM-37, built into Rampart rather than as a plugin. Started 2026-09-29 on branch
`claude/encryption`, **not released and not yet checked against a live server or a real
correspondent**. This page is the design, the research behind the server half (read from the
Stalwart 0.16.24 source, not from memory), and the list of what still has to be confirmed.

## Which class this is

Two features that share a key list, and they sit in different classes of `self-hosting.md`:

- **Signing, encrypting, decrypting and verifying mail** is class 1. It happens on this
  computer, with keys on this computer, and works on any account, IMAP included. Looking a
  recipient's key up on the network (Web Key Directory, keys.openpgp.org) talks to those
  services directly and needs no server of ours.
- **Encryption at rest on the server** is class 3. It is Stalwart's feature, set on your own
  account over JMAP as you, and on any other account the section is one sentence saying why
  it is not there.

Neither ever uses an admin token.

---

## The server half: Stalwart's encryption at rest

### What it is

Stalwart can encrypt every message it stores for an account to that account's own OpenPGP
key or S/MIME certificate. The copy on the server's disk can then only be read with the
private key, which stays on the person's computer. Somebody with the disk, a backup or the
database cannot read the mail; the server itself cannot read it back either.

### The objects, exactly as they are on the wire

All three are Stalwart management objects under `urn:stalwart:jmap`, sent to the ordinary
JMAP API URL, the same way `account-security.md` describes for passwords.

**`x:PublicKey`**, a collection (`crates/registry/src/schema/structs.rs`, `PublicKey`;
validation in `crates/jmap/src/registry/mapping/public_key.rs`):

| Property | Meaning |
|---|---|
| `key` | The key as text: one or more PEM blocks, either OpenPGP (`-----BEGIN PGP PUBLIC KEY BLOCK-----`) or X.509 (`-----BEGIN CERTIFICATE-----`), never a mix. Parsed by `parse_public_key` in `crates/common/src/storage/encryption.rs`. An OpenPGP key must have a usable, unrevoked, unexpired subkey flagged for transport encryption. |
| `description` | Required, non-empty. |
| `emailAddresses` | Optional set of addresses, sent as an object of `true` values. |
| `expiresAt` | Optional UTC date-time. |
| `accountId` | Set by the server to the caller's account. A normal user cannot set it (`assert_can_set_account`). |
| `createdAt` | Server set. |

Over the account's quota (`MaxPublicKeys`) a create fails with `overQuota`.

**`x:AccountSettings`**, a singleton with id `singleton` (`AccountSettings` in `structs.rs`;
handled by `account_set` and `account_get` in `crates/jmap/src/registry/mapping/account.rs`).
It carries `description`, `locale`, `timeZone` and the one that matters here:

`encryptionAtRest`, one of:

```json
{"@type": "Disabled"}
{"@type": "Aes256", "publicKey": "<x:PublicKey id>", "encryptOnAppend": false, "allowSpamTraining": false}
```

with `@type` one of `Aes128`, `Aes256`, `Aes256Gcm`, `ChaCha20Poly1305`. This is the same
`encryptionAtRest` field an administrator sees on `x:Account` (`UserAccount`); the
self-service object is a window onto those four fields of your own account and nothing else.
Any other property in the update is refused with `invalidProperties`.

**`x:Email`**, the server-wide singleton an administrator controls, has `encryptAtRest`
(default **true**) and `encryptOnAppend` (default **false**). These gate the per-account
setting: see below.

### Can the signed-in user set it on their own account? Yes

Checked in `crates/common/src/auth/permissions.rs`: the default **user** role gets every
permission whose name starts with `sysAccountSettings` or `sysPublicKey`
(`sysAccountSettingsGet`, `sysAccountSettingsUpdate`, `sysPublicKeyGet`, `Create`, `Update`,
`Destroy`, `Query`). `x:PublicKey` is filtered to the caller's own account
(`OBJ_FILTER_ACCOUNT`), so one person sees and changes only their own keys. So Rampart does
this entirely as the signed-in person with their own mail session, which is what `CLAUDE.md`
requires.

Two ways it can still be refused, both shown as one sentence and neither worked around:

- An administrator removed one of those permissions from the account's role. The call fails
  with a method-level `forbidden`, and the page says "this server does not let accounts set
  their own encryption. Only an administrator can change that, and Rampart will not ask for
  administrator rights." (`restFailure` in `EncryptionAtRest.kt`.)
- The account is the recovery administrator, which has `sysAccountSettings*` cleared
  (`crates/http/src/auth/permissions.rs`).

### What it encrypts

From `email_ingest` in `crates/email/src/message/ingest.rs`:

- **New mail only.** Encryption happens when a message is stored. **Mail already in the
  mailbox is not touched**, and there is no task that goes back over it.
- **Mail arriving by SMTP** is encrypted whenever the server-wide `x:Email.encryptAtRest` is
  true (the default) and the account has a key set.
- **Mail put in by a client** (JMAP `Email/import`, IMAP `APPEND`: your own sent copies,
  drafts, moves from another server) is encrypted only when **both** the account's
  `encryptOnAppend` and the server-wide `x:Email.encryptOnAppend` are true. The server-wide
  one is off by default, so on most servers only incoming mail is encrypted. Rampart cannot
  read `x:Email` without admin rights, so the page says this rather than claiming to know.
- **Restores from a backup** are never encrypted.
- **A message that is already encrypted** (multipart/encrypted, application/pkcs7-mime, or a
  single text part that starts with an armored PGP block) is stored as it came, not wrapped a
  second time (`is_encrypted` in `crates/email/src/message/crypto.rs`).
- If an administrator has turned the server-wide `encryptAtRest` off, the per-account setting
  is accepted and does nothing. That is also invisible to a non-admin.

The server's own processing still sees the plaintext: Sieve filters run in
`crates/email/src/sieve/ingest.rs` before `email_ingest` stores the message, and the spam
filter runs at SMTP time. Filters keep working.

### What the stored message looks like

The server moves the message's MIME headers and body inside the encryption and keeps every
other header outside (`encrypt` in `crypto.rs`). So **From, To, Cc, Subject, Date and
Message-ID stay readable on the server**; the body and attachments do not.

- OpenPGP: `multipart/encrypted; protocol="application/pgp-encrypted"` with the usual version
  part and an armored `encrypted.asc`, the preamble "OpenPGP/MIME message (Automatically
  encrypted by Stalwart)". Standard RFC 3156, which Rampart, Thunderbird and GnuPG read.
- S/MIME: `application/pkcs7-mime` enveloped data, or authenticated enveloped data for the
  AEAD ciphers.

### Algorithms

| `@type` | OpenPGP key | S/MIME certificate |
|---|---|---|
| `Aes128` | AES-128, OpenPGP integrity-protected data (Sequoia) | AES-128-CBC, key wrapped with RSA PKCS#1 v1.5 |
| `Aes256` | AES-256, as above | AES-256-CBC, key wrapped with RSA PKCS#1 v1.5 |
| `Aes256Gcm` | **refused** | AES-256-GCM as authenticated enveloped data, key wrapped with RSA-OAEP (SHA-256) |
| `ChaCha20Poly1305` | **refused** | ChaCha20-Poly1305 likewise |

With an OpenPGP key the two AEAD choices are refused by `account_set` with
`invalidProperties` and "AES-256-GCM is only supported for S/MIME encryption, but the
selected public key is an OpenPGP key." Rampart only offers AES-256 and AES-128 for an
OpenPGP key (`ciphersFor`), and AES-256 is the default either way.

**S/MIME at rest works only with an RSA certificate**: the server reads the certificate's key
as an RSA public key (`RsaPublicKey::from_pkcs1_der`). An elliptic curve certificate is
accepted by `x:PublicKey/set` and then fails when mail arrives.

ChaCha20-Poly1305 inside CMS is rare, and most mail programs cannot open it; Rampart has not
been checked against it either. It is offered because Stalwart offers it, but anybody choosing
it should expect other programs not to read the result. AES-256 is what every client reads.

### What it does to search, to other clients and to spam training

- **Server-side search stops seeing the body.** After encrypting, the server empties every
  text and attachment part of its parsed copy before indexing (`Remove contents from parsed
  message` in `ingest.rs`). Searching on the server still finds the headers (sender, subject,
  date), never the words in the message.
- **Webmail and phone apps cannot read the mail** unless they hold the private key. Stalwart's
  webmail, Bulwark, iOS Mail without the S/MIME identity installed and most Android clients
  show an attachment called `encrypted.asc` or `smime.p7m` and nothing else. Thunderbird with
  your key, Apple Mail with your S/MIME identity, Thunderbird for Android with OpenKeychain,
  and Rampart read it.
- **Spam training** is controlled by `allowSpamTraining`. With it on, Stalwart keeps the
  message's original, unencrypted blob as a training sample (`add_spam_sample` is handed
  `params.blob_hash`, the blob as it arrived). **Rampart always sends it as false**, because a
  readable copy kept for the classifier undoes the point.
- **Losing the private key loses the mail.** There is no recovery on the server side.

### How to turn it off

`x:AccountSettings/set` with `encryptionAtRest: {"@type": "Disabled"}`, which is the Turn off
button. **Mail already encrypted stays encrypted**: the server cannot decrypt it, having no
private key. The public key stays in `x:PublicKey` until destroyed; Rampart leaves it, so
turning encryption back on reuses it rather than uploading a second copy (`ensureKey` compares
the key text).

### The Rampart side of it

Settings, Encryption, "Encrypted on the server" (`EncryptionPage.kt`, calls in
`EncryptionAtRest.kt`):

1. Pick one of your own keys (a test certificate is not offered: this protects real mail).
2. Pick the cipher (only the ones the server accepts for that kind of key) and whether mail
   copied in by apps is included.
3. Turn on: uploads the public key with `x:PublicKey/set` unless the server already holds
   exactly that text, then sets `encryptionAtRest` in `x:AccountSettings/set`.

The warning is on the page above the button, in the error colour: server-side search stops
covering new mail, other clients cannot read it, losing the private key loses the mail.

### A finding worth reporting upstream (not ours to fix)

`EncryptionSettings.publicKey` is indexed as a foreign key (`structs_impl.rs`), which checks
that the `x:PublicKey` exists, but `account_set` does not check that it belongs to the same
account. A user who knew the id of somebody else's public key could point their own account at
it and have their mail encrypted to that person. It harms only the account doing it, since the
other person cannot fetch the mail, but it is not what a reader of the schema would expect.
Rampart only ever uploads and names the person's own key.

---

## The client half

### Reading

`CryptoMime.kt` recognises and opens:

- **PGP/MIME** (RFC 3156): `multipart/encrypted` and `multipart/signed` with
  `application/pgp-signature`, including sign-then-encrypt in both forms (a signed OpenPGP
  message inside the encryption, and a `multipart/signed` entity inside it).
- **Inline PGP**: an armored `BEGIN PGP MESSAGE` or `BEGIN PGP SIGNED MESSAGE` block in a
  text part. Words outside the block were neither encrypted nor signed; they are shown apart,
  under a line saying so, never mixed into the protected text.
- **S/MIME** (RFC 8551): `application/pkcs7-mime` enveloped data and authenticated enveloped
  data (AES-GCM), opaque signed data, and `multipart/signed` with
  `application/pkcs7-signature`.

A signed part is checked against its bytes exactly as they arrived, cut out of the raw message
at its boundary lines, not re-serialised by a MIME library. The whole message is fetched as
its own blob on JMAP, so 8-bit bytes are not re-encoded on the way.

A message without an OpenPGP integrity packet is refused, not shown: that is the shape an
attacker leaves after changing ciphertext in flight (EFAIL). Only the encrypted part is ever
decrypted and shown; it is never joined back to plaintext parts around it, which is the other
half of EFAIL.

**The badge** (`sealLines`) sits above the message:

- "Encrypted with OpenPGP" or "S/MIME", and whether it was signed.
- A good signature is calm only when the key is one you have (made, imported, from your own
  address book, from WKD or keys.openpgp.org, or one you said to trust) or, for S/MIME, one a
  certificate authority in this computer's trust store vouches for, **and** it names the address
  the message is from. Otherwise it is an alarm in the theme's error colour (the brand red,
  `#DB2D54`), saying which: broken, signed by an unknown key, signed by a key you have not
  trusted, or signed by somebody other than the sender.
- No revocation checking for S/MIME (OCSP or CRLs): that is a network request per message
  opened. Listed below as not done.

**Decrypted content goes through the same hostile HTML path as any other mail.** It becomes a
`Body` and is prepared by `prepareReading`: the jsoup clean, the content security policy, the
link confirmation. Remote pictures in decrypted content are always blocked, whatever the
sender allowance says, because a remote picture in an encrypted message would tell its sender
the moment it was decrypted. Nothing about decryption loosens `EmailDocument.kt`.

The decrypted body is held in memory for the session (`SealedView`), never written to the
local store as a body. Replies quote the server's copy, not the decrypted text, so a reply
cannot carry decrypted words out in the clear because encryption was not switched on for it.
That is deliberately conservative and is listed below as something to revisit.

### Sending

Sign and Encrypt are two buttons in the composer's options row, per message
(`CryptoCompose.kt`). The key is chosen per identity, in Settings, Encryption; an identity's
kind of key decides the format (OpenPGP or S/MIME). PGP/MIME is the default; inline PGP is a
setting, and it refuses a message with formatting or files rather than sending part of it
unprotected.

- **Always encrypted to the sender's own key too**, so the copy in Sent is readable. With no
  key of your own, an encrypted send is refused for that reason.
- **A missing recipient key blocks the send, naming the recipient**: "There is no encryption
  key for ben@example.net, so the message was not sent. Find their key, or turn encryption off
  to send it readable." The composer says the same thing live, before Send.
- **Never silently unencrypted.** The ordinary send path refuses a draft marked for signing or
  encryption (`refusedOption`), so anything that forgot to route it through `sendSealed` fails
  loudly. A message the server would hold to send later (FUTURERELEASE) is refused the same
  way; Rampart's own undo-send wait and its own scheduled sends go through the protected path.
- **Encryption hides the body, not the subject.** The composer says so whenever Encrypt is on,
  along with the addresses and the date. Protected headers (the draft that hides the subject
  too) are not written and not read yet.
- **Drafts saved while writing are ordinary, unencrypted drafts on the server.** The composer
  says so. Encrypting drafts is listed below.
- Attachments are fetched back from the server as blobs and go inside the encryption; one that
  cannot be fetched stops the send.
- The finished bytes are sent as they are: on JMAP uploaded, `Email/import`ed into Drafts and
  submitted with `EmailSubmission/set` (`Jmap.sendRaw`), on IMAP over SMTP and appended to
  Sent unchanged (`Imap.sendRaw`). The server never rebuilds the MIME the signature covers.

Algorithms written: OpenPGP AES-256 with the integrity packet, SHA-256 signatures, v4 keys.
S/MIME AES-256-CBC with RSA key transport, SHA-256 signatures, certificate chain included.

### Where a recipient's key comes from

In this order (`KeyLookup.kt`):

1. Keys already in the list.
2. **The address book**: a JSContact card's `cryptoKeys` (what a vCard KEY becomes over JMAP),
   only when the key is inside the card as a `data:` URI. A card that points at a URL is not
   followed.
3. **Web Key Directory**, the advanced method (`https://openpgpkey.<domain>/.well-known/
   openpgpkey/<domain>/hu/<hash>?l=<local>`) and then the direct method
   (`https://<domain>/.well-known/openpgpkey/hu/<hash>?l=<local>`) only when the advanced host
   does not exist, as the draft says. The hash is the z-base-32 of the SHA-1 of the lower-cased
   local part.
4. **keys.openpgp.org** through its VKS API, `https://keys.openpgp.org/vks/v1/by-email/<address>`.
5. **Keys attached to received mail**: an Autocrypt header naming the From address, or an
   `application/pgp-keys` part, and the signer's certificate on a signed S/MIME message. Kept
   only when the person presses Keep, and **not trusted** until they say so.

3 and 4 are network requests that tell a third party who you are writing to. They happen only
when you compose to that recipient with Encrypt on, or press Look up in Settings, never in the
background, and the composer says which address is being looked up and where while it happens.
Both are https only, redirects to http are refused, the answer is capped at 512 KB, and a key
is only accepted if it names the address it was looked up for. A key found this way is offered
with its fingerprint and used only once "Use this key" is pressed. S/MIME certificates are not
looked up on the network.

### Keys

`KeyStore.kt`:

- **Make**: OpenPGP Ed25519 (signing) with X25519 (encryption) as v4 keys, the default, which
  GnuPG, Thunderbird and Proton read; or RSA 3072 or 4096. S/MIME: import a PKCS#12 (`.p12`,
  `.pfx`) from a certificate authority. A self-signed S/MIME certificate can be made **for
  testing only**; the key list marks it so for good, and it is not offered for encryption at
  rest.
- **Import** an armored or binary OpenPGP key (public or secret), a PKCS#12, or a PEM or DER
  certificate.
- **Export**: a public key to the clipboard freely; a secret key only after a confirmation
  that says what anybody holding the file can do (`exportSecret(confirmed = true)`).
- **Stored in the operating system's credential store, never in a file of ours.** The key
  list (`keys.json`, beside the settings) holds only public halves, where each key came from,
  trust, and which identity uses which. Secret halves go through `Secrets.storeNamed` under
  `openpgp-secret-<fingerprint>` and `smime-secret-<fingerprint>`, base64 on one line. If the
  store refuses, the key is not listed, so nothing looks like a working key that is not.
- **Passphrases are never stored.** A key imported with a passphrase keeps it; it is asked for
  the first time the key is needed, held in memory for the session, and forgotten on exit. A
  PKCS#12 password is checked on import and asked for again the same way.

**Sizes in the credential store.** The secrets are small: a Curve25519 secret key ring is
about 1 KB of base64, RSA 4096 about 7 KB, a PKCS#12 from a CA 3 to 10 KB.

- Linux: `secret-tool store` reads the secret from standard input until end of file, with no
  size limit of its own; the secret service holds it in a D-Bus message, whose default limit is
  far larger than any key. Not checked on this machine: there is no secret service here.
- Windows: DPAPI (`CryptProtectData`) into a file under the settings directory; no documented
  limit anywhere near these sizes. Not checked on this machine.
- macOS: `Secrets.kt` only knows DPAPI and `secret-tool`, so on a Mac without `secret-tool`
  there is no credential store, and **secret keys cannot be made or imported there**; the page
  says so in one sentence. Other people's public keys still work. Keychain support is a
  `Secrets.kt` change, not an encryption one.

### Rook

Rook never sees ciphertext or decrypted plaintext by default (`RookGate.kt`). Every path that
sends message text to a model goes through the gate:

| Path | Where |
|---|---|
| Summarise, action items and suggested replies (all read `summariseTurns`) | `Main.kt` |
| Reply context for writing help | `Main.kt`, `summariseTurns` |
| The chat panel's open message and its `read` tool | `Main.kt`, `rookTextOf` |
| Ask Rook and the Today view (`inboxReader`) | `Main.kt`, `readerFor` |
| Add to calendar and Make a task | `CalendarFromMailUi.kt`, `TasksUi.kt` |
| The tone sample from Sent | `WritingHelpUi.kt` |

An armored block or S/MIME content is caught by content, so it holds for messages nobody has
opened; PGP/MIME and S/MIME messages carry no text part as the server describes them, so there
is nothing of theirs to send. A body the reader decrypted is registered with the gate and comes
out as a one-line placeholder. The one way past is **Decrypt to summarise**, a button on an
opened encrypted message: it builds the summary packet with the decrypted text and always opens
the packet viewer first, whatever was agreed before, so the person sees exactly what would be
sent and presses Send themselves.

### Search inside encrypted mail

A setting, **off by default** (`KeyIndex.searchInside`). When on, a message decrypted in the
reader has its text added to the local search index in the local store, which is SQLCipher
with its key in the credential store. With no encrypted store (no credential store, or a store
opened without a key) nothing is indexed, whatever the setting says (`EncryptedSearch.mayIndex`,
`Store.indexDecrypted` refuses too). Turning it off takes the decrypted words back out
(`Store.forgetDecrypted`). Decrypted text is never sent to server-side search.

---

## What was verified, and how

All offline, with keys made inside the tests (`PgpTest`, `SmimeTest`, `CryptoKeysTest`,
`SealedSendTest`, 51 tests): round trips for PGP/MIME, inline PGP and S/MIME; tampered
signatures give a broken verdict; wrong and missing private keys give one sentence; a missing
recipient key blocks sending with the recipient named; the MIME structure of what is sent; the
Rook gate; WKD addresses against the draft's own example; the search setting's default.

Interoperability was checked by hand on 2026-09-29 with GnuPG 2.4.4 and OpenSSL 3.0.13, in
both directions, with throwaway keys:

- GnuPG decrypted Rampart's PGP/MIME and verified its signature; Rampart decrypted GnuPG's
  PGP/MIME (signed inside), verified GnuPG's detached signature over a `multipart/signed` part,
  a GnuPG clear-signed inline message, and a GnuPG signed-and-encrypted inline message.
- OpenSSL decrypted Rampart's S/MIME and verified both its `multipart/signed` and the signature
  inside the enveloped message; Rampart read OpenSSL's `multipart/signed`, sign-then-encrypt,
  opaque signed data, and AES-256-GCM authenticated enveloped data with the key wrapped both by
  RSA PKCS#1 v1.5 and by RSA-OAEP with SHA-256 (the second is the shape Stalwart writes for
  `Aes256Gcm` at rest).

## Not done yet

- Protected headers (hiding the subject), both writing and reading.
- Encrypting drafts on the server while encryption is on.
- Quoting the decrypted text in a reply that is itself encrypted.
- S/MIME with elliptic curve certificates (key agreement), and S/MIME revocation checks.
- Opening files inside an encrypted message in place; they can be saved from the badge.
- Autocrypt as a sending protocol (Rampart only reads its header).
- Keys shared across a person's computers, and Keychain on macOS.
- Rook's settings map does not include the encryption options, on purpose: a model should not
  be the thing that turns on indexing of decrypted mail.

## Needs checking live, in this order

1. On a real Stalwart, as an ordinary user: `x:PublicKey/set` accepts an armored key with the
   trailing newline, `x:AccountSettings/get` returns `encryptionAtRest` as shown above, and
   Turn on then a message from outside arrives as `multipart/encrypted` and opens in Rampart.
2. Turn off leaves earlier mail encrypted and new mail plain.
3. With an OpenPGP key, `Aes256Gcm` is refused with the sentence quoted above (Rampart does not
   offer it; send it by hand to confirm the wording).
4. Thunderbird: a signed and encrypted message each way, with Ed25519 and with RSA keys, and a
   key found through WKD on a domain that publishes one.
5. Outlook and Apple Mail with a CA-issued S/MIME certificate: signed and encrypted each way,
   and the certificate chain verifying as vouched for.
6. Proton: encrypted mail from a Proton account (its keys through WKD or keys.openpgp.org) and
   back.
7. Webmail showing the server-encrypted mail as an attachment, as described.
8. The credential store on Windows, Linux and a Mac, with an RSA 4096 key.
