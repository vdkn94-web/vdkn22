package com.splayer.splay

enum class MediaKind { VIDEO, MUSIC }

data class MediaItem(
    val path: String,
    val name: String,
    val kind: MediaKind
)

data class SmbSettings(
    val server: String,
    val share: String,
    val username: String,
    val password: String,
    val folder: String
) {
    fun isConfigured(): Boolean =
        server.isNotBlank() && share.isNotBlank() && folder.isNotBlank()
}
