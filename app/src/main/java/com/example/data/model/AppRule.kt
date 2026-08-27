package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isProxied: Boolean = false,
    val isSystemApp: Boolean = false
)
