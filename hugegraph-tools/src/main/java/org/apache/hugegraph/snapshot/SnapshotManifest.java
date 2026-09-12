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

import java.util.ArrayList;
import java.util.List;

public class SnapshotManifest {

    public static final int FORMAT_VERSION = 1;

    private int formatVersion = FORMAT_VERSION;
    private String backupId;
    private String graph;
    private long createdAt;
    private SnapshotMode mode;
    private String parentBackupId;
    private List<SnapshotFile> files = new ArrayList<>();

    public SnapshotManifest() {
    }

    public SnapshotManifest(String backupId, String graph, long createdAt,
                            SnapshotMode mode, String parentBackupId) {
        this.backupId = backupId;
        this.graph = graph;
        this.createdAt = createdAt;
        this.mode = mode;
        this.parentBackupId = parentBackupId;
    }

    public int formatVersion() {
        return this.formatVersion;
    }

    public String backupId() {
        return this.backupId;
    }

    public String graph() {
        return this.graph;
    }

    public long createdAt() {
        return this.createdAt;
    }

    public SnapshotMode mode() {
        return this.mode;
    }

    public String parentBackupId() {
        return this.parentBackupId;
    }

    public List<SnapshotFile> files() {
        return this.files;
    }

    public long totalSize() {
        long total = 0L;
        for (SnapshotFile file : this.files) {
            total += file.size();
        }
        return total;
    }
}
