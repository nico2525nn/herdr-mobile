package dev.herdr.mobile.core.network

import kotlinx.serialization.json.Json

/**
 * Single JSON codec for everything crossing the wire.
 *
 * `ignoreUnknownKeys` matters more than usual here: the daemon and the client are versioned
 * independently, and a newer daemon adding a field must never break an installed client.
 * `explicitNulls = false` keeps optional fields out of the request bodies we send.
 */
val HerdrJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}

val HerdrJsonPretty: Json = Json(HerdrJson) {
    prettyPrint = true
}