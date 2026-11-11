package org.openstrap.app.update

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openstrap.app.BuildConfig
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.catching
import org.openstrap.app.data.settings.SettingsStore
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Why an install stopped. */
enum class InstallFailure { Network, Checksum, NoChecksum, NotOpenStrap, Signature, Recording, Storage, Android }

/** Where installing a release stands. */
sealed interface Install {
    data object Idle : Install
    /** [done] of [total] bytes, [total] 0 when unknown. */
    data class Downloading(val tag: String, val done: Long, val total: Long) : Install
    /** Handed to Android, which asks the user when it needs to, then replaces the app and closes it. */
    data class Installing(val tag: String) : Install
    data class Failed(val tag: String, val failure: InstallFailure, val detail: String? = null) : Install
}

/** Where reading the release list stands: one value, so "checking" and "failed" can't both be true. */
sealed interface Check {
    data object Idle : Check
    data object Checking : Check
    /** The last check couldn't reach GitHub or read its reply. */
    data object Failed : Check
}

data class UpdateState(
    /** Every release GitHub listed at the last check (kept across restarts), newest first. */
    val releases: List<Release> = emptyList(),
    /** When that check was, epoch seconds. */
    val checkedAt: Long? = null,
    val check: Check = Check.Idle,
    val install: Install = Install.Idle,
)

/**
 * New versions of OpenStrap from its GitHub releases. Once a day, unless turned off on the Updates page, it reads the
 * release list (an anonymous GET to api.github.com, nothing else sent) and notifies once per new release. Install
 * downloads that release's APK, checks it against GitHub's SHA-256 and that it's OpenStrap signed with the same key,
 * then hands it to Android's package installer. Never while a workout records, since installing closes the app.
 */
class AppUpdater(
    private val app: Application,
    private val settings: SettingsStore,
    private val recording: () -> Boolean,
    /** The app's scope ([org.openstrap.app.AppContainer.appScope]): a download carries on when the page closes. */
    private val scope: CoroutineScope,
) {
    private val cache = File(app.filesDir, "releases.json")
    private val checkLock = Mutex()
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state

    /** The version running, "2.1.0". */
    val installed: String = BuildConfig.VERSION_NAME

    /** A newer release the Updates page hasn't shown yet ([Releases.waiting]): the avatar and the Updates row carry a dot. */
    val waiting: StateFlow<Boolean> = combine(_state, settings.updateSeen) { s, seen -> Releases.waiting(s.releases, installed, seen) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** The Updates page is showing: the newest release counts as seen, and the dots go. */
    fun markSeen() {
        val newest = Releases.newer(_state.value.releases, installed).firstOrNull() ?: return
        scope.launch { settings.setUpdateSeen(newest.tag) }
    }

    init {
        // The list from the last check, so the Updates page and its notes show before (or without) a new one.
        scope.launch {
            val cached = withContext(Dispatchers.IO) { runCatching { Releases.parse(cache.readText()) }.getOrNull() }.orEmpty()
            val checked = settings.updateChecked.first()
            _state.update { it.copy(releases = it.releases.ifEmpty { cached }, checkedAt = it.checkedAt ?: checked) }
        }
    }

    /** Sets the daily check going, or stops it, as the switch says; each app start. */
    fun start() {
        scope.launch { if (settings.updateAuto.first()) schedule(app) else cancel(app) }
        reconcile()
    }

    /** True from creating the installer session until it is committed, when no session is yet there to find. */
    @Volatile private var committing = false

    /**
     * Brings the saved "installing" tag and [Install.Installing] in line with what Android has. Each app start, and when the
     * Updates page comes back to the front (the user may have walked away from Android's confirm screen):
     * - the tag is for this version or an older one: the update is in, the tag goes;
     * - no installer session of ours is open: nothing is installing, so the tag goes and the page is back to Idle;
     * - a session is open and memory says Idle (the process died after the commit): the page shows Installing again.
     */
    fun reconcile() {
        scope.launch {
            if (committing) return@launch
            val tag = (_state.value.install as? Install.Installing)?.tag ?: settings.updateInstalling() ?: return@launch
            val target = Releases.version(tag)
            val mine = Releases.version(installed)
            val inThere = target != null && mine != null && Releases.compare(target, mine) <= 0
            val live = !inThere && withContext(Dispatchers.IO) { sessionOpen() }
            if (committing) return@launch
            when {
                live -> _state.update { if (it.install is Install.Idle) it.copy(install = Install.Installing(tag)) else it }
                else -> {
                    settings.setUpdateInstalling(null)
                    _state.update { if (it.install is Install.Installing) it.copy(install = Install.Idle) else it }
                }
            }
        }
    }

    /** An installer session of this app is open: committed or not, Android hasn't finished with it. */
    private fun sessionOpen(): Boolean = runCatching {
        app.packageManager.packageInstaller.mySessions.any { it.appPackageName == app.packageName }
    }.getOrDefault(false)

    fun setAuto(on: Boolean) {
        scope.launch {
            settings.setUpdateAuto(on)
            if (on) schedule(app) else cancel(app)
        }
    }

    fun checkNow() {
        scope.launch { check() }
    }

    /** Reads the release list from GitHub; true when it got one. */
    suspend fun check(): Boolean = checkLock.withLock {
        _state.update { it.copy(check = Check.Checking) }
        try {
            catching {
                val json = get(Releases.API, github = true)
                val releases = Releases.parse(json)
                withContext(Dispatchers.IO) {
                    val tmp = File(app.filesDir, "releases.json.tmp")
                    tmp.writeText(json)
                    tmp.renameTo(cache)
                }
                val now = System.currentTimeMillis() / 1000
                settings.setUpdateChecked(now)
                _state.update { it.copy(releases = releases, checkedAt = now, check = Check.Idle) }
                true
            }.getOrElse { e ->
                Log.i(TAG, "Update check failed: $e")
                _state.update { it.copy(check = Check.Failed) }
                false
            }
        } finally {
            // Cancelled too: the page doesn't stay on Checking.
            _state.update { if (it.check == Check.Checking) it.copy(check = Check.Idle) else it }
        }
    }

    /** The daily check: reads the list, then notifies about the newest release once, if it's newer than this one. */
    suspend fun dailyCheck() {
        if (!check()) return
        val newest = Releases.newer(_state.value.releases, installed).firstOrNull() ?: return
        if (settings.updateNotified() == newest.tag) return
        UpdateNotifier(app).available(newest)
        settings.setUpdateNotified(newest.tag)
    }

    /** Android lets OpenStrap install apps; when it doesn't, [allowInstalls] opens the switch for it. */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    val allowInstalls: Intent
        get() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))

    /** Downloads [release]'s APK, checks it, and hands it to Android to install. A no-op while one is under way. */
    fun install(release: Release) {
        val busy = _state.value.install.let { it is Install.Downloading || it is Install.Installing }
        val apk = release.apk
        if (busy || apk == null) return
        _state.update { it.copy(install = Install.Downloading(release.tag, 0, apk.size)) }
        scope.launch {
            val tag = release.tag
            catching {
                if (recording()) throw Stop(InstallFailure.Recording)
                val file = download(release, apk)
                checkArchive(file)
                // The download can outlast a workout's start.
                if (recording()) throw Stop(InstallFailure.Recording)
                committing = true
                _state.update { it.copy(install = Install.Installing(tag)) }
                settings.setUpdateInstalling(tag)
                commit(file)
                watchInstalling(tag)
            }.onFailure { e ->
                val failure = failureFor(e)
                if (e !is Stop) Log.i(TAG, "Update install failed ($failure): $e")
                fail(tag, failure, e.message.takeIf { failure == InstallFailure.Android })
            }
            committing = false
        }
    }

    /**
     * Installing has a way out: when the user ignores Android's confirm screen nothing is ever reported, so after
     * [INSTALL_TIMEOUT_MS] the open session is abandoned and the page goes back to Idle.
     */
    private fun watchInstalling(tag: String) {
        scope.launch {
            delay(INSTALL_TIMEOUT_MS)
            if ((_state.value.install as? Install.Installing)?.tag != tag) return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    val installer = app.packageManager.packageInstaller
                    installer.mySessions.filter { it.appPackageName == app.packageName }.forEach { installer.abandonSession(it.sessionId) }
                }
            }
            settings.setUpdateInstalling(null)
            _state.update { if ((it.install as? Install.Installing)?.tag == tag) it.copy(install = Install.Idle) else it }
        }
    }

    /** Clears a failed install's message, when the page is left. */
    fun dismissFailure() {
        _state.update { if (it.install is Install.Failed) it.copy(install = Install.Idle) else it }
    }

    /**
     * What Android's package installer reported ([UpdateReceiver]). The tag is the one in memory, or the saved one when the
     * process died after the commit, so a failure then is shown and the saved tag cleared instead of lost.
     */
    internal fun installResult(context: Context, status: Int, message: String?, confirm: Intent?) {
        when (status) {
            // Android asks the user first: its own confirmation screen.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let {
                runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { e -> withTag { t -> fail(t, InstallFailure.Android, e.message) } }
            }
            // On success Android closes the app, so this is rarely seen.
            PackageInstaller.STATUS_SUCCESS -> _state.update { it.copy(install = Install.Idle) }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                _state.update { it.copy(install = Install.Idle) }
                scope.launch { settings.setUpdateInstalling(null) }
            }
            else -> {
                val failure = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallFailure.Signature
                    PackageInstaller.STATUS_FAILURE_STORAGE -> InstallFailure.Storage
                    else -> InstallFailure.Android
                }
                Log.i(TAG, "Update install failed: status $status, $message")
                withTag { t -> fail(t, failure, message) }
            }
        }
    }

    /** Runs [block] with the tag being installed: from memory, or else the saved one. Nothing when neither is there. */
    private fun withTag(block: (String) -> Unit) {
        val tag = (_state.value.install as? Install.Installing)?.tag
        if (tag != null) return block(tag)
        scope.launch { settings.updateInstalling()?.let(block) }
    }

    private fun fail(tag: String, failure: InstallFailure, detail: String? = null) {
        _state.update { it.copy(install = Install.Failed(tag, failure, detail)) }
        scope.launch { settings.setUpdateInstalling(null) }
    }

    private class Stop(val failure: InstallFailure) : Exception(failure.name)

    /** Why [e] stopped an install: one of the checks below, the network, or anything else Android's to blame for. */
    private fun failureFor(e: Throwable): InstallFailure = when (e) {
        is Stop -> e.failure
        is IOException -> InstallFailure.Network
        else -> InstallFailure.Android
    }

    /** The APK in the cache, downloaded unless an earlier, matching copy is there, and its SHA-256 checked. */
    private suspend fun download(release: Release, apk: ReleaseAsset): File = withContext(Dispatchers.IO) {
        urlFailure(apk.url)?.let { throw Stop(it) }
        val expected = apk.sha256
            ?: release.sums?.takeIf { urlFailure(it.url) == null }?.let { Releases.sumFor(get(it.url), apk.name) }
            ?: throw Stop(InstallFailure.NoChecksum)
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        // Only the one being installed is kept.
        dir.listFiles()?.filter { it.name != apk.name }?.forEach { it.delete() }
        val file = File(dir, apk.name)
        if (file.exists() && checksumFailure(expected, sha256(file)) == null) return@withContext file

        val tmp = File(dir, "${apk.name}.part")
        val conn = URL(apk.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "unstrap/$installed")
            if (conn.responseCode !in 200..299) throw IOException("Download returned HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: apk.size
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        // A step per percent, not per read.
                        val percent = if (total > 0) done * 100 / total else done / 1_000_000
                        if (percent != shown) {
                            shown = percent
                            _state.update { it.copy(install = Install.Downloading(release.tag, done, total)) }
                        }
                    }
                }
            }
            checksumFailure(expected, hex(digest.digest()))?.let {
                tmp.delete()
                throw Stop(it)
            }
            if (!tmp.renameTo(file)) throw IOException("Couldn't keep the download")
            file
        } finally {
            conn.disconnect()
        }
    }

    /** The APK is OpenStrap, signed with the key this copy was signed with, or Android would refuse it anyway. */
    private fun checkArchive(file: File) {
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val mine = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val failure = archiveFailure(archive?.packageName, app.packageName, archive?.let(::signers).orEmpty(), signers(mine))
        if (failure != null) throw Stop(failure)
    }

    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().mapTo(HashSet()) { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }

    /**
     * One package installer session for [file], which Android reports on to [UpdateReceiver]. A session that fails before its
     * commit is abandoned, or it would stay open, holding its copy of the APK, until Android cleans up (audit E12).
     */
    internal suspend fun commit(file: File) = withContext(Dispatchers.IO) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            // Without a prompt when Android allows it (OpenStrap installed the copy it replaces, Android 12+); it asks otherwise.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_INSTALL_STATUS)
                // Mutable, so Android can put the status in it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
                committed = true
            }
        } finally {
            if (!committed) runCatching { installer.abandonSession(id) }.onFailure { Log.w(TAG, "Update session $id not abandoned: $it") }
        }
    }

    /** A small text file (SHA256SUMS.txt) or GitHub's API reply. */
    private suspend fun get(url: String, github: Boolean = false): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "unstrap/$installed")
            if (github) {
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from $url")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "unstrap"
        private const val DAILY = "update-check"

        /** How long Installing waits for Android's report before the page goes back to Idle. */
        internal const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

        /** Starts the daily check; a no-op when it's already scheduled. Runs only with a network. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DAILY)
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** A download address OpenStrap fetches from: HTTPS with a host, nothing else. Null when [url] is one. */
        internal fun urlFailure(url: String): InstallFailure? {
            val uri = runCatching { URI(url) }.getOrNull()
            val ok = uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
            return if (ok) null else InstallFailure.NotOpenStrap
        }

        /** The download's SHA-256, [actual], against GitHub's, [expected]. Null when they match. */
        internal fun checksumFailure(expected: String, actual: String): InstallFailure? =
            if (expected.isNotBlank() && actual.equals(expected.trim(), ignoreCase = true)) null else InstallFailure.Checksum

        /**
         * The downloaded APK is OpenStrap, [archivePackage] the [installedPackage], and signed with the very keys this
         * copy was ([installedSigners]). Null when it is. Android would refuse it otherwise, after the download.
         */
        internal fun archiveFailure(
            archivePackage: String?,
            installedPackage: String,
            archiveSigners: Set<String>,
            installedSigners: Set<String>,
        ): InstallFailure? =
            when {
                archivePackage == null || archivePackage != installedPackage -> InstallFailure.NotOpenStrap
                archiveSigners.isEmpty() || archiveSigners != installedSigners -> InstallFailure.Signature
                else -> null
            }
    }
}

/** The daily check ([AppUpdater.schedule]). A failed one waits for tomorrow's. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OpenStrapApp).container
        if (container.settings.updateAuto.first()) container.updater.dailyCheck()
        return Result.success()
    }
}
