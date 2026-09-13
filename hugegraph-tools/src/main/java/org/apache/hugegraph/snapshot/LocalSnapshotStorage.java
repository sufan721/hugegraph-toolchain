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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.hugegraph.exception.ToolsException;
import org.apache.hugegraph.util.E;

public class LocalSnapshotStorage implements SnapshotStorage {

    private static final String LOCK_FILE = ".snapshot.lock";

    private final Path root;

    public LocalSnapshotStorage(String root) {
        this(Paths.get(root));
    }

    public LocalSnapshotStorage(Path root) {
        E.checkArgument(root != null, "Snapshot storage root can't be null");
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String root() {
        return this.root.toString();
    }

    @Override
    public void initialize() {
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new ToolsException("Failed to initialize snapshot storage '%s'",
                                     e, this.root);
        }
    }

    @Override
    public Closeable lock() {
        Path lockFile = this.root.resolve(LOCK_FILE);
        this.ensureParent(lockFile);
        try {
            FileChannel channel = FileChannel.open(lockFile,
                                                   StandardOpenOption.CREATE,
                                                   StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.lock();
                return new StorageLock(channel, lock);
            } catch (Throwable e) {
                try {
                    channel.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new ToolsException("Failed to lock snapshot storage '%s'",
                                     e, this.root);
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(this.resolve(path));
    }

    @Override
    public long size(String path) {
        try {
            return Files.size(this.resolve(path));
        } catch (IOException e) {
            throw new ToolsException("Failed to read file size '%s'",
                                     e, path);
        }
    }

    @Override
    public List<String> listFiles(String directory, boolean recursive) {
        Path base = this.resolve(directory);
        if (!Files.exists(base)) {
            return Collections.emptyList();
        }
        E.checkState(Files.isDirectory(base),
                     "Snapshot path '%s' is not a directory", base);
        int depth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> paths = Files.walk(base, depth)) {
            return paths.filter(Files::isRegularFile)
                        .map(this::relativePath)
                        .sorted()
                        .collect(Collectors.toList());
        } catch (IOException e) {
            throw new ToolsException("Failed to list snapshot files in '%s'",
                                     e, base);
        }
    }

    @Override
    public InputStream input(String path) {
        try {
            return Files.newInputStream(this.resolve(path),
                                        StandardOpenOption.READ);
        } catch (IOException e) {
            throw new ToolsException("Failed to open snapshot file '%s'",
                                     e, path);
        }
    }

    @Override
    public OutputStream output(String path, boolean override) {
        Path target = this.resolve(path);
        this.ensureParent(target);
        try {
            return Files.newOutputStream(target, options(override));
        } catch (IOException e) {
            throw new ToolsException("Failed to create snapshot file '%s'",
                                     e, path);
        }
    }

    @Override
    public byte[] read(String path) {
        try {
            return Files.readAllBytes(this.resolve(path));
        } catch (IOException e) {
            throw new ToolsException("Failed to read snapshot file '%s'",
                                     e, path);
        }
    }

    @Override
    public void write(String path, byte[] content, boolean override) {
        Path target = this.resolve(path);
        this.ensureParent(target);
        try {
            Files.write(target, content, options(override));
        } catch (IOException e) {
            throw new ToolsException("Failed to write snapshot file '%s'",
                                     e, path);
        }
    }

    @Override
    public void move(String sourcePath, String targetPath, boolean replace) {
        Path source = this.resolve(sourcePath);
        Path target = this.resolve(targetPath);
        this.ensureParent(target);
        try {
            this.move(source, target, replace);
        } catch (IOException e) {
            throw new ToolsException("Failed to move '%s' to '%s'",
                                     e, sourcePath, targetPath);
        }
    }

    @Override
    public void delete(String path) {
        Path target = this.resolve(path);
        if (target.equals(this.root) || !Files.exists(target)) {
            return;
        }
        try {
            if (Files.isDirectory(target)) {
                List<Path> paths;
                try (Stream<Path> stream = Files.walk(target)) {
                    paths = stream.sorted(Comparator.reverseOrder())
                                  .collect(Collectors.toList());
                }
                for (Path item : paths) {
                    Files.deleteIfExists(item);
                }
            } else {
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            throw new ToolsException("Failed to delete snapshot path '%s'",
                                     e, path);
        }
    }

    @Override
    public void replaceDirectory(String sourcePath, String targetPath) {
        Path source = this.resolve(sourcePath);
        Path target = this.resolve(targetPath);
        E.checkState(!source.equals(this.root),
                     "The snapshot source directory can't be the data root");
        E.checkState(!target.equals(this.root),
                     "The snapshot target directory can't be the data root");
        E.checkState(Files.isDirectory(source),
                     "The staged snapshot directory '%s' doesn't exist", source);

        Path backup = this.resolve(".tmp/.replace-" + UUID.randomUUID());
        boolean movedOld = false;
        boolean movedNew = false;
        boolean preserveBackup = false;
        try {
            this.ensureParent(backup);
            if (Files.exists(target)) {
                this.move(target, backup, false);
                movedOld = true;
            }
            this.move(source, target, false);
            movedNew = true;
            if (movedOld) {
                this.delete(this.relativePath(backup));
            }
        } catch (Exception e) {
            if (movedNew && Files.exists(target)) {
                try {
                    this.delete(this.relativePath(target));
                } catch (Exception deleteError) {
                    e.addSuppressed(deleteError);
                }
            }
            if (movedOld && Files.exists(backup)) {
                try {
                    this.move(backup, target, false);
                } catch (IOException rollbackError) {
                    preserveBackup = true;
                    e.addSuppressed(rollbackError);
                }
            }
            throw new ToolsException("Failed to replace snapshot directory '%s'",
                                     e, targetPath);
        } finally {
            if (!preserveBackup && Files.exists(backup)) {
                this.delete(this.relativePath(backup));
            }
        }
    }

    private static final class StorageLock implements Closeable {

        private final FileChannel channel;
        private final FileLock lock;

        private StorageLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                this.lock.release();
            } finally {
                this.channel.close();
            }
        }
    }

    private Path resolve(String path) {
        E.checkArgument(path != null && !path.isEmpty(),
                        "Snapshot path can't be null or empty");
        Path candidate = Paths.get(path);
        E.checkArgument(!candidate.isAbsolute(),
                        "Snapshot path must be relative, but got '%s'", path);
        Path resolved = this.root.resolve(candidate).normalize();
        E.checkState(resolved.startsWith(this.root),
                     "Snapshot path '%s' escapes storage root '%s'",
                     path, this.root);
        return resolved;
    }

    private String relativePath(Path path) {
        return this.root.relativize(path).toString().replace('\\', '/');
    }

    private void ensureParent(Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new ToolsException("Failed to create parent directory for '%s'",
                                     e, target);
        }
    }

    private void move(Path source, Path target, boolean replace)
            throws IOException {
        try {
            Files.move(source, target, moveOptions(replace));
        } catch (AtomicMoveNotSupportedException e) {
            if (replace) {
                Files.move(source, target,
                           StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private static StandardOpenOption[] options(boolean override) {
        return override ? new StandardOpenOption[]{
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        } : new StandardOpenOption[]{
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
        };
    }

    private static CopyOption[] moveOptions(boolean replace) {
        return replace ? new CopyOption[]{
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        } : new CopyOption[]{StandardCopyOption.ATOMIC_MOVE};
    }
}
