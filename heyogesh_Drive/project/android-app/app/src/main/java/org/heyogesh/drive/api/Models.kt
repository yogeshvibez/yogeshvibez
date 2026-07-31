package org.heyogesh.drive.api

data class ServerError(val code: String?, val message: String?, val requestId: String?)
data class ErrorEnvelope(val error: ServerError?)

data class LoginResponse(val accessToken: String, val expiresAt: String)

data class DriveItem(
    val name: String,
    val path: String,
    val kind: String,
    val size: Long?,
    val modifiedAt: String,
    val extension: String?,
    val mimeType: String?,
)

data class FolderResponse(
    val path: String,
    val parentPath: String?,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val items: List<DriveItem>,
)

data class OpenResponse(
    val path: String,
    val mimeType: String,
    val streamUrl: String,
    val streamExpiresAt: String,
    val downloadPath: String,
)

data class ArchiveStatus(
    val id: String,
    val state: String,
    val sourceBytes: Long,
    val processedBytes: Long,
    val totalFiles: Int,
    val processedFiles: Int,
    val outputBytes: Long,
    val error: ServerError?,
    val updatedAt: String,
    val downloadPath: String?,
)

class ApiException(
    val statusCode: Int,
    val errorCode: String = "REQUEST_FAILED",
    override val message: String,
) : Exception(message)
