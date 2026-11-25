package com.nx.android.agent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nx.android.R
import com.nx.android.agent.AgentRecommendation
import com.nx.android.agent.AgentRecommendations
import com.nx.android.core.ui.theme.NxPalette

/**
 * The small card at the top of the assistant's settings: the providers we recommend, each one a
 * link.
 *
 * It is a card and not another row because it is a group — a heading, and under it the
 * recommended providers. Tapping one opens that provider's page in the browser: the key is the
 * player's own, and the app never holds one.
 *
 * It sits at the top of the section on purpose: it is the way in. The rows under it are the models
 * the player already has; this one points at the ones we would suggest.
 *
 * The card is light on the app's dark ground — the one bright thing on the page, so it reads as a
 * card and not as a hole. Its ink is the app's dark ink, the same one the light panels use.
 */
@Composable
fun RecommendedModelsCard(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(NxPalette.Cream)
            .border(1.dp, CardInk.copy(alpha = 0.10f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.agent_models_recommended),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = CardInk,
        )
        Text(
            stringResource(R.string.agent_models_recommended_hint),
            style = MaterialTheme.typography.bodySmall,
            color = CardInk.copy(alpha = 0.62f),
        )
        HorizontalDivider(
            color = CardInk.copy(alpha = 0.12f),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        for (recommendation in AgentRecommendations.ALL) {
            RecommendationRow(recommendation)
        }
    }
}

/** The ink on the light card: the app's dark ink, the one the light panels write with. */
private val CardInk = Color(0xFF141416)

/** One recommended provider: its name, where its key comes from, and the way out to it. */
@Composable
private fun RecommendationRow(recommendation: AgentRecommendation) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { openLink(ctx, recommendation.url) }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(providerLabelRes(recommendation.providerId)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CardInk,
            )
            Text(
                stringResource(providerHintRes(recommendation.providerId)),
                style = MaterialTheme.typography.bodySmall,
                color = CardInk.copy(alpha = 0.62f),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            stringResource(R.string.agent_models_recommended_open),
            Modifier.size(16.dp),
            tint = NxPalette.Brand,
        )
    }
}

/** Open a page in the browser: a device without one has no way there, so it passes quietly. */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // no browser: there is no other way to that page
    }
}
