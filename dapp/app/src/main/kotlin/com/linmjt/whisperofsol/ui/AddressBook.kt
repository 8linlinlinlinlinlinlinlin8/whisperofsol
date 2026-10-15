package com.linmjt.whisperofsol.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AddressBook — local-only address book for quickly filling the recipient
 * field in SendWhisperScreen.
 *
 * v3.0.7: First release.
 *
 * Design:
 *   - Stored in SharedPreferences (not Room) — entries are small (address +
 *     nickname, max ~100 chars each) and the access pattern is simple
 *     (read all / write one / delete one). Room would be overkill and add
 *     a 1.5 MB dep for no win.
 *   - **NOT encrypted** — addresses are public Solana base58 strings,
 *     nicknames are user-chosen local labels. No PII, no key material.
 *     (We DO NOT store the recipient's enc_pub or Whisper ID here — those
 *     are on-chain PDAs and auto-looked up at send time.)
 *   - **Clearable**: lives in `whisper_address_book` prefs file, which is
 *     wiped on `pm clear` or Settings → Apps → Clear data. Per user
 *     request: clearing app data should remove the book too.
 *   - **No sync**: stays on device. If the user reinstalls the app or
 *     moves to a new phone, the book is gone. This is intentional —
 *     the user wants a "quick send to friends" scratchpad, not a
 *     cross-device contact list.
 *
 * Limits:
 *   - Max 50 entries (enforced). Beyond that, oldest entry is evicted
 *     on add. We don't surface the cap to users — they almost certainly
 *     won't hit it for a friends list.
 *   - Address is validated as base58 (Solana wallet addresses are 32-44
 *     base58 chars). We trim whitespace but don't otherwise clean user
 *     input — if it's garbage, send will fail and show the same error
 *     it would for any invalid address.
 *   - Nickname: 1-40 chars, trimmed. Empty nicknames are stored as
 *     the truncated address (first 4 + "…" + last 4) as a fallback
 *     display label.
 */
data class AddressBookEntry(
    val address: String,
    val nickname: String,
    val addedAt: Long = System.currentTimeMillis(),
) {
    /** Display label: nickname if non-empty, else abbreviated address. */
    fun displayLabel(): String =
        if (nickname.isNotBlank()) nickname
        else if (address.length >= 12) "${address.take(4)}…${address.takeLast(4)}"
        else address
}

/**
 * AddressBookPrefs — SharedPreferences wrapper for the local address book.
 *
 * Key: "entries_json" — a JSON array of {address, nickname, addedAt} objects.
 * Why JSON in a single string (not one pref per entry):
 *   - Atomic writes (one prefs.edit().apply() per add/remove, no
 *     orphan entries on partial failure)
 *   - Simpler enumeration (one get → parse JSON → List<Entry>)
 *   - The cap is 50 entries × ~100 chars = ~5 KB total, well under
 *     the SharedPreferences practical limit (most apps store MBs).
 *
 * Used by:
 *   - SendWhisperScreen: quick-pick chips, "Save to address book" button
 *   - AddressBookScreen: full list + add/remove/edit
 *   - Settings: entry point to the full AddressBookScreen
 */
object AddressBookPrefs {
    private const val PREFS_NAME = "whisper_address_book"
    private const val KEY_ENTRIES = "entries_json"
    private const val MAX_ENTRIES = 50

    /** Read all entries, sorted by addedAt desc (newest first). */
    fun getAll(context: Context): List<AddressBookEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        AddressBookEntry(
                            address = obj.getString("address"),
                            nickname = obj.optString("nickname", ""),
                            addedAt = obj.optLong("addedAt", 0L),
                        )
                    )
                }
            }.sortedByDescending { it.addedAt }
        } catch (_: Exception) {
            // Corrupt JSON — clear and return empty so the UI doesn't crash.
            prefs.edit().remove(KEY_ENTRIES).apply()
            emptyList()
        }
    }

    /**
     * Add or update an entry (keyed by address — same address replaces
     * the existing nickname, refreshes addedAt to "now" so the
     * touched entry bubbles to the top of the quick-pick list).
     *
     * Enforces MAX_ENTRIES by evicting the oldest if the cap is hit.
     */
    fun add(context: Context, address: String, nickname: String) {
        val cleanAddr = address.trim()
        if (cleanAddr.isEmpty()) return
        val cleanNick = nickname.trim().take(40)
        val current = getAll(context).toMutableList()
        // Remove existing entry for this address (re-add refreshes timestamp)
        current.removeAll { it.address == cleanAddr }
        current.add(0, AddressBookEntry(cleanAddr, cleanNick))
        // Evict oldest if over cap
        val trimmed = if (current.size > MAX_ENTRIES) {
            current.take(MAX_ENTRIES)
        } else current
        save(context, trimmed)
    }

    fun remove(context: Context, address: String) {
        val current = getAll(context).filter { it.address != address }
        save(context, current)
    }

    fun contains(context: Context, address: String): Boolean =
        getAll(context).any { it.address == address.trim() }

    private fun save(context: Context, entries: List<AddressBookEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("address", e.address)
            obj.put("nickname", e.nickname)
            obj.put("addedAt", e.addedAt)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, arr.toString())
            .apply()
    }
}
