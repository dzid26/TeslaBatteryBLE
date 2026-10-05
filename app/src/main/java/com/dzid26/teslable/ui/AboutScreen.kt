// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.BuildConfig

/**
 * Everything about the app that is not a setting: version, description, links,
 * support, licenses, and the Tesla disclaimer. Opened from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                title = { Text("About") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(16.dp),
        ) {
            AppCard()
            Spacer(Modifier.height(16.dp))
            LinksCard()
            Spacer(Modifier.height(16.dp))
            SupportCard()
            Spacer(Modifier.height(16.dp))
            LicensesCard()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Not affiliated with, endorsed by, or sponsored by Tesla, Inc.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppCard() {
    SectionCard(title = "TeslaBatteryBLE") {
        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text =
                "A local-first Tesla BLE battery tracker. It reads battery data over " +
                    "Bluetooth and keeps the history on the phone: no account, no cloud, " +
                    "nothing leaves the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinksCard() {
    SectionCard(title = "Links") {
        LinkRow("Source code", SOURCE_URL)
        LinkRow("Releases", RELEASES_URL)
        LinkRow("Privacy statement", PRIVACY_URL)
        LinkRow("Master plan", MASTER_PLAN_URL)
    }
}

@Composable
private fun SupportCard() {
    SectionCard(title = "Support") {
        Text(
            text =
                "TeslaBatteryBLE is free software, built in spare time. If it is useful " +
                    "to you, you can support development through GitHub Sponsors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LinkRow("Sponsor on GitHub", SPONSORS_URL)
    }
}

@Composable
private fun LicensesCard() {
    SectionCard(title = "Licenses") {
        LicenseLine("TeslaBatteryBLE", "AGPL-3.0-only")
        LicenseLine("Wire", "Apache-2.0")
        LicenseLine("AndroidX", "Apache-2.0")
        LicenseLine("Kotlin", "Apache-2.0")
        Spacer(Modifier.height(4.dp))
        LinkRow("License text", LICENSE_URL)
        LinkRow("Third-party notices", NOTICES_URL)
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LicenseLine(
    name: String,
    license: String,
) {
    Text(
        text = "$name — $license",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** A tappable line that opens [url] in the browser. */
@Composable
private fun LinkRow(
    label: String,
    url: String,
) {
    val context = LocalContext.current
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { openUrl(context, url) }
                .padding(vertical = 8.dp),
    )
}

private fun openUrl(
    context: Context,
    url: String,
) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private const val SOURCE_URL = "https://github.com/dzid26/TeslaBatteryBLE"
private const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"
private const val NOTICES_URL = "$SOURCE_URL/blob/main/THIRD_PARTY_NOTICES.md"
private const val PRIVACY_URL = "$SOURCE_URL/blob/main/PRIVACY.md"
private const val MASTER_PLAN_URL = "$SOURCE_URL/blob/main/docs/master-plan.md"
private const val RELEASES_URL = "$SOURCE_URL/releases"
private const val SPONSORS_URL = "https://github.com/sponsors/dzid26"
