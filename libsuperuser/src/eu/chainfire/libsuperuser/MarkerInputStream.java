/*
 * Copyright (C) 2012-2019 Jorrit "Chainfire" Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.chainfire.libsuperuser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

@SuppressWarnings("WeakerAccess")
@AnyThread
public class MarkerInputStream extends InputStream {
    private static final String EXCEPTION_EOF = "EOF encountered, shell probably died";

    @NonNull
    private final StreamGobbler gobbler;
    private final InputStream inputStream;
    private final byte[] marker;
    private final int markerLength;
    private final int markerMaxLength;
    private final byte[] read1 = new byte[1];
    private final byte[] buffer = new byte[65536];
    private int bufferUsed = 0;
    private volatile boolean eof = false;
    private volatile boolean done = false;

    public MarkerInputStream(@NonNull StreamGobbler gobbler, @NonNull String marker) throws UnsupportedEncodingException {
        this.gobbler = gobbler;
        this.gobbler.suspendGobbling();
        this.inputStream = gobbler.getInputStream();
        this.marker = marker.getBytes("UTF-8");
        this.markerLength = marker.length();
        this.markerMaxLength = marker.length() + 5; // marker + space + exitCode(max(3)) + \n
    }

    @Override
    public int read() throws IOException {
        while (true) {
            int r = read(read1, 0, 1);
            if (r < 0) return -1;
            if (r == 0) {
                // wait for data to become available
                try {
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    // no action
                }
                continue;
            }
            return (int)read1[0] & 0xFF;
        }
    }

    @Override
    public int read(@NonNull byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    private void fill(int safeSizeToWaitFor) {
        // fill up our own buffer
        if (isEOF()) return;
        try {
            int a;
            while (((a = inputStream.available()) > 0) || (safeSizeToWaitFor > 0)) {
                int left = buffer.length - bufferUsed;
                if (left == 0) return;
                int r = inputStream.read(buffer, bufferUsed, Math.max(safeSizeToWaitFor, Math.min(a, left)));
                if (r >= 0) {
                    bufferUsed += r;
                    safeSizeToWaitFor -= r;
                } else {
                    // This shouldn't happen *unless* we have both the full content and the end
                    // marker, otherwise the shell was interrupted/died. An IOException is raised
                    // in read() below if that is the case.
                    setEOF();
                    break;
                }
            }
        } catch (IOException e) {
            setEOF();
        }
    }

    @Override
    public synchronized int read(@NonNull byte[] b, int off, int len) throws IOException {
        if (done) return -1;

        fill(markerLength - bufferUsed);

        // we need our buffer to be big enough to detect the marker
        if (bufferUsed < markerLength) return 0;

        // see if we have our marker
        int match = -1;
        for (int i = Math.max(0, bufferUsed - markerMaxLength); i < bufferUsed - markerLength; i++) {
            boolean found = true;
            for (int j = 0; j < markerLength; j++) {
                if (buffer[i + j] != marker[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                match = i;
                break;
            }
        }

        if (match == 0) {
            // marker is at the front of the buffer
            while (buffer[bufferUsed -1] != (byte)'\n') {
                if (isEOF()) throw new IOException(EXCEPTION_EOF);
                fill(1);
            }
            if (gobbler.getOnLineListener() != null) gobbler.getOnLineListener().onLine(new String(buffer, 0, bufferUsed - 1, "UTF-8"));
            done = true;
            return -1;
        } else {
            int ret;
            if (match == -1) {
                if (isEOF()) throw new IOException(EXCEPTION_EOF);

                // marker isn't in the buffer, drain as far as possible while keeping some space
                // leftover so we can still find the marker if its read is split between two fill()
                // calls
                ret = Math.min(len, bufferUsed - markerMaxLength);
            } else {
                // even if eof, it is possibly we have both the content and the end marker, which
                // counts as a completed command, so we don't throw IOException here

                // marker found, max drain up to marker, this will eventually cause the marker to be
                // at the front of the buffer
                ret = Math.min(len, match);
            }
            if (ret > 0) {
                System.arraycopy(buffer, 0, b, off, ret);
                bufferUsed -= ret;
                System.arraycopy(buffer, ret, buffer, 0, bufferUsed);
            } else {
                try {
                    // prevent 100% CPU on reading from for example /dev/random
                    Thread.sleep(4);
                } catch (Exception e) {
                    // no action
                }
            }
            return ret;
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public synchronized void close() throws IOException {
        if (!isEOF() && !done) {
            // drain
            byte[] buffer = new byte[1024];
            while (read(buffer) >= 0) {
            }
        }
    }

    public synchronized boolean isEOF() {
        return eof;
    }

    public synchronized void setEOF() {
        eof = true;
    }
}
