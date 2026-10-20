package com.hanzilock.sync

import com.hanzilock.AppCore
import com.hanzilock.core.Languages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Keeps your devices in step through a private GitHub repository (Settings > Sync): each device uploads
 * its own snapshot to devices/<device>.json.gz and merges the others' ([SyncEngine]). It syncs at start,
 * shortly after changes, every few minutes, and right away when a session is finished.
 */
class SyncManager(private val core: AppCore, private val defaultDeviceName: String) {
    sealed interface State {
        /** No repository or token set. */
        data object Off : State
        data object Syncing : State
        data class Done(val at: Long, val otherDevices: Int) : State
        data class Failed(val message: String) : State
    }

    private val settings get() = core.settings
    private val engine = SyncEngine(core)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(if (isOn) State.Done(settings.syncLastAt, 0) else State.Off)
    val state: StateFlow<State> = _state

    val isOn: Boolean get() = settings.syncRepo.isNotBlank() && settings.syncToken.isNotBlank()

    val deviceName: String get() = settings.syncDeviceName.ifBlank { defaultDeviceName }

    @OptIn(FlowPreview::class)
    fun start() {
        core.scope.launch {
            delay(3_000)
            while (true) {
                if (isOn) syncNow()
                delay(PERIOD_MS)
            }
        }
        // Changes made here (or merged in, which then cost one quick look) go out after a short pause.
        core.scope.launch {
            merge(
                core.words.changes.drop(1),
                core.stories.changes.drop(1),
                settings.changes.map { settings.shared().at }.distinctUntilChanged().drop(1),
            ).debounce(DEBOUNCE_MS).collect { if (isOn) syncNow() }
        }
        // A finished session unlocks your other devices: send it at once.
        core.scope.launch {
            settings.changes.map { settings.sharedCompletedAt }.distinctUntilChanged().drop(1).collect { if (isOn) syncNow() }
        }
    }

    /** Downloads what your other devices changed, merges it, and uploads this device's data. */
    suspend fun syncNow(): State = mutex.withLock {
        if (!isOn) return State.Off.also { _state.value = it }
        _state.value = State.Syncing
        val result = withContext(Dispatchers.IO) { runCatching { sync() } }
        _state.value = result.fold({ State.Done(settings.syncLastAt, it) }, { State.Failed(it.message ?: "Sync failed.") })
        _state.value
    }

    /** Returns how many other devices sync through the repository. */
    private fun sync(): Int {
        val store = GitHubStore(settings.syncRepo, settings.syncToken)
        val me = settings.syncDeviceId
        val entries = store.list(DIR).filter { it.name.endsWith(SUFFIX) }
        val seen = settings.syncSeen.toMutableMap()
        var others = 0
        for (e in entries) {
            val device = e.name.removeSuffix(SUFFIX)
            if (device == me) continue
            others++
            if (seen[device] == e.sha) continue
            val snapshot = SyncJson.decodeFromString<SyncSnapshot>(gunzip(store.download(e.path)).decodeToString())
            if (snapshot.format == SyncSnapshot.FORMAT) engine.merge(snapshot)
            seen[device] = e.sha
            settings.syncSeen = seen
        }
        installMissingLanguages()

        val snapshot = engine.export(me, deviceName)
        val hash = sha256(SyncJson.encodeToString(SyncSnapshot.serializer(), snapshot.copy(exportedAt = 0)))
        val own = entries.firstOrNull { it.name == me + SUFFIX }
        if (own == null || own.sha != settings.syncOwnSha || hash != settings.syncOwnHash) {
            val bytes = gzip(SyncJson.encodeToString(SyncSnapshot.serializer(), snapshot).encodeToByteArray())
            val path = "$DIR/$me$SUFFIX"
            val message = "Sync from $deviceName"
            val sha = try {
                store.upload(path, bytes, own?.sha, message)
            } catch (e: GitHubStore.Conflict) {
                store.upload(path, bytes, store.sha(path), message)
            }
            settings.syncOwnSha = sha
            settings.syncOwnHash = hash
        }
        settings.syncLastAt = System.currentTimeMillis()
        return others
    }

    /** A language added on another device brings its word sets here too (downloaded, like adding it here). */
    private fun installMissingLanguages() {
        for (profile in settings.addedLanguages) {
            val entry = Languages.CATALOG[profile.code]?.takeIf { it.packRepo != null } ?: continue
            if (core.words.sets(profile.code).isNotEmpty()) continue
            runCatching { core.languagePacks.install(entry) {} }
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val DIR = "devices"
        private const val SUFFIX = ".json.gz"
        private const val PERIOD_MS = 5 * 60_000L
        private const val DEBOUNCE_MS = 30_000L
    }
}
