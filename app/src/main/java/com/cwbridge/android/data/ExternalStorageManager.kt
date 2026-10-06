package com.cwbridge.android.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.EXTRA_INITIAL_URI
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.cwbridge.android.bridge.LogBuffer
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages external storage access using Storage Access Framework (SAF).
 * Allows users to select any directory (including SD card, cloud storage, etc.)
 * for storing backups or additional data.
 */
class ExternalStorageManager(private val activity: AppCompatActivity) {
    
    companion object {
        private const val PREF_EXTERNAL_URI = "external_storage_uri"
        private const val REQUEST_CODE_SELECT_FOLDER = 42
        private const val REQUEST_CODE_CREATE_DOCUMENT = 43
    }

    // Activity result launcher for folder selection
    private val selectFolderLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                onFolderSelected(uri)
            }
        }
    }

    // Activity result launcher for document creation
    private val createDocumentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                onDocumentCreated(uri)
            }
        }
    }

    // Callback for when a folder is selected
    private var folderSelectionCallback: ((Uri) -> Unit)? = null
    
    // Callback for when a document is created
    private var documentCreationCallback: ((Uri) -> Unit)? = null

    /**
     * Launch folder selection dialog using SAF.
     * User can select any directory they have access to.
     */
    fun selectFolder(callback: (Uri) -> Unit) {
        folderSelectionCallback = callback
        
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            // Optionally set initial URI to a common location
            val initialUri = getLastSelectedUri()
            if (initialUri != null) {
                putExtra(EXTRA_INITIAL_URI, initialUri)
            }
        }
        
        selectFolderLauncher.launch(intent)
    }

    /**
     * Create a new document in the selected folder.
     */
    fun createDocument(
        fileName: String,
        mimeType: String = "application/json",
        callback: (Uri) -> Unit
    ) {
        documentCreationCallback = callback
        
        val lastUri = getLastSelectedUri()
        if (lastUri == null) {
            LogBuffer.w("ExternalStorage", "No folder selected, cannot create document")
            return
        }
        
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
            putExtra(EXTRA_INITIAL_URI, lastUri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        
        createDocumentLauncher.launch(intent)
    }

    /**
     * Save the selected folder URI for future use.
     */
    fun saveSelectedUri(uri: Uri) {
        val prefs = activity.getPreferences(Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_EXTERNAL_URI, uri.toString()).apply()
        DatabaseManager.setExternalStorageUri(uri.toString())
    }

    /**
     * Get the last selected folder URI.
     */
    fun getLastSelectedUri(): Uri? {
        val prefs = activity.getPreferences(Context.MODE_PRIVATE)
        val uriString = prefs.getString(PREF_EXTERNAL_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    /**
     * Clear the saved folder URI.
     */
    fun clearSelectedUri() {
        val prefs = activity.getPreferences(Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_EXTERNAL_URI).apply()
        DatabaseManager.clearExternalStorageUri()
    }

    /**
     * Check if an external folder is selected.
     */
    fun hasSelectedFolder(): Boolean = getLastSelectedUri() != null

    /**
     * Take persistable URI permissions for the given URI.
     * This allows the app to access the URI even after restart.
     */
    fun takePersistableUriPermission(uri: Uri): Boolean {
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            return true
        } catch (e: SecurityException) {
            LogBuffer.e("ExternalStorage", "Failed to take persistable URI permission: ${e.message}")
            return false
        }
    }

    /**
     * Release persistable URI permissions for the given URI.
     */
    fun releasePersistableUriPermission(uri: Uri) {
        try {
            activity.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            LogBuffer.e("ExternalStorage", "Failed to release persistable URI permission: ${e.message}")
        }
    }

    /**
     * Write data to a file in the selected folder.
     * Creates the file if it doesn't exist.
     */
    suspend fun writeToExternalFile(
        fileName: String,
        data: String,
        mimeType: String = "application/json"
    ): Boolean {
        val uri = getLastSelectedUri() ?: return false
        
        return try {
            val folder = DocumentFile.fromTreeUri(activity, uri) ?: return false
            val file = folder.createFile(mimeType, fileName) ?: folder.findFile(fileName)
            
            if (file == null) {
                LogBuffer.w("ExternalStorage", "Cannot create or find file: $fileName")
                return false
            }
            
            activity.contentResolver.openOutputStream(file.uri)?.use { output ->
                output.write(data.toByteArray())
            }
            true
        } catch (t: Throwable) {
            LogBuffer.e("ExternalStorage", "Failed to write file: ${t.message}")
            false
        }
    }

    /**
     * Read data from a file in the selected folder.
     */
    suspend fun readFromExternalFile(fileName: String): String? {
        val uri = getLastSelectedUri() ?: return null
        
        return try {
            val folder = DocumentFile.fromTreeUri(activity, uri) ?: return null
            val file = folder.findFile(fileName) ?: return null
            
            activity.contentResolver.openInputStream(file.uri)?.use { input ->
                input.bufferedReader().use { reader ->
                    reader.readText()
                }
            }
        } catch (t: Throwable) {
            LogBuffer.e("ExternalStorage", "Failed to read file: ${t.message}")
            null
        }
    }

    /**
     * List files in the selected folder.
     */
    suspend fun listFilesInFolder(): List<String> {
        val uri = getLastSelectedUri() ?: return emptyList()
        
        return try {
            val folder = DocumentFile.fromTreeUri(activity, uri) ?: return emptyList()
            folder.listFiles().map { it.name ?: "" }.filter { it.isNotBlank() }
        } catch (t: Throwable) {
            LogBuffer.e("ExternalStorage", "Failed to list files: ${t.message}")
            emptyList()
        }
    }

    /**
     * Export all data to external storage.
     */
    suspend fun exportAllData(): Boolean {
        return DatabaseManager.exportToExternalStorage(activity)
    }

    /**
     * Import all data from external storage.
     */
    suspend fun importAllData(): Boolean {
        return DatabaseManager.importFromExternalStorage(activity)
    }

    /**
     * Delete a file from the selected folder.
     */
    suspend fun deleteFile(fileName: String): Boolean {
        val uri = getLastSelectedUri() ?: return false
        
        return try {
            val folder = DocumentFile.fromTreeUri(activity, uri) ?: return false
            val file = folder.findFile(fileName) ?: return false
            file.delete()
            true
        } catch (t: Throwable) {
            LogBuffer.e("ExternalStorage", "Failed to delete file: ${t.message}")
            false
        }
    }

    // Callback handlers
    private fun onFolderSelected(uri: Uri) {
        takePersistableUriPermission(uri)
        saveSelectedUri(uri)
        folderSelectionCallback?.invoke(uri)
        folderSelectionCallback = null
        LogBuffer.i("ExternalStorage", "Folder selected: $uri")
    }

    private fun onDocumentCreated(uri: Uri) {
        takePersistableUriPermission(uri)
        documentCreationCallback?.invoke(uri)
        documentCreationCallback = null
        LogBuffer.i("ExternalStorage", "Document created: $uri")
    }
}
