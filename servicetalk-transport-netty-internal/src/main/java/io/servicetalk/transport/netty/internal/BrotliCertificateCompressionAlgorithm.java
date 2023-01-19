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
package io.servicetalk.transport.netty.internal;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.Decoder;
import com.aayushatharva.brotli4j.decoder.DecoderJNI;
import com.aayushatharva.brotli4j.decoder.DirectDecompress;
import com.aayushatharva.brotli4j.encoder.Encoder;
import io.netty.handler.ssl.OpenSslCertificateCompressionAlgorithm;

import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLEngine;

public class BrotliCertificateCompressionAlgorithm implements OpenSslCertificateCompressionAlgorithm {

    static {
        Brotli4jLoader.ensureAvailability();
    }

    @Override
    public byte[] compress(final SSLEngine engine, final byte[] uncompressedCertificate) throws Exception {
        return Encoder.compress(uncompressedCertificate);
    }

    @Override
    public byte[] decompress(final SSLEngine engine, final int uncompressedLen, final byte[] compressedCertificate)
            throws Exception {
        DirectDecompress directDecompress = Decoder.decompress(compressedCertificate);
        if (directDecompress.getResultStatus() != DecoderJNI.Status.DONE) {
            // TODO
            throw new RuntimeException("Failed brotli decompression");
        }
        byte[] decompressed = directDecompress.getDecompressedData();
        if (decompressed.length != uncompressedLen) {
            // TODO
            throw new RuntimeException("Error while decompressing with brotli, incorrect size");
        }
        return decompressed;
    }

    @Override
    public int algorithmId() {
        return 0x02;
    }
}
