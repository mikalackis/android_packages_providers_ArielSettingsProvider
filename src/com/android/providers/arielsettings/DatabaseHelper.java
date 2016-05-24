/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.arielsettings;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.media.AudioSystem;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.ArielSettings;
import android.provider.ArielSettings.Global;
import android.provider.ArielSettings.Secure;
import android.text.TextUtils;
import android.util.Log;

import com.android.ims.ImsConfig;
import com.android.internal.content.PackageHelper;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Legacy settings database helper class for {@link SettingsProvider}.
 *
 * IMPORTANT: Do not add any more upgrade steps here as the global,
 * secure, and system settings are no longer stored in a database
 * but are kept in memory and persisted to XML.
 *
 * See: SettingsProvider.UpgradeController#onUpgradeLocked
 *
 * @deprecated The implementation is frozen.  Do not add any new code to this class!
 */
@Deprecated
class DatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "ArielSettingsProvider";
    private static final String DATABASE_NAME = "ariel_settings.db";

    // Please, please please. If you update the database version, check to make sure the
    // database gets upgraded properly. At a minimum, please confirm that 'upgradeVersion'
    // is properly propagated through your change.  Not doing so will result in a loss of user
    // ArielSettings.
    private static final int DATABASE_VERSION = 1;

    private Context mContext;
    private int mUserHandle;

    private static final HashSet<String> mValidTables = new HashSet<String>();

    private static final String DATABASE_JOURNAL_SUFFIX = "-journal";
    private static final String DATABASE_BACKUP_SUFFIX = "-backup";

    private static final String TABLE_SYSTEM = "system";
    private static final String TABLE_SECURE = "secure";
    private static final String TABLE_GLOBAL = "global";

    static {
        mValidTables.add(TABLE_SYSTEM);
        mValidTables.add(TABLE_SECURE);
        mValidTables.add(TABLE_GLOBAL);
    }

    static String dbNameForUser(final int userHandle) {
        // The owner gets the unadorned db name;
        if (userHandle == UserHandle.USER_OWNER) {
            return DATABASE_NAME;
        } else {
            // Place the database in the user-specific data tree so that it's
            // cleaned up automatically when the user is deleted.
            File databaseFile = new File(
                    Environment.getUserSystemDirectory(userHandle), DATABASE_NAME);
            return databaseFile.getPath();
        }
    }

    public DatabaseHelper(Context context, int userHandle) {
        super(context, dbNameForUser(userHandle), null, DATABASE_VERSION);
        mContext = context;
        mUserHandle = userHandle;
    }

    public static boolean isValidTable(String name) {
        return mValidTables.contains(name);
    }

    public void dropDatabase() {
        close();
        File databaseFile = mContext.getDatabasePath(getDatabaseName());
        if (databaseFile.exists()) {
            databaseFile.delete();
        }
        File databaseJournalFile = mContext.getDatabasePath(getDatabaseName()
                + DATABASE_JOURNAL_SUFFIX);
        if (databaseJournalFile.exists()) {
            databaseJournalFile.delete();
        }
    }

    public void backupDatabase() {
        close();
        File databaseFile = mContext.getDatabasePath(getDatabaseName());
        if (!databaseFile.exists()) {
            return;
        }
        File backupFile = mContext.getDatabasePath(getDatabaseName()
                + DATABASE_BACKUP_SUFFIX);
        if (backupFile.exists()) {
            return;
        }
        databaseFile.renameTo(backupFile);
    }

    private void createSecureTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE secure (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT UNIQUE ON CONFLICT REPLACE," +
                "value TEXT" +
                ");");
        db.execSQL("CREATE INDEX secureIndex1 ON secure (name);");
    }

    private void createGlobalTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE global (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT UNIQUE ON CONFLICT REPLACE," +
                "value TEXT" +
                ");");
        db.execSQL("CREATE INDEX globalIndex1 ON global (name);");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE system (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT UNIQUE ON CONFLICT REPLACE," +
                    "value TEXT" +
                    ");");
        db.execSQL("CREATE INDEX systemIndex1 ON system (name);");

        createSecureTable(db);

        // Only create the global table for the singleton 'owner' user
        if (mUserHandle == UserHandle.USER_OWNER) {
            createGlobalTable(db);
        }

        db.execSQL("CREATE TABLE bluetooth_devices (" +
                    "_id INTEGER PRIMARY KEY," +
                    "name TEXT," +
                    "addr TEXT," +
                    "channel INTEGER," +
                    "type INTEGER" +
                    ");");

        db.execSQL("CREATE TABLE bookmarks (" +
                    "_id INTEGER PRIMARY KEY," +
                    "title TEXT," +
                    "folder TEXT," +
                    "intent TEXT," +
                    "shortcut INTEGER," +
                    "ordering INTEGER" +
                    ");");

        db.execSQL("CREATE INDEX bookmarksIndex1 ON bookmarks (folder);");
        db.execSQL("CREATE INDEX bookmarksIndex2 ON bookmarks (shortcut);");

        // Populate bookmarks table with initial bookmarks
        boolean onlyCore = false;
        try {
            onlyCore = IPackageManager.Stub.asInterface(ServiceManager.getService(
                    "package")).isOnlyCoreApps();
        } catch (RemoteException e) {
        }

        // Load inital settings values
        loadSettings(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
        Log.w(TAG, "Upgrading settings database from version " + oldVersion + " to "
                + currentVersion);

        int upgradeVersion = oldVersion;

        // Pattern for upgrade blocks:
        //
        //    if (upgradeVersion == [the DATABASE_VERSION you set] - 1) {
        //        .. your upgrade logic..
        //        upgradeVersion = [the DATABASE_VERSION you set]
        //    }

//        if (upgradeVersion == 36) {
//           // This upgrade adds the STREAM_SYSTEM_ENFORCED type to the list of
//            // types affected by ringer modes (silent, vibrate, etc.)
//            db.beginTransaction();
//            try {
//                db.execSQL("DELETE FROM system WHERE name='"
//                        + ArielSettings.System.MODE_RINGER_STREAMS_AFFECTED + "'");
//                int newValue = (1 << AudioManager.STREAM_RING)
//                        | (1 << AudioManager.STREAM_NOTIFICATION)
//                        | (1 << AudioManager.STREAM_SYSTEM)
//                        | (1 << AudioManager.STREAM_SYSTEM_ENFORCED);
//                db.execSQL("INSERT INTO system ('name', 'value') values ('"
//                        + ArielSettings.System.MODE_RINGER_STREAMS_AFFECTED + "', '"
//                        + String.valueOf(newValue) + "')");
//                db.setTransactionSuccessful();
//            } finally {
//                db.endTransaction();
//            }
//            upgradeVersion = 37;
//        }

        /*
         * IMPORTANT: Do not add any more upgrade steps here as the global,
         * secure, and system settings are no longer stored in a database
         * but are kept in memory and persisted to XML.
         *
         * See: SettingsProvider.UpgradeController#onUpgradeLocked
         */

        if (upgradeVersion != currentVersion) {
            recreateDatabase(db, oldVersion, upgradeVersion, currentVersion);
        }
    }

    public void recreateDatabase(SQLiteDatabase db, int oldVersion,
            int upgradeVersion, int currentVersion) {
        db.execSQL("DROP TABLE IF EXISTS global");
        db.execSQL("DROP TABLE IF EXISTS globalIndex1");
        db.execSQL("DROP TABLE IF EXISTS system");
        db.execSQL("DROP INDEX IF EXISTS systemIndex1");
        db.execSQL("DROP TABLE IF EXISTS secure");
        db.execSQL("DROP INDEX IF EXISTS secureIndex1");
        db.execSQL("DROP TABLE IF EXISTS gservices");
        db.execSQL("DROP INDEX IF EXISTS gservicesIndex1");
        db.execSQL("DROP TABLE IF EXISTS bluetooth_devices");
        db.execSQL("DROP TABLE IF EXISTS bookmarks");
        db.execSQL("DROP INDEX IF EXISTS bookmarksIndex1");
        db.execSQL("DROP INDEX IF EXISTS bookmarksIndex2");
        db.execSQL("DROP TABLE IF EXISTS favorites");

        onCreate(db);

        // Added for diagnosing ArielSettings.db wipes after the fact
        String wipeReason = oldVersion + "/" + upgradeVersion + "/" + currentVersion;
        db.execSQL("INSERT INTO secure(name,value) values('" +
                "wiped_db_reason" + "','" + wipeReason + "');");
    }

    private String[] setToStringArray(Set<String> set) {
        String[] array = new String[set.size()];
        return set.toArray(array);
    }

    private void moveSettingsToNewTable(SQLiteDatabase db,
            String sourceTable, String destTable,
            String[] settingsToMove, boolean doIgnore) {
        // Copy settings values from the source table to the dest, and remove from the source
        SQLiteStatement insertStmt = null;
        SQLiteStatement deleteStmt = null;

        db.beginTransaction();
        try {
            insertStmt = db.compileStatement("INSERT "
                    + (doIgnore ? " OR IGNORE " : "")
                    + " INTO " + destTable + " (name,value) SELECT name,value FROM "
                    + sourceTable + " WHERE name=?");
            deleteStmt = db.compileStatement("DELETE FROM " + sourceTable + " WHERE name=?");

            for (String setting : settingsToMove) {
                insertStmt.bindString(1, setting);
                insertStmt.execute();

                deleteStmt.bindString(1, setting);
                deleteStmt.execute();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            if (insertStmt != null) {
                insertStmt.close();
            }
            if (deleteStmt != null) {
                deleteStmt.close();
            }
        }
    }

    /**
     * Move any settings with the given prefixes from the source table to the
     * destination table.
     */
    private void movePrefixedSettingsToNewTable(
            SQLiteDatabase db, String sourceTable, String destTable, String[] prefixesToMove) {
        SQLiteStatement insertStmt = null;
        SQLiteStatement deleteStmt = null;

        db.beginTransaction();
        try {
            insertStmt = db.compileStatement("INSERT INTO " + destTable
                    + " (name,value) SELECT name,value FROM " + sourceTable
                    + " WHERE substr(name,0,?)=?");
            deleteStmt = db.compileStatement(
                    "DELETE FROM " + sourceTable + " WHERE substr(name,0,?)=?");

            for (String prefix : prefixesToMove) {
                insertStmt.bindLong(1, prefix.length() + 1);
                insertStmt.bindString(2, prefix);
                insertStmt.execute();

                deleteStmt.bindLong(1, prefix.length() + 1);
                deleteStmt.bindString(2, prefix);
                deleteStmt.execute();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            if (insertStmt != null) {
                insertStmt.close();
            }
            if (deleteStmt != null) {
                deleteStmt.close();
            }
        }
    }

//    private void upgradeScreenTimeoutFromNever(SQLiteDatabase db) {
//        // See if the timeout is -1 (for "Never").
//        Cursor c = db.query(TABLE_SYSTEM, new String[] { "_id", "value" }, "name=? AND value=?",
//                new String[] { ArielSettings.System.SCREEN_OFF_TIMEOUT, "-1" },
//                null, null, null);
//
//        SQLiteStatement stmt = null;
//        if (c.getCount() > 0) {
//            c.close();
//            try {
//                stmt = db.compileStatement("INSERT OR REPLACE INTO system(name,value)"
//                        + " VALUES(?,?);");
//
//                // Set the timeout to 30 minutes in milliseconds
//                loadSetting(stmt, ArielSettings.System.SCREEN_OFF_TIMEOUT,
//                        Integer.toString(30 * 60 * 1000));
//            } finally {
//                if (stmt != null) stmt.close();
//            }
//        } else {
//            c.close();
//        }
//    }

    private void loadSettings(SQLiteDatabase db) {
        loadSystemSettings(db);
        loadSecureSettings(db);
        // The global table only exists for the 'owner' user
        if (mUserHandle == UserHandle.USER_OWNER) {
            loadGlobalSettings(db);
        }
    }

    private void loadSystemSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                    + " VALUES(?,?);");

//            loadBooleanSetting(stmt, ArielSettings.System.DIM_SCREEN,
//                    R.bool.def_dim_screen);
//            loadIntegerSetting(stmt, ArielSettings.System.SCREEN_OFF_TIMEOUT,
//                    R.integer.def_screen_off_timeout);
//
//            // Set default cdma DTMF type
//            loadSetting(stmt, ArielSettings.System.DTMF_TONE_TYPE_WHEN_DIALING, 0);

            /*
             * IMPORTANT: Do not add any more upgrade steps here as the global,
             * secure, and system settings are no longer stored in a database
             * but are kept in memory and persisted to XML.
             *
             * See: SettingsProvider.UpgradeController#onUpgradeLocked
             */
        } finally {
            if (stmt != null) stmt.close();
        }
    }

    private void loadSecureSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                    + " VALUES(?,?);");

//            loadStringSetting(stmt, ArielSettings.Secure.LOCATION_PROVIDERS_ALLOWED,
//                    R.string.def_location_providers_allowed);

            /*
             * IMPORTANT: Do not add any more upgrade steps here as the global,
             * secure, and system settings are no longer stored in a database
             * but are kept in memory and persisted to XML.
             *
             * See: SettingsProvider.UpgradeController#onUpgradeLocked
             */
        } finally {
            if (stmt != null) stmt.close();
        }
    }

    private void loadGlobalSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = null;
        try {
            stmt = db.compileStatement("INSERT OR IGNORE INTO global(name,value)"
                    + " VALUES(?,?);");

//            // --- Previously in 'system'
//            loadBooleanSetting(stmt, ArielSettings.Global.AIRPLANE_MODE_ON,
//                    R.bool.def_airplane_mode_on);

            /*
             * IMPORTANT: Do not add any more upgrade steps here as the global,
             * secure, and system settings are no longer stored in a database
             * but are kept in memory and persisted to XML.
             *
             * See: SettingsProvider.UpgradeController#onUpgradeLocked
             */
        } finally {
            if (stmt != null) stmt.close();
        }
    }

    private void loadSetting(SQLiteStatement stmt, String key, Object value) {
        stmt.bindString(1, key);
        stmt.bindString(2, value.toString());
        stmt.execute();
    }

    private void loadStringSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key, mContext.getResources().getString(resid));
    }

    private void loadBooleanSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key,
                mContext.getResources().getBoolean(resid) ? "1" : "0");
    }

    private void loadIntegerSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key,
                Integer.toString(mContext.getResources().getInteger(resid)));
    }

    private void loadFractionSetting(SQLiteStatement stmt, String key, int resid, int base) {
        loadSetting(stmt, key,
                Float.toString(mContext.getResources().getFraction(resid, base, base)));
    }

    private int getIntValueFromSystem(SQLiteDatabase db, String name, int defaultValue) {
        return getIntValueFromTable(db, TABLE_SYSTEM, name, defaultValue);
    }

    private int getIntValueFromTable(SQLiteDatabase db, String table, String name,
            int defaultValue) {
        String value = getStringValueFromTable(db, table, name, null);
        return (value != null) ? Integer.parseInt(value) : defaultValue;
    }

    private String getStringValueFromTable(SQLiteDatabase db, String table, String name,
            String defaultValue) {
        Cursor c = null;
        try {
            c = db.query(table, new String[] { ArielSettings.System.VALUE }, "name='" + name + "'",
                    null, null, null, null);
            if (c != null && c.moveToFirst()) {
                String val = c.getString(0);
                return val == null ? defaultValue : val;
            }
        } finally {
            if (c != null) c.close();
        }
        return defaultValue;
    }

}
