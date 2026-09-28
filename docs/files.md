# Files: JMAP file storage in Stalwart 0.16

What Stalwart actually does with `urn:ietf:params:jmap:filenode`, read from the 0.16.24
source rather than from the draft, and what Rampart does with it. Paths below are in the
Stalwart repository. The protocol is the IETF JMAP file storage draft
(draft-ietf-jmap-filenode); the source cites its revisions 13 and 14 in TODO comments,
which are also the places where it does not yet do what the draft says.

Rampart's side is `src/main/kotlin/org/rampart/Files.kt` (requests and answers, all plain
functions, tested in `FilesTest.kt`) and `FilesPane.kt` (the page, Save to Files and From
Files).

## Which class of feature this is

Class 3 in `CLAUDE.md`: it needs the account's own Stalwart. The sidebar button is absent
unless at least one signed in account's session advertises the capability, and the page
says in one sentence why an IMAP account or a server without file storage has nothing to
show. It does everything as the signed in user, over the same mail session. No admin
token is involved.

## Methods

`crates/jmap-proto/src/request/method.rs`: `FileNode/get`, `FileNode/changes`,
`FileNode/query`, `FileNode/queryChanges`, `FileNode/set`, `FileNode/copy`. Every one of
them requires `urn:ietf:params:jmap:filenode` in `using`, and Rampart names it only on
these calls (the reason is on `Jmap.call`). The account is the mail account; Stalwart
keeps an account's files in the same account as its mail.

## Properties

`crates/jmap-proto/src/object/file_node.rs`, `FileNodeProperty`: `id`, `parentId`,
`blobId`, `size`, `name`, `type`, `nodeType`, `target`, `created`, `modified`, `accessed`,
`changed`, `executable`, `role`, `myRights`, `shareWith`, `isSubscribed`.

What `crates/jmap/src/file/get.rs` actually returns:

- **A folder is a node with no blob.** `nodeType` is `"directory"` when the stored node has
  no file part and `"file"` when it has one. For a folder `blobId`, `size`, `type` and
  `executable` are all null. `"symlink"` exists in the enum and is never produced.
- **There is no root node.** `parentId` is null for a top-level node. `role` is always
  null, so the draft's root, home, trash and the rest are not there to hang anything from.
- `type` for a file with no stored type comes back as `application/octet-stream`.
- `size` is stored as a 32-bit number (`crates/groupware/src/file/mod.rs`), so a file over
  4 GiB cannot be represented; `FileNode/set` refuses a `size` above that.
- `accessed` is always "now", `changed` is always `modified`, and `isSubscribed` is always
  true. All three are TODOs in the source.
- `myRights` is every right for the owner, and the computed ACL on a shared node.
- `FileNode/get` takes one extra argument, `fetchParents`, which adds every ancestor of the
  requested ids.
- The `blobId` of a file is a blob linked to that FileNode document, not the upload.

## Query

`crates/jmap/src/file/query.rs`. Filters that do something: `parentId`, `ancestorId`,
`descendantId`, `isTopLevel`, `nodeType` ("directory" or "file"), `name`, `nameMatch`
(a glob), `minSize`, `maxSize`. **Accepted and silently ignored:** `role`, `hasAnyRole`,
`blobId`, `isExecutable`, the created, modified and accessed ranges, `type`, `typeMatch`,
`text` and `body`. Sorts that work: `name`, `size`, `nodeType` (folders first); `created`,
`modified`, `type` and `tree` are accepted and have no effect, which matches the
`fileNodeQuerySortOptions` the session advertises (`name`, `size`, `nodeType`). The query
takes a `depth` argument that is parsed and not used.

Limits come from the server's JMAP settings: 500 objects per get and 5000 results per
query by default. Rampart reads the whole tree in pages of 250 (a query and a get by
back-reference in one request) and stops after 40 pages.

## Set

`crates/jmap/src/file/set.rs`.

**Arguments.** `onDestroyRemoveChildren` (default false), `onExists` (`"reject"` by
default, or `"replace"`, `"rename"`, `"newest"`), `compareCaseInsensitively` (default
false; the session says names are case sensitive).

**Names.** 1 to 255 octets. Refused if they contain any of `/ < > : " \ | ? *`, or are
`.`, `..`, `CON`, `PRN`, `AUX`, `NUL`, `COM0` to `COM9` or `LPT0` to `LPT9` in any case.
The refusal is `invalidProperties` with a description naming which rule. Rampart replaces
those characters in a local file's name before uploading, prefixes a reserved name with
an underscore, and checks a typed name before sending it.

**Creating a folder** is a create with a name and no `blobId`. Setting `type`, a non-zero
`size` or `executable: true` on a folder is refused.

**Creating a file** is a create with a `blobId`. The server checks the caller may read that
blob, reads it to get the size (a `size` the client sends is replaced by the real one),
and refuses a missing blob with `invalidProperties` on `blobId`. A `type` is only taken
when it contains a slash and is at most 256 octets; anything else makes the create fail,
so Rampart leaves out a type it doubts.

**Parents.** `parentId` must name an existing folder (or one created earlier in the same
request). Moving a folder into itself or into anything under it is refused as a circular
reference. In a shared account nothing can be created at the top level.

**Name collisions** are among siblings with the same parent. With `onExists` at its
default the create or update fails with `alreadyExists` and `existingId`. `"rename"`
picks `name (2).ext`, `name (3).ext` and so on, and the created or updated object in the
response carries the new `name`. `"replace"` destroys the existing node, and refuses with
`nodeHasChildren` when that is a non-empty folder unless `onDestroyRemoveChildren` is set.
`"newest"` replaces only when the incoming `modified` is later. Rampart uses `"rename"` for
uploads and Save to Files, so nothing is ever overwritten, and the default for new
folders, renames and moves, so the person hears that the name is taken.

**Updates.** `name`, `parentId`, `blobId` (new contents), `type`, `executable`, `modified`
and `shareWith` can be changed. `nodeType` and `created` cannot, and `changed` is never
settable. `modified` is bumped to now unless the client sets it.

**Destroy.** A node with anything under it is refused with `nodeHasChildren` unless
`onDestroyRemoveChildren` is true, in which case the whole subtree goes and every id in it
is listed in `destroyed`. There is no trash: destroyed is gone. Rampart's delete dialog
says so, and for a folder counts the files and folders that go with it.

**Error types used:** `forbidden`, `notFound`, `invalidProperties`, `alreadyExists`,
`nodeHasChildren`, `willDestroy`, plus the generic `overQuota` and `tooLarge`. Rampart turns
each into one sentence, preferring Stalwart's own description where it wrote one
(`setErrorSentence` in `Files.kt`).

## Content: upload, download, and blobs shared with mail

A file's content goes up through the ordinary JMAP upload endpoint (`uploadUrl`), exactly
as a mail attachment does, and comes down through `downloadUrl` with the file's `blobId`.
Rampart reuses `Jmap.upload` and `Jmap.download`, and the download goes through the same
`uniqueIn` and `safeFileName` as a saved attachment, with a second check that the file it
wrote is inside the folder it was meant for.

Whether a blob can be shared between mail and files was checked in the source, and the
answer is different in each direction.

- **File to email: yes, by reference.** `Email/set` fetches an attachment's blob through
  `blob_download` (`crates/jmap/src/email/set.rs`), which applies `has_access_blob`
  (`crates/jmap/src/blob/download.rs`). A blob linked to a FileNode in the caller's own
  account passes that check. So From Files in the composer attaches the node's own
  `blobId` and nothing is downloaded or uploaded.
- **Email attachment to file: not by its blobId.** An attachment's `blobId` in Stalwart
  names a *section* of the message blob (an offset, a length and a transfer encoding;
  `BlobId.section` in `crates/types/src/blob.rs`). `FileNode/set` ignores the section and
  reads the whole blob by its hash (`get_blob(blob_id.hash, 0..usize::MAX)` in
  `set.rs`), so handing it an attachment's `blobId` would store the entire raw message,
  still encoded, under the attachment's name. It does not refuse; it stores the wrong file.
- **What works instead: `Blob/upload` (RFC 9404).** `crates/jmap/src/blob/upload.rs` takes
  a data source of `{ "blobId": ... }`, reads the section and decodes it
  (`get_blob_section` in `crates/common/src/storage/blob.rs`), and stores the result as a
  new blob. Save to Files does that, then creates the FileNode over the new blob: two small
  requests, and the file never leaves the server. Where the session does not advertise
  `urn:ietf:params:jmap:blob`, it downloads the attachment into a temporary folder of its
  own, uploads it and removes the folder.

## The session

`crates/common/src/config/mailstore/capabilities.rs` advertises, for the account:
`maxFileNodeDepth` null, `maxSizeFileNodeName` 255, the forbidden characters and names
above, `fileNodeQuerySortOptions` `["name", "size", "nodeType"]`,
`mayCreateTopLevelFileNode` true, `caseInsensitiveNames` false, and no web trash or web
URL template. Rampart does not read these yet; it applies the same rules because they are
fixed in this version of the source.

## Not done yet

- Sharing (`shareWith`, `myRights`) is not shown or edited.
- `FileNode/changes` is not used; the page reads the whole tree when it opens and after
  every change it makes.
- Uploading a folder by dropping it: folders are skipped with a sentence saying so.
- Replacing a file's contents in place (a `blobId` update).
- None of this has been run against a live server yet.
