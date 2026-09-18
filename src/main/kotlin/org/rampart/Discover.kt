package org.rampart

import java.util.Hashtable
import javax.naming.directory.InitialDirContext

/**
 * Where a mail account actually lives, worked out from the address.
 *
 * Nobody knows their own IMAP hostname. Asking for one is how a mail client ends up being
 * set up by whoever installed it and by nobody else, and Rampart is meant to be installed
 * by strangers. So an address is the only thing asked for, and the hostnames are filled in
 * from what the domain publishes about itself.
 *
 * Every step is a guess that is then tried, never a claim. Discovery hands back an ordered
 * list of candidates and the sign-in screen signs in with the first that works, which is
 * also why the fields stay on the screen: a domain that publishes nothing still has an
 * owner who knows the answer.
 */
internal sealed interface Route {
    /** The one to prefer wherever it exists, and the only one with no second protocol. */
    data class Jmap(val server: String) : Route

    /** Reading and sending are two servers on IMAP, and they are often not the same host. */
    data class Imap(
        val host: String,
        val port: Int = 993,
        val sendHost: String = host,
        val sendPort: Int = 587,
    ) : Route
}

/**
 * The ways worth trying for an address, best first.
 *
 * JMAP before IMAP, because a server offering both offers strictly more through JMAP: push,
 * threading, server-side search across folders and a blob store, all of which IMAP either
 * lacks or fakes. A domain that answers on both is not a tie.
 *
 * [lookup] is the DNS side, separated so the ordering can be tested without a resolver.
 */
internal fun routesFor(email: String, lookup: (String) -> List<Srv> = ::srv): List<Route> {
    val domain = email.substringAfterLast('@').trim().lowercase().trim('.')
    if (domain.isBlank() || '.' !in domain) return emptyList()
    val routes = mutableListOf<Route>()

    // RFC 8620 section 2.2. A JMAP server is allowed to live somewhere other than the domain
    // in the address, and this is the only thing that says where.
    lookup("_jmap._tcp.$domain").forEach { routes += Route.Jmap(it.url()) }
    // The well-known path on the domain itself, which is what a small self-hosted setup
    // almost always is, and costs one request that 404s when it is not.
    routes += Route.Jmap(domain)

    // RFC 6186. Both are asked for because they are answered separately, and a domain that
    // publishes where to read but not where to send is ordinary rather than broken.
    val submission = lookup("_submission._tcp.$domain").firstOrNull()
    val imaps = lookup("_imaps._tcp.$domain")
    imaps.forEach {
        routes += Route.Imap(it.host, it.port, submission?.host ?: it.host, submission?.port ?: 587)
    }

    // What everybody names them when they publish nothing. Tried in the order that guesses
    // right most often, and the domain itself last because a web server answering on 993 is
    // the least likely of the three.
    listOf("imap", "mail").forEach { prefix ->
        routes += Route.Imap(
            host = "$prefix.$domain",
            sendHost = submission?.host ?: "smtp.$domain",
            sendPort = submission?.port ?: 587,
        )
    }
    routes += Route.Imap(domain, sendHost = submission?.host ?: domain)

    return routes.distinct()
}

/** One SRV record, reduced to the two fields that matter here. */
internal data class Srv(val host: String, val port: Int, val priority: Int, val weight: Int) {
    /** An SRV pointing at a non-default port has to keep it, and JMAP is spoken over https. */
    fun url(): String = if (port == 443) host else "$host:$port"
}

/**
 * The SRV records for a name, best first, or nothing at all.
 *
 * A target of "." is the published way to say the service is deliberately not offered here
 * (RFC 2782), so it is dropped rather than turned into a hostname of "".
 *
 * Never throws. A domain with no records, a resolver that is not answering and a network
 * that is not there are all the same answer to the question being asked, which is "is there
 * anything published for this domain", and the fallback guesses are tried either way.
 */
internal fun srv(name: String): List<Srv> = runCatching {
    val environment = Hashtable<String, String>().apply {
        put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
        // A name that does not resolve should cost a moment, not the whole sign-in. The
        // default is a second per server with two retries, on a screen somebody is waiting at.
        put("com.sun.jndi.dns.timeout.initial", "1500")
        put("com.sun.jndi.dns.timeout.retries", "1")
    }
    val attributes = InitialDirContext(environment).getAttributes(name, arrayOf("SRV"))
    val records = attributes.get("SRV")?.all ?: return emptyList()
    buildList {
        while (records.hasMore()) parseSrv(records.next().toString())?.let { add(it) }
    }.sortedWith(compareBy({ it.priority }, { -it.weight }))
}.getOrDefault(emptyList())

/**
 * One record as the resolver prints it: priority, weight, port, target.
 *
 * Split out from the lookup because this is the half that can be wrong in a way worth a
 * test, and the half that cannot be exercised without a resolver is then four lines.
 */
internal fun parseSrv(record: String): Srv? {
    val parts = record.trim().split(Regex("\\s+"))
    if (parts.size < 4) return null
    val target = parts[3].trim().trimEnd('.')
    if (target.isBlank() || parts[3].trim() == ".") return null
    val port = parts[2].toIntOrNull() ?: return null
    return Srv(target, port, parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
}
