// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Who may control Tab Cast TV (0.3.0). Nothing is accepted from an unpaired device: the TV
 * shows a 6-digit PIN, the device sends it once and gets a long random token, which it presents from then on (link
 * HELLO, HTTP header or cookie). The TV keeps only SHA-256 hashes of the tokens; each device can be removed on the TV.
 *
 * Guessing is rate-limited: a PIN is valid for [PIN_MS] and [TRIES] attempts; after that it is gone, and pairing is
 * locked for [LOCK_MS]. A new PIN appears only on the TV's screen, so someone else on the same Wi-Fi can't pair.
 */
class Pairing(private val store: Store, private val clock: () -> Long = System::currentTimeMillis,
              private val random: SecureRandom = SecureRandom()) {

    data class Device(val id: String, val name: String, val kind: String, val tokenHash: String, val created: Long, val lastSeen: Long)

    /** Where the paired devices live (TV: SharedPreferences). */
    interface Store {
        fun load(): List<Device>
        fun save(devices: List<Device>)
    }

    sealed class Result {
        class Ok(val token: String, val device: Device) : Result()
        class Wrong(val triesLeft: Int) : Result()
        class Locked(val seconds: Int) : Result()
        object NoPin : Result()
    }

    private val lock = Any()
    private var devices: MutableList<Device> = store.load().toMutableList()
    private var pin: String? = null
    private var pinUntil = 0L
    private var tries = 0
    private var lockedUntil = 0L
    private var lastShown = 0L

    /** The TV shows (PIN, reason) or hides (null) the code. Called with the lock not held. */
    @Volatile var onPin: (String?, String?) -> Unit = { _, _ -> }

    val currentPin: String? get() = synchronized(lock) { pin?.takeIf { clock() < pinUntil } }

    /**
     * Shows a PIN on the TV (a new one unless one is still valid). [reason] says who asked ("Pair a device" on the TV,
     * or "a device at 192.0.2.10 wants to pair"). Device requests are limited to one per [KNOCK_MS]. Null while locked.
     */
    fun showPin(reason: String, fromDevice: Boolean = false): String? {
        val shown: String
        synchronized(lock) {
            val now = clock()
            if (now < lockedUntil) return null
            val cur = pin?.takeIf { now < pinUntil }
            if (cur != null) return cur
            if (fromDevice && now - lastShown < KNOCK_MS) return null
            shown = (random.nextInt(900_000) + 100_000).toString()
            pin = shown; pinUntil = now + PIN_MS; tries = 0; lastShown = now
        }
        onPin(shown, reason)
        return shown
    }

    fun hidePin() {
        synchronized(lock) { pin = null }
        onPin(null, null)
    }

    /** A device sends the PIN it read on the TV. */
    fun pair(entered: String, name: String, kind: String): Result {
        val result: Result
        var hide = false
        synchronized(lock) {
            val now = clock()
            if (now < lockedUntil) return Result.Locked(((lockedUntil - now + 999) / 1000).toInt())
            val p = pin?.takeIf { now < pinUntil } ?: return Result.NoPin
            if (!MessageDigest.isEqual(p.toByteArray(), entered.filter { it.isDigit() }.toByteArray())) {
                tries++
                if (tries >= TRIES) { pin = null; lockedUntil = now + LOCK_MS; hide = true; result = Result.Locked((LOCK_MS / 1000).toInt()) }
                else result = Result.Wrong(TRIES - tries)
            } else {
                val token = newToken()
                val d = Device(newToken().take(12), name.trim().take(60).ifEmpty { "device" }, kind.take(20), hash(token), now, now)
                devices.add(d)
                store.save(devices.toList())
                pin = null; hide = true
                result = Result.Ok(token, d)
            }
        }
        if (hide) onPin(null, null)
        return result
    }

    /** The device a token belongs to, or null. Updates "last seen" (saved at most every 10 minutes). */
    fun check(token: String?): Device? {
        if (token.isNullOrBlank() || token.length > 200) return null
        val h = hash(token.trim())
        synchronized(lock) {
            val i = devices.indexOfFirst { MessageDigest.isEqual(it.tokenHash.toByteArray(), h.toByteArray()) }
            if (i < 0) return null
            val d = devices[i]
            val now = clock()
            if (now - d.lastSeen > 10 * 60_000) { devices[i] = d.copy(lastSeen = now); store.save(devices.toList()) }
            return devices[i]
        }
    }

    /** A new token for an already paired device to hand on (e.g. an Apple Shortcut): its own entry, removable on its own. */
    fun issue(name: String, kind: String): Result.Ok = synchronized(lock) {
        val token = newToken()
        val now = clock()
        val d = Device(newToken().take(12), name.trim().take(60).ifEmpty { kind }, kind.take(20), hash(token), now, now)
        devices.add(d)
        store.save(devices.toList())
        Result.Ok(token, d)
    }

    fun devices(): List<Device> = synchronized(lock) { devices.toList() }

    fun revoke(id: String): Boolean = synchronized(lock) {
        val removed = devices.removeAll { it.id == id }
        if (removed) store.save(devices.toList())
        removed
    }

    private fun newToken(): String = ByteArray(32).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        const val PIN_MS = 120_000L
        const val TRIES = 5
        const val LOCK_MS = 60_000L
        const val KNOCK_MS = 10_000L

        fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
