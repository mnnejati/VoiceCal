package ir.appointment.voice.data

import java.io.File
import kotlinx.coroutines.flow.Flow

class AppointmentRepository(private val dao: AppointmentDao) {

    fun observeAll(): Flow<List<AppointmentEntity>> = dao.observeAll()

    suspend fun getAllOnce(): List<AppointmentEntity> = dao.observeAllOnce()


    suspend fun save(appointment: AppointmentEntity): Long = dao.insert(appointment)

    suspend fun update(appointment: AppointmentEntity) = dao.update(appointment)

    /** Deletes the DB row AND the associated audio file from device storage. */
    suspend fun deleteWithAudio(appointment: AppointmentEntity) {
        dao.delete(appointment)
        val file = File(appointment.audioFilePath)
        if (file.exists()) {
            file.delete()
        }
    }
}
