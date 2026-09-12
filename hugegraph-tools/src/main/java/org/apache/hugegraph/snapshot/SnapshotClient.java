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

import java.util.Map;

import org.apache.hugegraph.base.ToolClient;
import org.apache.hugegraph.util.E;

public class SnapshotClient {

    private final ToolClient client;

    public SnapshotClient(ToolClient client) {
        E.checkArgument(client != null, "Tool client can't be null");
        this.client = client;
    }

    public Map<String, String> createSnapshot() {
        return this.client.graphs().createSnapshot(this.graph());
    }

    public Map<String, String> resumeSnapshot() {
        return this.client.graphs().resumeSnapshot(this.graph());
    }

    private String graph() {
        return this.client.graph().graph();
    }
}
