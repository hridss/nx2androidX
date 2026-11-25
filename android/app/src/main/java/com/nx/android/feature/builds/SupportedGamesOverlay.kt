package com.nx.android.feature.builds

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.nx.android.R
import com.nx.android.core.ui.components.CloseIcon
import com.nx.android.core.ui.components.Hint
import com.nx.android.core.ui.nav.NavKey
import com.nx.android.core.ui.nav.OnNavKey
import com.nx.android.core.ui.theme.NxPalette

/**
 * The list of the games the porter knows, over the whole screen.
 *
 * It is a page and not a card: the list is long, and a card in the middle of the screen would cut
 * the list off. It covers the screen — the top bar is under it — and the app's outer layer draws it
 * (the builds page reports the intent: a cover drawn in the page reaches only the strip under the
 * top bar).
 *
 * The content is the list the build carries (docs/PORT_CHECKLIST.md), rendered as Markdown: the
 * same renderer the assistant's answers use.
 */
@Composable
fun SupportedGamesOverlay(onClose: () -> Unit) {
    // The system back key and the pad's back key close it: the page below is not there while it is open
    BackHandler { onClose() }
    OnNavKey { key -> if (key == NavKey.Back) onClose() }

    val ctx = LocalContext.current
    // The list is read once: it does not change while the page is open
    val markdown = remember { GameListContent.withStatusMarks(GameListContent.load(ctx)) }
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize().background(NxPalette.Surface)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The page's own head: its name, and the way out
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.builds_game_list_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                CloseIcon(onClose)
            }
            if (markdown.isBlank()) {
                // The list did not come with this build: the page says so and stays open
                Hint(stringResource(R.string.builds_game_list_empty))
            } else {
                Column(Modifier.weight(1f).verticalScroll(scroll)) {
                    Markdown(
                        markdown,
                        colors = markdownColor(),
                        typography = markdownTypography(),
                    )
                }
            }
        }
    }
}
