package com.shanks.minify.ui

import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.nativead.NativeAdView
import com.shanks.minify.ads.AdManager
import com.shanks.minify.ui.theme.LocalAppAccentColor
import com.shanks.minify.ui.theme.Surface1
import com.shanks.minify.ui.theme.TextSec

/**
 * A Composable that displays a Google Mobile Ads Native Ad.
 * Uses a clean dark-themed layout to match Minify's UI.
 * Now observes [AdManager.nativeAd] for centralized management and refresh.
 */
@Composable
fun NativeAdView(
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAppAccentColor.current
    val nativeAd = AdManager.nativeAd

    if (nativeAd != null) {
        AndroidView(
            factory = { ctx ->
                LayoutInflater.from(ctx).inflate(com.shanks.minify.R.layout.ad_unified, null) as NativeAdView
            },
            update = { view ->
                // Ad title
                val headlineView = view.findViewById<TextView>(com.shanks.minify.R.id.ad_headline)
                headlineView.text = nativeAd.headline
                view.headlineView = headlineView

                // Ad body
                val bodyView = view.findViewById<TextView>(com.shanks.minify.R.id.ad_body)
                bodyView.text = nativeAd.body
                view.bodyView = bodyView

                // Call to action
                val callToActionView = view.findViewById<Button>(com.shanks.minify.R.id.ad_call_to_action)
                callToActionView.text = nativeAd.callToAction
                callToActionView.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor.toArgb())
                view.callToActionView = callToActionView

                // Ad attribution
                val advertiserView = view.findViewById<TextView>(com.shanks.minify.R.id.ad_advertiser)
                advertiserView.text = nativeAd.advertiser ?: "Ad"
                view.advertiserView = advertiserView

                view.setNativeAd(nativeAd)
            },
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface1)
        )
    } else {
        // Shimmer or placeholder while loading
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface1),
            contentAlignment = Alignment.Center
        ) {
            Text("Loading Ad...", color = TextSec, fontSize = 12.sp)
        }
    }
}
