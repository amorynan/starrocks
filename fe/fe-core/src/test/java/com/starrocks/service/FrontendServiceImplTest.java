// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.service;

import com.clearspring.analytics.util.Lists;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.ExpressionRangePartitionInfo;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Table;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.QueryQueueManager;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.DropTableStmt;
import com.starrocks.system.Backend;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TCreatePartitionRequest;
import com.starrocks.thrift.TCreatePartitionResult;
import com.starrocks.thrift.TResourceUsage;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TUpdateResourceUsageRequest;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.apache.thrift.TException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collection;
import java.util.List;

public class FrontendServiceImplTest {

    @Mocked
    ExecuteEnv exeEnv;

    private TUpdateResourceUsageRequest genUpdateResourceUsageRequest(
            long backendId, int numRunningQueries, long memLimitBytes, long memUsedBytes, int cpuUsedPermille) {
        TResourceUsage usage = new TResourceUsage();
        usage.setNum_running_queries(numRunningQueries);
        usage.setMem_limit_bytes(memLimitBytes);
        usage.setMem_used_bytes(memUsedBytes);
        usage.setCpu_used_permille(cpuUsedPermille);

        TUpdateResourceUsageRequest request = new TUpdateResourceUsageRequest();
        request.setResource_usage(usage);
        request.setBackend_id(backendId);

        return request;
    }

    @Test
    public void testUpdateResourceUsage() throws TException {
        QueryQueueManager queryQueueManager = QueryQueueManager.getInstance();
        FrontendServiceImpl impl = new FrontendServiceImpl(exeEnv);

        Backend backend = new Backend(0, "127.0.0.1", 80);
        ComputeNode computeNode = new ComputeNode(2, "127.0.0.1", 88);

        new MockUp<SystemInfoService>() {
            @Mock
            public ComputeNode getBackendOrComputeNode(long id) {
                if (id == backend.getId()) {
                    return backend;
                }
                if (id == computeNode.getId()) {
                    return computeNode;
                }
                return null;
            }
        };
        new Expectations(queryQueueManager) {
            {
                queryQueueManager.maybeNotifyAfterLock();
                times = 2;
            }
        };

        long backendId = 0;
        int numRunningQueries = 1;
        long memLimitBytes = 3;
        long memUsedBytes = 2;
        int cpuUsedPermille = 300;
        TUpdateResourceUsageRequest request = genUpdateResourceUsageRequest(
                backendId, numRunningQueries, memLimitBytes, memUsedBytes, cpuUsedPermille);

        // For backend, notify pending queries.
        impl.updateResourceUsage(request);
        Assert.assertEquals(numRunningQueries, backend.getNumRunningQueries());
        Assert.assertEquals(memLimitBytes, backend.getMemLimitBytes());
        Assert.assertEquals(memUsedBytes, backend.getMemUsedBytes());
        Assert.assertEquals(cpuUsedPermille, backend.getCpuUsedPermille());

        // For compute node, notify pending queries.
        numRunningQueries = 10;
        memLimitBytes = 30;
        memUsedBytes = 20;
        cpuUsedPermille = 310;
        request = genUpdateResourceUsageRequest(
                backendId, numRunningQueries, memLimitBytes, memUsedBytes, cpuUsedPermille);
        impl.updateResourceUsage(request);
        Assert.assertEquals(numRunningQueries, backend.getNumRunningQueries());
        Assert.assertEquals(memLimitBytes, backend.getMemLimitBytes());
        Assert.assertEquals(memUsedBytes, backend.getMemUsedBytes());
        Assert.assertEquals(cpuUsedPermille, backend.getCpuUsedPermille());

        // Don't notify, because this BE doesn't exist.
        request.setBackend_id(/* Not Exist */ 1);
        impl.updateResourceUsage(request);
    }

    private static ConnectContext connectContext;
    private static StarRocksAssert starRocksAssert;

    @BeforeClass
    public static void beforeClass() throws Exception {
        FeConstants.runningUnitTest = true;
        FeConstants.default_scheduler_interval_millisecond = 100;
        Config.dynamic_partition_enable = true;
        Config.dynamic_partition_check_interval_seconds = 1;
        Config.enable_strict_storage_medium_check = false;
        UtFrameUtils.createMinStarRocksCluster();
        // create connect context
        connectContext = UtFrameUtils.createDefaultCtx();
        starRocksAssert = new StarRocksAssert(connectContext);

        starRocksAssert.withDatabase("test").useDatabase("test")
                .withTable("CREATE TABLE site_access_empty (\n" +
                        "    event_day DATETIME NOT NULL,\n" +
                        "    site_id INT DEFAULT '10',\n" +
                        "    city_code VARCHAR(100),\n" +
                        "    user_name VARCHAR(32) DEFAULT '',\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ")\n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY date_trunc('day', event_day)\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id)\n" +
                        "PROPERTIES(\n" +
                        "    \"replication_num\" = \"1\"\n" +
                        ");")
                .withTable("CREATE TABLE site_access_day (\n" +
                        "    event_day DATE,\n" +
                        "    site_id INT DEFAULT '10',\n" +
                        "    city_code VARCHAR(100),\n" +
                        "    user_name VARCHAR(32) DEFAULT '',\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ")\n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY date_trunc('day', event_day) (\n" +
                        "START (\"2015-06-01\") END (\"2022-12-01\") EVERY (INTERVAL 1 month)\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id) BUCKETS 32\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ");")
                .withTable("CREATE TABLE site_access_month (\n" +
                        "    event_day DATE,\n" +
                        "    site_id INT DEFAULT '10',\n" +
                        "    city_code VARCHAR(100),\n" +
                        "    user_name VARCHAR(32) DEFAULT '',\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ")\n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY date_trunc('month', event_day) (\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id) BUCKETS 32\n" +
                        "PROPERTIES (\n" +
                        "\"replication_num\" = \"1\"\n" +
                        ");")
                .withTable("CREATE TABLE site_access_slice (\n" +
                        "    event_day datetime,\n" +
                        "    site_id INT DEFAULT '10',\n" +
                        "    city_code VARCHAR(100),\n" +
                        "    user_name VARCHAR(32) DEFAULT '',\n" +
                        "    pv BIGINT DEFAULT '0'\n" +
                        ")\n" +
                        "DUPLICATE KEY(event_day, site_id, city_code, user_name)\n" +
                        "PARTITION BY time_slice(event_day, interval 1 day) (\n" +
                        "START (\"2015-01-01\") END (\"2022-01-01\") EVERY (INTERVAL 1 year)\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(event_day, site_id) BUCKETS 32\n" +
                        "PROPERTIES(\"replication_num\" = \"1\");");
    }

    @AfterClass
    public static void tearDown() throws Exception {
        ConnectContext ctx = starRocksAssert.getCtx();
        String dropSQL = "drop table site_access";
        String dropSQL2 = "drop table site_access_2";
        try {
            DropTableStmt dropTableStmt = (DropTableStmt) UtFrameUtils.parseStmtWithNewParser(dropSQL, ctx);
            GlobalStateMgr.getCurrentState().dropTable(dropTableStmt);
            DropTableStmt dropTableStmt2 = (DropTableStmt) UtFrameUtils.parseStmtWithNewParser(dropSQL2, ctx);
            GlobalStateMgr.getCurrentState().dropTable(dropTableStmt2);
        } catch (Exception ex) {

        }
    }

    @Test
    public void testCreatePartitionApi() throws TException {
        Database db = GlobalStateMgr.getCurrentState().getDb("test");
        Table table = db.getTable("site_access_day");
        List<List<String>> partitionValues = Lists.newArrayList();
        List<String> values = Lists.newArrayList();
        values.add("1990-04-24");
        partitionValues.add(values);

        FrontendServiceImpl impl = new FrontendServiceImpl(exeEnv);
        TCreatePartitionRequest request = new TCreatePartitionRequest();
        request.setDb_id(db.getId());
        request.setTable_id(table.getId());
        request.setPartition_values(partitionValues);
        TCreatePartitionResult partition = impl.createPartition(request);

        Assert.assertEquals(partition.getStatus().getStatus_code(), TStatusCode.OK);
        Partition p19900424 = table.getPartition("p19900424");
        Assert.assertNotNull(p19900424);

        partition = impl.createPartition(request);
        Assert.assertEquals(1, partition.partitions.size());
    }

    @Test
    public void testCreatePartitionApiSlice() throws TException {
        Database db = GlobalStateMgr.getCurrentState().getDb("test");
        Table table = db.getTable("site_access_slice");
        List<List<String>> partitionValues = Lists.newArrayList();
        List<String> values = Lists.newArrayList();
        values.add("1990-04-24");
        partitionValues.add(values);

        FrontendServiceImpl impl = new FrontendServiceImpl(exeEnv);
        TCreatePartitionRequest request = new TCreatePartitionRequest();
        request.setDb_id(db.getId());
        request.setTable_id(table.getId());
        request.setPartition_values(partitionValues);
        TCreatePartitionResult partition = impl.createPartition(request);

        Assert.assertEquals(partition.getStatus().getStatus_code(), TStatusCode.OK);
        Partition p19900424 = table.getPartition("p19900424");
        Assert.assertNotNull(p19900424);

        partition = impl.createPartition(request);
        Assert.assertEquals(1, partition.partitions.size());
    }

    @Test
    public void testCreatePartitionApiMultiValues() throws TException {
        Database db = GlobalStateMgr.getCurrentState().getDb("test");
        Table table = db.getTable("site_access_day");
        List<List<String>> partitionValues = Lists.newArrayList();
        List<String> values = Lists.newArrayList();
        values.add("1990-04-24");
        values.add("1990-04-24");
        values.add("1989-11-02");
        partitionValues.add(values);

        FrontendServiceImpl impl = new FrontendServiceImpl(exeEnv);
        TCreatePartitionRequest request = new TCreatePartitionRequest();
        request.setDb_id(db.getId());
        request.setTable_id(table.getId());
        request.setPartition_values(partitionValues);
        TCreatePartitionResult partition = impl.createPartition(request);

        Assert.assertEquals(partition.getStatus().getStatus_code(), TStatusCode.OK);
        Partition p19891102 = table.getPartition("p19891102");
        Assert.assertNotNull(p19891102);

        partition = impl.createPartition(request);
        Assert.assertEquals(2, partition.partitions.size());
    }

    @Test
    public void testCreatePartitionApiMonth() throws TException {
        Database db = GlobalStateMgr.getCurrentState().getDb("test");
        Table table = db.getTable("site_access_month");
        List<List<String>> partitionValues = Lists.newArrayList();
        List<String> values = Lists.newArrayList();
        values.add("1990-04-24");
        values.add("1990-04-30");
        values.add("1990-04-01");
        values.add("1990-04-25");
        values.add("1989-11-02");
        partitionValues.add(values);

        FrontendServiceImpl impl = new FrontendServiceImpl(exeEnv);
        TCreatePartitionRequest request = new TCreatePartitionRequest();
        request.setDb_id(db.getId());
        request.setTable_id(table.getId());
        request.setPartition_values(partitionValues);
        TCreatePartitionResult partition = impl.createPartition(request);

        Assert.assertEquals(TStatusCode.OK, partition.getStatus().getStatus_code());
        Partition p199004 = table.getPartition("p199004");
        Assert.assertNotNull(p199004);

        partition = impl.createPartition(request);
        Assert.assertEquals(2, partition.partitions.size());
    }

    @Test
    public void testCreateEmptyPartition() {
        Database db = GlobalStateMgr.getCurrentState().getDb("test");
        Table table = db.getTable("site_access_empty");
        Collection<Partition> partitions = table.getPartitions();
        Assert.assertEquals(1, partitions.size());
        String name = partitions.iterator().next().getName();
        Assert.assertEquals(ExpressionRangePartitionInfo.AUTOMATIC_SHADOW_PARTITION_NAME, name);
        Assert.assertTrue(name.startsWith(ExpressionRangePartitionInfo.SHADOW_PARTITION_PREFIX));
    }

}
