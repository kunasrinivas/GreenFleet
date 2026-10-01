package com.greenfleet.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.greenfleet.app.GreenFleetApplication
import com.greenfleet.app.data.*
import com.greenfleet.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

enum class Page { PLAN, HISTORY, SETTINGS, PRIVACY }
data class FleetState(
    val page: Page = Page.PLAN,
    val demo: Boolean = true,
    val source: String = "",
    val dropoffs: List<String> = listOf("", ""),
    val finalDestination: String = "",
    val busy: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val route: Route? = null,
    val completedLegs: Int = 0,
    val privacyAccepted: Boolean = false,
    val backendUrl: String = "",
    val emissionFactor: String = "180",
    val hasToken: Boolean = false,
)

class FleetViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GreenFleetApplication
    private val settings = app.settings
    private val historyDao = app.database.history()
    private val mutable = MutableStateFlow(FleetState(privacyAccepted = settings.privacyAccepted,
        backendUrl = settings.backendUrl, emissionFactor = settings.emissionFactor.toString(), hasToken = settings.hasToken))
    val state = mutable.asStateFlow()
    val history = historyDao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var job: Job? = null

    fun page(page: Page) { mutable.update { it.copy(page = page, error = null) } }
    fun error(message: String?) { mutable.update { it.copy(error = message) } }
    fun acceptPrivacy() { settings.privacyAccepted = true; mutable.update { it.copy(privacyAccepted = true, page = Page.PLAN) } }
    fun edit(transform: (FleetState) -> FleetState) {
        if (!state.value.busy) mutable.update { transform(it).copy(route = null, completedLegs = 0, error = null) }
    }
    fun demo(value: Boolean) = edit { it.copy(demo = value, source = "", dropoffs = listOf("", ""), finalDestination = "") }
    fun loadDemo() = edit { it.copy(demo = true, source = DemoProvider.request.source,
        dropoffs = DemoProvider.request.dropoffs, finalDestination = DemoProvider.request.finalDestination.orEmpty()) }
    fun addStop() = edit { if (it.dropoffs.size < 20) it.copy(dropoffs = it.dropoffs + "") else it }
    fun removeStop(index: Int) = edit { if (it.dropoffs.size > 2) it.copy(dropoffs = it.dropoffs.filterIndexed { i, _ -> i != index }) else it }
    // Keep busy until the cancelled job's finally block runs, so it cannot clear a newer plan.
    fun cancel() { job?.cancel() }
    fun plan() {
        if (state.value.busy) return
        val snapshot = state.value
        if (!snapshot.privacyAccepted) { page(Page.PRIVACY); return }
        job = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, route = null, completedLegs = 0) }
            try {
                val request = RouteRequest(snapshot.source.trim(), snapshot.dropoffs.map(String::trim), snapshot.finalDestination.trim().ifBlank { null })
                request.validate()
                val planner = if (snapshot.demo) DemoProvider().let { PlanRoute(it, it) }
                    else {
                        require(BuildConfigBridge.mapsConfigured) { "Configure the Android Maps SDK key before using live routing. See the setup guide." }
                        require(snapshot.backendUrl.isNotBlank()) { "Add your HTTPS routing backend in Settings." }
                        val routeToken = withContext(Dispatchers.IO) { settings.token() }
                        BackendClient(snapshot.backendUrl, { routeToken }).let { PlanRoute(it, it) }
                    }
                val route = withTimeout(300000) {
                    planner(request, Vehicle(emissionFactorGPerKm = settings.emissionFactor), snapshot.demo) { message ->
                        mutable.update { it.copy(progress = message) }
                    }
                }
                historyDao.save(RouteHistory(route.id, route.createdAt.toEpochMilli(), route.locations.size - 1,
                    route.totalDistanceKm, route.totalDurationMinutes, route.totalCo2Grams, route.co2SavedGrams,
                    route.vehicle.emissionFactorGPerKm, route.isDemo, 0, route.optimizedOrder.size - 1))
                mutable.update { it.copy(route = route) }
            } catch (_: TimeoutCancellationException) { error("Route planning timed out. Try again with fewer stops.") }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error(e.message ?: "Check your route inputs.") }
            catch (e: IOException) { error(e.message ?: "The routing service is unavailable.") }
            catch (_: Exception) { error("Unable to prepare or save this route. Please retry.") }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }
    fun completeNext() {
        val snapshot = state.value
        val route = snapshot.route ?: return
        if (snapshot.completedLegs >= route.optimizedOrder.size - 1) return
        val completed = snapshot.completedLegs + 1
        mutable.update { it.copy(completedLegs = completed) }
        viewModelScope.launch {
            try { historyDao.updateProgress(route.id, completed) }
            catch (_: Exception) { error("Progress changed for this session, but could not be saved to history.") }
        }
    }
    fun saveSettings(url: String, token: String, factor: String) {
        if (state.value.busy) { error("Finish or cancel route planning before changing connection settings."); return }
        // Block planning while endpoint and token are saved together; do not mix credentials across origins.
        mutable.update { it.copy(busy = true, progress = "Saving secure settings") }
        viewModelScope.launch {
            try {
                val value = factor.toDoubleOrNull() ?: throw IllegalArgumentException("Enter an emission factor from 1 to 1000 g/km.")
                Vehicle(emissionFactorGPerKm = value)
                if (url.isNotBlank()) BackendClient(url.trim(), settings::token)
                if (token.isNotBlank()) require(token.length in 32..512 && token.all { it.code in 33..126 }) { "Use a valid access token (32–512 characters)." }
                withContext(Dispatchers.IO) {
                    // A changed endpoint must never inherit the previous endpoint's token.
                    if (url.trim() != settings.backendUrl) settings.setToken("")
                    if (token.isNotBlank()) settings.setToken(token)
                    settings.backendUrl = url.trim(); settings.emissionFactor = value
                }
                mutable.update { it.copy(backendUrl = url.trim(), emissionFactor = value.toString(), hasToken = settings.hasToken, error = null, page = Page.PLAN) }
            } catch (e: IllegalArgumentException) { error(e.message ?: "Check your settings.") }
            catch (_: Exception) { error("Secure settings could not be saved. Please try again.") }
            finally { mutable.update { it.copy(busy = false, progress = "") } }
        }
    }
    fun removeToken() {
        if (state.value.busy) { error("Finish or cancel route planning before removing the access token."); return }
        viewModelScope.launch(Dispatchers.IO) {
        try { settings.setToken(""); mutable.update { it.copy(hasToken = false) } }
        catch (_: Exception) { error("The access token could not be removed. Please retry.") }
    } }
    fun clearHistory() { viewModelScope.launch {
        try { historyDao.clear() } catch (_: Exception) { error("History could not be cleared. Please retry.") }
    } }
}
private object BuildConfigBridge { val mapsConfigured = com.greenfleet.app.BuildConfig.MAPS_CONFIGURED }
