package com.greenfleet.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greenfleet.app.data.RouteHistory
import com.greenfleet.domain.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val Forest = Color(0xFF116B48)
private val Ink = Color(0xFF19372C)
private val Muted = Color(0xFF64766C)
private val Paper = Color(0xFFF5F7F3)
private val Mint = Color(0xFFE5F2E9)
private fun number(value: Double, digits: Int = 1) = String.format(Locale.getDefault(), "%.${digits}f", value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreenFleetApp(model: FleetViewModel, onLocation: () -> Unit, onNavigate: (Coordinate) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    var locationRationale by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White, primaryContainer = Mint,
        onPrimaryContainer = Ink, surface = Color.White, background = Paper, onBackground = Ink,
        onSurface = Ink, onSurfaceVariant = Muted, outline = Color(0xFFBACABF))) {
        Scaffold(containerColor = Paper,
            topBar = {
                CenterAlignedTopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Outlined.Eco, null, tint = Forest, modifier = Modifier.size(30.dp))
                        Text("GreenFleet", fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                    }
                }, actions = { IconButton(onClick = { model.page(Page.PRIVACY) }) { Icon(Icons.Outlined.PrivacyTip, "Privacy notice") } },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Paper))
            },
            bottomBar = {
                if (state.privacyAccepted && state.page != Page.PRIVACY) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    listOf(Triple(Page.PLAN, "Plan", Icons.Outlined.Route), Triple(Page.HISTORY, "History", Icons.Outlined.History),
                        Triple(Page.SETTINGS, "Settings", Icons.Outlined.Tune)).forEach { (page, label, icon) ->
                        NavigationBarItem(selected = state.page == page, onClick = { model.page(page) }, icon = { Icon(icon, label) }, label = { Text(label) })
                    }
                }
            }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                state.error?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { model.error(null) }) { Icon(Icons.Outlined.Close, "Dismiss message") }
                        }
                    }
                }
                if (!state.privacyAccepted || state.page == Page.PRIVACY) PrivacyScreen(state.privacyAccepted, model::acceptPrivacy) { model.page(Page.PLAN) }
                else when (state.page) {
                    Page.PLAN -> if (state.route != null) ResultScreen(state.route!!, state.completedLegs,
                        onEdit = { model.edit { it } }, onNavigate = onNavigate, onComplete = model::completeNext)
                        else PlannerScreen(state, model, onLocation = { locationRationale = true })
                    Page.HISTORY -> HistoryScreen(history, model::clearHistory)
                    Page.SETTINGS -> SettingsScreen(state, model::saveSettings, model::removeToken)
                    Page.PRIVACY -> Unit
                }
            }
        }
        if (locationRationale) AlertDialog(onDismissRequest = { locationRationale = false },
            title = { Text("Use your location as the depot?") },
            text = { Text("GreenFleet requests foreground location once to fill the depot coordinates. It stops when a fix is found, after 20 seconds, or when you leave the app. You can always type an address instead.") },
            confirmButton = { TextButton(onClick = { locationRationale = false; onLocation() }) { Text("Use location") } },
            dismissButton = { TextButton(onClick = { locationRationale = false }) { Text("Enter manually") } })
    }
}

@Composable
private fun Heading(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow.uppercase(), color = Forest, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(title, color = Ink, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun PlannerScreen(state: FleetState, model: FleetViewModel, onLocation: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Heading("Every kilometre counts", "Deliver more.\nDrive less.", "One car. A smarter round trip. Lower estimated emissions.") }
        item {
            Surface(color = Mint, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (state.demo) Icons.Outlined.Science else Icons.Outlined.Public, null, tint = Forest)
                        Text(if (state.demo) "Offline demo" else "Live road routing", Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.Bold)
                        Switch(checked = !state.demo, onCheckedChange = { model.demo(!it) }, enabled = !state.busy)
                    }
                    Text(if (state.demo) "Explore with sample addresses and synthetic distances. No network calls." else "Addresses are sent to your routing backend and Google Maps Platform.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (state.demo) TextButton(onClick = model::loadDemo, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) {
                        Text("Load Amsterdam demo"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
        item {
            Panel {
                SectionTitle(Icons.Outlined.Warehouse, "Depot", "Start & return")
                AddressField(state.source, { value -> model.edit { it.copy(source = value) } }, "Depot address or lat,lng", !state.busy)
                if (!state.demo) TextButton(onClick = onLocation, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use my current location")
                }
            }
        }
        item { SectionTitle(Icons.Outlined.LocalShipping, "Drop-offs", "${state.dropoffs.size} / 20 stops") }
        items(state.dropoffs.size) { index ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StopNumber((index + 1).toString())
                AddressField(state.dropoffs[index], { value -> model.edit { current ->
                    current.copy(dropoffs = current.dropoffs.toMutableList().apply { this[index] = value })
                } }, "Drop-off ${index + 1}", !state.busy, Modifier.weight(1f))
                if (state.dropoffs.size > 2) IconButton(onClick = { model.removeStop(index) }, enabled = !state.busy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, "Remove drop-off ${index + 1}", tint = Muted)
                }
            }
        }
        item {
            OutlinedButton(onClick = model::addStop, enabled = state.dropoffs.size < 20 && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add drop-off")
            }
        }
        item {
            Panel {
                SectionTitle(Icons.Outlined.Flag, "Final destination", "Optional")
                AddressField(state.finalDestination, { value -> model.edit { it.copy(finalDestination = value) } }, "Final address or lat,lng", !state.busy)
                Text("This stop stays last, before returning to the depot. If blank, the last entered drop-off stays last.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DirectionsCar, null, tint = Muted)
                Text("Petrol car", Modifier.weight(1f).padding(start = 8.dp), color = Muted)
                Text("${state.emissionFactor.removeSuffix(".0")} g CO₂/km", color = Forest, fontWeight = FontWeight.Medium)
            }
        }
        item {
            if (state.busy) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)
                    Text(state.progress, color = Muted)
                    OutlinedButton(onClick = model::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel planning") }
                }
            } else Button(onClick = model::plan, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(10.dp)); Text("Optimize round trip", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { Text("Road distance first · Travel time breaks ties · Return included", color = Muted, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String, trailing: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Forest, modifier = Modifier.size(21.dp))
        Text(title, Modifier.weight(1f).padding(start = 9.dp), fontWeight = FontWeight.SemiBold)
        Text(trailing, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun AddressField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { if (it.length <= 300) onChange(it) }, modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label, fontSize = 13.sp) }, maxLines = 2, shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, unfocusedBorderColor = Color(0xFFD8E0D9)))
}

@Composable
private fun StopNumber(label: String, completed: Boolean = false) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(if (completed) Forest else Mint), contentAlignment = Alignment.Center) {
        if (completed) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        else Text(label, color = Forest, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun ResultScreen(route: Route, completed: Int, onEdit: () -> Unit, onNavigate: (Coordinate) -> Unit, onComplete: () -> Unit) {
    val totalLegs = route.optimizedOrder.size - 1
    val next = route.orderedLocations.getOrNull(completed + 1)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Heading(if (route.isDemo) "Demo · Synthetic estimates" else "Your round trip", "A little less driving.\nA lighter footprint.", "${route.locations.size - 1} destinations · Petrol car · Return to depot included") }
        item {
            Surface(color = Forest, shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Eco, null, tint = Color(0xFFBEE7AC)); Spacer(Modifier.width(9.dp)); Text("ESTIMATED CO₂ SAVED", color = Color.White, fontSize = 11.sp, letterSpacing = 1.5.sp) }
                    Text("${number(route.co2SavedGrams / 1000, 2)} kg", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    Text("${number(route.distanceSavedKm)} km less than the entered stop order", color = Color(0xFFD3E8D9), fontSize = 13.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Distance", "${number(route.totalDistanceKm)}", "km", Modifier.weight(1f))
                Metric("Drive time", number(route.totalDurationMinutes, 0), "min", Modifier.weight(1f))
                Metric("Est. CO₂", number(route.totalCo2Grams / 1000, 2), "kg", Modifier.weight(1f))
            }
        }
        item { RouteMap(route) }
        item {
            Panel {
                SectionTitle(Icons.Outlined.CompareArrows, "Compared with entered order", "Same car")
                Comparison("Distance", "${number(route.baselineCost.distanceKm)} km", "${number(route.totalDistanceKm)} km")
                Comparison("Drive time", "${number(route.baselineCost.durationMinutes)} min", "${number(route.totalDurationMinutes)} min")
                Comparison("Estimated CO₂", "${number(Co2Calculator.grams(route.baselineCost.distanceKm, route.vehicle) / 1000, 2)} kg", "${number(route.totalCo2Grams / 1000, 2)} kg")
                Text(if (route.timeSavedMinutes >= 0) "${number(route.timeSavedMinutes)} min of driving saved." else "${number(abs(route.timeSavedMinutes))} min more driving; this route prioritizes shorter distance.", style = MaterialTheme.typography.bodySmall, color = Muted)
                if (route.optimizedOrder == route.baselineOrder) Text("The entered order was retained: no shorter route was found.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        item { SectionTitle(Icons.Outlined.Route, "Stop order", "$completed / $totalLegs legs complete") }
        items(route.orderedLocations.size) { index ->
            val stop = route.orderedLocations[index]
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StopNumber(if (index == 0 || index == totalLegs) "D" else index.toString(), index in 1..completed)
                Column(Modifier.weight(1f)) {
                    Text(when (index) { 0 -> "Start · ${stop.name}"; totalLegs -> "Return · ${stop.name}"; totalLegs - 1 -> "Final · ${stop.name}"; else -> stop.name }, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(stop.address, style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (index == completed + 1) Text("NEXT STOP", fontSize = 10.sp, color = Forest, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item {
            Panel {
                if (next != null) {
                    Text(if (route.isDemo) "Try the route progress" else if (completed == 0) "Ready to leave the depot?" else "Continue to the next stop", fontWeight = FontWeight.Bold)
                    if (!route.isDemo) Button(onClick = { onNavigate(next.coordinate) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Outlined.Navigation, null); Spacer(Modifier.width(8.dp)); Text(if (completed == 0) "Start route" else "Navigate to next stop")
                    }
                    OutlinedButton(onClick = onComplete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (route.isDemo) "Simulate arrival" else if (completed == totalLegs - 1) "Confirm return to depot" else "Mark next stop completed")
                    }
                    Text(if (route.isDemo) "Navigation is disabled for synthetic demo routes." else "Google Maps navigates each leg and may choose a different road path. Return here to confirm arrival. Stops are never completed automatically.", style = MaterialTheme.typography.bodySmall, color = Muted)
                } else {
                    Icon(Icons.Outlined.TaskAlt, null, tint = Forest, modifier = Modifier.size(32.dp))
                    Text("Round trip complete", fontWeight = FontWeight.Bold)
                    Text("All destinations visited and return to depot confirmed.", color = Muted)
                }
            }
        }
        item {
            Text("CO₂ is a planning estimate: distance × ${number(route.vehicle.emissionFactorGPerKm, 0)} g/km. Average speed: ${number(route.path.cost.averageSpeedKmh)} km/h. Traffic, idling, elevation, driving style and stop service time are not modeled. Lower speed is not assumed to be cleaner.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        item { OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit route") } }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, fontSize = 11.sp, color = Muted)
            Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text(unit, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun Comparison(label: String, before: String, after: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp, color = Muted)
        Text(before, fontSize = 12.sp, color = Muted)
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(horizontal = 8.dp).size(13.dp), tint = Muted)
        Text(after, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Forest)
    }
}

@Composable
private fun RouteMap(route: Route) {
    if (route.isDemo) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFE8EFE7)) {
            Column(Modifier.padding(18.dp)) {
                Text("DEMO ROUTE DIAGRAM", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Forest)
                Text("Illustrative connections · not road geometry", fontSize = 11.sp, color = Muted)
                Canvas(Modifier.fillMaxWidth().height(180.dp).padding(16.dp)) {
                    val nodes = route.orderedLocations.map { it.coordinate }
                    val minLat = nodes.minOf { it.lat }; val maxLat = nodes.maxOf { it.lat }
                    val minLng = nodes.minOf { it.lng }; val maxLng = nodes.maxOf { it.lng }
                    val positions = nodes.map { Offset(((it.lng - minLng) / (maxLng - minLng)).toFloat() * size.width,
                        (1 - (it.lat - minLat) / (maxLat - minLat)).toFloat() * size.height) }
                    val path = Path().apply { moveTo(positions[0].x, positions[0].y); positions.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, Color.White, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(path, Forest, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    positions.dropLast(1).forEachIndexed { index, point ->
                        drawCircle(Color.White, 8.dp.toPx(), point); drawCircle(if (index == 0) Ink else Forest, 5.dp.toPx(), point)
                    }
                }
            }
        }
    } else {
        val camera = rememberCameraPositionState()
        var loaded by remember { mutableStateOf(false) }
        val bounds = remember(route.id) { LatLngBounds.builder().apply { route.path.geometry.forEach { include(LatLng(it.lat, it.lng)) } }.build() }
        LaunchedEffect(route.id, loaded) { if (loaded) camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 75)) }
        Column {
            GoogleMap(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(20.dp)), cameraPositionState = camera,
                onMapLoaded = { loaded = true }, uiSettings = MapUiSettings(mapToolbarEnabled = false, myLocationButtonEnabled = false)) {
                Polyline(points = route.path.geometry.map { LatLng(it.lat, it.lng) }, color = Forest, width = 11f)
                route.orderedLocations.dropLast(1).forEachIndexed { index, location ->
                    Marker(state = rememberUpdatedMarkerState(LatLng(location.coordinate.lat, location.coordinate.lng)),
                        title = if (index == 0) "Depot" else "$index. ${location.name}", snippet = location.address)
                }
            }
            Text("Road route from Google Maps · Driving estimates exclude live traffic", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable
private fun HistoryScreen(routes: List<RouteHistory>, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Your recent journeys", "Route history", "The last 20 plans, stored on this device. Only statistics are saved; addresses and route geometry are not retained.") }
        if (routes.isEmpty()) item { Panel { Icon(Icons.Outlined.History, null, tint = Forest, modifier = Modifier.size(40.dp)); Text("Your first route starts here.", fontWeight = FontWeight.Bold); Text("Plan a route to see its statistics in your history.", color = Muted) } }
        items(routes, key = { it.id }) { route ->
            Panel {
                Row {
                    Text(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(route.createdAt)), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (route.isDemo) Text("DEMO", color = Forest, fontSize = 10.sp)
                }
                Text("${route.stops} destinations · ${number(route.distanceKm)} km · ${number(route.co2Grams / 1000, 2)} kg estimated CO₂", color = Muted, fontSize = 13.sp)
                Text("${number(route.co2SavedGrams / 1000, 2)} kg estimated savings", color = Forest, fontWeight = FontWeight.Medium)
                Text(if (route.completedLegs == route.totalLegs) "Round trip complete" else "${route.completedLegs} of ${route.totalLegs} legs confirmed", color = Muted, fontSize = 11.sp)
            }
        }
        if (routes.isNotEmpty()) item { TextButton(onClick = { confirmClear = true }) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Clear route history") } }
        item { Text("Active routes remain in memory during this app session. Closing the app can discard the active route; history preserves its statistics.", style = MaterialTheme.typography.bodySmall, color = Muted) }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear route history?") }, text = { Text("This removes saved statistics from this device.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep history") } })
}

@Composable
private fun SettingsScreen(state: FleetState, onSave: (String, String, String) -> Unit, onRemoveToken: () -> Unit) {
    var url by rememberSaveable(state.backendUrl) { mutableStateOf(state.backendUrl) }
    var factor by rememberSaveable(state.emissionFactor) { mutableStateOf(state.emissionFactor) }
    var token by remember { mutableStateOf("") } // Never put tokens in SavedState or logs.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Heading("Make it yours", "Fleet settings", "One standard petrol car, with an emission factor you can calibrate.") }
        item {
            Panel {
                SectionTitle(Icons.Outlined.Eco, "CO₂ estimate", "Petrol")
                OutlinedTextField(factor, { if (it.length <= 8) factor = it }, label = { Text("Emission factor · g CO₂/km") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("180 g/km is an illustrative default, not a certified vehicle measurement. Use a defensible factor for your car. Changes apply to new plans.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        item {
            Panel {
                SectionTitle(Icons.Outlined.Cloud, "Live routing", "Google Maps")
                OutlinedTextField(url, { if (it.length <= 300) url = it }, label = { Text("HTTPS backend origin") }, placeholder = { Text("https://routing.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(token, { if (it.length <= 512) token = it }, label = { Text(if (state.hasToken) "Replace access token (optional)" else "Backend access token") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text(if (state.hasToken) "An access token is stored securely on this device." else "Add the token provided by your backend operator.", style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("Changing the backend address clears its previous token. The offline demo works without backend setup.", style = MaterialTheme.typography.bodySmall, color = Muted)
                if (state.hasToken) TextButton(onClick = onRemoveToken) { Text("Remove stored access token") }
            }
        }
        item { Button(onClick = { onSave(url, token, factor); token = "" }, Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Save settings") } }
    }
}

@Composable
private fun PrivacyScreen(accepted: Boolean, onAccept: () -> Unit, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Heading("Private by design", "Your route.\nYour data.", "Before your first trip, here is how GreenFleet handles your information.") }
        item { Panel {
            PrivacyItem("What you enter", "Depot, drop-offs and final destination addresses or coordinates are used to plan a car route. The optional location button reads your foreground location once; there is no background tracking.")
            PrivacyItem("Where it goes", "In live mode, addresses and coordinates go over HTTPS to the routing backend you configure and Google Maps Platform for geocoding, road distances and maps. Starting navigation opens Google Maps (or its website) and sends the next destination. No other third-party sharing is implemented.")
            PrivacyItem("What stays here", "The last 20 route summaries and completion counts are saved locally. Addresses and route geometry stay in app memory only. You can clear history in the History tab. Backups are disabled. Backend access tokens are encrypted with an Android Keystore key.")
            PrivacyItem("What the estimate means", "CO₂ equals route distance × your configured petrol-car emission factor. It is an estimated tailpipe figure, not measured emissions. Traffic, idling, elevation and driving style can change actual results.")
            PrivacyItem("Offline demo", "The sample runs entirely on this device with synthetic distances. It is for exploring the app, not real driving or environmental reporting.")
        } }
        item { Text("The configured backend operator controls its hosting. This MVP backend does not log addresses or retain routing responses. Google's services are subject to Google's privacy policy and service terms.", style = MaterialTheme.typography.bodySmall, color = Muted) }
        item {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(onClick = { uri.openUri("https://policies.google.com/privacy") }) { Text("Google privacy policy") }
            TextButton(onClick = { uri.openUri("https://maps.google.com/help/terms_maps/") }) { Text("Google Maps terms") }
        }
        item { Button(onClick = if (accepted) onBack else onAccept, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (accepted) "Back to route planner" else "Continue to GreenFleet") } }
    }
}

@Composable
private fun PrivacyItem(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, fontWeight = FontWeight.Bold, color = Forest); Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted) }
}
