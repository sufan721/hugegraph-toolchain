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

import org.apache.hugegraph.cmd.HugeGraphCommand;
import org.apache.hugegraph.cmd.SubCommands;
import org.junit.Assert;
import org.junit.Test;

public class SnapshotCommandTest {

    @Test
    public void testParseSnapshotBackupCommand() {
        HugeGraphCommand command = new HugeGraphCommand();
        command.parseCommand(new String[] {
                "--graph", "hugegraph",
                "snapshot-backup",
                "--directory", "./backup",
                "--mode", "full",
                "--keep-num", "2",
                "--server-data-root", "./server-data"
        });
        SubCommands.SnapshotBackup backup =
                command.subCommand("snapshot-backup");
        Assert.assertEquals("./backup", backup.directory());
        Assert.assertEquals("full", backup.mode());
        Assert.assertEquals(2, backup.keepNum());
        Assert.assertEquals("./server-data", backup.serverDataRoot());
    }

    @Test
    public void testParseSnapshotRestoreCommand() {
        HugeGraphCommand command = new HugeGraphCommand();
        command.parseCommand(new String[] {
                "--graph", "hugegraph",
                "snapshot-restore",
                "--directory", "./backup",
                "--version", "20260912-100000"
        });
        SubCommands.SnapshotRestore restore =
                command.subCommand("snapshot-restore");
        Assert.assertEquals("./backup", restore.directory());
        Assert.assertEquals("20260912-100000", restore.backupId());
    }
}
