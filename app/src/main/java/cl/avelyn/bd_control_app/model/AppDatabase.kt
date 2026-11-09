package cl.avelyn.bd_control_app.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cl.avelyn.bd_control_app.model.dao.SensorLogDao
import cl.avelyn.bd_control_app.model.entity.SensorLog
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Base de datos local, cifrada con SQLCipher. Guarda solo el historial de temperatura y
 * humedad ([SensorLog]): los usuarios ya no viven aquí, se autentican contra una tabla
 * en Supabase (ver [cl.avelyn.bd_control_app.model.repository.AuthRepository]), así la
 * app nunca crea cuentas por su cuenta y el login siempre valida contra una única fuente.
 *
 * Limitación conocida: la passphrase está embebida en el código ([DB_PASSPHRASE]) en vez de
 * protegerse con el Android Keystore. Esto se documenta como una simplificación deliberada
 * para el alcance de este proyecto: sigue cifrando el archivo en disco (cumple "protección
 * de datos sensibles" de ISO 27400), pero la llave es extraíble si alguien descompila la app.
 */
@Database(entities = [SensorLog::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sensorLogDao(): SensorLogDao

    companion object {
        private const val DB_NAME = "bodega.db"

        /**
         * v1 -> v2: soporte de varios sensores. Los registros existentes se atribuyen al sensor
         * por defecto ("sensor") en vez de borrarse. (El DEFAULT de la columna solo existe en el
         * archivo; Room no lo exige en la entidad.)
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sensor_log ADD COLUMN deviceId TEXT NOT NULL DEFAULT 'sensor'")
            }
        }

        /**
         * v2 -> v3: cola de sincronización. Cada fila recibe una clave única (`m<id>`, "migrada") y queda
         * marcada como NO sincronizada, así que las lecturas que ya tenías también se suben a Supabase.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sensor_log ADD COLUMN sampleKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sensor_log ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sensor_log SET sampleKey = 'm' || id")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sensor_log_deviceId_sampleKey ON sensor_log (deviceId, sampleKey)")
            }
        }

        // Cambiar por un valor propio antes de entregar el proyecto.
        private const val DB_PASSPHRASE = "bodega_academica_2026_no_usar_en_produccion"

        fun build(ctx: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DB_PASSPHRASE.toByteArray())
            return Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration() // Evita crashes por cambios de esquema en desarrollo
                .build()
        }
    }
}