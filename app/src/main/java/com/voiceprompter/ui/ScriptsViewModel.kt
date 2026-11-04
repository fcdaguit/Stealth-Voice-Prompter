package com.voiceprompter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceprompter.VoicePrompterApp
import com.voiceprompter.data.Script
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScriptsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = (application as VoicePrompterApp).database.scriptDao()

    private val query = MutableStateFlow("")

    @Suppress("OPT_IN_USAGE")
    val scripts: StateFlow<List<Script>> = query
        .flatMapLatest { q -> if (q.isBlank()) dao.observeAll() else dao.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        query.value = value
    }

    fun save(script: Script, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val stamped = script.copy(updatedAt = System.currentTimeMillis())
            val id = if (stamped.id == 0L) {
                dao.insert(stamped)
            } else {
                dao.update(stamped)
                stamped.id
            }
            onSaved(id)
        }
    }

    fun duplicate(script: Script) {
        viewModelScope.launch {
            dao.insert(script.copy(id = 0, title = "${script.title} (copy)"))
        }
    }

    fun delete(script: Script) {
        viewModelScope.launch { dao.delete(script) }
    }

    suspend fun byId(id: Long): Script? = dao.byId(id)
}
