package com.example.data.db

import androidx.room.*
import com.example.data.model.AppRule
import kotlinx.coroutines.flow.Flow

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM app_rules ORDER BY appName ASC")
    fun getAllRules(): Flow<List<AppRule>>

    @Query("SELECT * FROM app_rules WHERE isProxied = 1")
    suspend fun getProxiedRules(): List<AppRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<AppRule>)

    @Query("UPDATE app_rules SET isProxied = :isProxied WHERE packageName = :packageName")
    suspend fun setRuleProxied(packageName: String, isProxied: Boolean)

    @Query("UPDATE app_rules SET isProxied = :isProxied")
    suspend fun setAllRulesProxied(isProxied: Boolean)
}
