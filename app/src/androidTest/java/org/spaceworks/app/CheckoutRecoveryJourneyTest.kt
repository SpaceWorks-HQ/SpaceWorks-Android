package org.spaceworks.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.spaceworks.app.platform.CheckoutRecoveryScreen
import org.spaceworks.app.theme.DarkScheme
import org.spaceworks.app.theme.LightScheme

class CheckoutRecoveryJourneyTest {
    @get:Rule val compose=createComposeRule()
    private val journey by lazy {NativeJourney(compose,"a8-sdk-recovery")}
    @Test fun interruptedCheckoutRemainsReadableAndExplicitAtLargeTextWithoutOpeningTheSdk() {
        var refreshes=0
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,3.2f)) {
                MaterialTheme(colorScheme=if(DeviceProfile.dark)DarkScheme else LightScheme) {
                    CheckoutRecoveryScreen {refreshes++}
                }
            }
        }
        journey.systemBars(DeviceProfile.dark)
        journey.visible(compose.onNodeWithTag("checkout-recovery-message-0")).assertTextEquals("If checkout was interrupted,")
        journey.visible(compose.onNodeWithTag("checkout-recovery-message-1")).assertTextEquals("return to check your payment status.")
        assertEquals(0,refreshes)
        journey.clickTag("checkout-recovery-action")
        assertEquals(1,refreshes)
        journey.shot("a8-interrupted-checkout-large-text")
    }
}
