package com.shanks.minify.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shanks.minify.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TextPrim = Color(0xFFFFFFFF)
private val TextSec  = Color(0xFF8E8E93)

@Composable
fun CodecSelector(
    selected: CodecChoice,
    onChange: (CodecChoice) -> Unit,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    var expanded by remember { mutableStateOf(value = false) }

    val statusMap = remember {
        mutableStateMapOf<CodecChoice, CodecAvailability.CodecStatus>().also { map ->
            CodecChoice.entries.forEach {
                map[it] = CodecAvailability.CodecStatus(supported = true, isHardware = true)
            }
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            CodecChoice.entries.forEach { choice ->
                val status = CodecAvailability.getStatus(choice)
                withContext(Dispatchers.Main) { statusMap[choice] = status }
            }
        }
    }

    Card(
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = selected.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) TextPrim else TextPrim.copy(alpha = 0.38f)
                )
                Text(
                    text  = selected.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) TextSec else TextSec.copy(alpha = 0.38f)
                )
            }

            Box {
                OutlinedButton(
                    onClick  = { if (enabled) expanded = true },
                    enabled  = enabled,
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                    border   = ButtonDefaults.outlinedButtonBorder.copy()
                ) {
                    Text("Change", color = if (enabled) accent else accent.copy(alpha = 0.38f))
                }

                DropdownMenu(
                    expanded          = expanded,
                    onDismissRequest  = { expanded = false },
                    containerColor    = Surface2
                ) {
                    CodecChoice.entries.forEach { choice ->
                        val status    = statusMap[choice]
                        val available = status?.supported == true

                        val reasonSuffix = when {
                            status == null      -> ""
                            !status.supported   -> " — ${status.unavailableReason}"
                            else                -> ""
                        }

                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text  = choice.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (available) TextPrim
                                        else TextPrim.copy(alpha = 0.38f)
                                    )
                                    Text(
                                        text  = "${choice.subtitle}$reasonSuffix",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (available) TextSec
                                        else TextSec.copy(alpha = 0.35f)
                                    )
                                }
                            },
                            onClick = {
                                if (available) { onChange(choice); expanded = false }
                            },
                            enabled = available,
                            trailingIcon = {
                                if (choice == selected) {
                                    Text("✓", color = accent)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}