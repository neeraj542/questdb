/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2018 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.griffin.engine.groupby;

import com.questdb.cairo.*;
import com.questdb.cairo.map.Map;
import com.questdb.cairo.map.MapFactory;
import com.questdb.cairo.map.MapKey;
import com.questdb.cairo.map.MapValue;
import com.questdb.cairo.sql.*;
import com.questdb.griffin.FunctionParser;
import com.questdb.griffin.SqlException;
import com.questdb.griffin.SqlExecutionContext;
import com.questdb.griffin.engine.functions.GroupByFunction;
import com.questdb.griffin.engine.functions.bind.BindVariableService;
import com.questdb.griffin.engine.functions.columns.TimestampColumn;
import com.questdb.griffin.engine.table.EmptyTableRecordCursor;
import com.questdb.griffin.model.QueryModel;
import com.questdb.std.*;
import org.jetbrains.annotations.NotNull;

public class SampleByInterpolateRecordCursorFactory implements RecordCursorFactory {

    protected final RecordCursorFactory base;
    protected final Map recordKeyMap;
    private final Map dataMap;
    private final SampleByInterpolatedRecordCursor cursor;
    private final ObjList<Function> recordFunctions;
    private final ObjList<GroupByFunction> groupByFunctions;
    private final ObjList<InterpolationUtil.StoreYFunction> storeYFunctions;
    private final ObjList<InterpolationUtil.InterpolatorFunction> interpolatorFunctions;
    private final RecordSink mapSink;
    // this sink is used to copy recordKeyMap keys to dataMap
    private final RecordSink mapSink2;
    private final RecordMetadata metadata;
    private final int timestampIndex;
    private final TimestampSampler sampler;
    private final int yDataSize;
    private long yData;

    public SampleByInterpolateRecordCursorFactory(
            CairoConfiguration configuration,
            RecordCursorFactory base,
            @NotNull TimestampSampler timestampSampler,
            @Transient @NotNull QueryModel model,
            @Transient @NotNull ListColumnFilter listColumnFilter,
            @Transient @NotNull FunctionParser functionParser,
            @Transient @NotNull SqlExecutionContext executionContext,
            @Transient @NotNull BytecodeAssembler asm,
            @Transient @NotNull ArrayColumnTypes keyTypes,
            @Transient @NotNull ArrayColumnTypes valueTypes,
            @Transient @NotNull EntityColumnFilter entityColumnFilter
    ) throws SqlException {
        final int columnCount = model.getColumns().size();
        final RecordMetadata metadata = base.getMetadata();
        this.groupByFunctions = new ObjList<>(columnCount);
        valueTypes.add(ColumnType.BYTE); // gap flag

        AbstractSampleByRecordCursorFactory.prepareGroupByFunctions(
                model,
                metadata,
                functionParser,
                executionContext,
                groupByFunctions,
                valueTypes
        );

        this.recordFunctions = new ObjList<>(columnCount);
        final GenericRecordMetadata groupByMetadata = new GenericRecordMetadata();
        final IntIntHashMap symbolTableIndex = new IntIntHashMap();

        AbstractSampleByRecordCursorFactory.prepareGroupByRecordFunctions(
                model,
                metadata,
                listColumnFilter,
                groupByFunctions,
                recordFunctions,
                groupByMetadata,
                keyTypes,
                valueTypes,
                symbolTableIndex
        );

        this.storeYFunctions = new ObjList<>(columnCount);
        this.interpolatorFunctions = new ObjList<>(columnCount);

        // create timestamp column
        TimestampColumn timestampColumn = new TimestampColumn(0, valueTypes.getColumnCount() + keyTypes.getColumnCount());
        for (int i = 0, n = recordFunctions.size(); i < n; i++) {
            if (recordFunctions.getQuick(i) == null) {
                recordFunctions.setQuick(i, timestampColumn);
            }
        }

        for (int i = 0, n = groupByFunctions.size(); i < n; i++) {
            GroupByFunction function = groupByFunctions.getQuick(i);
            switch (function.getType()) {
                case ColumnType.BYTE:
                    storeYFunctions.add(InterpolationUtil.STORE_Y_BYTE);
                    interpolatorFunctions.add(InterpolationUtil.INTERPOLATE_BYTE);
                    break;
                case ColumnType.SHORT:
                    storeYFunctions.add(InterpolationUtil.STORE_Y_SHORT);
                    interpolatorFunctions.add(InterpolationUtil.INTERPOLATE_SHORT);
                    break;
                case ColumnType.INT:
                    storeYFunctions.add(InterpolationUtil.STORE_Y_INT);
                    interpolatorFunctions.add(InterpolationUtil.INTERPOLATE_INT);
                    break;
                case ColumnType.LONG:
                    storeYFunctions.add(InterpolationUtil.STORE_Y_LONG);
                    interpolatorFunctions.add(InterpolationUtil.INTERPOLATE_LONG);
                    break;
                case ColumnType.DOUBLE:
                    storeYFunctions.add(InterpolationUtil.STORE_Y_DOUBLE);
                    interpolatorFunctions.add(InterpolationUtil.INTERPOLATE_DOUBLE);
                    break;
                case ColumnType.FLOAT:
                    storeYFunctions.add(InterpolationUtil.STORE_Y_FLOAT);
                    interpolatorFunctions.add(InterpolationUtil.INTERPOLATE_FLOAT);
                    break;
                case ColumnType.DATE:
                    storeYFunctions.add(InterpolationUtil.STORE_Y_DATE);
                    interpolatorFunctions.add(InterpolationUtil.INTERPOLATE_DATE);
                    break;
                case ColumnType.TIMESTAMP:
                    storeYFunctions.add(InterpolationUtil.STORE_Y_TIMESTAMP);
                    interpolatorFunctions.add(InterpolationUtil.INTERPOLATE_TIMESTAMP);
                    break;
                default:
                    throw new UnsupportedOperationException();
            }
        }

        this.timestampIndex = metadata.getTimestampIndex();
        this.yDataSize = groupByFunctions.size() * 16;
        this.yData = Unsafe.malloc(yDataSize);

        // sink will be storing record columns to map key
        this.mapSink = RecordSinkFactory.getInstance(asm, metadata, listColumnFilter, false);
        entityColumnFilter.of(keyTypes.getColumnCount());
        this.mapSink2 = RecordSinkFactory.getInstance(asm, keyTypes, entityColumnFilter, false);

        // this is the map itself, which we must not forget to free when factory closes
        this.recordKeyMap = MapFactory.createMap(configuration, keyTypes);

        // data map will contain rounded timestamp value as last key column
        keyTypes.add(ColumnType.TIMESTAMP);

        this.dataMap = MapFactory.createMap(configuration, keyTypes, valueTypes);
        this.base = base;
        this.metadata = groupByMetadata;
        this.sampler = timestampSampler;
        this.cursor = new SampleByInterpolatedRecordCursor(recordFunctions, symbolTableIndex);
    }

    @Override
    public void close() {
        // todo: test that functions are indeed being freed
        for (int i = 0, n = recordFunctions.size(); i < n; i++) {
            recordFunctions.getQuick(i).close();
        }
        recordKeyMap.close();
        dataMap.close();
        freeYData();
    }

    @Override
    public RecordMetadata getMetadata() {
        return metadata;
    }

    @Override
    public RecordCursor getCursor(BindVariableService bindVariableService) {
        recordKeyMap.clear();
        dataMap.clear();
        final RecordCursor baseCursor = base.getCursor(bindVariableService);
        try {

            // Collect map of unique key values.
            // using this values we will fill gaps in main
            // data before jumping to another timestamp.
            // This will allow to maintain chronological order of
            // main data map.
            //
            // At the same time check if cursor has data
            while (baseCursor.hasNext()) {
                final Record record = baseCursor.next();
                final MapKey key = recordKeyMap.withKey();
                mapSink.copy(record, key);
                key.createValue();
            }

            // no data, nothing to do
            if (recordKeyMap.size() == 0) {
                baseCursor.close();
                return EmptyTableRecordCursor.INSTANCE;
            }

            // topTop() is guaranteeing that we get
            // the same data as previous while() loop
            // there is no data
            baseCursor.toTop();

            // Evaluate group-by functions.
            // On every change of timestamp sample value we
            // check group for gaps and fill them with placeholder
            // entries. Values for these entries will be interpolated later


            // we have data in cursor, so we can grab first value
            final boolean good = baseCursor.hasNext();
            assert good;
            Record record = baseCursor.next();
            long prevSample = sampler.round(record.getTimestamp(timestampIndex));
            long loSample = prevSample; // the lowest timestamp value
            long hiSample;

            final int n = groupByFunctions.size();
            do {
                // this seems inefficient, but we only double-sample
                // very first record and nothing else
                long sample = sampler.round(record.getTimestamp(timestampIndex));
                if (sample != prevSample) {
                    // before we continue with next interval
                    // we need to fill gaps in current interval
                    // we will go over unique keys and attempt to
                    // find them in data map with current timestamp

                    fillGaps(prevSample, sample);
                    prevSample = sample;
                }

                // same data group - evaluate group-by functions
                MapKey key = dataMap.withKey();
                mapSink.copy(record, key);
                key.putLong(sample);

                MapValue value = key.createValue();
                if (value.isNew()) {
                    value.putByte(0, (byte) 0); // not a gap
                    for (int i = 0; i < n; i++) {
                        groupByFunctions.getQuick(i).computeFirst(value, record);
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        groupByFunctions.getQuick(i).computeNext(value, record);
                    }
                }

                if (baseCursor.hasNext()) {
                    record = baseCursor.next();
                } else {
                    hiSample = sampler.nextTimestamp(prevSample);
                    break;
                }
            } while (true);

            // fill gaps if any at end of base cursor
            fillGaps(prevSample, hiSample);

            // find gaps by checking each of the unique keys against every sample
            long sample;
            for (sample = prevSample = loSample; sample < hiSample; prevSample = sample, sample = sampler.nextTimestamp(sample)) {
                final RecordCursor mapCursor = recordKeyMap.getCursor();
                while (mapCursor.hasNext()) {
                    final Record mapRecord = mapCursor.next();
                    // locate first gap
                    MapValue value = findDataMapValue(mapRecord, sample);
                    if (value.getByte(0) == 1) {
                        // gap is at 'sample', so potential X-value is at 'prevSample'
                        // now we need to find Y-value
                        long current = sample;

                        while (true) {
                            // to timestamp after 'sample' to begin with
                            long x2 = sampler.nextTimestamp(current);
                            // is this timestamp withing range?
                            if (x2 < hiSample) {
                                value = findDataMapValue(mapRecord, x2);
                                if (value.getByte(0) == 1) { // gap
                                    current = x2;
                                } else {
                                    // got something
                                    // Y-value is at 'x2', which is on first iteration
                                    // is 'sample+1', so

                                    // do we really have X-value?
                                    if (sample == loSample) {
                                        // prevSample does not exist
                                        // find first valid value from 'x2+1' onwards
                                        long x1 = x2;
                                        while (true) {
                                            x2 = sampler.nextTimestamp(x2);
                                            if (x2 < hiSample) {
                                                final MapValue x2value = findDataMapValue(mapRecord, x2);
                                                if (x2value.getByte(0) == 0) { // non-gap
                                                    // found value at 'x2' - this is our Y-value
                                                    // the X-value it at 'x1'
                                                    // compute slope and go back down all the way to start
                                                    // computing values in records

                                                    // this has to be a loop that would store y1 and y2 values for each
                                                    // group-by function
                                                    // use current 'value' for record
                                                    computeYPoints(mapRecord, x1, x2value);
                                                    interpolateRange(x1, x2, loSample, x1, mapRecord);
                                                    break;
                                                }
                                            } else {
                                                // we only have a single value at 'x1' - cannot interpolate
                                                // make all values before and after 'x1' NULL
                                                nullifyRange(loSample, x1, mapRecord);
                                                nullifyRange(sampler.nextTimestamp(x1), hiSample, mapRecord);
                                                break;
                                            }
                                        }
                                    } else {

                                        // calculate slope between 'preSample' and 'x2'
                                        // yep, that's right, and go all the way back down
                                        // to 'sample' calculating interpolated values
                                        computeYPoints(mapRecord, prevSample, value);
                                        interpolateRange(prevSample, x2, sampler.nextTimestamp(prevSample), x2, mapRecord);
                                    }
                                    break;
                                }
                            } else {
                                // try using first two values
                                // we had X-value at 'prevSample'
                                // it will become Y-value and X is at 'prevSample-1'
                                // and calculate interpolated value all the way to 'hiSample'

                                long x1 = sampler.previousTimestamp(prevSample);

                                if (x1 < loSample) {
                                    // not enough data points
                                    // fill all data points from 'sample' down with null
                                    nullifyRange(sample, hiSample, mapRecord);
                                } else {
                                    computeYPoints(mapRecord, x1, findDataMapValue(mapRecord, prevSample));
                                    interpolateRange(x1, prevSample, sampler.nextTimestamp(prevSample), hiSample, mapRecord);
                                }
                                break;
                            }
                        }
                    }
                }
            }

            return initFunctionsAndCursor(bindVariableService, dataMap.getCursor(), baseCursor);
        } catch (CairoException e) {
            baseCursor.close();
            //todo: free other things
            throw e;
        }
    }

    @Override
    public boolean isRandomAccessCursor() {
        return true;
    }

    private void computeYPoints(Record record, long x1, MapValue x2value) {
        for (int i = 0, m = groupByFunctions.size(); i < m; i++) {
            storeYFunctions.getQuick(i).store(groupByFunctions.getQuick(i), x2value, yData + i * 16 + 8);
        }

        final MapValue x1value = findDataMapValue(record, x1);

        for (int i = 0, m = groupByFunctions.size(); i < m; i++) {
            storeYFunctions.getQuick(i).store(groupByFunctions.getQuick(i), x1value, yData + i * 16);
        }
    }

    private void fillGaps(long lo, long hi) {
        final RecordCursor keyCursor = recordKeyMap.getCursor();
        long timestamp = lo;
        while (timestamp < hi) {
            while (keyCursor.hasNext()) {
                MapKey key = dataMap.withKey();
                Record rec = keyCursor.next();
                mapSink2.copy(rec, key);
                key.putLong(timestamp);
                MapValue value = key.createValue();
                if (value.isNew()) {
                    value.putByte(0, (byte) 1); // this is gap
                }
            }
            timestamp = sampler.nextTimestamp(timestamp);
            keyCursor.toTop();
        }
    }

    private MapValue findDataMapValue(Record record, long timestamp) {
        final MapKey key = dataMap.withKey();
        mapSink2.copy(record, key);
        key.putLong(timestamp);
        return key.findValue();
    }

    private void freeYData() {
        if (yData != 0) {
            Unsafe.free(yData, yDataSize);
            yData = 0;
        }
    }

    @NotNull
    protected RecordCursor initFunctionsAndCursor(BindVariableService bindVariableService, RecordCursor mapCursor, RecordCursor baseCursor) {
        cursor.of(mapCursor, baseCursor);
        // init all record function for this cursor, in case functions require metadata and/or symbol tables
        for (int i = 0, m = recordFunctions.size(); i < m; i++) {
            recordFunctions.getQuick(i).init(cursor, bindVariableService);
        }
        return cursor;
    }

    private void interpolateRange(long x1, long x2, long lo, long hi, Record record) {
        for (long x = lo; x < hi; x = sampler.nextTimestamp(x)) {
            final MapKey key = dataMap.withKey();
            mapSink2.copy(record, key);
            key.putLong(x);
            final MapValue value = key.findValue();
            assert value != null && value.getByte(0) == 1;
            value.putByte(0, (byte) 0); // fill the value, change flag from 'gap' to 'fill'
            for (int i = 0, m = groupByFunctions.size(); i < m; i++) {
                interpolatorFunctions.getQuick(i).interpolateAndStore(groupByFunctions.getQuick(i), value, x, x1, x2, yData + i * 16, yData + i * 16 + 8);
            }
        }
    }

    private void nullifyRange(long lo, long hi, Record record) {
        for (long x = lo; x < hi; x = sampler.nextTimestamp(x)) {
            final MapKey key = dataMap.withKey();
            mapSink2.copy(record, key);
            key.putLong(x);
            MapValue value = key.findValue();
            assert value != null && value.getByte(0) == 1; // expect  'gap' flag
            value.putByte(0, (byte) 0); // fill the value, change flag from 'gap' to 'fill'
            for (int i = 0, m = groupByFunctions.size(); i < m; i++) {
                groupByFunctions.getQuick(i).setNull(value);
            }
        }

    }

    private static class SampleByInterpolatedRecordCursor implements RecordCursor {
        private final VirtualRecord functionRecord;
        private final IntIntHashMap symbolTableIndex;
        private RecordCursor mapCursor;
        private RecordCursor baseCursor;

        public SampleByInterpolatedRecordCursor(ObjList<Function> functions, IntIntHashMap symbolTableIndex) {
            this.functionRecord = new VirtualRecord(functions);
            this.symbolTableIndex = symbolTableIndex;
        }

        @Override
        public void close() {
            Misc.free(mapCursor);
            Misc.free(baseCursor);
        }

        @Override
        public Record getRecord() {
            return functionRecord;
        }

        @Override
        public SymbolTable getSymbolTable(int columnIndex) {
            return baseCursor.getSymbolTable(symbolTableIndex.get(columnIndex));
        }

        @Override
        public Record newRecord() {
            VirtualRecord record = new VirtualRecord(functionRecord.getFunctions());
            record.of(mapCursor.newRecord());
            return record;
        }

        @Override
        public Record recordAt(long rowId) {
            mapCursor.recordAt(functionRecord.getBaseRecord(), rowId);
            return functionRecord;
        }

        @Override
        public void recordAt(Record record, long atRowId) {
            assert record instanceof VirtualRecord;
            mapCursor.recordAt(((VirtualRecord) record).getBaseRecord(), atRowId);
        }

        @Override
        public void toTop() {
            mapCursor.toTop();
        }

        @Override
        public boolean hasNext() {
            return mapCursor.hasNext();
        }

        @Override
        public Record next() {
            mapCursor.next();
            return functionRecord;
        }

        public void of(RecordCursor mapCursor, RecordCursor baseCursor) {
            this.mapCursor = mapCursor;
            this.baseCursor = baseCursor;
            functionRecord.of(mapCursor.getRecord());
        }
    }
}