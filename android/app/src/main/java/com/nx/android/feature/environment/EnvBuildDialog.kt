package com.nx.android.feature.environment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nx.android.R
import com.nx.android.core.ui.components.CapsuleButton
import com.nx.android.core.ui.theme.NxPalette

/**
 * The environment's build, in a dialog the player cannot skip.
 *
 * The app brings the environment in one archive: when the archive is not the one the phone's environment was
 * built with, the whole thing is built again. It takes a while (the rootfs, the compiler and the .NET runtime
 * are half a gigabyte), so the progress is shown and the app waits - a half-built environment is not something
 * the player should be let near.
 */
@Composable
fun EnvBuildDialog(
    progress: Int,
    error: String,
    onRetry: () -> Unit,
) {
    val failed = error.isNotEmpty()
    AlertDialog(
        // it cannot be skipped: the environment is not ready until it is built
        onDismissRequest = {},
        title = {
            Text(stringResource(if (failed) R.string.env_build_failed else R.string.env_build_title))
        },
        text = {
            Column {
                if (failed) {
                    Text(
                        stringResource(R.string.env_build_failed_detail, error),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        stringResource(R.string.env_build_progress, progress),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = NxPalette.Building,
                    )
                }
            }
        },
        confirmButton = {
            if (failed) {
                CapsuleButton(stringResource(R.string.env_build_retry), onRetry)
            }
        },
        dismissButton = null,
    )
}
