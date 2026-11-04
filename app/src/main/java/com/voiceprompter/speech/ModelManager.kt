package com.voiceprompter.speech

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gets a Vosk acoustic model onto the device.
 *
 * The small English model is roughly 40 MB, which is too big to commit to git
 * comfortably and bloats every CI artifact if bundled in assets. So the app
 * downloads it on first run and unpacks it into app storage, after which
 * everything is offline.
 *
 * If you would rather ship it inside the APK, drop the unzipped model into
 * `app/src/main/assets/model-en-us/` and [ensureModel] will find it there and
 * skip the download. Remember to take that path out of .gitignore if you do.
 */
class ModelManager(private val context: Context) {

    companion object {
        const val ASSET_DIR = "model-en-us"

        /**
         * Published small US English model. Verify this URL resolves before
         * relying on it — Alpha Cephei reorganises the model index from time
         * to time, and a model name can be retired.
         */
        const val DEFAULT_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }

    private val modelRoot: File
        get() = File(context.filesDir, "vosk")

    /** Returns the model directory, downloading and unpacking it if needed. */
    suspend fun ensureModel(
        url: String = DEFAULT_MODEL_URL,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            installedModel()?.let { return@runCatching it }

            bundledAsset()?.let { assetDir ->
                val target = File(modelRoot, ASSET_DIR)
                copyAssetDir(assetDir, target)
                return@runCatching target
            }

            downloadAndUnpack(url, onProgress)
        }
    }

    /** The model directory if one is already installed, else null. */
    fun installedModel(): File? =
        modelRoot.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, "am").exists() }

    fun deleteModel(): Boolean = modelRoot.deleteRecursively()

    private fun bundledAsset(): String? = runCatching {
        if (context.assets.list(ASSET_DIR)?.isNotEmpty() == true) ASSET_DIR else null
    }.getOrNull()

    private fun copyAssetDir(assetPath: String, target: File) {
        val entries = context.assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        for (entry in entries) {
            copyAssetDir("$assetPath/$entry", File(target, entry))
        }
    }

    private fun downloadAndUnpack(url: String, onProgress: (Float) -> Unit): File {
        modelRoot.mkdirs()
        val staging = File(modelRoot, "download.zip")

        val connection = java.net.URL(url).openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        val total = connection.contentLengthLong.takeIf { it > 0 }

        connection.getInputStream().use { input ->
            staging.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total != null) onProgress(copied.toFloat() / total)
                }
            }
        }

        staging.inputStream().use { unzip(it, modelRoot) }
        staging.delete()

        return installedModel()
            ?: error("Downloaded archive did not contain a Vosk model directory")
    }

    private fun unzip(stream: InputStream, target: File) {
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = File(target, entry.name)

                // Guard against zip-slip: a crafted archive with ../ entries
                // could otherwise write outside the target directory.
                if (!destination.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    throw SecurityException("Zip entry escapes target directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }
}
