package io.github.slop_coder.pendelbuddy.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortNamesTest {
    @Test
    fun automatic() {
        assertEquals("BS", ShortNames.auto("Basel SBB"))
        assertEquals("LU", ShortNames.auto("Luzern"))
        assertEquals("GE Cornavin", ShortNames.auto("Genève Cornavin"))
        assertEquals("LS", ShortNames.auto("Lausanne, Bahnhof"))
        // Stops named "Town, Stop" keep the stop part; unknown names stay.
        assertEquals("Post", ShortNames.auto("Musterdorf, Post"))
        assertEquals("Beispielstadt", ShortNames.auto("Beispielstadt"))
        assertEquals("Zwischenau", ShortNames.auto("Zwischenau HB"))
    }

    @Test
    fun ownNameWinsEverywhere() {
        val post = Station("8599001", "Musterdorf, Post")
        val s = Settings(post, Station("8599003", "Beispielstadt"), buffers = listOf(Buffer(post, 10)))
            .withShortName("8599001", " MD ")
        assertEquals("MD", ShortNames.of(s.home!!))
        assertEquals("MD", s.buffers.single().station.short)
        assertEquals("Post", ShortNames.of(s.withShortName("8599001", "").home!!))
    }
}
