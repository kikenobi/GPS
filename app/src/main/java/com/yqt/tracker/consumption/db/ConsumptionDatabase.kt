package com.yqt.tracker.consumption.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [UsageEventEntity::class], version = 1, exportSchema = false)
abstract class ConsumptionDatabase : RoomDatabase() {
    abstract fun usageEventDao(): UsageEventDao

    companion object {
        @Volatile private var INSTANCE: ConsumptionDatabase? = null

        fun get(context: Context): ConsumptionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ConsumptionDatabase::class.java,
                    "consumption.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}