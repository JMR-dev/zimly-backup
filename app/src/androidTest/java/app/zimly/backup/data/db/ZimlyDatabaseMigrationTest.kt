package app.zimly.backup.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every installed copy of the app has a version 7 database. Version 8 adds the sync_retry and
 * download_in_progress tables through an automatic migration; if it failed, the app would crash
 * on open for every existing user. Room checks the migrated schema against the exported one.
 */
@RunWith(AndroidJUnit4::class)
class ZimlyDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ZimlyDatabase::class.java)

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(DB)
    }

    @Test
    fun version7UpgradesToVersion8KeepingItsRemotes() {
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO Remote (uid, name, url, key, secret, bucket, region, virtual_hosted_style, content_type, content_uri, direction) " +
                    "VALUES (1, 'Photos', 'https://s3.example.com', 'key', 'secret', 'bucket', NULL, 0, 'MEDIA', 'Camera', 'UPLOAD')"
            )
        }

        helper.runMigrationsAndValidate(DB, 8, true).use { db ->
            db.query("SELECT name FROM Remote WHERE uid = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Photos", cursor.getString(0))
            }
        }
    }

    /** Room itself opens the migrated file, as the app does, with every automatic migration. */
    @Test
    fun theAppOpensAMigratedVersion7Database() {
        helper.createDatabase(DB, 7).close()

        val database = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), ZimlyDatabase::class.java, DB).build()
        try {
            database.openHelper.writableDatabase
            assertEquals(8, database.openHelper.readableDatabase.version)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
