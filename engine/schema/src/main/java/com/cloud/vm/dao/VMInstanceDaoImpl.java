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
package com.cloud.vm.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Attribute;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.db.UpdateBuilder;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachine.Type;

@Component
public class VMInstanceDaoImpl extends GenericDaoBase<VMInstanceVO, Long> implements VMInstanceDao {

    static final int MAX_CONSECUTIVE_SAME_STATE_UPDATE_COUNT = 3;

    protected SearchBuilder<VMInstanceVO> VMClusterSearch;
    protected SearchBuilder<VMInstanceVO> LHVMClusterSearch;
    protected SearchBuilder<VMInstanceVO> IdStatesSearch;
    protected SearchBuilder<VMInstanceVO> AllFieldsSearch;
    protected SearchBuilder<VMInstanceVO> IdServiceOfferingIdSelectSearch;
    protected SearchBuilder<VMInstanceVO> ZoneTemplateNonExpungedSearch;
    protected SearchBuilder<VMInstanceVO> TemplateNonExpungedSearch;
    protected SearchBuilder<VMInstanceVO> NameLikeSearch;
    protected SearchBuilder<VMInstanceVO> StateChangeSearch;
    protected SearchBuilder<VMInstanceVO> TransitionSearch;
    protected SearchBuilder<VMInstanceVO> TypesSearch;
    protected SearchBuilder<VMInstanceVO> IdTypesSearch;
    protected SearchBuilder<VMInstanceVO> HostIdTypesSearch;
    protected SearchBuilder<VMInstanceVO> HostIdStatesSearch;
    protected SearchBuilder<VMInstanceVO> HostIdUpTypesSearch;
    protected SearchBuilder<VMInstanceVO> HostUpSearch;
    protected SearchBuilder<VMInstanceVO> InstanceNameSearch;
    protected SearchBuilder<VMInstanceVO> HostNameSearch;
    protected SearchBuilder<VMInstanceVO> HostNameAndZoneSearch;
    protected GenericSearchBuilder<VMInstanceVO, Long> FindIdsOfVirtualRoutersByAccount;
    protected GenericSearchBuilder<VMInstanceVO, Long> CountActiveByHost;
    protected GenericSearchBuilder<VMInstanceVO, Long> CountRunningAndStartingByAccount;
    protected GenericSearchBuilder<VMInstanceVO, Long> CountByZoneAndState;
    protected SearchBuilder<VMInstanceVO> NetworkTypeSearch;
    protected GenericSearchBuilder<VMInstanceVO, String> DistinctHostNameSearch;
    protected SearchBuilder<VMInstanceVO> HostAndStateSearch;
    protected SearchBuilder<VMInstanceVO> StartingWithNoHostSearch;
    protected SearchBuilder<VMInstanceVO> NotMigratingSearch;
    protected SearchBuilder<VMInstanceVO> BackupSearch;
    protected SearchBuilder<VMInstanceVO> LastHostAndStatesSearch;
    protected SearchBuilder<VMInstanceVO> VmsNotInClusterUsingPool;
    protected SearchBuilder<VMInstanceVO> IdsPowerStateSelectSearch;

    @Inject
    ResourceTagDao tagsDao;
    @Inject
    NicDao nicDao;
    @Inject
    VolumeDao volumeDao;
    @Inject
    protected HostDao hostDao;

    protected Attribute _updateTimeAttr;

    private static final String ORDER_CLUSTERS_NUMBER_OF_VMS_FOR_ACCOUNT_PART1 = "SELECT host.cluster_id, SUM(IF(vm.state='Running' AND vm.account_id = ?, 1, 0)) " +
        "FROM `cloud`.`host` host LEFT JOIN `cloud`.`vm_instance` vm ON host.id = vm.host_id WHERE ";
    private static final String ORDER_CLUSTERS_NUMBER_OF_VMS_FOR_ACCOUNT_PART2 = " AND host.type = 'Routing' AND host.removed is null GROUP BY host.cluster_id " +
        "ORDER BY 2 ASC ";

    private static final String ORDER_PODS_NUMBER_OF_VMS_FOR_ACCOUNT = "SELECT pod.id, SUM(IF(vm.state='Running' AND vm.account_id = ?, 1, 0)) FROM `cloud`.`" +
        "host_pod_ref` pod LEFT JOIN `cloud`.`vm_instance` vm ON pod.id = vm.pod_id WHERE pod.data_center_id = ? AND pod.removed is null "
        + " GROUP BY pod.id ORDER BY 2 ASC ";

    private static final String ORDER_HOSTS_NUMBER_OF_VMS_FOR_ACCOUNT =
        "SELECT host.id, SUM(IF(vm.state='Running' AND vm.account_id = ?, 1, 0)) FROM `cloud`.`host` host LEFT JOIN `cloud`.`vm_instance` vm ON host.id = vm.host_id " +
            "WHERE host.data_center_id = ? AND host.type = 'Routing' AND host.removed is null ";

    private static final String ORDER_HOSTS_NUMBER_OF_VMS_FOR_ACCOUNT_PART2 = " GROUP BY host.id ORDER BY 2 ASC ";

    private static final String COUNT_VMS_BASED_ON_VGPU_TYPES1_LEGACY =
            "SELECT pci, type, SUM(vmcount) FROM (SELECT MAX(IF(offering.name = 'pciDevice',value,'')) AS pci, MAX(IF(offering.name = 'vgpuType', value,'')) " +
            "AS type, COUNT(DISTINCT vm.id) AS vmcount FROM service_offering_details offering INNER JOIN vm_instance vm ON offering.service_offering_id = vm.service_offering_id " +
            "INNER JOIN `cloud`.`host` ON vm.host_id = host.id WHERE vm.state = 'Running' AND host.data_center_id = ? ";
    private static final String COUNT_VMS_BASED_ON_VGPU_TYPES2_LEGACY =
            "GROUP BY vm.service_offering_id) results GROUP BY pci, type";

    private static final String COUNT_VMS_BASED_ON_VGPU_TYPES1 =
            "SELECT CONCAT(gpu_card.vendor_name,  ' ',  gpu_card.device_name), vgpu_profile.name, COUNT(gpu_device.vm_id) "
            + "FROM `cloud`.`gpu_device` "
            + "INNER JOIN `cloud`.`host` ON gpu_device.host_id = host.id "
            + "INNER JOIN `cloud`.`gpu_card` ON gpu_device.card_id = gpu_card.id "
            + "INNER JOIN `cloud`.`vgpu_profile` ON vgpu_profile.id = gpu_device.vgpu_profile_id "
            + "WHERE vm_id IS NOT NULL AND host.data_center_id = ? ";
    private static final String COUNT_VMS_BASED_ON_VGPU_TYPES2 =
            "GROUP BY gpu_card.name, vgpu_profile.name";

    private static final String UPDATE_SYSTEM_VM_TEMPLATE_ID_FOR_HYPERVISOR = "UPDATE `cloud`.`vm_instance` SET vm_template_id = ? WHERE type <> 'User' AND hypervisor_type = ? AND removed is NULL";

    private static final String COUNT_VMS_BY_ZONE_AND_STATE_AND_HOST_TAG = "SELECT COUNT(1) FROM vm_instance vi JOIN service_offering so ON vi.service_offering_id=so.id " +
            "JOIN vm_template vt ON vi.vm_template_id = vt.id WHERE vi.data_center_id = ? AND vi.state = ? AND vi.removed IS NULL AND (so.host_tag = ? OR vt.template_tag = ?)";


    public VMInstanceDaoImpl() {
    }

    @PostConstruct
    protected void init() {

        IdStatesSearch = createSearchBuilder();
        IdStatesSearch.and("id", IdStatesSearch.entity().getId(), Op.EQ);
        IdStatesSearch.and("states", IdStatesSearch.entity().getState(), Op.IN);
        IdStatesSearch.done();

        VMClusterSearch = createSearchBuilder();
        SearchBuilder<HostVO> hostSearch = hostDao.createSearchBuilder();
        VMClusterSearch.join("hostSearch", hostSearch, hostSearch.entity().getId(), VMClusterSearch.entity().getHostId(), JoinType.INNER);
        hostSearch.and("clusterId", hostSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        VMClusterSearch.done();

        LHVMClusterSearch = createSearchBuilder();
        SearchBuilder<HostVO> hostSearch1 = hostDao.createSearchBuilder();
        LHVMClusterSearch.join("hostSearch1", hostSearch1, hostSearch1.entity().getId(), LHVMClusterSearch.entity().getLastHostId(), JoinType.INNER);
        LHVMClusterSearch.and("hostid", LHVMClusterSearch.entity().getHostId(), Op.NULL);
        hostSearch1.and("clusterId", hostSearch1.entity().getClusterId(), SearchCriteria.Op.EQ);
        LHVMClusterSearch.done();

        AllFieldsSearch = createSearchBuilder();
        AllFieldsSearch.and("host", AllFieldsSearch.entity().getHostId(), Op.EQ);
        AllFieldsSearch.and("lastHost", AllFieldsSearch.entity().getLastHostId(), Op.EQ);
        AllFieldsSearch.and("state", AllFieldsSearch.entity().getState(), Op.EQ);
        AllFieldsSearch.and("zone", AllFieldsSearch.entity().getDataCenterId(), Op.EQ);
        AllFieldsSearch.and("pod", AllFieldsSearch.entity().getPodIdToDeployIn(), Op.EQ);
        AllFieldsSearch.and("type", AllFieldsSearch.entity().getType(), Op.EQ);
        AllFieldsSearch.and("account", AllFieldsSearch.entity().getAccountId(), Op.EQ);
        AllFieldsSearch.done();

        IdServiceOfferingIdSelectSearch = createSearchBuilder();
        IdServiceOfferingIdSelectSearch.and("host", IdServiceOfferingIdSelectSearch.entity().getHostId(), Op.EQ);
        IdServiceOfferingIdSelectSearch.and("lastHost", IdServiceOfferingIdSelectSearch.entity().getLastHostId(), Op.EQ);
        IdServiceOfferingIdSelectSearch.and("state", IdServiceOfferingIdSelectSearch.entity().getState(), Op.EQ);
        IdServiceOfferingIdSelectSearch.and("states", IdServiceOfferingIdSelectSearch.entity().getState(), Op.IN);
        IdServiceOfferingIdSelectSearch.selectFields(IdServiceOfferingIdSelectSearch.entity().getId(), IdServiceOfferingIdSelectSearch.entity().getServiceOfferingId());
        IdServiceOfferingIdSelectSearch.done();

        ZoneTemplateNonExpungedSearch = createSearchBuilder();
        ZoneTemplateNonExpungedSearch.and("zone", ZoneTemplateNonExpungedSearch.entity().getDataCenterId(), Op.EQ);
        ZoneTemplateNonExpungedSearch.and("template", ZoneTemplateNonExpungedSearch.entity().getTemplateId(), Op.EQ);
        ZoneTemplateNonExpungedSearch.and("state", ZoneTemplateNonExpungedSearch.entity().getState(), Op.NEQ);
        ZoneTemplateNonExpungedSearch.done();


        TemplateNonExpungedSearch = createSearchBuilder();
        TemplateNonExpungedSearch.and("template", TemplateNonExpungedSearch.entity().getTemplateId(), Op.EQ);
        TemplateNonExpungedSearch.and("state", TemplateNonExpungedSearch.entity().getState(), Op.NEQ);
        TemplateNonExpungedSearch.done();

        NameLikeSearch = createSearchBuilder();
        NameLikeSearch.and("name", NameLikeSearch.entity().getHostName(), Op.LIKE);
        NameLikeSearch.done();

        StateChangeSearch = createSearchBuilder();
        StateChangeSearch.and("id", StateChangeSearch.entity().getId(), Op.EQ);
        StateChangeSearch.and("states", StateChangeSearch.entity().getState(), Op.EQ);
        StateChangeSearch.and("host", StateChangeSearch.entity().getHostId(), Op.EQ);
        StateChangeSearch.and("update", StateChangeSearch.entity().getUpdated(), Op.EQ);
        StateChangeSearch.done();

        TransitionSearch = createSearchBuilder();
        TransitionSearch.and("updateTime", TransitionSearch.entity().getUpdateTime(), Op.LT);
        TransitionSearch.and("states", TransitionSearch.entity().getState(), Op.IN);
        TransitionSearch.done();

        TypesSearch = createSearchBuilder();
        TypesSearch.and("types", TypesSearch.entity().getType(), Op.IN);
        TypesSearch.done();

        IdTypesSearch = createSearchBuilder();
        IdTypesSearch.and("id", IdTypesSearch.entity().getId(), Op.EQ);
        IdTypesSearch.and("types", IdTypesSearch.entity().getType(), Op.IN);
        IdTypesSearch.done();

        HostIdTypesSearch = createSearchBuilder();
        HostIdTypesSearch.and("hostid", HostIdTypesSearch.entity().getHostId(), Op.EQ);
        HostIdTypesSearch.and("types", HostIdTypesSearch.entity().getType(), Op.IN);
        HostIdTypesSearch.done();

        HostIdStatesSearch = createSearchBuilder();
        HostIdStatesSearch.and("hostId", HostIdStatesSearch.entity().getHostId(), Op.EQ);
        HostIdStatesSearch.and("states", HostIdStatesSearch.entity().getState(), Op.IN);
        HostIdStatesSearch.done();

        HostIdUpTypesSearch = createSearchBuilder();
        HostIdUpTypesSearch.and("hostid", HostIdUpTypesSearch.entity().getHostId(), Op.EQ);
        HostIdUpTypesSearch.and("types", HostIdUpTypesSearch.entity().getType(), Op.IN);
        HostIdUpTypesSearch.and("states", HostIdUpTypesSearch.entity().getState(), Op.NIN);
        HostIdUpTypesSearch.done();

        HostUpSearch = createSearchBuilder();
        HostUpSearch.and("host", HostUpSearch.entity().getHostId(), Op.EQ);
        HostUpSearch.and("states", HostUpSearch.entity().getState(), Op.IN);
        HostUpSearch.done();

        InstanceNameSearch = createSearchBuilder();
        InstanceNameSearch.and("instanceName", InstanceNameSearch.entity().getInstanceName(), Op.EQ);
        InstanceNameSearch.done();

        HostNameSearch = createSearchBuilder();
        HostNameSearch.and("hostName", HostNameSearch.entity().getHostName(), Op.EQ);
        HostNameSearch.done();

        HostNameAndZoneSearch = createSearchBuilder();
        HostNameAndZoneSearch.and("hostName", HostNameAndZoneSearch.entity().getHostName(), Op.EQ);
        HostNameAndZoneSearch.and("zone", HostNameAndZoneSearch.entity().getDataCenterId(), Op.EQ);
        HostNameAndZoneSearch.done();

        FindIdsOfVirtualRoutersByAccount = createSearchBuilder(Long.class);
        FindIdsOfVirtualRoutersByAccount.selectFields(FindIdsOfVirtualRoutersByAccount.entity().getId());
        FindIdsOfVirtualRoutersByAccount.and("account", FindIdsOfVirtualRoutersByAccount.entity().getAccountId(), SearchCriteria.Op.EQ);
        FindIdsOfVirtualRoutersByAccount.and("type", FindIdsOfVirtualRoutersByAccount.entity().getType(), SearchCriteria.Op.EQ);
        FindIdsOfVirtualRoutersByAccount.and("state", FindIdsOfVirtualRoutersByAccount.entity().getState(), SearchCriteria.Op.NIN);
        FindIdsOfVirtualRoutersByAccount.done();

        CountActiveByHost = createSearchBuilder(Long.class);
        CountActiveByHost.select(null, Func.COUNT, null);
        CountActiveByHost.and("host", CountActiveByHost.entity().getHostId(), SearchCriteria.Op.EQ);
        CountActiveByHost.and("state", CountActiveByHost.entity().getState(), SearchCriteria.Op.IN);
        CountActiveByHost.done();

        CountRunningAndStartingByAccount = createSearchBuilder(Long.class);
        CountRunningAndStartingByAccount.select(null, Func.COUNT, null);
        CountRunningAndStartingByAccount.and("account", CountRunningAndStartingByAccount.entity().getAccountId(), SearchCriteria.Op.EQ);
        CountRunningAndStartingByAccount.and("states", CountRunningAndStartingByAccount.entity().getState(), SearchCriteria.Op.IN);
        CountRunningAndStartingByAccount.done();

        CountByZoneAndState = createSearchBuilder(Long.class);
        CountByZoneAndState.select(null, Func.COUNT, null);
        CountByZoneAndState.and("zone", CountByZoneAndState.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        CountByZoneAndState.and("state", CountByZoneAndState.entity().getState(), SearchCriteria.Op.EQ);
        CountByZoneAndState.done();

        HostAndStateSearch = createSearchBuilder();
        HostAndStateSearch.and("host", HostAndStateSearch.entity().getHostId(), Op.EQ);
        HostAndStateSearch.and("states", HostAndStateSearch.entity().getState(), Op.IN);
        HostAndStateSearch.and("idsNotIn", HostAndStateSearch.entity().getId(), Op.NIN);
        HostAndStateSearch.done();

        StartingWithNoHostSearch = createSearchBuilder();
        StartingWithNoHostSearch.and("state", StartingWithNoHostSearch.entity().getState(), Op.EQ);
        StartingWithNoHostSearch.and("host", StartingWithNoHostSearch.entity().getHostId(), Op.NULL);
        StartingWithNoHostSearch.done();

        _updateTimeAttr = _allAttributes.get("updateTime");
        assert _updateTimeAttr != null : "Couldn't get this updateTime attribute";

        SearchBuilder<NicVO> nicSearch = nicDao.createSearchBuilder();
        nicSearch.and("networkId", nicSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        nicSearch.and("removedNic", nicSearch.entity().getRemoved(), SearchCriteria.Op.NULL);

        DistinctHostNameSearch = createSearchBuilder(String.class);
        DistinctHostNameSearch.selectFields(DistinctHostNameSearch.entity().getHostName());

        DistinctHostNameSearch.and("types", DistinctHostNameSearch.entity().getType(), SearchCriteria.Op.IN);
        DistinctHostNameSearch.and("removed", DistinctHostNameSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        DistinctHostNameSearch.join("nicSearch", nicSearch, DistinctHostNameSearch.entity().getId(), nicSearch.entity().getInstanceId(), JoinBuilder.JoinType.INNER);
        DistinctHostNameSearch.done();

        NotMigratingSearch = createSearchBuilder();
        NotMigratingSearch.and("host", NotMigratingSearch.entity().getHostId(), Op.EQ);
        NotMigratingSearch.and("lastHost", NotMigratingSearch.entity().getLastHostId(), Op.EQ);
        NotMigratingSearch.and("state", NotMigratingSearch.entity().getState(), Op.NEQ);
        NotMigratingSearch.done();

        BackupSearch = createSearchBuilder();
        BackupSearch.and("zone_id", BackupSearch.entity().getDataCenterId(), Op.EQ);
        BackupSearch.and("backup_offering_not_null", BackupSearch.entity().getBackupOfferingId(), Op.NNULL);
        BackupSearch.and("backup_offering_id", BackupSearch.entity().getBackupOfferingId(), Op.EQ);
        BackupSearch.done();

        LastHostAndStatesSearch = createSearchBuilder();
        LastHostAndStatesSearch.and("lastHost", LastHostAndStatesSearch.entity().getLastHostId(), Op.EQ);
        LastHostAndStatesSearch.and("states", LastHostAndStatesSearch.entity().getState(), Op.IN);
        LastHostAndStatesSearch.done();

        VmsNotInClusterUsingPool = createSearchBuilder();
        SearchBuilder<VolumeVO> volumeSearch = volumeDao.createSearchBuilder();
        volumeSearch.and("poolId", volumeSearch.entity().getPoolId(), Op.EQ);
        volumeSearch.and("removed", volumeSearch.entity().getRemoved(), Op.NULL);
        VmsNotInClusterUsingPool.join("volumeSearch", volumeSearch, volumeSearch.entity().getInstanceId(), VmsNotInClusterUsingPool.entity().getId(), JoinType.INNER);
        SearchBuilder<HostVO> hostSearch2 = hostDao.createSearchBuilder();
        hostSearch2.and("clusterId", hostSearch2.entity().getClusterId(), SearchCriteria.Op.NEQ);
        VmsNotInClusterUsingPool.join("hostSearch2", hostSearch2, hostSearch2.entity().getId(), VmsNotInClusterUsingPool.entity().getHostId(), JoinType.INNER);
        VmsNotInClusterUsingPool.and("vmStates", VmsNotInClusterUsingPool.entity().getState(), Op.IN);
        VmsNotInClusterUsingPool.done();

        IdsPowerStateSelectSearch = createSearchBuilder();
        IdsPowerStateSelectSearch.and("id", IdsPowerStateSelectSearch.entity().getId(), Op.IN);
        IdsPowerStateSelectSearch.selectFields(IdsPowerStateSelectSearch.entity().getId(),
                IdsPowerStateSelectSearch.entity().getPowerHostId(),
                IdsPowerStateSelectSearch.entity().getPowerState(),
                IdsPowerStateSelectSearch.entity().getPowerStateUpdateCount(),
                IdsPowerStateSelectSearch.entity().getPowerStateUpdateTime());
        IdsPowerStateSelectSearch.done();
    }

    @Override
    public List<VMInstanceVO> listByAccountId(long accountId) {
        SearchCriteria<VMInstanceVO> sc = AllFieldsSearch.create();
        sc.setParameters("account", accountId);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> findVMInstancesLike(String name) {
        SearchCriteria<VMInstanceVO> sc = NameLikeSearch.create();
        sc.setParameters("name", "%" + name + "%");
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listByHostId(long hostid) {
        SearchCriteria<VMInstanceVO> sc = AllFieldsSearch.create();
        sc.setParameters("host", hostid);

        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listNonMigratingVmsByHostEqualsLastHost(long hostId) {
        SearchCriteria<VMInstanceVO> sc = NotMigratingSearch.create();
        sc.setParameters("host", hostId);
        sc.setParameters("lastHost", hostId);
        sc.setParameters("state", State.Migrating);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listByZoneId(long zoneId) {
        SearchCriteria<VMInstanceVO> sc = AllFieldsSearch.create();
        sc.setParameters("zone", zoneId);

        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listByPodId(long podId) {
        SearchCriteria<VMInstanceVO> sc = AllFieldsSearch.create();
        sc.setParameters("pod", podId);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listByClusterId(long clusterId) {
        SearchCriteria<VMInstanceVO> sc = VMClusterSearch.create();
        sc.setJoinParameters("hostSearch", "clusterId", clusterId);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listLHByClusterId(long clusterId) {
        SearchCriteria<VMInstanceVO> sc = LHVMClusterSearch.create();
        sc.setJoinParameters("hostSearch1", "clusterId", clusterId);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listByZoneIdAndType(long zoneId, VirtualMachine.Type type) {
        SearchCriteria<VMInstanceVO> sc = AllFieldsSearch.create();
        sc.setParameters("zone", zoneId);
        sc.setParameters("type", type.toString());
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listNonExpungedByTemplate(long templateId) {
        SearchCriteria<VMInstanceVO> sc = TemplateNonExpungedSearch.create();

        sc.setParameters("template", templateId);
        sc.setParameters("state", State.Expunging);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listNonExpungedByZoneAndTemplate(long zoneId, long templateId) {
        SearchCriteria<VMInstanceVO> sc = ZoneTemplateNonExpungedSearch.create();

        sc.setParameters("zone", zoneId);
        sc.setParameters("template", templateId);
        sc.setParameters("state", State.Expunging);

        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> findVMInTransition(Date time, State... states) {
        SearchCriteria<VMInstanceVO> sc = TransitionSearch.create();

        sc.setParameters("states", (Object[])states);
        sc.setParameters("updateTime", time);

        return search(sc, null);
    }

    @Override
    public List<VMInstanceVO> listByHostIdTypes(long hostid, Type... types) {
        SearchCriteria<VMInstanceVO> sc = HostIdTypesSearch.create();
        sc.setParameters("hostid", hostid);
        sc.setParameters("types", (Object[])types);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listByHostAndState(long hostId, State... states) {
        SearchCriteria<VMInstanceVO> sc = HostIdStatesSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("states", (Object[])states);

        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listUpByHostIdTypes(long hostid, Type... types) {
        SearchCriteria<VMInstanceVO> sc = HostIdUpTypesSearch.create();
        sc.setParameters("hostid", hostid);
        sc.setParameters("types", (Object[])types);
        sc.setParameters("states", new Object[] {State.Destroyed, State.Stopped, State.Expunging});
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listUpByHostId(Long hostId) {
        SearchCriteria<VMInstanceVO> sc = HostUpSearch.create();
        sc.setParameters("host", hostId);
        sc.setParameters("states", new Object[] {State.Starting, State.Running, State.Stopping, State.Migrating});
        return listBy(sc);
    }

    @Override
    public int countByTypes(Type... types) {
        SearchCriteria<VMInstanceVO> sc = TypesSearch.create();
        sc.setParameters("types", (Object[])types);
        return getCount(sc);
    }

    @Override
    public List<VMInstanceVO> listByTypeAndState(VirtualMachine.Type type, State state) {
        SearchCriteria<VMInstanceVO> sc = AllFieldsSearch.create();
        sc.setParameters("type", type);
        sc.setParameters("state", state);
        return listBy(sc);
    }

    @Override
    public VMInstanceVO findByIdTypes(long id, Type... types) {
        SearchCriteria<VMInstanceVO> sc = IdTypesSearch.create();
        sc.setParameters("id", id);
        sc.setParameters("types", (Object[])types);
        return findOneIncludingRemovedBy(sc);
    }

    @Override
    public VMInstanceVO findVMByInstanceName(String name) {
        SearchCriteria<VMInstanceVO> sc = InstanceNameSearch.create();
        sc.setParameters("instanceName", name);
        return findOneBy(sc);
    }

    @Override
    public VMInstanceVO findVMByInstanceNameIncludingRemoved(String name) {
        SearchCriteria<VMInstanceVO> sc = InstanceNameSearch.create();
        sc.setParameters("instanceName", name);
        return findOneIncludingRemovedBy(sc);
    }

    @Override
    public VMInstanceVO findVMByHostName(String hostName) {
        SearchCriteria<VMInstanceVO> sc = HostNameSearch.create();
        sc.setParameters("hostName", hostName);
        return findOneBy(sc);
    }

    @Override
    public VMInstanceVO findVMByHostNameInZone(String hostName, long zoneId) {
        SearchCriteria<VMInstanceVO> sc = HostNameAndZoneSearch.create();
        sc.setParameters("hostName", hostName);
        sc.setParameters("zone", zoneId);
        return findOneBy(sc);
    }

    @Override
    public void updateProxyId(long id, Long proxyId, Date time) {
        VMInstanceVO vo = createForUpdate();
        vo.setProxyId(proxyId);
        vo.setProxyAssignTime(time);
        update(id, vo);
    }

    @Override
    public boolean updateState(State oldState, Event event, State newState, VirtualMachine vm, Object opaque) {
        if (newState == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("There's no way to transition from old state: " + oldState.toString() + " event: " + event.toString());
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        Pair<Long, Long> hosts = (Pair<Long, Long>)opaque;
        Long newHostId = hosts.second();

        VMInstanceVO vmi = (VMInstanceVO)vm;
        Long oldHostId = vmi.getHostId();
        Long oldUpdated = vmi.getUpdated();
        Date oldUpdateDate = vmi.getUpdateTime();
        if (newState.equals(oldState) && newHostId != null && newHostId.equals(oldHostId)) {
            // state is same, don't need to update
            return true;
        }
        if(ifStateUnchanged(oldState,newState, oldHostId, newHostId)) {
            return true;
        }

        // lock the target row at beginning to avoid lock-promotion caused deadlock
        lockRow(vm.getId(), true);

        SearchCriteria<VMInstanceVO> sc = StateChangeSearch.create();
        sc.setParameters("id", vmi.getId());
        sc.setParameters("states", oldState);
        sc.setParameters("host", vmi.getHostId());
        sc.setParameters("update", vmi.getUpdated());

        vmi.incrUpdated();
        UpdateBuilder ub = getUpdateBuilder(vmi);

        ub.set(vmi, "state", newState);
        ub.set(vmi, "hostId", newHostId);
        ub.set(vmi, "podIdToDeployIn", vmi.getPodIdToDeployIn());
        ub.set(vmi, _updateTimeAttr, new Date());

        int result = update(vmi, sc);
        if (result == 0) {
            VMInstanceVO vo = findByIdIncludingRemoved(vm.getId());

            if (logger.isDebugEnabled()) {
                if (vo != null) {
                    StringBuilder str = new StringBuilder("Unable to update ").append(vo.toString());
                    str.append(": DB Data={Host=").append(vo.getHostId()).append("; State=").append(vo.getState().toString()).append("; updated=").append(vo.getUpdated())
                            .append("; time=").append(vo.getUpdateTime());
                    str.append("} New Data: {Host=").append(vm.getHostId()).append("; State=").append(vm.getState().toString()).append("; updated=").append(vmi.getUpdated())
                            .append("; time=").append(vo.getUpdateTime());
                    str.append("} Stale Data: {Host=").append(oldHostId).append("; State=").append(oldState).append("; updated=").append(oldUpdated).append("; time=")
                            .append(oldUpdateDate).append("}");
                    logger.debug(str.toString());

                } else {
                    logger.debug("Unable to update the vm {}; the vm either doesn't exist or already removed", vm);
                }
            }

            if (vo != null && vo.getState() == newState) {
                // allow for concurrent update if target state has already been matched
                logger.debug("VM {} state has been already been updated to {}", vo, newState);
                return true;
            }
        }
        return result > 0;
    }

    boolean ifStateUnchanged(State oldState, State newState, Long oldHostId, Long newHostId ) {
        if (oldState == State.Stopped && newState == State.Stopped && newHostId == null && oldHostId == null) {
            // No change , no need to update
            return true;
        }
        return false;
    }

    @Override
    public List<VMInstanceVO> listByLastHostId(Long hostId) {
        SearchCriteria<VMInstanceVO> sc = AllFieldsSearch.create();
        sc.setParameters("lastHost", hostId);
        sc.setParameters("state", State.Stopped);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listByLastHostIdAndStates(Long hostId, State... states) {
        SearchCriteria<VMInstanceVO> sc = LastHostAndStatesSearch.create();
        sc.setParameters("lastHost", hostId);
        sc.setParameters("states", (Object[])states);
        return listBy(sc);
    }

    @Override
    public List<Long> findIdsOfAllocatedVirtualRoutersForAccount(long accountId) {
        SearchCriteria<Long> sc = FindIdsOfVirtualRoutersByAccount.create();
        sc.setParameters("account", accountId);
        sc.setParameters("type", VirtualMachine.Type.DomainRouter);
        sc.setParameters("state", new Object[] {State.Destroyed, State.Error, State.Expunging});
        return customSearch(sc, null);
    }

    @Override
    public List<VMInstanceVO> listVmsMigratingFromHost(Long hostId) {
        SearchCriteria<VMInstanceVO> sc = AllFieldsSearch.create();
        sc.setParameters("lastHost", hostId);
        sc.setParameters("state", State.Migrating);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listByZoneWithBackups(Long zoneId, Long backupOfferingId) {
        SearchCriteria<VMInstanceVO> sc = BackupSearch.create();
        sc.setParameters("zone_id", zoneId);
        if (backupOfferingId != null) {
            sc.setParameters("backup_offering_id", backupOfferingId);
        }
        return listBy(sc);
    }

    @Override
    public Long countActiveByHostId(long hostId) {
        SearchCriteria<Long> sc = CountActiveByHost.create();
        sc.setParameters("host", hostId);
        sc.setParameters("state", State.Running, State.Starting, State.Stopping, State.Migrating);
        return customSearch(sc, null).get(0);
    }

    @Override
    public Pair<List<Long>, Map<Long, Double>> listClusterIdsInZoneByVmCount(long zoneId, long accountId) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt = null;
        List<Long> result = new ArrayList<Long>();
        Map<Long, Double> clusterVmCountMap = new HashMap<Long, Double>();

        StringBuilder sql = new StringBuilder(ORDER_CLUSTERS_NUMBER_OF_VMS_FOR_ACCOUNT_PART1);
        sql.append("host.data_center_id = ?");
        sql.append(ORDER_CLUSTERS_NUMBER_OF_VMS_FOR_ACCOUNT_PART2);
        try {
            pstmt = txn.prepareAutoCloseStatement(sql.toString());
            pstmt.setLong(1, accountId);
            pstmt.setLong(2, zoneId);

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Long clusterId = rs.getLong(1);
                result.add(clusterId);
                clusterVmCountMap.put(clusterId, rs.getDouble(2));
            }
            return new Pair<List<Long>, Map<Long, Double>>(result, clusterVmCountMap);
        } catch (SQLException e) {
            throw new CloudRuntimeException("DB Exception on: " + sql, e);
        } catch (Throwable e) {
            throw new CloudRuntimeException("Caught: " + sql, e);
        }
    }

    @Override
    public Pair<List<Long>, Map<Long, Double>> listClusterIdsInPodByVmCount(long podId, long accountId) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt = null;
        List<Long> result = new ArrayList<Long>();
        Map<Long, Double> clusterVmCountMap = new HashMap<Long, Double>();

        StringBuilder sql = new StringBuilder(ORDER_CLUSTERS_NUMBER_OF_VMS_FOR_ACCOUNT_PART1);
        sql.append("host.pod_id = ?");
        sql.append(ORDER_CLUSTERS_NUMBER_OF_VMS_FOR_ACCOUNT_PART2);
        try {
            pstmt = txn.prepareAutoCloseStatement(sql.toString());
            pstmt.setLong(1, accountId);
            pstmt.setLong(2, podId);

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Long clusterId = rs.getLong(1);
                result.add(clusterId);
                clusterVmCountMap.put(clusterId, rs.getDouble(2));
            }
            return new Pair<List<Long>, Map<Long, Double>>(result, clusterVmCountMap);
        } catch (SQLException e) {
            throw new CloudRuntimeException("DB Exception on: " + sql, e);
        } catch (Throwable e) {
            throw new CloudRuntimeException("Caught: " + sql, e);
        }

    }

    @Override
    public Pair<List<Long>, Map<Long, Double>> listPodIdsInZoneByVmCount(long dataCenterId, long accountId) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt = null;
        List<Long> result = new ArrayList<Long>();
        Map<Long, Double> podVmCountMap = new HashMap<Long, Double>();
        try {
            String sql = ORDER_PODS_NUMBER_OF_VMS_FOR_ACCOUNT;
            pstmt = txn.prepareAutoCloseStatement(sql);
            pstmt.setLong(1, accountId);
            pstmt.setLong(2, dataCenterId);

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Long podId = rs.getLong(1);
                result.add(podId);
                podVmCountMap.put(podId, rs.getDouble(2));
            }
            return new Pair<List<Long>, Map<Long, Double>>(result, podVmCountMap);
        } catch (SQLException e) {
            throw new CloudRuntimeException("DB Exception on: " + ORDER_PODS_NUMBER_OF_VMS_FOR_ACCOUNT, e);
        } catch (Throwable e) {
            throw new CloudRuntimeException("Caught: " + ORDER_PODS_NUMBER_OF_VMS_FOR_ACCOUNT, e);
        }
    }

    @Override
    public List<Long> listHostIdsByVmCount(long dcId, Long podId, Long clusterId, long accountId) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt = null;
        List<Long> result = new ArrayList<Long>();
        try {
            String sql = ORDER_HOSTS_NUMBER_OF_VMS_FOR_ACCOUNT;
            if (podId != null) {
                sql = sql + " AND host.pod_id = ? ";
            }

            if (clusterId != null) {
                sql = sql + " AND host.cluster_id = ? ";
            }

            sql = sql + ORDER_HOSTS_NUMBER_OF_VMS_FOR_ACCOUNT_PART2;

            pstmt = txn.prepareAutoCloseStatement(sql);
            pstmt.setLong(1, accountId);
            pstmt.setLong(2, dcId);
            if (podId != null) {
                pstmt.setLong(3, podId);
            }
            if (clusterId != null) {
                pstmt.setLong(4, clusterId);
            }

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                result.add(rs.getLong(1));
            }
            return result;
        } catch (SQLException e) {
            throw new CloudRuntimeException("DB Exception on: " + ORDER_PODS_NUMBER_OF_VMS_FOR_ACCOUNT, e);
        } catch (Throwable e) {
            throw new CloudRuntimeException("Caught: " + ORDER_PODS_NUMBER_OF_VMS_FOR_ACCOUNT, e);
        }
    }

    @Override
    public HashMap<String, Long> countVgpuVMs(Long dcId, Long podId, Long clusterId) {
        StringBuilder finalQueryLegacy = new StringBuilder();
        StringBuilder finalQuery = new StringBuilder();
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmtLegacy = null;
        PreparedStatement pstmt = null;
        List<Long> resourceIdList = new ArrayList<Long>();
        HashMap<String, Long> result = new HashMap<String, Long>();

        resourceIdList.add(dcId);
        finalQueryLegacy.append(COUNT_VMS_BASED_ON_VGPU_TYPES1_LEGACY);
        finalQuery.append(COUNT_VMS_BASED_ON_VGPU_TYPES1);

        if (podId != null) {
            finalQueryLegacy.append("AND host.pod_id = ? ");
            finalQuery.append("AND host.pod_id = ? ");
            resourceIdList.add(podId);
        }

        if (clusterId != null) {
            finalQueryLegacy.append("AND host.cluster_id = ? ");
            finalQuery.append("AND host.cluster_id = ? ");
            resourceIdList.add(clusterId);
        }
        finalQueryLegacy.append(COUNT_VMS_BASED_ON_VGPU_TYPES2_LEGACY);
        finalQuery.append(COUNT_VMS_BASED_ON_VGPU_TYPES2);

        try {
            pstmtLegacy = txn.prepareAutoCloseStatement(finalQueryLegacy.toString());
            pstmt = txn.prepareAutoCloseStatement(finalQuery.toString());
            for (int i = 0; i < resourceIdList.size(); i++) {
                pstmtLegacy.setLong(1 + i, resourceIdList.get(i));
                pstmt.setLong(1 + i, resourceIdList.get(i));
            }
            ResultSet rs = pstmtLegacy.executeQuery();
            while (rs.next()) {
                result.put(rs.getString(1).concat(rs.getString(2)), rs.getLong(3));
            }
            rs = pstmt.executeQuery();
            while (rs.next()) {
                result.put(rs.getString(1).concat(rs.getString(2)), rs.getLong(3));
            }
            return result;
        } catch (SQLException e) {
            throw new CloudRuntimeException("DB Exception on: " + finalQueryLegacy, e);
        } catch (Throwable e) {
            throw new CloudRuntimeException("Caught: " + finalQueryLegacy, e);
        }
    }

    @Override
    public Long countRunningAndStartingByAccount(long accountId) {
        SearchCriteria<Long> sc = CountRunningAndStartingByAccount.create();
        sc.setParameters("account", accountId);
        sc.setParameters("states", new Object[] {State.Starting, State.Running});
        return customSearch(sc, null).get(0);
    }

    @Override
    public Long countByZoneAndState(long zoneId, State state) {
        SearchCriteria<Long> sc = CountByZoneAndState.create();
        sc.setParameters("zone", zoneId);
        sc.setParameters("state", state);
        return customSearch(sc, null).get(0);
    }

    @Override
    public Long countByZoneAndStateAndHostTag(long dcId, State state, String hostTag) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt = null;
        try {
            pstmt = txn.prepareAutoCloseStatement(COUNT_VMS_BY_ZONE_AND_STATE_AND_HOST_TAG);

            pstmt.setLong(1, dcId);
            pstmt.setString(2, String.valueOf(state));
            pstmt.setString(3, hostTag);
            pstmt.setString(4, hostTag);

            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception e) {
            logger.warn(String.format("Error counting vms by host tag for dcId= %s, hostTag= %s", dcId, hostTag), e);
        }
        return 0L;
    }

    @Override
    public List<VMInstanceVO> listNonRemovedVmsByTypeAndNetwork(long networkId, VirtualMachine.Type... types) {
        if (NetworkTypeSearch == null) {

            SearchBuilder<NicVO> nicSearch = nicDao.createSearchBuilder();
            nicSearch.and("networkId", nicSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
            nicSearch.and("removed", nicSearch.entity().getRemoved(), SearchCriteria.Op.NULL);

            NetworkTypeSearch = createSearchBuilder();
            NetworkTypeSearch.and("types", NetworkTypeSearch.entity().getType(), SearchCriteria.Op.IN);
            NetworkTypeSearch.and("removed", NetworkTypeSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
            NetworkTypeSearch.join("nicSearch", nicSearch, NetworkTypeSearch.entity().getId(), nicSearch.entity().getInstanceId(), JoinBuilder.JoinType.INNER);
            NetworkTypeSearch.done();
        }

        SearchCriteria<VMInstanceVO> sc = NetworkTypeSearch.create();
        if (types != null && types.length != 0) {
            sc.setParameters("types", (Object[])types);
        }
        sc.setJoinParameters("nicSearch", "networkId", networkId);

        return listBy(sc);
    }

    @Override
    public List<String> listDistinctHostNames(long networkId, VirtualMachine.Type... types) {
        SearchCriteria<String> sc = DistinctHostNameSearch.create();
        if (types != null && types.length != 0) {
            sc.setParameters("types", (Object[])types);
        }
        sc.setJoinParameters("nicSearch", "networkId", networkId);

        return customSearch(sc, null);
    }

    @Override
    @DB
    public boolean remove(Long id) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();
        VMInstanceVO vm = findById(id);
        if (vm != null && vm.getType() == Type.User) {
            tagsDao.removeByIdAndType(id, ResourceObjectType.UserVm);
        }
        boolean result = super.remove(id);
        txn.commit();
        return result;
    }

    @Override
    public List<VMInstanceVO> findByHostInStatesExcluding(Long hostId, Collection<Long> excludingIds, State... states) {
        SearchCriteria<VMInstanceVO> sc = HostAndStateSearch.create();
        sc.setParameters("host", hostId);
        if (excludingIds != null && !excludingIds.isEmpty()) {
            sc.setParameters("idsNotIn", excludingIds.toArray());
        }
        sc.setParameters("states", (Object[])states);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> findByHostInStates(Long hostId, State... states) {
        SearchCriteria<VMInstanceVO> sc = HostAndStateSearch.create();
        sc.setParameters("host", hostId);
        sc.setParameters("states", (Object[])states);
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> listStartingWithNoHostId() {
        SearchCriteria<VMInstanceVO> sc = StartingWithNoHostSearch.create();
        sc.setParameters("state", State.Starting);
        return listBy(sc);
    }

    protected List<VMInstanceVO> listSelectPowerStateByIds(final List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        }
        SearchCriteria<VMInstanceVO> sc = IdsPowerStateSelectSearch.create();
        sc.setParameters("id", ids.toArray());
        return customSearch(sc, null);
    }

    protected Integer getPowerUpdateCount(final VMInstanceVO instance, final long powerHostId,
              final VirtualMachine.PowerState powerState, Date wisdomEra) {
        if (instance.getPowerStateUpdateTime() == null || instance.getPowerStateUpdateTime().before(wisdomEra)) {
            Long savedPowerHostId = instance.getPowerHostId();
            boolean isStateMismatch = instance.getPowerState() != powerState
                    || savedPowerHostId == null
                    || !savedPowerHostId.equals(powerHostId)
                    || !isPowerStateInSyncWithInstanceState(powerState, powerHostId, instance);
            if (isStateMismatch) {
                return 1;
            } else if (instance.getPowerStateUpdateCount() < MAX_CONSECUTIVE_SAME_STATE_UPDATE_COUNT) {
                return instance.getPowerStateUpdateCount() + 1;
            }
        }
        return null;
    }

    @Override
    public boolean updatePowerState(final long instanceId, final long powerHostId,
            final VirtualMachine.PowerState powerState, Date wisdomEra) {
        return Transaction.execute((TransactionCallback<Boolean>) status -> {
            VMInstanceVO instance = findById(instanceId);
            if (instance == null) {
                return false;
            }
            // Check if we need to update based on powerStateUpdateTime
            if (instance.getPowerStateUpdateTime() == null || instance.getPowerStateUpdateTime().before(wisdomEra)) {
                Long savedPowerHostId = instance.getPowerHostId();
                boolean isStateMismatch = instance.getPowerState() != powerState
                        || savedPowerHostId == null
                        || !savedPowerHostId.equals(powerHostId)
                        || !isPowerStateInSyncWithInstanceState(powerState, powerHostId, instance);

                if (isStateMismatch) {
                    instance.setPowerState(powerState);
                    instance.setPowerHostId(powerHostId);
                    instance.setPowerStateUpdateCount(1);
                } else if (instance.getPowerStateUpdateCount() < MAX_CONSECUTIVE_SAME_STATE_UPDATE_COUNT) {
                    instance.setPowerStateUpdateCount(instance.getPowerStateUpdateCount() + 1);
                } else {
                    // No need to update if power state is already in sync and count exceeded
                    return false;
                }
                instance.setPowerStateUpdateTime(DateUtil.currentGMTTime());
                update(instanceId, instance);
                return true; // Return true since an update occurred
            }
            return false;
        });
    }

    @Override
    public Map<Long, VirtualMachine.PowerState> updatePowerState(
            final Map<Long, VirtualMachine.PowerState> instancePowerStates, long powerHostId, Date wisdomEra) {
        Map<Long, VirtualMachine.PowerState> notUpdated = new HashMap<>();
        List<VMInstanceVO> instances = listSelectPowerStateByIds(new ArrayList<>(instancePowerStates.keySet()));
        Map<Long, Integer> updateCounts = new HashMap<>();
        for (VMInstanceVO instance : instances) {
            VirtualMachine.PowerState powerState = instancePowerStates.get(instance.getId());
            Integer count = getPowerUpdateCount(instance, powerHostId, powerState, wisdomEra);
            if (count != null) {
                updateCounts.put(instance.getId(), count);
            } else {
                notUpdated.put(instance.getId(), powerState);
            }
        }
        if (updateCounts.isEmpty()) {
            return notUpdated;
        }
        StringBuilder sql = new StringBuilder("UPDATE `cloud`.`vm_instance` SET " +
                "`power_host` = ?, `power_state_update_time` = now(), `power_state` = CASE ");
        updateCounts.keySet().forEach(key -> {
            sql.append("WHEN id = ").append(key).append(" THEN '").append(instancePowerStates.get(key)).append("' ");
        });
        sql.append("END, `power_state_update_count` = CASE ");
        StringBuilder idList = new StringBuilder();
        updateCounts.forEach((key, value) -> {
            sql.append("WHEN `id` = ").append(key).append(" THEN ").append(value).append(" ");
            idList.append(key).append(",");
        });
        idList.setLength(idList.length() - 1);
        sql.append("END WHERE `id` IN (").append(idList).append(")");
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        try (PreparedStatement pstmt = txn.prepareAutoCloseStatement(sql.toString())) {
            pstmt.setLong(1, powerHostId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Unable to execute update power states SQL from VMs {} due to: {}",
                    idList, e.getMessage(), e);
            return instancePowerStates;
        }
        return notUpdated;
    }

    private boolean isPowerStateInSyncWithInstanceState(final VirtualMachine.PowerState powerState, final long powerHostId, final VMInstanceVO instance) {
        State instanceState = instance.getState();
        if ((powerState == VirtualMachine.PowerState.PowerOff && instanceState == State.Running)
                || (powerState == VirtualMachine.PowerState.PowerOn && instanceState == State.Stopped)) {
            HostVO instanceHost = hostDao.findById(instance.getHostId());
            HostVO powerHost = powerHostId == instance.getHostId() ? instanceHost : hostDao.findById(powerHostId);
            logger.debug("VM: {} on host: {} and power host : {} is in {} state, but power state is {}",
                    instance, instanceHost, powerHost, instanceState, powerState);
            return false;
        }
        return true;
    }

    @Override
    public boolean isPowerStateUpToDate(final VMInstanceVO instance) {
        return instance.getPowerStateUpdateCount() < MAX_CONSECUTIVE_SAME_STATE_UPDATE_COUNT;
    }

    @Override
    public void resetVmPowerStateTracking(final long instanceId) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                VMInstanceVO instance = findById(instanceId);
                if (instance != null) {
                    instance.setPowerStateUpdateCount(0);
                    instance.setPowerStateUpdateTime(DateUtil.currentGMTTime());
                    update(instanceId, instance);
                }
            }
        });
    }

    @Override
    public void resetVmPowerStateTracking(List<Long> instanceIds) {
        if (CollectionUtils.isEmpty(instanceIds)) {
            return;
        }
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                SearchCriteria<VMInstanceVO> sc = IdsPowerStateSelectSearch.create();
                sc.setParameters("id", instanceIds.toArray());
                VMInstanceVO vm = createForUpdate();
                vm.setPowerStateUpdateCount(0);
                vm.setPowerStateUpdateTime(DateUtil.currentGMTTime());
                UpdateBuilder ub = getUpdateBuilder(vm);
                update(ub, sc, null);
            }
        });
    }

    @Override @DB
    public void resetHostPowerStateTracking(final long hostId) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                SearchCriteria<VMInstanceVO> sc = createSearchCriteria();
                sc.addAnd("powerHostId", SearchCriteria.Op.EQ, hostId);

                VMInstanceVO instance = createForUpdate();
                instance.setPowerStateUpdateCount(0);
                instance.setPowerStateUpdateTime(DateUtil.currentGMTTime());

                update(instance, sc);
            }
        });
    }


    @Override
    public void updateSystemVmTemplateId(long templateId, Hypervisor.HypervisorType hypervisorType) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();

        StringBuilder sql = new StringBuilder(UPDATE_SYSTEM_VM_TEMPLATE_ID_FOR_HYPERVISOR);
        try {
            PreparedStatement updateStatement = txn.prepareAutoCloseStatement(sql.toString());
            updateStatement.setLong(1, templateId);
            updateStatement.setString(2, hypervisorType.toString());
            updateStatement.executeUpdate();
        } catch (SQLException e) {
            throw new CloudRuntimeException("DB Exception on: " + sql, e);
        } catch (Throwable e) {
            throw new CloudRuntimeException("Caught: " + sql, e);
        }
    }

    @Override
    public List<VMInstanceVO> listByHostOrLastHostOrHostPod(List<Long> hostIds, long podId) {
        SearchBuilder<VMInstanceVO> sb = createSearchBuilder();
        sb.and().op("hostId", sb.entity().getHostId(), Op.IN);
        sb.or("lastHostId", sb.entity().getLastHostId(), Op.IN);
        sb.or().op("hostIdNull", sb.entity().getHostId(), SearchCriteria.Op.NULL);
        sb.and("lastHostIdNull", sb.entity().getHostId(), SearchCriteria.Op.NULL);
        sb.and("podId", sb.entity().getPodIdToDeployIn(), Op.EQ);
        sb.cp();
        sb.cp();
        sb.done();
        SearchCriteria<VMInstanceVO> sc = sb.create();
        sc.setParameters("hostId", hostIds.toArray());
        sc.setParameters("lastHostId", hostIds.toArray());
        sc.setParameters("podId", String.valueOf(podId));
        return listBy(sc);
    }

    @Override
    public List<VMInstanceVO> searchRemovedByRemoveDate(Date startDate, Date endDate, Long batchSize,
                List<Long> skippedVmIds) {
        SearchBuilder<VMInstanceVO> sb = createSearchBuilder();
        sb.and("removed", sb.entity().getRemoved(), SearchCriteria.Op.NNULL);
        sb.and("startDate", sb.entity().getRemoved(), SearchCriteria.Op.GTEQ);
        sb.and("endDate", sb.entity().getRemoved(), SearchCriteria.Op.LTEQ);
        sb.and("skippedVmIds", sb.entity().getId(), Op.NOTIN);
        SearchCriteria<VMInstanceVO> sc = sb.create();
        if (startDate != null) {
            sc.setParameters("startDate", startDate);
        }
        if (endDate != null) {
            sc.setParameters("endDate", endDate);
        }
        if (CollectionUtils.isNotEmpty(skippedVmIds)) {
            sc.setParameters("skippedVmIds", skippedVmIds.toArray());
        }
        Filter filter = new Filter(VMInstanceVO.class, "id", true, 0L, batchSize);
        return searchIncludingRemoved(sc, filter, null, false);
    }

    @Override
    public Pair<List<VMInstanceVO>, Integer> listByVmsNotInClusterUsingPool(long clusterId, long poolId) {
        SearchCriteria<VMInstanceVO> sc = VmsNotInClusterUsingPool.create();
        sc.setParameters("vmStates", State.Starting, State.Running, State.Stopping, State.Migrating, State.Restoring);
        sc.setJoinParameters("volumeSearch", "poolId", poolId);
        sc.setJoinParameters("hostSearch2", "clusterId", clusterId);
        List<VMInstanceVO> vms = search(sc, null);
        List<VMInstanceVO> uniqueVms = vms.stream().distinct().collect(Collectors.toList());
        return new Pair<>(uniqueVms, uniqueVms.size());
    }

    @Override
    public List<VMInstanceVO> listIdServiceOfferingForUpVmsByHostId(Long hostId) {
        SearchCriteria<VMInstanceVO> sc = IdServiceOfferingIdSelectSearch.create();
        sc.setParameters("host", hostId);
        sc.setParameters("states", new Object[] {State.Starting, State.Running, State.Stopping, State.Migrating});
        return customSearch(sc, null);
    }

    @Override
    public List<VMInstanceVO> listIdServiceOfferingForVmsMigratingFromHost(Long hostId) {
        SearchCriteria<VMInstanceVO> sc = IdServiceOfferingIdSelectSearch.create();
        sc.setParameters("lastHost", hostId);
        sc.setParameters("state", State.Migrating);
        return customSearch(sc, null);
    }

    @Override
    public Map<String, Long> getNameIdMapForVmInstanceNames(Collection<String> names) {
        SearchBuilder<VMInstanceVO> sb = createSearchBuilder();
        sb.and("name", sb.entity().getInstanceName(), Op.IN);
        sb.selectFields(sb.entity().getId(), sb.entity().getInstanceName());
        SearchCriteria<VMInstanceVO> sc = sb.create();
        sc.setParameters("name", names.toArray());
        List<VMInstanceVO> vms = customSearch(sc, null);
        return vms.stream()
                .collect(Collectors.toMap(VMInstanceVO::getInstanceName, VMInstanceVO::getId));
    }

    @Override
    public Map<String, Long> getNameIdMapForVmIds(Collection<Long> ids) {
        SearchBuilder<VMInstanceVO> sb = createSearchBuilder();
        sb.and("id", sb.entity().getId(), Op.IN);
        sb.selectFields(sb.entity().getId(), sb.entity().getInstanceName());
        SearchCriteria<VMInstanceVO> sc = sb.create();
        sc.setParameters("id", ids.toArray());
        List<VMInstanceVO> vms = customSearch(sc, null);
        return vms.stream()
                .collect(Collectors.toMap(VMInstanceVO::getInstanceName, VMInstanceVO::getId));
    }
}
