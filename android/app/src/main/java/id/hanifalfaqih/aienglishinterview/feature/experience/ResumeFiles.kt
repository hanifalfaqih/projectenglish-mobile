package id.hanifalfaqih.aienglishinterview.feature.experience

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

/** MIME types accepted by the resume import picker. */
const val MIME_PDF = "application/pdf"
const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/** Backend resume limit, enforced client-side before upload. */
const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

fun displayName(resolver: ContentResolver, uri: Uri): String {
    return try {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "resume.pdf"
    } catch (e: SecurityException) {
        "resume.pdf"
    }
}

/** Reads a picked document with a byte cap; null uri = user cancelled. */
fun readPickedFile(
    resolver: ContentResolver,
    uri: Uri?,
    maxBytes: Int = MAX_IMPORT_BYTES,
): Result<ByteArray?> {
    if (uri == null) return Result.success(null)
    return try {
        resolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    return Result.failure(IOException("too_large"))
                }
                out.write(buf, 0, n)
            }
            Result.success(out.toByteArray())
        } ?: Result.failure(IOException("unreadable"))
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: SecurityException) {
        Result.failure(IOException("unreadable", e))
    }
}

/** MIME for upload, derived from the picked filename. */
fun mimeFor(filename: String): String =
    if (filename.endsWith(".docx", ignoreCase = true)) MIME_DOCX else MIME_PDF
