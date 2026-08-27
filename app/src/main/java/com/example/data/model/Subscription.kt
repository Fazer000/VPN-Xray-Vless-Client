package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val serverCount: Int = 0,
    val isAutoUpdateEnabled: Boolean = true
)
