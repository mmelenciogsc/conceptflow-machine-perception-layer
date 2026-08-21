// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Properties;
import org.conceptflow.mpl.v1.FramePayload;
import org.conceptflow.mpl.v1.ImageEncoding;
import org.conceptflow.mpl.v1.PerceptionCue;
import org.conceptflow.mpl.v1.PerceptionResult;
import org.junit.Test;

public final class ProtocolVectorInteropTest {
    @Test
    public void pythonGeneratedCanonicalVectorsParseAndRoundTripByteExactly() throws IOException {
        Properties vectors = new Properties();
        try (InputStream stream = requireFixture()) {
            vectors.load(stream);
        }
        byte[] frameBytes = Base64.getDecoder().decode(vectors.getProperty("frame_payload_base64"));
        byte[] resultBytes = Base64.getDecoder().decode(vectors.getProperty("perception_result_base64"));

        FramePayload frame = FramePayload.parseFrom(frameBytes);
        PerceptionResult result = PerceptionResult.parseFrom(resultBytes);

        assertEquals("1", vectors.getProperty("schema_version"));
        assertEquals("interop-request-1", frame.getRequestId());
        assertEquals(ImageEncoding.IMAGE_ENCODING_PNG, frame.getImage().getEncoding());
        assertTrue(frame.getFrameData().startsWith(com.google.protobuf.ByteString.copyFrom(
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47})));
        assertEquals(frame.getRequestId(), result.getRequestId());
        assertEquals(frame.getFrameId(), result.getFrameId());
        PerceptionCue cue = result.getCues(0);
        assertEquals("Object ahead", cue.getSpeech().getText());
        assertArrayEquals(frameBytes, frame.toByteArray());
        assertArrayEquals(resultBytes, result.toByteArray());
    }

    private static InputStream requireFixture() {
        InputStream stream = ProtocolVectorInteropTest.class.getClassLoader()
                .getResourceAsStream("protocol_vectors.properties");
        if (stream == null) {
            throw new IllegalStateException("canonical protocol vector resource is missing");
        }
        return stream;
    }
}
