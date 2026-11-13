package com.bikeguard.app.ui.expenses

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bikeguard.app.BikeGuardApp
import com.bikeguard.app.data.ExpenseRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class ExpensesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BikeGuardApp
    private val dao = app.db.expenses()

    val expenses: StateFlow<List<ExpenseRecord>> =
        dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val monthStartMs = LocalDate.now().withDayOfMonth(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val monthTotal: StateFlow<Double> =
        dao.totalSince(monthStartMs).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    fun add(category: String, amount: Double, note: String) {
        if (amount <= 0) return
        viewModelScope.launch {
            dao.insert(
                ExpenseRecord(
                    category = category, amount = amount, note = note.trim(),
                    spentOn = System.currentTimeMillis(),
                )
            )
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { dao.delete(id) }
    }
}
