package com.unihub.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.unihub.app.core.common.DateFormats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** غلاف موحّد للألواح السفلية (بديل عملي وأوسع من نوافذ الحوار الضيقة في المرجع) */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppSheet(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            content()
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                actions()
            }
        }
    }
}

/** حقل إدخال مع تسمية ورسالة خطأ اختيارية */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 8,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.small
    )
}

/** صف شرائح اختيار واحد (أولوية/نوع/يوم...) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label) }
            )
        }
    }
}

/** حقل تاريخ يفتح منتقي تواريخ النظام ويعيد قيمة ISO */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    valueIso: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = valueIso?.let { DateFormats.format(it) } ?: "",
        onValueChange = {},
        readOnly = true,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        shape = MaterialTheme.shapes.small,
        trailingIcon = {
            if (valueIso != null && onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "مسح التاريخ")
                }
            } else {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = "اختيار التاريخ")
                }
            }
        }
    )

    if (showPicker) {
        val initialMillis = DateFormats.parseDateOrNull(valueIso)
            ?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis)
                            .atOffset(ZoneOffset.UTC)
                            .toLocalDate()
                        onPick(date.format(DateFormats.DATE))
                    }
                    showPicker = false
                }) { Text("تم") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("إلغاء") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

/** حقل وقت يفتح منتقي أوقات النظام ويعيد قيمة HH:mm */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    value: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value ?: "",
        onValueChange = {},
        readOnly = true,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        shape = MaterialTheme.shapes.small,
        trailingIcon = {
            if (value != null && onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "مسح الوقت")
                }
            } else {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Filled.Schedule, contentDescription = "اختيار الوقت")
                }
            }
        }
    )

    if (showPicker) {
        val initial = DateFormats.parseTimeOrNull(value)
        val state = rememberTimePickerState(
            initialHour = initial?.hour ?: 9,
            initialMinute = initial?.minute ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(
                        LocalTime.of(state.hour, state.minute).format(DateFormats.TIME)
                    )
                    showPicker = false
                }) { Text("تم") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("إلغاء") }
            },
            text = { TimePicker(state = state) }
        )
    }
}
