package com.dawnmoon.music_download.ui.workbench

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.domain.model.MusicQuality

/**
 * 工作台的音质下拉选择器。
 *
 * 显示当前任务将使用的音质，展开后列出全部等级；选择只影响本次入队，
 * 不回写设置中的默认值（由 ViewModel 维护两者的区别）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelector(
    selected: MusicQuality,
    onQualityChange: (MusicQuality) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.quality_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MusicQuality.entries.forEach { quality ->
                DropdownMenuItem(
                    text = { Text(quality.label) },
                    onClick = {
                        expanded = false
                        onQualityChange(quality)
                    },
                )
            }
        }
    }
}
