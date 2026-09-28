package id.hanifalfaqih.aienglishinterview.feature.premium

import android.app.Activity
import id.hanifalfaqih.aienglishinterview.core.monetization.FakeMonetization
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationResult
import id.hanifalfaqih.aienglishinterview.core.monetization.PaywallOffer
import id.hanifalfaqih.aienglishinterview.core.monetization.PremiumState
import id.hanifalfaqih.aienglishinterview.core.monetization.PurchaseOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class PremiumViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val activity: Activity = mock(Activity::class.java)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsOffersOnStart() = runTest(dispatcher) {
        val vm = PremiumViewModel(
            FakeMonetization(
                offers = MonetizationResult.Success(
                    listOf(PaywallOffer("annual", "Premium Annual", "$39.99")),
                ),
            ),
        )
        advanceUntilIdle()
        val offers = vm.offersState as OffersState.Loaded
        assertEquals("annual", offers.offers.single().id)
        assertEquals(PremiumState.Determined(false), vm.premiumState)
    }

    @Test
    fun offersUnavailableShown() = runTest(dispatcher) {
        val fake = FakeMonetization()
        fake.offers = MonetizationResult.Failure("No products")
        val vm = PremiumViewModel(fake)
        advanceUntilIdle()
        assertTrue(vm.offersState is OffersState.Error)
    }

    @Test
    fun purchaseSuccessShowsMessage() = runTest(dispatcher) {
        val vm = PremiumViewModel(FakeMonetization())
        advanceUntilIdle()
        vm.purchase(activity, "monthly")
        advanceUntilIdle()
        assertEquals(PremiumState.Determined(true), vm.premiumState)
        assertTrue(vm.message?.contains("activated", ignoreCase = true) == true)
    }

    @Test
    fun purchaseCancelledShowsMessage() = runTest(dispatcher) {
        val fake = FakeMonetization()
        fake.purchaseOutcome = PurchaseOutcome.Cancelled
        val vm = PremiumViewModel(fake)
        advanceUntilIdle()
        vm.purchase(activity, "monthly")
        advanceUntilIdle()
        assertEquals(PremiumState.Determined(false), vm.premiumState)
        assertTrue(vm.message?.contains("cancelled", ignoreCase = true) == true)
    }

    @Test
    fun restoreNothingFound() = runTest(dispatcher) {
        val fake = FakeMonetization()
        fake.restoreResult = MonetizationResult.Success(false)
        val vm = PremiumViewModel(fake)
        advanceUntilIdle()
        vm.restore()
        advanceUntilIdle()
        assertTrue(vm.message?.contains("No premium", ignoreCase = true) == true)
    }
}
