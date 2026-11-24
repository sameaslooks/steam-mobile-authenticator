package io.github.sma.core

import android.content.Context

/** User-Agent required by OpenStreetMap services (Nominatim and tile servers) and GitHub API. */
internal fun appUserAgent(ctx: Context): String {
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
        .getOrNull() ?: "unknown"
    return "SMA/$version (io.github.sameaslooks.sma)"
}
