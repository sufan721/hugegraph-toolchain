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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.hugegraph.exception.ToolsException;
import org.apache.hugegraph.util.E;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SnapshotMetadataManager {

    public static final String INDEX_FILE = "index.json";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String VERSIONS_DIR = "versions";
    public static final String BLOBS_DIR = "blobs";
    public static final String TEMP_DIR = ".tmp";

    private static final int BUFFER_SIZE = 8192;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ObjectMapper MAPPER = mapper();

    private final SnapshotStorage storage;
    private final String graph;

    public SnapshotMetadataManager(SnapshotStorage storage, String graph) {
        E.checkArgument(storage != null, "Snapshot storage can't be null");
        E.checkArgument(graph != null && !graph.isEmpty(),
                        "Graph name can't be null or empty");
        this.storage = storage;
        this.graph = graph;
        this.initialize();
    }

    public String graph() {
        return this.graph;
    }

    public SnapshotIndex loadIndex() {
        if (!this.storage.exists(INDEX_FILE)) {
            return new SnapshotIndex(this.graph);
        }
        SnapshotIndex index = this.deserialize(this.storage.read(INDEX_FILE),
                                               SnapshotIndex.class);
        E.checkState(index.formatVersion() == SnapshotIndex.FORMAT_VERSION,
                     "Unsupported snapshot index format '%s'",
                     index.formatVersion());
        E.checkState(this.graph.equals(index.graph()),
                     "Snapshot index graph '%s' doesn't match '%s'",
                     index.graph(), this.graph);
        if (index.versions() == null) {
            index.versions(new ArrayList<SnapshotVersion>());
        }
        return index;
    }

    public void saveIndex(SnapshotIndex index) {
        index.graph(this.graph);
        index.formatVersion(SnapshotIndex.FORMAT_VERSION);
        this.atomicWrite(INDEX_FILE, this.serialize(index));
    }

    public String writeManifest(String versionDirectory,
                                SnapshotManifest manifest) {
        this.validateManifest(manifest);
        byte[] content = this.serialize(manifest);
        this.storage.write(this.path(versionDirectory, MANIFEST_FILE),
                           content, false);
        return checksum(content);
    }

    public void publishVersion(String tempDirectory, String backupId) {
        String target = this.versionDirectory(backupId);
        E.checkState(!this.storage.exists(target),
                     "Snapshot version '%s' already exists", backupId);
        this.storage.move(tempDirectory, target, false);
    }

    public SnapshotManifest loadManifest(String backupId) {
        String path = this.path(this.versionDirectory(backupId), MANIFEST_FILE);
        byte[] content = this.storage.read(path);
        SnapshotManifest manifest = this.deserialize(content,
                                                      SnapshotManifest.class);
        this.validateManifest(manifest);
        E.checkState(backupId.equals(manifest.backupId()),
                     "Snapshot manifest id '%s' doesn't match '%s'",
                     manifest.backupId(), backupId);
        SnapshotVersion version = this.findVersion(backupId);
        if (version != null && version.manifestChecksum() != null) {
            E.checkState(version.manifestChecksum().equals(checksum(content)),
                         "Snapshot manifest checksum mismatch for '%s'",
                         backupId);
        }
        return manifest;
    }

    public SnapshotVersion findVersion(String backupId) {
        for (SnapshotVersion version : this.loadIndex().versions()) {
            if (version.backupId().equals(backupId)) {
                return version;
            }
        }
        return null;
    }

    public SnapshotVersion latestValid() {
        List<SnapshotVersion> versions =
                new ArrayList<>(this.loadIndex().versions());
        if (versions.isEmpty()) {
            return null;
        }
        versions.sort(SnapshotVersion.BY_CREATION);
        return versions.get(versions.size() - 1);
    }

    public String newVersionDirectory(String backupId) {
        String directory = TEMP_DIR + "/version-" + backupId + "-" +
                           UUID.randomUUID();
        E.checkState(!this.storage.exists(directory),
                     "Temporary snapshot directory '%s' already exists",
                     directory);
        return directory;
    }

    public String versionDirectory(String backupId) {
        return VERSIONS_DIR + "/" + backupId;
    }

    public String blobPath(String checksum) {
        return BLOBS_DIR + "/" + checksum;
    }

    public String checksum(InputStream input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, length);
            }
            return hex(digest.digest());
        } catch (IOException e) {
            throw new ToolsException("Failed to calculate snapshot checksum",
                                     e);
        } catch (NoSuchAlgorithmException e) {
            throw new ToolsException("SHA-256 is not available", e);
        }
    }

    public static String checksum(byte[] content) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new ToolsException("SHA-256 is not available", e);
        }
    }

    private void initialize() {
        this.storage.initialize();
        this.createDirectories(VERSIONS_DIR);
        this.createDirectories(BLOBS_DIR);
        this.createDirectories(TEMP_DIR);
    }

    private void createDirectories(String directory) {
        this.storage.write(this.path(directory, ".keep"), new byte[0], true);
    }

    private void atomicWrite(String target, byte[] content) {
        String temp = TEMP_DIR + "/index-" + UUID.randomUUID() + ".json";
        this.storage.write(temp, content, false);
        this.storage.move(temp, target, true);
    }

    private void validateManifest(SnapshotManifest manifest) {
        E.checkNotNull(manifest, "Snapshot manifest");
        E.checkState(manifest.formatVersion() ==
                     SnapshotManifest.FORMAT_VERSION,
                     "Unsupported snapshot manifest format '%s'",
                     manifest.formatVersion());
        E.checkState(manifest.backupId() != null &&
                     !manifest.backupId().isEmpty(),
                     "Snapshot manifest backup id can't be empty");
        E.checkState(this.graph.equals(manifest.graph()),
                     "Snapshot manifest graph '%s' doesn't match '%s'",
                     manifest.graph(), this.graph);
        E.checkNotNull(manifest.mode(), "Snapshot manifest mode");
        E.checkNotNull(manifest.files(), "Snapshot manifest files");
        Set<String> paths = new HashSet<>();
        for (SnapshotFile file : manifest.files()) {
            E.checkNotNull(file, "Snapshot manifest file");
            E.checkState(file.path() != null && !file.path().isEmpty(),
                         "Snapshot file path can't be empty");
            E.checkState(paths.add(file.path()),
                         "Duplicated snapshot file path '%s'", file.path());
            E.checkState(file.size() >= 0,
                         "Invalid snapshot file size '%s'", file.size());
            E.checkState(file.checksum() != null &&
                         file.checksum().matches("[0-9a-f]{64}"),
                         "Invalid snapshot file checksum '%s'",
                         file.checksum());
        }
    }

    private byte[] serialize(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new ToolsException("Failed to serialize snapshot metadata",
                                     e);
        }
    }

    private <T> T deserialize(byte[] content, Class<T> clazz) {
        try {
            return MAPPER.readValue(content, clazz);
        } catch (IOException e) {
            throw new ToolsException("Failed to deserialize snapshot metadata",
                                     e);
        }
    }

    private String path(String parent, String child) {
        return parent == null || parent.isEmpty() ? child : parent + "/" + child;
    }

    private static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setVisibility(PropertyAccessor.FIELD,
                             JsonAutoDetect.Visibility.ANY);
        return mapper;
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(chars);
    }
}
