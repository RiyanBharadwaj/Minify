package com.shanks.minify.platform

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Pure, total selector mapping an API level to the foreground-service type used
 * when starting a foreground compression (Requirement 1.9/1.10).
 *
 * - Below API 29: returns `null` — the foreground-service type parameter did not
 *   exist yet, so the service is started via `startForeground(id, notification)`.
 * - API 29 and above: returns [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC].
 *
 * `dataSync` is used rather than the newer `mediaProcessing` (added in API 34)
 * because `mediaProcessing` is not reliably supported across all API-34 builds
 * (some OEM/custom ROMs reject it as an unknown FGS type), whereas `dataSync`
 * is the long-standing, broadly-supported type for background processing/
 * transcoding work and is available from API 29 onward. It must match the
 * `android:foregroundServiceType` declared for the service in the manifest.
 *
 * This function is total and never throws for any [apiLevel].
 */
object ForegroundServicePolicy {

    fun serviceType(apiLevel: Int): Int? =
        if (apiLevel >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else null
}
