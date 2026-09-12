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

import java.nio.file.Paths;

import org.apache.commons.lang3.StringUtils;
import org.apache.hugegraph.base.Printer;
import org.apache.hugegraph.base.ToolClient;
import org.apache.hugegraph.base.ToolManager;
import org.apache.hugegraph.cmd.SubCommands;
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
        SnapshotManifest manifest = this.repository.backup(
                                    this.serverStorage, this.mode,
                                    this.keepNum);
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
                Paths.get(directory).resolve(this.graph()).toString());
        SnapshotMetadataManager metadata = new SnapshotMetadataManager(
                                            storage, this.graph());
        this.repository = new SnapshotRepository(storage, metadata);
        this.serverStorage = new LocalSnapshotStorage(serverStorageRoot(
                                                      directory,
                                                      serverDataRoot));
        this.snapshotClient = new SnapshotClient(this.client);
    }

    private void checkInitialized() {
        E.checkState(this.repository != null,
                     "Snapshot manager hasn't been initialized");
    }

    private static String serverStorageRoot(String directory,
                                            String serverDataRoot) {
        if (StringUtils.isEmpty(serverDataRoot)) {
            serverDataRoot = System.getenv("HUGEGRAPH_SERVER_DATA_ROOT");
        }
        return StringUtils.isEmpty(serverDataRoot) ? directory : serverDataRoot;
    }
}