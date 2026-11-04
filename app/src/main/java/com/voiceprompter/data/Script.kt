package com.voiceprompter.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scripts")
data class Script(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    /** Null means the script sits at the top level. */
    val folder: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
