/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

package org.apache.hugegraph.structure.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class SnapshotRepo implements AutoCloseable {
    private final Path root;
    private final Path manifests;
    private final Path objects;
    private final ObjectMapper mapper;
    private FileChannel lockChannel;
    private FileLock lock;

    public SnapshotRepo(Path backupDir, String graph) throws IOException {
        this.root = backupDir.resolve(graph).toAbsolutePath().normalize();
        this.manifests = root.resolve("manifests");
        this.objects = root.resolve("objects");
        this.mapper = new ObjectMapper();
        Files.createDirectories(manifests);
        Files.createDirectories(objects);
    }

    public void lock() throws IOException {
        Files.createDirectories(root);
        this.lockChannel = FileChannel.open(root.resolve("snapshot.lock"),
                                             java.nio.file.StandardOpenOption.CREATE,
                                             java.nio.file.StandardOpenOption.WRITE);
        this.lock = lockChannel.tryLock();
        if (lock == null) {
            throw new IOException("Snapshot repository is locked: " + root);
        }
    }

    public long nextVersion() throws IOException {
        long version = 0;
        for (SnapshotManifest manifest : list()) {
            version = Math.max(version, manifest.getVersion());
        }
        return version + 1;
    }

    public List<SnapshotManifest> list() throws IOException {
        List<SnapshotManifest> result = new ArrayList<>();
        if (!Files.exists(manifests)) {
            return result;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(manifests)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                  .sorted().forEach(p -> {
                      try {
                          result.add(mapper.readValue(p.toFile(),
                                                      SnapshotManifest.class));
                      } catch (IOException e) {
                          throw new SnapshotIOException(e);
                      }
                  });
        } catch (SnapshotIOException e) { throw e.exception; }
        result.sort(Comparator.comparingLong(SnapshotManifest::getVersion));
        return result;
    }

    public SnapshotManifest read(long version) throws IOException {
        Path file = manifests.resolve(String.format("%08d.json", version));
        if (!Files.exists(file)) {
            throw new IOException("Manifest not found: " + version);
        }
        return mapper.readValue(file.toFile(), SnapshotManifest.class);
    }

    public List<String> diff(SnapshotManifest previous, SnapshotManifest current) {
        Map<String, SnapshotFileEntry> old = new HashMap<>();
        if (previous != null) {
            for (SnapshotFileEntry entry : previous.getFiles()) {
                old.put(entry.getPath(), entry);
            }
        }
        List<String> delta = new ArrayList<>();
        for (SnapshotFileEntry e : current.getFiles()) {
            SnapshotFileEntry prior = old.get(e.getPath());
            if (prior == null) delta.add(e.getPath());
            else if (prior.getSize() != e.getSize() ||
                     !prior.getCrc32c().equals(e.getCrc32c())) {
                throw new IllegalStateException("Changed file requires full backup: " + e.getPath());
            }
        }
        return delta;
    }

    public void writeManifest(SnapshotManifest manifest) throws IOException {
        Path temp = manifests.resolve(String.format(".%08d.json.tmp", manifest.getVersion()));
        mapper.writeValue(temp.toFile(), manifest);
        Path target = manifests.resolve(String.format("%08d.json", manifest.getVersion()));
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                       StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path objectPath(String relative) {
        Path path = objects.resolve(relative).normalize();
        if (!path.startsWith(objects)) {
            throw new IllegalArgumentException("Invalid snapshot path");
        }
        return path;
    }

    public void putObject(Path source, String relative) throws IOException {
        Path target = objectPath(relative);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            if (Files.size(target) != Files.size(source) ||
                !crc32c(target).equals(crc32c(source))) {
                throw new IOException("Object collision: " + relative);
            }
            return;
        }
        try {
            Files.createLink(target, source);
        } catch (IOException e) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    public void gc(int keepNum) throws IOException {
        List<SnapshotManifest> all = list();
        if (keepNum <= 0 || all.size() <= keepNum) return;
        for (SnapshotManifest m : all.subList(0, all.size() - keepNum)) {
            Files.deleteIfExists(manifests.resolve(String.format("%08d.json", m.getVersion())));
        }
        Set<String> referenced = new HashSet<>();
        for (SnapshotManifest m : list()) {
            for (SnapshotFileEntry entry : m.getFiles()) referenced.add(entry.getPath());
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(objects)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String relative = objects.relativize(path).toString().replace('\\', '/');
                if (!referenced.contains(relative)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new SnapshotIOException(e);
                    }
                }
            });
        } catch (SnapshotIOException e) {
            throw e.exception;
        }
    }

    public void reset() throws IOException {
        this.deleteContents(manifests);
        this.deleteContents(objects);
    }

    public void validate(SnapshotManifest manifest) throws IOException {
        for (SnapshotFileEntry entry : manifest.getFiles()) {
            Path object = objectPath(entry.getPath());
            if (!Files.isRegularFile(object) || Files.size(object) != entry.getSize() ||
                !crc32c(object).equals(entry.getCrc32c())) {
                throw new IOException("Snapshot object is missing or corrupt: " + entry.getPath());
            }
        }
    }

    public void validateChain() throws IOException {
        SnapshotManifest previous = null;
        for (SnapshotManifest manifest : list()) {
            if (previous == null) {
                if (manifest.getParentVersion() != null ||
                    !"full".equals(manifest.getMode())) {
                    throw new IOException("Invalid snapshot baseline: " + manifest.getVersion());
                }
            } else if (!Long.valueOf(previous.getVersion()).equals(manifest.getParentVersion()) ||
                       manifest.getVersion() != previous.getVersion() + 1) {
                throw new IOException("Invalid snapshot chain at version: " +
                                      manifest.getVersion());
            }
            validate(manifest);
            previous = manifest;
        }
    }

    private void deleteContents(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(directory))
                  .forEach(path -> {
                      try {
                          Files.delete(path);
                      } catch (IOException e) {
                          throw new SnapshotIOException(e);
                      }
                  });
        } catch (SnapshotIOException e) {
            throw e.exception;
        }
    }

    public static String crc32c(Path path) throws IOException {
        HashFunction function = Hashing.crc32c();
        com.google.common.hash.Hasher hasher = function.newHasher();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = new byte[8192];
            int length;
            while ((length = in.read(bytes)) >= 0) {
                hasher.putBytes(bytes, 0, length);
            }
        }
        return hasher.hash().toString();
    }

    public static SnapshotFileEntry fingerprint(Path root, Path file) throws IOException {
        String path = root.relativize(file).toString().replace('\\', '/');
        return new SnapshotFileEntry(path, Files.size(file), crc32c(file));
    }

    @Override
    public void close() throws IOException {
        if (lock != null) {
            lock.release();
        }
        if (lockChannel != null) {
            lockChannel.close();
        }
    }

    private static class SnapshotIOException extends RuntimeException {
        private final IOException exception;

        SnapshotIOException(IOException exception) {
            super(exception);
            this.exception = exception;
        }
    }
}
