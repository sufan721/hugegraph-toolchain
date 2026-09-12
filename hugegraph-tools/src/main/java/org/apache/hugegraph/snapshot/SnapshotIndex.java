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

public class SnapshotIndex {

    public static final int FORMAT_VERSION = 1;

    private int formatVersion = FORMAT_VERSION;
    private String graph;
    private List<SnapshotVersion> versions = new ArrayList<>();

    public SnapshotIndex() {
    }

    public SnapshotIndex(String graph) {
        this.graph = graph;
    }

    public int formatVersion() {
        return this.formatVersion;
    }

    public void formatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String graph() {
        return this.graph;
    }

    public void graph(String graph) {
        this.graph = graph;
    }

    public List<SnapshotVersion> versions() {
        return this.versions;
    }

    public void versions(List<SnapshotVersion> versions) {
        this.versions = versions;
    }
}
