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

public class SnapshotFile {

    private String path;
    private long size;
    private String checksum;

    public SnapshotFile() {
    }

    public SnapshotFile(String path, long size, String checksum) {
        this.path = path;
        this.size = size;
        this.checksum = checksum;
    }

    public String path() {
        return this.path;
    }

    public long size() {
        return this.size;
    }

    public String checksum() {
        return this.checksum;
    }
}
