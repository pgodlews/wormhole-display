package io.github.pgodlews.wormhole

import android.content.Context

/** A sender that completed AirPlay PIN pairing; [publicKey] is its base64 ed25519 key. */
data class PairedClient(
    val publicKey: String,
    val deviceId: String,
    val name: String
) {
    fun serialize(): String = "${escape(publicKey)}|${escape(deviceId)}|${escape(name)}"

    companion object {
        private fun escape(s: String): String =
            s.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A")

        private fun unescape(s: String): String =
            s.replace("%7C", "|").replace("%0A", "\n").replace("%25", "%")

        fun deserialize(str: String): PairedClient? {
            val parts = str.split("|", limit = 3)
            if (parts.size < 3 || parts[0].isBlank()) return null
            return PairedClient(unescape(parts[0]), unescape(parts[1]), unescape(parts[2]))
        }

        fun serializeAll(clients: Collection<PairedClient>): String =
            clients.joinToString("\n") { it.serialize() }

        fun deserializeAll(raw: String?): List<PairedClient> =
            raw.orEmpty().split("\n").mapNotNull { deserialize(it) }
    }
}

/**
 * Senders that paired with the PIN shown on screen. The native core asks [isPaired] when a
 * returning sender skips PIN entry (on its HTTP thread), so lookups stay in memory.
 */
class PairedClients(context: Context) {
    private val prefs = context.getSharedPreferences("paired_clients", Context.MODE_PRIVATE)
    private val clients = LinkedHashMap<String, PairedClient>()  // guarded by this

    init {
        PairedClient.deserializeAll(prefs.getString(KEY_CLIENTS, null)).forEach { clients[it.publicKey] = it }
    }

    @Synchronized
    fun isPaired(publicKey: String): Boolean = clients.containsKey(publicKey)

    @Synchronized
    fun count(): Int = clients.size

    @Synchronized
    fun add(client: PairedClient) {
        clients.remove(client.publicKey)
        clients[client.publicKey] = client
        while (clients.size > MAX_CLIENTS) clients.remove(clients.keys.first())
        prefs.edit().putString(KEY_CLIENTS, PairedClient.serializeAll(clients.values)).apply()
    }

    @Synchronized
    fun clear() {
        clients.clear()
        prefs.edit().remove(KEY_CLIENTS).apply()
    }

    companion object {
        private const val KEY_CLIENTS = "clients"
        const val MAX_CLIENTS = 32
    }
}
