package com.linmjt.whisperofsol.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AddressBook — local-only address book for quickly filling the recipient
 * field in SendWhisperScreen.
 *
 * v3.0.7: First release.
 * v3.0.8: Sort + frequency tracking + post-send prompt disable flag.
 *   - Added [AddressBookEntry.useCount] and [AddressBookEntry.lastUsedAt]
 *     for the "Frequent" and "Recent" sort orders.
 *   - Bumped on every successful send via [AddressBookPrefs.recordUse].
 *   - Added [AddressBookPrefs.isPostSendPromptDisabled] /
 *     [AddressBookPrefs.setPostSendPromptDisabled] so users can opt out
 *     of the post-send "save address?" dialog. Stored in the same
 *     SharedPreferences file as the entries, so it gets reset on
 *     `pm clear` (intentional — clearing app data is a "fresh start"
 *     signal that should re-enable every prompt).
 *
 * Design:
 *   - Stored in SharedPreferences (not Room) — entries are small (address +
 *     nickname, max ~100 chars each) and the access pattern is simple
 *     (read all / write one / delete one). Room would be overkill and add
 *     a 1.5 MB dep for no win.
 *   - **NOT encrypted** — addresses are public Solana base58 strings,
 *     nicknames are user-chosen local labels. No PII, no key material.
 *   - **Clearable**: lives in `whisper_address_book` prefs file, which is
 *     wiped on `pm clear` or Settings → Apps → Clear data. Per user
 *     request: clearing app data should remove the book too.
 *   - **No sync**: stays on device. If the user reinstalls the app or
 *     moves to a new phone, the book is gone.
 *
 * Limits:
 *   - Max 50 entries (enforced). Beyond that, oldest entry is evicted
 *     on add. We don't surface the cap to users.
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
    /** v3.0.8: bumped on every successful send to this address. */
    val useCount: Int = 0,
    /** v3.0.8: timestamp of the most recent successful send. Defaults to addedAt. */
    val lastUsedAt: Long = addedAt,
) {
    /** Display label: nickname if non-empty, else abbreviated address. */
    fun displayLabel(): String =
        if (nickname.isNotBlank()) nickname
        else if (address.length >= 12) "${address.take(4)}…${address.takeLast(4)}"
        else address

    /**
     * v3.0.8: Avatar background color derived from a hash of the nickname
     * (or address if nickname is blank). Picked from [AvatarPalette] so
     * each name gets a stable, recognizable color across sessions.
     */
    fun avatarColorIndex(): Int {
        val seed = if (nickname.isNotBlank()) nickname else address
        return (seed.hashCode() and Int.MAX_VALUE) % AvatarPalette.size
    }
}

/** v3.0.8: stable palette for entry avatars (matches the theme primaries). */
val AvatarPalette = listOf(
    0xFFEF4444.toInt(),  // Red500
    0xFF22C55E.toInt(),  // Green500
    0xFF3B82F6.toInt(),  // Blue500-ish (info)
    0xFFF59E0B.toInt(),  // Amber (warm)
    0xFF8B5CF6.toInt(),  // Violet
    0xFF14B8A6.toInt(),  // Teal
)

/** v3.0.8: sort order for the address book sheet. */
enum class AddressBookSort(val key: String) {
    /** Most recently used first (lastUsedAt desc, falls back to addedAt). */
    Recent("recent"),
    /** Alphabetical by nickname (lowercase). Empty nicknames go last. */
    Alphabetical("a-z"),
    /** Most-used first (useCount desc, ties broken by lastUsedAt desc). */
    Frequent("frequent");

    companion object {
        fun fromKey(k: String?): AddressBookSort =
            entries.firstOrNull { it.key == k } ?: Recent
    }
}

/**
 * AddressBookPrefs — SharedPreferences wrapper for the local address book
 * and the post-send prompt opt-out flag.
 *
 * Keys:
 *   - "entries_json" — JSON array of {address, nickname, addedAt, useCount, lastUsedAt}
 *   - "post_send_prompt_disabled" — Boolean. If true, the post-send
 *     "Save address?" dialog never appears (until app data is cleared).
 *
 * Why JSON in a single string (not one pref per entry):
 *   - Atomic writes (one prefs.edit().apply() per add/remove, no
 *     orphan entries on partial failure)
 *   - Simpler enumeration (one get → parse JSON → List<Entry>)
 *   - The cap is 50 entries × ~100 chars = ~5 KB total, well under
 *     the SharedPreferences practical limit (most apps store MBs).
 */
object AddressBookPrefs {
    private const val PREFS_NAME = "whisper_address_book"
    private const val KEY_ENTRIES = "entries_json"
    private const val KEY_POSTSEND_DISABLED = "post_send_prompt_disabled"
    private const val MAX_ENTRIES = 50

    /** Read all entries, default-sorted (by addedAt desc — newest first). */
    fun getAll(context: Context): List<AddressBookEntry> = parseStored(readRaw(context))

    /** Read all entries filtered by [query] (matches nickname or address substring, case-insensitive). */
    fun search(context: Context, query: String): List<AddressBookEntry> {
        val q = query.trim()
        if (q.isEmpty()) return getAll(context)
        return getAll(context).filter { e ->
            e.nickname.contains(q, ignoreCase = true) || e.address.contains(q, ignoreCase = true)
        }
    }

    /** v3.0.8: get entries sorted by [sort] (applies before search filter, so sheet keeps stable ordering). */
    fun getAllSorted(context: Context, sort: AddressBookSort): List<AddressBookEntry> {
        val all = getAll(context)
        return when (sort) {
            AddressBookSort.Recent       -> all.sortedWith(compareByDescending<AddressBookEntry> { it.lastUsedAt }.thenByDescending { it.addedAt })
            AddressBookSort.Alphabetical -> all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nickname.ifBlank { it.address } })
            AddressBookSort.Frequent     -> all.sortedWith(compareByDescending<AddressBookEntry> { it.useCount }.thenByDescending { it.lastUsedAt })
        }
    }

    /**
     * Add or update an entry (keyed by address — same address replaces
     * the existing nickname, refreshes addedAt to "now" so the
     * touched entry bubbles to the top of the quick-pick list).
     *
     * Use [refreshUsage] = false to add a fresh entry without bumping
     * lastUsedAt (which is what we want when the user manually adds
     * a new address — adding ≠ using).
     */
    fun add(context: Context, address: String, nickname: String) {
        val cleanAddr = address.trim()
        if (cleanAddr.isEmpty()) return
        val cleanNick = nickname.trim().take(40)
        val current = getAll(context).toMutableList()
        // Remove existing entry for this address (re-add refreshes timestamp)
        current.removeAll { it.address == cleanAddr }
        current.add(0, AddressBookEntry(cleanAddr, cleanNick))
        val trimmed = if (current.size > MAX_ENTRIES) current.take(MAX_ENTRIES) else current
        save(context, trimmed)
    }

    /** v3.0.8: edit an existing entry's nickname in place (keyed by address). No-op if address not found. */
    fun rename(context: Context, address: String, newNickname: String) {
        val cleanNick = newNickname.trim().take(40)
        val current = getAll(context).map { e ->
            if (e.address == address.trim()) e.copy(nickname = cleanNick) else e
        }
        save(context, current)
    }

    fun remove(context: Context, address: String) {
        val current = getAll(context).filter { it.address != address }
        save(context, current)
    }

    fun contains(context: Context, address: String): Boolean =
        getAll(context).any { it.address == address.trim() }

    /**
     * v3.0.8: bump useCount + lastUsedAt for [address]. Called after a
     * successful send so the address bubbles up in the "Frequent" and
     * "Recent" sort orders. No-op if the address is not in the book
     * (the post-send prompt is what adds it the first time).
     */
    fun recordUse(context: Context, address: String) {
        val now = System.currentTimeMillis()
        val cleanAddr = address.trim()
        val current = getAll(context).map { e ->
            if (e.address == cleanAddr) e.copy(useCount = e.useCount + 1, lastUsedAt = now) else e
        }
        save(context, current)
    }

    // ─── Post-send prompt opt-out ──────────────────────────────────────

    /** v3.0.8: true if the user has checked "Don't ask me again" on the post-send dialog. */
    fun isPostSendPromptDisabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_POSTSEND_DISABLED, false)

    /** v3.0.8: persist the opt-out. Persists until `pm clear` resets the prefs file. */
    fun setPostSendPromptDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_POSTSEND_DISABLED, disabled)
            .apply()
    }

    // ─── Internal ──────────────────────────────────────────────────────

    private fun readRaw(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: "[]"

    private fun parseStored(raw: String): List<AddressBookEntry> = try {
        val arr = JSONArray(raw)
        buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val addedAt = obj.optLong("addedAt", 0L)
                add(
                    AddressBookEntry(
                        address = obj.getString("address"),
                        nickname = obj.optString("nickname", ""),
                        addedAt = addedAt,
                        // v3.0.8 schema: useCount / lastUsedAt default to 0 / addedAt
                        // so entries written by v3.0.7 (no useCount key) still parse.
                        useCount = obj.optInt("useCount", 0),
                        lastUsedAt = obj.optLong("lastUsedAt", addedAt),
                    )
                )
            }
        }.sortedByDescending { it.addedAt }
    } catch (_: Exception) {
        // Corrupt JSON — clear and return empty so the UI doesn't crash.
        emptyList()
    }

    private fun save(context: Context, entries: List<AddressBookEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("address", e.address)
            obj.put("nickname", e.nickname)
            obj.put("addedAt", e.addedAt)
            obj.put("useCount", e.useCount)
            obj.put("lastUsedAt", e.lastUsedAt)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, arr.toString())
            .apply()
    }
}
