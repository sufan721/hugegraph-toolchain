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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SnapshotMetadataManagerTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void testIndexManifestRoundTripAndChecksum() throws Exception {
        SnapshotStorage storage = new LocalSnapshotStorage(
                temporary.newFolder().toPath());
        SnapshotMetadataManager metadata = new SnapshotMetadataManager(
                storage, "g");
        SnapshotIndex index = metadata.loadIndex();
        Assert.assertEquals("g", index.graph());
        SnapshotManifest manifest = new SnapshotManifest(
                "v1", "g", 1L, SnapshotMode.FULL, null);
        String checksum = metadata.writeManifest(".tmp/v1", manifest);
        metadata.publishVersion(".tmp/v1", "v1");
        index.versions().add(new SnapshotVersion(manifest, checksum));
        metadata.saveIndex(index);
        Assert.assertEquals("v1", metadata.latestValid().backupId());
        Assert.assertEquals("v1", metadata.loadManifest("v1").backupId());
        Assert.assertEquals(SnapshotMetadataManager.checksum(
                "abc".getBytes(StandardCharsets.UTF_8)),
                metadata.checksum(new ByteArrayInputStream(
                        "abc".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void testMissingIndexAndNewVersionDirectory() throws Exception {
        SnapshotStorage storage = new LocalSnapshotStorage(
                temporary.newFolder().toPath());
        SnapshotMetadataManager metadata = new SnapshotMetadataManager(
                storage, "g");
        Assert.assertNull(metadata.latestValid());
        String directory = metadata.newVersionDirectory("v");
        Assert.assertTrue(directory.startsWith(".tmp/version-v-"));
        Assert.assertEquals("versions/v", metadata.versionDirectory("v"));
        Assert.assertEquals("blobs/hash", metadata.blobPath("hash"));
    }

    @Test(expected = IllegalStateException.class)
    public void testManifestRejectsInvalidChecksum() throws Exception {
        SnapshotStorage storage = new LocalSnapshotStorage(
                temporary.newFolder().toPath());
        SnapshotMetadataManager metadata = new SnapshotMetadataManager(
                storage, "g");
        SnapshotManifest manifest = new SnapshotManifest(
                "v", "g", 1L, SnapshotMode.FULL, null);
        manifest.files().add(new SnapshotFile("snapshot/f", 1L, "bad"));
        metadata.writeManifest(".tmp/v", manifest);
    }
}
