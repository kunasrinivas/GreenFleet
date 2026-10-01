package com.greenfleet.app.data

import com.greenfleet.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class BackendClientTest {
    private fun <T> httpsTest(block: (BackendClient, MockWebServer) -> T): T {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        return MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            val http = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .followRedirects(false).followSslRedirects(false).build()
            block(BackendClient(server.url("/").toString(), { "test-access-token-".repeat(3) }, http), server)
        }
    }
    @Test fun `HTTPS geocoding and matrix preserve directed distances`() = httpsTest { api, server -> runBlocking {
        server.enqueue(MockResponse().setBody("""{"address":"Precise test address","lat":52.0,"lng":4.0}"""))
        val source = api.geocode("Test address", LocationType.SOURCE, "0")
        assertEquals(Coordinate(52.0, 4.0), source.coordinate)
        val request = server.takeRequest()
        assertEquals("/v1/geocode", request.path)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
        val rows = JSONArray().apply { repeat(3) { i -> put(JSONArray().apply { repeat(3) { j ->
            put(JSONObject().put("distanceMeters", if (i == j) 0 else i * 100 + j).put("durationSeconds", if (i == j) 0 else 30))
        } }) } }
        server.enqueue(MockResponse().setBody(JSONObject().put("rows", rows).toString()))
        val matrix = api.matrix(List(3) { source.copy(id = it.toString()) })
        assertEquals(102.0, matrix[1, 2].distanceMeters, 0.0)
        assertEquals(201.0, matrix[2, 1].distanceMeters, 0.0)
    } }
    @Test fun `route decodes Google geometry and validates returned leg count`() = httpsTest { api, server -> runBlocking {
        val places = List(2) { Location(it.toString(), "Test", "Test", Coordinate(52.0, 4.0 + it), LocationType.DROPOFF) }
        val body = JSONObject().put("legs", JSONArray().put(JSONObject().put("distanceMeters", 1000).put("durationSeconds", 120)))
            .put("polylines", JSONArray().put("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
        server.enqueue(MockResponse().setBody(body.toString()))
        val route = api.route(places)
        assertEquals(3, route.geometry.size)
        assertEquals(Coordinate(38.5, -120.2), route.geometry.first())
        assertEquals(1.0, route.cost.distanceKm, 0.0)
        server.enqueue(MockResponse().setBody(body.put("legs", JSONArray()).toString()))
        try { api.route(places); fail("Missing legs accepted") } catch (_: IOException) { }
    } }
    @Test fun `cleartext and redirected endpoints are rejected`() {
        for (url in listOf("http://localhost/", "https://user:pass@example.com/", "https://example.com/?token=secret", "https://example.com/path")) {
            try { BackendClient(url, { "" }); fail("Unsafe origin accepted") } catch (_: IllegalArgumentException) { }
        }
        httpsTest { api, server -> runBlocking {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
            try { api.geocode("Test address", LocationType.SOURCE, "0"); fail("Redirect followed") } catch (_: IOException) { }
            assertEquals(1, server.requestCount)
        } }
    }
    @Test fun `malformed response fails with sanitized message`() = httpsTest { api, server -> runBlocking {
        server.enqueue(MockResponse().setBody("{private-address malformed"))
        try { api.geocode("Test address", LocationType.SOURCE, "0"); fail("Invalid JSON accepted") }
        catch (error: IOException) { assertFalse(error.message!!.contains("private-address")) }
    } }
    @Test fun `polyline rejects truncated overlong and invalid encoded coordinates`() {
        for (value in listOf("", "_", "___________?", "!!", "_p~iF~ps|U_")) {
            try { decodePolyline(value); fail("Malformed polyline accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}

