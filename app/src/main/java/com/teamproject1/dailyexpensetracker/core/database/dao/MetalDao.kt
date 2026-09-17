package com.teamproject1.dailyexpensetracker.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.teamproject1.dailyexpensetracker.core.database.entity.MetalHoldingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MetalHoldingDao {
    @Insert
    suspend fun insert(holding: MetalHoldingEntity): Long

    @Update
    suspend fun update(holding: MetalHoldingEntity)

    @Query("SELECT * FROM metal_holdings WHERE bookId = :bookId AND isArchived = 0 ORDER BY metalType ASC, caratType ASC, form ASC")
    fun getActive(bookId: Long): Flow<List<MetalHoldingEntity>>

    @Query("SELECT * FROM metal_holdings WHERE bookId = :bookId AND isArchived = 0 ORDER BY metalType ASC, caratType ASC, form ASC")
    suspend fun getActiveOnce(bookId: Long): List<MetalHoldingEntity>

    @Query("SELECT * FROM metal_holdings WHERE bookId = :bookId AND isArchived = 1 ORDER BY metalType ASC, caratType ASC, form ASC")
    fun getArchived(bookId: Long): Flow<List<MetalHoldingEntity>>

    @Query("UPDATE metal_holdings SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("UPDATE metal_holdings SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)
}
