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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HexUtilTest {

    private static final BufferAllocator ALLOC = ReadOnlyBufferAllocator.PREFER_HEAP_ALLOCATOR;

    @Test
    void testSimpleHexDump() {
        Buffer input = ALLOC.fromAscii("abc");
        String output = HexUtil.simpleHexDump(input, 0, input.readableBytes());
        assertEquals("616263", output);

        output = HexUtil.simpleHexDump(input, 1, input.readableBytes() - 1);
        assertEquals("6263", output);

        output = HexUtil.simpleHexDump(input, 0, 2);
        assertEquals("6162", output);
    }

    @Test
    void testPrettyHexDump() {
        Buffer input = ALLOC.fromAscii("abc");
        String output = HexUtil.prettyHexDump(input, 0, input.readableBytes());

        String expected = "         +-------------------------------------------------+\n" +
                "         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |\n" +
                "+--------+-------------------------------------------------+----------------+\n" +
                "|00000000| 61 62 63                                        |abc             |\n" +
                "+--------+-------------------------------------------------+----------------+";
        assertEquals(expected, output);
    }

}
