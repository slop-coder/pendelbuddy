package io.github.slop_coder.pendelbuddy.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.xml.parsers.DocumentBuilderFactory

/** OJP 2.0 (opentransportdata.swiss): trip request XML and the parts of the answer the app uses. */
object Ojp {
    const val REQUESTOR = "PendelBuddy_prod"

    /** OJP knows stations by SLOID; the Swiss UIC number 85xxxxx is "ch:1:sloid:xxxxx". */
    fun sloid(uic: String): String =
        if (uic.length == 7 && uic.startsWith("85")) "ch:1:sloid:${uic.drop(2).trimStart('0')}" else uic

    /** Back from "ch:1:sloid:1234" or a platform "ch:1:sloid:1234:4:7" to "8501234". */
    fun uic(ref: String): String {
        val m = Regex("""^ch:1:sloid:(\d+)""").find(ref) ?: return ref
        return "85" + m.groupValues[1].padStart(5, '0')
    }

    fun tripRequest(from: Station, to: Station, at: Long, results: Int, now: Long, maxChanges: Int? = null): String {
        val ts = Instant.ofEpochSecond(now).toString()
        return """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPRequest>
    <siri:ServiceRequest>
      <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
      <siri:RequestorRef>$REQUESTOR</siri:RequestorRef>
      <OJPTripRequest>
        <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
        <siri:MessageIdentifier>PB-$now</siri:MessageIdentifier>
        <Origin>
          <PlaceRef><StopPlaceRef>${sloid(from.id)}</StopPlaceRef><Name><Text>${xml(from.name)}</Text></Name></PlaceRef>
          <DepArrTime>${Instant.ofEpochSecond(at)}</DepArrTime>
        </Origin>
        <Destination>
          <PlaceRef><StopPlaceRef>${sloid(to.id)}</StopPlaceRef><Name><Text>${xml(to.name)}</Text></Name></PlaceRef>
        </Destination>
        <Params>
          <NumberOfResults>$results</NumberOfResults>
          <UseRealtimeData>explanatory</UseRealtimeData>${maxChanges?.let { "\n          <TransferLimit>$it</TransferLimit>" } ?: ""}
          <IncludeIntermediateStops>true</IncludeIntermediateStops>
        </Params>
      </OJPTripRequest>
    </siri:ServiceRequest>
  </OJPRequest>
</OJP>"""
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val LEVELS = mapOf("manySeatsAvailable" to 1, "fewSeatsAvailable" to 2, "standingRoomOnly" to 3)

    /** Trips of an OJPTripDelivery; [fromId]/[toId] are the UIC numbers that were asked for. */
    fun parseTrips(body: String, fromId: String, toId: String): List<Trip> {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        doc.documentElement.all("ErrorCondition").firstOrNull()?.let {
            throw OjpException(it.textContent.trim().replace(Regex("\\s+"), " "))
        }
        return doc.documentElement.all("Trip").mapNotNull { trip(it, fromId, toId) }
    }

    private fun trip(t: Element, fromId: String, toId: String): Trip? {
        val raw = t.children("Leg")
        val timedLegs = raw.map { it.child("TimedLeg")?.let(::timedLeg) }
        val firstTimed = timedLegs.indexOfFirst { it != null }.takeIf { it >= 0 } ?: return null
        val lastTimed = timedLegs.indexOfLast { it != null }
        // Walks between trains only have a duration: place them after the train before.
        val legs = mutableListOf<Leg>()
        var clock = timedLegs[firstTimed]!!.departure
        raw.forEachIndexed { i, el ->
            if (i < firstTimed || i > lastTimed) return@forEachIndexed
            val timed = timedLegs[i]
            if (timed != null) {
                legs += timed
                clock = timed.arrival
            } else {
                val w = walk(el, clock) ?: return@forEachIndexed
                legs += w
                clock = w.arrival
            }
        }
        return Trip(
            fromId = fromId,
            toId = toId,
            departure = 0,
            arrival = 0,
            transfers = t.child("Transfers")?.textContent?.trim()?.toIntOrNull() ?: (timedLegs.count { it != null } - 1),
            legs = legs,
        ).trimWalks()
    }

    private fun timedLeg(l: Element): Leg? {
        val board = l.child("LegBoard") ?: return null
        val alight = l.child("LegAlight") ?: return null
        val service = l.child("Service")
        val dep = board.child("ServiceDeparture") ?: return null
        val arr = alight.child("ServiceArrival") ?: return null
        // Occupancy when leaving each stop of the ride: the boarding stop and every stop in between.
        val calls = (listOf(board) + l.children("LegIntermediate")).mapNotNull { stop ->
            Call(
                id = uic(stop.child("StopPointRef")?.textContent?.trim().orEmpty()),
                name = stop.child("StopPointName")?.text().orEmpty(),
                departure = time(stop.child("ServiceDeparture")?.child("TimetabledTime")) ?: return@mapNotNull null,
                capacity = occupancy(stop),
            )
        }
        return Leg(
            line = service?.let(::lineName) ?: "?",
            direction = service?.child("DestinationText")?.text(),
            fromId = uic(board.child("StopPointRef")?.textContent?.trim().orEmpty()),
            fromName = board.child("StopPointName")?.text().orEmpty(),
            toId = uic(alight.child("StopPointRef")?.textContent?.trim().orEmpty()),
            toName = alight.child("StopPointName")?.text().orEmpty(),
            departure = time(dep.child("TimetabledTime")) ?: return null,
            arrival = time(arr.child("TimetabledTime")) ?: return null,
            departureDelay = delay(dep),
            platform = quay(board),
            plannedPlatform = board.child("PlannedQuay")?.text(),
            arrivalDelay = delay(arr),
            arrivalPlatform = quay(alight),
            plannedArrivalPlatform = alight.child("PlannedQuay")?.text(),
            // The fullest stretch counts: will there be a seat the whole way?
            capacity = Capacity.worst(calls.map { it.capacity }),
            trainNumber = service?.child("TrainNumber")?.textContent?.trim()?.trimStart('0')?.takeIf { it.isNotEmpty() },
            cancelled = service?.child("Cancelled")?.textContent?.trim() == "true" || skipped(board) || skipped(alight),
            boardSkipped = skipped(board),
            alightSkipped = skipped(alight),
            calls = calls,
            journeyRef = service?.child("JourneyRef")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
            operatingDay = service?.child("OperatingDayRef")?.textContent?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun skipped(stop: Element) = stop.child("NotServicedStop")?.textContent?.trim() == "true"

    private fun occupancy(stop: Element): Capacity {
        val byClass = stop.children("ExpectedDepartureOccupancy").associate { o ->
            o.child("FareClass")?.textContent?.trim() to LEVELS[o.child("OccupancyLevel")?.textContent?.trim()]
        }
        return Capacity(byClass["firstClass"], byClass["secondClass"])
    }

    /** Transfer and walking legs, starting at [start] unless they carry their own time window. */
    private fun walk(l: Element, start: Long): Leg? {
        val leg = l.child("TransferLeg") ?: l.child("ContinuousLeg") ?: return null
        val begin = time(leg.child("TimeWindowStart")) ?: start
        val end = time(leg.child("TimeWindowEnd")) ?: (begin + duration(l))
        val from = leg.child("LegStart")
        val to = leg.child("LegEnd")
        return Leg(
            line = null,
            fromId = uic(from?.child("StopPointRef")?.textContent?.trim() ?: from?.child("StopPlaceRef")?.textContent?.trim().orEmpty()),
            fromName = from?.child("Name")?.text().orEmpty(),
            toName = to?.child("Name")?.text().orEmpty(),
            departure = begin,
            arrival = end,
        )
    }

    /** Seconds of a leg's "PT6M"-style duration (on the Leg or inside it), 0 if missing. */
    private fun duration(leg: Element): Long {
        val d = leg.child("Duration") ?: leg.children("TransferLeg").plus(leg.children("ContinuousLeg")).firstNotNullOfOrNull { it.child("Duration") }
        return d?.textContent?.trim()?.let { runCatching { java.time.Duration.parse(it).seconds }.getOrNull() } ?: 0
    }

    /** "IC 8", "S 3", "B 10": the mode's short name and the published line name, or the line alone if it already starts with it. */
    private fun lineName(service: Element): String {
        val short = service.child("Mode")?.child("ShortName")?.text().orEmpty()
        val published = service.child("PublishedServiceName")?.text().orEmpty()
        return when {
            published.isEmpty() -> short.ifEmpty { "?" }
            short.isEmpty() || published.startsWith(short) -> published.replace(Regex("^([A-Za-z]+)(\\d)"), "$1 $2")
            else -> "$short $published"
        }
    }

    private fun quay(e: Element) = (e.child("EstimatedQuay") ?: e.child("PlannedQuay"))?.text()

    private fun delay(service: Element): Int? {
        val planned = time(service.child("TimetabledTime")) ?: return null
        val estimated = time(service.child("EstimatedTime")) ?: return null
        // Whole minutes, like the station boards: 50 s late is still on time.
        return ChronoUnit.MINUTES.between(Instant.ofEpochSecond(planned), Instant.ofEpochSecond(estimated)).toInt()
    }

    private fun time(e: Element?) = e?.textContent?.trim()?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }

    /** Element text, or the text of its <Text> child (OJP wraps names in <Text xml:lang>). */
    private fun Element.text() = (child("Text") ?: this).textContent.trim()

    private fun Element.children(name: String): List<Element> {
        val out = mutableListOf<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.localName == name) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(name: String) = children(name).firstOrNull()

    private fun Element.all(name: String): List<Element> {
        val list = getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) as Element }
    }
}

class OjpException(message: String) : Exception(message)
