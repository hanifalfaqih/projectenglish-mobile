package id.hanifalfaqih.aienglishinterview.core.monetization

/**
 * Single wiring point for monetization, mirroring `data/remote/ApiProvider`.
 * Production code uses the RevenueCat implementation (which degrades to
 * [PremiumState.Unavailable] when no key is configured); tests inject
 * [FakeMonetization] instead.
 */
object MonetizationProvider {
    val repository: MonetizationRepository by lazy { RevenueCatMonetization() }
}
