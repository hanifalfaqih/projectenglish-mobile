package id.hanifalfaqih.aienglishinterview.feature.premium

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationProvider
import id.hanifalfaqih.aienglishinterview.core.monetization.PREMIUM_BLURB
import id.hanifalfaqih.aienglishinterview.core.monetization.PaywallOffer
import id.hanifalfaqih.aienglishinterview.core.monetization.PremiumState
import id.hanifalfaqih.aienglishinterview.ui.components.BannerTone
import id.hanifalfaqih.aienglishinterview.ui.components.Hairline
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.QuietAction
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.components.SectionLabel
import id.hanifalfaqih.aienglishinterview.ui.components.StatusBanner
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue

/**
 * Premium paywall: explains the entitlement, shows live premium state,
 * dashboard-driven offers, purchase, and restore. Never fakes prices or
 * purchase success — unavailable/misconfigured states say so explicitly.
 *
 * RevenueCat configuration is MONTHLY ONLY — the UI renders exactly the
 * offers the existing integration provides (live prices from RevenueCat,
 * never hardcoded) and never implies an annual option.
 *
 * Presentation: restrained and premium — neutral canvas, one quiet offer
 * row, one blue Subscribe action, restore/back as text actions.
 */
@Composable
fun PaywallScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PremiumViewModel = viewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        SectionLabel("Premium Practice")
        Spacer(modifier = Modifier.height(10.dp))
        ScreenTitle("Everything, unlocked")
        Spacer(modifier = Modifier.height(10.dp))
        ScreenSubtitle(PREMIUM_BLURB)

        Spacer(modifier = Modifier.height(28.dp))

        // Premium status indicator
        when (val premium = viewModel.premiumState) {
            is PremiumState.Loading -> {
                StatusBanner(
                    text = "Checking your access…",
                    tone = BannerTone.Progress,
                    spinning = true,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            is PremiumState.Determined -> {
                if (premium.isPremium) {
                    StatusBanner(text = "Premium is active on this device.", tone = BannerTone.Success)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            is PremiumState.Unavailable -> {
                StatusBanner(
                    text = "Unable to check your access: ${premium.reason}",
                    tone = BannerTone.Error,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Offers section — rendered from live RevenueCat data.
        when (val offers = viewModel.offersState) {
            is OffersState.Loading -> {
                StatusBanner(
                    text = "Loading available plans…",
                    tone = BannerTone.Info,
                    spinning = true,
                )
            }
            is OffersState.Loaded -> {
                if (offers.offers.isEmpty()) {
                    StatusBanner(
                        text = "No products are available right now.",
                        tone = BannerTone.Info,
                    )
                } else {
                    offers.offers.forEach { offer ->
                        OfferRow(
                            offer = offer,
                            onPurchase = {
                                if (activity != null) {
                                    viewModel.purchase(activity, offer.id)
                                }
                            },
                            purchasing = viewModel.purchasing,
                        )
                        Hairline()
                    }
                }
            }
            is OffersState.Error -> {
                StatusBanner(
                    text = offers.message,
                    tone = BannerTone.Error,
                    actionLabel = "Retry",
                    onAction = { viewModel.refresh() },
                )
            }
            is OffersState.Unavailable -> {
                StatusBanner(
                    text = "Premium unavailable: ${offers.reason}",
                    tone = BannerTone.Error,
                )
            }
        }

        if (viewModel.message != null) {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(text = viewModel.message ?: "", tone = BannerTone.Info)
        }
        if (viewModel.purchasing) {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(
                text = "Processing purchase…",
                tone = BannerTone.Progress,
                spinning = true,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        QuietAction(
            text = "Restore purchases",
            onClick = { viewModel.restore() },
            enabled = !viewModel.purchasing,
            modifier = Modifier.fillMaxWidth(),
        )
        QuietAction(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** Live RevenueCat offer: title + localized price, purchase as the action. */
@Composable
private fun OfferRow(
    offer: PaywallOffer,
    onPurchase: () -> Unit,
    purchasing: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = offer.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = offer.price,
                style = MaterialTheme.typography.titleLarge,
                color = PrimaryBlue,
            )
        }
        PrimaryButton(
            text = "Subscribe",
            onClick = onPurchase,
            enabled = !purchasing,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PaywallScreenPreview() {
    AIEnglishInterviewTheme {
        PaywallScreen(onBack = {})
    }
}
