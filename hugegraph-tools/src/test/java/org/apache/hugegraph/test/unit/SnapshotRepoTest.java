/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.test.unit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.hugegraph.structure.snapshot.SnapshotFileEntry;
import org.apache.hugegraph.structure.snapshot.SnapshotManifest;
import org.apache.hugegraph.structure.snapshot.SnapshotRepo;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SnapshotRepoTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testIncrementalManifestAndGc() throws Exception {
        Path backup = folder.newFolder("backup").toPath();
        Path snapshot = folder.newFolder("snapshot").toPath();
        Path first = snapshot.resolve("000001.sst");
        Files.write(first, "first".getBytes(StandardCharsets.UTF_8));

        try (SnapshotRepo repo = new SnapshotRepo(backup, "graph")) {
            repo.lock();
            SnapshotManifest baseline = manifest(1, entry(snapshot, first));
            baseline.setDeltaPaths(Arrays.asList("000001.sst"));
            repo.putObject(first, "000001.sst");
            repo.writeManifest(baseline);

            Path second = snapshot.resolve("000002.sst");
            Files.write(second, "second".getBytes(StandardCharsets.UTF_8));
            SnapshotManifest incremental = manifest(2, entry(snapshot, first),
                                                   entry(snapshot, second));
            incremental.setDeltaPaths(repo.diff(baseline, incremental));
            repo.putObject(second, "000002.sst");
            repo.writeManifest(incremental);

            Assert.assertEquals(Arrays.asList("000002.sst"),
                                incremental.getDeltaPaths());
            repo.validate(incremental);
            repo.gc(1);
            Assert.assertEquals(1, repo.list().size());
            Assert.assertTrue(Files.exists(repo.objectPath("000002.sst")));
        }
    }

    @Test(expected = IllegalStateException.class)
    public void testRejectChangedPathInIncrementalBackup() throws Exception {
        Path backup = folder.newFolder("backup").toPath();
        try (SnapshotRepo repo = new SnapshotRepo(backup, "graph")) {
            SnapshotManifest baseline = manifest(1,
                    new SnapshotFileEntry("000001.sst", 1L, "a"));
            SnapshotManifest incremental = manifest(2,
                    new SnapshotFileEntry("000001.sst", 2L, "b"));
            repo.diff(baseline, incremental);
        }
    }

    private static SnapshotManifest manifest(long version, SnapshotFileEntry... entries) {
        SnapshotManifest manifest = new SnapshotManifest();
        manifest.setGraph("graph");
        manifest.setVersion(version);
        manifest.setFiles(Arrays.asList(entries));
        return manifest;
    }

    private static SnapshotFileEntry entry(Path root, Path file) throws Exception {
        return SnapshotRepo.fingerprint(root, file);
    }
}
