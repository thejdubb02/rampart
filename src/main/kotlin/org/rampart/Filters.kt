package org.rampart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The rules that belong to every account, and which accounts have opted out of them.
 *
 * **These are not a second kind of filter.** They are ordinary Sieve rules, compiled into
 * each account's own script and saved to that account's server, so they run at delivery
 * with Rampart closed exactly as an account's own rules do. Nothing is evaluated here.
 *
 * Why a local file as well, when the rules are already on every server: this is the record
 * of what the set is *meant* to be. Without it, removing a rule means guessing which of
 * three servers is the authority, and an account that was offline on the day of a change
 * keeps a rule nobody can see any more. With it, every save pushes the same set out again
 * and drift is corrected rather than accumulated.
 *
 * The file can still be the thing that is missing: a fresh install on another machine has
 * no copy of it. That is why the rules are also marked in the metadata Bulwark's format
 * carries, and why [adopt] exists.
 */
internal data class GlobalFilters(
    val rules: List<Rule> = emptyList(),
    /**
     * Accounts the set is deliberately kept off, by account key.
     *
     * Exceptions rather than a list of accounts to include, so an account signed in
     * tomorrow gets the set without anybody going back to a screen to add it, which is
     * what "global" has to mean to be worth having.
     */
    val exceptions: Set<String> = emptySet(),
) {
    fun appliesTo(key: String): Boolean = key !in exceptions

    fun forAccount(key: String): List<Rule> = if (appliesTo(key)) rules else emptyList()
}

internal object Filters {
    fun read(): GlobalFilters {
        val saved = store.read()
        return GlobalFilters(
            rules = (saved["rules"] as? JsonArray).orEmpty().mapNotNull(::ruleOf),
            exceptions = (saved["exceptions"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull }.toSet(),
        )
    }

    fun write(value: GlobalFilters) = store.write {
        put("rules", buildJsonArray { value.rules.forEach { add(metaOf(it.copy(global = true))) } })
        put("exceptions", buildJsonArray { value.exceptions.forEach { add(JsonPrimitive(it)) } })
    }

    /**
     * Take the global rules an account's server already carries as the set, if there is no
     * set here.
     *
     * For the second machine, and for a reinstall. The rules are on the server and marked,
     * so the only thing actually missing is the local record, and refusing to recognise
     * them would show an empty global list next to three accounts visibly running the
     * rules, then delete them from all three on the first save.
     *
     * Only when empty. Once there is a set, the local file is the authority and a server
     * that disagrees is drift to be corrected, not a source.
     */
    fun adopt(script: Script) {
        val found = script.rules.filter { it.global }
        if (found.isEmpty()) return
        val current = read()
        if (current.rules.isNotEmpty()) return
        write(current.copy(rules = found))
    }

    private val store = JsonStore("filters.json")
}

/**
 * One account's script as it should be saved: the global rules, then its own.
 *
 * Global first because Sieve runs top down and a rule that stops processing has to be able
 * to mean it. A global "anything from this address is junk" that ran after an account rule
 * had already filed the message somewhere would be a rule that works on one account and
 * not another, for reasons nobody can see on the screen.
 *
 * Whatever the script already held as global is dropped rather than merged, because the
 * set passed in is the whole set. That is what makes removing a rule, and switching the
 * set off for one account, reach the server at all.
 */
internal fun scriptFor(own: Script, globals: List<Rule>): Script =
    own.copy(rules = globals.map { it.copy(global = true) } + ownRules(own))

/** An account's own rules: everything in its script that the global set did not put there. */
internal fun ownRules(script: Script): List<Rule> = script.rules.filterNot { it.global }

/**
 * Writes the global set into one account's script on its own server.
 *
 * The whole script is read first and written back, because the account's own rules and
 * anything in the script that no builder wrote have to survive a change to a set that has
 * nothing to do with them.
 *
 * False means the account cannot carry the set: either its server has no Sieve, or its
 * script was written by hand rather than by a builder. Rampart cannot rebuild one of those
 * without guessing at what it said, and guessing at somebody's delivery rules to deliver a
 * convenience is the wrong trade. It is returned rather than swallowed because a save that
 * quietly reached three accounts out of four is the kind of success nobody can see is not
 * one.
 */
internal fun pushGlobals(jmap: MailBackend, globals: List<Rule>): Boolean {
    if (!jmap.hasSieve()) return false
    val chosen = theOneRunning(jmap.sieveScripts())
    // Nothing to add and nothing to take away: an account with no script at all should not
    // be given an empty one for the sake of it.
    if (chosen == null && globals.isEmpty()) return true
    val own = if (chosen == null) Script(emptyList()) else scriptOf(jmap.sieveText(chosen))
    if (chosen != null && !own.editable) return false
    jmap.saveSieve(chosen?.name ?: "rampart", sieveOf(scriptFor(own, globals)), chosen)
    return true
}

/**
 * Which of a server's scripts to read and write.
 *
 * The active one, because that is the one actually filtering mail: a mailbox can hold
 * several and editing a script nobody activated looks exactly like a save that did
 * nothing. Failing that, ours, then whatever is there, then none, and the first save
 * writes a new one.
 */
internal fun theOneRunning(all: List<Jmap.SieveInfo>): Jmap.SieveInfo? =
    all.firstOrNull { it.active } ?: all.firstOrNull { it.name == "rampart" } ?: all.firstOrNull()
