package com.example.data.db

import androidx.room.*
import com.example.data.model.VpnServer
import kotlinx.coroutines.flow.Flow

@Dao
interface VpnServerDao {
    @Query("SELECT * FROM vpn_servers ORDER BY isPinned DESC, groupName ASC, name ASC")
    fun getAllServers(): Flow<List<VpnServer>>

    @Query("SELECT * FROM vpn_servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: String): VpnServer?

    @Query("SELECT * FROM vpn_servers WHERE groupName = :groupName ORDER BY isPinned DESC, name ASC")
    fun getServersByGroup(groupName: String): Flow<List<VpnServer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<VpnServer>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: VpnServer)

    @Update
    suspend fun updateServer(server: VpnServer)

    @Query("UPDATE vpn_servers SET isPinned = :isPinned WHERE id = :serverId")
    suspend fun setPinned(serverId: String, isPinned: Boolean)

    @Query("UPDATE vpn_servers SET latencyMs = :latencyMs, lastPingTimestamp = :timestamp WHERE id = :serverId")
    suspend fun updateLatency(serverId: String, latencyMs: Long, timestamp: Long)

    @Query("DELETE FROM vpn_servers WHERE subscriptionId = :subscriptionId")
    suspend fun deleteServersBySubscription(subscriptionId: String)

    @Query("DELETE FROM vpn_servers WHERE id = :id")
    suspend fun deleteServerById(id: String)

    @Query("DELETE FROM vpn_servers")
    suspend fun deleteAllServers()
}
