/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.andlib.data;

import android.content.ContentValues;
import android.database.Cursor;

import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DAO for reading data from an instance of {@link AbstractDatabase}. If you
 * are writing an add-on for Astrid, you probably want to be using a subclass
 * of ContentResolverDao instead.
 *
 * @author Tim Su <tim@todoroo.com>
 *
 */
public class DatabaseDao<TYPE extends AbstractModel> {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDao.class);

    private final Class<TYPE> modelClass;

    private Table table;

    private AbstractDatabase database;

    public DatabaseDao(Class<TYPE> modelClass) {
        this.modelClass = modelClass;
        try {
            modelClass.getConstructor(); // check for default constructor
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /** Gets table associated with this DAO */
    public Table getTable() {
        return table;
    }

    /**
     * Sets database accessed by this DAO. Used for dependency-injected
     * initialization by child classes and unit tests
     */
    public void setDatabase(AbstractDatabase database) {
        if(database == this.database) {
            return;
        }
        this.database = database;
        table = database.getTable(modelClass);
    }

    // --- listeners

    public interface ModelUpdateListener<MTYPE> {
        public void onModelUpdated(MTYPE model);
    }

    private final ArrayList<ModelUpdateListener<TYPE>> listeners =
        new ArrayList<>();

    public void addListener(ModelUpdateListener<TYPE> listener) {
        listeners.add(listener);
    }

    protected void onModelUpdated(TYPE model) {
        TYPE modelCopy = (TYPE) model.clone();
        for(ModelUpdateListener<TYPE> listener : listeners) {
            listener.onModelUpdated(modelCopy);
        }
    }

    // --- dao methods

    public List<TYPE> toList(Query query) {
        final List<TYPE> result = new ArrayList<>();
        query(new Callback<TYPE>() {
            @Override
            public void apply(TYPE entry) {
                result.add(entry);
            }
        }, query);
        return result;
    }

    public void query(Callback<TYPE> callback, Query query) {
        TodorooCursor<TYPE> cursor = query(query);
        try {
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                callback.apply(fromCursor(cursor));
            }
        } finally {
            cursor.close();
        }
    }

    public TYPE getFirst(Query query) {
        TodorooCursor<TYPE> cursor = query(query);
        try {
            return cursor.moveToFirst() ? fromCursor(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    private TYPE fromCursor(TodorooCursor<TYPE> cursor) {
        TYPE instance;
        try {
            instance = modelClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        instance.readPropertiesFromCursor(cursor);
        return instance;
    }

    /**
     * Construct a query with SQL DSL objects
     */
    public TodorooCursor<TYPE> query(Query query) {
        query.from(table);
        Cursor cursor = database.rawQuery(query.toString());
        return new TodorooCursor<>(cursor, query.getFields());
    }

    /**
     * Returns object corresponding to the given identifier
     * @param properties
     *            properties to read
     * @param id
     *            id of item
     * @return null if no item found
     */
    public TYPE fetch(long id, Property<?>... properties) {
        TodorooCursor<TYPE> cursor = fetchItem(id, properties);
        return returnFetchResult(cursor);
    }

    protected TYPE returnFetchResult(TodorooCursor<TYPE> cursor) {
        try {
            if (cursor.getCount() == 0) {
                return null;
            }
            return fromCursor(cursor);
        } finally {
            cursor.close();
        }
    }

    /**
     * Delete the given id
     * @return true if delete was successful
     */
    public boolean delete(long id) {
        return database.delete(table.name,
                AbstractModel.ID_PROPERTY.eq(id).toString(), null) > 0;
    }

    /**
     * Delete all matching a clause
     * @param where predicate for deletion
     * @return # of deleted items
     */
    public int deleteWhere(Criterion where) {
        log.debug("deleteWhere({})", where);
        return database.delete(table.name,
                where.toString(), null);
    }

    /**
     * Update all matching a clause to have the values set on template object.
     * <p>
     * Example (updates "joe" => "bob" in metadata value1):
     * {code}
     * Metadata item = new Metadata();
     * item.setVALUE1("bob");
     * update(item, Metadata.VALUE1.eq("joe"));
     * {code}
     * @param where sql criteria
     * @param template set fields on this object in order to set them in the db.
     * @return # of updated items
     */
    public int update(Criterion where, TYPE template) {
        return database.update(table.name, template.getSetValues(),
                where.toString());
    }

    /**
     * Save the given object to the database. Creates a new object if
     * model id property has not been set
     *
     * @return true on success.
     */
    public boolean persist(TYPE item) {
        if (item.getId() == AbstractModel.NO_ID) {
            return createNew(item);
        } else {
            ContentValues values = item.getSetValues();

            if (values.size() == 0) // nothing changed
            {
                return true;
            }

            return saveExisting(item);
        }
    }

    private interface DatabaseChangeOp {
        public boolean makeChange();
    }

    private boolean insertOrUpdateAndRecordChanges(TYPE item, DatabaseChangeOp op) {
        final AtomicBoolean result = new AtomicBoolean(false);
        synchronized(database) {
            result.set(op.makeChange());
            if (result.get()) {
                onModelUpdated(item);
                item.markSaved();
            }
        }
        return result.get();
    }

    /**
     * Creates the given item.
     * @param item
     *            item model
     * @return returns true on success.
     */
    public boolean createNew(final TYPE item) {
        item.clearValue(AbstractModel.ID_PROPERTY);

        DatabaseChangeOp insert = new DatabaseChangeOp() {
            @Override
            public boolean makeChange() {
                long newRow = database.insert(table.name,
                        AbstractModel.ID_PROPERTY.name, item.getMergedValues());
                boolean result = newRow >= 0;
                if (result) {
                    item.setId(newRow);
                }
                return result;
            }
        };
        return insertOrUpdateAndRecordChanges(item, insert);
    }

    /**
     * Saves the given item. Will not create a new item!
     * @param item
     *            item model
     * @return returns true on success.
     */
    public boolean saveExisting(final TYPE item) {
        final ContentValues values = item.getSetValues();
        if(values == null || values.size() == 0) // nothing changed
        {
            return true;
        }
        DatabaseChangeOp update = new DatabaseChangeOp() {
            @Override
            public boolean makeChange() {
                return database.update(table.name, values,
                        AbstractModel.ID_PROPERTY.eq(item.getId()).toString()) > 0;
            }
        };
        return insertOrUpdateAndRecordChanges(item, update);
    }

    // --- helper methods

    /**
     * Returns cursor to object corresponding to the given identifier
     * @param properties
     *            properties to read
     * @param id
     *            id of item
     */
    protected TodorooCursor<TYPE> fetchItem(long id, Property<?>... properties) {
        TodorooCursor<TYPE> cursor = query(
                Query.select(properties).where(AbstractModel.ID_PROPERTY.eq(id)));
        cursor.moveToFirst();
        return new TodorooCursor<>(cursor, properties);
    }

    public int count(Query query) {
        TodorooCursor<TYPE> cursor = query(query);
        try {
            return cursor.getCount();
        } finally {
            cursor.close();
        }
    }
}
