package com.imad.downloader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.imad.downloader.core.ReleaseInfo
import com.imad.downloader.core.UpdateClient
import com.imad.downloader.core.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * In-app APK updates, the same way Imad Tunnel does them: read version.json, download with
 * progress, check the SHA-256 and that the APK is signed with this app's own certificate, then
 * hand it to Android's installer (asking once for "install unknown apps").
 */
class AppUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: UpdateClient = UpdateClient(),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null
    private val dir get() = File(context.filesDir, "updates")

    private val currentCode: Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    fun reset() { _state.value = UpdateState.Idle }

    fun check(onFound: (ReleaseInfo) -> Unit = {}) {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = runCatching { client.manifest().android }.fold(
                onSuccess = { r -> if (r != null && r.versionCode > currentCode) UpdateState.Available(r).also { onFound(r) } else UpdateState.UpToDate },
                onFailure = { UpdateState.Failed(it.message ?: "Could not check for updates") },
            )
        }
    }

    fun download(release: ReleaseInfo) {
        // A finished check may still be wrapping up; only a running download blocks a new one.
        if (_state.value is UpdateState.Downloading || _state.value == UpdateState.Verifying) return
        job?.cancel()
        _state.value = UpdateState.Downloading(release, 0, release.size)
        job = scope.launch {
            _state.value = runCatching {
                val f = client.download(release, dir) { d, t -> _state.value = UpdateState.Downloading(release, d, t) }
                _state.value = UpdateState.Verifying
                if (!signedLikeMe(f)) { f.delete(); error("The update is not signed by iDownloader. Not installing.") }
                f
            }.fold({ UpdateState.Ready(release, it) }, { UpdateState.Failed(it.message ?: "Download failed", release) })
        }
    }

    private fun signedLikeMe(apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val mine = certs(pm.getPackageInfo(context.packageName, flags))
        certs(archive).any { it in mine }
    }.getOrDefault(false)

    private fun certs(pi: PackageInfo): Set<String> {
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val s = pi.signingInfo ?: return emptySet()
            (if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory) ?: emptyArray()
        } else @Suppress("DEPRECATION") (pi.signatures ?: emptyArray())
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(file: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", file), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
