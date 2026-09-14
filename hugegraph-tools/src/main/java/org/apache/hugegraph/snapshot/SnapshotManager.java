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
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;
import org.apache.hugegraph.base.Printer;
import org.apache.hugegraph.base.ToolClient;
import org.apache.hugegraph.base.ToolManager;
import org.apache.hugegraph.cmd.SubCommands;
import org.apache.hugegraph.exception.ToolsException;
import org.apache.hugegraph.util.E;

public class SnapshotManager extends ToolManager {

    private SnapshotClient snapshotClient;
    private SnapshotRepository repository;
    private SnapshotStorage serverStorage;
    private SnapshotMode mode;
    private int keepNum;
    private String backupId;

    public SnapshotManager(ToolClient.ConnectionInfo info) {
        super(info, "snapshot");
    }

    public void init(SubCommands.SnapshotBackup command) {
        E.checkArgument(command != null, "Snapshot backup command can't be null");
        this.init(command.directory(), command.serverDataRoot());
        this.mode = SnapshotMode.from(command.mode());
        this.keepNum = command.keepNum();
    }

    public void init(SubCommands.SnapshotRestore command) {
        E.checkArgument(command != null,
                        "Snapshot restore command can't be null");
        this.init(command.directory(), command.serverDataRoot());
        this.backupId = command.backupId();
    }

    public void backup() {
        this.checkInitialized();
        Printer.print("Graph '%s' start snapshot backup in mode '%s'",
                      this.graph(), this.mode.value());
        this.snapshotClient.createSnapshot();
        SnapshotManifest manifest;
        try {
            manifest = this.repository.backup(this.serverStorage, this.mode,
                                              this.keepNum);
        } catch (Throwable e) {
            this.cleanupServerSnapshot(e);
            throw e;
        }
        this.repository.cleanupServerSnapshot(this.serverStorage, manifest);
        Printer.print("Snapshot backup '%s' finished: %d files, %d bytes",
                      manifest.backupId(), manifest.files().size(),
                      manifest.totalSize());
    }

    public void restore() {
        this.checkInitialized();
        Printer.print("Graph '%s' start snapshot restore", this.graph());
        SnapshotManifest manifest = this.repository.restore(this.serverStorage,
                                                             this.backupId);
        this.snapshotClient.resumeSnapshot();
        Printer.print("Snapshot restore '%s' finished: %d files, %d bytes",
                      manifest.backupId(), manifest.files().size(),
                      manifest.totalSize());
    }

    private void init(String directory, String serverDataRoot) {
        SnapshotStorage storage = new LocalSnapshotStorage(
                                  graphRoot(directory, this.graph()));
        SnapshotMetadataManager metadata = new SnapshotMetadataManager(
                                            storage, this.graph());
        this.repository = new SnapshotRepository(storage, metadata);
        this.serverStorage = new LocalSnapshotStorage(
                             serverStorageRoot(serverDataRoot));
        this.snapshotClient = new SnapshotClient(this.client);
    }

    /**
     * Resolve the directory which stores the snapshots of a graph, the graph
     * name can't be an absolute path or escape from the backup directory.
     */
    static Path graphRoot(String directory, String graph) {
        E.checkArgument(StringUtils.isNotEmpty(graph),
                        "Graph name can't be null or empty");
        Path root = Paths.get(directory).toAbsolutePath().normalize();
        Path graphRoot = root.resolve(graph).normalize();
        E.checkArgument(graphRoot.startsWith(root) && !graphRoot.equals(root),
                        "Invalid graph name '%s', it must resolve to a " +
                        "directory inside the snapshot directory '%s'",
                        graph, root);
        try {
            LocalSnapshotStorage.checkNoSymbolicLinks(root);
            java.nio.file.Files.createDirectories(root);
            LocalSnapshotStorage.checkNoSymbolicLinks(graphRoot);
            E.checkState(graphRoot.getParent().toRealPath().startsWith(
                         root.toRealPath()),
                         "Invalid graph name '%s', it resolves outside " +
                         "the snapshot directory '%s'", graph, root);
        } catch (IOException e) {
            throw new ToolsException("Failed to resolve snapshot directory '%s'",
                                     e, directory);
        }
        return graphRoot;
    }

    private void cleanupServerSnapshot(Throwable cause) {
        try {
            this.repository.cleanupServerSnapshot(this.serverStorage);
        } catch (Throwable cleanupError) {
            cause.addSuppressed(cleanupError);
        }
    }

    private void checkInitialized() {
        E.checkState(this.repository != null,
                     "Snapshot manager hasn't been initialized");
    }

    private static String serverStorageRoot(String serverDataRoot) {
        if (StringUtils.isEmpty(serverDataRoot)) {
            serverDataRoot = System.getenv("HUGEGRAPH_SERVER_DATA_ROOT");
        }
        E.checkArgument(StringUtils.isNotEmpty(serverDataRoot),
                        "The server data root is required to locate the " +
                        "RocksDB snapshot files, please specify " +
                        "--server-data-root or set HUGEGRAPH_SERVER_DATA_ROOT");
        return serverDataRoot;
    }
}
