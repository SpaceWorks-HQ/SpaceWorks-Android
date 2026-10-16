package org.spaceworks.app

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.theme.BrandMotion

class OnboardingTest {
    @Test fun skipCompletionReplayAndThemePersistWithoutTouchingAccountSettings() {
        val settings=mutableMapOf("server" to "https://example.invalid")
        val intro=OnboardingStateHolder(false){settings["intro"]=it.toString()}
        assertFalse(intro.completed.value);intro.finish();assertEquals("true",settings["intro"])
        val returning=OnboardingStateHolder(settings["intro"].toBoolean()){settings["intro"]=it.toString()}
        assertTrue(returning.completed.value);returning.replay();assertEquals("https://example.invalid",settings["server"])
        val appearance=AppearanceStateHolder(false){settings["dark"]=it.toString()};appearance.setDark(true)
        assertTrue(AppearanceStateHolder(settings["dark"].toBoolean()){}.dark.value)
    }
    @Test fun motionComesFromPinnedBrandAndReducedMotionHasNoCustomDelay() {
        val motion=Json.parseToJsonElement(File("../design/tokens/mobile-motion.json").readText()).jsonObject
        assertEquals(motion["duration_ms"]!!.jsonObject["color"]!!.jsonPrimitive.int,BrandMotion.COLOR)
        assertEquals(motion["duration_ms"]!!.jsonObject["overlay_enter"]!!.jsonPrimitive.int,BrandMotion.OVERLAY_ENTER)
        assertEquals(0,BrandMotion.BLOCKING_DELAY);assertEquals(0,BrandMotion.duration(200,true));assertFalse(BrandMotion.REDUCED_SPATIAL)
        assertEquals("none",motion["onboarding"]!!.jsonObject["logo_motion"]!!.jsonPrimitive.content)
    }
}
