package com.greenfleet.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.greenfleet.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class DemoFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun offlineDemoPlansRoundTripAndTracksReturn() {
        fun reveal(text: String) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GreenFleetApplication
        if (!app.settings.privacyAccepted) {
            reveal("Continue to GreenFleet")
            compose.onNodeWithText("Continue to GreenFleet").performClick()
        }
        reveal("Load Amsterdam demo")
        compose.onNodeWithText("Load Amsterdam demo").performClick()
        reveal("Optimize round trip")
        compose.onNodeWithText("Optimize round trip").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("1.44 kg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1.44 kg").assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { screenshot ->
            java.io.File(app.cacheDir, "demo-summary.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
        repeat(7) { reveal("Simulate arrival"); compose.onNodeWithText("Simulate arrival").performClick() }
        reveal("Round trip complete")
        compose.onNodeWithText("Round trip complete").assertIsDisplayed()
        compose.onNodeWithText("History", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Route history").assertIsDisplayed()
    }

    @Test fun historyRetainsOnlyLast20AndCanBeDeleted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        try {
            repeat(25) { db.history().save(RouteHistory(it.toString(), it.toLong(), 5, 12.0, 24.0, 2160.0, 1440.0, 180.0, true, 0, 7)) }
            val entries = db.history().observe().first()
            assertEquals(20, entries.size); assertEquals("24", entries.first().id); assertEquals("5", entries.last().id)
            db.history().updateProgress("24", 7)
            assertEquals(7, db.history().observe().first().first().completedLegs)
            db.history().clear(); assertTrue(db.history().observe().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun accessTokenIsEncryptedAtRest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SecureSettings(context)
        val previous = settings.token()
        val value = "instrumentation-test-token-".repeat(2)
        try {
            settings.setToken(value)
            assertEquals(value, settings.token())
            val raw = context.getSharedPreferences("greenfleet_settings", 0).getString("access_token", "")!!
            assertFalse(raw.contains(value))
            assertNotEquals(value, raw)
            settings.setToken(""); assertEquals("", settings.token())
        } finally { settings.setToken(previous) }
    }
}
