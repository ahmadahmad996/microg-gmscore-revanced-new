/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast

import android.content.Context
import android.util.Log
import org.microg.gms.settings.SettingsContract
import org.microg.gms.settings.SettingsContract.Cast

private const val TAG = "CastPreferences"

object CastPreferences {

    /**
     * Whether GmsCore should advertise Chromecast devices through its own
     * MediaRouteProvider. Disable this when Google Play services is installed as well,
     * otherwise every device is listed twice (once by each provider).
     */
    @JvmStatic
    fun isCastDiscoveryEnabled(context: Context): Boolean {
        return try {
            SettingsContract.getSettings(context, Cast.getContentUri(context), arrayOf(Cast.ENABLED)) { cursor ->
                cursor.getInt(0) != 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read cast discovery setting, defaulting to enabled", e)
            true
        }
    }

    @JvmStatic
    fun setCastDiscoveryEnabled(context: Context, enabled: Boolean) {
        SettingsContract.setSettings(context, Cast.getContentUri(context)) {
            put(Cast.ENABLED, enabled)
        }
    }
}
