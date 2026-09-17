package org.rampart

/**
 * Emoji, for a composer on a desktop.
 *
 * There is no picker on Windows worth relying on, the one built into the operating system
 * takes two hands and a keyboard shortcut nobody remembers, and a mail client that cannot
 * put a tick in a sentence is a mail client people keep a browser open next to.
 *
 * A fixed list rather than a library or a lookup table: the full set is several thousand,
 * this is the couple of hundred that turn up in mail, and a dependency for a list of
 * characters is a dependency for a list of characters.
 *
 * Rendering them is the operating system's job. Skia falls back to whichever font has the
 * glyph, which on Windows is Segoe UI Emoji and is already there.
 */
internal data class EmojiGroup(val name: String, val emoji: List<String>)

internal val EMOJI: List<EmojiGroup> = listOf(
    EmojiGroup(
        "Faces",
        ("😀 😃 😄 😁 😅 😂 🙂 🙃 😉 😊 😍 😘 😗 😚 😋 😜 🤪 🤨 🧐 🤓 😎 🥳 🙁 😕 😟 " +
            "😮 😯 😥 😢 😭 😤 😠 😡 🤯 😳 🥺 😬 🙄 😴 🤔 🤗 🤝 🙏 👏 👍 👎 👋 ✌️ 🤞 💪")
            .split(" "),
    ),
    EmojiGroup(
        "Work",
        ("✅ ❌ ⚠️ ❗ ❓ 📌 📎 📁 📂 📄 📃 📊 📈 📉 🗓️ ⏰ ⏳ 🔔 🔕 🔒 🔓 🔑 💡 🔍 ✏️ " +
            "📝 💼 🏢 🏠 📞 📧 💬 🚀 🎯 🛠️ ⚙️ 🧾 💳 💰 📦 🚚 ⭐ 🔥 ✨ 🎉 🎊 👀 🧠 ♻️")
            .split(" "),
    ),
    EmojiGroup(
        "Everything else",
        ("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 ☀️ 🌤️ ☁️ 🌧️ ⛈️ ❄️ 🌈 🌍 🗺️ 🍕 🍔 ☕ 🍺 🥂 🎂 🍰 " +
            "🐶 🐱 🌱 🌳 🚗 ✈️ 🏖️ ⚽ 🎵 📺 🎮 💻 🖨️ 📱 🔋 🧹 🧪 🎁 🏆 👷 🧰 🚧 🪜 🧱")
            .split(" "),
    ),
)

/**
 * Emoji whose name or group contains what was typed.
 *
 * There are no names here to search, so this matches the group instead, which is enough to
 * get from "work" to a tick and is the only thing a fixed list can honestly offer. Blank
 * gives everything, which is the normal case: people browse a picker, they do not query it.
 */
internal fun emojiFor(query: String): List<EmojiGroup> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return EMOJI
    return EMOJI.filter { it.name.lowercase().contains(needle) }.ifEmpty { EMOJI }
}
