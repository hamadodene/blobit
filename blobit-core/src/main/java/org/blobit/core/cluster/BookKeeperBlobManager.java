/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package org.blobit.core.cluster;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.DefaultEnsemblePlacementPolicy;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.commons.pool2.KeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.apache.zookeeper.KeeperException;
import org.blobit.core.api.Configuration;
import org.blobit.core.api.MetadataManager;
import org.blobit.core.api.ObjectManager;
import org.blobit.core.api.ObjectManagerException;
import org.blobit.core.api.PutPromise;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Stores Objects on Apache BookKeeper
 *
 * @author enrico.olivelli
 */
public class BookKeeperBlobManager implements ObjectManager {

    private static final Logger LOG = Logger.getLogger(BookKeeperBlobManager.class.getName());

    private final MetadataManager metadataStorageManager;
    private final BookKeeper bookKeeper;
    private final GenericKeyedObjectPool<String, BucketWriter> writers;
    private final GenericKeyedObjectPool<Long, BucketReader> readers;
    private final int replicationFactor;
    private final long maxBytesPerLedger;
    private final ExecutorService callbacksExecutor;
    private final ExecutorService threadpool = Executors.newSingleThreadExecutor();
    private ConcurrentMap<Long, BucketWriter> activeWriters = new ConcurrentHashMap<>();
    private LedgerLifeCycleManager lifeCycleManager;

    @Override
    public PutPromise put(String bucketId, byte[] data) {
        return put(bucketId, data, 0, data.length);
    }

    @Override
    public PutPromise put(String bucketId, byte[] data, int offset, int len) {
        if (data.length < offset + len || offset < 0 || len < 0) {
            throw new IndexOutOfBoundsException();
        }
        try {
            BucketWriter writer = writers.borrowObject(bucketId);
            try {
                return writer
                    .writeBlob(bucketId, data, offset, len);
            } finally {
                writers.returnObject(bucketId, writer);
            }
        } catch (Exception err) {
            return new PutPromise(null, wrapGenericException(err));
        }
    }

    private <T> CompletableFuture<T> wrapGenericException(Exception err) {
        CompletableFuture<T> error = new CompletableFuture<>();
        error.completeExceptionally(new ObjectManagerException(err));
        return error;
    }

    @Override
    public CompletableFuture<byte[]> get(String bucketId, String id) {
        try {
            BKEntryId entry = BKEntryId.parseId(id);
            BucketReader reader = readers.borrowObject(entry.ledgerId);
            try {
                CompletableFuture<byte[]> result = reader
                    .readObject(entry.firstEntryId, entry.lastEntryId);
                return result;
            } finally {
                readers.returnObject(entry.ledgerId, reader);
            }
        } catch (Exception err) {
            return wrapGenericException(err);
        }
    }

    @Override
    @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION")
    public CompletableFuture<Void> delete(String bucketId, String id) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            BKEntryId bk = BKEntryId.parseId(id);
            metadataStorageManager.deleteObject(bucketId, bk.ledgerId, bk.firstEntryId);
            result.complete(null);
        } catch (ObjectManagerException ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }

    private final class WritersFactory implements KeyedPooledObjectFactory<String, BucketWriter> {

        @Override
        public PooledObject<BucketWriter> makeObject(String bucketId) throws Exception {
            BucketWriter writer = new BucketWriter(bucketId,
                bookKeeper, replicationFactor, maxBytesPerLedger, metadataStorageManager, BookKeeperBlobManager.this);
            activeWriters.put(writer.getId(), writer);
            DefaultPooledObject<BucketWriter> be = new DefaultPooledObject<>(writer);
            return be;
        }

        @Override
        public void destroyObject(String k, PooledObject<BucketWriter> po) throws Exception {
            activeWriters.remove(po.getObject().getId());
            po.getObject().close();
        }

        @Override
        public boolean validateObject(String k, PooledObject<BucketWriter> po) {
            return po.getObject().isValid();
        }

        @Override
        public void activateObject(String k, PooledObject<BucketWriter> po) throws Exception {
        }

        @Override
        public void passivateObject(String k, PooledObject<BucketWriter> po) throws Exception {
        }
    }

    private static final class BucketReaderInstance {

        private int count;
        private boolean retired;
        private final BucketReader reader;

        public BucketReaderInstance(BucketReader reader) {
            super();
            this.reader = reader;
            this.count = 0;
            this.retired = false;
        }

    }

    @SuppressWarnings("serial")
    private static final class LambdaWrapperException extends RuntimeException {
        public LambdaWrapperException(Throwable cause) {
            super(cause);
        }
    }

    private final class ReadersFactory implements KeyedPooledObjectFactory<Long, BucketReader> {

        private final Lock lock = new ReentrantLock();
        private final ConcurrentMap<Long,BucketReaderInstance> instances = new ConcurrentHashMap<>();

        @Override
        public PooledObject<BucketReader> makeObject(Long ledgerId) throws Exception {

            BucketReaderInstance instance;

            while(true) {

                try {
                    instance = instances.computeIfAbsent(ledgerId, k -> {

                        /* Serialize reader creations */
                        lock.lock();

                        BucketReader reader;
                        try {
                            reader = new BucketReader(ledgerId, bookKeeper, BookKeeperBlobManager.this);
                        } catch (ObjectManagerException e) {
                            throw new LambdaWrapperException(e);
                        } finally {
                            lock.unlock();
                        }

                        return new BucketReaderInstance(reader);

                    });

                } catch (LambdaWrapperException e) {
                    throw (Exception) e.getCause();
                }

                synchronized (instance) {
                    if (!instance.retired) {
                        instance.count++;
                        break;
                    }
                }
            }

            return new DefaultPooledObject<>(instance.reader);
        }

        @Override
        public void destroyObject(Long ledgerId, PooledObject<BucketReader> po) throws Exception {

            BucketReaderInstance instance = instances.get(ledgerId);

            synchronized (instance) {
                if (--instance.count == 0) {
                    instance.retired = true;
                    instances.remove(ledgerId, instance);

                    instance.reader.close();
                }
            }
        }

        @Override
        public boolean validateObject(Long k, PooledObject<BucketReader> po) {
            return po.getObject().isValid();
        }

        @Override
        public void activateObject(Long k, PooledObject<BucketReader> po) throws Exception {

        }

        @Override
        public void passivateObject(Long k, PooledObject<BucketReader> po) throws Exception {

        }
    }

    public BookKeeperBlobManager(Configuration configuration, MetadataManager metadataStorageManager) throws ObjectManagerException {
        try {
            this.lifeCycleManager = new LedgerLifeCycleManager(metadataStorageManager, this);
            this.replicationFactor = configuration.getReplicationFactor();
            this.maxBytesPerLedger = configuration.getMaxBytesPerLedger();
            this.metadataStorageManager = metadataStorageManager;
            int concurrentWrites = configuration.getConcurrentWriters();
            this.callbacksExecutor = Executors.newFixedThreadPool(concurrentWrites);
            ClientConfiguration clientConfiguration = new ClientConfiguration();
            clientConfiguration.setThrottleValue(0);
            clientConfiguration.setEnsemblePlacementPolicy(DefaultEnsemblePlacementPolicy.class);
            clientConfiguration.setUseV2WireProtocol(true);
            for (String key : configuration.keys()) {
                if (key.startsWith("bookkeeper.")) {
                    String rawKey = key.substring("bookkeeper.".length());
                    clientConfiguration.setProperty(rawKey, configuration.getProperty(key));
                }
            }
            clientConfiguration.setZkServers(configuration.getZookkeeperUrl());
            GenericKeyedObjectPoolConfig configWriters = new GenericKeyedObjectPoolConfig();
            configWriters.setMaxTotalPerKey(concurrentWrites);
            configWriters.setTestOnReturn(true);
            configWriters.setBlockWhenExhausted(true);
            this.writers = new GenericKeyedObjectPool<>(new WritersFactory(), configWriters);

            GenericKeyedObjectPoolConfig configReaders = new GenericKeyedObjectPoolConfig();
            configReaders.setMaxTotalPerKey(concurrentWrites);
            configReaders.setTestOnReturn(true);
            configReaders.setBlockWhenExhausted(true);

            this.readers = new GenericKeyedObjectPool<>(new ReadersFactory(), configReaders);

            this.bookKeeper = BookKeeper
                .forConfig(clientConfiguration)
                .build();
        } catch (IOException | InterruptedException | KeeperException ex) {
            throw new ObjectManagerException(ex);
        }
    }

    public boolean dropLedger(long idledger) throws ObjectManagerException {
        if (activeWriters.containsKey(idledger)) {
            return false;
        }
        try {
            LOG.log(Level.INFO, "dropping ledger {0}", idledger);
            bookKeeper.deleteLedger(idledger);
            return true;
        } catch (BKException.BKNoSuchLedgerExistsException ok) {
            return true;
        } catch (BKException err) {
            throw new ObjectManagerException(err);
        } catch (InterruptedException err) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void start() {

        lifeCycleManager.start();
    }

    @Override
    public void close() {
        writers.close();
        readers.close();
        lifeCycleManager.close();

        if (bookKeeper != null) {
            try {
                bookKeeper.close();
            } catch (BKException | InterruptedException err) {
                LOG.log(Level.SEVERE, "Error while closing BK", err);
            }
        }
        threadpool.shutdown();
        callbacksExecutor.shutdown();

    }

    Future<?> scheduleWriterDisposal(BucketWriter writer) {
        return threadpool.submit(() -> {
            writer.releaseResources();
        });
    }

    Future<?> scheduleReaderDisposal(BucketReader reader) {
        return threadpool.submit(() -> {
            reader.releaseResources();
        });
    }

    public ExecutorService getCallbacksExecutor() {
        return callbacksExecutor;
    }

    @Override
    public MetadataManager getMetadataStorageManager() {
        return metadataStorageManager;
    }

    @Override
    public void gc() {
        lifeCycleManager.run();
    }

    @Override
    public void gc(String bucketId) {
        try {
            lifeCycleManager.gcBucket(bucketId);
        } catch (ObjectManagerException ex) {
            Logger.getLogger(BookKeeperBlobManager.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void closeAllActiveWriters() {
        writers.clear();
    }

}
