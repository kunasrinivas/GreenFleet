package com.greenfleet.app.data

import com.greenfleet.domain.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BackendClient(
    baseUrl: String,
    private val token: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
) : GeocodingService, RoutingService {
    private val base = baseUrl.toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null && it.encodedPath == "/") {
            "Use an HTTPS backend origin, with no path, credentials or query."
        }
    }
    private suspend fun post(path: String, json: JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
        val accessToken = token()
        if (accessToken.length < 32) {
            continuation.resumeWithException(IllegalArgumentException("Add your backend access token in Settings."))
            return@suspendCancellableCoroutine
        }
        val request = Request.Builder().url(base.newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer $accessToken")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Unable to reach the routing service. Check your connection and backend settings."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (!it.isSuccessful) throw IOException(when (it.code) {
                            401, 403 -> "Routing access denied. Check your backend token in Settings."
                            422 -> "A location could not be resolved precisely or reached by car. Check full addresses or use coordinates."
                            429 -> "Routing quota reached. Wait a minute, then retry."
                            else -> "The routing service could not complete the request. Please retry."
                        })
                        val body = requireNotNull(it.body) { "The routing service returned an empty response." }
                        val bytes = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                require(bytes.size() + count <= 8 * 1024 * 1024) { "Routing response is too large." }
                                bytes.write(buffer, 0, count)
                            }
                        }
                        JSONObject(bytes.toString("UTF-8"))
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    val safe = if (error is IOException) error else IOException("The routing service returned invalid data. Please retry.")
                    if (continuation.isActive) continuation.resumeWithException(safe)
                }
            }
        })
    }
    override suspend fun geocode(input: String, type: LocationType, id: String): Location {
        Coordinate.parse(input)?.let { return Location(id, input, input, it, type) }
        val result = post("v1/geocode", JSONObject().put("input", input))
        return parseSafely {
            val address = result.getString("address")
            require(address.isNotBlank() && address.length <= 300)
            Location(id, address.substringBefore(','), address, Coordinate(result.getDouble("lat"), result.getDouble("lng")), type)
        }
    }
    override suspend fun matrix(locations: List<Location>): RoadMatrix {
        val result = post("v1/matrix", points(locations))
        return parseSafely {
            val rows = result.getJSONArray("rows")
            require(rows.length() == locations.size)
            RoadMatrix(List(rows.length()) { i ->
                val row = rows.getJSONArray(i)
                require(row.length() == locations.size)
                List(row.length()) { j -> cost(row.getJSONObject(j)) }
            })
        }
    }
    override suspend fun route(orderedLocations: List<Location>): RoadPath {
        val result = post("v1/route", points(orderedLocations))
        return parseSafely {
            val legs = result.getJSONArray("legs")
            require(legs.length() == orderedLocations.size - 1)
            val polylines = result.getJSONArray("polylines")
            require(polylines.length() in 1..3)
            val geometry = buildList {
                for (i in 0 until polylines.length()) {
                    val part = decodePolyline(polylines.getString(i))
                    addAll(if (isNotEmpty() && last() == part.first()) part.drop(1) else part)
                }
            }
            RoadPath(geometry, List(legs.length()) { cost(legs.getJSONObject(it)) })
        }
    }
    private fun points(locations: List<Location>) = JSONObject().put("locations", JSONArray().apply {
        locations.forEach { put(JSONObject().put("lat", it.coordinate.lat).put("lng", it.coordinate.lng)) }
    })
    private fun cost(value: JSONObject) = RoadCost(value.getDouble("distanceMeters"), value.getDouble("durationSeconds"))
    private fun <T> parseSafely(block: () -> T): T = try { block() }
        catch (_: Exception) { throw IOException("The routing service returned incomplete or invalid data. Please retry.") }
}

internal fun decodePolyline(encoded: String): List<Coordinate> {
    require(encoded.length in 2..1000000)
    var index = 0; var lat = 0L; var lng = 0L
    fun delta(): Long {
        var result = 0L; var shift = 0
        while (true) {
            require(index < encoded.length && shift <= 30) { "Invalid route polyline." }
            val value = encoded[index++].code - 63
            require(value in 0..63)
            result = result or ((value and 31).toLong() shl shift)
            shift += 5
            if (value < 32) break
        }
        return if (result and 1L == 1L) (result shr 1).inv() else result shr 1
    }
    return buildList {
        while (index < encoded.length) {
            lat += delta(); lng += delta()
            require(size < 200000)
            add(Coordinate(lat / 1e5, lng / 1e5))
        }
        require(size >= 2)
    }
}

