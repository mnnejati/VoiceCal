package ir.appointment.voice.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppointmentDao {

    // Nearest date first. Rows with unknown date (NULL sortTimestamp) go last.
    @Query(
        """
        SELECT * FROM appointments
        ORDER BY
            CASE WHEN sortTimestamp IS NULL THEN 1 ELSE 0 END ASC,
            sortTimestamp ASC
        """
    )
    fun observeAll(): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM appointments")
    suspend fun observeAllOnce(): List<AppointmentEntity>

    @Insert
    suspend fun insert(appointment: AppointmentEntity): Long

    @Update
    suspend fun update(appointment: AppointmentEntity)

    @Delete
    suspend fun delete(appointment: AppointmentEntity)
}
