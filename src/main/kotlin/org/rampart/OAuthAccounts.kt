package org.rampart

import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth accounts from the point of view of the rest of Rampart: saved like any other IMAP
 * account, opened with a token instead of a password, and able to say "sign in again".
 *
 * No window in here, so the sign-in screen and the startup restore share one path, and the
 * window file (OAuthUi.kt) is only buttons and words.
 */
internal object OAuthAccounts {
    /** One per account, shared by everything that account opens. See [TokenKeeper]. */
    private val keepers = ConcurrentHashMap<String, TokenKeeper>()

    /**
     * Told when a provider refuses an account's sign-in, from whichever thread found out.
     * The window sets it; until it does, the account simply fails to open.
     */
    @Volatile
    var onSignInLost: (SavedAccount, OAuthFailure.SignInAgain) -> Unit = { _, _ -> }

    fun key(account: SavedAccount) = "${account.email}@${account.server}"

    /** The saved form of an account on a provider, with its preset servers. */
    fun accountFor(provider: OAuthProvider, email: String) = SavedAccount(
        name = email,
        server = withPort(provider.imapHost, provider.imapPort, 993),
        email = email,
        protocol = "imap",
        sendServer = withPort(provider.sendHostFor(email), provider.smtpPort, 587),
        oauth = provider.id,
    )

    /**
     * Kept in the credential store, or the reason it could not be.
     *
     * A store that is not there is not a reason to refuse the sign-in: the account works for
     * this session from memory, the way a password nobody chose to remember does.
     */
    fun store(account: SavedAccount, tokens: OAuthTokens): String? = Secrets.storeOAuthTokens(account, tokens.toJson())

    /**
     * Written to accounts.json, replacing any earlier entry for the same mailbox.
     *
     * Replacing rather than [Accounts.remember], which keeps an existing entry, because the
     * common case is a Gmail account that used to sign in with an app password and now signs
     * in through Google: the old entry has no provider on it and would be opened the old way.
     */
    fun remember(account: SavedAccount, path: java.nio.file.Path = Accounts.file()) {
        val others = Accounts.read(path).filterNot { it.server == account.server && it.email == account.email }
        Accounts.write(others + account, path)
    }

    /** The keeper for an account, made or handed new tokens after the browser has run. */
    fun keeperFor(account: SavedAccount, provider: OAuthProvider, client: OAuthClient, tokens: OAuthTokens): TokenKeeper =
        keepers.compute(key(account)) { _, existing ->
            existing?.also { it.replace(tokens, client) } ?: TokenKeeper(
                provider = provider,
                client = client,
                initial = tokens,
                save = { fresh -> store(account, fresh) },
                onSignInLost = { failure -> onSignInLost(account, failure) },
            )
        }!!

    /**
     * Opens the mailbox with the keeper's token as the IMAP and SMTP password.
     *
     * The token is asked for once to sign in and again every time a connection is made
     * afterwards, which is the whole difference from a password account.
     */
    fun open(account: SavedAccount, keeper: TokenKeeper): MailBackend = Imap.connect(
        host = hostOfServer(account.server),
        user = account.email,
        password = keeper.accessToken(),
        port = portOfServer(account.server) ?: 993,
        sendHost = hostOfServer(account.sendServer).ifBlank { hostOfServer(account.server) },
        sendPort = portOfServer(account.sendServer) ?: 587,
        bearer = keeper::accessToken,
    )

    /**
     * An account signed in on an earlier run, opened again from its stored tokens.
     *
     * Throws [OAuthFailure.SignInAgain] when there is nothing stored or the provider refuses
     * the refresh, which the caller shows as a button rather than an error. Anything else
     * that goes wrong is thrown as itself, a network failure included.
     */
    fun restore(account: SavedAccount): MailBackend {
        val provider = OAuthProviders.byId(account.oauth)
            ?: throw OAuthFailure.Refused("This account signs in through \"${account.oauth}\", which this version of Rampart does not know.")
        val client = OAuthClients.current(provider)
        if (!client.configured) throw OAuthFailure.Refused(notConfiguredSentence(provider))
        val tokens = Secrets.loadOAuthTokens(account)?.let(OAuthTokens::fromJson)
            ?: throw OAuthFailure.SignInAgain("Nothing is stored for this account's ${provider.name} sign-in, so it has to be done again in the browser.")
        return open(account, keeperFor(account, provider, client, tokens))
    }

    /** Signing out: the tokens go, and the keeper with them. */
    fun forget(account: SavedAccount) {
        keepers.remove(key(account))
        Secrets.forgetOAuthTokens(account)
    }
}

/**
 * Whether a mail server turned the token down, as opposed to not being reached.
 *
 * Signing in through the browser worked and the IMAP or SMTP server still said no. For Gmail
 * that is usually a Workspace administrator who has turned IMAP off; for Microsoft 365 it is
 * an organisation that has turned IMAP or authenticated SMTP off for its users. Neither is fixed by signing in again,
 * so it gets its own sentence.
 */
internal fun mailServerRefusedToken(provider: OAuthProvider, error: Throwable): String? {
    if (!passwordRejected(error)) return null
    return when (provider) {
        OAuthProviders.GOOGLE ->
            "Google signed you in, but Gmail's mail server turned the sign-in down. " +
                "On a work or school account, its administrator may have IMAP switched off."
        else ->
            "${provider.name} signed you in, but its mail server turned the sign-in down. " +
                "Your organisation may have IMAP or SMTP sign-in switched off for your account."
    }
}
