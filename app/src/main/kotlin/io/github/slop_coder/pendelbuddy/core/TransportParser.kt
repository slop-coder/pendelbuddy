package io.github.slop_coder.pendelbuddy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Reads the JSON of transport.opendata.ch (`/v1/connections`, `/v1/locations`). */
object TransportParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseConnections(body: String): List<Trip> {
        val root = json.parseToJsonElement(body).jsonObject
        return root.arr("connections").mapNotNull { (it as? JsonObject)?.let(::trip) }
    }

    fun parseStations(body: String): List<Station> {
        val root = json.parseToJsonElement(body).jsonObject
        return root.arr("stations").mapNotNull { (it as? JsonObject)?.let(::station) }
            // Results without an id are addresses or POIs, not stops.
            .filter { it.id.isNotEmpty() }
    }

    private fun trip(c: JsonObject): Trip? {
        val from = c.obj("from") ?: return null
        val to = c.obj("to") ?: return null
        val fromStation = from.obj("station")?.let(::station) ?: return null
        val toStation = to.obj("station")?.let(::station) ?: return null
        return Trip(
            fromId = fromStation.id,
            toId = toStation.id,
            departure = from.long("departureTimestamp") ?: return null,
            arrival = to.long("arrivalTimestamp") ?: return null,
            departureDelay = from.int("delay"),
            arrivalDelay = to.int("delay"),
            platform = from.str("platform"),
            transfers = c.int("transfers") ?: 0,
            legs = c.arr("sections").mapNotNull { (it as? JsonObject)?.let(::leg) },
        ).trimWalks()
    }

    private fun leg(s: JsonObject): Leg? {
        val dep = s.obj("departure") ?: return null
        val arr = s.obj("arrival") ?: return null
        val depStation = dep.obj("station")?.let(::station) ?: return null
        val journey = s.obj("journey")
        val arrStation = arr.obj("station")?.let(::station)
        return Leg(
            line = journey?.let(::lineName),
            direction = journey?.str("to"),
            fromId = depStation.id,
            fromName = depStation.name,
            toId = arrStation?.id.orEmpty(),
            toName = arrStation?.name.orEmpty(),
            // Walks only carry the arrival time of the previous leg; fill in what is there.
            departure = dep.long("departureTimestamp") ?: arr.long("arrivalTimestamp") ?: return null,
            arrival = arr.long("arrivalTimestamp") ?: dep.long("departureTimestamp") ?: return null,
            departureDelay = dep.int("delay"),
            platform = dep.str("platform"),
            arrivalDelay = arr.int("delay"),
            arrivalPlatform = arr.str("platform"),
            capacity = journey?.let { Capacity(capacity(it["capacity1st"]), capacity(it["capacity2nd"])) } ?: Capacity(),
            trainNumber = journey?.str("name")?.trimStart('0')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) },
        )
    }

    private fun lineName(j: JsonObject): String {
        val category = j.str("category").orEmpty()
        // Trains without a line number get their (zero-padded) train number here, e.g. "000906"; drop it.
        val number = j.str("number")?.takeUnless { it.length >= 4 && it.all(Char::isDigit) }.orEmpty()
        return "$category $number".trim().ifEmpty { "?" }
    }

    private fun station(o: JsonObject): Station? {
        val name = o.str("name") ?: return null
        val coord = o.obj("coordinate")
        // The API calls latitude x and longitude y.
        return Station(o.str("id").orEmpty(), name, coord?.double("x"), coord?.double("y"), o.str("icon"))
    }

    /** 1..3 when known; the API sends null, -1 or the number as a string otherwise. */
    private fun capacity(e: JsonElement?): Int? =
        (e as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it in 1..3 }

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.long(k: String) = str(k)?.toLongOrNull()
    private fun JsonObject.int(k: String) = str(k)?.toIntOrNull()
    private fun JsonObject.double(k: String) = str(k)?.toDoubleOrNull()
}
