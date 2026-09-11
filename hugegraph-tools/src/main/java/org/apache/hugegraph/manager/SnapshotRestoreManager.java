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

package org.apache.hugegraph.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.hugegraph.base.ToolClient;
import org.apache.hugegraph.base.ToolManager;
import org.apache.hugegraph.cmd.SubCommands;
import org.apache.hugegraph.structure.snapshot.SnapshotFileEntry;
import org.apache.hugegraph.structure.snapshot.SnapshotManifest;
import org.apache.hugegraph.structure.snapshot.SnapshotRepo;

public class SnapshotRestoreManager extends ToolManager {
    public SnapshotRestoreManager(ToolClient.ConnectionInfo info) {
        super(info, "snapshot-restore");
    }

    public SnapshotManifest restore(String graph, SubCommands.SnapshotRestore cmd)
                                    throws IOException {
        try (SnapshotRepo repo = new SnapshotRepo(Paths.get(cmd.directory), graph)) {
            SnapshotManifest selected = null;
            if (cmd.version != null) {
                selected = repo.read(cmd.version);
            } else {
                for (SnapshotManifest manifest : repo.list()) {
                    if (cmd.backupId == null || cmd.backupId.equals(manifest.getBackupId())) {
                        selected = manifest;
                    }
                }
            }
            if (selected == null) {
                throw new IOException("Snapshot version not found");
            }
            repo.validateChain();
            repo.validate(selected);
            Path target = Paths.get(cmd.snapshotDir).toAbsolutePath().normalize();
            Files.createDirectories(target);
            try (java.util.stream.Stream<Path> stream = Files.walk(target)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .filter(path -> !path.equals(target)).forEach(path -> {
                          try {
                              Files.deleteIfExists(path);
                          } catch (IOException e) {
                              throw new SnapshotIOException(e);
                          }
                      });
            } catch (SnapshotIOException e) {
                throw e.exception;
            }
            for (SnapshotFileEntry entry : selected.getFiles()) {
                Path out = target.resolve(entry.getPath()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("Invalid manifest path");
                }
                Files.createDirectories(out.getParent());
                Files.copy(repo.objectPath(entry.getPath()), out,
                           java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            this.client.graphs().resumeSnapshot(graph);
            return selected;
        }
    }

    private static class SnapshotIOException extends RuntimeException {
        private final IOException exception;

        SnapshotIOException(IOException exception) {
            this.exception = exception;
        }
    }
}
