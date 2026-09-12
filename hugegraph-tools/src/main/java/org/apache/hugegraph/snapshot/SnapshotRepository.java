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
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.hugegraph.exception.ToolsException;
import org.apache.hugegraph.util.E;

public class SnapshotRepository {

    private static final String SNAPSHOT_PREFIX = "snapshot_";
    private static final String RESTORE_TEMP_PREFIX = ".snapshot-restore-";
    private static final DateTimeFormatter BACKUP_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                             .withZone(ZoneId.systemDefault());
    private static final int BUFFER_SIZE = 8192;
    private static final String KEEP_FILE = ".keep";

    private final SnapshotStorage storage;
    private final SnapshotMetadataManager metadata;

    public SnapshotRepository(SnapshotStorage storage,
                              SnapshotMetadataManager metadata) {
        E.checkArgument(storage != null, "Snapshot storage can't be null");
        E.checkArgument(metadata != null, "Snapshot metadata can't be null");
        this.storage = storage;
        this.metadata = metadata;
    }

    public SnapshotManifest backup(SnapshotStorage serverStorage,
                                   SnapshotMode requestedMode,
                                   int keepNum) {
        E.checkArgument(serverStorage != null, "Server storage can't be null");
        E.checkArgument(requestedMode != null, "Snapshot mode can't be null");
        E.checkArgument(keepNum >= 0, "Snapshot keep number can't be negative");

        SnapshotIndex index = this.metadata.loadIndex();
        SnapshotVersion parent = this.metadata.latestValid();
        SnapshotMode mode = requestedMode;
        if (mode == SnapshotMode.INCREMENTAL && parent == null) {
            mode = SnapshotMode.FULL;
        }

        String backupId = this.newBackupId(index);
        SnapshotManifest manifest = new SnapshotManifest(
                backupId, this.metadata.graph(), System.currentTimeMillis(),
                mode, mode == SnapshotMode.FULL ? null : parent.backupId());
        String tempVersion = this.metadata.newVersionDirectory(backupId);
        Set<String> newBlobs = new LinkedHashSet<>();
        boolean published = false;
        try {
            List<String> files = this.snapshotFiles(serverStorage);
            E.checkState(!files.isEmpty(),
                         "No RocksDB snapshot files were found in '%s'. " +
                         "Please verify --server-data-root",
                         serverStorage.root());
            for (String path : files) {
                String checksum = this.checksum(serverStorage, path);
                long size = serverStorage.size(path);
                String blobPath = this.metadata.blobPath(checksum);
                if (!this.storage.exists(blobPath) &&
                    this.copyToRepository(serverStorage, path, blobPath)) {
                    newBlobs.add(blobPath);
                }
                manifest.files().add(new SnapshotFile(path, size, checksum));
            }
            manifest.files().sort(Comparator.comparing(SnapshotFile::path));
            String manifestChecksum = this.metadata.writeManifest(tempVersion,
                                                                   manifest);
            this.metadata.publishVersion(tempVersion, backupId);
            published = true;

            index.versions().add(new SnapshotVersion(manifest,
                                                      manifestChecksum));
            try {
                this.metadata.saveIndex(index);
            } catch (Throwable e) {
                this.storage.delete(this.metadata.versionDirectory(backupId));
                published = false;
                throw e;
            }
            this.prune(keepNum);
            return manifest;
        } catch (Throwable e) {
            if (!published) {
                this.storage.delete(tempVersion);
                newBlobs.forEach(this.storage::delete);
            }
            throw e;
        }
    }

    public void cleanupServerSnapshot(SnapshotStorage serverStorage,
                                      SnapshotManifest manifest) {
        E.checkArgument(serverStorage != null, "Server storage can't be null");
        E.checkArgument(manifest != null, "Snapshot manifest can't be null");
        Set<String> directories = new LinkedHashSet<>();
        for (SnapshotFile file : manifest.files()) {
            directories.add(this.topDirectory(file.path()));
        }
        directories.forEach(serverStorage::delete);
    }

    public SnapshotManifest select(String backupId) {
        if (backupId == null || backupId.isEmpty() ||
            "latest".equalsIgnoreCase(backupId)) {
            SnapshotVersion latest = this.metadata.latestValid();
            E.checkState(latest != null,
                         "No valid snapshot backup exists in '%s'",
                         this.storage.root());
            return this.metadata.loadManifest(latest.backupId());
        }
        SnapshotVersion version = this.metadata.findVersion(backupId);
        E.checkState(version != null,
                     "Snapshot backup '%s' doesn't exist", backupId);
        return this.metadata.loadManifest(backupId);
    }

    public SnapshotManifest restore(SnapshotStorage serverStorage,
                                    String backupId) {
        E.checkArgument(serverStorage != null, "Server storage can't be null");
        SnapshotManifest manifest = this.select(backupId);
        this.verify(manifest);

        String stageRoot = RESTORE_TEMP_PREFIX + UUID.randomUUID();
        Set<String> snapshotDirectories = new LinkedHashSet<>();
        try {
            for (SnapshotFile file : manifest.files()) {
                String topDirectory = this.topDirectory(file.path());
                snapshotDirectories.add(topDirectory);
                this.copyFromRepository(this.metadata.blobPath(
                                        file.checksum()),
                                        serverStorage,
                                        stageRoot + "/" + file.path());
            }
            E.checkState(!snapshotDirectories.isEmpty(),
                         "Snapshot '%s' contains no files",
                         manifest.backupId());
            for (String directory : snapshotDirectories) {
                serverStorage.replaceDirectory(stageRoot + "/" + directory,
                                               directory);
            }
            return manifest;
        } finally {
            serverStorage.delete(stageRoot);
        }
    }

    public void verify(SnapshotManifest manifest) {
        E.checkArgument(manifest != null, "Snapshot manifest can't be null");
        for (SnapshotFile file : manifest.files()) {
            String blob = this.metadata.blobPath(file.checksum());
            E.checkState(this.storage.exists(blob),
                         "Snapshot data file '%s' is missing", blob);
            E.checkState(this.storage.size(blob) == file.size(),
                         "Snapshot data file '%s' has invalid size", blob);
            E.checkState(file.checksum().equals(
                         this.checksum(this.storage, blob)),
                         "Snapshot data file '%s' has invalid checksum", blob);
        }
    }

    private void prune(int keepNum) {
        if (keepNum <= 0) {
            return;
        }
        SnapshotIndex index = this.metadata.loadIndex();
        List<SnapshotVersion> versions = new ArrayList<>(index.versions());
        if (versions.size() <= keepNum) {
            return;
        }
        versions.sort((left, right) -> {
            int result = Long.compare(left.createdAt(), right.createdAt());
            return result != 0 ? result :
                   left.backupId().compareTo(right.backupId());
        });
        List<SnapshotVersion> keptVersions = versions.subList(
                                             versions.size() - keepNum,
                                             versions.size());
        Set<String> keptIds = new HashSet<>();
        Set<String> retainedBlobs = new HashSet<>();
        for (SnapshotVersion version : keptVersions) {
            keptIds.add(version.backupId());
            for (SnapshotFile file : this.metadata.loadManifest(
                                     version.backupId()).files()) {
                retainedBlobs.add(this.metadata.blobPath(file.checksum()));
            }
        }

        SnapshotIndex retainedIndex = new SnapshotIndex(this.metadata.graph());
        retainedIndex.versions().addAll(keptVersions);
        this.metadata.saveIndex(retainedIndex);

        for (SnapshotVersion version : index.versions()) {
            if (!keptIds.contains(version.backupId())) {
                this.storage.delete(this.metadata.versionDirectory(
                                    version.backupId()));
            }
        }
        for (String blob : this.storage.listFiles(
                                SnapshotMetadataManager.BLOBS_DIR, false)) {
            if (!blob.endsWith(KEEP_FILE) && !retainedBlobs.contains(blob)) {
                this.storage.delete(blob);
            }
        }
    }

    private List<String> snapshotFiles(SnapshotStorage serverStorage) {
        List<String> files = new ArrayList<>();
        for (String path : serverStorage.listFiles(".", true)) {
            if (this.topDirectory(path).startsWith(SNAPSHOT_PREFIX)) {
                files.add(path);
            }
        }
        Collections.sort(files);
        return files;
    }

    private String topDirectory(String path) {
        String normalized = path.replace('\\', '/');
        int separator = normalized.indexOf('/');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private String newBackupId(SnapshotIndex index) {
        String base = BACKUP_ID_FORMAT.format(Instant.now());
        String candidate = base;
        int suffix = 1;
        while (this.metadata.findVersion(candidate) != null ||
               this.storage.exists(this.metadata.versionDirectory(candidate))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private boolean copyToRepository(SnapshotStorage serverStorage,
                                     String source,
                                     String target) {
        String temp = SnapshotMetadataManager.TEMP_DIR + "/blob-" +
                      UUID.randomUUID();
        try (InputStream input = serverStorage.input(source);
             OutputStream output = this.storage.output(temp, false)) {
            this.copy(input, output);
        } catch (IOException e) {
            this.storage.delete(temp);
            throw new ToolsException("Failed to copy snapshot file '%s'",
                                     e, source);
        }
        try {
            this.storage.move(temp, target, false);
            return true;
        } catch (Throwable e) {
            this.storage.delete(temp);
            if (!this.storage.exists(target)) {
                throw e;
            }
            return false;
        }
    }

    private String checksum(SnapshotStorage storage, String path) {
        try (InputStream input = storage.input(path)) {
            return this.metadata.checksum(input);
        } catch (IOException e) {
            throw new ToolsException("Failed to read snapshot file '%s'",
                                     e, path);
        }
    }

    private void copyFromRepository(String source,
                                    SnapshotStorage serverStorage,
                                    String target) {
        try (InputStream input = this.storage.input(source);
             OutputStream output = serverStorage.output(target, false)) {
            this.copy(input, output);
        } catch (IOException e) {
            throw new ToolsException("Failed to restore snapshot file '%s'",
                                     e, source);
        }
    }

    private void copy(InputStream input, OutputStream output)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int length;
        while ((length = input.read(buffer)) >= 0) {
            output.write(buffer, 0, length);
        }
    }
}