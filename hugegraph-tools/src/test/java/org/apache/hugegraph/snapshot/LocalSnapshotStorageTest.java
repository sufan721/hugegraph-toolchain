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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalSnapshotStorageTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void testWriteRejectsSymbolicLinkPathComponent() throws Exception {
        Path root = this.temporary.newFolder("root").toPath();
        Path outside = this.temporary.newFolder("outside").toPath();
        Path link = root.resolve("linked-directory");
        this.createSymbolicLink(link, outside);
        LocalSnapshotStorage storage = new LocalSnapshotStorage(root);

        try {
            storage.write("linked-directory/escaped", "data".getBytes(
                          StandardCharsets.UTF_8), true);
            Assert.fail("Expected a symbolic-link storage path to be rejected");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("symbolic link"));
        }
        Assert.assertFalse(Files.exists(outside.resolve("escaped")));
    }

    @Test
    public void testInitializeRejectsSymbolicLinkBeforeCreatingRoot()
            throws Exception {
        Path outside = this.temporary.newFolder("outside").toPath();
        Path link = this.temporary.getRoot().toPath().resolve("linked-root");
        this.createSymbolicLink(link, outside);
        Path root = link.resolve("snapshot-root");

        try {
            new LocalSnapshotStorage(root).initialize();
            Assert.fail("Expected a symbolic-link storage root to be rejected");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("symbolic link"));
        }
        Assert.assertFalse(Files.exists(outside.resolve("snapshot-root")));
    }

    private void createSymbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException e) {
            Assume.assumeNoException(e);
        } catch (IOException e) {
            Assume.assumeNoException(e);
        }
    }
}
