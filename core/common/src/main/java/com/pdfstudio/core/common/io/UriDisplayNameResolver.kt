package com.pdfstudio.core.common.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object UriDisplayNameResolver {

    fun resolve(context: Context, uri: Uri, fallback: String? = null): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            queryDisplayName(context, uri)?.let { return it }
        }
        uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && !isOpaqueProviderId(it) }
            ?.let { return it }
        fallback
            ?.takeIf { it.isNotBlank() && !isOpaqueProviderId(it) }
            ?.let { return it }
        return "document.pdf"
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0 || !cursor.moveToFirst()) return@use null
                cursor.getString(index)?.takeIf { it.isNotBlank() }
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun isOpaqueProviderId(segment: String): Boolean {
        return segment.matches(OPAQUE_ID)
    }

    private val OPAQUE_ID = Regex("^(msf|document|raw|external):\\d+$", RegexOption.IGNORE_CASE)
}
