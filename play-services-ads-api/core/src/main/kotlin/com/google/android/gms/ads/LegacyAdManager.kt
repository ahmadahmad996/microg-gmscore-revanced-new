/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.gms.ads

import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import com.google.android.gms.ads.internal.AdRequestParcel
import com.google.android.gms.ads.internal.client.AdSizeParcel
import com.google.android.gms.ads.internal.client.IAdClickListener
import com.google.android.gms.ads.internal.client.IAdListener
import com.google.android.gms.ads.internal.client.IAdLoadCallback
import com.google.android.gms.ads.internal.client.IAdManager
import com.google.android.gms.ads.internal.client.IAppEventListener
import com.google.android.gms.ads.internal.client.IFullScreenContentCallback
import com.google.android.gms.ads.internal.client.IOnPaidEventListener
import com.google.android.gms.ads.internal.client.IVideoController
import com.google.android.gms.dynamic.IObjectWrapper
import com.google.android.gms.dynamic.ObjectWrapper

private const val TAG = "LegacyAdManager"

/**
 * Minimal [IAdManager] returned by [AdManagerCreatorImpl] for the banner/interstitial path.
 *
 * It is a non-null ad manager so the AdMob SDK keeps using microG's remote result. No ad is served:
 * [getView] returns an empty container, [loadAd] reports "no fill", and the listeners/getters are
 * accepted but inert.
 */
internal class LegacyAdManager(private val context: Context?, adSize: AdSizeParcel?) : IAdManager.Stub() {
    private val adView: FrameLayout? by lazy { context?.let { FrameLayout(it) } }

    // Older AdMob SDKs call getAdSize() and dereference the result immediately. Never return
    // null here: use the size supplied by the caller, or a standard 320x50 banner as fallback.
    private var adSizeParcel: AdSizeParcel = adSize ?: AdSizeParcel().apply {
        formatString = "320x50_mb"
        width = 320
        height = 50
        widthPixels = 320
        heightPixels = 50
        isParcelStateValid = true
    }

    override fun getView(): IObjectWrapper = ObjectWrapper.wrap(adView)
    override fun destroy() = Unit
    override fun loadAd(request: AdRequestParcel?): Boolean {
        Log.w(TAG, "loadAd")
        return false
    }
    override fun pause() = Unit
    override fun resume() = Unit
    override fun setAdListener(listener: IAdListener?) = Unit
    override fun setAppEventListener(listener: IAppEventListener?) = Unit
    override fun getAdSize(): AdSizeParcel = adSizeParcel
    override fun setAdSize(adSize: AdSizeParcel?) {
        adSize?.let { adSizeParcel = it }
    }
    override fun setAdClickListener(listener: IAdClickListener?) = Unit
    override fun setManualImpressionFlag(flag: Int) = Unit
    override fun getVideoController(): IVideoController? = null
    override fun setManualImpressionsEnabled(enabled: Boolean) = Unit
    override fun setOnPaidEventListener(listener: IOnPaidEventListener?) = Unit
    override fun loadAdWithCallback(request: AdRequestParcel?, callback: IAdLoadCallback?) = Unit
    override fun showInterstitial(activityWrapper: IObjectWrapper?) = Unit
    override fun setFullScreenContentCallback(callback: IFullScreenContentCallback?) = Unit
    override fun setStartTimestampMillis(timestampMillis: Long) = Unit
}
