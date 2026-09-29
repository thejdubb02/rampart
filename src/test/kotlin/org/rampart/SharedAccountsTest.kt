package org.rampart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the session's other accounts, and keeping everything about a shared account
 * addressed to that account: its key, its settings, and the id written into every request.
 */
class SharedAccountsTest {
    /** Shaped like Stalwart 0.16's session: your own account, a group, and a colleague who shared a folder. */
    private val session = Json.parseToJsonElement(
        """
        {
          "primaryAccounts": { "urn:ietf:params:jmap:mail": "a" },
          "accounts": {
            "a": { "name": "me@example.com", "isPersonal": true, "isReadOnly": false,
                   "accountCapabilities": { "urn:ietf:params:jmap:mail": {}, "urn:ietf:params:jmap:mail:share": {},
                                            "urn:ietf:params:jmap:contacts": { "mayCreateAddressBook": true } } },
            "g": { "name": "sales@example.com", "isPersonal": false, "isReadOnly": false,
                   "accountCapabilities": { "urn:ietf:params:jmap:mail": {}, "urn:ietf:params:jmap:mail:share": {},
                                            "urn:ietf:params:jmap:contacts": { "mayCreateAddressBook": true } } },
            "c": { "name": "colleague@example.com", "isPersonal": false, "isReadOnly": false,
                   "accountCapabilities": { "urn:ietf:params:jmap:mail": {},
                                            "urn:ietf:params:jmap:contacts": { "mayCreateAddressBook": false } } },
            "f": { "name": "files-only@example.com", "isPersonal": false, "isReadOnly": false,
                   "accountCapabilities": { "urn:ietf:params:jmap:filenode": { "mayCreateTopLevelFileNode": false } } }
          }
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `the session's other mail accounts are the shared ones, and your own is not among them`() {
        val all = sessionAccountsIn(session)
        assertEquals(4, all.size)
        val shared = sharedMailAccounts(all, "a")
        assertEquals(listOf("g", "c"), shared.map { it.id })
        assertEquals("sales@example.com", shared.first().name)
    }

    @Test
    fun `a group is read as owned and a colleague's grant is not`() {
        val byId = sessionAccountsIn(session).associateBy { it.id }
        assertTrue(byId.getValue("g").owner)
        assertFalse(byId.getValue("c").owner)
        assertTrue(byId.getValue("a").canShare)
        assertFalse(byId.getValue("c").canShare)
    }

    @Test
    fun `your own account is never shared even when the server forgets isPersonal`() {
        val forgetful = sessionAccountsIn(session).map { it.copy(personal = false) }
        assertEquals(listOf("g", "c"), sharedMailAccounts(forgetful, "a").map { it.id })
    }

    @Test
    fun `a broken session reads as no accounts rather than an error`() {
        assertEquals(emptyList(), sessionAccountsIn(JsonObject(emptyMap())))
    }

    @Test
    fun `a shared key names the login and the shared account and can be taken apart again`() {
        val key = sharedKey("me@example.com", "mail.example.com", "g")
        assertTrue(isSharedKey(key))
        assertEquals("me@example.com@mail.example.com", loginKeyOf(key))
        assertEquals("g", sharedAccountIdOf(key))
        assertFalse(isSharedKey("me@example.com@mail.example.com"))
        assertEquals("me@example.com@mail.example.com", loginKeyOf("me@example.com@mail.example.com"))
        assertNull(sharedAccountIdOf("me@example.com@mail.example.com"))
    }

    @Test
    fun `the account a shared mailbox is opened under has exactly its shared key`() {
        val login = SavedAccount("Me", "mail.example.com", "me@example.com")
        val group = sessionAccountsIn(session).first { it.id == "g" }
        val opened = sharedSavedAccount(login, group)
        // Session.key is the account's address, an @ and its server.
        assertEquals(sharedKey("me@example.com", "mail.example.com", "g"), "${opened.email}@${opened.server}")
        assertEquals("sales@example.com", opened.name)
    }

    @Test
    fun `the same group through two logins is two keys`() {
        assertFalse(
            sharedKey("me@example.com", "mail.example.com", "g") == sharedKey("other@example.com", "mail.example.com", "g"),
        )
    }

    // ---- every request carries the shared account's id --------------------------------

    private fun accountIdIn(invocation: JsonArray) = invocation[1].jsonObject["accountId"]?.jsonPrimitive?.content

    @Test
    fun `every request built for a shared account names that account`() {
        val built = listOf(
            mailboxesWithRights("g"),
            mailboxesWithRights("g", listOf("m1")),
            whereMessagesAre("g", listOf("e1", "e2")),
            shareFolder("g", "m1", "c", ShareLevel.READ),
            shareFolder("g", "m1", "c", null),
        )
        built.forEach { assertEquals("g", accountIdIn(it), it.toString()) }
    }

    @Test
    fun `principal lookups go to the login's own account`() {
        assertEquals("a", accountIdIn(principalByAddress("a", " someone@example.com ")))
        assertEquals("a", accountIdIn(principalsNamed("a", listOf("c"))))
        assertEquals(
            "someone@example.com",
            principalByAddress("a", " someone@example.com ")[1].jsonObject["filter"]!!.jsonObject["email"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `sharing patches one grantee and leaves everyone else alone`() {
        val update = shareFolder("a", "m1", "c", ShareLevel.READ_WRITE)[1].jsonObject["update"]!!.jsonObject["m1"]!!.jsonObject
        assertEquals(setOf("shareWith/c"), update.keys)
        assertEquals(ShareLevel.READ_WRITE.shareWithValue(), update["shareWith/c"])
        val removed = shareFolder("a", "m1", "c", null)[1].jsonObject["update"]!!.jsonObject["m1"]!!.jsonObject
        assertEquals(JsonNull, removed["shareWith/c"])
    }

    @Test
    fun `shareWith is asked for by name because Stalwart leaves it out otherwise`() {
        val properties = mailboxesWithRights("g")[1].jsonObject["properties"] as JsonArray
        assertTrue(JsonPrimitive("shareWith") in properties)
        assertTrue(JsonPrimitive("myRights") in properties)
    }

    // ---- answers ------------------------------------------------------------------------

    @Test
    fun `folders come back with rights, and one whose parent is hidden moves to the top`() {
        val answer = Json.parseToJsonElement(
            """
            ["Mailbox/get", { "accountId": "c", "list": [
              { "id": "m2", "name": "Clients", "parentId": "m9", "role": null, "unreadEmails": 3, "totalEmails": 10,
                "myRights": { "mayReadItems": true, "maySetSeen": false, "maySetKeywords": false }, "shareWith": null },
              { "id": "m1", "name": "Inbox", "role": "inbox", "unreadEmails": 1, "totalEmails": 2,
                "myRights": { "mayReadItems": true, "mayAddItems": true }, "shareWith": { "a": { "mayReadItems": true } } }
            ] }, "m"]
            """.trimIndent(),
        ) as JsonArray
        val folders = rightsFoldersIn(answer)
        assertEquals(listOf("m1", "m2"), folders.map { it.mailbox.id })
        val clients = folders.first { it.mailbox.id == "m2" }
        assertNull(clients.mailbox.parentId)
        assertEquals(3, clients.mailbox.unread)
        assertEquals(ShareLevel.READ, ShareLevel.of(clients.rights))
        assertNull(clients.shareWith)
        assertEquals(ShareLevel.READ, ShareLevel.of(folders.first().shareWith!!.getValue("a")))
    }

    @Test
    fun `a refused share gives the server's own sentence`() {
        val refused = Json.parseToJsonElement(
            """["Mailbox/set", { "notUpdated": { "m1": { "type": "forbidden", "description": "You are not allowed to change the permissions of this mailbox." } } }, "s"]""",
        ) as JsonArray
        assertEquals("You are not allowed to change the permissions of this mailbox.", shareRefusal(refused, "m1"))
        val done = Json.parseToJsonElement("""["Mailbox/set", { "updated": { "m1": null } }, "s"]""") as JsonArray
        assertNull(shareRefusal(done, "m1"))
    }

    @Test
    fun `a closed directory is explained rather than reported as an error code`() {
        assertTrue(principalLookupRefusal("The server refused the request: forbidden").contains("directory queries"))
    }
}
