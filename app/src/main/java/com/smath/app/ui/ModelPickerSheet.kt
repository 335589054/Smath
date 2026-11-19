package com.smath.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smath.app.data.AppSettings
import com.smath.app.data.ThinkingLevel
import com.smath.app.data.providerKindColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSelect: (providerId: String, model: String) -> Unit,
    onThinkingLevel: (ThinkingLevel) -> Unit,
    onManage: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "选择模型",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "不同服务商需要在设置中分别填写 API Key",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    onDismiss()
                    onManage()
                }) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("管理")
                }
            }
            Spacer(Modifier.height(10.dp))
            settings.providers.forEach { provider ->
                val color = providerKindColor(provider.kind)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallDot(color, size = 8)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            provider.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (!provider.hasKey) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Key,
                                    contentDescription = null,
                                    modifier = Modifier.width(13.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "未配置 Key",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    provider.models.forEach { model ->
                        val active = settings.activeProviderId == provider.id && settings.activeModel == model
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(provider.id, model)
                                    onDismiss()
                                }
                                .padding(horizontal = 26.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = model,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (active) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "当前模型",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(19.dp)
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 22.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Column(modifier = Modifier.padding(horizontal = 22.dp)) {
                Text(
                    "思考强度",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    settings.thinking.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThinkingLevel.entries.forEach { level ->
                        FilterChip(
                            selected = settings.thinking == level,
                            onClick = { onThinkingLevel(level) },
                            label = { Text(level.label) }
                        )
                    }
                }
                Text(
                    "仅对支持思考的模型生效；只控制内部推理，不会压缩最终回答。DeepSeek 会同时发送 thinking 与 reasoning_effort。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "提示：DeepSeek 官方的 deepseek-flash 支持图片输入；ChatGPT 需选择支持视觉的模型。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}