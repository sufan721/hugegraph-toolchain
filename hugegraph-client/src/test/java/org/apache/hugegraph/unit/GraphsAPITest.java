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

package org.apache.hugegraph.unit;

import java.util.Collections;
import java.util.Map;

import org.apache.hugegraph.api.graphs.GraphsAPI;
import org.apache.hugegraph.client.RestClient;
import org.apache.hugegraph.driver.GraphsManager;
import org.apache.hugegraph.rest.RestHeaders;
import org.apache.hugegraph.rest.RestResult;
import org.apache.hugegraph.testutil.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class GraphsAPITest extends BaseUnitTest {

    private RestClient mockClient;
    private GraphsAPI graphsAPI;

    @Before
    public void setup() {
        this.mockClient = Mockito.mock(RestClient.class);
        Mockito.when(this.mockClient.apiVersionLt(Mockito.anyString()))
               .thenReturn(false);
        this.graphsAPI = new GraphsAPI(this.mockClient, "DEFAULT");
    }

    @Test
    public void testCreateGraphUsesJsonContentType() {
        RestResult mockResult = Mockito.mock(RestResult.class);
        Mockito.when(mockResult.readObject(Map.class))
               .thenReturn(null);

        ArgumentCaptor<String> pathCaptor =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bodyCaptor =
                ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<RestHeaders> headersCaptor =
                ArgumentCaptor.forClass(RestHeaders.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor =
                ArgumentCaptor.forClass(Map.class);

        Mockito.when(this.mockClient.post(
                pathCaptor.capture(),
                bodyCaptor.capture(),
                headersCaptor.capture(),
                paramsCaptor.capture()
        )).thenReturn(mockResult);

        this.graphsAPI.create("test-graph", null, "{}");

        RestHeaders capturedHeaders = headersCaptor.getValue();
        Assert.assertEquals("application/json",
                            capturedHeaders.get(RestHeaders.CONTENT_TYPE));

        Assert.assertTrue(
                pathCaptor.getValue().contains("test-graph"));
        Assert.assertEquals("{}", bodyCaptor.getValue());
        Assert.assertNull(paramsCaptor.getValue());
    }

    @Test
    public void testCloneGraphUsesJsonContentTypeAndParams() {
        RestResult mockResult = Mockito.mock(RestResult.class);
        Mockito.when(mockResult.readObject(Map.class))
               .thenReturn(null);

        ArgumentCaptor<RestHeaders> headersCaptor =
                ArgumentCaptor.forClass(RestHeaders.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor =
                ArgumentCaptor.forClass(Map.class);

        Mockito.when(this.mockClient.post(
                Mockito.anyString(),
                Mockito.any(),
                headersCaptor.capture(),
                paramsCaptor.capture()
        )).thenReturn(mockResult);

        this.graphsAPI.create("new-graph", "source-graph", "{}");

        RestHeaders capturedHeaders = headersCaptor.getValue();
        Assert.assertEquals("application/json",
                            capturedHeaders.get(RestHeaders.CONTENT_TYPE));

        Map<String, Object> capturedParams = paramsCaptor.getValue();
        Assert.assertNotNull(capturedParams);
        Assert.assertEquals("source-graph",
                            capturedParams.get("clone_graph_name"));
    }

    @Test
    public void testSetDefaultGraphUsesCanonicalPost() {
        RestResult mockResult = Mockito.mock(RestResult.class);
        Mockito.when(mockResult.readObject(Map.class))
               .thenReturn(null);

        ArgumentCaptor<String> pathCaptor =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bodyCaptor =
                ArgumentCaptor.forClass(Object.class);

        Mockito.when(this.mockClient.post(pathCaptor.capture(),
                                          bodyCaptor.capture()))
               .thenReturn(mockResult);

        this.graphsAPI.setDefault("test-graph");

        Assert.assertEquals("graphspaces/DEFAULT/graphs/test-graph/default",
                            pathCaptor.getValue());
        Assert.assertTrue(((Map<?, ?>) bodyCaptor.getValue()).isEmpty());
        Mockito.verify(mockResult).readObject(Map.class);
    }

    @Test
    public void testUnsetDefaultGraphUsesCanonicalDelete() {
        RestResult mockResult = Mockito.mock(RestResult.class);
        Mockito.when(mockResult.readObject(Map.class))
               .thenReturn(null);

        ArgumentCaptor<String> pathCaptor =
                ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor =
                ArgumentCaptor.forClass(Map.class);

        Mockito.when(this.mockClient.delete(pathCaptor.capture(),
                                            paramsCaptor.capture()))
               .thenReturn(mockResult);

        this.graphsAPI.unSetDefault("test-graph");

        Assert.assertEquals("graphspaces/DEFAULT/graphs/test-graph/default",
                            pathCaptor.getValue());
        Assert.assertTrue(paramsCaptor.getValue().isEmpty());
        Mockito.verify(mockResult).readObject(Map.class);
    }

    @Test
    public void testCreateSnapshotUsesExpectedEndpointAndStatus() {
        RestResult mockResult = Mockito.mock(RestResult.class);
        Map<String, String> response = Collections.singletonMap(
                "test-graph", "snapshot_created");
        Mockito.when(mockResult.readObject(Map.class)).thenReturn(response);

        ArgumentCaptor<String> pathCaptor =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bodyCaptor =
                ArgumentCaptor.forClass(Object.class);
        Mockito.when(this.mockClient.put(pathCaptor.capture(),
                                         Mockito.isNull(),
                                         bodyCaptor.capture()))
               .thenReturn(mockResult);

        Assert.assertEquals(response, this.graphsAPI.createSnapshot("test-graph"));
        Assert.assertEquals("graphspaces/DEFAULT/graphs/test-graph/snapshot_create",
                            pathCaptor.getValue());
        Assert.assertTrue(((Map<?, ?>) bodyCaptor.getValue()).isEmpty());
        Mockito.verify(this.mockClient).checkApiVersion("0.74", "graph snapshot");
        Mockito.verify(mockResult).readObject(Map.class);
    }

    @Test
    public void testResumeSnapshotUsesExpectedEndpointAndStatus() {
        RestResult mockResult = Mockito.mock(RestResult.class);
        Map<String, String> response = Collections.singletonMap(
                "test-graph", "snapshot_resumed");
        Mockito.when(mockResult.readObject(Map.class)).thenReturn(response);

        ArgumentCaptor<String> pathCaptor =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bodyCaptor =
                ArgumentCaptor.forClass(Object.class);
        Mockito.when(this.mockClient.put(pathCaptor.capture(),
                                         Mockito.isNull(),
                                         bodyCaptor.capture()))
               .thenReturn(mockResult);

        Assert.assertEquals(response, this.graphsAPI.resumeSnapshot("test-graph"));
        Assert.assertEquals("graphspaces/DEFAULT/graphs/test-graph/snapshot_resume",
                            pathCaptor.getValue());
        Assert.assertTrue(((Map<?, ?>) bodyCaptor.getValue()).isEmpty());
        Mockito.verify(this.mockClient).checkApiVersion("0.74", "graph snapshot");
        Mockito.verify(mockResult).readObject(Map.class);
    }

    @Test
    public void testGraphsManagerDelegatesSnapshotOperations() {
        GraphsManager manager = new GraphsManager(this.mockClient, "DEFAULT");
        Map<String, String> created = Collections.singletonMap(
                "test-graph", "snapshot_created");
        Map<String, String> resumed = Collections.singletonMap(
                "test-graph", "snapshot_resumed");
        RestResult createdResult = Mockito.mock(RestResult.class);
        RestResult resumedResult = Mockito.mock(RestResult.class);
        Mockito.when(createdResult.readObject(Map.class)).thenReturn(created);
        Mockito.when(resumedResult.readObject(Map.class)).thenReturn(resumed);
        Mockito.when(this.mockClient.put(Mockito.anyString(),
                                         Mockito.isNull(),
                                         Mockito.any()))
               .thenReturn(createdResult, resumedResult);

        Assert.assertEquals(created, manager.createSnapshot("test-graph"));
        Assert.assertEquals(resumed, manager.resumeSnapshot("test-graph"));
        Mockito.verify(this.mockClient, Mockito.times(2))
               .checkApiVersion("0.74", "graph snapshot");
    }

    @Test
    public void testPerGraphReloadIsDeprecated() throws NoSuchMethodException {
        Assert.assertNotNull(GraphsAPI.class.getMethod("reload", String.class)
                                            .getAnnotation(Deprecated.class));
        Assert.assertNotNull(GraphsManager.class.getMethod("reload", String.class)
                                                .getAnnotation(Deprecated.class));
        Assert.assertNull(GraphsAPI.class.getMethod("reload")
                                         .getAnnotation(Deprecated.class));
        Assert.assertNull(GraphsManager.class.getMethod("reload")
                                             .getAnnotation(Deprecated.class));
    }
}
