/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.rocksdb.streaming;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.util.DataOutputStreamPlus;
import org.apache.cassandra.metrics.StreamingMetrics;
import org.apache.cassandra.rocksdb.encoding.RowKeyEncoder;
import org.apache.cassandra.streaming.ProgressInfo;
import org.apache.cassandra.streaming.StreamManager;
import org.apache.cassandra.streaming.StreamSession;
import org.apache.cassandra.utils.FBUtilities;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

public class RocksDBStreamWriter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RocksDBStreamWriter.class);
    private static final long READ_AHEAD_SIZE = Long.getLong("cassandra.rocksdb.stream.readahead_size", 10L * 1024 * 1024);
    private static final long OUTGOING_BYTES_DELTA_UPDATE_THRESHOLD = 1 * 1024 * 1024;
    private final RocksDB db;
    private final Collection<Range<Token>> ranges;
    private final StreamManager.StreamRateLimiter limiter;
    private final long estimatedTotalSize;
    private StreamSession session = null;
    private long outgoingBytes;

    public RocksDBStreamWriter(RocksDB db, Collection<Range<Token>> ranges, StreamManager.StreamRateLimiter limiter, long estimatedTotalSize)
    {
        this.db = db;
        this.ranges = RocksDBStreamUtils.normalizeRanges(ranges);
        this.limiter = limiter;
        this.outgoingBytes = 0;
        this.estimatedTotalSize = estimatedTotalSize;
        RocksdbThroughputManager.getInstance().registerOutgoingStreamWriter(this);
    }

    public RocksDBStreamWriter(RocksDB db, Collection<Range<Token>> ranges, StreamSession session, long estimatedTotalSize)
    {
        this(db, ranges, StreamManager.getRateLimiter(session.peer), estimatedTotalSize);
        this.session = session;
    }

    public RocksDBStreamWriter(RocksDB db, Collection<Range<Token>> ranges)
    {
        this(db, ranges, new StreamManager.StreamRateLimiter(FBUtilities.getBroadcastAddress()), 0);
    }

    public void write(OutputStream out) throws IOException
    {
        write(out, 0);
    }

    public void write(OutputStream out, int limit) throws IOException
    {
        int streamedPairs = 0;
        long outgoingBytesDelta = 0;
        // Iterate through all possible key-value pairs and send to stream.
        outerloop:
        for (Range<Token> range : ranges) {
            RocksIterator iterator = db.newIterator(new ReadOptions().setReadaheadSize(READ_AHEAD_SIZE));
            try
            {
                iterator.seekToFirst();
                iterator.seek(RowKeyEncoder.encodeToken(range.left));
                byte[] stop = RowKeyEncoder.encodeToken(range.right);
                while (iterator.isValid())
                {
                    byte[] key = iterator.key();
                    byte[] value = iterator.value();
                    if (FBUtilities.compareUnsigned(key, stop) >= 0)
                        break;
                    limiter.acquire(RocksDBStreamUtils.MORE.length + Integer.BYTES * 2 + key.length + value.length);
                    out.write(RocksDBStreamUtils.MORE);
                    out.write(ByteBuffer.allocate(Integer.BYTES).putInt(key.length).array());
                    out.write(key);
                    out.write(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                    out.write(value);
                    outgoingBytes += RocksDBStreamUtils.MORE.length + Integer.BYTES + key.length + Integer.BYTES + value.length;
                    outgoingBytesDelta +=  RocksDBStreamUtils.MORE.length + Integer.BYTES + key.length + Integer.BYTES + value.length;
                    if (outgoingBytesDelta > OUTGOING_BYTES_DELTA_UPDATE_THRESHOLD)
                    {
                        StreamingMetrics.totalOutgoingBytes.inc(outgoingBytesDelta);
                        outgoingBytesDelta = 0;
                        if (session != null) {
                            session.progress("Rocksdb sstable", ProgressInfo.Direction.OUT, outgoingBytes, estimatedTotalSize);
                        }
                    }
                    streamedPairs++;


                    if (limit > 0 && streamedPairs >= limit) {
                        break outerloop;
                    }
                    iterator.next();
                }
            } finally
            {
                iterator.close();
            }
        }
        LOGGER.info("Ranges streamed: " + ranges);
        LOGGER.info("Number of rocksdb entries written: " + streamedPairs);
        out.write(RocksDBStreamUtils.EOF);
    }

    public long getOutgoingBytes()
    {
        return outgoingBytes;
    }

}
