package com.yqt.tracker.consumption.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usage_events",
    indices = [
        Index(value = ["periodKey"]),
        Index(value = ["serviceId"]),
        Index(value = ["operationId"], unique = true),
        Index(value = ["timestampMs"]),
    ]
)
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val periodKey: String, // yyyy-MM (periodo lógico mensual)
    val serviceId: String,
    val sku: String,
    val operationType: String,
    val operationId: String,
    val result: String, // ok / error
    val billableQuantity: Double,
    val origin: String,
    val metadata: String? = null,
)