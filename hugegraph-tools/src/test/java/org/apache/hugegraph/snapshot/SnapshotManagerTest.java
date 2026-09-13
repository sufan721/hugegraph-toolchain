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

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assert;
import org.junit.Test;

public class SnapshotManagerTest {

    @Test
    public void testGraphRootResolvesInsideSnapshotDirectory() {
        Path root = Paths.get("./backup").toAbsolutePath().normalize();
        Assert.assertEquals(root.resolve("hugegraph"),
                            SnapshotManager.graphRoot("./backup", "hugegraph"));
    }

    @Test
    public void testGraphRootRejectsEscapingGraphName() {
        this.assertRejected("../escape", "Invalid graph name");
        this.assertRejected("../../etc", "Invalid graph name");
        this.assertRejected("/escape", "Invalid graph name");
    }

    @Test
    public void testGraphRootRejectsEmptyGraphName() {
        this.assertRejected("", "Graph name can't be null or empty");
    }

    private void assertRejected(String graph, String message) {
        try {
            SnapshotManager.graphRoot("./backup", graph);
            Assert.fail("Expected the graph name '" + graph + "' to be rejected");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains(message));
        }
    }
}
