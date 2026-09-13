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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
                                                     "hugegraph");
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
    public void testSnapshotSpanningDirectoriesIsRejected() {
        this.writeServerFile("CURRENT", "content");
        this.serverStorage.write("snapshot_other/graph/CURRENT",
                                 "other".getBytes(StandardCharsets.UTF_8),
                                 true);
        SnapshotManifest manifest = this.repository.backup(
                                     this.serverStorage, SnapshotMode.FULL, 0);
        Assert.assertEquals(2, manifest.files().size());

        try {
            this.repository.restore(this.serverStorage, manifest.backupId());
            Assert.fail("Expected the multi-directory snapshot to be rejected");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("multiple directories"));
        }
        Assert.assertEquals("content", this.readServerFile("CURRENT"));
        Assert.assertEquals("other", this.readServerFile("snapshot_other/graph",
                                                         "CURRENT"));
    }

    @Test
    public void testCleanupServerSnapshotWithoutManifest() {
        this.writeServerFile("CURRENT", "content");
        this.serverStorage.write("snapshot_other/graph/CURRENT",
                                 "other".getBytes(StandardCharsets.UTF_8),
                                 true);

        this.repository.cleanupServerSnapshot(this.serverStorage);

        Assert.assertFalse(this.serverStorage.exists(SNAPSHOT_DIR));
        Assert.assertFalse(this.serverStorage.exists("snapshot_other/graph"));
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
}
