package com.chatscroll.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatscroll.app.R
import com.chatscroll.app.data.Settings
import com.chatscroll.app.ui.theme.InkSoft

/**
 * The app's settings. So far it only holds the reading-position preference, laid
 * out as a list so later options can be added below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    rememberPosition: Boolean,
    rememberSeconds: Int,
    onRememberChange: (Boolean) -> Unit,
    onSecondsChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    // Kept as typed text so clearing the field while editing is not snapped back.
    var secondsText by rememberSaveable { mutableStateOf(rememberSeconds.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)) {
            SettingRow(
                title = stringResource(R.string.settings_remember),
                description = stringResource(R.string.settings_remember_desc)
            ) {
                Switch(checked = rememberPosition, onCheckedChange = onRememberChange)
            }

            if (rememberPosition) {
                Spacer(Modifier.height(8.dp))
                SettingRow(
                    title = stringResource(R.string.settings_frequency),
                    description = stringResource(R.string.settings_frequency_desc)
                ) {
                    TextField(
                        value = secondsText,
                        onValueChange = { typed ->
                            val digits = typed.filter { it.isDigit() }.take(4)
                            secondsText = digits
                            digits.toIntOrNull()?.let { onSecondsChange(it) }
                        },
                        singleLine = true,
                        suffix = { Text(stringResource(R.string.settings_seconds_unit)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, description: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(description, color = InkSoft, fontSize = 13.sp, lineHeight = 18.sp)
        }
        control()
    }
}
