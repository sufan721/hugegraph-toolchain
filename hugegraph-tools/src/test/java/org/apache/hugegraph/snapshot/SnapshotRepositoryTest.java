/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.snapshot;

import java.io.Closeable;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.hugegraph.exception.ToolsException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SnapshotRepositoryTest {

    private static final String SNAPSHOT_ROOT = "snapshot_rocksdb-data";
    private static final String SNAPSHOT_DIR = SNAPSHOT_ROOT + "/g";
    private static final String OTHER_SNAPSHOT_DIR = SNAPSHOT_ROOT + "/other";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private SnapshotStorage repositoryStorage;
    private SnapshotStorage serverStorage;
    private SnapshotMetadataManager metadata;
    private SnapshotRepository repository;

    @Before
    public void setup() throws Exception {
        Path repositoryRoot = this.temporary.newFolder("repository").toPath();
        Path serverRoot = this.temporary.newFolder("server").toPath();
        this.repositoryStorage = new LocalSnapshotStorage(repositoryRoot);
        this.serverStorage = new LocalSnapshotStorage(serverRoot);
        this.metadata = new SnapshotMetadataManager(this.repositoryStorage,
                                                     "g");
        this.repository = new SnapshotRepository(this.repositoryStorage,
                                                  this.metadata);
    }

    @Test
    public void testFullIncrementalBackupAndRestore() {
        this.writeServerFile("CURRENT", "current-v1");
        this.writeServerFile("000001.sst", "sst-v1");

        SnapshotManifest full = this.repository.backup(
                                this.serverStorage, SnapshotMode.FULL, 0);
        Assert.assertEquals(SnapshotMode.FULL, full.mode());
        Assert.assertNull(full.parentBackupId());
        Assert.assertEquals(2, full.files().size());

        this.serverStorage.delete(SNAPSHOT_DIR + "/000001.sst");
        this.writeServerFile("CURRENT", "current-v2");
        this.writeServerFile("000002.sst", "sst-v2");

        SnapshotManifest incremental = this.repository.backup(
                                       this.serverStorage,
                                       SnapshotMode.INCREMENTAL, 0);
        Assert.assertEquals(SnapshotMode.INCREMENTAL,
                            incremental.mode());
        Assert.assertEquals(full.backupId(),
                            incremental.parentBackupId());
        Assert.assertEquals(2, incremental.files().size());

        Assert.assertNull(this.file(incremental, "000001.sst"));
        Assert.assertNotNull(this.file(incremental, "000002.sst"));

        this.serverStorage.delete(SNAPSHOT_DIR);
        this.writeServerFile("dirty.sst", "dirty");
        SnapshotManifest restored = this.repository.restore(
                                     this.serverStorage,
                                     incremental.backupId());
        Assert.assertEquals(incremental.backupId(),
                            restored.backupId());
        Assert.assertEquals("current-v2",
                            this.readServerFile("CURRENT"));
        Assert.assertEquals("sst-v2",
                            this.readServerFile("000002.sst"));
        Assert.assertFalse(this.serverStorage.exists(
                           SNAPSHOT_DIR + "/000001.sst"));
        Assert.assertFalse(this.serverStorage.exists(
                           SNAPSHOT_DIR + "/dirty.sst"));
    }

    @Test
    public void testRetentionKeepsLatestRestorableVersion() {
        this.writeServerFile("CURRENT", "v1");
        this.repository.backup(this.serverStorage, SnapshotMode.FULL, 0);

        this.writeServerFile("CURRENT", "v2");
        this.repository.backup(this.serverStorage,
                               SnapshotMode.INCREMENTAL, 0);

        this.writeServerFile("CURRENT", "v3");
        SnapshotManifest latest = this.repository.backup(
                                   this.serverStorage,
                                   SnapshotMode.INCREMENTAL, 1);

        Assert.assertEquals(1, this.metadata.loadIndex().versions().size());
        Assert.assertEquals(latest.backupId(),
                            this.metadata.loadIndex().versions().get(0)
                                          .backupId());
        this.serverStorage.delete(SNAPSHOT_DIR);
        this.repository.restore(this.serverStorage, null);
        Assert.assertEquals("v3", this.readServerFile("CURRENT"));
    }

    @Test
    public void testBackupIgnoresOtherGraphSnapshotsInSharedDataRoot() {
        this.writeServerFile("CURRENT", "content");
        this.serverStorage.write("snapshot_rocksdb-data/other/CURRENT",
                                 "other".getBytes(StandardCharsets.UTF_8),
                                 true);
        SnapshotManifest manifest = this.repository.backup(
                                     this.serverStorage, SnapshotMode.FULL, 0);
        Assert.assertEquals(1, manifest.files().size());

        this.writeServerFile("CURRENT", "dirty");
        this.serverStorage.write("snapshot_rocksdb-data/other/CURRENT",
                                 "other-dirty".getBytes(StandardCharsets.UTF_8),
                                 true);
        this.repository.restore(this.serverStorage, manifest.backupId());

        Assert.assertEquals("content", this.readServerFile("CURRENT"));
        Assert.assertEquals("other-dirty", this.readServerFile(
                            "snapshot_rocksdb-data/other", "CURRENT"));
    }

    @Test
    public void testCleanupServerSnapshotWithoutManifest() {
        this.writeServerFile("CURRENT", "content");
        this.serverStorage.write("snapshot_rocksdb-index/g/CURRENT",
                                 "other".getBytes(StandardCharsets.UTF_8),
                                 true);

        this.repository.cleanupServerSnapshot(this.serverStorage);

        Assert.assertFalse(this.serverStorage.exists(SNAPSHOT_DIR));
        Assert.assertFalse(this.serverStorage.exists("snapshot_rocksdb-index/g"));
    }

    @Test
    public void testCleanupKeepsOtherGraphsSnapshot() {
        this.writeServerFile("CURRENT", "content");
        SnapshotManifest manifest = this.repository.backup(
                                     this.serverStorage,
                                     SnapshotMode.FULL, 0);
        this.writeOtherGraphFile("CURRENT", "other");

        this.repository.cleanupServerSnapshot(this.serverStorage, manifest);

        Assert.assertFalse(this.serverStorage.exists(SNAPSHOT_DIR));
        Assert.assertEquals("other", this.readOtherGraphFile("CURRENT"));
        Assert.assertTrue(this.serverStorage.exists(SNAPSHOT_ROOT));
    }

    @Test
    public void testRestoreKeepsOtherGraphsSnapshot() {
        this.writeServerFile("CURRENT", "current-v1");
        SnapshotManifest manifest = this.repository.backup(
                                     this.serverStorage,
                                     SnapshotMode.FULL, 0);
        this.writeServerFile("CURRENT", "dirty");
        this.writeOtherGraphFile("CURRENT", "other");

        this.repository.restore(this.serverStorage, manifest.backupId());

        Assert.assertEquals("current-v1", this.readServerFile("CURRENT"));
        Assert.assertEquals("other", this.readOtherGraphFile("CURRENT"));
    }

    @Test
    public void testRestoreReplacesAllSnapshotDirectoriesOfOneGraph() {
        this.writeServerFile("CURRENT", "graph-v1");
        this.serverStorage.write("snapshot_rocksdb-index/g/CURRENT",
                                 "index-v1".getBytes(StandardCharsets.UTF_8),
                                 true);
        SnapshotManifest manifest = this.repository.backup(
                                    this.serverStorage, SnapshotMode.FULL, 0);

        this.writeServerFile("CURRENT", "dirty-graph");
        this.serverStorage.write("snapshot_rocksdb-index/g/CURRENT",
                                 "dirty-index".getBytes(StandardCharsets.UTF_8),
                                 true);
        this.writeOtherGraphFile("CURRENT", "other");

        this.repository.restore(this.serverStorage, manifest.backupId());

        Assert.assertEquals("graph-v1", this.readServerFile("CURRENT"));
        Assert.assertEquals("index-v1", this.readServerFile(
                            "snapshot_rocksdb-index/g", "CURRENT"));
        Assert.assertEquals("other", this.readOtherGraphFile("CURRENT"));
    }

    @Test
    public void testCorruptBlobIsDetected() {
        this.writeServerFile("CURRENT", "content");
        SnapshotManifest manifest = this.repository.backup(
                                     this.serverStorage,
                                     SnapshotMode.FULL, 0);
        SnapshotFile file = manifest.files().get(0);
        String blob = this.metadata.blobPath(file.checksum());
        this.repositoryStorage.delete(blob);
        this.repositoryStorage.write(blob, "corrupt".getBytes(
                                     StandardCharsets.UTF_8), false);
        try {
            this.repository.verify(manifest);
            Assert.fail("Expected snapshot checksum validation to fail");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("invalid checksum"));
        }
    }

    @Test
    public void testInterruptedBackupCleansTemporaryFilesAndCanRetry()
            throws Exception {
        Path repositoryRoot = this.temporary.newFolder("failing-repository")
                                  .toPath();
        FailingSnapshotStorage storage = new FailingSnapshotStorage(repositoryRoot);
        SnapshotMetadataManager metadata = new SnapshotMetadataManager(storage, "g");
        SnapshotRepository failingRepository = new SnapshotRepository(storage, metadata);
        this.writeServerFile("CURRENT", "content");

        try {
            failingRepository.backup(this.serverStorage, SnapshotMode.FULL, 0);
            Assert.fail("Expected the injected repository write to fail");
        } catch (ToolsException e) {
            Assert.assertTrue(e.getMessage().contains("injected"));
        }
        Assert.assertEquals(1, storage.listFiles(".tmp", true).size());

        storage.failWrites(false);
        SnapshotManifest manifest = failingRepository.backup(this.serverStorage,
                                                              SnapshotMode.FULL, 0);
        Assert.assertNotNull(manifest);
        Assert.assertEquals(1, metadata.loadIndex().versions().size());
    }

    @Test
    public void testRepositoryLockSerializesConcurrentOperations()
            throws Exception {
        LocalSnapshotStorage storage = new LocalSnapshotStorage(
                                       this.temporary.newFolder("locked")
                                                     .toPath());
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        try (Closeable ignored = storage.lock()) {
            Thread thread = new Thread(() -> {
                waiting.countDown();
                try (Closeable lock = storage.lock()) {
                    acquired.countDown();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            thread.start();
            Assert.assertTrue(waiting.await(1, TimeUnit.SECONDS));
            Assert.assertFalse(acquired.await(200, TimeUnit.MILLISECONDS));
        }
        Assert.assertTrue(acquired.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testDataRootSupportsSpacesAndUnicode() throws Exception {
        SnapshotStorage unicodeServer = new LocalSnapshotStorage(
                this.temporary.newFolder("data root 中文").toPath());
        unicodeServer.write("snapshot_rocksdb-data/g/CURRENT",
                            "content".getBytes(StandardCharsets.UTF_8), true);

        SnapshotManifest manifest = this.repository.backup(unicodeServer,
                                                            SnapshotMode.FULL, 0);
        unicodeServer.delete("snapshot_rocksdb-data/g");
        this.repository.restore(unicodeServer, manifest.backupId());

        Assert.assertEquals("content", new String(unicodeServer.read(
                            "snapshot_rocksdb-data/g/CURRENT"),
                            StandardCharsets.UTF_8));
    }

    private SnapshotFile file(SnapshotManifest manifest, String suffix) {
        for (SnapshotFile file : manifest.files()) {
            if (file.path().endsWith(suffix)) {
                return file;
            }
        }
        return null;
    }

    private void writeServerFile(String name, String content) {
        this.serverStorage.write(SNAPSHOT_DIR + "/" + name,
                                 content.getBytes(StandardCharsets.UTF_8),
                                 true);
    }

    private void writeOtherGraphFile(String name, String content) {
        this.serverStorage.write(OTHER_SNAPSHOT_DIR + "/" + name,
                                 content.getBytes(StandardCharsets.UTF_8),
                                 true);
    }

    private String readOtherGraphFile(String name) {
        return this.readServerFile(OTHER_SNAPSHOT_DIR, name);
    }

    private String readServerFile(String name) {
        return this.readServerFile(SNAPSHOT_DIR, name);
    }

    private String readServerFile(String directory, String name) {
        byte[] content = this.serverStorage.read(directory + "/" + name);
        return new String(content, StandardCharsets.UTF_8);
    }

    private static class FailingSnapshotStorage extends LocalSnapshotStorage {

        private boolean failWrites = true;

        FailingSnapshotStorage(Path root) {
            super(root);
        }

        @Override
        public OutputStream output(String path, boolean override) {
            if (this.failWrites && path.startsWith(".tmp/blob-")) {
                throw new ToolsException("injected repository write failure");
            }
            return super.output(path, override);
        }

        public void failWrites(boolean failWrites) {
            this.failWrites = failWrites;
        }
    }
}
