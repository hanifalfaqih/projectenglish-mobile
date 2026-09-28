package id.hanifalfaqih.aienglishinterview.core.voice

import java.util.Base64 as JavaBase64
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeIdentity(
    private val value: AppIdentity?,
) : AppIdentityProvider {
    override fun current(): AppIdentity? = value
}

private fun transport(
    server: MockWebServer,
    key: String = "test-key",
    identity: AppIdentityProvider? = null,
): GoogleSttTransport {
    // Route the fixed-endpoint transport at the mock server, preserving the
    // official path and key query parameter.
    return GoogleSttTransport(
        apiKey = key,
        client = OkHttpClient.Builder().build(),
        base64 = { JavaBase64.getEncoder().encodeToString(it) },
        endpoint = server.url("/v1/speech:recognize").toString(),
        identity = identity,
    )
}

private fun successJson(vararg transcripts: String): String {
    val alternatives = transcripts.joinToString(",") { "{\"transcript\":\"$it\"}" }
    return "{\"results\":[{\"alternatives\":[$alternatives]}]}"
}

class GoogleSttTransportTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesFirstUsableTranscript() {
        val outcome = GoogleSttTransport.parseResponse(200, successJson("", "hello world"))
        assertEquals(SttOutcome.Transcript("hello world"), outcome)
    }

    @Test
    fun emptyResultsIsNoSpeech() {
        assertEquals(SttOutcome.NoSpeech, GoogleSttTransport.parseResponse(200, "{\"results\":[]}"))
        assertEquals(SttOutcome.NoSpeech, GoogleSttTransport.parseResponse(200, "{}"))
    }

    @Test
    fun malformedBodyIsFailure() {
        val outcome = GoogleSttTransport.parseResponse(200, "not json{{{")
        assertTrue(outcome is SttOutcome.Failure)
    }

    @Test
    fun unauthorizedMapsToAuthMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("denied"))
        val outcome = transport(server).transcribe(ByteArray(320))
        assertTrue(outcome is SttOutcome.Failure)
        assertTrue((outcome as SttOutcome.Failure).message.contains("key", ignoreCase = true))
    }

    @Test
    fun forbiddenMapsToAuthMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
        val outcome = transport(server).transcribe(ByteArray(320))
        assertTrue(outcome is SttOutcome.Failure)
    }

    @Test
    fun rateLimitedMapsToRetryMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        val outcome = transport(server).transcribe(ByteArray(320))
        assertTrue(outcome is SttOutcome.Failure)
        assertTrue((outcome as SttOutcome.Failure).message.contains("busy", ignoreCase = true))
    }

    @Test
    fun serverErrorMapsToFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val outcome = transport(server).transcribe(ByteArray(320))
        assertTrue(outcome is SttOutcome.Failure)
    }

    @Test
    fun networkFailureMapsToFailure() = runTest {
        server.shutdown()
        val outcome = transport(server).transcribe(ByteArray(320))
        assertTrue(outcome is SttOutcome.Failure)
    }

    @Test
    fun requestUsesOfficialShape() = runTest {
        server.enqueue(MockResponse().setBody(successJson("hi")))
        val outcome = transport(server, key = "k123").transcribe(ByteArray(320))
        assertEquals(SttOutcome.Transcript("hi"), outcome)
        val recorded = server.takeRequest()
        val path = requireNotNull(recorded.path)
        assertEquals("/v1/speech:recognize", path.substringBefore("?"))
        assertTrue(path.contains("key=k123"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"encoding\":\"LINEAR16\""))
        assertTrue(body.contains("\"sampleRateHertz\":16000"))
        assertTrue(body.contains("\"languageCode\":\"en-US\""))
        assertTrue(body.contains("\"content\":\""))
    }

    @Test
    fun defaultClientHasNoInterceptors_keySafety() {
        assertTrue(GoogleSttTransport.defaultSttClient().interceptors.isEmpty())
        assertTrue(GoogleSttTransport.defaultSttClient().networkInterceptors.isEmpty())
    }

    @Test
    fun androidIdentityHeaders_present() = runTest {
        server.enqueue(MockResponse().setBody(successJson("hi")))
        val api = transport(
            server,
            identity = FakeIdentity(AppIdentity("com.example.app", "AABBCC")),
        )
        val outcome = api.transcribe(ByteArray(320))
        assertEquals(SttOutcome.Transcript("hi"), outcome)
        val recorded = server.takeRequest()
        assertEquals("com.example.app", recorded.getHeader("X-Android-Package"))
        assertEquals("AABBCC", recorded.getHeader("X-Android-Cert"))
        // API-key auth is unchanged.
        assertTrue(requireNotNull(recorded.path).contains("key=test-key"))
    }

    @Test
    fun identityValue_sentVerbatim() = runTest {
        server.enqueue(MockResponse().setBody(successJson("hi")))
        // Normalization (colon stripping/uppercase) is owned by
        // PackageManagerAppIdentity and unit-tested below; the transport
        // sends the provided normalized identity unchanged.
        val api = transport(
            server,
            identity = FakeIdentity(AppIdentity("com.example.app", "AABBCC")),
        )
        api.transcribe(ByteArray(320))
        val recorded = server.takeRequest()
        assertEquals("AABBCC", recorded.getHeader("X-Android-Cert"))
    }

    @Test
    fun missingIdentity_failsWithoutNetwork() = runTest {
        val api = transport(server, identity = FakeIdentity(null))
        val outcome = api.transcribe(ByteArray(320))
        assertTrue(outcome is SttOutcome.Failure)
        assertTrue(
            (outcome as SttOutcome.Failure).message.contains("identity", ignoreCase = true),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sha1Hex_isUppercaseWithoutColons() {
        val bytes = byteArrayOf(0x0A, 0xFF.toByte(), 0x00)
        assertEquals("0AFF00", PackageManagerAppIdentity.sha1Hex(bytes))
    }

    @Test
    fun normalizeCert_stripsColonsAndUppercases() {
        assertEquals("AABBCC", PackageManagerAppIdentity.normalizeCert("aa:bb:cc"))
        assertEquals("AABBCC", PackageManagerAppIdentity.normalizeCert("AABBCC"))
    }
}
