/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.hugegraph.exception.ToolsException;
import org.apache.hugegraph.util.E;

/** @deprecated Physical snapshots are managed by the Server task API. */
@Deprecated
public final class SnapshotManager {

    private SnapshotManager() {
    }

    static Path graphRoot(String directory, String graph) {
        E.checkArgument(graph != null && !graph.isEmpty(),
                        "Graph name can't be null or empty");
        Path root = Paths.get(directory).toAbsolutePath().normalize();
        Path graphRoot = root.resolve(graph).normalize();
        E.checkArgument(graphRoot.startsWith(root) && !graphRoot.equals(root),
                        "Invalid graph name '%s', it must resolve to a " +
                        "directory inside the snapshot directory '%s'",
                        graph, root);
        try {
            LocalSnapshotStorage.checkNoSymbolicLinks(root);
            java.nio.file.Files.createDirectories(root);
            LocalSnapshotStorage.checkNoSymbolicLinks(graphRoot);
            E.checkState(graphRoot.getParent().toRealPath().startsWith(
                         root.toRealPath()),
                         "Invalid graph name '%s', it resolves outside the " +
                         "snapshot directory '%s'", graph, root);
        } catch (IOException e) {
            throw new ToolsException("Failed to resolve snapshot directory " +
                                     "'%s'", e, directory);
        }
        return graphRoot;
    }
}
