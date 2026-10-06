package dev.herdr.mobile

import dev.herdr.mobile.core.model.HostProfile
import kotlinx.serialization.Serializable

/** DataStore form of [HostProfile]: aliases only, never secrets. */
@Serializable
data class StoredProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String = "",
    val daemonPort: Int = 8765,
    val daemonHost: String = "127.0.0.1",
    val privateKeyAlias: String? = null,
    val privateKeyLabel: String? = null,
    val hostKeyAlias: String? = null,
    val useTls: Boolean = false,
    val bearerTokenAlias: String? = null,
) {
    fun toModel() = HostProfile(
        id = id,
        label = label,
        host = host,
        port = port,
        username = username,
        daemonPort = daemonPort,
        daemonHost = daemonHost,
        privateKeyAlias = privateKeyAlias,
        privateKeyLabel = privateKeyLabel,
        hostKeyAlias = hostKeyAlias,
        useTls = useTls,
        bearerTokenAlias = bearerTokenAlias,
    )

    companion object {
        fun from(model: HostProfile) = StoredProfile(
            id = model.id,
            label = model.label,
            host = model.host,
            port = model.port,
            username = model.username,
            daemonPort = model.daemonPort,
            daemonHost = model.daemonHost,
            privateKeyAlias = model.privateKeyAlias,
            privateKeyLabel = model.privateKeyLabel,
            hostKeyAlias = model.hostKeyAlias,
            useTls = model.useTls,
            bearerTokenAlias = model.bearerTokenAlias,
        )
    }
}
