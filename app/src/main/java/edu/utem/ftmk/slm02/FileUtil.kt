package edu.utem.ftmk.slm02

import android.content.Context
import android.net.Uri
import java.io.File

object FileUtil {

    fun copyUriToInternalStorage(context: Context, uri: Uri): String {
        val name = uri.lastPathSegment?.substringAfterLast("/") ?: "model.gguf"
        val outFile = File(context.filesDir, name)

        if (!outFile.exists()) {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile.absolutePath
    }
}
