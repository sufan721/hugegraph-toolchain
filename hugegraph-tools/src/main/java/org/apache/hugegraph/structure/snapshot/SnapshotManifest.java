/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

package org.apache.hugegraph.structure.snapshot;

import java.util.ArrayList;
import java.util.List;

public class SnapshotManifest {
    private int formatVersion = 1;
    private String graph;
    private long version;
    private String backupId;
    private String mode;
    private Long parentVersion;
    private String createdAt;
    private String snapshotDir;
    private List<SnapshotFileEntry> files = new ArrayList<>();
    private List<String> deltaPaths = new ArrayList<>();
    private SnapshotStats stats = new SnapshotStats();

    public SnapshotManifest() { }
    public int getFormatVersion() { return formatVersion; }
    public void setFormatVersion(int value) { this.formatVersion = value; }
    public String getGraph() { return graph; }
    public void setGraph(String value) { this.graph = value; }
    public long getVersion() { return version; }
    public void setVersion(long value) { this.version = value; }
    public String getBackupId() { return backupId; }
    public void setBackupId(String value) { this.backupId = value; }
    public String getMode() { return mode; }
    public void setMode(String value) { this.mode = value; }
    public Long getParentVersion() { return parentVersion; }
    public void setParentVersion(Long value) { this.parentVersion = value; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String value) { this.createdAt = value; }
    public String getSnapshotDir() { return snapshotDir; }
    public void setSnapshotDir(String value) { this.snapshotDir = value; }
    public List<SnapshotFileEntry> getFiles() { return files; }
    public void setFiles(List<SnapshotFileEntry> value) { this.files = value; }
    public List<String> getDeltaPaths() { return deltaPaths; }
    public void setDeltaPaths(List<String> value) { this.deltaPaths = value; }
    public SnapshotStats getStats() { return stats; }
    public void setStats(SnapshotStats value) { this.stats = value; }

    public static class SnapshotStats {
        private int totalFiles;
        private long totalBytes;
        private int deltaFiles;
        private long deltaBytes;
        public int getTotalFiles() { return totalFiles; }
        public void setTotalFiles(int value) { this.totalFiles = value; }
        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long value) { this.totalBytes = value; }
        public int getDeltaFiles() { return deltaFiles; }
        public void setDeltaFiles(int value) { this.deltaFiles = value; }
        public long getDeltaBytes() { return deltaBytes; }
        public void setDeltaBytes(long value) { this.deltaBytes = value; }
    }
}
