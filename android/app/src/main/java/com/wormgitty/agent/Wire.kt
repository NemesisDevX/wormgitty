package com.wormgitty.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Single serialization profile for every on-disk / wire payload. */
@Serializable
data class Registration(
    val id: String = "",
    val name: String,
    val phone: String,
    val gender: String = "",
    val createdAtMs: Long = 0L,
    val fingerprint: Fingerprint = Fingerprint(),
    val constraints: Constraints = Constraints()
)

@Serializable
data class Fingerprint(
    val androidId: String = "",
    val model: String = "",
    val manufacturer: String = "",
    val sdkInt: Int = 0,
    val abi: String = ""
)

@Serializable
data class Constraints(
    val totalMemMb: Long = 0L,
    val batteryOptIgnored: Boolean = false,
    val phantomCeiling: Int = SystemDeviceInfoProvider.DEFAULT_PHANTOM_CEILING
)

/** Liveness snapshot WormSvc persists so health survives process death. */
@Serializable
data class HealthState(
    val state: String = "stopped",
    val startedAt: Long = 0L,
    val lastBeat: Long = 0L
)

object Wire {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(value: Registration): String =
        json.encodeToString(Registration.serializer(), value)

    fun decodeRegistration(text: String): Registration? =
        runCatching { json.decodeFromString(Registration.serializer(), text) }.getOrNull()

    fun encode(value: HealthState): String =
        json.encodeToString(HealthState.serializer(), value)

    fun decodeHealth(text: String): HealthState? =
        runCatching { json.decodeFromString(HealthState.serializer(), text) }.getOrNull()
}
