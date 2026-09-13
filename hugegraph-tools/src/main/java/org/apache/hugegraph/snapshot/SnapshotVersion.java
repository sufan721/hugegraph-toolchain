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

import java.util.Comparator;

public class SnapshotVersion {

    /**
     * Orders versions by creation time ascending, breaking ties by backup id
     * so that the ordering is deterministic even for same-timestamp versions.
     */
    public static final Comparator<SnapshotVersion> BY_CREATION = (left, right) -> {
        int result = Long.compare(left.createdAt(), right.createdAt());
        return result != 0 ? result : left.backupId().compareTo(right.backupId());
    };

    private String backupId;
    private long createdAt;
    private String manifestChecksum;

    public SnapshotVersion() {
    }

    public SnapshotVersion(SnapshotManifest manifest, String manifestChecksum) {
        this.backupId = manifest.backupId();
        this.createdAt = manifest.createdAt();
        this.manifestChecksum = manifestChecksum;
    }

    public String backupId() {
        return this.backupId;
    }

    public long createdAt() {
        return this.createdAt;
    }

    public String manifestChecksum() {
        return this.manifestChecksum;
    }
}
