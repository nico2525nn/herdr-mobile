package dev.herdr.mobile

import dev.herdr.mobile.core.model.BackendMode
import dev.herdr.mobile.core.model.HostProfile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredProfileBackendTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyProfilesDefaultToTui() {
        val legacy = """[{"id":"p1","label":"host","host":"example.test","username":"nico"}]"""
        val profile = json.decodeFromString(ListSerializer(StoredProfile.serializer()), legacy).single().toModel()
        assertEquals(BackendMode.TUI, profile.backendMode)
    }

    @Test
    fun backendModeSurvivesProfilePersistence() {
        val profile = HostProfile(
            id = "p1",
            label = "host",
            host = "example.test",
            username = "nico",
            backendMode = BackendMode.CORE,
        )
        val stored = StoredProfile.from(profile)
        val encoded = json.encodeToString(ListSerializer(StoredProfile.serializer()), listOf(stored))
        val decoded = json.decodeFromString(ListSerializer(StoredProfile.serializer()), encoded).single().toModel()
        assertEquals(BackendMode.CORE, decoded.backendMode)
    }
}
