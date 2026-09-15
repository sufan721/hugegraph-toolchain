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

package org.apache.hugegraph.api.graphs;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.hugegraph.api.API;
import org.apache.hugegraph.client.RestClient;
import org.apache.hugegraph.rest.RestResult;
import org.apache.hugegraph.structure.constant.HugeType;
import org.apache.hugegraph.util.E;

public class GraphBackupsAPI extends API {

    private static final String PATH = "graphspaces/%s/graphs/%s/backups";
    private static final String TASK_ID = "task_id";
    private static final String REPOSITORY_PATTERN =
            "[A-Za-z0-9][A-Za-z0-9._-]{0,62}";

    public GraphBackupsAPI(RestClient client, String graphSpace, String graph) {
        super(client);
        this.path(String.format(PATH, graphSpace, graph));
    }

    @Override
    protected String type() {
        return HugeType.TASK.string();
    }

    public long create(String repository, int keepNum) {
        // Server resolves the repository and runs RocksDB BackupEngine. This
        // client never reads the graph data directory or copies DB files.
        checkRepository(repository);
        E.checkArgument(keepNum >= 0, "Keep number must be non-negative");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("repository", repository);
        body.put("keep_num", keepNum);
        RestResult result = this.client.post(this.path(), body);
        Map<String, Object> response = result.readObject(Map.class);
        Object taskId = response.get(TASK_ID);
        E.checkState(taskId instanceof Number,
                     "Backup response must contain numeric '%s': %s",
                     TASK_ID, response);
        return ((Number) taskId).longValue();
    }

    public long restore(String repository, String backupId, boolean confirm) {
        // A graph backup version is restored by the Server as a coordinated
        // set of Store backup ids; the client only submits the task.
        E.checkArgument(confirm, "Snapshot restore requires --confirm");
        checkRepository(repository);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("repository", repository);
        if (backupId != null && !backupId.isEmpty()) {
            body.put("backup_id", backupId);
        }
        body.put("confirm", true);
        RestResult result = this.client.post(this.path() + "/restore", body);
        Map<String, Object> response = result.readObject(Map.class);
        Object taskId = response.get(TASK_ID);
        E.checkState(taskId instanceof Number,
                     "Restore response must contain numeric '%s': %s",
                     TASK_ID, response);
        return ((Number) taskId).longValue();
    }

    public Map<String, Object> get(String backupId) {
        RestResult result = this.client.get(this.path(), backupId);
        return result.readObject(Map.class);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list() {
        RestResult result = this.client.get(this.path());
        Object value = result.readObject(Map.class).get("backups");
        return value == null ? Collections.emptyList() : (List<Map<String, Object>>) value;
    }

    private static void checkRepository(String repository) {
        E.checkArgument(repository != null &&
                        repository.matches(REPOSITORY_PATTERN),
                        "Invalid repository name '%s'", repository);
    }
}
