/*
 * Copyright © 2023 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.buffer.api;

final class HexUtil {

    private static final char[] HEXDUMP_TABLE = new char[256 * 4];
    private static final String[] BYTE2HEX = new String[256];
    private static final String[] BYTE2HEX_PAD = new String[256];
    private static final String[] HEXDUMP_ROWPREFIXES = new String[65536 >>> 4];
    private static final char[] BYTE2CHAR = new char[256];
    private static final String[] HEXPADDING = new String[16];
    private static final String[] BYTEPADDING = new String[16];
    private static final String NEWLINE = "\n";

    static {
        final char[] DIGITS = "0123456789abcdef".toCharArray();
        for (int i = 0; i < 256; i ++) {
            HEXDUMP_TABLE[ i << 1     ] = DIGITS[i >>> 4 & 0x0F];
            HEXDUMP_TABLE[(i << 1) + 1] = DIGITS[i       & 0x0F];
        }

        // Generate the lookup table that converts a byte into a 2-digit hexadecimal integer.
        for (int i = 0; i < BYTE2HEX_PAD.length; i++) {
            String str = Integer.toHexString(i);
            BYTE2HEX_PAD[i] = i > 0xf ? str : ('0' + str);
        }

        int i;

        // Generate the lookup table for byte-to-hex-dump conversion
        for (i = 0; i < BYTE2HEX.length; i ++) {
            BYTE2HEX[i] = ' ' + BYTE2HEX_PAD[i & 0xff];
        }

        // Generate the lookup table for hex dump paddings
        for (i = 0; i < HEXPADDING.length; i ++) {
            int padding = HEXPADDING.length - i;
            StringBuilder buf = new StringBuilder(padding * 3);
            for (int j = 0; j < padding; j ++) {
                buf.append("   ");
            }
            HEXPADDING[i] = buf.toString();
        }

        // Generate the lookup table for the start-offset header in each row (up to 64KiB).
        for (i = 0; i < HEXDUMP_ROWPREFIXES.length; i ++) {
            StringBuilder buf = new StringBuilder(12);
            buf.append(NEWLINE);
            buf.append(Long.toHexString(i << 4 & 0xFFFFFFFFL | 0x100000000L));
            buf.setCharAt(buf.length() - 9, '|');
            buf.append('|');
            HEXDUMP_ROWPREFIXES[i] = buf.toString();
        }

        // Generate the lookup table for byte dump paddings
        for (i = 0; i < BYTEPADDING.length; i ++) {
            int padding = BYTEPADDING.length - i;
            StringBuilder buf = new StringBuilder(padding);
            for (int j = 0; j < padding; j ++) {
                buf.append(' ');
            }
            BYTEPADDING[i] = buf.toString();
        }

        // Generate the lookup table for byte-to-char conversion
        for (i = 0; i < BYTE2CHAR.length; i ++) {
            if (i <= 0x1f || i >= 0x7f) {
                BYTE2CHAR[i] = '.';
            } else {
                BYTE2CHAR[i] = (char) i;
            }
        }

    }

    private HexUtil() {
        // singleton
    }

    static String simpleHexDump(final Buffer buffer, final int fromIndex, final int length) {
        if (length <= 0) {
            return "";
        }

        int endIndex = fromIndex + length;
        char[] buf = new char[length << 1];

        int srcIdx = fromIndex;
        int dstIdx = 0;
        for (; srcIdx < endIndex; srcIdx ++, dstIdx += 2) {
            System.arraycopy(
                    HEXDUMP_TABLE, buffer.getUnsignedByte(srcIdx) << 1,
                    buf, dstIdx, 2);
        }

        return new String(buf);
    }

    static String prettyHexDump(final Buffer buffer, final int fromIndex, final int length) {
        if (length <= 0) {
            return "";
        }

        int rows = length / 16 + ((length & 15) == 0? 0 : 1) + 4;
        StringBuilder buf = new StringBuilder(rows * 80);
        appendPrettyHexDump(buf, buffer, fromIndex, length);
        return buf.toString();
    }

    private static void appendPrettyHexDump(StringBuilder dump, Buffer buffer, int fromIndex, int length) {
        if (isOutOfBounds(fromIndex, length, buffer.capacity())) {
            throw new IndexOutOfBoundsException(
                    "expected: " + "0 <= fromIndex(" + fromIndex + ") <= fromIndex + length(" + length
                            + ") <= " + "buffer.capacity(" + buffer.capacity() + ')');
        }
        if (length == 0) {
            return;
        }
        dump.append(
                "         +-------------------------------------------------+" +
                        NEWLINE + "         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |" +
                        NEWLINE + "+--------+-------------------------------------------------+----------------+");

        final int fullRows = length >>> 4;
        final int remainder = length & 0xF;

        // Dump the rows which have 16 bytes.
        for (int row = 0; row < fullRows; row ++) {
            int rowStartIndex = (row << 4) + fromIndex;

            // Per-row prefix.
            appendHexDumpRowPrefix(dump, row, rowStartIndex);

            // Hex dump
            int rowEndIndex = rowStartIndex + 16;
            for (int j = rowStartIndex; j < rowEndIndex; j ++) {
                dump.append(BYTE2HEX[buffer.getUnsignedByte(j)]);
            }
            dump.append(" |");

            // ASCII dump
            for (int j = rowStartIndex; j < rowEndIndex; j ++) {
                dump.append(BYTE2CHAR[buffer.getUnsignedByte(j)]);
            }
            dump.append('|');
        }

        // Dump the last row which has less than 16 bytes.
        if (remainder != 0) {
            int rowStartIndex = (fullRows << 4) + fromIndex;
            appendHexDumpRowPrefix(dump, fullRows, rowStartIndex);

            // Hex dump
            int rowEndIndex = rowStartIndex + remainder;
            for (int j = rowStartIndex; j < rowEndIndex; j ++) {
                dump.append(BYTE2HEX[buffer.getUnsignedByte(j)]);
            }
            dump.append(HEXPADDING[remainder]);
            dump.append(" |");

            // Ascii dump
            for (int j = rowStartIndex; j < rowEndIndex; j ++) {
                dump.append(BYTE2CHAR[buffer.getUnsignedByte(j)]);
            }
            dump.append(BYTEPADDING[remainder]);
            dump.append('|');
        }

        dump.append(NEWLINE +
                "+--------+-------------------------------------------------+----------------+");
    }

    private static void appendHexDumpRowPrefix(final StringBuilder dump, final int row, final int rowStartIndex) {
        if (row < HEXDUMP_ROWPREFIXES.length) {
            dump.append(HEXDUMP_ROWPREFIXES[row]);
        } else {
            dump.append(NEWLINE);
            dump.append(Long.toHexString(rowStartIndex & 0xFFFFFFFFL | 0x100000000L));
            dump.setCharAt(dump.length() - 9, '|');
            dump.append('|');
        }
    }

    private static boolean isOutOfBounds(final int index, final int length, final int capacity) {
        return (index | length | capacity | (index + length) | (capacity - (index + length))) < 0;
    }

}
