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
package cn.edu.tsinghua.iginx.metadata;

import cn.edu.tsinghua.iginx.metadata.entity.FragmentStatistics;
import cn.edu.tsinghua.iginx.metadata.entity.FragmentMeta;
import cn.edu.tsinghua.iginx.metadata.entity.IginxMeta;
import cn.edu.tsinghua.iginx.metadata.entity.StorageEngineMeta;
import cn.edu.tsinghua.iginx.metadata.entity.StorageUnitMeta;
import cn.edu.tsinghua.iginx.metadata.entity.TimeInterval;
import cn.edu.tsinghua.iginx.metadata.entity.TimeSeriesInterval;
import cn.edu.tsinghua.iginx.metadata.entity.UserMeta;
import cn.edu.tsinghua.iginx.metadata.hook.StorageEngineChangeHook;
import cn.edu.tsinghua.iginx.plan.InsertRecordsPlan;
import cn.edu.tsinghua.iginx.policy.simple.TimeSeriesCalDO;
import cn.edu.tsinghua.iginx.thrift.AuthType;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IMetaManager {

    /**
     * 批量新增存储引擎节点
     */
    boolean addStorageEngines(List<StorageEngineMeta> storageEngineMetas);

    /**
     * 获取所有的存储引擎实例的原信息（包括每个存储引擎的存储单元列表）
     */
    List<StorageEngineMeta> getStorageEngineList();

    /**
     * 获取存储引擎实例的数量
     */
    int getStorageEngineNum();

    /**
     * 获取某个存储引擎实例的原信息（包括存储引擎的存储单元列表）
     */
    StorageEngineMeta getStorageEngine(long id);

    StorageUnitMeta getStorageUnit(String id);

    Map<String, StorageUnitMeta> getStorageUnits(Set<String> ids);

    /**
     * 获取所有活跃的 iginx 节点的元信息
     */
    List<IginxMeta> getIginxList();

    /**
     * 获取当前 iginx 节点的 ID
     */
    long getIginxId();

    /**
     * 获取某个时间序列区间的所有分片
     */
    Map<TimeSeriesInterval, List<FragmentMeta>> getFragmentMapByTimeSeriesInterval(TimeSeriesInterval tsInterval);

    /**
     * 获取某个时间区间的所有最新的分片（这些分片一定也都是未终结的分片）
     */
    Map<TimeSeriesInterval, FragmentMeta> getLatestFragmentMapByTimeSeriesInterval(TimeSeriesInterval tsInterval);

    /**
     * 获取全部最新的分片
     */
    Map<TimeSeriesInterval, FragmentMeta> getLatestFragmentMap();

    /**
     * 获取某个时间序列区间在某个时间区间的所有分片。
     */
    Map<TimeSeriesInterval, List<FragmentMeta>> getFragmentMapByTimeSeriesIntervalAndTimeInterval(TimeSeriesInterval tsInterval,
                                                                                                  TimeInterval timeInterval);

    /**
     * 获取某个时间序列的所有分片（按照分片时间戳排序）
     */
    List<FragmentMeta> getFragmentListByTimeSeriesName(String tsName);

    /**
     * 获取某个时间序列的最新分片
     */
    FragmentMeta getLatestFragmentByTimeSeriesName(String tsName);

    /**
     * 获取某个时间序列在某个时间区间的所有分片（按照分片时间戳排序）
     */
    List<FragmentMeta> getFragmentListByTimeSeriesNameAndTimeInterval(String tsName, TimeInterval timeInterval);

    /**
     * 创建分片和存储单元
     */
    boolean createFragmentsAndStorageUnits(List<StorageUnitMeta> storageUnits, List<FragmentMeta> fragments);

    /**
     * 是否已经创建过分片
     */
    boolean hasFragment();

    /**
     * 创建初始分片和初始存储单元
     */
    boolean createInitialFragmentsAndStorageUnits(List<StorageUnitMeta> storageUnits, List<FragmentMeta> initialFragments);

    /**
     * 为新创建的分片选择存储引擎实例
     *
     * @return 选出的存储引擎实例 Id 列表
     */
    List<Long> selectStorageEngineIdList();

    void registerStorageEngineChangeHook(StorageEngineChangeHook hook);

    /**
     * 增加或更新 schemaMappings
     *
     * @param schema        待更新的 schema 名
     * @param schemaMapping 待更新的 schema，如果 schema 为空，则表示删除给定的 schema
     */
    void addOrUpdateSchemaMapping(String schema, Map<String, Integer> schemaMapping);

    /**
     * 增加或更新某个给定 schemaMapping 的数据项
     *
     * @param schema 待更新的 schema 名
     * @param key    待更新的数据项的名
     * @param value  待更新的数据项，如果 value = -1 表示删除该数据项
     */
    void addOrUpdateSchemaMappingItem(String schema, String key, int value);

    /**
     * 获取某个 schemaMapping
     *
     * @param schema 需要获取的 schema
     * @return schema。如果不存在则返回空指针
     */
    Map<String, Integer> getSchemaMapping(String schema);

    /**
     * 获取某个 schemaMapping 中的数据项
     *
     * @param schema 需要获取的 schema
     * @param key    需要获取的数据项的名
     * @return 数据项的值。如果不存在则返回 -1
     */
    int getSchemaMappingItem(String schema, String key);

    /**
     * 更新活跃的分片的统计信息
     *
     * @param statisticsMap 活跃的分片的关于当前请求的统计信息
     */
    void updateActiveFragmentStatistics(Map<FragmentMeta, FragmentStatistics> statisticsMap);

    Map<FragmentMeta, FragmentStatistics> getActiveFragmentStatistics();

    boolean addUser(UserMeta user);

    boolean updateUser(String username, String password, Set<AuthType> auths);

    boolean removeUser(String username);

    UserMeta getUser(String username);

    List<UserMeta> getUsers();

    List<UserMeta> getUsers(List<String> username);

    boolean election();

    void saveTimeSeriesData(InsertRecordsPlan plan);

    List<TimeSeriesCalDO> getMaxValueFromTimeSeries();

    Map<String, Double> getTimeseriesData();

    int updateVersion();

    Map<Integer, Integer> getTimeseriesVersionMap();

}
