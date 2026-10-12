package app.photon.ui.shared.components

import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/**
 * The extensions Photon names files of each type with, the usual one first.
 * Office, OpenDocument, e-book and archive types are listed so they're
 * never saved as ".bin"; anything else goes to Android's [MimeTypeMap].
 */
private val EXTENSIONS: Map<String, List<String>> = mapOf(
    "image/jpeg" to listOf("jpg", "jpeg", "jpe"),
    "image/png" to listOf("png"),
    "image/webp" to listOf("webp"),
    "image/gif" to listOf("gif"),
    "image/heic" to listOf("heic"),
    "image/heif" to listOf("heif"),
    "video/mp4" to listOf("mp4"),
    "video/3gpp" to listOf("3gp"),
    "video/quicktime" to listOf("mov"),
    "video/webm" to listOf("webm"),
    "audio/ogg" to listOf("ogg", "opus", "oga"),
    "audio/opus" to listOf("opus"),
    "audio/mpeg" to listOf("mp3"),
    "audio/mp4" to listOf("m4a"),
    "audio/aac" to listOf("aac"),
    "audio/amr" to listOf("amr"),
    "application/pdf" to listOf("pdf"),
    "application/msword" to listOf("doc"),
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to listOf("docx"),
    "application/vnd.ms-excel" to listOf("xls"),
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to listOf("xlsx"),
    "application/vnd.ms-powerpoint" to listOf("ppt"),
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" to listOf("pptx"),
    "application/vnd.oasis.opendocument.text" to listOf("odt"),
    "application/vnd.oasis.opendocument.spreadsheet" to listOf("ods"),
    "application/vnd.oasis.opendocument.presentation" to listOf("odp"),
    "application/vnd.apple.pages" to listOf("pages"),
    "application/vnd.apple.numbers" to listOf("numbers"),
    "application/vnd.apple.keynote" to listOf("key"),
    "application/epub+zip" to listOf("epub"),
    "application/zip" to listOf("zip"),
    "application/x-zip-compressed" to listOf("zip"),
    "application/x-7z-compressed" to listOf("7z"),
    "application/vnd.rar" to listOf("rar"),
    "application/x-rar-compressed" to listOf("rar"),
    "application/gzip" to listOf("gz"),
    "application/x-tar" to listOf("tar"),
    "application/rtf" to listOf("rtf"),
    "application/json" to listOf("json"),
    "application/vnd.android.package-archive" to listOf("apk"),
    "text/plain" to listOf("txt"),
    "text/csv" to listOf("csv"),
    "text/html" to listOf("html", "htm"),
    "text/markdown" to listOf("md"),
    "text/rtf" to listOf("rtf"),
    "text/calendar" to listOf("ics"),
    "text/vcard" to listOf("vcf"),
    "text/x-vcard" to listOf("vcf"),
)

/** "Some bytes": a type that says nothing about the file. */
private const val OCTET_STREAM = "application/octet-stream"

/** [mime] without parameters, in lower case; null when it names no type. */
private fun baseType(mime: String?): String? =
    mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != OCTET_STREAM }

/** The extension (".docx") a file of [mime] is named with; null when that's unknown. */
internal fun mimeToExt(mime: String?): String? {
    val type = baseType(mime) ?: return null
    val ext = EXTENSIONS[type]?.first() ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
    return ext?.let { ".$it" }
}

/** The type a file named with [extension] ("docx", no dot) holds; null when that's unknown. */
private fun typeOfExtension(extension: String): String? {
    val ext = extension.lowercase(Locale.ROOT)
    return EXTENSIONS.entries.firstOrNull { ext in it.value }?.key
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

/** What Save writes: the name the file is saved under, and the type it's saved as. */
internal data class SaveTarget(val displayName: String, val mime: String)

/**
 * Where Save puts [file], received as [mime].
 *
 * - With [fileName], the name the sender's file had (a document's): that
 *   name, cleaned ([sanitizeFileName]). If it has no extension its type
 *   explains, the type's extension is added ("Report" → "Report.docx").
 *   An extension that names another known type wins over [mime], so the
 *   name is kept as the sender had it.
 * - Without one, Photon's own download name (the message id), with the
 *   type's extension in place of whatever the platform wrote (WhatsApp
 *   names files it doesn't know ".bin").
 */
internal fun saveTarget(fileName: String?, mime: String?, file: File): SaveTarget {
    val type = baseType(mime)
    val typeExt = mimeToExt(type)
    val saveAs = type ?: OCTET_STREAM
    val name = fileName?.let(::sanitizeFileName)
        ?: return SaveTarget(if (typeExt != null) file.nameWithoutExtension + typeExt else file.name, saveAs)

    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { name.lastIndexOf('.') > 0 && it.isNotEmpty() }
    val extType = ext?.let(::typeOfExtension)
    return when {
        // The name says what it is: keep it, saved as the type its extension names.
        extType != null -> SaveTarget(name, if (type != null && ext in EXTENSIONS[type].orEmpty()) type else extType)
        typeExt != null -> SaveTarget(name + typeExt, saveAs)
        else -> SaveTarget(name, saveAs)
    }
}
