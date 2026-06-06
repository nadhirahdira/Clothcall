package com.clothcall.data.db;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AppDatabase_Impl extends AppDatabase {
  private volatile GarmentDao _garmentDao;

  private volatile CaregiverProfileDao _caregiverProfileDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(2) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `garments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `imagePath` TEXT NOT NULL, `baselinePath` TEXT, `dateAdded` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `caregiver_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `fadeThreshold` INTEGER NOT NULL, `isActive` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '7ae3465c2486df14ecbac63708506fe1')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `garments`");
        db.execSQL("DROP TABLE IF EXISTS `caregiver_profiles`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsGarments = new HashMap<String, TableInfo.Column>(5);
        _columnsGarments.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGarments.put("name", new TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGarments.put("imagePath", new TableInfo.Column("imagePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGarments.put("baselinePath", new TableInfo.Column("baselinePath", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGarments.put("dateAdded", new TableInfo.Column("dateAdded", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysGarments = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesGarments = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoGarments = new TableInfo("garments", _columnsGarments, _foreignKeysGarments, _indicesGarments);
        final TableInfo _existingGarments = TableInfo.read(db, "garments");
        if (!_infoGarments.equals(_existingGarments)) {
          return new RoomOpenHelper.ValidationResult(false, "garments(com.clothcall.data.db.Garment).\n"
                  + " Expected:\n" + _infoGarments + "\n"
                  + " Found:\n" + _existingGarments);
        }
        final HashMap<String, TableInfo.Column> _columnsCaregiverProfiles = new HashMap<String, TableInfo.Column>(4);
        _columnsCaregiverProfiles.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCaregiverProfiles.put("name", new TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCaregiverProfiles.put("fadeThreshold", new TableInfo.Column("fadeThreshold", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCaregiverProfiles.put("isActive", new TableInfo.Column("isActive", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysCaregiverProfiles = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesCaregiverProfiles = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoCaregiverProfiles = new TableInfo("caregiver_profiles", _columnsCaregiverProfiles, _foreignKeysCaregiverProfiles, _indicesCaregiverProfiles);
        final TableInfo _existingCaregiverProfiles = TableInfo.read(db, "caregiver_profiles");
        if (!_infoCaregiverProfiles.equals(_existingCaregiverProfiles)) {
          return new RoomOpenHelper.ValidationResult(false, "caregiver_profiles(com.clothcall.data.db.CaregiverProfile).\n"
                  + " Expected:\n" + _infoCaregiverProfiles + "\n"
                  + " Found:\n" + _existingCaregiverProfiles);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "7ae3465c2486df14ecbac63708506fe1", "ce40fa7fff6275018ebde51e234236cf");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "garments","caregiver_profiles");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `garments`");
      _db.execSQL("DELETE FROM `caregiver_profiles`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(GarmentDao.class, GarmentDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(CaregiverProfileDao.class, CaregiverProfileDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public GarmentDao garmentDao() {
    if (_garmentDao != null) {
      return _garmentDao;
    } else {
      synchronized(this) {
        if(_garmentDao == null) {
          _garmentDao = new GarmentDao_Impl(this);
        }
        return _garmentDao;
      }
    }
  }

  @Override
  public CaregiverProfileDao caregiverProfileDao() {
    if (_caregiverProfileDao != null) {
      return _caregiverProfileDao;
    } else {
      synchronized(this) {
        if(_caregiverProfileDao == null) {
          _caregiverProfileDao = new CaregiverProfileDao_Impl(this);
        }
        return _caregiverProfileDao;
      }
    }
  }
}
