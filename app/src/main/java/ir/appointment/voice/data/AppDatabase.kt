package ir.appointment.voice.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AppointmentEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appointmentDao(): AppointmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "appointments.db"
                )
                    // The 'title' column was added in version 2 (for general task
                    // reminders, not just appointments). No real users/production
                    // data exist yet at this stage, so a destructive fallback is the
                    // simplest safe choice rather than writing a manual migration —
                    // it just means anyone upgrading loses previously-saved items
                    // once, along with their audio files.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
