package com.splayer.splay

import com.hierynomus.smbj.auth.AuthenticationContext

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
    fun isConfigured(): Boolean = server.isNotBlank() && share.isNotBlank()

    fun authenticationContext(): AuthenticationContext =
        if (username.isBlank() && password.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(username, password.toCharArray(), "")
}
