package com.galaxy.assettrail.driver.device

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.galaxy.assettrail.core.payload.Hashes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Photos of the load taken at pickup. They stay on the phone (and later the platform's payload
 * store); only "sha256:<hex>" goes into the waypoint payload.
 */
class PhotoStore(private val context: Context) {
    private val directory = File(context.filesDir, "load-photos").apply { mkdirs() }

    /** A file the camera app can write to. */
    fun newPhoto(): Pair<File, Uri> {
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return file to uri
    }

    suspend fun hash(file: File): String? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) null else "sha256:" + Hashes.hex(Hashes.sha256(file.readBytes()))
    }

    /** Demo: stands in for a photo when the device has no camera. */
    fun samplePhotoHash(): String = "sha256:" + Hashes.hex(Hashes.sha256(Hashes.randomBytes(64)))
}
