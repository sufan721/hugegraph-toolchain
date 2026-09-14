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

import java.nio.file.Paths;

import org.apache.hugegraph.base.ToolClient;
import org.apache.hugegraph.driver.GraphManager;
import org.apache.hugegraph.driver.SchemaManager;
import org.apache.hugegraph.structure.constant.GraphMode;
import org.apache.hugegraph.structure.graph.Edge;
import org.apache.hugegraph.structure.graph.Vertex;
import org.apache.hugegraph.util.E;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Opt-in integration tests against a real HugeGraph Server backed by RocksDB.
 * Set HUGEGRAPH_SNAPSHOT_IT=true and HUGEGRAPH_SERVER_DATA_ROOT to execute.
 */
public class RocksDbSnapshotIT {

    private static final String ENABLED = "HUGEGRAPH_SNAPSHOT_IT";
    private static final String DATA_ROOT = "HUGEGRAPH_SERVER_DATA_ROOT";
    private static final String URL = "HUGEGRAPH_SNAPSHOT_IT_URL";
    private static final String GRAPH = "HUGEGRAPH_SNAPSHOT_IT_GRAPH";
    private static final String DEFAULT_URL = "http://127.0.0.1:8080";
    private static final String PERSON = "snapshot_it_person";
    private static final String KNOWS = "snapshot_it_knows";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private ToolClient client;
    private SnapshotRepository repository;
    private SnapshotStorage serverStorage;
    private String graph;
    private GraphMode originalGraphMode;

    @Before
    public void setup() throws Exception {
        Assume.assumeTrue("Set " + ENABLED + "=true to run this integration test",
                          "true".equalsIgnoreCase(System.getenv(ENABLED)));
        String dataRoot = System.getenv(DATA_ROOT);
        Assume.assumeTrue("Set " + DATA_ROOT + " to the real Server data root",
                          dataRoot != null && !dataRoot.isEmpty());

        this.graph = System.getenv(GRAPH);
        E.checkArgument(this.graph != null && !this.graph.isEmpty(),
                        "Set %s to an isolated integration-test graph", GRAPH);
        this.client = new ToolClient(new ToolClient.ConnectionInfo(
                      this.value(URL, DEFAULT_URL), this.graph, null, null,
                      60, null, null));
        this.originalGraphMode = this.client.graphs().mode(this.graph);
        SnapshotStorage repositoryStorage = new LocalSnapshotStorage(
                                              this.temporary.newFolder("backup")
                                                            .toPath());
        SnapshotMetadataManager metadata = new SnapshotMetadataManager(
                                           repositoryStorage, this.graph);
        this.repository = new SnapshotRepository(repositoryStorage, metadata);
        this.serverStorage = new LocalSnapshotStorage(Paths.get(dataRoot));
        this.createSchema();
        this.removeTestData();
    }

    @After
    public void teardown() {
        if (this.client != null) {
            if (this.originalGraphMode != null) {
                this.client.graphs().mode(this.graph, this.originalGraphMode);
            }
            this.client.close();
        }
    }

    @Test
    public void fullBaselineIncrementalAndSelectedRestore() {
        GraphManager graphManager = this.client.graph();
        Vertex alice = this.person("alice", "baseline");
        Vertex bob = this.person("bob", "baseline");
        Vertex charlie = this.person("charlie", "baseline");
        graphManager.addVertex(alice);
        graphManager.addVertex(bob);
        graphManager.addVertex(charlie);
        Edge aliceKnowsBob = graphManager.addEdge(alice, KNOWS, bob);
        graphManager.addEdge(bob, KNOWS, charlie);

        SnapshotManifest baseline = this.backup(SnapshotMode.FULL, 0);

        alice.property("state", "updated");
        graphManager.appendVertexProperty(alice);
        Vertex diana = this.person("diana", "added");
        graphManager.addVertex(diana);
        graphManager.addEdge(bob, KNOWS, diana);
        graphManager.deleteEdge(aliceKnowsBob.id());
        graphManager.deleteVertex(charlie.id());

        SnapshotManifest incremental = this.backup(SnapshotMode.INCREMENTAL, 0);
        this.assertGraph(3, 2, "updated", false, true);

        this.restore(baseline.backupId());
        this.assertGraph(3, 2, "baseline", true, false);

        this.restore(incremental.backupId());
        this.assertGraph(3, 2, "updated", false, true);
    }

    @Test
    public void retentionKeepsLatestVersionRestorable() {
        GraphManager graphManager = this.client.graph();
        graphManager.addVertex(this.person("retention", "v1"));
        this.backup(SnapshotMode.FULL, 0);
        graphManager.addVertex(this.person("retention-v2", "v2"));
        SnapshotManifest latest = this.backup(SnapshotMode.INCREMENTAL, 1);

        this.removeTestData();
        this.restore(null);

        Assert.assertEquals(latest.backupId(), this.repository.select(null).backupId());
        Assert.assertNotNull(this.vertex("retention-v2"));
    }

    private SnapshotManifest backup(SnapshotMode mode, int keepNum) {
        this.client.graphs().createSnapshot(this.graph);
        SnapshotManifest manifest = this.repository.backup(this.serverStorage,
                                                            mode, keepNum);
        this.repository.cleanupServerSnapshot(this.serverStorage, manifest);
        return manifest;
    }

    private void restore(String backupId) {
        this.client.graphs().mode(this.graph, GraphMode.RESTORING);
        this.repository.restore(this.serverStorage, backupId);
        this.client.graphs().resumeSnapshot(this.graph);
        this.client.graphs().mode(this.graph, this.originalGraphMode);
    }

    private void createSchema() {
        SchemaManager schema = this.client.schema();
        schema.propertyKey("name").asText().ifNotExist().create();
        schema.propertyKey("state").asText().ifNotExist().create();
        schema.vertexLabel(PERSON).properties("name", "state")
              .primaryKeys("name").nullableKeys("state")
              .ifNotExist().create();
        schema.edgeLabel(KNOWS).sourceLabel(PERSON).targetLabel(PERSON)
              .ifNotExist().create();
    }

    private void removeTestData() {
        GraphManager graphManager = this.client.graph();
        for (Edge edge : graphManager.listEdges(KNOWS)) {
            graphManager.deleteEdge(edge.id());
        }
        for (Vertex vertex : graphManager.listVertices(PERSON)) {
            graphManager.deleteVertex(vertex.id());
        }
    }

    private void assertGraph(int vertices, int edges, String aliceState,
                             boolean charliePresent, boolean dianaPresent) {
        GraphManager graphManager = this.client.graph();
        Assert.assertEquals(vertices, graphManager.listVertices(PERSON).size());
        Assert.assertEquals(edges, graphManager.listEdges(KNOWS).size());
        Assert.assertEquals(aliceState, this.vertex("alice").property("state"));
        Assert.assertEquals(charliePresent, this.vertex("charlie") != null);
        Assert.assertEquals(dianaPresent, this.vertex("diana") != null);
    }

    private Vertex person(String name, String state) {
        Vertex person = new Vertex(PERSON);
        person.property("name", name);
        person.property("state", state);
        return person;
    }

    private Vertex vertex(String name) {
        for (Vertex vertex : this.client.graph().listVertices(PERSON)) {
            if (name.equals(vertex.property("name"))) {
                return vertex;
            }
        }
        return null;
    }

    private String value(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? defaultValue : value;
    }
}
