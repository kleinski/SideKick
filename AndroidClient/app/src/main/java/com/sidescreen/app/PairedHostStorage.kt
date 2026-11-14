package com.sidescreen.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class PairedHostStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("paired_host", Context.MODE_PRIVATE)

    data class Entry(
        val host: String,
        val port: Int,
        val token: ByteArray,
        val macName: String,
        val hostId: String = deriveId(macName, token),
        val lastConnected: Long = System.currentTimeMillis(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (other !is Entry) return false
            return host == other.host &&
                port == other.port &&
                macName == other.macName &&
                hostId == other.hostId &&
                token.contentEquals(other.token)
        }

        override fun hashCode(): Int =
            (((host.hashCode() * 31 + port) * 31 + macName.hashCode()) * 31 + hostId.hashCode()) * 31 +
                token.contentHashCode()
    }

    init {
        migrateLegacySingleEntryIfNeeded()
    }

    /**
     * Migrates the single-host storage keys ("host", "port", "token_b64", "mac_name")
     * from versions <= 1.1.1 into the multi-host JSON list ("paired_hosts_json").
     */
    private fun migrateLegacySingleEntryIfNeeded() {
        if (prefs.contains(KEY_HOSTS_JSON)) return
        val legacy = loadLegacySingle() ?: return
        saveAll(listOf(legacy))
    }

    private fun loadLegacySingle(): Entry? {
        val host = prefs.getString("host", null) ?: return null
        val port = prefs.getInt("port", -1).takeIf { it > 0 } ?: return null
        val tokenB64 = prefs.getString("token_b64", null) ?: return null
        val token =
            try {
                Base64.decode(tokenB64, Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (e: IllegalArgumentException) {
                return null
            }
        if (token.size != 32) return null
        val macName = prefs.getString("mac_name", null) ?: "Mac"
        return Entry(
            host = host,
            port = port,
            token = token,
            macName = macName,
            hostId = deriveId(macName, token),
            lastConnected = System.currentTimeMillis(),
        )
    }

    /**
     * Returns all paired Macs sorted by [Entry.lastConnected] descending
     * (most recently used Mac first).
     */
    fun loadAll(): List<Entry> {
        migrateLegacySingleEntryIfNeeded()
        val raw = prefs.getString(KEY_HOSTS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val host = obj.optString("host", "").takeIf { it.isNotEmpty() } ?: continue
                val port = obj.optInt("port", -1).takeIf { it in 1..65535 } ?: continue
                val tokenB64 = obj.optString("token_b64", "").takeIf { it.isNotEmpty() } ?: continue
                val token =
                    try {
                        Base64.decode(tokenB64, Base64.NO_WRAP or Base64.NO_PADDING)
                    } catch (e: IllegalArgumentException) {
                        continue
                    }
                if (token.size != 32) continue
                val macName = obj.optString("mac_name", "Mac").ifBlank { "Mac" }
                val hostId = obj.optString("host_id", "").ifBlank { deriveId(macName, token) }
                val lastConnected = obj.optLong("last_connected", 0L)
                result.add(Entry(host, port, token, macName, hostId, lastConnected))
            }
            result.sortedByDescending { it.lastConnected }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Returns the most recently connected Mac (first in [loadAll]), or null if none paired.
     */
    fun load(): Entry? = loadAll().firstOrNull()

    /**
     * Adds or updates a paired Mac in the multi-host list without overwriting other Macs.
     * Matches an existing Mac by `hostId`, or by `macName`, or by `token` equality.
     */
    fun save(entry: Entry) {
        val current = loadAll().toMutableList()
        val resolvedId = entry.hostId.ifBlank { deriveId(entry.macName, entry.token) }
        val updated =
            entry.copy(
                hostId = resolvedId,
                lastConnected = System.currentTimeMillis(),
            )
        current.removeAll { existing ->
            existing.hostId == updated.hostId ||
                existing.macName.equals(updated.macName, ignoreCase = true) ||
                existing.token.contentEquals(updated.token)
        }
        current.add(0, updated)
        saveAll(current)
    }

    /**
     * Updates the stored IP/port for an already-paired Mac discovered via mDNS or Handover,
     * preserving its `lastConnected` order unless [touchLastConnected] is true.
     */
    fun updateEndpoint(
        hostIdOrName: String,
        newHost: String,
        newPort: Int? = null,
        touchLastConnected: Boolean = false,
    ): Entry? {
        val current = loadAll().toMutableList()
        val idx =
            current.indexOfFirst {
                it.hostId == hostIdOrName || it.macName.equals(hostIdOrName, ignoreCase = true)
            }
        if (idx < 0) return null
        val old = current[idx]
        val updated =
            old.copy(
                host = newHost,
                port = newPort ?: old.port,
                lastConnected = if (touchLastConnected) System.currentTimeMillis() else old.lastConnected,
            )
        current[idx] = updated
        saveAll(current)
        return updated
    }

    fun markConnected(entry: Entry) {
        save(entry.copy(lastConnected = System.currentTimeMillis()))
    }

    fun remove(hostId: String) {
        val current = loadAll().filterNot { it.hostId == hostId }
        saveAll(current)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun saveAll(entries: List<Entry>) {
        val sorted = entries.sortedByDescending { it.lastConnected }
        val arr = JSONArray()
        for (e in sorted) {
            val obj =
                JSONObject().apply {
                    put("host", e.host)
                    put("port", e.port)
                    put("token_b64", Base64.encodeToString(e.token, Base64.NO_WRAP or Base64.NO_PADDING))
                    put("mac_name", e.macName)
                    put("host_id", e.hostId)
                    put("last_connected", e.lastConnected)
                }
            arr.put(obj)
        }
        val editor = prefs.edit().putString(KEY_HOSTS_JSON, arr.toString())
        val primary = sorted.firstOrNull()
        if (primary != null) {
            editor
                .putString("host", primary.host)
                .putInt("port", primary.port)
                .putString("token_b64", Base64.encodeToString(primary.token, Base64.NO_WRAP or Base64.NO_PADDING))
                .putString("mac_name", primary.macName)
        } else {
            editor
                .remove("host")
                .remove("port")
                .remove("token_b64")
                .remove("mac_name")
        }
        editor.apply()
    }

    companion object {
        private const val KEY_HOSTS_JSON = "paired_hosts_json"

        fun deriveId(
            macName: String,
            token: ByteArray,
        ): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(macName.lowercase().toByteArray(Charsets.UTF_8))
                digest.update(token)
                digest.digest().take(8).joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                macName.lowercase()
            }
        }
    }
}
