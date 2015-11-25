package org.apache.kylin.cube.gridtable;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.apache.kylin.aggregation.MeasureAggregator;
import org.apache.kylin.common.datatype.DataTypeSerializer;
import org.apache.kylin.common.datatype.StringSerializer;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.BytesUtil;
import org.apache.kylin.common.util.ImmutableBitSet;
import org.apache.kylin.cube.kv.RowConstants;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.gridtable.DefaultGTComparator;
import org.apache.kylin.gridtable.GTInfo;
import org.apache.kylin.gridtable.IGTCodeSystem;
import org.apache.kylin.gridtable.IGTComparator;

/**
 * defines how column values will be encoded to/ decoded from GTRecord 
 * 
 * Cube meta can provide which columns are dictionary encoded (dict encoded dimensions) or fixed length encoded (fixed length dimensions)
 * Metrics columns are more flexible, they will use DataTypeSerializer according to their data type.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class CubeCodeSystem implements IGTCodeSystem {

    // ============================================================================

    private GTInfo info;

    private Map<Integer, Dictionary> dictionaryMap; // column index ==> dictionary of column
    private Map<Integer, Integer> fixLenMap; // column index ==> fixed length of column
    private Map<Integer, Integer> dependentMetricsMap;
    private DataTypeSerializer[] serializers;
    private IGTComparator comparator;

    public CubeCodeSystem(Map<Integer, Dictionary> dictionaryMap) {
        this(dictionaryMap, Collections.<Integer, Integer> emptyMap(), Collections.<Integer, Integer> emptyMap());
    }

    public CubeCodeSystem(Map<Integer, Dictionary> dictionaryMap, Map<Integer, Integer> fixLenMap, Map<Integer, Integer> dependentMetricsMap) {
        this.dictionaryMap = dictionaryMap;
        this.fixLenMap = fixLenMap;
        this.dependentMetricsMap = dependentMetricsMap;
    }

    public IGTCodeSystem trimForCoprocessor() {
        DataTypeSerializer[] newSerializers = new DataTypeSerializer[serializers.length];

        for (int i = 0; i < serializers.length; i++) {
            if (serializers[i] instanceof DictionarySerializer) {
                newSerializers[i] = new TrimmedDictionarySerializer(serializers[i].maxLength());
            } else {
                newSerializers[i] = serializers[i];
            }
        }

        return new TrimmedCubeCodeSystem(info, dependentMetricsMap, newSerializers, comparator);
    }

    @Override
    public void init(GTInfo info) {
        this.info = info;

        serializers = new DataTypeSerializer[info.getColumnCount()];
        for (int i = 0; i < info.getColumnCount(); i++) {
            // dimension with dictionary
            if (dictionaryMap.get(i) != null) {
                serializers[i] = new DictionarySerializer(dictionaryMap.get(i));
            }
            // dimension of fixed length
            else if (fixLenMap.get(i) != null) {
                serializers[i] = new FixLenSerializer(fixLenMap.get(i));
            }
            // metrics
            else {
                serializers[i] = DataTypeSerializer.create(info.getColumnType(i));
            }
        }

        this.comparator = new DefaultGTComparator();
    }

    @Override
    public IGTComparator getComparator() {
        return comparator;
    }

    @Override
    public int codeLength(int col, ByteBuffer buf) {
        return serializers[col].peekLength(buf);
    }

    @Override
    public int maxCodeLength(int col) {
        return serializers[col].maxLength();
    }

    @Override
    public void encodeColumnValue(int col, Object value, ByteBuffer buf) {
        encodeColumnValue(col, value, 0, buf);
    }

    @Override
    public void encodeColumnValue(int col, Object value, int roundingFlag, ByteBuffer buf) {
        DataTypeSerializer serializer = serializers[col];
        if (serializer instanceof DictionarySerializer) {
            ((DictionarySerializer) serializer).serializeWithRounding(value, roundingFlag, buf);
        } else {
            if ((value instanceof String) && (!(serializer instanceof StringSerializer || serializer instanceof FixLenSerializer))) {
                value = serializer.valueOf((String) value);
            }
            serializer.serialize(value, buf);
        }
    }

    @Override
    public Object decodeColumnValue(int col, ByteBuffer buf) {
        return serializers[col].deserialize(buf);
    }

    @Override
    public MeasureAggregator<?>[] newMetricsAggregators(ImmutableBitSet columns, String[] aggrFunctions) {
        assert columns.trueBitCount() == aggrFunctions.length;

        MeasureAggregator<?>[] result = new MeasureAggregator[aggrFunctions.length];
        for (int i = 0; i < result.length; i++) {
            int col = columns.trueBitAt(i);
            result[i] = MeasureAggregator.create(aggrFunctions[i], info.getColumnType(col).toString());
        }

        // deal with holistic distinct count
        if (dependentMetricsMap != null) {
            for (Integer child : dependentMetricsMap.keySet()) {
                if (columns.get(child)) {
                    Integer parent = dependentMetricsMap.get(child);
                    if (columns.get(parent) == false)
                        throw new IllegalStateException();

                    int childIdx = columns.trueBitIndexOf(child);
                    int parentIdx = columns.trueBitIndexOf(parent);
                    result[childIdx].setDependentAggregator(result[parentIdx]);
                }
            }
        }

        return result;
    }

    static class TrimmedDictionarySerializer extends DataTypeSerializer {

        final int fieldSize;

        public TrimmedDictionarySerializer(int fieldSize) {
            this.fieldSize = fieldSize;

        }

        @Override
        public int peekLength(ByteBuffer in) {
            return fieldSize;
        }

        @Override
        public int maxLength() {
            return fieldSize;
        }

        @Override
        public int getStorageBytesEstimate() {
            return fieldSize;
        }

        @Override
        public Object valueOf(byte[] value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void serialize(Object value, ByteBuffer out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object deserialize(ByteBuffer in) {
            throw new UnsupportedOperationException();
        }
    }

    static class DictionarySerializer extends DataTypeSerializer {
        private Dictionary dictionary;

        DictionarySerializer(Dictionary dictionary) {
            this.dictionary = dictionary;
        }

        public void serializeWithRounding(Object value, int roundingFlag, ByteBuffer buf) {
            int id = dictionary.getIdFromValue(value, roundingFlag);
            BytesUtil.writeUnsigned(id, dictionary.getSizeOfId(), buf);
        }

        @Override
        public void serialize(Object value, ByteBuffer buf) {
            int id = dictionary.getIdFromValue(value);
            BytesUtil.writeUnsigned(id, dictionary.getSizeOfId(), buf);
        }

        @Override
        public Object deserialize(ByteBuffer in) {
            int id = BytesUtil.readUnsigned(in, dictionary.getSizeOfId());
            return dictionary.getValueFromId(id);
        }

        @Override
        public int peekLength(ByteBuffer in) {
            return dictionary.getSizeOfId();
        }

        @Override
        public int maxLength() {
            return dictionary.getSizeOfId();
        }

        @Override
        public int getStorageBytesEstimate() {
            return dictionary.getSizeOfId();
        }

        @Override
        public Object valueOf(byte[] value) {
            throw new UnsupportedOperationException();
        }
    }

    static class FixLenSerializer extends DataTypeSerializer {

        // be thread-safe and avoid repeated obj creation
        private ThreadLocal<byte[]> current = new ThreadLocal<byte[]>();

        private int fixLen;

        FixLenSerializer(int fixLen) {
            this.fixLen = fixLen;
        }

        private byte[] currentBuf() {
            byte[] buf = current.get();
            if (buf == null) {
                buf = new byte[fixLen];
                current.set(buf);
            }
            return buf;
        }

        @Override
        public void serialize(Object value, ByteBuffer out) {
            byte[] buf = currentBuf();
            if (value == null) {
                Arrays.fill(buf, Dictionary.NULL);
                out.put(buf);
            } else {
                byte[] bytes = Bytes.toBytes(value.toString());
                if (bytes.length > fixLen) {
                    throw new IllegalStateException("Expect at most " + fixLen + " bytes, but got " + bytes.length + ", value string: " + value.toString());
                }
                out.put(bytes);
                for (int i = bytes.length; i < fixLen; i++) {
                    out.put(RowConstants.ROWKEY_PLACE_HOLDER_BYTE);
                }
            }
        }

        @Override
        public Object deserialize(ByteBuffer in) {
            byte[] buf = currentBuf();
            in.get(buf);

            int tail = fixLen;
            while (tail > 0 && (buf[tail - 1] == RowConstants.ROWKEY_PLACE_HOLDER_BYTE || buf[tail - 1] == Dictionary.NULL)) {
                tail--;
            }

            if (tail == 0) {
                return buf[0] == Dictionary.NULL ? null : "";
            }

            return Bytes.toString(buf, 0, tail);
        }

        @Override
        public int peekLength(ByteBuffer in) {
            return fixLen;
        }

        @Override
        public int maxLength() {
            return fixLen;
        }

        @Override
        public int getStorageBytesEstimate() {
            return fixLen;
        }

        @Override
        public Object valueOf(byte[] value) {
            try {
                return new String(value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // does not happen
                throw new RuntimeException(e);
            }
        }

    }

}