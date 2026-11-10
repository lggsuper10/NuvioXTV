package com.nuvio.tv.core.smb

/** Account used to authenticate against one SMB server. */
data class SmbCredentials(
    val username: String?,
    val password: String,
    val domain: String?
) {
    val hasCredentials: Boolean
        get() = !username.isNullOrBlank() || password.isNotEmpty()
}
