package com.bikeguard.app.ui.expenses

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
        )

        // ---- this month's total: pale mint banner, dark bold text, chart icon ----
        // Light: mint at 28% alpha over white. Dark: the same amber at 16% alpha.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Palette.Ember.copy(alpha = if (Palette.isLight) 0.28f else 0.16f),
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.exp_total_month, "৳" + Math.round(total)),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = Palette.Text, modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.BarChart, null, tint = Palette.Leaf, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- add-expense form, one white card ----
        Panel(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("fuel" to R.string.exp_fuel, "service" to R.string.exp_service, "repair" to R.string.exp_repair, "other" to R.string.exp_other)
                    .forEach { (value, label) ->
                        // Light: solid mint chip with dark text. Dark: 16% amber tint, amber text.
                        val selected = category == value
                        FilterChip(
                            selected = selected, onClick = { category = value },
                            modifier = Modifier.weight(1f),
                            // One line each: "Other" used to wrap letter by letter ("Ot / he / r")
                            // because the fixed row ran out of width.
                            label = {
                                Text(
                                    stringResource(label), style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            },
                            shape = RoundedCornerShape(24.dp),
                            border = if (selected) null else BorderStroke(1.dp, Palette.Stroke),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (Palette.isLight) Palette.Ember else Palette.Ember.copy(alpha = 0.16f),
                                selectedLabelColor = if (Palette.isLight) Palette.Text else Palette.Ember,
                                containerColor = Palette.Card, labelColor = Palette.Text,
                            ),
                        )
                    }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.exp_amount)) },
                leadingIcon = { Icon(Icons.Default.AccountBalanceWallet, null, tint = Palette.Text) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card,
                    focusedBorderColor = Palette.Leaf, unfocusedBorderColor = Palette.Stroke,
                    cursorColor = Palette.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note, onValueChange = { note = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.exp_note)) },
                leadingIcon = { Icon(Icons.Default.Description, null, tint = Palette.Text) },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card,
                    focusedBorderColor = Palette.Leaf, unfocusedBorderColor = Palette.Stroke,
                    cursorColor = Palette.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val a = amount.toDoubleOrNull() ?: 0.0
                    if (a > 0) { vm.add(category, a, note); amount = ""; note = "" }
                },
                enabled = (amount.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Ember,
                    // Mint with dark text in light; amber needs the dark ink text in dark.
                    contentColor = if (Palette.isLight) Palette.Text else Palette.Ink,
                    disabledContainerColor = Palette.Ember.copy(alpha = 0.4f),
                    disabledContentColor = (if (Palette.isLight) Palette.Text else Palette.Ink).copy(alpha = 0.5f),
                ),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.exp_add),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (list.isEmpty()) {
            // Empty state: clipboard icon, bold first sentence, muted rest.
            // exp_empty is one string in both locales, so it is split at its
            // first sentence end ("." in English, "।" in Bengali) — no new string.
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Assignment, null, tint = Palette.Text, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(12.dp))
                    val (title, body) = splitEmpty(stringResource(R.string.exp_empty))
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (body.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(body, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
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
                            // Dark amount text: mint on white is unreadable (v1.2.23 pattern).
                            Text("৳" + e.amount.toInt(), style = MaterialTheme.typography.titleLarge, color = Palette.Text)
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

/** Splits "First sentence. Rest." into ("First sentence.", "Rest."). No terminator: whole text, empty rest. */
private fun splitEmpty(text: String): Pair<String, String> {
    val idx = text.indexOfFirst { it == '.' || it == '।' }
    if (idx < 0 || idx + 1 >= text.length) return text to ""
    return text.substring(0, idx + 1) to text.substring(idx + 1).trim()
}
