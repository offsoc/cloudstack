// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.capacity.dao;

import java.util.List;
import java.util.Map;

import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDaoImpl.SummedCapacity;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.GenericDao;

public interface CapacityDao extends GenericDao<CapacityVO, Long> {
    CapacityVO findByHostIdType(Long hostId, short capacityType);

    List<CapacityVO> listByHostIdTypes(Long hostId, List<Short> capacityTypes);

    List<Long> listClustersInZoneOrPodByHostCapacities(long id, long vmId, int requiredCpu, long requiredRam, boolean isZone);

    List<Long> listHostsWithEnoughCapacity(int requiredCpu, long requiredRam, Long clusterId, String hostType);

    boolean removeBy(Short capacityType, Long zoneId, Long podId, Long clusterId, Long hostId);

    List<SummedCapacity> findByClusterPodZone(Long zoneId, Long podId, Long clusterId);

    List<SummedCapacity> findNonSharedStorageForClusterPodZone(Long zoneId, Long podId, Long clusterId);

    Pair<List<Long>, Map<Long, Double>> orderClustersByAggregateCapacity(long id, long vmId, short capacityType, boolean isZone);

    Ternary<Long, Long, Long> findCapacityByZoneAndHostTag(Long zoneId, String hostTag);

    List<SummedCapacity> findCapacityBy(Integer capacityType, Long zoneId, Long podId, Long clusterId);

    List<SummedCapacity> findFilteredCapacityBy(Integer capacityType, Long zoneId, Long podId, Long clusterId, List<Long> hostIds, List<Long> poolIds);

    List<Long> listPodsByHostCapacities(long zoneId, int requiredCpu, long requiredRam);

    Pair<List<Long>, Map<Long, Double>> orderPodsByAggregateCapacity(long zoneId, short capacityType);

    List<SummedCapacity> findCapacityBy(Integer capacityType, Long zoneId,
        Long podId, Long clusterId, String resourceState);

    List<SummedCapacity> listCapacitiesGroupedByLevelAndType(Integer capacityType, Long zoneId, Long podId,
         Long clusterId, int level, List<Long> hostIds, List<Long> poolIds, Long limit);

    void updateCapacityState(Long dcId, Long podId, Long clusterId, Long hostId, String capacityState, short[] capacityType);

    List<Long> listClustersCrossingThreshold(short capacityType, Long zoneId, String configName, long computeRequested);

    float findClusterConsumption(Long clusterId, short capacityType, long computeRequested);

    Pair<List<Long>, Map<Long, Double>> orderHostsByFreeCapacity(Long zoneId, Long clusterId, short capacityType);

    List<CapacityVO> listHostCapacityByCapacityTypes(Long zoneId, Long clusterId, List<Short> capacityTypes);

    List<CapacityVO> listPodCapacityByCapacityTypes(Long zoneId, List<Short> capacityTypes);

    List<CapacityVO> listClusterCapacityByCapacityTypes(Long zoneId, Long podId, List<Short> capacityTypes);
}
