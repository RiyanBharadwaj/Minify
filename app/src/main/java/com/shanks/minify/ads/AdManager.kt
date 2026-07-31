package com.shanks.minify.ads

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AdManager {
    private const val NATIVE_AD_UNIT_ID = "ca-app-pub-7808044311175217/8576387766"
    private const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-7808044311175217/5796547309"
    private const val REFRESH_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var refreshJob: Job? = null

    var nativeAd by mutableStateOf<NativeAd?>(null)
        private set

    private var interstitialAd: InterstitialAd? = null
    private var isInterstitialLoading = false

    fun initialize(context: Context) {
        startNativeRefreshLoop(context)
        loadInterstitial(context)
    }

    private fun startNativeRefreshLoop(context: Context) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                loadNativeAd(context)
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun loadNativeAd(context: Context) {
        android.util.Log.d("AdManager", "Loading native ad...")
        val adLoader = AdLoader.Builder(context, NATIVE_AD_UNIT_ID)
            .forNativeAd { ad ->
                android.util.Log.d("AdManager", "Native ad loaded successfully: ${ad.headline}")
                nativeAd?.destroy()
                nativeAd = ad
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    android.util.Log.e("AdManager", "Native ad failed to load: ${error.code} - ${error.message}")
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()
        adLoader.loadAd(AdRequest.Builder().build())
    }

    fun loadInterstitial(context: Context) {
        if (interstitialAd != null || isInterstitialLoading) return

        isInterstitialLoading = true
        android.util.Log.d("AdManager", "Loading interstitial ad...")
        InterstitialAd.load(
            context,
            INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    android.util.Log.d("AdManager", "Interstitial ad loaded")
                    interstitialAd = ad
                    isInterstitialLoading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    android.util.Log.e("AdManager", "Interstitial ad failed to load: ${error.message}")
                    interstitialAd = null
                    isInterstitialLoading = false
                }
            }
        )
    }

    fun showInterstitial(activity: Activity, onDismissed: () -> Unit) {
        val ad = interstitialAd
        if (ad != null) {
            android.util.Log.d("AdManager", "Showing interstitial ad")
            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    android.util.Log.d("AdManager", "Interstitial ad dismissed")
                    interstitialAd = null
                    onDismissed()
                    loadInterstitial(activity) // Pre-load next one
                }

                override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                    android.util.Log.e("AdManager", "Interstitial ad failed to show: ${error.message}")
                    interstitialAd = null
                    onDismissed()
                    loadInterstitial(activity)
                }
            }
            ad.show(activity)
        } else {
            android.util.Log.d("AdManager", "Interstitial ad not ready")
            onDismissed()
            loadInterstitial(activity)
        }
    }
}
