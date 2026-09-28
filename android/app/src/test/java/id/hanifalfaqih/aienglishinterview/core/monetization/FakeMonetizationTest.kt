package id.hanifalfaqih.aienglishinterview.core.monetization

import android.app.Activity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

private fun activity(): Activity = mock(Activity::class.java)

class FakeMonetizationTest {

    @Test
    fun initialNotEntitled() = runTest {
        val fake = FakeMonetization(isPremium = false)
        assertEquals(PremiumState.Determined(false), fake.premiumState.value)
        assertEquals(0, fake.refreshCalls)
    }

    @Test
    fun entitlementGrantedAndRevoked() = runTest {
        val fake = FakeMonetization(isPremium = false)
        fake.setPremium(true)
        assertEquals(PremiumState.Determined(true), fake.premiumState.value)
        fake.setPremium(false)
        assertEquals(PremiumState.Determined(false), fake.premiumState.value)
    }

    @Test
    fun offeringsUnavailable() = runTest {
        val fake = FakeMonetization()
        fake.offers = MonetizationResult.Failure("No products")
        val result = fake.loadOffers()
        assertTrue(result is MonetizationResult.Failure)
    }

    @Test
    fun purchaseSuccess_grantsPremium() = runTest {
        val fake = FakeMonetization(isPremium = false)
        val outcome = fake.purchase(activity(), "monthly")
        assertEquals(PurchaseOutcome.Success, outcome)
        assertEquals(PremiumState.Determined(true), fake.premiumState.value)
        assertEquals(listOf("monthly"), fake.purchaseCalls)
    }

    @Test
    fun purchaseCancelled_keepsState() = runTest {
        val fake = FakeMonetization(isPremium = false)
        fake.purchaseOutcome = PurchaseOutcome.Cancelled
        val outcome = fake.purchase(activity(), "monthly")
        assertEquals(PurchaseOutcome.Cancelled, outcome)
        assertEquals(PremiumState.Determined(false), fake.premiumState.value)
    }

    @Test
    fun purchaseFailure_reportsMessage() = runTest {
        val fake = FakeMonetization()
        fake.purchaseOutcome = PurchaseOutcome.Failure("Store error")
        val outcome = fake.purchase(activity(), "monthly")
        assertEquals(PurchaseOutcome.Failure("Store error"), outcome)
    }

    @Test
    fun restoreSuccess_setsPremium() = runTest {
        val fake = FakeMonetization(isPremium = false)
        fake.restoreResult = MonetizationResult.Success(true)
        val result = fake.restore()
        assertEquals(MonetizationResult.Success(true), result)
        assertEquals(PremiumState.Determined(true), fake.premiumState.value)
        assertEquals(1, fake.restoreCalls)
    }

    @Test
    fun restoreNothingFound() = runTest {
        val fake = FakeMonetization(isPremium = false)
        fake.restoreResult = MonetizationResult.Success(false)
        val result = fake.restore()
        assertEquals(MonetizationResult.Success(false), result)
        assertEquals(PremiumState.Determined(false), fake.premiumState.value)
    }

    @Test
    fun restoreFailure() = runTest {
        val fake = FakeMonetization()
        fake.restoreResult = MonetizationResult.Failure("No connection")
        val result = fake.restore()
        assertTrue(result is MonetizationResult.Failure)
    }
}
