package com.yqt.tracker.consumption.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UsageEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: UsageEventEntity): Long

    @Query("SELECT COALESCE(SUM(billableQuantity), 0) FROM usage_events WHERE serviceId = :serviceId AND periodKey = :periodKey AND result = 'ok'")
    suspend fun sumOkForService(serviceId: String, periodKey: String): Double

    @Query("SELECT * FROM usage_events WHERE serviceId = :serviceId AND periodKey = :periodKey ORDER BY timestampMs DESC")
    suspend fun eventsForService(serviceId: String, periodKey: String): List<UsageEventEntity>

    @Query("SELECT * FROM usage_events WHERE periodKey = :periodKey ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun eventsForPeriod(periodKey: String, limit: Int = 500): List<UsageEventEntity>

    @Query("SELECT DISTINCT periodKey FROM usage_events ORDER BY periodKey DESC")
    suspend fun listPeriods(): List<String>

    @Query("SELECT * FROM usage_events ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<UsageEventEntity>

    @Query("SELECT COUNT(*) FROM usage_events WHERE operationId = :operationId")
    suspend fun countByOperationId(operationId: String): Int
}