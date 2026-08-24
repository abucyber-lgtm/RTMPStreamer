package com.example.rtmpstreamer.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface StreamProfileDao {

    @Query("SELECT * FROM stream_profiles ORDER BY createdAt ASC")
    fun observeAll(): LiveData<List<StreamProfile>>

    @Query("SELECT * FROM stream_profiles WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun getEnabled(): List<StreamProfile>

    @Insert
    suspend fun insert(profile: StreamProfile): Long

    @Update
    suspend fun update(profile: StreamProfile)

    @Delete
    suspend fun delete(profile: StreamProfile)

    @Query("UPDATE stream_profiles SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
