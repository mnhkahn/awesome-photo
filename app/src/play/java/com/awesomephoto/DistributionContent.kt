package com.awesomephoto

import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler

@Composable
fun DistributionLinks() {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(BuildConfig.PRIVACY_POLICY_URL) }) {
        Text(stringResource(R.string.privacy_policy))
    }
}

@Composable
fun DistributionPanel() = Unit
