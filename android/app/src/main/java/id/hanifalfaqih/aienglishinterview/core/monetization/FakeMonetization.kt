package id.hanifalfaqih.aienglishinterview.core.monetization

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic test double for [MonetizationRepository]. Scriptable
 * premium state, offers, and purchase/restore outcomes. Never touches the
 * network or the RevenueCat SDK.
 */
class FakeMonetization(
    isPremium: Boolean = false,
    var offers: MonetizationResult<List<PaywallOffer>> = MonetizationResult.Success(
        listOf(PaywallOffer("monthly", "Premium Monthly", "$4.99")),
    ),
    var purchaseOutcome: PurchaseOutcome = PurchaseOutcome.Success,
    var restoreResult: MonetizationResult<Boolean> = MonetizationResult.Success(true),
) : MonetizationRepository {

    private val _premiumState =
        MutableStateFlow<PremiumState>(PremiumState.Determined(isPremium))
    override val premiumState: StateFlow<PremiumState> = _premiumState.asStateFlow()

    var refreshCalls = 0
        private set
    var purchaseCalls = mutableListOf<String>()
        private set
    var restoreCalls = 0
        private set

    fun setPremium(premium: Boolean) {
        _premiumState.value = PremiumState.Determined(premium)
    }

    override suspend fun refresh() {
        refreshCalls++
    }

    override suspend fun loadOffers(): MonetizationResult<List<PaywallOffer>> = offers

    override suspend fun purchase(activity: Activity, offerId: String): PurchaseOutcome {
        purchaseCalls.add(offerId)
        val outcome = purchaseOutcome
        if (outcome is PurchaseOutcome.Success) {
            _premiumState.value = PremiumState.Determined(true)
        }
        return outcome
    }

    override suspend fun restore(): MonetizationResult<Boolean> {
        restoreCalls++
        val result = restoreResult
        if (result is MonetizationResult.Success) {
            _premiumState.value = PremiumState.Determined(result.value)
        }
        return result
    }
}
