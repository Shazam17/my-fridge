package com.example.myapplication.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 1 → 2: добавлена локальная таблица sync_state (SYNC_SPEC.md, раздел 3). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_state` (
                `id` INTEGER NOT NULL,
                `remoteEtag` TEXT,
                `lastSyncAt` INTEGER,
                `deviceId` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}

/** 2 → 3: журнал событий и учёт сегментов (EVENT_SOURCING_SPEC.md, раздел 5). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `event` (
                `eventId` TEXT NOT NULL,
                `deviceId` TEXT NOT NULL,
                `seq` INTEGER NOT NULL,
                `hlcWall` INTEGER NOT NULL,
                `hlcCounter` INTEGER NOT NULL,
                `author` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `aggregate` TEXT NOT NULL,
                `aggregateId` TEXT NOT NULL,
                `payload` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `origin` TEXT NOT NULL,
                `pushed` INTEGER NOT NULL,
                `appliedAt` INTEGER NOT NULL,
                PRIMARY KEY(`eventId`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_event_deviceId_seq` ON `event` (`deviceId`, `seq`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_pushed` ON `event` (`pushed`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `remote_segment` (
                `key` TEXT NOT NULL,
                `size` INTEGER NOT NULL,
                `appliedAt` INTEGER NOT NULL,
                PRIMARY KEY(`key`)
            )
            """.trimIndent(),
        )
        db.execSQL("ALTER TABLE `sync_state` ADD COLUMN `hlcWall` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `sync_state` ADD COLUMN `hlcCounter` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `sync_state` ADD COLUMN `lastSeq` INTEGER NOT NULL DEFAULT 0")
    }
}

/** 3 → 4: пометка предмета «использовать в первую очередь» (isPriority). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `product` ADD COLUMN `isPriority` INTEGER NOT NULL DEFAULT 0")
    }
}
