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

    init {
        // Pull the server log on open, so expenses added before a reinstall or
        // a local wipe come back, and push anything still unsynced (v1.2.15).
        viewModelScope.launch { app.sync.syncExpenses() }
    }

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
                    // The client UUID is the server row's id, so the upload is
                    // idempotent and a retry never duplicates the expense.
                    serverId = java.util.UUID.randomUUID().toString(),
                )
            )
            app.sync.syncExpenses()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val row = dao.get(id) ?: return@launch
            // Delete the server row first when it is already up there: if the
            // phone is offline the delete fails and the local row stays, so the
            // next sync cannot resurrect a deleted expense from the server.
            if (row.synced && row.serverId.isNotBlank() && !app.api.deleteExpense(row.serverId)) return@launch
            dao.delete(id)
        }
    }
}
