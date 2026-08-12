package com.vazheyar.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FlashcardEntity::class, ReviewLogEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flashcards(): FlashcardDao
    abstract fun reviewLogs(): ReviewLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE flashcards ADD COLUMN fsrsState TEXT NOT NULL DEFAULT 'LEARNING'")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN fsrsStep INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN fsrsDifficulty REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN fsrsStability REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN scheduledDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN reviewCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN enrichmentProvider TEXT")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN enrichmentModel TEXT")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN aiAttemptCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN lastAiAttemptAt INTEGER")
                db.execSQL("ALTER TABLE flashcards ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

                // Preserve useful scheduling information from the legacy scheduler.
                db.execSQL("UPDATE flashcards SET fsrsState = CASE WHEN repetition > 0 THEN 'REVIEW' ELSE 'LEARNING' END")
                db.execSQL("UPDATE flashcards SET fsrsDifficulty = CASE WHEN repetition > 0 THEN 5.0 ELSE 0.0 END")
                db.execSQL("UPDATE flashcards SET fsrsStability = CASE WHEN intervalDays > 0 THEN CAST(intervalDays AS REAL) ELSE 0.0 END")
                db.execSQL("UPDATE flashcards SET scheduledDays = intervalDays, reviewCount = repetition, updatedAt = createdAt")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashcards_nextReviewAt ON flashcards(nextReviewAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashcards_enrichmentStatus ON flashcards(enrichmentStatus)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS review_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        cardId INTEGER NOT NULL,
                        rating INTEGER NOT NULL,
                        reviewedAt INTEGER NOT NULL,
                        elapsedDays INTEGER NOT NULL,
                        scheduledDays INTEGER NOT NULL,
                        stabilityBefore REAL NOT NULL,
                        difficultyBefore REAL NOT NULL,
                        stabilityAfter REAL NOT NULL,
                        difficultyAfter REAL NOT NULL,
                        stateBefore TEXT NOT NULL,
                        stateAfter TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_logs_cardId ON review_logs(cardId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_logs_reviewedAt ON review_logs(reviewedAt)")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "vazheyar.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
        }
    }
}
