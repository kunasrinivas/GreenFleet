package com.greenfleet.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** History intentionally stores statistics only, never addresses, coordinates or Google geometry. */
@Entity(tableName = "route_history")
data class RouteHistory(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val stops: Int,
    val distanceKm: Double,
    val durationMinutes: Double,
    val co2Grams: Double,
    val co2SavedGrams: Double,
    val emissionFactor: Double,
    val isDemo: Boolean,
    val completedLegs: Int,
    val totalLegs: Int,
)

@Dao
abstract class HistoryDao {
    @Query("SELECT * FROM route_history ORDER BY createdAt DESC, id DESC LIMIT 20")
    abstract fun observe(): Flow<List<RouteHistory>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(route: RouteHistory)
    @Query("DELETE FROM route_history WHERE id NOT IN (SELECT id FROM route_history ORDER BY createdAt DESC, id DESC LIMIT 20)")
    abstract suspend fun trim()
    @Query("UPDATE route_history SET completedLegs = :completed WHERE id = :id")
    abstract suspend fun updateProgress(id: String, completed: Int)
    @Query("DELETE FROM route_history")
    abstract suspend fun clear()
    @Transaction
    open suspend fun save(route: RouteHistory) { insert(route); trim() }
}

@Database(entities = [RouteHistory::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() { abstract fun history(): HistoryDao }

