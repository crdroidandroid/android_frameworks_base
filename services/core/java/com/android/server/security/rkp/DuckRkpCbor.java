/* SPDX-License-Identifier: Apache-2.0 */
package com.android.server.security.rkp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;

final class DuckRkpCbor {
    static final int LIMIT = 256 * 1024;

    private DuckRkpCbor() {}

    static byte[] encode(DataItem item) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(item);
        return out.toByteArray();
    }

    static DataItem decode(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > LIMIT) {
            throw new IOException("Invalid CBOR size");
        }
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        scan(input, 0);
        if (input.available() != 0) throw new IOException("Trailing CBOR data");
        CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(bytes));
        decoder.setRejectDuplicateKeys(true);
        List<DataItem> items = decoder.decode();
        if (items.size() != 1) throw new IOException("Expected one CBOR item");
        return items.get(0);
    }

    private static void scan(ByteArrayInputStream in, int depth) throws IOException {
        if (depth > 16) throw new IOException("CBOR nesting limit");
        int initial = in.read();
        if (initial < 0) throw new IOException("Truncated CBOR");
        int major = initial >>> 5;
        int extra = initial & 31;
        if (extra > 27 || major == 6 || (major == 7 && extra > 23)) {
            throw new IOException("Unsupported CBOR encoding");
        }
        long size = extra;
        if (extra >= 24) {
            size = 0;
            for (int i = 0; i < (1 << (extra - 24)); i++) {
                int next = in.read();
                if (next < 0 || size > (Long.MAX_VALUE >>> 8)) {
                    throw new IOException("Invalid CBOR length");
                }
                size = (size << 8) | next;
            }
        }
        if (major == 2 || major == 3) {
            if (size > in.available() || in.skip(size) != size) {
                throw new IOException("Truncated CBOR string");
            }
        } else if (major == 4 || major == 5) {
            if (size > 128) throw new IOException("CBOR container limit");
            for (long i = 0; i < size * (major == 5 ? 2 : 1); i++) {
                scan(in, depth + 1);
            }
        }
    }

    static List<DataItem> array(DataItem item) throws IOException {
        if (!(item instanceof Array)) throw new IOException("Expected CBOR array");
        return ((Array) item).getDataItems();
    }

    static Map map(DataItem item) throws IOException {
        if (!(item instanceof Map)) throw new IOException("Expected CBOR map");
        return (Map) item;
    }

    static byte[] bytes(DataItem item) throws IOException {
        if (!(item instanceof ByteString) || ((ByteString) item).getBytes() == null) {
            throw new IOException("Expected CBOR bytes");
        }
        return ((ByteString) item).getBytes();
    }

    static int integer(DataItem item) throws IOException {
        if (!(item instanceof co.nstant.in.cbor.model.Number)) {
            throw new IOException("Expected CBOR integer");
        }
        return ((co.nstant.in.cbor.model.Number) item).getValue().intValueExact();
    }

    static DataItem number(long value) {
        return value < 0 ? new NegativeInteger(value) : new UnsignedInteger(value);
    }

    static Array arrayOf(DataItem... items) {
        Array array = new Array();
        for (DataItem item : items) array.add(item);
        return array;
    }

    static ByteString bytes(byte[] bytes) {
        return new ByteString(bytes);
    }

    static UnicodeString text(String value) {
        return new UnicodeString(value);
    }

    static byte[] coordinate(BigInteger value) throws IOException {
        byte[] encoded = value.toByteArray();
        int offset = encoded.length == 33 && encoded[0] == 0 ? 1 : 0;
        if (value.signum() < 0 || encoded.length - offset > 32) {
            throw new IOException("Invalid P-256 coordinate");
        }
        byte[] result = new byte[32];
        System.arraycopy(encoded, offset, result, 32 - encoded.length + offset,
                encoded.length - offset);
        return result;
    }
}
