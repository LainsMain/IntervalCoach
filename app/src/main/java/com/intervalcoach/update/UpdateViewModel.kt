package com.intervalcoach.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class UpdateUiState(
    val checking: Boolean = true,
    val release: ReleaseInfo? = null,
    val available: Boolean = false,
    val progress: Int? = null,
    val downloadedFile: File? = null,
    val message: String? = null
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val client = UpdateClient(application)
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    init { check() }
    fun check() {
        if (mutableState.value.progress != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(checking = true, message = null)
            try {
                val context = getApplication<Application>()
                val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                val release = client.latest()
                val available = isNewerVersion(release.version, installed)
                mutableState.value = UpdateUiState(checking = false, release = release, available = available,
                    message = if (available) null else "You're up to date")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.value = mutableState.value.copy(checking = false, message = e.message ?: "Could not check for updates")
            }
        }
    }
    fun download() {
        val release = mutableState.value.release?.takeIf { mutableState.value.available } ?: return
        if (mutableState.value.progress != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(progress = 0, message = null, downloadedFile = null)
            try {
                val file = client.download(release) { progress ->
                    mutableState.value = mutableState.value.copy(progress = progress)
                }
                mutableState.value = mutableState.value.copy(progress = null, downloadedFile = file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.value = mutableState.value.copy(progress = null, message = e.message ?: "Download failed")
            }
        }
    }
    fun installIntent(): Intent? {
        val context = getApplication<Application>()
        val file = mutableState.value.downloadedFile?.takeIf { it.isFile } ?: run {
            mutableState.value = mutableState.value.copy(downloadedFile = null, message = "Download expired. Download again.")
            return null
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updatefiles", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun permissionIntent(): Intent {
        val context = getApplication<Application>()
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    }
    fun reportError(message: String) { mutableState.value = mutableState.value.copy(message = message) }
}
