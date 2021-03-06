/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.coyote.ProtocolException;
import org.apache.coyote.http2.HpackDecoder.HeaderEmitter;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

class Http2Parser {

    private static final Log log = LogFactory.getLog(Http2Parser.class);
    private static final StringManager sm = StringManager.getManager(Http2Parser.class);

    static final byte[] CLIENT_PREFACE_START =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final String connectionId;
    private final Input input;
    private final Output output;
    private final byte[] frameHeaderBuffer = new byte[9];

    private volatile HpackDecoder hpackDecoder;
    private final ByteBuffer headerReadBuffer = ByteBuffer.allocate(1024);
    private volatile int headersCurrentStream = -1;
    private volatile boolean headersEndStream = false;

    private volatile int maxPayloadSize = ConnectionSettings.DEFAULT_MAX_FRAME_SIZE;


    Http2Parser(String connectionId, Input input, Output output) {
        this.connectionId = connectionId;
        this.input = input;
        this.output = output;
    }


    /**
     * Read and process a single frame. Once the start of a frame is read, the
     * remainder will be read using blocking IO.
     *
     * @param block Should this method block until a frame is available is no
     *              frame is available immediately?
     *
     * @return <code>true</code> if a frame was read otherwise
     *         <code>false</code>
     *
     * @throws IOException If an IO error occurs while trying to read a frame
     */
    boolean readFrame(boolean block) throws IOException {
        return readFrame(block, null);
    }


    private boolean readFrame(boolean block, FrameType expected) throws IOException {
        if (!input.fill(block, frameHeaderBuffer)) {
            return false;
        }

        int payloadSize = ByteUtil.getThreeBytes(frameHeaderBuffer, 0);
        FrameType frameType = FrameType.valueOf(ByteUtil.getOneByte(frameHeaderBuffer, 3));
        int flags = ByteUtil.getOneByte(frameHeaderBuffer, 4);
        int streamId = ByteUtil.get31Bits(frameHeaderBuffer, 5);

        validateFrame(expected, frameType, streamId, flags, payloadSize);

        switch (frameType) {
        case DATA:
            readDataFrame(streamId, flags, payloadSize);
            break;
        case HEADERS:
            readHeadersFrame(streamId, flags, payloadSize);
            break;
        case PRIORITY:
            readPriorityFrame(streamId);
            break;
        case RST:
            readRstFrame(streamId);
            break;
        case SETTINGS:
            readSettingsFrame(flags, payloadSize);
            break;
        case PUSH_PROMISE:
            readPushPromiseFrame(streamId);
            break;
        case PING:
            readPingFrame(flags);
            break;
        case GOAWAY:
            readGoawayFrame(payloadSize);
            break;
        case WINDOW_UPDATE:
            readWindowUpdateFrame(streamId);
            break;
        case CONTINUATION:
            readContinuationFrame(streamId, flags, payloadSize);
            break;
        case UNKNOWN:
            readUnknownFrame(streamId, frameType, flags, payloadSize);
        }

        return true;
    }


    private void readDataFrame(int streamId, int flags, int payloadSize) throws IOException {
        // Process the Stream
        int padLength = 0;

        boolean endOfStream = Flags.isEndOfStream(flags);

        if (Flags.hasPadding(flags)) {
            byte[] b = new byte[1];
            input.fill(true, b);
            padLength = b[0] & 0xFF;
        }

        ByteBuffer dest = output.getInputByteBuffer(streamId, payloadSize);
        if (dest == null) {
            swallow(payloadSize);
            if (endOfStream) {
                output.receiveEndOfStream(streamId);
            }
        } else {
            synchronized (dest) {
                input.fill(true, dest, payloadSize);
                if (endOfStream) {
                    output.receiveEndOfStream(streamId);
                }
                dest.notifyAll();
            }
        }
        swallow(padLength);
    }


    private void readHeadersFrame(int streamId, int flags, int payloadSize) throws IOException {
        if (hpackDecoder == null) {
            hpackDecoder = output.getHpackDecoder();
        }
        hpackDecoder.setHeaderEmitter(output.headersStart(streamId));

        int padLength = 0;
        boolean padding = Flags.hasPadding(flags);
        boolean priority = Flags.hasPriority(flags);
        int optionalLen = 0;
        if (padding) {
            optionalLen = 1;
        }
        if (priority) {
            optionalLen += 5;
        }
        if (optionalLen > 0) {
            byte[] optional = new byte[optionalLen];
            input.fill(true, optional);
            int optionalPos = 0;
            if (padding) {
                padLength = ByteUtil.getOneByte(optional, optionalPos++);
            }
            if (priority) {
                boolean exclusive = ByteUtil.isBit7Set(optional[optionalPos]);
                int parentStreamId = ByteUtil.get31Bits(optional, optionalPos);
                int weight = ByteUtil.getOneByte(optional, optionalPos + 4) + 1;
                output.reprioritise(streamId, parentStreamId, exclusive, weight);
            }

            payloadSize -= optionalLen;
        }

        boolean endOfHeaders = Flags.isEndOfHeaders(flags);

        readHeaderBlock(payloadSize, endOfHeaders);

        swallow(padLength);

        if (endOfHeaders) {
            output.headersEnd(streamId);
        } else {
            headersCurrentStream = streamId;
        }

        if (Flags.isEndOfStream(flags)) {
            if (headersCurrentStream == -1) {
                output.receiveEndOfStream(streamId);
            } else {
                headersEndStream = true;
            }
        }
    }


    private void readPriorityFrame(int streamId) throws IOException {
        byte[] payload = new byte[5];
        input.fill(true, payload);

        boolean exclusive = ByteUtil.isBit7Set(payload[0]);
        int parentStreamId = ByteUtil.get31Bits(payload, 0);
        int weight = ByteUtil.getOneByte(payload, 4) + 1;

        output.reprioritise(streamId, parentStreamId, exclusive, weight);
    }


    private void readRstFrame(int streamId) throws IOException {
        byte[] payload = new byte[4];
        input.fill(true, payload);

        long errorCode = ByteUtil.getFourBytes(payload, 0);
        output.reset(streamId, errorCode);
    }


    private void readSettingsFrame(int flags, int payloadSize) throws IOException {
        boolean ack = Flags.isAck(flags);
        if (payloadSize > 0 && ack) {
            throw new Http2Exception(sm.getString("http2Parser.processFrameSettings.ackWithNonZeroPayload"),
                    0, ErrorCode.FRAME_SIZE_ERROR);
        }

        if (payloadSize != 0) {
            // Process the settings
            byte[] setting = new byte[6];
            for (int i = 0; i < payloadSize / 6; i++) {
                input.fill(true, setting);
                int id = ByteUtil.getTwoBytes(setting, 0);
                long value = ByteUtil.getFourBytes(setting, 2);
                output.setting(id, value);
            }
        }
        output.settingsEnd(ack);
    }


    private void readPushPromiseFrame(int streamId) throws IOException {
        throw new Http2Exception(sm.getString("http2Parser.processFramePushPromise",
                connectionId, Integer.valueOf(streamId)), 0, ErrorCode.PROTOCOL_ERROR);
    }


    private void readPingFrame(int flags) throws IOException {
        if (Flags.isAck(flags)) {
            // Read the payload
            byte[] payload = new byte[8];
            input.fill(true, payload);
            output.pingReceive(payload);
        } else {
            output.pingAck();
        }
    }


    private void readGoawayFrame(int payloadSize) throws IOException {
        byte[] payload = new byte[payloadSize];
        input.fill(true, payload);

        int lastStreamId = ByteUtil.get31Bits(payload, 0);
        long errorCode = ByteUtil.getFourBytes(payload, 4);
        String debugData = null;
        if (payloadSize > 8) {
            debugData = new String(payload, 8, payloadSize - 8, StandardCharsets.UTF_8);
        }
        output.goaway(lastStreamId, errorCode, debugData);
    }


    private void readWindowUpdateFrame(int streamId) throws IOException {
        byte[] payload = new byte[4];
        input.fill(true,  payload);
        int windowSizeIncrement = ByteUtil.get31Bits(payload, 0);

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("http2Parser.processFrameWindowUpdate.debug", connectionId,
                    Integer.toString(streamId), Integer.toString(windowSizeIncrement)));
        }

        // Validate the data
        if (windowSizeIncrement == 0) {
            throw new Http2Exception("http2Parser.processFrameWindowUpdate.invalidIncrement",
                    streamId, ErrorCode.PROTOCOL_ERROR);
        }

        output.incrementWindowSize(streamId, windowSizeIncrement);
    }


    private void readContinuationFrame(int streamId, int flags, int payloadSize)
            throws IOException {
        if (headersCurrentStream == -1) {
            // No headers to continue
            throw new Http2Exception(sm.getString(
                    "http2Parser.processFrameContinuation.notExpected", connectionId,
                    Integer.toString(streamId)), 0, ErrorCode.PROTOCOL_ERROR);
        }

        boolean endOfHeaders = Flags.isEndOfHeaders(flags);
        readHeaderBlock(payloadSize, endOfHeaders);

        if (endOfHeaders) {
            output.headersEnd(streamId);
            headersCurrentStream = -1;
            if (headersEndStream) {
                output.receiveEndOfStream(streamId);
                headersEndStream = false;
            }
        }
    }


    private void readHeaderBlock(int payloadSize, boolean endOfHeaders) throws IOException {
        while (payloadSize > 0) {
            int toRead = Math.min(headerReadBuffer.remaining(), payloadSize);
            // headerReadBuffer in write mode
            input.fill(true, headerReadBuffer, toRead);
            // switch to read mode
            headerReadBuffer.flip();
            try {
                hpackDecoder.decode(headerReadBuffer);
            } catch (HpackException hpe) {
                throw new Http2Exception(
                        sm.getString("http2Parser.processFrameHeaders.decodingFailed"),
                        0, ErrorCode.COMPRESSION_ERROR);
            }
            // switches to write mode
            headerReadBuffer.compact();
            payloadSize -= toRead;
        }

        if (headerReadBuffer.position() > 0 && endOfHeaders) {
            throw new Http2Exception(
                    sm.getString("http2Parser.processFrameHeaders.decodingDataLeft"),
                    0, ErrorCode.COMPRESSION_ERROR);
        }
    }


    private void readUnknownFrame(int streamId, FrameType frameType, int flags, int payloadSize)
            throws IOException {
        output.swallow(streamId, frameType, flags, payloadSize);
        swallow(payloadSize);
    }


    private void swallow(int len) throws IOException {
        if (len == 0) {
            return;
        }
        int read = 0;
        byte[] buffer = new byte[1024];
        while (read < len) {
            int thisTime = Math.min(buffer.length, len - read);
            input.fill(true, buffer, 0, thisTime);
            read += thisTime;
        }
    }


    /*
     * Implementation note:
     * Validation applicable to all incoming frames should be implemented here.
     * Frame type specific validation should be performed in the appropriate
     * readXxxFrame() method.
     * For validation applicable to some but not all frame types, use your
     * judgement.
     */
    private void validateFrame(FrameType expected, FrameType frameType, int streamId, int flags,
            int payloadSize) throws Http2Exception {

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("http2Parser.processFrame", connectionId,
                    Integer.toString(streamId), frameType, Integer.toString(flags),
                    Integer.toString(payloadSize)));
        }

        if (expected != null && frameType != expected) {
            throw new Http2Exception(sm.getString("http2Parser.processFrame.unexpectedType",
                    expected, frameType), streamId, ErrorCode.PROTOCOL_ERROR);
        }

        if (payloadSize > maxPayloadSize) {
            throw new Http2Exception(sm.getString("http2Parser.payloadTooBig",
                    Integer.toString(payloadSize), Integer.toString(maxPayloadSize)),
                    streamId, ErrorCode.FRAME_SIZE_ERROR);
        }

        if (headersCurrentStream != -1) {
            if (headersCurrentStream != streamId) {
                throw new Http2Exception(sm.getString("http2Parser.headers.wrongStream",
                        connectionId, Integer.toString(headersCurrentStream),
                        Integer.toString(streamId)), streamId, ErrorCode.COMPRESSION_ERROR);
            }
            if (frameType != FrameType.CONTINUATION) {
                throw new Http2Exception(sm.getString("http2Parser.headers.wrongFrameType",
                        connectionId, Integer.toString(headersCurrentStream),
                        frameType), streamId, ErrorCode.COMPRESSION_ERROR);
            }
        }

        frameType.checkStream(connectionId, streamId);
        frameType.checkPayloadSize(connectionId, streamId, payloadSize);
    }


    /**
     * Read and validate the connection preface from input using blocking IO.
     */
    void readConnectionPreface() {
        byte[] data = new byte[CLIENT_PREFACE_START.length];
        try {
            input.fill(true, data);

            for (int i = 0; i < CLIENT_PREFACE_START.length; i++) {
                if (CLIENT_PREFACE_START[i] != data[i]) {
                    throw new ProtocolException(sm.getString("http2Parser.preface.invalid"));
                }
            }

            // Must always be followed by a settings frame
            readFrame(true, FrameType.SETTINGS);
        } catch (IOException ioe) {
            throw new ProtocolException(sm.getString("http2Parser.preface.io"), ioe);
        }
    }


    void setHpackDecoder(HpackDecoder hpackDecoder) {
        this.hpackDecoder = hpackDecoder;
    }


    /**
     * Interface that must be implemented by the source of data for the parser.
     */
    static interface Input {

        /**
         * Fill the given array with data unless non-blocking is requested and
         * no data is available. If any data is available then the buffer will
         * be filled using blocking I/O.
         *
         * @param block Should the first read into the provided buffer be a
         *              blocking read or not.
         * @param data  Buffer to fill
         * @param offset Position in buffer to start writing
         * @param length Number of bytes to read
         *
         * @return <code>true</code> if the buffer was filled otherwise
         *         <code>false</code>
         *
         * @throws IOException If an I/O occurred while obtaining data with
         *                     which to fill the buffer
         */
        boolean fill(boolean block, byte[] data, int offset, int length) throws IOException;

        default boolean fill(boolean block, byte[] data) throws IOException {
            return fill(block, data, 0, data.length);
        }

        default boolean fill(boolean block, ByteBuffer data, int len) throws IOException {
            boolean result = fill(block, data.array(), data.arrayOffset(), len);
            if (result) {
                data.position(data.position() + len);
            }
            return result;
        }
    }


    /**
     * Interface that must be implemented to receive notifications from the
     * parser as it processes incoming frames.
     */
    static interface Output {

        HpackDecoder getHpackDecoder();

        // Data frames
        ByteBuffer getInputByteBuffer(int streamId, int payloadSize) throws Http2Exception;
        void receiveEndOfStream(int streamId);

        // Header frames
        HeaderEmitter headersStart(int streamId) throws Http2Exception;
        void headersEnd(int streamId);

        // Priority frames (also headers)
        void reprioritise(int streamId, int parentStreamId, boolean exclusive, int weight)
                throws Http2Exception;

        // Reset frames
        void reset(int streamId, long errorCode) throws Http2Exception;

        // Settings frames
        void setting(int identifier, long value) throws IOException;
        void settingsEnd(boolean ack) throws IOException;

        // Ping frames
        void pingReceive(byte[] payload) throws IOException;
        void pingAck();

        // Goaway
        void goaway(int lastStreamId, long errorCode, String debugData);

        // Window size
        void incrementWindowSize(int streamId, int increment) throws Http2Exception;

        // Testing
        void swallow(int streamId, FrameType frameType, int flags, int size) throws IOException;
    }
}
