/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

package org.apache.hugegraph.structure.snapshot;

public class SnapshotFileEntry {
    private String path;
    private long size;
    private String crc32c;

    public SnapshotFileEntry() {
    }

    public SnapshotFileEntry(String path, long size, String crc32c) {
        this.path = path;
        this.size = size;
        this.crc32c = crc32c;
    }

    public String getPath() { return this.path; }
    public void setPath(String path) { this.path = path; }
    public long getSize() { return this.size; }
    public void setSize(long size) { this.size = size; }
    public String getCrc32c() { return this.crc32c; }
    public void setCrc32c(String crc32c) { this.crc32c = crc32c; }
}
