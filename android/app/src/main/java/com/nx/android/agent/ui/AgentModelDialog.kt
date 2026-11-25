package com.nx.android.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.nx.android.R
import com.nx.android.agent.AgentPresets
import com.nx.android.agent.env.PiCatalog
import com.nx.android.agent.env.PiModelList
import com.nx.android.agent.runtime.AgentModelProbe
import com.nx.android.agent.runtime.AgentModelProfile
import com.nx.android.core.ui.theme.NxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor says after the last attempt. */
private data class Note(val text: String, val isError: Boolean)

/**
 * The model's own editor: first the provider, then the parameters.
 *
 * A new model starts at the provider (a named one brings its endpoint and its first model along);
 * an edited one starts at the parameters, with what it already had.
 *
 * Two kinds of provider:
 *
 * - the ones the engine knows by itself: the player brings the key, the engine brings the endpoint
 *   and the list of models, so there is no address to fill in
 * - the ones we describe: the endpoint and the model list come from the endpoint itself, so the
 *   app asks it
 */
@Composable
fun AgentModelDialog(
    existing: AgentModelProfile?,
    onDismiss: () -> Unit,
    onSave: (AgentModelProfile) -> Unit,
    onDelete: (() -> Unit)? = null,
    error: String = "",
    engineReady: Boolean = true,
    listModels: suspend (providerId: String, apiKey: String) -> Result<PiModelList.Listing> =
        { _, _ -> Result.failure(IllegalStateException("the engine is not reachable")) },
) {
    var providerId by remember { mutableStateOf(existing?.providerId.orEmpty()) }
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var modelName by remember { mutableStateOf(existing?.modelName.orEmpty()) }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var keyVisible by remember { mutableStateOf(false) }
    // the provider comes first for a new model; an edited one goes straight to its parameters
    var choosingProvider by remember { mutableStateOf(existing == null) }

    // 端点给的模型清单：拉过之后就在这里，也存进配置里
    var modelIds by remember { mutableStateOf(existing?.modelIds.orEmpty()) }
    // 拉清单时用的端点与密钥：改了它们，这份清单就不作数了
    var fetchedFor by remember {
        mutableStateOf(
            existing?.let {
                if (PiCatalog.engineManaged(it.providerId)) "" to it.apiKey else it.baseUrl to it.apiKey
            },
        )
    }
    var probing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Note?>(null) }
    var picking by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // 引擎认识这家：端点与清单都归它管
    val engineManaged = PiCatalog.engineManaged(providerId)
    val complete = modelName.isNotBlank() && apiKey.isNotBlank() &&
        (engineManaged || baseUrl.isNotBlank())
    val target = if (engineManaged) "" to apiKey.trim() else baseUrl.trim() to apiKey.trim()
    val stale = fetchedFor != null && fetchedFor != target

    /** 问端点要模型清单：引擎管的那家问引擎，其余的问端点。 */
    fun fetchModels() {
        if (probing) return
        val key = apiKey.trim()
        if (key.isEmpty()) {
            note = Note(ctx.getString(R.string.agent_model_fetch_needs_key), true)
            return
        }
        probing = true
        note = null
        scope.launch {
            if (engineManaged) {
                val result = withContext(Dispatchers.IO) { listModels(providerId, key) }
                probing = false
                result.onSuccess { listing ->
                    modelIds = listing.models.map { it.id }
                    fetchedFor = "" to key
                    note = Note(
                        ctx.getString(R.string.agent_model_fetched, listing.models.size, listing.millis),
                        false,
                    )
                }.onFailure { e ->
                    note = Note(ctx.getString(R.string.agent_model_engine_failed, e.message.orEmpty()), true)
                }
            } else {
                val url = baseUrl.trim()
                if (url.isEmpty()) {
                    probing = false
                    note = Note(ctx.getString(R.string.agent_model_fetch_needs_url), true)
                    return@launch
                }
                val outcome = withContext(Dispatchers.IO) { AgentModelProbe.models(url, key) }
                probing = false
                when (outcome) {
                    is AgentModelProbe.Outcome.Ok -> {
                        modelIds = outcome.value.ids
                        fetchedFor = url to key
                        note = Note(
                            if (outcome.value.ids.isEmpty()) {
                                ctx.getString(R.string.agent_model_fetched_none)
                            } else {
                                ctx.getString(R.string.agent_model_fetched, outcome.value.ids.size, outcome.millis)
                            },
                            false,
                        )
                    }
                    is AgentModelProbe.Outcome.Failed -> note = Note(ctx.getString(whyRes(outcome.why)), true)
                }
            }
        }
    }

    /** 问端点要一次最小的对话：这个模型能不能真的跑（引擎管的供应商走引擎，这条用不上）。 */
    fun testModel() {
        if (probing) return
        val url = baseUrl.trim()
        val key = apiKey.trim()
        val model = modelName.trim()
        if (url.isEmpty() || key.isEmpty() || model.isEmpty()) {
            note = Note(ctx.getString(R.string.agent_model_test_needs_all), true)
            return
        }
        probing = true
        note = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { AgentModelProbe.test(url, key, model) }
            probing = false
            note = when (outcome) {
                is AgentModelProbe.Outcome.Ok ->
                    Note(ctx.getString(R.string.agent_model_test_ok, outcome.millis), false)
                is AgentModelProbe.Outcome.Failed ->
                    Note(ctx.getString(whyRes(outcome.why)), true)
            }
        }
    }

    // 挑模型的那一栏是另一个对话框：主对话框先让开
    if (picking) {
        AgentModelPickerDialog(
            models = modelIds,
            onPick = {
                modelName = it
                picking = false
            },
            onDismiss = { picking = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 平台默认的弹框宽度太窄（手机上只有 ~280dp），这一屏是表单，挤在这个宽度里没法看。
        // 关掉它，窗口才跟着下面的链走：边距在外、封顶在中间、铺满在内。
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                stringResource(
                    if (choosingProvider) R.string.agent_model_pick_provider else R.string.agent_model_params,
                ),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            if (choosingProvider) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (p in AgentPresets.ALL) {
                        ProviderChoice(p.id, p.id == providerId) {
                            providerId = p.id
                            baseUrl = p.baseUrl
                            modelName = p.defaultModel
                            choosingProvider = false
                        }
                    }
                }
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 密钥在最上面（顶格第一）：玩家先有的是它，其余都从它出发
                    // 接口地址与密钥并排：横屏上竖着排会把下面的按钮挤出屏幕
                    // 引擎认识的供应商：端点由引擎管，玩家不填
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            apiKey,
                            { apiKey = it },
                            Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(stringResource(R.string.agent_setup_api_key)) },
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        stringResource(R.string.agent_setup_key_toggle),
                                        Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                        if (!engineManaged) {
                            OutlinedTextField(
                                baseUrl,
                                { baseUrl = it },
                                Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(stringResource(R.string.agent_setup_base_url)) },
                            )
                        }
                    }
                    // 问模型清单：引擎管的问引擎，其余的问端点
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { fetchModels() },
                            enabled = !probing && apiKey.isNotBlank() &&
                                (engineManaged && engineReady || !engineManaged && baseUrl.isNotBlank()),
                        ) {
                            Text(stringResource(R.string.agent_model_fetch))
                        }
                        if (!engineManaged) {
                            OutlinedButton(
                                onClick = { testModel() },
                                enabled = !probing && baseUrl.isNotBlank() && apiKey.isNotBlank() &&
                                    modelName.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.agent_model_test))
                            }
                        }
                    }
                    // 刚才那一下的结果：通了多久，不通为什么（就贴在按钮下面，别让人找）
                    note?.let {
                        Text(
                            it.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.isError) MaterialTheme.colorScheme.error else NxPalette.Brand,
                        )
                    }
                    // 名称与模型名并排：模型是清单挑出来的，名称只是给玩家自己看的
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            label,
                            { label = it },
                            Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(stringResource(R.string.agent_model_label)) },
                        )
                        OutlinedTextField(
                            modelName,
                            { modelName = it },
                            Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(stringResource(R.string.agent_setup_model)) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { picking = true },
                                    enabled = modelIds.isNotEmpty() && !stale,
                                ) {
                                    Icon(
                                        Icons.Filled.List,
                                        stringResource(R.string.agent_model_pick_title),
                                        Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                    // 引擎目录里没有这个模型：手填的名字引擎未必认
                    if (engineManaged && modelIds.isNotEmpty() && modelName.isNotBlank() &&
                        modelName.trim() !in modelIds
                    ) {
                        Text(
                            stringResource(R.string.agent_model_not_in_engine),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 端点或密钥改过了：刚才那份清单说的不是现在这个端点
                    if (stale) {
                        Text(
                            stringResource(R.string.agent_model_list_stale),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(providerHintRes(providerId)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 存不上的时候：错在哪里写在这里，框子不关
                    if (error.isNotBlank()) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // the delete lives here: a model that is not wanted any more goes away from the
                    // place it was written
                    if (existing != null && onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text(stringResource(R.string.agent_models_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (choosingProvider) {
                TextButton(onClick = { choosingProvider = false }) {
                    Text(stringResource(R.string.agent_model_cancel))
                }
            } else {
                TextButton(
                    enabled = complete,
                    onClick = {
                        onSave(
                            AgentModelProfile(
                                id = existing?.id ?: "p-${System.currentTimeMillis()}",
                                providerId = providerId.ifBlank { "custom" },
                                label = label.trim(),
                                // 引擎管的供应商：端点不用存，它是引擎的事
                                baseUrl = if (engineManaged) "" else baseUrl.trim(),
                                modelName = modelName.trim(),
                                apiKey = apiKey.trim(),
                                modelIds = modelIds,
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.agent_setup_save))
                }
            }
        },
        dismissButton = {
            if (!choosingProvider && existing != null) {
                TextButton(onClick = { choosingProvider = true }) {
                    Text(stringResource(R.string.agent_model_pick_provider))
                }
            }
        },
    )
}

/** Why the endpoint said no, in the app's own language. */
private fun whyRes(why: AgentModelProbe.Why): Int = when (why) {
    AgentModelProbe.Why.BadKey -> R.string.agent_model_bad_key
    AgentModelProbe.Why.NoSuchApi -> R.string.agent_model_no_such_api
    AgentModelProbe.Why.NotAccepted -> R.string.agent_model_not_accepted
    AgentModelProbe.Why.ModelUnavailable -> R.string.agent_model_unavailable
    AgentModelProbe.Why.ServerSide -> R.string.agent_model_server_side
    AgentModelProbe.Why.Network -> R.string.agent_model_network
    AgentModelProbe.Why.CleartextBlocked -> R.string.agent_model_cleartext
    AgentModelProbe.Why.Unreadable -> R.string.agent_model_unreadable
    AgentModelProbe.Why.Unexpected -> R.string.agent_model_unexpected
}

/** One provider in the picker: the picked one says so in the brand's color. */
@Composable
private fun ProviderChoice(providerId: String, selected: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) NxPalette.Brand.copy(alpha = 0.14f) else NxPalette.Surface, shape)
            .border(
                1.dp,
                if (selected) NxPalette.Brand else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(providerLabelRes(providerId)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) NxPalette.Brand else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = NxPalette.Brand)
        }
    }
}
