package app.satisfyingsand.play

import android.app.Activity
import android.util.Log
import app.satisfyingsand.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A full-screen (interstitial) ad before each game, unless the player has bought "Remove ads".
 *
 * Google's consent flow (UMP) runs first, as AdMob requires for players in regions with privacy
 * laws; ads are only requested once consent allows it. An ad is preloaded so the game starts as
 * soon as it's dismissed; if no ad is ready the game simply starts, never blocking play.
 */
class Ads(private val activity: Activity, private val adsRemoved: () -> Boolean) {
    private var consent: ConsentInformation? = null
    private val started = AtomicBoolean(false)
    private var ad: InterstitialAd? = null
    private var loading = false

    fun start() {
        if (adsRemoved()) return
        try {
            val info = UserMessagingPlatform.getConsentInformation(activity)
            consent = info
            info.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { initIfAllowed() }
            }, { Log.w(TAG, "consent update failed: ${it.message}") })
            initIfAllowed() // consent from a previous session may already allow ads
        } catch (e: Exception) {
            Log.w(TAG, "ads unavailable", e)
        }
    }

    private fun initIfAllowed() {
        if (consent?.canRequestAds() != true || adsRemoved()) return
        if (!started.getAndSet(true)) MobileAds.initialize(activity) { preload() } else preload()
    }

    /** Whether the consent settings need a "Privacy options" entry in Settings. */
    val privacyOptionsRequired: Boolean
        get() = consent?.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions() {
        try { UserMessagingPlatform.showPrivacyOptionsForm(activity) { initIfAllowed() } } catch (e: Exception) { Log.w(TAG, "privacy form", e) }
    }

    private fun preload() {
        if (ad != null || loading || adsRemoved()) return
        loading = true
        try {
            InterstitialAd.load(activity, BuildConfig.ADMOB_INTERSTITIAL_ID, AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(loaded: InterstitialAd) { ad = loaded; loading = false }
                    override fun onAdFailedToLoad(error: LoadAdError) { loading = false; Log.i(TAG, "no ad: ${error.message}") }
                })
        } catch (e: Exception) {
            loading = false
            Log.w(TAG, "ad load failed", e)
        }
    }

    /** Shows an ad if one is ready (and ads aren't removed), then runs [then]. */
    fun beforeGame(then: () -> Unit) {
        val current = ad
        if (adsRemoved() || current == null) { then(); preload(); return }
        ad = null
        var done = false
        fun finish() { if (!done) { done = true; then(); preload() } }
        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = finish()
            override fun onAdFailedToShowFullScreenContent(error: AdError) = finish()
        }
        try { current.show(activity) } catch (e: Exception) { finish() }
    }

    private companion object { const val TAG = "Ads" }
}
