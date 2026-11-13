package com.bikeguard.app.ui.expenses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bikeguard.app.R
import com.bikeguard.app.ui.fmtDate
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel

/** Expense log (fuel, service, repair, other) with this month's total. Remote role only. */
@Composable
fun ExpensesScreen(modifier: Modifier = Modifier, vm: ExpensesViewModel = viewModel()) {
    val list by vm.expenses.collectAsStateWithLifecycle()
    val total by vm.monthTotal.collectAsStateWithLifecycle()
    var category by remember { mutableStateOf("fuel") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Text(
            stringResource(R.string.tab_expenses), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.exp_total_month, "৳" + total.toInt()), style = MaterialTheme.typography.titleLarge, color = Palette.Ember)
        }
        Spacer(Modifier.height(12.dp))
        Panel(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fuel" to R.string.exp_fuel, "service" to R.string.exp_service, "repair" to R.string.exp_repair, "other" to R.string.exp_other)
                    .forEach { (value, label) ->
                        FilterChip(
                            selected = category == value, onClick = { category = value }, label = { Text(stringResource(label)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Palette.Ember.copy(alpha = 0.2f), selectedLabelColor = Palette.Ember,
                            ),
                        )
                    }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                singleLine = true, label = { Text(stringResource(R.string.exp_amount)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note, onValueChange = { note = it }, singleLine = true,
                label = { Text(stringResource(R.string.exp_note)) }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val a = amount.toDoubleOrNull() ?: 0.0
                    if (a > 0) { vm.add(category, a, note); amount = ""; note = "" }
                },
                enabled = (amount.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.exp_add)) }
        }
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.exp_empty), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.id }) { e ->
                    Panel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(
                                        when (e.category) {
                                            "fuel" -> R.string.exp_fuel
                                            "service" -> R.string.exp_service
                                            "repair" -> R.string.exp_repair
                                            else -> R.string.exp_other
                                        }
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (e.note.isNotBlank()) Text(e.note, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                                Text(fmtDate(e.spentOn), color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                            }
                            Text("৳" + e.amount.toInt(), style = MaterialTheme.typography.titleLarge, color = Palette.Ember)
                            IconButton(onClick = { vm.delete(e.id) }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.exp_delete), tint = Palette.Danger)
                            }
                        }
                    }
                }
            }
        }
    }
}
