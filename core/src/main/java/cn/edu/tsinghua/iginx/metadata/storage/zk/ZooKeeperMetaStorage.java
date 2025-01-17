/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package cn.edu.tsinghua.iginx.metadata.storage.zk;

import cn.edu.tsinghua.iginx.conf.ConfigDescriptor;
import cn.edu.tsinghua.iginx.exceptions.MetaStorageException;
import cn.edu.tsinghua.iginx.metadata.entity.FragmentStatistics;
import cn.edu.tsinghua.iginx.metadata.hook.ActiveFragmentStatisticsChangeHook;
import cn.edu.tsinghua.iginx.metadata.hook.FragmentChangeHook;
import cn.edu.tsinghua.iginx.metadata.hook.ReshardCounterChangeHook;
import cn.edu.tsinghua.iginx.metadata.hook.ReshardNotificationHook;
import cn.edu.tsinghua.iginx.metadata.entity.UserMeta;
import cn.edu.tsinghua.iginx.metadata.hook.*;
import cn.edu.tsinghua.iginx.metadata.storage.IMetaStorage;
import cn.edu.tsinghua.iginx.metadata.entity.FragmentMeta;
import cn.edu.tsinghua.iginx.metadata.entity.IginxMeta;
import cn.edu.tsinghua.iginx.metadata.entity.StorageEngineMeta;
import cn.edu.tsinghua.iginx.metadata.entity.StorageUnitMeta;
import cn.edu.tsinghua.iginx.metadata.entity.TimeSeriesInterval;
import cn.edu.tsinghua.iginx.metadata.entity.UserMeta;
import cn.edu.tsinghua.iginx.metadata.hook.UserChangeHook;
import cn.edu.tsinghua.iginx.metadata.utils.JsonUtils;
import com.google.gson.reflect.TypeToken;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.RetryForever;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class ZooKeeperMetaStorage implements IMetaStorage {

    private static final Logger logger = LoggerFactory.getLogger(ZooKeeperMetaStorage.class);

    private static final String IGINX_NODE = "/iginx/node";

    private static final String STORAGE_ENGINE_NODE = "/storage/node";

    private static final String ACTIVE_FRAGMENT_STATISTICS_NODE = "/statistics/fragment/active/node";

    private static final String STORAGE_UNIT_NODE = "/unit/unit";

    private static final String IGINX_LOCK_NODE = "/lock/iginx";

    private static final String STORAGE_ENGINE_LOCK_NODE = "/lock/storage";

    private static final String FRAGMENT_LOCK_NODE = "/lock/fragment";

    private static final String STORAGE_UNIT_LOCK_NODE = "/lock/unit";

    private static final String SCHEMA_MAPPING_LOCK_NODE = "/lock/schema";

    private static final String USER_LOCK_NODE = "/lock/user";

    private static final String RESHARD_NOTIFICATION_LOCK_NODE = "/lock/notification/reshard";

    private static final String RESHARD_COUNTER_LOCK_NODE = "/lock/counter/reshard";

    private static final String STORAGE_ENGINE_NODE_PREFIX = "/storage";

    private static final String IGINX_NODE_PREFIX = "/iginx";

    private static final String FRAGMENT_NODE_PREFIX = "/fragment";

    private static final String STORAGE_UNIT_NODE_PREFIX = "/unit";

    private static final String SCHEMA_MAPPING_PREFIX = "/schema";

    private static final String USER_NODE_PREFIX = "/user";

    private static final String ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX = "/statistics/fragment/active";

    private static final String POLICY_NODE_PREFIX = "/policy";

    private static final String POLICY_LEADER = "/policy/leader";

    private static final String POLICY_VERSION = "/policy/version";

    private static final String POLICY_LOCK_NODE = "/lock/policy";

    private static final String TIMESERIES_NODE_PREFIX = "/timeseries";

    private boolean isMaster = false;

    private static ZooKeeperMetaStorage INSTANCE = null;

    private final CuratorFramework client;

    private SchemaMappingChangeHook schemaMappingChangeHook = null;

    protected TreeCache schemaMappingsCache;

    private static final String SINGLE_ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX = "/add";

    private static final String INACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX = "/statistics/fragment/inactive";

    private static final String RESHARD_NOTIFICATION_NODE_PREFIX = "/notification/reshard";

    private static final String RESHARD_COUNTER_NODE_PREFIX = "/counter/reshard";

    private final Lock storageUnitMutexLock = new ReentrantLock();
    private final InterProcessMutex storageUnitMutex;
    private final Lock fragmentMutexLock = new ReentrantLock();
    private final InterProcessMutex fragmentMutex;
    private final Lock activeFragmentStatisticsMutexLock = new ReentrantLock();
    private final Lock reshardNotificationMutexLock = new ReentrantLock();
    private final InterProcessMutex reshardNotificationMutex;
    private final Lock reshardCounterMutexLock = new ReentrantLock();
    private final InterProcessMutex reshardCounterMutex;

    protected TreeCache iginxCache;
    protected TreeCache storageEngineCache;
    protected TreeCache storageUnitCache;
    protected TreeCache fragmentCache;
    protected TreeCache userCache;
    protected TreeCache activeFragmentStatisticsCache;
    protected TreeCache reshardNotificationCache;
    protected TreeCache reshardCounterCache;

    private IginxChangeHook iginxChangeHook = null;
    private StorageChangeHook storageChangeHook = null;
    private StorageUnitChangeHook storageUnitChangeHook = null;
    private FragmentChangeHook fragmentChangeHook = null;
    private UserChangeHook userChangeHook = null;
    private ActiveFragmentStatisticsChangeHook activeFragmentStatisticsChangeHook = null;
    private ReshardNotificationHook reshardNotificationHook = null;
    private ReshardCounterChangeHook reshardCounterChangeHook = null;
    private TimeseriesChangeHook timeseriesChangeHook = null;

    private VersionChangeHook versionChangeHook = null;


    private TreeCache policyCache;

    private TreeCache timeseriesCache;

    private TreeCache versionCache;

    public ZooKeeperMetaStorage() {
        client = CuratorFrameworkFactory.builder()
                .connectString(ConfigDescriptor.getInstance().getConfig().getZookeeperConnectionString())
                .connectionTimeoutMs(15000)
                .retryPolicy(new RetryForever(1000))
                .build();
        client.start();

        fragmentMutex = new InterProcessMutex(client, FRAGMENT_LOCK_NODE);
        storageUnitMutex = new InterProcessMutex(client, STORAGE_UNIT_LOCK_NODE);
        reshardNotificationMutex = new InterProcessMutex(client, RESHARD_NOTIFICATION_LOCK_NODE);
        reshardCounterMutex = new InterProcessMutex(client, RESHARD_COUNTER_LOCK_NODE);
    }

    public static ZooKeeperMetaStorage getInstance() {
        if (INSTANCE == null) {
            synchronized (ZooKeeperMetaStorage.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ZooKeeperMetaStorage();
                }
            }
        }
        return INSTANCE;
    }

    @Override
    public Map<String, Map<String, Integer>> loadSchemaMapping() throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(client, SCHEMA_MAPPING_LOCK_NODE);
        try {
            mutex.acquire();
            Map<String, Map<String, Integer>> schemaMappings = new HashMap<>();
            if (client.checkExists().forPath(SCHEMA_MAPPING_PREFIX) == null) {
                // 当前还没有数据，创建父节点，然后不需要解析数据
                client.create()
                        .withMode(CreateMode.PERSISTENT)
                        .forPath(SCHEMA_MAPPING_PREFIX);
            } else {
                List<String> schemas = this.client.getChildren()
                        .forPath(SCHEMA_MAPPING_PREFIX);
                for (String schema : schemas) {
                    Map<String, Integer> schemaMapping = JsonUtils.getGson().fromJson(new String(this.client.getData()
                            .forPath(SCHEMA_MAPPING_PREFIX + "/" + schema)), new TypeToken<Map<String, Integer>>() {
                    }.getType());
                    schemaMappings.put(schema, schemaMapping);
                }
            }
            registerSchemaMappingListener();
            return schemaMappings;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when loading schema mapping: ", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + SCHEMA_MAPPING_LOCK_NODE + ": ", e);
            }
        }
    }

    private void registerSchemaMappingListener() throws Exception {
        this.schemaMappingsCache = new TreeCache(client, SCHEMA_MAPPING_PREFIX);
        TreeCacheListener listener = (curatorFramework, event) -> {
            if (schemaMappingChangeHook == null) {
                return;
            }
            if (event.getData() == null || event.getData().getPath() == null || event.getData().getPath().equals(SCHEMA_MAPPING_PREFIX)) {
                return; // 创建根节点，不必理会
            }
            byte[] data;
            Map<String, Integer> schemaMapping = null;
            String schema = event.getData().getPath().substring(SCHEMA_MAPPING_PREFIX.length());
            switch (event.getType()) {
                case NODE_ADDED:
                case NODE_UPDATED:
                    data = event.getData().getData();
                    schemaMapping = JsonUtils.getGson().fromJson(new String(data), new TypeToken<Map<String, Integer>>() {
                    }.getType());
                    break;
                case NODE_REMOVED:
                default:
                    break;
            }
            schemaMappingChangeHook.onChange(schema, schemaMapping);
        };
        this.schemaMappingsCache.getListenable().addListener(listener);
        this.schemaMappingsCache.start();
    }

    @Override
    public void registerSchemaMappingChangeHook(SchemaMappingChangeHook hook) {
        this.schemaMappingChangeHook = hook;
    }

    @Override
    public void updateSchemaMapping(String schema, Map<String, Integer> schemaMapping) throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(this.client, SCHEMA_MAPPING_LOCK_NODE);
        try {
            mutex.acquire();
            if (this.client.checkExists().forPath(SCHEMA_MAPPING_PREFIX + "/" + schema) == null) {
                if (schemaMapping == null) { // 待删除的数据不存在
                    return;
                }
                // 创建新数据
                this.client.create().forPath(SCHEMA_MAPPING_PREFIX + "/" + schema, JsonUtils.toJson(schemaMapping));
            } else {
                if (schemaMapping == null) { // 待删除的数据存在
                    this.client.delete().forPath(SCHEMA_MAPPING_PREFIX + "/" + schema);
                }
                // 更新数据
                this.client.setData().forPath(SCHEMA_MAPPING_PREFIX + "/" + schema, JsonUtils.toJson(schemaMapping));
            }
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when updating schema mapping: ", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + SCHEMA_MAPPING_LOCK_NODE + ": ", e);
            }
        }
    }

    @Override
    public Map<Long, IginxMeta> loadIginx() throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(client, IGINX_LOCK_NODE);
        try {
            mutex.acquire();
            Map<Long, IginxMeta> iginxMetaMap = new HashMap<>();
            if (client.checkExists().forPath(IGINX_NODE_PREFIX) == null) {
                // 当前还没有数据，创建父节点，然后不需要解析数据
                client.create()
                        .withMode(CreateMode.PERSISTENT)
                        .forPath(IGINX_NODE_PREFIX);
            } else {
                List<String> children = client.getChildren()
                        .forPath(IGINX_NODE_PREFIX);
                for (String childName : children) {
                    byte[] data = client.getData()
                            .forPath(IGINX_NODE_PREFIX + "/" + childName);
                    IginxMeta iginxMeta = JsonUtils.fromJson(data, IginxMeta.class);
                    if (iginxMeta == null) {
                        logger.error("encounter error when resolving data from " + IGINX_NODE_PREFIX + "/" + childName);
                        continue;
                    }
                    iginxMetaMap.putIfAbsent(iginxMeta.getId(), iginxMeta);
                }
            }
            if (iginxCache == null) {
                registerIginxListener();
            }
            return iginxMetaMap;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when loading iginx: ", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + SCHEMA_MAPPING_LOCK_NODE + ": ", e);
            }
        }
    }

    @Override
    public long registerIginx(IginxMeta iginx) throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(client, IGINX_LOCK_NODE);
        try {
            mutex.acquire();
            String nodeName = this.client.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                    .forPath(IGINX_NODE, "".getBytes(StandardCharsets.UTF_8));
            long id = Long.parseLong(nodeName.substring(IGINX_NODE.length()));
            IginxMeta iginxMeta = new IginxMeta(id, iginx.getIp(),
                    iginx.getPort(), iginx.getExtraParams());
            this.client.setData()
                    .forPath(nodeName, JsonUtils.toJson(iginxMeta));
            return id;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when when loading iginx: ", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + IGINX_LOCK_NODE + ": ", e);
            }
        }
    }

    private void registerIginxListener() throws Exception {
        this.iginxCache = new TreeCache(this.client, IGINX_NODE_PREFIX);
        TreeCacheListener listener = (curatorFramework, event) -> {
            if (iginxChangeHook == null) {
                return;
            }
            byte[] data;
            IginxMeta iginxMeta;
            switch (event.getType()) {
                case NODE_UPDATED:
                    data = event.getData().getData();
                    iginxMeta = JsonUtils.fromJson(data, IginxMeta.class);
                    if (iginxMeta != null) {
                        logger.info("new iginx comes to cluster: id = " + iginxMeta.getId() + " ip = " + iginxMeta.getIp() + " port = " + iginxMeta.getPort());
                        iginxChangeHook.onChange(iginxMeta.getId(), iginxMeta);
                    } else {
                        logger.error("encounter error when resolving iginx meta from zookeeper");
                    }
                    break;
                case NODE_REMOVED:
                    data = event.getData().getData();
                    String path = event.getData().getPath();
                    logger.info("node " + path + " is removed");
                    if (path.equals(IGINX_NODE_PREFIX)) {
                        // 根节点被删除
                        logger.info("all iginx leave from cluster, iginx shutdown");
                        System.exit(1);
                        break;
                    }
                    iginxMeta = JsonUtils.fromJson(data, IginxMeta.class);
                    if (iginxMeta != null) {
                        logger.info("iginx leave from cluster: id = " + iginxMeta.getId() + " , ip = " + iginxMeta.getIp() + " , port = " + iginxMeta.getPort());
                        iginxChangeHook.onChange(iginxMeta.getId(), null);
                    } else {
                        logger.error("encounter error when resolving iginx meta from zookeeper");
                    }
                    break;
                default:
                    break;
            }
        };
        this.iginxCache.getListenable().addListener(listener);
        this.iginxCache.start();
    }

    @Override
    public void registerIginxChangeHook(IginxChangeHook hook) {
        this.iginxChangeHook = hook;
    }

    @Override
    public Map<Long, StorageEngineMeta> loadStorageEngine(List<StorageEngineMeta> storageEngines) throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(this.client, STORAGE_ENGINE_LOCK_NODE);
        try {
            mutex.acquire();
            if (this.client.checkExists().forPath(STORAGE_ENGINE_NODE_PREFIX) == null) { // 节点不存在，说明还没有别的 iginx 节点写入过元信息
                this.client.create().creatingParentsIfNeeded()
                        .withMode(CreateMode.PERSISTENT)
                        .forPath(STORAGE_ENGINE_NODE_PREFIX);
                for (StorageEngineMeta storageEngineMeta : storageEngines) {
                    String nodeName = this.client.create()
                            .creatingParentsIfNeeded()
                            .withMode(CreateMode.PERSISTENT_SEQUENTIAL)
                            .forPath(STORAGE_ENGINE_NODE, "".getBytes(StandardCharsets.UTF_8));
                    long id = Long.parseLong(nodeName.substring(STORAGE_ENGINE_NODE.length()));
                    storageEngineMeta.setId(id);
                    this.client.setData().forPath(nodeName, JsonUtils.toJson(storageEngineMeta));
                }
            }
            // 注册监听器
            registerStorageEngineListener();
            // 加载数据
            Map<Long, StorageEngineMeta> storageEngineMetaMap = new HashMap<>();
            List<String> children = this.client.getChildren().forPath(STORAGE_ENGINE_NODE_PREFIX);
            for (String childName : children) {
                byte[] data = this.client.getData()
                        .forPath(STORAGE_ENGINE_NODE_PREFIX + "/" + childName);
                StorageEngineMeta storageEngineMeta = JsonUtils.fromJson(data, StorageEngineMeta.class);
                if (storageEngineMeta == null) {
                    logger.error("encounter error when resolving data from " + STORAGE_ENGINE_NODE_PREFIX + "/" + childName);
                    continue;
                }
                storageEngineMetaMap.putIfAbsent(storageEngineMeta.getId(), storageEngineMeta);
            }
            return storageEngineMetaMap;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when loading schema mapping", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + STORAGE_ENGINE_LOCK_NODE + ": ", e);
            }
        }
    }

    @Override
    public long addStorageEngine(StorageEngineMeta storageEngine) throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(this.client, STORAGE_ENGINE_LOCK_NODE);
        try {
            mutex.acquire();
            String nodeName = this.client.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.PERSISTENT_SEQUENTIAL)
                    .forPath(STORAGE_ENGINE_NODE, "".getBytes(StandardCharsets.UTF_8));
            long id = Long.parseLong(nodeName.substring(STORAGE_ENGINE_NODE.length()));
            storageEngine.setId(id);
            this.client.setData()
                    .forPath(nodeName, JsonUtils.toJson(storageEngine));
            return id;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when adding storage engine: ", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + SCHEMA_MAPPING_LOCK_NODE + ": ", e);
            }
        }
    }

    private void registerStorageEngineListener() throws Exception {
        this.storageEngineCache = new TreeCache(this.client, STORAGE_ENGINE_NODE_PREFIX);
        TreeCacheListener listener = (curatorFramework, event) -> {
            if (storageChangeHook == null) {
                return;
            }
            byte[] data;
            StorageEngineMeta storageEngineMeta;
            switch (event.getType()) {
                case NODE_ADDED:
                case NODE_UPDATED:
                    if (event.getData().getPath().equals(STORAGE_ENGINE_NODE_PREFIX)) {
                        break;
                    }
                    data = event.getData().getData();
                    logger.info("storage engine meta update: " + event.getData().getPath());
                    logger.info("storage engine: " + new String(data));
                    storageEngineMeta = JsonUtils.fromJson(data, StorageEngineMeta.class);
                    if (storageEngineMeta != null) {
                        logger.info("new storage engine comes to cluster: id = " + storageEngineMeta.getId() + " , ip = " + storageEngineMeta.getIp() + " , port = " + storageEngineMeta.getPort());
                        storageChangeHook.onChange(storageEngineMeta.getId(), storageEngineMeta);
                    } else {
                        logger.error("encounter error when resolving storage engine from zookeeper");
                    }
                    break;
                case NODE_REMOVED:
                    data = event.getData().getData();
                    String path = event.getData().getPath();
                    logger.info("node " + path + " is removed");
                    if (path.equals(IGINX_NODE_PREFIX)) {
                        // 根节点被删除
                        logger.info("all iginx leave from cluster, iginx exit");
                        System.exit(2);
                        break;
                    }
                    storageEngineMeta = JsonUtils.fromJson(data, StorageEngineMeta.class);
                    if (storageEngineMeta != null) {
                        logger.info("storage engine leave from cluster: id = " + storageEngineMeta.getId() + " , ip = " + storageEngineMeta.getIp() + " , port = " + storageEngineMeta.getPort());
                        storageChangeHook.onChange(storageEngineMeta.getId(), null);
                    } else {
                        logger.error("encounter error when resolving storage engine from zookeeper");
                    }
                    break;
                default:
                    break;
            }
        };
        this.storageEngineCache.getListenable().addListener(listener);
        this.storageEngineCache.start();
    }

    @Override
    public void registerStorageChangeHook(StorageChangeHook hook) {
        this.storageChangeHook = hook;
    }

    @Override
    public Map<String, StorageUnitMeta> loadStorageUnit() throws MetaStorageException {
        try {
            Map<String, StorageUnitMeta> storageUnitMetaMap = new HashMap<>();
            if (this.client.checkExists().forPath(STORAGE_UNIT_NODE_PREFIX) == null) {
                // 当前还没有数据，创建父节点，然后不需要解析数据
                this.client.create().withMode(CreateMode.PERSISTENT).forPath(STORAGE_UNIT_NODE_PREFIX);
            } else {
                List<String> storageUnitIds = this.client.getChildren().forPath(STORAGE_UNIT_NODE_PREFIX);
                storageUnitIds.sort(String::compareTo);
                for (String storageUnitId : storageUnitIds) {
                    logger.info("load storage unit: " + storageUnitId);
                    byte[] data = this.client.getData()
                            .forPath(STORAGE_UNIT_NODE_PREFIX + "/" + storageUnitId);
                    StorageUnitMeta storageUnitMeta = JsonUtils.fromJson(data, StorageUnitMeta.class);
                    if (!storageUnitMeta.isMaster()) { // 需要加入到主节点的子节点列表中
                        StorageUnitMeta masterStorageUnitMeta = storageUnitMetaMap.get(storageUnitMeta.getMasterId());
                        if (masterStorageUnitMeta == null) { // 子节点先于主节点加入系统中，不应该发生，报错
                            logger.error("unexpected storage unit " + new String(data) + ", because it does not has a master storage unit");
                        } else {
                            masterStorageUnitMeta.addReplica(storageUnitMeta);
                        }
                    }
                    storageUnitMetaMap.put(storageUnitMeta.getId(), storageUnitMeta);
                }
            }
            if (this.storageUnitCache == null) {
                registerStorageUnitListener();
            }
            return storageUnitMetaMap;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when loading storage unit: ", e);
        }
    }

    @Override
    public void lockStorageUnit() throws MetaStorageException {
        try {
            storageUnitMutexLock.lock();
            storageUnitMutex.acquire();
        } catch (Exception e) {
            storageUnitMutexLock.unlock();
            throw new MetaStorageException("encounter error when acquiring storage unit mutex: ", e);
        }
    }

    @Override
    public String addStorageUnit() throws MetaStorageException { // 只在有锁的情况下调用，内部不需要加锁
        try {
            String nodeName = this.client.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.PERSISTENT_SEQUENTIAL)
                    .forPath(STORAGE_UNIT_NODE, "".getBytes(StandardCharsets.UTF_8));
            return nodeName.substring(STORAGE_UNIT_NODE_PREFIX.length() + 1);
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when adding storage unit: ", e);
        }
    }

    @Override
    public void updateStorageUnit(StorageUnitMeta storageUnitMeta) throws MetaStorageException { // 只在有锁的情况下调用，内部不需要加锁
        try {
            this.client.setData()
                    .forPath(STORAGE_UNIT_NODE_PREFIX + "/" + storageUnitMeta.getId(), JsonUtils.toJson(storageUnitMeta));
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when updating storage unit: ", e);
        }
    }

    @Override
    public void releaseStorageUnit() throws MetaStorageException {
        try {
            storageUnitMutex.release();
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when releasing storage mutex: ", e);
        } finally {
            storageUnitMutexLock.unlock();
        }
    }

    private void registerStorageUnitListener() throws Exception {
        this.storageUnitCache = new TreeCache(this.client, STORAGE_UNIT_NODE_PREFIX);
        TreeCacheListener listener = (curatorFramework, event) -> {
            if (storageUnitChangeHook == null) {
                return;
            }
            switch (event.getType()) {
                case NODE_ADDED:
                case NODE_UPDATED:
                    byte[] data = event.getData().getData();
                    if (event.getData().getPath().equals(STORAGE_UNIT_NODE_PREFIX)) {
                        break;
                    }
                    StorageUnitMeta storageUnitMeta = JsonUtils.fromJson(data, StorageUnitMeta.class);
                    if (storageUnitMeta != null) {
                        logger.info("new storage unit comes to cluster: id = " + storageUnitMeta.getId());
                        storageUnitChangeHook.onChange(storageUnitMeta.getId(), storageUnitMeta);
                    }
                    break;
            }
        };
        this.storageUnitCache.getListenable().addListener(listener);
        this.storageUnitCache.start();
    }

    @Override
    public void registerStorageUnitChangeHook(StorageUnitChangeHook hook) {
        this.storageUnitChangeHook = hook;
    }

    @Override
    public Map<TimeSeriesInterval, List<FragmentMeta>> loadFragment() throws MetaStorageException {
        try {
            Map<TimeSeriesInterval, List<FragmentMeta>> fragmentListMap = new HashMap<>();
            if (this.client.checkExists().forPath(FRAGMENT_NODE_PREFIX) == null) {
                // 当前还没有数据，创建父节点，然后不需要解析数据
                this.client.create().withMode(CreateMode.PERSISTENT).forPath(FRAGMENT_NODE_PREFIX);
            } else {
                List<String> tsIntervalNames = this.client.getChildren().forPath(FRAGMENT_NODE_PREFIX);
                for (String tsIntervalName : tsIntervalNames) {
                    TimeSeriesInterval fragmentTimeSeries = TimeSeriesInterval.fromString(tsIntervalName);
                    List<FragmentMeta> fragmentMetaList = new ArrayList<>();
                    List<String> timeIntervalNames = this.client.getChildren().forPath(FRAGMENT_NODE_PREFIX + "/" + tsIntervalName);
                    for (String timeIntervalName : timeIntervalNames) {
                        FragmentMeta fragmentMeta = JsonUtils.fromJson(this.client.getData()
                                .forPath(FRAGMENT_NODE_PREFIX + "/" + tsIntervalName + "/" + timeIntervalName), FragmentMeta.class);
                        fragmentMetaList.add(fragmentMeta);
                    }
                    fragmentListMap.put(fragmentTimeSeries, fragmentMetaList);
                }
            }
            if (this.fragmentCache == null) {
                registerFragmentListener();
            }
            return fragmentListMap;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when updating schema mapping: ", e);
        }
    }

    private void registerFragmentListener() throws Exception {
        this.fragmentCache = new TreeCache(this.client, FRAGMENT_NODE_PREFIX);
        TreeCacheListener listener = (curatorFramework, event) -> {
            if (fragmentChangeHook == null) {
                return;
            }
            byte[] data;
            FragmentMeta fragmentMeta;
            switch (event.getType()) {
                case NODE_UPDATED:
                    data = event.getData().getData();
                    fragmentMeta = JsonUtils.fromJson(data, FragmentMeta.class);
                    if (fragmentMeta != null) {
                        fragmentChangeHook.onChange(false, fragmentMeta);
                    } else {
                        logger.error("encounter error when resolving fragment from zookeeper");
                    }
                    break;
                case NODE_ADDED:
                    String path = event.getData().getPath();
                    String[] pathParts = path.split("/");
                    if (pathParts.length == 4) {
                        fragmentMeta = JsonUtils.fromJson(event.getData().getData(), FragmentMeta.class);
                        if (fragmentMeta != null) {
                            fragmentChangeHook.onChange(true, fragmentMeta);
                        } else {
                            logger.error("encounter error when resolving fragment from zookeeper");
                        }
                    }
                    break;
                default:
                    break;
            }
        };
        this.fragmentCache.getListenable().addListener(listener);
        this.fragmentCache.start();
    }

    @Override
    public void lockFragment() throws MetaStorageException {
        try {
            fragmentMutexLock.lock();
            fragmentMutex.acquire();
        } catch (Exception e) {
            fragmentMutexLock.unlock();
            throw new MetaStorageException("encounter error when acquiring fragment mutex: ", e);
        }
    }

    @Override
    public void updateFragment(FragmentMeta fragmentMeta) throws MetaStorageException { // 只在有锁的情况下调用，内部不需要加锁
        try {
            this.client.setData()
                    .forPath(FRAGMENT_NODE_PREFIX + "/" + fragmentMeta.getTsInterval().toString() + "/" + fragmentMeta.getTimeInterval().toString(), JsonUtils.toJson(fragmentMeta));
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when updating fragment: ", e);
        }
    }

    @Override
    public void addFragment(FragmentMeta fragmentMeta) throws MetaStorageException { // 只在有锁的情况下调用，内部不需要加锁
        try {
            this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                    .forPath(FRAGMENT_NODE_PREFIX + "/" + fragmentMeta.getTsInterval().toString() + "/" + fragmentMeta.getTimeInterval().toString(), JsonUtils.toJson(fragmentMeta));
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when adding fragment: ", e);
        }
    }

    @Override
    public void releaseFragment() throws MetaStorageException {
        try {
            fragmentMutex.release();
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when releasing fragment mutex: ", e);
        } finally {
            fragmentMutexLock.unlock();
        }
    }

    @Override
    public void registerFragmentChangeHook(FragmentChangeHook hook) {
        this.fragmentChangeHook = hook;
    }

    private void registerUserListener() throws Exception {
        this.userCache = new TreeCache(this.client, USER_NODE_PREFIX);
        TreeCacheListener listener = (curatorFramework, event) -> {
            if (userChangeHook == null) {
                return;
            }
            UserMeta userMeta;
            switch (event.getType()) {
                case NODE_ADDED:
                case NODE_UPDATED:
                    if (event.getData().getPath().equals(USER_NODE_PREFIX)) {
                        return; // 前缀事件，非含数据的节点的变化，不需要处理
                    }
                    userMeta = JsonUtils.fromJson(event.getData().getData(), UserMeta.class);
                    if (userMeta != null) {
                        userChangeHook.onChange(userMeta.getUsername(), userMeta);
                    } else {
                        logger.error("encounter error when resolving user from zookeeper");
                    }
                    break;
                case NODE_REMOVED:
                    String path = event.getData().getPath();
                    String[] pathParts = path.split("/");
                    String username = pathParts[pathParts.length - 1];
                    userChangeHook.onChange(username, null);
                    break;
                default:
                    break;
            }
        };
        this.userCache.getListenable().addListener(listener);
        this.userCache.start();
    }

    @Override
    public List<UserMeta> loadUser(UserMeta userMeta) throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(this.client, USER_LOCK_NODE);
        try {
            mutex.acquire();
            if (this.client.checkExists().forPath(USER_NODE_PREFIX) == null) { // 节点不存在，说明系统中第一个用户还没有被创建
                this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                        .forPath(USER_NODE_PREFIX + "/" + userMeta.getUsername(), JsonUtils.toJson(userMeta));
            }
            List<UserMeta> users = new ArrayList<>();
            List<String> usernames = this.client.getChildren().forPath(USER_NODE_PREFIX);
            for (String username : usernames) {
                byte[] data = this.client.getData()
                        .forPath(USER_NODE_PREFIX + "/" + username);
                UserMeta user = JsonUtils.fromJson(data, UserMeta.class);
                if (user == null) {
                    logger.error("encounter error when resolving data from " + USER_NODE_PREFIX + "/" + username);
                    continue;
                }
                users.add(user);
            }
            registerUserListener();
            return users;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when loading user: ", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + USER_LOCK_NODE + ": ", e);
            }
        }
    }

    @Override
    public void registerUserChangeHook(UserChangeHook hook) {
        this.userChangeHook = hook;
    }

    @Override
    public void addUser(UserMeta userMeta) throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(this.client, USER_LOCK_NODE);
        try {
            mutex.acquire();
            this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                    .forPath(USER_NODE_PREFIX + "/" + userMeta.getUsername(), JsonUtils.toJson(userMeta));
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when adding user: ", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + USER_LOCK_NODE + ": ", e);
            }
        }
    }

    @Override
    public void updateUser(UserMeta userMeta) throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(this.client, USER_LOCK_NODE);
        try {
            mutex.acquire();
            this.client.setData()
                    .forPath(USER_NODE_PREFIX + "/" + userMeta.getUsername(), JsonUtils.toJson(userMeta));
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when updating user: ", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + USER_LOCK_NODE + ": ", e);
            }
        }
    }

    @Override
    public void removeUser(String username) throws MetaStorageException {
        InterProcessMutex mutex = new InterProcessMutex(this.client, USER_LOCK_NODE);
        try {
            mutex.acquire();
            this.client.delete()
                    .forPath(USER_NODE_PREFIX + "/" + username);
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when removing user: ", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("encounter error when releasing interprocess lock for " + USER_LOCK_NODE + ": ", e);
            }
        }
    }

    @Override
    public Map<FragmentMeta, FragmentStatistics> loadActiveFragmentStatistics() throws MetaStorageException {
        try {
            Map<FragmentMeta, FragmentStatistics> activeFragmentStatisticsMap = new HashMap<>();
            if (this.client.checkExists().forPath(ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX) == null) {
                client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX);
            } else {
                List<String> children = client.getChildren().forPath(ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX);
                for (String childName : children) {
                    byte[] data = client.getData()
                            .forPath(ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX + "/" + childName);
                    Map<FragmentMeta, FragmentStatistics> deltaActiveFragmentStatisticsMap = JsonUtils.fromJson(data, activeFragmentStatisticsMap.getClass());
                    if (deltaActiveFragmentStatisticsMap == null) {
                        logger.error("encounter error when resolving data from " + ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX + "/" + childName);
                        continue;
                    }
                    deltaActiveFragmentStatisticsMap.forEach((key, value) -> activeFragmentStatisticsMap.computeIfAbsent(key, e -> new FragmentStatistics()).update(value));
                }
            }
            registerActiveFragmentStatisticsListener();
            registerReshardNotificationListener();
            registerReshardCounterListener();
            return activeFragmentStatisticsMap;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when loading active fragment statistics: ", e);
        }
    }

    private void registerActiveFragmentStatisticsListener() throws Exception {
        this.activeFragmentStatisticsCache = new TreeCache(this.client, ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX);
        TreeCacheListener listener = (curatorFramework, event) -> {
            if (activeFragmentStatisticsChangeHook == null) {
                return;
            }
            String path;
            byte[] data;
            Map<FragmentMeta, FragmentStatistics> statisticsMap;
            switch (event.getType()) {
                case NODE_ADDED:
                case NODE_UPDATED:
                    path = event.getData().getPath();
                    data = event.getData().getData();
                    String[] pathParts = path.split("/");
                    if (pathParts.length == 6) {
                        statisticsMap = JsonUtils.getGson().fromJson(new String(data), new TypeToken<Map<FragmentMeta, FragmentStatistics>>() {}.getType());
                        if (statisticsMap != null) {
                            activeFragmentStatisticsChangeHook.onChange(statisticsMap);
                        } else {
                            logger.error("encounter error when resolving active fragment statistics from zookeeper");
                        }
                    }
                    break;
                default:
                    break;
            }
        };
        this.activeFragmentStatisticsCache.getListenable().addListener(listener);
        this.activeFragmentStatisticsCache.start();
    }

    @Override
    public void lockActiveFragmentStatistics() throws MetaStorageException {
        try {
            activeFragmentStatisticsMutexLock.lock();
        } catch (Exception e) {
            activeFragmentStatisticsMutexLock.unlock();
            throw new MetaStorageException("encounter error when locking active fragment statistics: ", e);
        }
    }

    @Override
    public void addActiveFragmentStatistics(long id, Map<FragmentMeta, FragmentStatistics> deltaActiveFragmentStatistics) throws MetaStorageException {
        try {
            this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT_SEQUENTIAL)
                    .forPath(ACTIVE_FRAGMENT_STATISTICS_NODE + String.format("%010d", id) + SINGLE_ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX, JsonUtils.toJson(deltaActiveFragmentStatistics));
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when adding active fragment statistics: ", e);
        }
    }

    @Override
    public void addInactiveFragmentStatistics(Map<FragmentMeta, FragmentStatistics> activeFragmentStatistics, long endTime) throws MetaStorageException {
        try {
            for (FragmentStatistics statistics : activeFragmentStatistics.values()) {
                String path = INACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX + "/" + endTime + "/" + statistics.getTsInterval().toString();
                if (this.client.checkExists().forPath(path) == null) {
                    this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path, JsonUtils.toJson(statistics));
                } else {
                    this.client.setData().forPath(path, JsonUtils.toJson(statistics));
                }
            }
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when adding inactive fragment statistics: ", e);
        }
    }

    @Override
    public void registerTimeseriesChangeHook(TimeseriesChangeHook hook) {
        this.timeseriesChangeHook = hook;
    }

    @Override
    public void registerVersionChangeHook(VersionChangeHook hook) {
        this.versionChangeHook = hook;
    }

    @Override
    public boolean election()
    {
        if (isMaster) {
            return true;
        }
        try {
            this.client.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(POLICY_LEADER);
            logger.info("成功");
            isMaster = true;
        } catch (KeeperException.NodeExistsException e) {
            logger.info("失败");
            isMaster = false;
        }
        finally
        {
            return isMaster;
        }
    }

    @Override
    public void releaseActiveFragmentStatistics() throws MetaStorageException {
        activeFragmentStatisticsMutexLock.unlock();
    }

    @Override
    public void removeActiveFragmentStatistics() throws MetaStorageException {
        try {
            if (this.client.checkExists().forPath(ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX) != null) {
                this.client.delete().deletingChildrenIfNeeded().forPath(ACTIVE_FRAGMENT_STATISTICS_NODE_PREFIX);
            }
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when removing active fragment statistics: ", e);
        }
    }

    @Override
    public void updateTimeseriesData(Map<String, Double> timeseriesData, long iginxid, long version) throws Exception {
        InterProcessMutex mutex = new InterProcessMutex(this.client, POLICY_LOCK_NODE);
        try {
            mutex.acquire();
            if (this.client.checkExists().forPath(TIMESERIES_NODE_PREFIX + "/" + iginxid) == null) {
                this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT). forPath(TIMESERIES_NODE_PREFIX + "/" + iginxid, tobytes(timeseriesData));
            } else {
                this.client.setData().forPath(TIMESERIES_NODE_PREFIX + "/" + iginxid, tobytes(timeseriesData));
            }
            this.client.setData().forPath(POLICY_NODE_PREFIX + "/" + iginxid, String.valueOf(version).getBytes());
        } catch (Exception e) {
            throw new MetaStorageException("get error when update timeseries", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("get error when release interprocess lock for " + POLICY_LOCK_NODE, e);
            }
        }
    }

    @Override
    public void registerActiveFragmentStatisticsChangeHook(ActiveFragmentStatisticsChangeHook hook) {
        this.activeFragmentStatisticsChangeHook = hook;
    }

    @Override
    public boolean proposeToReshard() throws MetaStorageException {
        try {
            boolean resharding = true;
            if (this.client.checkExists().forPath(RESHARD_NOTIFICATION_NODE_PREFIX) == null) {
                this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                        .forPath(RESHARD_NOTIFICATION_NODE_PREFIX, JsonUtils.toJson(true));
            } else {
                resharding = JsonUtils.fromJson(
                        this.client.getData().forPath(RESHARD_NOTIFICATION_NODE_PREFIX), Boolean.class);
                if (!resharding) {
                    this.client.setData()
                            .forPath(RESHARD_NOTIFICATION_NODE_PREFIX, JsonUtils.toJson(true));
                    resharding = true;
                } else {
                    resharding = false;
                }
            }
            return resharding;
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when proposing to reshard: ", e);
        }
    }

    @Override
    public void lockReshardNotification() throws MetaStorageException {
        try {
            reshardNotificationMutexLock.lock();
            reshardNotificationMutex.acquire();
        } catch (Exception e) {
            reshardNotificationMutexLock.unlock();
            throw new MetaStorageException("encounter error when acquiring reshard notification mutex: ", e);
        }
    }

    @Override
    public void updateReshardNotification(boolean notification) throws MetaStorageException {
        try {
            if (this.client.checkExists().forPath(RESHARD_NOTIFICATION_NODE_PREFIX) == null) {
                this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                        .forPath(RESHARD_NOTIFICATION_NODE_PREFIX, JsonUtils.toJson(notification));
            } else {
                this.client.setData()
                        .forPath(RESHARD_NOTIFICATION_NODE_PREFIX, JsonUtils.toJson(notification));
            }
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when updating reshard notification: ", e);
        }
    }

    @Override
    public void releaseReshardNotification() throws MetaStorageException {
        try {
            reshardNotificationMutex.release();
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when releasing reshard notification mutex: ", e);
        } finally {
            reshardNotificationMutexLock.unlock();
        }
    }

    @Override
    public void removeReshardNotification() throws MetaStorageException {
        try {
            if (this.client.checkExists().forPath(RESHARD_NOTIFICATION_NODE_PREFIX) != null) {
                this.client.delete().forPath(RESHARD_NOTIFICATION_NODE_PREFIX);
            }
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when removing reshard notification: ", e);
        }
    }

    @Override
    public void registerReshardNotificationHook(ReshardNotificationHook hook) {
        this.reshardNotificationHook = hook;
    }

    private void registerReshardNotificationListener() throws Exception {
        this.reshardNotificationCache = new TreeCache(this.client, RESHARD_NOTIFICATION_NODE_PREFIX);
        TreeCacheListener listener = (curatorFramework, event) -> {
            byte[] data;
            boolean notification;
            switch (event.getType()) {
                case NODE_ADDED:
                case NODE_UPDATED:
                    data = event.getData().getData();
                    notification = JsonUtils.fromJson(data, Boolean.class);
                    reshardNotificationHook.onChange(notification);
                    break;
                default:
                    break;
            }
        };
        this.reshardNotificationCache.getListenable().addListener(listener);
        this.reshardNotificationCache.start();
    }

    @Override
    public void lockReshardCounter() throws MetaStorageException {
        try {
            reshardCounterMutexLock.lock();
            reshardCounterMutex.acquire();
        } catch (Exception e) {
            reshardCounterMutexLock.unlock();
            throw new MetaStorageException("encounter error when acquiring reshard counter mutex: ", e);
        }
    }

    @Override
    public void incrementReshardCounter() throws MetaStorageException {
        try {
            if (this.client.checkExists().forPath(RESHARD_COUNTER_NODE_PREFIX) == null) {
                this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                        .forPath(RESHARD_COUNTER_NODE_PREFIX, JsonUtils.toJson(1));
            } else {
                int counter = JsonUtils.fromJson(
                        this.client.getData().forPath(RESHARD_COUNTER_NODE_PREFIX), Integer.class);
                this.client.setData()
                        .forPath(RESHARD_COUNTER_NODE_PREFIX, JsonUtils.toJson(counter + 1));
            }
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when updating reshard counter: ", e);
        }
    }

    @Override
    public void resetReshardCounter() throws MetaStorageException {
        try {
            if (this.client.checkExists().forPath(RESHARD_COUNTER_NODE_PREFIX) == null) {
                this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                        .forPath(RESHARD_COUNTER_NODE_PREFIX, JsonUtils.toJson(0));
            } else {
                this.client.setData()
                        .forPath(RESHARD_COUNTER_NODE_PREFIX, JsonUtils.toJson(0));
            }
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when resetting reshard counter: ", e);
        }
    }

    @Override
    public Map<String, Double> getTimeseriesData() {
        Map<String, Double> ret = new HashMap<>();
        try {
            Set<Integer> idSet = loadIginx().keySet().stream().
                    map(Long::intValue).collect(Collectors.toSet());
            List<String> children = client.getChildren().forPath(TIMESERIES_NODE_PREFIX);
            for (String child: children) {
                byte[] data = this.client.getData()
                        .forPath(TIMESERIES_NODE_PREFIX + "/" + child);
                if (!idSet.contains(Integer.parseInt(child))) {
                    continue;
                }
                Map<String, Double> tmp = toMap(data);
                if (tmp == null) {
                    logger.error("resolve data from " + TIMESERIES_NODE_PREFIX + "/" + child + " error");
                    continue;
                }
                tmp.forEach((timeseries, value) -> {
                    double now = 0.0;
                    if (ret.containsKey(timeseries)) {
                        now = ret.get(timeseries);
                    }
                    ret.put(timeseries, now + value);
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ret;
    }


    public byte[] tobytes(Map<String, Double> prefix) {
        StringBuilder ret = new StringBuilder();
        for (Map.Entry<String, Double> entry: prefix.entrySet()) {
            ret.append(entry.getKey());
            ret.append("~");
            ret.append(entry.getValue());
            ret.append("^");
        }
        if (ret.length() != 0 && ret.charAt(ret.length() - 1) == '^') {
            ret.deleteCharAt(ret.length() - 1);
        }
        logger.info(ret.toString());
        return ret.toString().getBytes();
    }


    public Map<String, Double> toMap(byte[] prefix) {
        logger.info(new String(prefix));
        Map<String, Double> ret = new HashMap<>();
        String str = new String(prefix);
        String[] tmp = str.split("\\^");
        for (String entry: Arrays.asList(tmp))
        {
            String[] tmp2 = entry.split("~");
            try
            {
                ret.put(tmp2[0], Double.parseDouble(tmp2[1]));
            }
            catch (Exception e)
            {
                logger.error(entry);
            }
        }
        return ret;
    }

    @Override
    public void registerPolicy(long iginxId, int num) throws Exception {
        InterProcessMutex mutex = new InterProcessMutex(this.client, POLICY_LOCK_NODE);
        try {
            mutex.acquire();
            if (client.checkExists().forPath(POLICY_NODE_PREFIX) == null) {
                this.client.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.PERSISTENT)
                        .forPath(POLICY_NODE_PREFIX);
            }

            if (client.checkExists().forPath(TIMESERIES_NODE_PREFIX) == null) {
                this.client.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.PERSISTENT)
                        .forPath(TIMESERIES_NODE_PREFIX);
            }
            if (client.checkExists().forPath(POLICY_VERSION) == null) {
                this.client.create()
                        .creatingParentsIfNeeded()
                        .withMode(CreateMode.PERSISTENT)
                        .forPath(POLICY_VERSION);

                this.client.setData().forPath(POLICY_VERSION, ("0" + "\t" + num).getBytes());
            }

            this.client.create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.PERSISTENT)
                .forPath(POLICY_NODE_PREFIX + "/" + iginxId);

            this.client.setData().forPath(POLICY_NODE_PREFIX + "/" + iginxId, "0".getBytes());

            this.client.create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.PERSISTENT)
                .forPath(TIMESERIES_NODE_PREFIX + "/" + iginxId);

            this.policyCache = new TreeCache(this.client, POLICY_LEADER);
            TreeCacheListener listener = (curatorFramework, event) -> {
                switch (event.getType()) {
                    case NODE_REMOVED:
                        election();
                        break;
                    default:
                        break;
                }
            };
            this.policyCache.getListenable().addListener(listener);
            this.policyCache.start();

            this.timeseriesCache = new TreeCache(this.client, POLICY_NODE_PREFIX);
            TreeCacheListener listener2 = (curatorFramework, event) -> {
                switch (event.getType())
                {
                    case NODE_ADDED:
                    case NODE_UPDATED:
                        String path = event.getData().getPath();
                        String[] pathParts = path.split("/");
                        int version = -1;
                        int node = -1;
                        if (pathParts.length == 2 && isNumeric(pathParts[1])) {
                            version = Integer.parseInt(new String(event.getData().getData()));
                            node = Integer.parseInt(pathParts[1]);
                        }
                        if (version != -1 && node != -1) {
                            timeseriesChangeHook.onChange(node, version);
                        }
                        break;
                    default:
                        break;
                }
            };
            this.timeseriesCache.getListenable().addListener(listener2);
            this.timeseriesCache.start();

            this.versionCache = new TreeCache(this.client, POLICY_VERSION);
            TreeCacheListener listener3 = (curatorFramework, event) -> {
                switch (event.getType())
                {
                    case NODE_UPDATED:
                        String[] str = new String(event.getData().getData()).split("\t");
                        int version = Integer.parseInt(str[0]);
                        int newNum = Integer.parseInt(str[1]);
                        if (version > 0) {
                            versionChangeHook.onChange(version, newNum);
                        } else {
                            logger.error("resolve prefix from zookeeper error");
                        }
                        break;
                    default:
                        break;
                }
            };
            this.versionCache.getListenable().addListener(listener3);
            this.versionCache.start();

        } catch (Exception e) {
            throw new MetaStorageException("get error when init policy", e);
        } finally {
            try {
                mutex.release();
            } catch (Exception e) {
                throw new MetaStorageException("get error when release interprocess lock for " + POLICY_LOCK_NODE, e);
            }
        }
    }

    @Override
    public void releaseReshardCounter() throws MetaStorageException {
        try {
            reshardCounterMutex.release();
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when releasing reshard counter mutex: ", e);
        } finally {
            reshardCounterMutexLock.unlock();
        }
    }

    @Override
    public void removeReshardCounter() throws MetaStorageException {
        try {
            if (this.client.checkExists().forPath(RESHARD_COUNTER_NODE_PREFIX) != null) {
                this.client.delete().forPath(RESHARD_COUNTER_NODE_PREFIX);
            }
        } catch (Exception e) {
            throw new MetaStorageException("encounter error when removing reshard counter: ", e);
        }
    }

    @Override
    public void registerReshardCounterChangeHook(ReshardCounterChangeHook hook) {
        this.reshardCounterChangeHook = hook;
    }

    private void registerReshardCounterListener() throws Exception {
        this.reshardCounterCache = new TreeCache(this.client, RESHARD_COUNTER_NODE_PREFIX);
        TreeCacheListener listener = (curatorFramework, event) -> {
            byte[] data;
            int counter;
            switch (event.getType()) {
                case NODE_ADDED:
                case NODE_UPDATED:
                    data = event.getData().getData();
                    counter = JsonUtils.fromJson(data, Integer.class);
                    reshardCounterChangeHook.onChange(counter);
                    break;
                default:
                    break;
            }
        };
        this.reshardCounterCache.getListenable().addListener(listener);
        this.reshardCounterCache.start();
    }

    @Override
    public int updateVersion() {
        int version = -1;
        try
        {
            version = Integer.parseInt(new String(client.getData().forPath(POLICY_VERSION)).split("\t")[0]);
            this.client.setData().forPath(POLICY_VERSION, ((version + 1) + "\t" + "0").getBytes());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return version + 1;
    }

    public static boolean isNumeric(String str) {
        String bigStr;
        try {
            bigStr = new BigDecimal(str).toString();
        } catch (Exception e) {
            return false;//异常 说明包含非数字。
        }
        return true;
    }
}
