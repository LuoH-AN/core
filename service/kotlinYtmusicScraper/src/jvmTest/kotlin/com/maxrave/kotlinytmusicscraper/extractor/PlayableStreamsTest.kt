package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayableStreamsTest {
    private fun withStreams(
        statuses: List<Pair<Int, Int>>,
        check: (List<Pair<Int, String>>, MutableList<Int>) -> Unit,
    ) {
        val probes = mutableListOf<Int>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        statuses.forEach { (itag, status) ->
            server.createContext("/$itag") { exchange ->
                probes.add(itag)
                assertEquals("bytes=0-0", exchange.requestHeaders.getFirst("Range"))
                exchange.sendResponseHeaders(status, 1)
                exchange.responseBody.use { it.write(0) }
                exchange.close()
            }
        }
        server.start()
        try {
            val streams = statuses.map { (itag, _) -> itag to "http://127.0.0.1:${server.address.port}/$itag" }
            check(streams, probes)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aacOnlySongIsProbedAndAccepted() {
        // The exported log repeatedly rejected 7rOaV9Pr2Qs before probing its sole itag 140.
        withStreams(listOf(ITAG.AUDIO_AAC_MEDIUM to 206)) { streams, probes ->
            assertTrue(streams.hasPlayableAudio())
            assertEquals(streams, streams.playableStreams("7rOaV9Pr2Qs", "test"))
            assertEquals(listOf(ITAG.AUDIO_AAC_MEDIUM), probes)
        }
    }

    @Test
    fun rejectedOpusFallsBackToAacAndPreservesVideo() {
        withStreams(
            listOf(ITAG.AUDIO_OPUS_MEDIUM to 403, ITAG.AUDIO_AAC_MEDIUM to 206, ITAG.VIDEO_720P to 200),
        ) { streams, probes ->
            assertEquals(streams.drop(1), streams.playableStreams("song", "test"))
            assertEquals(listOf(ITAG.AUDIO_OPUS_MEDIUM, ITAG.AUDIO_AAC_MEDIUM), probes)
        }
    }

    @Test
    fun lowestOpusIsUsableWhenOtherRenditionsAreRejected() {
        withStreams(listOf(ITAG.AUDIO_OPUS_MEDIUM to 403, ITAG.AUDIO_OPUS_LOWEST to 206)) { streams, probes ->
            assertEquals(streams.takeLast(1), streams.playableStreams("song", "test"))
            assertEquals(listOf(ITAG.AUDIO_OPUS_MEDIUM, ITAG.AUDIO_OPUS_LOWEST), probes)
        }
    }

    @Test
    fun rejectedAacDoesNotMasqueradeAsPlayableAudio() {
        withStreams(listOf(ITAG.AUDIO_AAC_MEDIUM to 403)) { streams, probes ->
            assertTrue(streams.playableStreams("song", "test").isEmpty())
            assertEquals(listOf(ITAG.AUDIO_AAC_MEDIUM), probes)
        }
    }

    @Test
    fun transientRateLimitStillAllowsPlayback() {
        withStreams(listOf(ITAG.AUDIO_AAC_MEDIUM to 429)) { streams, _ ->
            assertEquals(streams, streams.playableStreams("song", "test"))
        }
    }

    @Test
    fun videoAndBlankAudioDoNotPassTheAudioGate() {
        assertFalse(listOf(ITAG.VIDEO_720P to "https://example.com/video", ITAG.AUDIO_AAC_MEDIUM to " ").hasPlayableAudio())
    }
}
