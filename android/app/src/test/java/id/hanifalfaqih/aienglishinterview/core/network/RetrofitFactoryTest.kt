package id.hanifalfaqih.aienglishinterview.core.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** Focused contract: the shared client carries voice-turn-safe timeouts. */
class RetrofitFactoryTest {

    @Test
    fun `shared client uses bounded voice-turn timeouts`() {
        val retrofit = RetrofitFactory.newRetrofit("http://127.0.0.1:3001/")
        val client = retrofit.callFactory() as OkHttpClient

        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(60_000, client.callTimeoutMillis)
    }
}
