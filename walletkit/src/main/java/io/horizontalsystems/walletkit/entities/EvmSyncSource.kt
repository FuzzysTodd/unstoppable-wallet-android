package io.horizontalsystems.walletkit.entities

import androidx.room.Entity
import java.net.URI

data class EvmSyncSource(
    val id: String,
    val name: String,
    val uris: List<URI>,
    val isWebSocket: Boolean = false,
    val auth: String? = null,
    // Shown in settings instead of the real URL when that URL carries an API key.
    val displayUrl: String? = null,
) {
    val isHttp: Boolean get() = !isWebSocket

    val uri: URI
        get() = uris[0]

    val urlForDisplay: String
        get() = displayUrl ?: uri.toString()
}

@Entity(primaryKeys = ["blockchainTypeUid", "url"])
data class EvmSyncSourceRecord(
    val blockchainTypeUid: String,
    val url: String,
    val auth: String?,
)
