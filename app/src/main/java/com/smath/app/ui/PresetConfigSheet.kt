package com.smath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smath.app.data.AgentPreset
import com.smath.app.data.AnswerStyle
import com.smath.app.data.Stage
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PresetConfigSheet(
    preset: AgentPreset,
    locked: Boolean,
    onDismiss: () -> Unit,
    onSave: (AgentPreset) -> Unit,
    onReset: () -> Unit,
    isNew: Boolean = false,
    onDelete: (() -> Unit)? = null,
    drawingEnabled: Boolean = false,
    calcEnabled: Boolean = false,
    leanEnabled: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nameEditable = isNew || !preset.builtIn
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var stage by remember(preset.id) { mutableStateOf(preset.stage) }
    var styles by remember(preset.id) { mutableStateOf(preset.effectiveStyles.toSet()) }
    var instruction by remember(preset.id) { mutableStateOf(preset.customInstruction) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(top = 20.dp, bottom = 18.dp)
        ) {
            Text(
                text = "Agent 预设",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "「${if (nameEditable) name.ifBlank { "自定义预设" } else preset.name}」的思考方式与回答风格",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (locked) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "当前对话已开始，预设已锁定。新建对话后可再次调整。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            if (nameEditable) {
                SectionLabel("预设名称")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("自定义预设", fontSize = 14.sp) }
                )
                Spacer(Modifier.height(22.dp))
            }
            SectionLabel("学段")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stage.entries.forEach { option ->
                    FilterChip(
                        selected = stage == option,
                        enabled = !locked,
                        onClick = { stage = option },
                        label = { Text(option.label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (stage) {
                    Stage.UNIVERSITY -> "按大学水平回答，仅在需要时提醒可能遗忘的高中知识。"
                    Stage.HIGH_SCHOOL -> "不引入超出高中的知识，除非问题本身涉及。"
                    Stage.MIDDLE_SCHOOL -> "不引入超出初中的知识，除非问题本身涉及。"
                    Stage.PRIMARY -> "只用小学基础知识，语气更亲切、耐心。"
                    Stage.NONE -> "不限制学段。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel("解答风格（可多选）")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                preset.availableStyles.forEach { option ->
                    val needsDrawing = option == AnswerStyle.VISUAL_RICH && !drawingEnabled
                    // “多用计算器agent”要用计算器或线性代数计算器去真的算一遍。
                    val needsCalc = option == AnswerStyle.MULTI_NUMERIC_CHECK && !calcEnabled
                    // “鼓励使用lean编译agent”必须先配好 lean 编译工具并开启。
                    val needsLean = option == AnswerStyle.LEAN_COMPILE && !leanEnabled
                    FilterChip(
                        selected = styles.contains(option),
                        enabled = !locked && !needsDrawing && !needsCalc && !needsLean,
                        onClick = {
                            styles = if (styles.contains(option)) styles - option else styles + option
                        },
                        label = { Text(option.label) }
                    )
                }
            }
            if (!drawingEnabled || !calcEnabled || !leanEnabled) {
                Spacer(Modifier.height(6.dp))
                val hints = buildList {
                    if (!drawingEnabled) add("「图文并茂」需要先在设置中开启绘图 Agent")
                    if (!calcEnabled) add("「多用计算器agent」需要先在设置中开启计算器或线性代数计算器 Agent")
                    if (!leanEnabled) add("「鼓励使用lean编译agent」需要先在设置中配置并开启 lean 编译 Agent")
                }
                Text(
                    text = hints.joinToString("；") + "。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("补充要求")
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 7,
                shape = RoundedCornerShape(16.dp),
                placeholder = {
                    Text("例如：先给思路，再给步骤；最后总结易错点。", fontSize = 14.sp)
                },
                supportingText = {
                    Text(
                        "默认还会注入基础人设提示词和 Markdown/LaTeX 排版要求。",
                        fontSize = 12.sp
                    )
                }
            )

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (preset.builtIn && !locked) {
                    TextButton(onClick = {
                        onReset()
                        onDismiss()
                    }) { Text("恢复默认") }
                }
                if (!preset.builtIn && !isNew && onDelete != null && !locked) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        if (!locked) {
                            onSave(
                                preset.copy(
                                    name = if (nameEditable) {
                                        name.trim().ifBlank { "自定义预设" }
                                    } else {
                                        preset.name
                                    },
                                    stage = stage,
                                    styles = preset.availableStyles.filter { styles.contains(it) },
                                    customInstruction = instruction.trim()
                                )
                            )
                        }
                        onDismiss()
                    }
                ) { Text(if (locked) "关闭" else "完成") }
            }
        }
    }

    if (confirmDelete) {
        SmathConfirmDialog(
            title = "删除预设？",
            message = "删除后无法恢复。已经开始的对话不会受影响。",
            confirmLabel = "删除",
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete?.invoke()
                onDismiss()
            }
        )
    }
}