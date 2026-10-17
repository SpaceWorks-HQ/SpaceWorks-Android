package org.spaceworks.app.platform

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.spaceworks.app.payments.PaymentStrings
import org.spaceworks.app.ui.BrandButton
import org.spaceworks.app.ui.BrandLogo

/** Explicit recovery only: the host returns an interruption; the caller must refetch server status. */
@Composable fun CheckoutRecoveryScreen(onCheckStatus:()->Unit) {
    val text=rememberMobileText()
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
            Column(Modifier.widthIn(max=460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement=Arrangement.spacedBy(24.dp)) {
                BrandLogo(compact=true)
                Text(text(PaymentStrings.PAYMENT_CHECK_PAYMENT_STATUS),style=MaterialTheme.typography.headlineMedium,
                    modifier=Modifier.semantics {heading()})
                text(PaymentStrings.PAYMENT_INTERRUPTED_RECOVERY_BODY).split("\n\n").forEachIndexed { index, paragraph ->
                    Text(paragraph,modifier=Modifier.testTag("checkout-recovery-message-$index"))
                }
                BrandButton(text(PaymentStrings.PAYMENT_CHECK_PAYMENT_STATUS),onCheckStatus,
                    modifier=Modifier.testTag("checkout-recovery-action"))
            }
        }
    }
}
