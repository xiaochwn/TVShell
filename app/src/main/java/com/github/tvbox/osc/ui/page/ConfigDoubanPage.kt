package com.github.tvbox.osc.ui.page

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tvshell.app.R
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsTestButton

private val SectionSpacing = 28.dp

private val CardContentPadding = 20.dp

private val ButtonRowPadding = 12.dp

@Composable
fun ConfigDoubanScreen(
    state: ConfigDoubanState,
    actions: ConfigDoubanActions,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) actions.onCookiePicked(uri)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        SettingsGroup(title = null) {
            SettingsCard(SettingsCardPosition.FIRST) {
                SettingsRow(
                    title = stringResource(R.string.douban_status_label),
                    leadingIconRes = R.drawable.ic_config_douban,
                    valueText = if (state.imported) {
                        stringResource(R.string.douban_status_imported, state.masked)
                    } else {
                        stringResource(R.string.douban_status_missing)
                    },
                )
            }
            SettingsCard(SettingsCardPosition.LAST) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardContentPadding, vertical = ButtonRowPadding),
                    horizontalArrangement = Arrangement.spacedBy(ButtonRowPadding),
                ) {
                    SettingsTestButton(
                        text = stringResource(R.string.douban_import),
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                    )
                    SettingsTestButton(
                        text = stringResource(R.string.douban_clear),
                        onClick = actions.clearCookie,
                        enabled = state.imported,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SettingsGroup(title = null) {
            SettingsCard(SettingsCardPosition.SINGLE) {
                Text(
                    text = stringResource(R.string.douban_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = CardContentPadding,
                        vertical = CardContentPadding,
                    ),
                )
            }
        }
    }
}
