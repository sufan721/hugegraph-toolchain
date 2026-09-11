/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

package org.apache.hugegraph.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.hugegraph.base.ToolClient;
import org.apache.hugegraph.base.ToolManager;
import org.apache.hugegraph.cmd.SubCommands;
import org.apache.hugegraph.structure.snapshot.SnapshotFileEntry;
import org.apache.hugegraph.structure.snapshot.SnapshotManifest;
import org.apache.hugegraph.structure.snapshot.SnapshotRepo;

public class SnapshotBackupManager extends ToolManager {
    public SnapshotBackupManager(ToolClient.ConnectionInfo info) {
        super(info, "snapshot-backup");
    }

    public SnapshotManifest backup(String graph, SubCommands.SnapshotBackup cmd)
                                   throws IOException {
        Path source = Paths.get(cmd.snapshotDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IOException("Snapshot directory not found: " + source);
        }
        if (!"full".equalsIgnoreCase(cmd.mode) &&
            !"incremental".equalsIgnoreCase(cmd.mode)) {
            throw new IllegalArgumentException("Snapshot mode must be full or incremental");
        }
        try (SnapshotRepo repo = new SnapshotRepo(Paths.get(cmd.directory), graph)) {
            repo.lock();
            this.client.graphs().createSnapshot(graph);
            List<SnapshotFileEntry> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        files.add(SnapshotRepo.fingerprint(source, path));
                    } catch (IOException e) {
                        throw new SnapshotIOException(e);
                    }
                });
            } catch (SnapshotIOException e) {
                throw e.exception;
            }
            SnapshotManifest previous = null;
            List<SnapshotManifest> versions = repo.list();
            if ("full".equalsIgnoreCase(cmd.mode)) {
                repo.reset();
                versions.clear();
            }
            if (!versions.isEmpty()) {
                previous = versions.get(versions.size() - 1);
            }
            SnapshotManifest manifest = new SnapshotManifest();
            manifest.setGraph(graph);
            manifest.setVersion(repo.nextVersion());
            manifest.setBackupId(String.format("%s-%04d",
                                 OffsetDateTime.now().toString().replace(':', '-'),
                                 manifest.getVersion()));
            manifest.setMode(previous == null || "full".equalsIgnoreCase(cmd.mode) ?
                             "full" : "incremental");
            manifest.setParentVersion(previous == null ? null : previous.getVersion());
            manifest.setCreatedAt(OffsetDateTime.now().toString());
            manifest.setSnapshotDir(source.toString());
            manifest.setFiles(files);
            List<String> delta = "full".equals(manifest.getMode()) ?
                                 files.stream().map(SnapshotFileEntry::getPath)
                                      .collect(java.util.stream.Collectors.toList()) :
                                 repo.diff(previous, manifest);
            manifest.setDeltaPaths(delta);
            SnapshotManifest.SnapshotStats stats = manifest.getStats();
            stats.setTotalFiles(files.size());
            stats.setTotalBytes(files.stream().mapToLong(SnapshotFileEntry::getSize).sum());
            stats.setDeltaFiles(delta.size());
            stats.setDeltaBytes(files.stream().filter(e -> delta.contains(e.getPath()))
                                    .mapToLong(SnapshotFileEntry::getSize).sum());
            for (String path : delta) {
                repo.putObject(source.resolve(path), path);
            }
            repo.writeManifest(manifest);
            if (cmd.keepNum > 0) {
                repo.gc(cmd.keepNum);
            }
            return manifest;
        }
    }

    private static class SnapshotIOException extends RuntimeException {
        private final IOException exception;

        SnapshotIOException(IOException exception) {
            this.exception = exception;
        }
    }
}
