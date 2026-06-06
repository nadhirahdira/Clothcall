package com.clothcall.data.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class GarmentDao_Impl implements GarmentDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Garment> __insertionAdapterOfGarment;

  private final EntityDeletionOrUpdateAdapter<Garment> __deletionAdapterOfGarment;

  private final EntityDeletionOrUpdateAdapter<Garment> __updateAdapterOfGarment;

  private final SharedSQLiteStatement __preparedStmtOfUpdateGarmentPaths;

  public GarmentDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfGarment = new EntityInsertionAdapter<Garment>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `garments` (`id`,`name`,`imagePath`,`baselinePath`,`dateAdded`) VALUES (nullif(?, 0),?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Garment entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getImagePath());
        if (entity.getBaselinePath() == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.getBaselinePath());
        }
        statement.bindLong(5, entity.getDateAdded());
      }
    };
    this.__deletionAdapterOfGarment = new EntityDeletionOrUpdateAdapter<Garment>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `garments` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Garment entity) {
        statement.bindLong(1, entity.getId());
      }
    };
    this.__updateAdapterOfGarment = new EntityDeletionOrUpdateAdapter<Garment>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `garments` SET `id` = ?,`name` = ?,`imagePath` = ?,`baselinePath` = ?,`dateAdded` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Garment entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getImagePath());
        if (entity.getBaselinePath() == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.getBaselinePath());
        }
        statement.bindLong(5, entity.getDateAdded());
        statement.bindLong(6, entity.getId());
      }
    };
    this.__preparedStmtOfUpdateGarmentPaths = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE garments SET imagePath = ?, baselinePath = ? WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insertGarment(final Garment garment, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfGarment.insertAndReturnId(garment);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteGarment(final Garment garment, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfGarment.handle(garment);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateGarment(final Garment garment, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfGarment.handle(garment);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateGarmentPaths(final int id, final String imagePath, final String baselinePath,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateGarmentPaths.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, imagePath);
        _argIndex = 2;
        if (baselinePath == null) {
          _stmt.bindNull(_argIndex);
        } else {
          _stmt.bindString(_argIndex, baselinePath);
        }
        _argIndex = 3;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateGarmentPaths.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<Garment>> getAllGarments() {
    final String _sql = "SELECT * FROM garments ORDER BY dateAdded DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"garments"}, new Callable<List<Garment>>() {
      @Override
      @NonNull
      public List<Garment> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "imagePath");
          final int _cursorIndexOfBaselinePath = CursorUtil.getColumnIndexOrThrow(_cursor, "baselinePath");
          final int _cursorIndexOfDateAdded = CursorUtil.getColumnIndexOrThrow(_cursor, "dateAdded");
          final List<Garment> _result = new ArrayList<Garment>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Garment _item;
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpImagePath;
            _tmpImagePath = _cursor.getString(_cursorIndexOfImagePath);
            final String _tmpBaselinePath;
            if (_cursor.isNull(_cursorIndexOfBaselinePath)) {
              _tmpBaselinePath = null;
            } else {
              _tmpBaselinePath = _cursor.getString(_cursorIndexOfBaselinePath);
            }
            final long _tmpDateAdded;
            _tmpDateAdded = _cursor.getLong(_cursorIndexOfDateAdded);
            _item = new Garment(_tmpId,_tmpName,_tmpImagePath,_tmpBaselinePath,_tmpDateAdded);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getGarmentById(final int id, final Continuation<? super Garment> $completion) {
    final String _sql = "SELECT * FROM garments WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Garment>() {
      @Override
      @Nullable
      public Garment call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "imagePath");
          final int _cursorIndexOfBaselinePath = CursorUtil.getColumnIndexOrThrow(_cursor, "baselinePath");
          final int _cursorIndexOfDateAdded = CursorUtil.getColumnIndexOrThrow(_cursor, "dateAdded");
          final Garment _result;
          if (_cursor.moveToFirst()) {
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpImagePath;
            _tmpImagePath = _cursor.getString(_cursorIndexOfImagePath);
            final String _tmpBaselinePath;
            if (_cursor.isNull(_cursorIndexOfBaselinePath)) {
              _tmpBaselinePath = null;
            } else {
              _tmpBaselinePath = _cursor.getString(_cursorIndexOfBaselinePath);
            }
            final long _tmpDateAdded;
            _tmpDateAdded = _cursor.getLong(_cursorIndexOfDateAdded);
            _result = new Garment(_tmpId,_tmpName,_tmpImagePath,_tmpBaselinePath,_tmpDateAdded);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
