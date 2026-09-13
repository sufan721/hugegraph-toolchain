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

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public interface SnapshotStorage {

    String root();

    void initialize();

    /**
     * Acquire an exclusive lock on the storage, blocking until it's
     * available. The lock is shared between processes, so it can be used to
     * serialize the operations which read and write the same storage.
     * The caller must close the returned instance to release the lock.
     */
    Closeable lock();

    boolean exists(String path);

    long size(String path);

    List<String> listFiles(String directory, boolean recursive);

    InputStream input(String path);

    OutputStream output(String path, boolean override);

    byte[] read(String path);

    void write(String path, byte[] content, boolean override);

    void move(String sourcePath, String targetPath, boolean replace);

    void delete(String path);

    void replaceDirectory(String sourcePath, String targetPath);
}
