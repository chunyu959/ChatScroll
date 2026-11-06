package com.chatscroll.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatscroll.app.R
import com.chatscroll.app.data.ReadingPreferenceValues
import com.chatscroll.app.data.Settings
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    rememberPosition: Boolean,
    rememberSeconds: Int,
    onRememberChange: (Boolean) -> Unit,
    onSecondsChange: (Int) -> Unit,
    onBack: () -> Unit,
    themeMode: String = Settings.DEFAULT_THEME_MODE,
    fontSize: Float = Settings.DEFAULT_FONT_SIZE,
    lineHeight: Float = Settings.DEFAULT_LINE_HEIGHT,
    onThemeChange: (String) -> Unit = {},
    onFontSizeChange: (Float) -> Unit = {},
    onLineHeightChange: (Float) -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    backupBusy: Boolean = false,
    pureReading: Boolean = false,
    onPureReadingChange: (Boolean) -> Unit = {},
    onStorage: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    var secondsText by rememberSaveable { mutableStateOf(rememberSeconds.coerceAtLeast(1).toString()) }
    var secondsFocused by rememberSaveable { mutableStateOf(false) }
    var previewFontSize by rememberSaveable(fontSize) {
        mutableStateOf(ReadingPreferenceValues.fontSize(fontSize))
    }
    var previewLineHeight by rememberSaveable(lineHeight) {
        mutableStateOf(ReadingPreferenceValues.lineHeight(lineHeight))
    }
    LaunchedEffect(rememberSeconds) {
        if (!secondsFocused) secondsText = rememberSeconds.coerceAtLeast(1).toString()
    }
    fun commitSeconds() {
        val corrected = ReadingPreferenceValues.secondsText(secondsText, rememberSeconds)
        secondsText = corrected.toString()
        if (corrected != rememberSeconds) onSecondsChange(corrected)
    }
    val back = {
        commitSeconds()
        focusManager.clearFocus()
        onBack()
    }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            SettingsSection(stringResource(R.string.settings_appearance_heading)) {
                SettingDescription(
                    stringResource(R.string.settings_theme_label),
                    stringResource(R.string.settings_theme_description)
                )
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    listOf(
                        "system" to R.string.settings_theme_system,
                        "light" to R.string.settings_theme_light,
                        "dark" to R.string.settings_theme_dark
                    ).forEach { (mode, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = themeMode == mode,
                                onClick = { onThemeChange(mode) },
                                role = Role.RadioButton
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = themeMode == mode, onClick = null)
                            Text(
                                stringResource(label),
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_reading_heading)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = pureReading,
                        role = Role.Switch,
                        onValueChange = onPureReadingChange
                    ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        SettingDescription(
                            stringResource(R.string.polish_pure_mode),
                            stringResource(R.string.polish_pure_mode_detail)
                        )
                    }
                    Switch(checked = pureReading, onCheckedChange = null)
                }
                Column {
                    val fontLabel = stringResource(R.string.settings_font_size_label)
                    SettingValueLabel(
                        fontLabel,
                        stringResource(R.string.settings_font_size_value, previewFontSize.toInt())
                    )
                    Slider(
                        value = previewFontSize,
                        onValueChange = { previewFontSize = round(it) },
                        onValueChangeFinished = { onFontSizeChange(previewFontSize) },
                        valueRange = Settings.MIN_FONT_SIZE..Settings.MAX_FONT_SIZE,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = fontLabel }
                    )
                }
                Column {
                    val lineLabel = stringResource(R.string.settings_line_height_label)
                    SettingValueLabel(
                        lineLabel,
                        stringResource(R.string.settings_line_height_value, previewLineHeight)
                    )
                    Slider(
                        value = previewLineHeight,
                        onValueChange = { previewLineHeight = round(it * 20f) / 20f },
                        onValueChangeFinished = { onLineHeightChange(previewLineHeight) },
                        valueRange = Settings.MIN_LINE_HEIGHT..Settings.MAX_LINE_HEIGHT,
                        steps = 15,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = lineLabel }
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        stringResource(R.string.settings_reading_preview),
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = previewFontSize.sp,
                        lineHeight = (previewFontSize * previewLineHeight).sp,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
            SettingsSection(stringResource(R.string.settings_position_heading)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = rememberPosition,
                        role = Role.Switch,
                        onValueChange = {
                            commitSeconds()
                            focusManager.clearFocus()
                            onRememberChange(it)
                        }
                    ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        SettingDescription(
                            stringResource(R.string.settings_remember),
                            stringResource(R.string.settings_remember_desc)
                        )
                    }
                    Switch(checked = rememberPosition, onCheckedChange = null)
                }
                if (rememberPosition) {
                    OutlinedTextField(
                        value = secondsText,
                        onValueChange = { secondsText = it.filter { char -> char in '0'..'9' }.take(4) },
                        label = { Text(stringResource(R.string.settings_frequency)) },
                        supportingText = { Text(stringResource(R.string.settings_frequency_desc)) },
                        suffix = { Text(stringResource(R.string.settings_seconds_unit)) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            commitSeconds()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged {
                            val wasFocused = secondsFocused
                            secondsFocused = it.isFocused
                            if (wasFocused && !it.isFocused) commitSeconds()
                        }
                    )
                }
            }
            SettingsSection(stringResource(R.string.polish_storage_title)) {
                Text(
                    stringResource(R.string.polish_storage_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = onStorage,
                    enabled = !backupBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.polish_storage_open), style = MaterialTheme.typography.labelLarge)
                }
            }
            SettingsSection(stringResource(R.string.settings_backup_heading)) {
                Text(
                    stringResource(R.string.settings_backup_notice),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onBackup,
                    enabled = !backupBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.settings_backup_export), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = onRestore,
                    enabled = !backupBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.settings_backup_restore), style = MaterialTheme.typography.labelLarge)
                }
                if (backupBusy) {
                    Text(
                        stringResource(R.string.settings_backup_busy),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

@Composable
private fun SettingDescription(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(4.dp))
    Text(
        description,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun SettingValueLabel(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
}
