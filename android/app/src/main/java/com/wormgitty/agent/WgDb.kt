package com.wormgitty.agent

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** Content-hash keyed pulled-photo ledger (dedup is by sha256, never MediaStore id). */
@Entity(tableName = "pulled_photos")
data class PulledPhotoEntity(
    @PrimaryKey val hash: String,
    val name: String,
    val category: String,
    val mediaId: Long,
    val pulledAtMs: Long
)

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(rows: List<PulledPhotoEntity>): List<Long>

    @Query("SELECT hash FROM pulled_photos")
    fun allHashes(): List<String>

    @Query("SELECT COUNT(*) FROM pulled_photos")
    fun count(): Int

    @Query("SELECT * FROM pulled_photos ORDER BY pulledAtMs ASC")
    fun all(): List<PulledPhotoEntity>
}

@Database(entities = [PulledPhotoEntity::class], version = 1, exportSchema = false)
abstract class WgDb : RoomDatabase() {
    abstract fun photos(): PhotoDao

    companion object {
        @Volatile private var instance: WgDb? = null

        fun get(ctx: Context): WgDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    ctx.applicationContext, WgDb::class.java, "wormgitty.db"
                ).build().also { instance = it }
            }
    }
}
