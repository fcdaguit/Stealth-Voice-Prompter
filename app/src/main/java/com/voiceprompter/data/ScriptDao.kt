package com.voiceprompter.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptDao {

    @Query("SELECT * FROM scripts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Script>>

    @Query("SELECT * FROM scripts WHERE folder IS :folder ORDER BY updatedAt DESC")
    fun observeInFolder(folder: String?): Flow<List<Script>>

    @Query(
        "SELECT * FROM scripts WHERE title LIKE '%' || :query || '%' " +
            "OR body LIKE '%' || :query || '%' ORDER BY updatedAt DESC",
    )
    fun search(query: String): Flow<List<Script>>

    @Query("SELECT DISTINCT folder FROM scripts WHERE folder IS NOT NULL ORDER BY folder")
    fun observeFolders(): Flow<List<String>>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun byId(id: Long): Script?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: Script): Long

    @Update
    suspend fun update(script: Script)

    @Delete
    suspend fun delete(script: Script)
}
