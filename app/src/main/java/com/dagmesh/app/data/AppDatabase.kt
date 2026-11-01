package com.dagmesh.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MessageEntity::class, PeerEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MeshDao

    companion object {
        /** Sürüm 1 → 2: medya alanları eklendi. Eski mesajlar korunur. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE messages ADD COLUMN fileName TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN mime TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN size INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN progress INTEGER NOT NULL DEFAULT 100")
                db.execSQL("ALTER TABLE messages ADD COLUMN localPath TEXT")
            }
        }

        /** Sürüm 2 → 3: yanıt (alıntı) ve emoji tepkileri. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyText TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyAuthor TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN myReaction TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN peerReaction TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dagmesh.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
