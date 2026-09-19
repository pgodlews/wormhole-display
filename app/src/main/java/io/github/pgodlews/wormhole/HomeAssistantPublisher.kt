package io.github.pgodlews.wormhole

import android.content.SharedPreferences
import android.util.Log
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Publishes AirPlay now-playing to Home Assistant so the Portal's HA dashboard and
 * screensaver can show "what's playing".
 *
 * Transport is the HA REST API (`POST /api/states/<entity>`) with a long-lived token —
 * no MQTT broker needed, and it writes the exact `media_*` attributes the dashboard's
 * template cards read. Cover art can't travel in a REST state, so it's served from a
 * tiny HTTP endpoint on the Portal and referenced by `entity_picture` — HA dashboard
 * clients fetch it directly over the LAN.
 *
 * All configuration lives in SharedPreferences (set at runtime, never compiled in):
 *   ha_url        e.g. http://homeassistant.local:8123
 *   ha_token      long-lived access token
 *   ha_entity     target entity_id (default sensor.portal_now_playing_airplay)
 *   ha_art_port   local art server port (default 8098)
 * The integration is a no-op until ha_url and ha_token are set.
 */
class HomeAssistantPublisher(private val prefs: SharedPreferences) {

    data class NowPlaying(
        val playing: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val year: Int = 0,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
    )

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "HA publisher") }
    private val state = AtomicReference(NowPlaying())
    private val art = AtomicReference<ByteArray?>(null)
    private var artContentType = "image/jpeg"
    @Volatile private var artVersion = 0
    @Volatile private var lastArtKey = ""      // title|artist|album the current art belongs to

    private val baseUrl get() = prefs.getString("ha_url", "")!!.trimEnd('/')
    private val token get() = prefs.getString("ha_token", "")!!
    private val entity get() = prefs.getString("ha_entity", DEFAULT_ENTITY)!!
    private val artPort get() = prefs.getInt("ha_art_port", DEFAULT_ART_PORT)
    // "ha_enabled" defaults to on when a URL and token exist, so configs made before the
    // enable toggle existed keep working.
    private val enabled get() = baseUrl.isNotBlank() && token.isNotBlank() &&
        prefs.getBoolean("ha_enabled", true)

    @Volatile private var artServer: ArtServer? = null
    @Volatile private var localIp: String = ""

    fun start(localIp: String) {
        this.localIp = localIp
        if (!enabled) {
            Log.i(TAG, "Home Assistant sync disabled (no ha_url/ha_token in prefs)")
            return
        }
        if (artServer == null) {
            artServer = runCatching { ArtServer(artPort).also { it.start() } }
                .onFailure { Log.w(TAG, "art server failed: ${it.message}") }
                .getOrNull()
        }
        Log.i(TAG, "Home Assistant sync -> $baseUrl entity=$entity art=http://$localIp:$artPort")
    }

    fun onMetadata(title: String, artist: String, album: String, year: Int) {
        val key = "$title|$artist|$album"
        if (key != lastArtKey) {
            // New track: drop stale art until the sender pushes the new cover.
            art.set(null)
            lastArtKey = key
        }
        state.updateAndGet { it.copy(playing = true, title = title, artist = artist, album = album, year = year) }
        push()
    }

    fun onCoverArt(bytes: ByteArray, isPng: Boolean) {
        art.set(bytes)
        artContentType = if (isPng) "image/png" else "image/jpeg"
        artVersion++
        push()
    }

    fun onProgress(positionSec: Double, durationSec: Double) {
        state.updateAndGet { it.copy(playing = true, positionSec = positionSec, durationSec = durationSec) }
        push()
    }

    /** Audio session started (metadata may not have arrived yet). */
    fun onSessionStart() {
        state.set(NowPlaying(playing = true))
        push()
    }

    /** Audio session ended: mark idle. */
    fun onSessionEnd() {
        state.set(NowPlaying(playing = false))
        art.set(null)
        lastArtKey = ""
        push()
    }

    private fun push() {
        if (!enabled) return
        val snap = state.get()
        val hasArt = art.get() != null
        val ip = localIp
        io.execute { postState(snap, hasArt, ip) }
    }

    private fun postState(np: NowPlaying, hasArt: Boolean, ip: String) {
        try {
            val attrs = JSONObject()
                .put("friendly_name", "Portal Now Playing (AirPlay)")
                .put("mp_state", if (np.playing) "playing" else "idle")
            if (np.playing) {
                if (np.title.isNotBlank()) attrs.put("media_title", np.title)
                if (np.artist.isNotBlank()) attrs.put("media_artist", np.artist)
                if (np.album.isNotBlank()) attrs.put("media_album_name", np.album)
                if (np.year > 0) attrs.put("media_year", np.year)
                if (np.durationSec > 0) attrs.put("media_duration", np.durationSec.toInt())
                if (np.durationSec > 0) attrs.put("media_position", np.positionSec.toInt())
                if (hasArt && ip.isNotBlank()) {
                    attrs.put("entity_picture", "http://$ip:$artPort/art.jpg?v=$artVersion")
                }
            }
            // State string: the dashboard treats off/idle/unknown as "nothing playing".
            val stateStr = if (np.playing) (np.title.ifBlank { "Playing" }) else "off"
            val body = JSONObject().put("state", stateStr).put("attributes", attrs).toString()

            val url = URL("$baseUrl/api/states/$entity")
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
                val code = responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "HA POST $entity -> $code")
                    errorStream?.close()
                } else {
                    inputStream.close()
                }
                disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "HA POST failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stop() {
        runCatching { onSessionEnd() }
        artServer?.close()
        artServer = null
    }

    /** Serves the latest cover art at GET /art.jpg for HA dashboard clients on the LAN. */
    private inner class ArtServer(port: Int) {
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "HA art") }
        @Volatile private var running = true

        fun start() {
            Thread({
                while (running) {
                    val sock = try { server.accept() } catch (e: Exception) { break }
                    pool.execute { serve(sock) }
                }
            }, "HA art accept").start()
        }

        private fun serve(sock: java.net.Socket) {
            sock.use {
                try {
                    // We don't parse the request beyond consuming the first line.
                    it.getInputStream().bufferedReader().readLine()
                    val img = art.get()
                    val out: OutputStream = it.getOutputStream()
                    if (img == null) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: $artContentType\r\n" +
                            "Content-Length: ${img.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                        out.write(header.toByteArray())
                        out.write(img)
                    }
                    out.flush()
                } catch (_: Exception) {
                }
            }
        }

        fun close() {
            running = false
            runCatching { server.close() }
            pool.shutdownNow()
        }
    }

    companion object {
        const val TAG = "WormholeHA"
        const val DEFAULT_ART_PORT = 8098
        const val DEFAULT_ENTITY = "sensor.wormhole_now_playing"
    }
}
