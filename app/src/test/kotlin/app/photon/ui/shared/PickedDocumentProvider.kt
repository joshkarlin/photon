package app.photon.ui.shared

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * Another app's document, as the system's document picker hands one to
 * Photon: a content URI whose provider names it and opens it. Set what it
 * serves, then [install] it.
 */
class PickedDocumentProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (refuseQueries) throw SecurityException("not yours")
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any?>(displayName)) }
    }

    override fun getType(uri: Uri) = mime

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file ?: throw FileNotFoundException(uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "app.photon.test.documents"
        val DOCUMENT: Uri = Uri.parse("content://$AUTHORITY/document/1")

        var displayName: String? = null
        var mime = "application/pdf"
        var file: File? = null
        var refuseQueries = false

        fun install() {
            Robolectric.setupContentProvider(PickedDocumentProvider::class.java, AUTHORITY)
        }

        fun reset() {
            displayName = null
            mime = "application/pdf"
            file = null
            refuseQueries = false
        }
    }
}
