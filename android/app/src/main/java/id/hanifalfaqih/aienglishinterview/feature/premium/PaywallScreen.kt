package id.hanifalfaqih.aienglishinterview.feature.premium

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.core.monetization.PREMIUM_BLURB
import id.hanifalfaqih.aienglishinterview.core.monetization.PremiumState
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Premium paywall: explains the entitlement, shows live premium state,
 * dashboard-driven offers, purchase, and restore. Never fakes prices or
 * purchase success — unavailable/misconfigured states say so explicitly.
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Premium Practice", style = MaterialTheme.typography.headlineMedium)
        Text(text = PREMIUM_BLURB, style = MaterialTheme.typography.bodyMedium)

        when (val premium = viewModel.premiumState) {
            is PremiumState.Loading -> CircularProgressIndicator()
            is PremiumState.Determined ->
                Text(
                    text = if (premium.isPremium) "Status: Premium active" else "Status: Free",
                    style = MaterialTheme.typography.bodyMedium,
                )
            is PremiumState.Unavailable -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = premium.reason,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        when (val offers = viewModel.offersState) {
            is OffersState.Loading -> CircularProgressIndicator()
            is OffersState.Loaded -> {
                if (offers.offers.isEmpty()) {
                    Text(
                        text = "No products are available right now.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                offers.offers.forEach { offer ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = offer.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = offer.price,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Button(
                                onClick = {
                                    if (activity != null) {
                                        viewModel.purchase(activity, offer.id)
                                    }
                                },
                                enabled = !viewModel.purchasing,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            ) {
                                Text("Buy")
                            }
                        }
                    }
                }
            }
            is OffersState.Error -> {
                Text(
                    text = offers.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { viewModel.refresh() }) {
                    Text("Retry")
                }
            }
            is OffersState.Unavailable -> {
                Text(
                    text = offers.reason,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (viewModel.message != null) {
            Text(
                text = viewModel.message ?: "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (viewModel.purchasing) {
            CircularProgressIndicator()
        }

        TextButton(
            onClick = { viewModel.restore() },
            enabled = !viewModel.purchasing,
        ) {
            Text("Restore purchases")
        }
        OutlinedButton(onClick = onBack) {
            Text("Back")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PaywallScreenPreview() {
    AIEnglishInterviewTheme {
        PaywallScreen(onBack = {})
    }
}
