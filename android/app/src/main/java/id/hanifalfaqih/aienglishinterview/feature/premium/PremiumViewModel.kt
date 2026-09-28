package id.hanifalfaqih.aienglishinterview.feature.premium

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationRepository
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationProvider
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationResult
import id.hanifalfaqih.aienglishinterview.core.monetization.PaywallOffer
import id.hanifalfaqih.aienglishinterview.core.monetization.PremiumState
import id.hanifalfaqih.aienglishinterview.core.monetization.PurchaseOutcome
import kotlinx.coroutines.launch

sealed interface OffersState {
    data object Loading : OffersState
    data class Loaded(val offers: List<PaywallOffer>) : OffersState
    data class Error(val message: String) : OffersState
    data class Unavailable(val reason: String) : OffersState
}

/**
 * Paywall state. Entitlement truth comes from RevenueCat via the repository;
 * this ViewModel only orchestrates loading, purchase, and restore calls.
 */
class PremiumViewModel(
    private val monetization: MonetizationRepository = MonetizationProvider.repository,
) : ViewModel() {

    var premiumState by mutableStateOf<PremiumState>(PremiumState.Loading)
        private set

    var offersState by mutableStateOf<OffersState>(OffersState.Loading)
        private set

    var purchasing by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            monetization.premiumState.collect { premiumState = it }
        }
        refresh()
    }

    fun refresh() {
        message = null
        offersState = OffersState.Loading
        viewModelScope.launch {
            monetization.refresh()
            offersState = when (val result = monetization.loadOffers()) {
                is MonetizationResult.Success -> OffersState.Loaded(result.value)
                is MonetizationResult.Failure -> OffersState.Error(result.message)
                is MonetizationResult.Unavailable -> OffersState.Unavailable(result.reason)
            }
        }
    }

    fun purchase(activity: Activity, offerId: String) {
        if (purchasing) return
        purchasing = true
        message = null
        viewModelScope.launch {
            message = when (val outcome = monetization.purchase(activity, offerId)) {
                is PurchaseOutcome.Success -> "Premium activated. Enjoy your practice!"
                is PurchaseOutcome.Cancelled -> "Purchase cancelled."
                is PurchaseOutcome.Failure -> outcome.message
                is PurchaseOutcome.Unavailable -> outcome.reason
            }
            purchasing = false
        }
    }

    fun restore() {
        if (purchasing) return
        purchasing = true
        message = null
        viewModelScope.launch {
            message = when (val result = monetization.restore()) {
                is MonetizationResult.Success ->
                    if (result.value) "Premium restored." else "No premium purchase found."
                is MonetizationResult.Failure -> result.message
                is MonetizationResult.Unavailable -> result.reason
            }
            purchasing = false
        }
    }
}
