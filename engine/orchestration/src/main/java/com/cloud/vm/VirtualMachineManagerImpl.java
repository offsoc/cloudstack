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

package com.cloud.vm;

import static com.cloud.configuration.ConfigurationManagerImpl.MIGRATE_VM_ACROSS_CLUSTERS;

import java.lang.reflect.Field;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import javax.persistence.EntityExistsException;


import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.vm.MigrateVMCmd;
import org.apache.cloudstack.api.command.admin.volume.MigrateVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.user.volume.MigrateVolumeCmd;
import org.apache.cloudstack.ca.CAManager;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.framework.ca.Certificate;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.extensions.dao.ExtensionDetailsDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.framework.extensions.vo.ExtensionDetailsVO;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.JobSerializerHelper;
import org.apache.cloudstack.framework.jobs.impl.OutcomeImpl;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.MessageDispatcher;
import org.apache.cloudstack.framework.messagebus.MessageHandler;
import org.apache.cloudstack.gpu.GpuService;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.resource.ResourceCleanupService;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.cache.SingleCache;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.cloudstack.vm.UnmanagedVMsManager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.AttachOrDettachConfigDriveCommand;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.ClusterVMMetaDataSyncAnswer;
import com.cloud.agent.api.ClusterVMMetaDataSyncCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.GetVmDiskStatsAnswer;
import com.cloud.agent.api.GetVmDiskStatsCommand;
import com.cloud.agent.api.GetVmNetworkStatsAnswer;
import com.cloud.agent.api.GetVmNetworkStatsCommand;
import com.cloud.agent.api.GetVmStatsAnswer;
import com.cloud.agent.api.GetVmStatsCommand;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.MigrateVmToPoolAnswer;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.PrepareExternalProvisioningAnswer;
import com.cloud.agent.api.PrepareExternalProvisioningCommand;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.RecreateCheckpointsCommand;
import com.cloud.agent.api.ReplugNicAnswer;
import com.cloud.agent.api.ReplugNicCommand;
import com.cloud.agent.api.RestoreVMSnapshotAnswer;
import com.cloud.agent.api.RestoreVMSnapshotCommand;
import com.cloud.agent.api.ScaleVmCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.UnregisterVMCommand;
import com.cloud.agent.api.VmDiskStatsEntry;
import com.cloud.agent.api.VmNetworkStatsEntry;
import com.cloud.agent.api.VmStatsEntry;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.DpdkTO;
import com.cloud.agent.api.to.GPUDeviceTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.agent.manager.allocator.HostAllocator;
import com.cloud.alert.AlertManager;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.capacity.CapacityManager;
import com.cloud.configuration.Resource;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.deploy.DeploymentPlanningManagerImpl;
import com.cloud.deployasis.dao.UserVmDeployAsIsDetailsDao;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.event.UsageEventVO;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageAccessException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.ha.HighAvailabilityManager.WorkType;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruBase;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.DiskOfferingInfo;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Journal;
import com.cloud.utils.LogUtils;
import com.cloud.utils.Pair;
import com.cloud.utils.Predicate;
import com.cloud.utils.ReflectionUse;
import com.cloud.utils.StringUtils;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExecutionException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.ItWorkVO.Step;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotManager;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.google.gson.Gson;


public class VirtualMachineManagerImpl extends ManagerBase implements VirtualMachineManager, VmWorkJobHandler, Listener, Configurable {

    public static final String VM_WORK_JOB_HANDLER = VirtualMachineManagerImpl.class.getSimpleName();

    private static final String VM_SYNC_ALERT_SUBJECT = "VM state sync alert";

    @Inject
    private UserVmManager _userVmMgr;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private NetworkOrchestrationService _networkMgr;
    @Inject
    private NetworkModel _networkModel;
    @Inject
    private AgentManager _agentMgr;
    @Inject
    private VMInstanceDao _vmDao;
    @Inject
    private ReservationDao _reservationDao;
    @Inject
    private ServiceOfferingDao _offeringDao;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    private VMTemplateDao _templateDao;
    @Inject
    private VMTemplateZoneDao templateZoneDao;
    @Inject
    private ItWorkDao _workDao;
    @Inject
    private UserVmDao _userVmDao;
    @Inject
    private UserVmService _userVmService;
    @Inject
    private CapacityManager _capacityMgr;
    @Inject
    private NicDao _nicsDao;
    @Inject
    private HostDao _hostDao;
    @Inject
    private HostDetailsDao hostDetailsDao;
    @Inject
    private AlertManager _alertMgr;
    @Inject
    private GuestOSCategoryDao _guestOsCategoryDao;
    @Inject
    private GuestOSDao _guestOsDao;
    @Inject
    private VolumeDao _volsDao;
    @Inject
    private HighAvailabilityManager _haMgr;
    @Inject
    private HostPodDao _podDao;
    @Inject
    private DataCenterDao _dcDao;
    @Inject
    private ClusterDao _clusterDao;
    @Inject
    private PrimaryDataStoreDao _storagePoolDao;
    @Inject
    private HypervisorGuruManager _hvGuruMgr;
    @Inject
    private NetworkDao _networkDao;
    @Inject
    private StoragePoolHostDao _poolHostDao;
    @Inject
    private VMSnapshotDao _vmSnapshotDao;
    @Inject
    private AffinityGroupVMMapDao _affinityGroupVMMapDao;
    @Inject
    private EntityManager _entityMgr;
    @Inject
    private GuestOSCategoryDao _guestOSCategoryDao;
    @Inject
    private GuestOSDao _guestOSDao;
    @Inject
    private ServiceOfferingDao _serviceOfferingDao;
    @Inject
    private CAManager caManager;
    @Inject
    private ResourceManager _resourceMgr;
    @Inject
    private ResourceLimitService _resourceLimitMgr;
    @Inject
    private VMSnapshotManager _vmSnapshotMgr;
    @Inject
    private ClusterDetailsDao _clusterDetailsDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private VolumeOrchestrationService volumeMgr;
    @Inject
    private GpuService gpuService;
    @Inject
    private DeploymentPlanningManager _dpMgr;
    @Inject
    private MessageBus _messageBus;
    @Inject
    private VirtualMachinePowerStateSync _syncMgr;
    @Inject
    private VmWorkJobDao _workJobDao;
    @Inject
    private AsyncJobManager _jobMgr;
    @Inject
    private StorageManager storageMgr;
    @Inject
    private NetworkDetailsDao networkDetailsDao;
    @Inject
    private SecurityGroupManager _securityGroupManager;
    @Inject
    private UserVmDeployAsIsDetailsDao userVmDeployAsIsDetailsDao;
    @Inject
    private UserVmJoinDao userVmJoinDao;
    @Inject
    private NetworkOfferingDao networkOfferingDao;
    @Inject
    private DomainRouterJoinDao domainRouterJoinDao;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    private AccountDao accountDao;
    @Inject
    private VpcDao vpcDao;
    @Inject
    private DomainDao domainDao;
    @Inject
    public NetworkService networkService;
    @Inject
    ResourceCleanupService resourceCleanupService;
    @Inject
    VmWorkJobDao vmWorkJobDao;
    @Inject
    DataStoreProviderManager dataStoreProviderManager;

    private SingleCache<List<Long>> vmIdsInProgressCache;

    @Inject
    private SnapshotDataStoreDao snapshotDataStoreDao;

    @Inject
    private SnapshotManager snapshotManager;

    @Inject
    private VolumeDataFactory volumeDataFactory;
    @Inject
    ExtensionsManager extensionsManager;
    @Inject
    ExtensionDetailsDao extensionDetailsDao;


    VmWorkJobHandlerProxy _jobHandlerProxy = new VmWorkJobHandlerProxy(this);

    Map<VirtualMachine.Type, VirtualMachineGuru> _vmGurus = new HashMap<>();
    protected StateMachine2<State, VirtualMachine.Event, VirtualMachine> _stateMachine;

    static final ConfigKey<Integer> StartRetry = new ConfigKey<Integer>("Advanced", Integer.class, "start.retry", "10",
            "Number of times to retry create and start commands", true);
    static final ConfigKey<Integer> VmOpWaitInterval = new ConfigKey<Integer>("Advanced", Integer.class, "vm.op.wait.interval", "120",
            "Time (in seconds) to wait before checking if a previous operation has succeeded", true);

    static final ConfigKey<Integer> VmOpLockStateRetry = new ConfigKey<Integer>("Advanced", Integer.class, "vm.op.lock.state.retry", "5",
            "Times to retry locking the state of a VM for operations, -1 means forever", true);
    static final ConfigKey<Long> VmOpCleanupInterval = new ConfigKey<Long>("Advanced", Long.class, "vm.op.cleanup.interval", "86400",
            "Interval to run the thread that cleans up the vm operations (in seconds)", false);
    static final ConfigKey<Long> VmOpCleanupWait = new ConfigKey<Long>("Advanced", Long.class, "vm.op.cleanup.wait", "3600",
            "Time (in seconds) to wait before cleaning up any vm work items", true);
    static final ConfigKey<Long> VmOpCancelInterval = new ConfigKey<Long>("Advanced", Long.class, "vm.op.cancel.interval", "3600",
            "Time (in seconds) to wait before cancelling a operation", false);
    static final ConfigKey<Boolean> VmDestroyForcestop = new ConfigKey<Boolean>("Advanced", Boolean.class, "vm.destroy.forcestop", "false",
            "On destroy, force-stop takes this value ", true);
    static final ConfigKey<Integer> ClusterDeltaSyncInterval = new ConfigKey<Integer>("Advanced", Integer.class, "sync.interval", "60",
            "Cluster Delta sync interval in seconds",
            false);
    static final ConfigKey<Integer> ClusterVMMetaDataSyncInterval = new ConfigKey<Integer>("Advanced", Integer.class, "vmmetadata.sync.interval", "180", "Cluster VM metadata sync interval in seconds",
            false);

    static final ConfigKey<Long> VmJobCheckInterval = new ConfigKey<Long>("Advanced",
            Long.class, "vm.job.check.interval", "3000",
            "Interval in milliseconds to check if the job is complete", false);
    static final ConfigKey<Long> VmJobTimeout = new ConfigKey<Long>("Advanced",
            Long.class, "vm.job.timeout", "600000",
            "Time in milliseconds to wait before attempting to cancel a job", false);
    static final ConfigKey<Integer> VmJobStateReportInterval = new ConfigKey<Integer>("Advanced",
            Integer.class, "vm.job.report.interval", "60",
            "Interval to send application level pings to make sure the connection is still working", false);

    static final ConfigKey<Boolean> HaVmRestartHostUp = new ConfigKey<Boolean>("Advanced", Boolean.class, "ha.vm.restart.hostup", "true",
            "If an out-of-band stop of a VM is detected and its host is up, then power on the VM", true);

    static final ConfigKey<Long> SystemVmRootDiskSize = new ConfigKey<Long>("Advanced",
            Long.class, "systemvm.root.disk.size", "-1",
            "Size of root volume (in GB) of system VMs and virtual routers", true);

    private boolean syncTransitioningVmPowerState;

    ScheduledExecutorService _executor = null;

    private long _nodeId;

    private List<StoragePoolAllocator> _storagePoolAllocators;

    private List<HostAllocator> hostAllocators;

    public List<HostAllocator> getHostAllocators() {
        return hostAllocators;
    }

    public void setHostAllocators(final List<HostAllocator> hostAllocators) {
        this.hostAllocators = hostAllocators;
    }

    @Override
    public void registerGuru(final VirtualMachine.Type type, final VirtualMachineGuru guru) {
        synchronized (_vmGurus) {
            _vmGurus.put(type, guru);
        }
    }

    @Override
    @DB
    public void allocate(final String vmInstanceName, final VirtualMachineTemplate template, final ServiceOffering serviceOffering,
                         final DiskOfferingInfo rootDiskOfferingInfo, final List<DiskOfferingInfo> dataDiskOfferings,
                         final LinkedHashMap<? extends Network, List<? extends NicProfile>> auxiliaryNetworks, final DeploymentPlan plan, final HypervisorType hyperType, final Map<String, Map<Integer, String>> extraDhcpOptions, final Map<Long, DiskOffering> datadiskTemplateToDiskOfferingMap, Volume volume, Snapshot snapshot)
                    throws InsufficientCapacityException {

        logger.info("allocating virtual machine from template: {} with hostname: {} and {} networks", template, vmInstanceName, auxiliaryNetworks.size());
        VMInstanceVO persistedVm = null;
        try {
            final VMInstanceVO vm = _vmDao.findVMByInstanceName(vmInstanceName);
            final Account owner = _entityMgr.findById(Account.class, vm.getAccountId());

            logger.debug("Allocating entries for VM: " + vm);

            vm.setDataCenterId(plan.getDataCenterId());
            if (plan.getPodId() != null) {
                vm.setPodIdToDeployIn(plan.getPodId());
            }
            assert plan.getClusterId() == null && plan.getPoolId() == null : "We currently don't support cluster and pool preset yet";
            persistedVm = _vmDao.persist(vm);

            final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(persistedVm, template, serviceOffering, null, null);

            Long rootDiskSize = rootDiskOfferingInfo.getSize();
            if (vm.getType().isUsedBySystem() && SystemVmRootDiskSize.value() != null && SystemVmRootDiskSize.value() > 0L) {
                rootDiskSize = SystemVmRootDiskSize.value();
            }
            final Long rootDiskSizeFinal = rootDiskSize;

            logger.debug("Allocating nics for {}", persistedVm);

            try {
                if (!vmProfile.getBootArgs().contains("ExternalLoadBalancerVm")) {
                    _networkMgr.allocate(vmProfile, auxiliaryNetworks, extraDhcpOptions);
                }
            } catch (final ConcurrentOperationException e) {
                throw new CloudRuntimeException("Concurrent operation while trying to allocate resources for the VM", e);
            }

            logger.debug("Allocating disks for {}",  persistedVm);

            allocateRootVolume(persistedVm, template, rootDiskOfferingInfo, owner, rootDiskSizeFinal, volume, snapshot);

            // Create new Volume context and inject event resource type, id and details to generate VOLUME.CREATE event for the ROOT disk.
            CallContext volumeContext = CallContext.register(CallContext.current(), ApiCommandResourceType.Volume);
            try {
                if (dataDiskOfferings != null) {
                    for (final DiskOfferingInfo dataDiskOfferingInfo : dataDiskOfferings) {
                        volumeMgr.allocateRawVolume(Type.DATADISK, "DATA-" + persistedVm.getId(), dataDiskOfferingInfo.getDiskOffering(), dataDiskOfferingInfo.getSize(),
                                dataDiskOfferingInfo.getMinIops(), dataDiskOfferingInfo.getMaxIops(), persistedVm, template, owner, null);
                    }
                }
                if (datadiskTemplateToDiskOfferingMap != null && !datadiskTemplateToDiskOfferingMap.isEmpty()) {
                    int diskNumber = 1;
                    for (Entry<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap : datadiskTemplateToDiskOfferingMap.entrySet()) {
                        DiskOffering diskOffering = dataDiskTemplateToDiskOfferingMap.getValue();
                        long diskOfferingSize = diskOffering.getDiskSize() / (1024 * 1024 * 1024);
                        VMTemplateVO dataDiskTemplate = _templateDao.findById(dataDiskTemplateToDiskOfferingMap.getKey());
                        volumeMgr.allocateRawVolume(Type.DATADISK, "DATA-" + persistedVm.getId() + "-" + String.valueOf(diskNumber), diskOffering, diskOfferingSize, null, null,
                                persistedVm, dataDiskTemplate, owner, Long.valueOf(diskNumber));
                        diskNumber++;
                    }
                }
            } finally {
                // Remove volumeContext and pop vmContext back
                CallContext.unregister();
            }

            logger.debug("Allocation completed for VM: " + persistedVm);
        } catch (InsufficientCapacityException | CloudRuntimeException e) {
            // Failed VM will be in Stopped. Transition it to Error, so it can be expunged by ExpungeTask or similar
            try {
                if (persistedVm != null) {
                    stateTransitTo(persistedVm, VirtualMachine.Event.OperationFailedToError, null);
                }
            } catch (NoTransitionException nte) {
                logger.error("Failed to transition {} in {} state to Error state", persistedVm, persistedVm.getState().toString());
            }
            throw e;
        }
    }

    private void allocateRootVolume(VMInstanceVO vm, VirtualMachineTemplate template, DiskOfferingInfo rootDiskOfferingInfo, Account owner, Long rootDiskSizeFinal, Volume volume, Snapshot snapshot) {
        // Create new Volume context and inject event resource type, id and details to generate VOLUME.CREATE event for the ROOT disk.
        CallContext volumeContext = CallContext.register(CallContext.current(), ApiCommandResourceType.Volume);
        try {
            String rootVolumeName = String.format("ROOT-%s", vm.getId());
            if (template.getFormat() == ImageFormat.ISO) {
                volumeMgr.allocateRawVolume(Type.ROOT, rootVolumeName, rootDiskOfferingInfo.getDiskOffering(), rootDiskOfferingInfo.getSize(),
                        rootDiskOfferingInfo.getMinIops(), rootDiskOfferingInfo.getMaxIops(), vm, template, owner, null);
            } else if (Arrays.asList(ImageFormat.BAREMETAL, ImageFormat.EXTERNAL).contains(template.getFormat())) {
                logger.debug("{} has format [{}]. Skipping ROOT volume [{}] allocation.", template, template.getFormat(), rootVolumeName);
            } else {
                volumeMgr.allocateTemplatedVolumes(Type.ROOT, rootVolumeName, rootDiskOfferingInfo.getDiskOffering(), rootDiskSizeFinal,
                        rootDiskOfferingInfo.getMinIops(), rootDiskOfferingInfo.getMaxIops(), template, vm, owner, volume, snapshot);
            }
        } finally {
            // Remove volumeContext and pop vmContext back
            CallContext.unregister();
        }
    }

    @Override
    public void allocate(final String vmInstanceName, final VirtualMachineTemplate template, final ServiceOffering serviceOffering,
            final LinkedHashMap<? extends Network, List<? extends NicProfile>> networks, final DeploymentPlan plan, final HypervisorType hyperType, Volume volume, Snapshot snapshot) throws InsufficientCapacityException {
        DiskOffering diskOffering = _diskOfferingDao.findById(serviceOffering.getDiskOfferingId());
        allocate(vmInstanceName, template, serviceOffering, new DiskOfferingInfo(diskOffering), new ArrayList<>(), networks, plan, hyperType, null, null, volume, snapshot);
    }

    VirtualMachineGuru getVmGuru(final VirtualMachine vm) {
        if(vm != null) {
            return _vmGurus.get(vm.getType());
        }
        return null;
    }

    @Override
    public void expunge(final String vmUuid) throws ResourceUnavailableException {
        try {
            advanceExpunge(vmUuid);
        } catch (final OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation timed out", e);
        } catch (final ConcurrentOperationException e) {
            throw new CloudRuntimeException("Concurrent operation ", e);
        }
    }

    @Override
    public void advanceExpunge(final String vmUuid) throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        advanceExpunge(vm);
    }

    private boolean isValidSystemVMType(VirtualMachine vm) {
        return VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType()) ||
                VirtualMachine.Type.ConsoleProxy.equals(vm.getType());
    }

    private boolean isVmDestroyed(VMInstanceVO vm) {
        if (vm == null || vm.getRemoved() != null) {
            logger.debug("Unable to find vm or vm is expunged: " + vm);
            return true;
        }
        return false;
    }

    protected void advanceExpunge(VMInstanceVO vm) throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        if (isVmDestroyed(vm)) {
            return;
        }

        if (HypervisorType.External.equals(vm.getHypervisorType())) {
            UserVmVO userVM = _userVmDao.findById(vm.getId());
            _userVmDao.loadDetails(userVM);
            userVM.setDetail(VmDetailConstants.EXPUNGE_EXTERNAL_VM, Boolean.TRUE.toString());
            _userVmDao.saveDetails(userVM);
        }

        advanceStop(vm.getUuid(), VmDestroyForcestop.value());
        vm = _vmDao.findByUuid(vm.getUuid());

        try {
            if (!stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, vm.getHostId())) {
                logger.debug("Unable to expunge the vm because it is not in the correct state: " + vm);
                throw new CloudRuntimeException("Unable to expunge " + vm);

            }
        } catch (final NoTransitionException e) {
            logger.debug("Unable to expunge the vm because it is not in the correct state: " + vm);
            throw new CloudRuntimeException("Unable to expunge " + vm, e);
        }

        logger.debug("Expunging vm " + vm);

        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);

        final HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vm.getHypervisorType());

        List<NicProfile> vmNics = profile.getNics();
        logger.debug("Cleaning up NICS [{}] of {}.", vmNics.stream().map(nic -> nic.toString()).collect(Collectors.joining(", ")),vm.toString());
        final List<Command> nicExpungeCommands = hvGuru.finalizeExpungeNics(vm, profile.getNics());
        _networkMgr.cleanupNics(profile);

        logger.debug("Cleaning up hypervisor data structures (ex. SRs in XenServer) for managed storage. Data from {}.", vm.toString());

        final List<Command> volumeExpungeCommands = hvGuru.finalizeExpungeVolumes(vm);

        final Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();

        List<Map<String, String>> targets = getTargets(hostId, vm.getId());

        if (CollectionUtils.isNotEmpty(volumeExpungeCommands) && hostId != null) {
            final Commands cmds = new Commands(Command.OnError.Stop);

            for (final Command volumeExpungeCommand : volumeExpungeCommands) {
                volumeExpungeCommand.setBypassHostMaintenance(isValidSystemVMType(vm));
                cmds.addCommand(volumeExpungeCommand);
            }

            _agentMgr.send(hostId, cmds);
            handleUnsuccessfulCommands(cmds, vm);
        }

        if (hostId != null) {
            volumeMgr.revokeAccess(vm.getId(), hostId);
        }

        volumeMgr.cleanupVolumes(vm.getId());

        if (hostId != null && CollectionUtils.isNotEmpty(targets)) {
            removeDynamicTargets(hostId, targets);
        }

        final VirtualMachineGuru guru = getVmGuru(vm);
        guru.finalizeExpunge(vm);

        userVmDeployAsIsDetailsDao.removeDetails(vm.getId());

        // Remove comments (if any)
        annotationDao.removeByEntityType(AnnotationService.EntityType.VM.name(), vm.getUuid());

        // send hypervisor-dependent commands before removing
        final List<Command> finalizeExpungeCommands = hvGuru.finalizeExpunge(vm);
        handleUnsuccessfulExpungeOperation(finalizeExpungeCommands, nicExpungeCommands, vm, hostId);

        if (logger.isDebugEnabled()) {
            logger.debug("Expunged " + vm);
        }
        resourceCleanupService.purgeExpungedVmResourcesLaterIfNeeded(vm);
    }

    private void handleUnsuccessfulExpungeOperation(List<Command> finalizeExpungeCommands, List<Command> nicExpungeCommands,
                                                    VMInstanceVO vm, Long hostId) throws OperationTimedoutException, AgentUnavailableException {
        if ((CollectionUtils.isNotEmpty(finalizeExpungeCommands) || CollectionUtils.isNotEmpty(nicExpungeCommands)) && hostId != null) {
            final Commands cmds = new Commands(Command.OnError.Stop);
            addAllExpungeCommandsFromList(finalizeExpungeCommands, cmds, vm);
            addAllExpungeCommandsFromList(nicExpungeCommands, cmds, vm);
            _agentMgr.send(hostId, cmds);
            if (!cmds.isSuccessful()) {
                for (final Answer answer : cmds.getAnswers()) {
                    if (!answer.getResult()) {
                        logger.warn("Failed to expunge vm due to: {}",  answer.getDetails());
                        throw new CloudRuntimeException(String.format("Unable to expunge %s due to %s", vm, answer.getDetails()));
                    }
                }
            }
        }
    }

    protected void handleUnsuccessfulCommands(Commands cmds, VMInstanceVO vm) throws CloudRuntimeException {
        String cmdsStr = cmds.toString();
        String vmToString = vm.toString();

        if (cmds.isSuccessful()) {
            logger.debug("The commands [{}] to {} were successful.", cmdsStr, vmToString);
            return;
        }

        logger.info("The commands [{}] to {} were unsuccessful. Handling answers.", cmdsStr, vmToString);

        Answer[] answers = cmds.getAnswers();
        if (answers == null) {
            logger.debug("There are no answers to commands [{}] to {}.", cmdsStr, vmToString);
            return;
        }

        for (Answer answer : answers) {
            String details = answer.getDetails();
            if (!answer.getResult()) {
                String message = String.format("Unable to expunge %s due to [%s].", vmToString, details);
                logger.error(message);
                throw new CloudRuntimeException(message);
            }

            logger.debug("Commands [{}] to {} got answer [{}].", cmdsStr, vmToString, details);
        }
    }

    private void addAllExpungeCommandsFromList(List<Command> cmdList, Commands cmds, VMInstanceVO vm) {
        if (CollectionUtils.isEmpty(cmdList)) {
            return;
        }
        for (final Command command : cmdList) {
            command.setBypassHostMaintenance(isValidSystemVMType(vm));
            logger.trace("Adding expunge command [{}] for VM [{}]", command.toString(), vm.toString());
            cmds.addCommand(command);
        }
    }

    private List<Map<String, String>> getTargets(Long hostId, long vmId) {
        List<Map<String, String>> targets = new ArrayList<>();

        HostVO hostVO = _hostDao.findById(hostId);

        if (hostVO == null || hostVO.getHypervisorType() != HypervisorType.VMware) {
            return targets;
        }

        List<VolumeVO> volumes = _volsDao.findByInstance(vmId);

        if (CollectionUtils.isEmpty(volumes)) {
            return targets;
        }

        for (VolumeVO volume : volumes) {
            StoragePoolVO storagePoolVO = _storagePoolDao.findById(volume.getPoolId());

            if (storagePoolVO != null && storagePoolVO.isManaged()) {
                Map<String, String> target = new HashMap<>();

                target.put(ModifyTargetsCommand.STORAGE_HOST, storagePoolVO.getHostAddress());
                target.put(ModifyTargetsCommand.STORAGE_PORT, String.valueOf(storagePoolVO.getPort()));
                target.put(ModifyTargetsCommand.IQN, volume.get_iScsiName());

                targets.add(target);
            }
        }

        return targets;
    }

    private void removeDynamicTargets(long hostId, List<Map<String, String>> targets) {
        ModifyTargetsCommand cmd = new ModifyTargetsCommand();

        cmd.setTargets(targets);
        cmd.setApplyToAllHostsInCluster(true);
        cmd.setAdd(false);
        cmd.setTargetTypeToRemove(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC);

        sendModifyTargetsCommand(cmd, hostId);
    }

    private void sendModifyTargetsCommand(ModifyTargetsCommand cmd, long hostId) {
        Answer answer = _agentMgr.easySend(hostId, cmd);

        if (answer == null) {
            logger.warn("Unable to get an answer to the modify targets command. Targets [{}].",
                    () -> cmd.getTargets().stream().map(target -> target.toString()).collect(Collectors.joining(", ")));
            return;
        }

        if (!answer.getResult()) {
            logger.warn("Unable to modify targets [{}] on the host [{}].",
                    () -> cmd.getTargets().stream().map(target -> target.toString()).collect(Collectors.joining(", ")), () -> hostId);
        }
    }

    @Override
    public boolean start() {
        vmIdsInProgressCache = new SingleCache<>(10, vmWorkJobDao::listVmIdsWithPendingJob);
        _executor.scheduleAtFixedRate(new CleanupTask(), 5, VmJobStateReportInterval.value(), TimeUnit.SECONDS);
        _executor.scheduleAtFixedRate(new TransitionTask(),  VmOpCleanupInterval.value(), VmOpCleanupInterval.value(), TimeUnit.SECONDS);
        cancelWorkItems(_nodeId);

        volumeMgr.cleanupStorageJobs();
        _workJobDao.expungeLeftoverWorkJobs(ManagementServerNode.getManagementServerId());
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> xmlParams) throws ConfigurationException {
        ReservationContextImpl.init(_entityMgr);
        VirtualMachineProfileImpl.init(_entityMgr);
        VmWorkMigrate.init(_entityMgr);

        _executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Vm-Operations-Cleanup"));
        _nodeId = ManagementServerNode.getManagementServerId();

        _agentMgr.registerForHostEvents(this, true, true, true);

        _messageBus.subscribe(VirtualMachineManager.Topics.VM_POWER_STATE, MessageDispatcher.getDispatcher(this));

        syncTransitioningVmPowerState = Boolean.TRUE.equals(VmSyncPowerStateTransitioning.value());

        return true;
    }

    protected VirtualMachineManagerImpl() {
        setStateMachine();
    }

    @Override
    public void start(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params) {
        start(vmUuid, params, null, null);
    }

    @Override
    public void start(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params, final DeploymentPlan planToDeploy, final DeploymentPlanner planner) {
        try {
            advanceStart(vmUuid, params, planToDeploy, planner);
        } catch (ConcurrentOperationException | InsufficientCapacityException e) {
            throw new CloudRuntimeException(String.format("Unable to start a VM [%s] due to [%s].", vmUuid, e.getMessage()), e).add(VirtualMachine.class, vmUuid);
        } catch (final ResourceUnavailableException e) {
            if (e.getScope() != null && e.getScope().equals(VirtualRouter.class)){
                throw new CloudRuntimeException("Network is unavailable. Please contact administrator", e).add(VirtualMachine.class, vmUuid);
            }
            throw new CloudRuntimeException(String.format("Unable to start a VM [%s] due to [%s].", vmUuid, e.getMessage()), e).add(VirtualMachine.class, vmUuid);
        }
    }

    protected boolean checkWorkItems(final VMInstanceVO vm, final State state) throws ConcurrentOperationException {
        while (true) {
            final ItWorkVO vo = _workDao.findByOutstandingWork(vm.getId(), state);
            if (vo == null) {
                logger.debug("Unable to find work for VM: {} and state: {}", vm, state);
                return true;
            }

            if (vo.getStep() == Step.Done) {
                logger.debug("Work for {} is {}", vm, vo.getStep());
                return true;
            }

            final VMInstanceVO instance = _vmDao.findById(vm.getId());
            if (instance != null && instance.getState() == State.Running) {
                logger.debug("VM is already started in DB: {}",  vm);
                return true;
            }

            if (vo.getSecondsTaskIsInactive() > VmOpCancelInterval.value()) {
                logger.warn("The task item for vm {} has been inactive for {}", vm, vo.getSecondsTaskIsInactive());
                return false;
            }

            try {
                Thread.sleep(VmOpWaitInterval.value()*1000);
            } catch (final InterruptedException e) {
                logger.info("Waiting for {} but is interrupted", vm);
                throw new ConcurrentOperationException("Waiting for " + vm + " but is interrupted");
            }
            logger.debug("Waited some more to make sure there's no activity on " + vm);
        }

    }

    @DB
    protected Ternary<VMInstanceVO, ReservationContext, ItWorkVO> changeToStartState(final VirtualMachineGuru vmGuru, final VMInstanceVO vm, final User caller,
            final Account account, Account owner, ServiceOfferingVO offering, VirtualMachineTemplate template) throws ConcurrentOperationException {
        final long vmId = vm.getId();

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Starting, vm.getType(), vm.getId());
        int retry = VmOpLockStateRetry.value();
        while (retry-- != 0) {
            try {
                final ItWorkVO workFinal = work;
                final Ternary<VMInstanceVO, ReservationContext, ItWorkVO> result =
                        Transaction.execute(new TransactionCallbackWithException<Ternary<VMInstanceVO, ReservationContext, ItWorkVO>, NoTransitionException>() {
                            @Override
                            public Ternary<VMInstanceVO, ReservationContext, ItWorkVO> doInTransaction(final TransactionStatus status) throws NoTransitionException {
                                final Journal journal = new Journal.LogJournal("Creating " + vm, logger);
                                final ItWorkVO work = _workDao.persist(workFinal);
                                final ReservationContextImpl context = new ReservationContextImpl(work.getId(), journal, caller, account);

                                if (stateTransitTo(vm, Event.StartRequested, null, work.getId())) {
                                    logger.debug("Successfully transitioned to start state for {} reservation id = {}", vm, work.getId());
                                    if (VirtualMachine.Type.User.equals(vm.type) && ResourceCountRunningVMsonly.value()) {
                                        _resourceLimitMgr.incrementVmResourceCount(owner.getAccountId(), vm.isDisplay(), offering, template);
                                    }
                                    return new Ternary<>(vm, context, work);
                                }

                                return new Ternary<>(null, null, work);
                            }
                        });

                work = result.third();
                if (result.first() != null) {
                    return result;
                }
            } catch (final NoTransitionException e) {
                logger.debug("Unable to transition into Starting state due to " + e.getMessage());
            }

            final VMInstanceVO instance = _vmDao.findById(vmId);
            if (instance == null) {
                throw new ConcurrentOperationException("Unable to acquire lock on " + vm);
            }

            logger.debug("Determining why we're unable to update the state to Starting for " + instance + ".  Retry=" + retry);

            final State state = instance.getState();
            if (state == State.Running) {
                logger.debug("VM is already started: " + vm);
                return null;
            }

            if (state.isTransitional()) {
                if (!checkWorkItems(vm, state)) {
                    throw new ConcurrentOperationException("There are concurrent operations on " + vm);
                } else {
                    continue;
                }
            }

            if (state != State.Stopped) {
                String msg = String.format("Cannot start %s in %s state", vm, state);
                logger.warn(msg);
                throw new CloudRuntimeException(msg);
            }
        }

        throw new ConcurrentOperationException("Unable to change the state of " + vm);
    }

    protected <T extends VMInstanceVO> boolean changeState(final T vm, final Event event, final Long hostId, final ItWorkVO work, final Step step) throws NoTransitionException {
        Step previousStep = null;
        if (work != null) {
            previousStep = work.getStep();
            _workDao.updateStep(work, step);
        }
        boolean result = false;
        try {
            result = stateTransitTo(vm, event, hostId);
            return result;
        } finally {
            if (!result && work != null) {
                _workDao.updateStep(work, previousStep);
            }
        }
    }

    protected boolean areAffinityGroupsAssociated(final VirtualMachineProfile vmProfile) {
        final VirtualMachine vm = vmProfile.getVirtualMachine();
        final long vmGroupCount = _affinityGroupVMMapDao.countAffinityGroupsForVm(vm.getId());

        return vmGroupCount > 0;
    }

    @Override
    public void advanceStart(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params, final DeploymentPlanner planner)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        advanceStart(vmUuid, params, null, planner);
    }

    @Override
    public void advanceStart(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params, final DeploymentPlan planToDeploy, final DeploymentPlanner planner)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            logger.debug("start parameter value of {} == {} during dispatching",
                    VirtualMachineProfile.Param.BootIntoSetup.getName(),
                    (params == null?"<very null>":params.get(VirtualMachineProfile.Param.BootIntoSetup)));

            final VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateStart(vmUuid, params, planToDeploy, planner);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            logger.debug("start parameter value of {} == {} during processing of queued job",
                    VirtualMachineProfile.Param.BootIntoSetup.getName(),
                    (params == null?"<very null>":params.get(VirtualMachineProfile.Param.BootIntoSetup)));

            final Outcome<VirtualMachine> outcome = startVmThroughJobQueue(vmUuid, params, planToDeploy, planner);

            retrieveVmFromJobOutcome(outcome, vmUuid, "startVm");

            retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
        }
    }

    private void setupAgentSecurity(final Host vmHost, final Map<String, String> sshAccessDetails, final VirtualMachine vm) throws AgentUnavailableException, OperationTimedoutException {
        final String csr = caManager.generateKeyStoreAndCsr(vmHost, sshAccessDetails);
        if (org.apache.commons.lang3.StringUtils.isNotEmpty(csr)) {
            final Map<String, String> ipAddressDetails = new HashMap<>(sshAccessDetails);
            ipAddressDetails.remove(NetworkElementCommand.ROUTER_NAME);
            addHostIpToCertDetailsIfConfigAllows(vmHost, ipAddressDetails, CAManager.AllowHostIPInSysVMAgentCert);
            final Certificate certificate = caManager.issueCertificate(csr, Arrays.asList(vm.getHostName(), vm.getInstanceName()),
                    new ArrayList<>(ipAddressDetails.values()), CAManager.CertValidityPeriod.value(), null);
            final boolean result = caManager.deployCertificate(vmHost, certificate, false, sshAccessDetails);
            if (!result) {
                logger.error("Failed to setup certificate for system vm: " + vm.getInstanceName());
            }
        } else {
            logger.error("Failed to setup keystore and generate CSR for system vm: " + vm.getInstanceName());
        }
    }

    protected void addHostIpToCertDetailsIfConfigAllows(Host vmHost, Map<String, String> ipAddressDetails, ConfigKey<Boolean> configKey) {
        if (configKey.valueIn(vmHost.getDataCenterId())) {
            ipAddressDetails.put(NetworkElementCommand.HYPERVISOR_HOST_PRIVATE_IP, vmHost.getPrivateIpAddress());
        }
    }

    protected void checkIfTemplateNeededForCreatingVmVolumes(VMInstanceVO vm) {
        final List<VolumeVO> existingRootVolumes = _volsDao.findReadyRootVolumesByInstance(vm.getId());
        if (CollectionUtils.isNotEmpty(existingRootVolumes)) {
            return;
        }
        final VMTemplateVO template = _templateDao.findById(vm.getTemplateId());
        if (template == null) {
            String msg = "Template for the VM instance can not be found, VM instance configuration needs to be updated";
            logger.error("{}. Template ID: {} seems to be removed", msg, vm.getTemplateId());
            throw new CloudRuntimeException(msg);
        }
        final VMTemplateZoneVO templateZoneVO = templateZoneDao.findByZoneTemplate(vm.getDataCenterId(), template.getId());
        if (templateZoneVO == null) {
            String msg = "Template for the VM instance can not be found in the zone ID: %s, VM instance configuration needs to be updated";
            logger.error("{}. {}", msg, template);
            throw new CloudRuntimeException(msg);
        }
    }

    protected void checkAndAttemptMigrateVmAcrossCluster(final VMInstanceVO vm, final Long destinationClusterId, final Map<Volume, StoragePool> volumePoolMap) {
        if (!HypervisorType.VMware.equals(vm.getHypervisorType()) || vm.getLastHostId() == null) {
            return;
        }
        Host lastHost = _hostDao.findById(vm.getLastHostId());
        if (lastHost == null) {
            logger.warn("Could not find last host with id [{}], skipping migrate VM [{}] across cluster check." , vm.getLastHostId(), vm.getUuid());
            return;
        }
        if (destinationClusterId.equals(lastHost.getClusterId())) {
            return;
        }
        if (volumePoolMap.values().stream().noneMatch(s -> destinationClusterId.equals(s.getClusterId()))) {
            return;
        }
        Answer[] answer = attemptHypervisorMigration(vm, volumePoolMap, lastHost.getId());
        if (answer == null) {
            logger.warn("Hypervisor inter-cluster migration during VM start failed");
            return;
        }
        // Other network related updates will be done using caller
        markVolumesInPool(vm, answer);
    }

    protected void updateVmMetadataManufacturerAndProduct(VirtualMachineTO vmTO, VMInstanceVO vm) {
        String metadataManufacturer = VmMetadataManufacturer.valueIn(vm.getDataCenterId());
        if (StringUtils.isBlank(metadataManufacturer)) {
            metadataManufacturer = VmMetadataManufacturer.defaultValue();
        }
        vmTO.setMetadataManufacturer(metadataManufacturer);
        String metadataProduct = VmMetadataProductName.valueIn(vm.getDataCenterId());
        if (StringUtils.isBlank(metadataProduct)) {
            metadataProduct = String.format("CloudStack %s Hypervisor", vm.getHypervisorType().toString());
        }
        vmTO.setMetadataProductName(metadataProduct);
    }

    protected void updateExternalVmDetailsFromPrepareAnswer(VirtualMachineTO vmTO, UserVmVO userVmVO,
                            Map<String, String> newDetails) {
        if (newDetails == null || newDetails.equals(vmTO.getDetails())) {
            return;
        }
        vmTO.setDetails(newDetails);
        userVmVO.setDetails(newDetails);
        _userVmDao.saveDetails(userVmVO);
    }

    protected void updateExternalVmDataFromPrepareAnswer(VirtualMachineTO vmTO, VirtualMachineTO updatedTO) {
        final String vncPassword = updatedTO.getVncPassword();
        final Map<String, String> details = updatedTO.getDetails();
        if ((vncPassword == null || vncPassword.equals(vmTO.getVncPassword())) &&
                (details == null || details.equals(vmTO.getDetails()))) {
            return;
        }
        UserVmVO userVmVO = _userVmDao.findById(vmTO.getId());
        if (userVmVO == null) {
            return;
        }
        if (vncPassword != null && !vncPassword.equals(userVmVO.getPassword())) {
            userVmVO.setVncPassword(vncPassword);
            vmTO.setVncPassword(vncPassword);
        }
        updateExternalVmDetailsFromPrepareAnswer(vmTO, userVmVO, updatedTO.getDetails());
    }

    protected void updateExternalVmNicsFromPrepareAnswer(VirtualMachineTO vmTO, VirtualMachineTO updatedTO) {
        if (ObjectUtils.anyNull(vmTO.getNics(), updatedTO.getNics())) {
            return;
        }
        Map<String, NicTO> originalNicsByUuid = new HashMap<>();
        for (NicTO nic : vmTO.getNics()) {
            originalNicsByUuid.put(nic.getNicUuid(), nic);
        }
        for (NicTO updatedNicTO : updatedTO.getNics()) {
            final String nicUuid = updatedNicTO.getNicUuid();
            NicTO originalNicTO = originalNicsByUuid.get(nicUuid);
            if (originalNicTO == null) {
                continue;
            }
            final String mac = updatedNicTO.getMac();
            final String ip4 = updatedNicTO.getIp();
            final String ip6 = updatedNicTO.getIp6Address();
            if (Objects.equals(mac, originalNicTO.getMac()) &&
                    Objects.equals(ip4, originalNicTO.getIp()) &&
                    Objects.equals(ip6, originalNicTO.getIp6Address())) {
                continue;
            }
            NicVO nicVO = _nicsDao.findByUuid(nicUuid);
            if (nicVO == null) {
                continue;
            }
            logger.debug("Updating {} during External VM preparation", nicVO);
            if (ip4 != null && !ip4.equals(nicVO.getIPv4Address())) {
                nicVO.setIPv4Address(ip4);
                originalNicTO.setIp(ip4);
            }
            if (ip6 != null && !ip6.equals(nicVO.getIPv6Address())) {
                nicVO.setIPv6Address(ip6);
                originalNicTO.setIp6Address(ip6);
            }
            if (mac != null && !mac.equals(nicVO.getMacAddress())) {
                nicVO.setMacAddress(mac);
                originalNicTO.setMac(mac);
            }
            _nicsDao.update(nicVO.getId(), nicVO);
        }
    }

    protected void updateExternalVmFromPrepareAnswer(VirtualMachineTO vmTO, VirtualMachineTO updatedTO) {
        if (updatedTO == null) {
            return;
        }
        updateExternalVmDataFromPrepareAnswer(vmTO, updatedTO);
        updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);
        return;
    }

    protected void processPrepareExternalProvisioning(boolean firstStart, Host host,
                 VirtualMachineProfile vmProfile, DataCenter dataCenter) throws CloudRuntimeException {
        VirtualMachineTemplate template = vmProfile.getTemplate();
        if (!firstStart || host == null || !HypervisorType.External.equals(host.getHypervisorType()) ||
                template.getExtensionId() == null) {
            return;
        }
        ExtensionDetailsVO detailsVO = extensionDetailsDao.findDetail(template.getExtensionId(),
                ApiConstants.ORCHESTRATOR_REQUIRES_PREPARE_VM);
        if (detailsVO == null || !Boolean.parseBoolean(detailsVO.getValue())) {
            return;
        }
        logger.debug("Sending PrepareExternalProvisioningCommand for {}", vmProfile);
        VirtualMachineTO virtualMachineTO = toVmTO(vmProfile);
        if (virtualMachineTO.getNics() == null || virtualMachineTO.getNics().length == 0) {
            List<NicVO> nics = _nicsDao.listByVmId(vmProfile.getId());
            NicTO[] nicTOs = new NicTO[nics.size()];
            nics.forEach(nicVO -> {
                NicTO nicTO = toNicTO(_networkModel.getNicProfile(vmProfile.getVirtualMachine(), nicVO, dataCenter),
                        HypervisorType.External);
                nicTOs[nicTO.getDeviceId()] = nicTO;
            });
            virtualMachineTO.setNics(nicTOs);
        }
        Map<String, String> vmDetails = virtualMachineTO.getExternalDetails();
        Map<String, Map<String, String>> externalDetails = extensionsManager.getExternalAccessDetails(host,
                vmDetails);
        PrepareExternalProvisioningCommand cmd = new PrepareExternalProvisioningCommand(virtualMachineTO);
        cmd.setExternalDetails(externalDetails);
        Answer answer = null;
        CloudRuntimeException cre = new CloudRuntimeException("Failed to prepare VM");
        try {
            answer = _agentMgr.send(host.getId(), cmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.error("Failed PrepareExternalProvisioningCommand due to : {}", e.getMessage(), e);
            throw cre;
        }
        if (answer == null) {
            logger.error("Invalid answer received for PrepareExternalProvisioningCommand");
            throw cre;
        }
        if (!(answer instanceof PrepareExternalProvisioningAnswer)) {
            logger.error("Unexpected answer received for PrepareExternalProvisioningCommand: [result: {}, details: {}]",
                    answer.getResult(), answer.getDetails());
            throw cre;
        }
        PrepareExternalProvisioningAnswer prepareAnswer = (PrepareExternalProvisioningAnswer)answer;
        if (!prepareAnswer.getResult()) {
            logger.error("Unexpected answer received for PrepareExternalProvisioningCommand: [result: {}, details: {}]",
                    answer.getResult(), answer.getDetails());
            throw cre;
        }
        updateExternalVmFromPrepareAnswer(virtualMachineTO, prepareAnswer.getVirtualMachineTO());
    }

    @Override
    public void orchestrateStart(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params, final DeploymentPlan planToDeploy, final DeploymentPlanner planner)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {

        logger.debug(() -> LogUtils.logGsonWithoutException("Trying to start VM [%s] using plan [%s] and planner [%s].", vmUuid, planToDeploy, planner));
        final CallContext cctxt = CallContext.current();
        final Account account = cctxt.getCallingAccount();
        final User caller = cctxt.getCallingUser();

        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        final boolean firstStart = vm.getUpdated() == 0;

        final VirtualMachineGuru vmGuru = getVmGuru(vm);

        final Account owner = _entityMgr.findById(Account.class, vm.getAccountId());
        final ServiceOfferingVO offering = _offeringDao.findById(vm.getId(), vm.getServiceOfferingId());
        final VirtualMachineTemplate template = _entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, vm.getTemplateId());
        final Ternary<VMInstanceVO, ReservationContext, ItWorkVO> start = changeToStartState(vmGuru, vm, caller, account, owner, offering, template);
        if (start == null) {
            return;
        }

        vm = start.first();
        final ReservationContext ctx = start.second();
        ItWorkVO work = start.third();

        VMInstanceVO startedVm = null;

        DataCenterDeployment plan = new DataCenterDeployment(vm.getDataCenterId(), vm.getPodIdToDeployIn(), null, null, null, null, ctx);
        if (planToDeploy != null && planToDeploy.getDataCenterId() != 0) {
            VMInstanceVO finalVm = vm;
            logger.debug(() -> DeploymentPlanningManagerImpl.logDeploymentWithoutException(finalVm, planToDeploy, planToDeploy.getAvoids(), planner));
            plan =
                    new DataCenterDeployment(planToDeploy.getDataCenterId(), planToDeploy.getPodId(), planToDeploy.getClusterId(), planToDeploy.getHostId(),
                            planToDeploy.getPoolId(), planToDeploy.getPhysicalNetworkId(), ctx);
        }

        final HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vm.getHypervisorType());

        boolean canRetry = true;
        ExcludeList avoids = null;
        try {
            final Journal journal = start.second().getJournal();

            if (planToDeploy != null) {
                avoids = planToDeploy.getAvoids();
                ExcludeList finalAvoids = avoids;
                logger.debug(() -> LogUtils.logGsonWithoutException("Avoiding components [%s] in deployment of VM [%s].", finalAvoids, vmUuid));
            }
            if (avoids == null) {
                avoids = new ExcludeList();
            }
            logger.debug("Deploy avoids pods: {}, clusters: {}, hosts: {}", avoids.getPodsToAvoid(), avoids.getClustersToAvoid(), avoids.getHostsToAvoid());

            boolean planChangedByVolume = false;
            boolean reuseVolume = true;
            final DataCenterDeployment originalPlan = plan;

            checkIfTemplateNeededForCreatingVmVolumes(vm);

            int retry = StartRetry.value();
            while (retry-- != 0) {
                logger.debug("VM start attempt #{}", (StartRetry.value() - retry));

                if (reuseVolume) {
                    final List<VolumeVO> vols = _volsDao.findReadyRootVolumesByInstance(vm.getId());
                    for (final VolumeVO vol : vols) {
                        final Long volTemplateId = vol.getTemplateId();
                        if (volTemplateId != null && volTemplateId != template.getId()) {
                            logger.debug("{} of {} is READY, but template ids don't match, let the planner reassign a new pool", vol, vm);
                            continue;
                        }

                        final StoragePool pool = (StoragePool)dataStoreMgr.getPrimaryDataStore(vol.getPoolId());
                        if (!pool.isInMaintenance()) {
                            logger.debug("Root volume is ready, need to place VM in volume's cluster");
                            final long rootVolDcId = pool.getDataCenterId();
                            final Long rootVolPodId = pool.getPodId();
                            final Long rootVolClusterId = pool.getClusterId();
                            if (planToDeploy != null && planToDeploy.getDataCenterId() != 0) {
                                final Long clusterIdSpecified = planToDeploy.getClusterId();
                                if (clusterIdSpecified != null && rootVolClusterId != null) {
                                    if (!rootVolClusterId.equals(clusterIdSpecified)) {
                                        logger.debug("Cannot satisfy the deployment plan passed in since " +
                                                "the ready Root volume is in different cluster. volume's cluster: {}, cluster specified: {}",
                                                () -> _clusterDao.findById(rootVolClusterId), () -> _clusterDao.findById(clusterIdSpecified));
                                        throw new ResourceUnavailableException(
                                                "Root volume is ready in different cluster, Deployment plan provided cannot be satisfied, unable to create a deployment for " +
                                                        vm, Cluster.class, clusterIdSpecified);
                                    }
                                }
                                plan =
                                        new DataCenterDeployment(planToDeploy.getDataCenterId(), planToDeploy.getPodId(), planToDeploy.getClusterId(),
                                                planToDeploy.getHostId(), vol.getPoolId(), null, ctx);
                            } else {
                                plan = new DataCenterDeployment(rootVolDcId, rootVolPodId, rootVolClusterId, null, vol.getPoolId(), null, ctx);
                                logger.debug("{} is READY, changing deployment plan to use this pool's dcId: {}, podId: {}, and clusterId: {}", vol, rootVolDcId,
                                        rootVolPodId, rootVolClusterId);
                                planChangedByVolume = true;
                            }
                        }
                        DataStoreProvider storeProvider = dataStoreProviderManager.getDataStoreProvider(pool.getStorageProviderName());
                        if (storeProvider != null) {
                            DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
                            if (storeDriver instanceof PrimaryDataStoreDriver) {
                                ((PrimaryDataStoreDriver)storeDriver).detachVolumeFromAllStorageNodes(vol);
                            }
                        }
                    }
                }

                VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vm, template, offering, owner, params);
                logBootModeParameters(params);
                DeployDestination dest = null;
                try {
                    dest = _dpMgr.planDeployment(vmProfile, plan, avoids, planner);
                } catch (final AffinityConflictException e2) {
                    logger.warn("Unable to create deployment, affinity rules associated to the VM conflict", e2);
                    throw new CloudRuntimeException("Unable to create deployment, affinity rules associated to the VM conflict");
                }

                if (dest == null) {
                    if (planChangedByVolume) {
                        plan = originalPlan;
                        planChangedByVolume = false;
                        reuseVolume = false;
                        continue;
                    }
                    throw new InsufficientServerCapacityException("Unable to create a deployment for " + vmProfile, DataCenter.class, plan.getDataCenterId(),
                            areAffinityGroupsAssociated(vmProfile));
                }

                avoids.addHost(dest.getHost().getId());
                if (!template.isDeployAsIs()) {
                    journal.record("Deployment found - Attempt #" + (StartRetry.value() - retry), vmProfile, dest);
                }

                long destHostId = dest.getHost().getId();
                vm.setPodIdToDeployIn(dest.getPod().getId());
                final Long clusterId = dest.getCluster().getId();
                updateOverCommitRatioForVmProfile(vmProfile, clusterId);

                StartAnswer startAnswer = null;

                try {
                    if (!changeState(vm, Event.OperationRetry, destHostId, work, Step.Prepare)) {
                        throw new ConcurrentOperationException("Unable to update the state of the Virtual Machine "+vm.getUuid()+" oldstate: "+vm.getState()+ "Event :"+Event.OperationRetry);
                    }
                } catch (final NoTransitionException e1) {
                    throw new ConcurrentOperationException(e1.getMessage());
                }

                try {
                    resetVmNicsDeviceId(vm.getId());

                    processPrepareExternalProvisioning(firstStart, dest.getHost(), vmProfile, dest.getDataCenter());

                    _networkMgr.prepare(vmProfile, dest, ctx);
                    if (vm.getHypervisorType() != HypervisorType.BareMetal && vm.getHypervisorType() != HypervisorType.External) {
                        checkAndAttemptMigrateVmAcrossCluster(vm, clusterId, dest.getStorageForDisks());
                        volumeMgr.prepare(vmProfile, dest);
                    }

                    if (!reuseVolume) {
                        reuseVolume = true;
                    }

                    vmGuru.finalizeVirtualMachineProfile(vmProfile, dest, ctx);

                    final VirtualMachineTO vmTO = hvGuru.implement(vmProfile);
                    updateVmMetadataManufacturerAndProduct(vmTO, vm);

                    checkAndSetEnterSetupMode(vmTO, params);

                    handlePath(vmTO.getDisks(), vm.getHypervisorType());
                    setVmNetworkDetails(vm, vmTO);

                    Commands cmds = new Commands(Command.OnError.Stop);
                    final Map<String, String> sshAccessDetails = _networkMgr.getSystemVMAccessDetails(vm);
                    final Map<String, String> ipAddressDetails = new HashMap<>(sshAccessDetails);
                    ipAddressDetails.remove(NetworkElementCommand.ROUTER_NAME);

                    StartCommand command = new StartCommand(vmTO, dest.getHost(), getExecuteInSequence(vm.getHypervisorType()));
                    updateStartCommandWithExternalDetails(dest.getHost(), vmTO, command);
                    cmds.addCommand(command);

                    vmGuru.finalizeDeployment(cmds, vmProfile, dest, ctx);

                    addExtraConfig(vmTO);

                    work = _workDao.findById(work.getId());
                    if (work == null || work.getStep() != Step.Prepare) {
                        throw new ConcurrentOperationException("Work steps have been changed: " + work);
                    }

                    _workDao.updateStep(work, Step.Starting);

                    _agentMgr.send(destHostId, cmds);

                    _workDao.updateStep(work, Step.Started);

                    startAnswer = cmds.getAnswer(StartAnswer.class);
                    if (startAnswer != null && startAnswer.getResult()) {
                        handlePath(vmTO.getDisks(), startAnswer.getIqnToData());

                        final String host_guid = startAnswer.getHost_guid();

                        if (host_guid != null) {
                            final HostVO finalHost = _resourceMgr.findHostByGuid(host_guid);
                            if (finalHost == null) {
                                throw new CloudRuntimeException("Host Guid " + host_guid + " doesn't exist in DB, something went wrong while processing start answer: "+startAnswer);
                            }
                            destHostId = finalHost.getId();
                        }
                        if (vmGuru.finalizeStart(vmProfile, destHostId, cmds, ctx)) {
                            syncDiskChainChange(startAnswer);

                            if (!changeState(vm, Event.OperationSucceeded, destHostId, work, Step.Done)) {
                                logger.error("Unable to transition to a new state. VM uuid: {}, VM oldstate: {}, Event: {}", vm, vm.getState(), Event.OperationSucceeded);
                                throw new ConcurrentOperationException(String.format("Failed to deploy VM %s", vm));
                            }

                            final GPUDeviceTO gpuDevice = startAnswer.getVirtualMachine().getGpuDevice();
                            if (gpuDevice != null) {
                                _resourceMgr.updateGPUDetailsForVmStart(destHostId, vm.getId(), gpuDevice);
                            }

                            if (vmInstanceDetailsDao.findDetail(vm.getId(), VmDetailConstants.DEPLOY_VM) != null) {
                                vmInstanceDetailsDao.removeDetail(vm.getId(), VmDetailConstants.DEPLOY_VM);
                            }

                            startedVm = vm;
                            logger.debug("Start completed for VM {}", vm);
                            final Host vmHost = _hostDao.findById(destHostId);
                            if (vmHost != null && (VirtualMachine.Type.ConsoleProxy.equals(vm.getType()) ||
                                    VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType())) && caManager.canProvisionCertificates()) {
                                for (int retries = 3; retries > 0; retries--) {
                                    try {
                                        final Certificate certificate = caManager.issueCertificate(null, Arrays.asList(vm.getHostName(), vm.getInstanceName()),
                                                new ArrayList<>(ipAddressDetails.values()), CAManager.CertValidityPeriod.value(), null);
                                        final boolean result = caManager.deployCertificate(vmHost, certificate, false, sshAccessDetails);
                                        if (!result) {
                                            logger.error("Failed to setup certificate for system vm: {}", vm.getInstanceName());
                                        }
                                        return;
                                    } catch (final Exception e) {
                                        logger.error("Retrying after catching exception while trying to secure agent for systemvm {}", vm, e);
                                    }
                                }
                                throw new CloudRuntimeException(String.format("Failed to setup and secure agent for systemvm %s", vm));
                            }
                            return;
                        } else {
                            logger.info("The guru did not like the answers so stopping {}", vm);
                            StopCommand stopCmd = new StopCommand(vm, getExecuteInSequence(vm.getHypervisorType()), false);
                            stopCmd.setControlIp(getControlNicIpForVM(vm));
                            Map<String, Boolean> vlanToPersistenceMap = getVlanToPersistenceMapForVM(vm.getId());
                            if (MapUtils.isNotEmpty(vlanToPersistenceMap)) {
                                stopCmd.setVlanToPersistenceMap(vlanToPersistenceMap);
                            }
                            final StopCommand cmd = stopCmd;
                            final Answer answer = _agentMgr.easySend(destHostId, cmd);
                            if (answer != null && answer instanceof StopAnswer) {
                                final StopAnswer stopAns = (StopAnswer)answer;
                                if (vm.getType() == VirtualMachine.Type.User) {
                                    final String platform = stopAns.getPlatform();
                                    if (platform != null) {
                                        final Map<String,String> vmmetadata = new HashMap<>();
                                        vmmetadata.put(vm.getInstanceName(), platform);
                                        syncVMMetaData(vmmetadata);
                                    }
                                }
                            }

                            if (answer == null || !answer.getResult()) {
                                logger.warn("Unable to stop {} dut to {}", vm, (answer != null ? answer.getDetails() : "no answers"));
                                _haMgr.scheduleStop(vm, destHostId, WorkType.ForceStop);
                                throw new ExecutionException("Unable to stop this VM, " + vm.getUuid() + " so we are unable to retry the start operation");
                            }
                            throw new ExecutionException("Unable to start  VM:" + vm.getUuid() + " due to error in finalizeStart, not retrying");
                        }
                    }
                    logger.info("Unable to start VM on {} due to {}", dest.getHost(), (startAnswer == null ? " no start answer" : startAnswer.getDetails()));
                    if (startAnswer != null && startAnswer.getContextParam("stopRetry") != null) {
                        break;
                    }

                } catch (OperationTimedoutException e) {
                    logger.debug("Unable to send the start command to host {} failed to start VM: {}", dest.getHost(), vm);
                    if (e.isActive()) {
                        _haMgr.scheduleStop(vm, destHostId, WorkType.CheckStop);
                    }
                    canRetry = false;
                    throw new AgentUnavailableException("Unable to start " + vm.getHostName(), destHostId, e);
                } catch (final ResourceUnavailableException e) {
                    logger.warn("Unable to contact resource.", e);
                    if (!avoids.add(e)) {
                        if (e.getScope() == Volume.class || e.getScope() == Nic.class) {
                            throw e;
                        } else {
                            logger.warn("unexpected ResourceUnavailableException : {}", e.getScope().getName(), e);
                            throw e;
                        }
                    }
                } catch (final InsufficientCapacityException e) {
                    logger.warn("Insufficient capacity ", e);
                    if (!avoids.add(e)) {
                        if (e.getScope() == Volume.class || e.getScope() == Nic.class) {
                            throw e;
                        } else {
                            logger.warn("unexpected InsufficientCapacityException : {}", e.getScope().getName(), e);
                        }
                    }
                } catch (ExecutionException | NoTransitionException | ResourceAllocationException e) {
                    logger.error("Failed to start instance {}", vm, e);
                    throw new AgentUnavailableException("Unable to start instance due to " + e.getMessage(), destHostId, e);
                } catch (final StorageAccessException e) {
                    logger.warn("Unable to access storage on host", e);
                } finally {
                    if (startedVm == null && canRetry) {
                        final Step prevStep = work.getStep();
                        _workDao.updateStep(work, Step.Release);

                        if ((prevStep == Step.Started || prevStep == Step.Starting) && startAnswer != null && startAnswer.getResult()) {
                            cleanup(vmGuru, vmProfile, work, Event.OperationFailed, false);
                        } else {
                            cleanup(vmGuru, vmProfile, work, Event.OperationFailed, true);
                        }
                    }
                }
            }
        } finally {
            if (startedVm == null) {
                if (VirtualMachine.Type.User.equals(vm.type) && ResourceCountRunningVMsonly.value()) {
                    _resourceLimitMgr.decrementVmResourceCount(owner.getAccountId(), vm.isDisplay(), offering, template);
                }
                if (canRetry) {
                    try {
                        conditionallySetPodToDeployIn(vm);
                        changeState(vm, Event.OperationFailed, null, work, Step.Done);
                    } catch (final NoTransitionException e) {
                        throw new ConcurrentOperationException(e.getMessage());
                    }
                }
            }

            if (planToDeploy != null) {
                planToDeploy.setAvoids(avoids);
            }
        }

        if (startedVm == null) {
            throw new CloudRuntimeException("Unable to start instance '" + vm.getHostName() + "' (" + vm.getUuid() + "), see management server log for details");
        }
    }

    protected void updateStartCommandWithExternalDetails(Host host, VirtualMachineTO vmTO, StartCommand command) {
        if (!HypervisorType.External.equals(host.getHypervisorType())) {
            return;
        }
        Map<String, String> vmExternalDetails = vmTO.getExternalDetails();
        for (NicTO nic : vmTO.getNics()) {
            if (!nic.isDefaultNic()) {
                continue;
            }
            vmExternalDetails.put(VmDetailConstants.CLOUDSTACK_VLAN, networkService.getNicVlanValueForExternalVm(nic));
        }
        Map<String, Map<String, String>> externalDetails = extensionsManager.getExternalAccessDetails(host, vmExternalDetails);
        command.setExternalDetails(externalDetails);
    }

    protected void updateStopCommandForExternalHypervisorType(final HypervisorType hypervisorType,
                  final VirtualMachineProfile vmProfile, final StopCommand stopCommand) {
        if (!HypervisorType.External.equals(hypervisorType) || vmProfile.getHostId() == null) {
            return;
        }
        Host host = _hostDao.findById(vmProfile.getHostId());
        if (host == null) {
            return;
        }
        VirtualMachineTO vmTO = ObjectUtils.defaultIfNull(stopCommand.getVirtualMachine(), toVmTO(vmProfile));
        if (MapUtils.isEmpty(vmTO.getGuestOsDetails())) {
            vmTO.setGuestOsDetails(null);
        }
        if (MapUtils.isEmpty(vmTO.getExtraConfig())) {
            vmTO.setExtraConfig(null);
        }
        if (MapUtils.isEmpty(vmTO.getNetworkIdToNetworkNameMap())) {
            vmTO.setNetworkIdToNetworkNameMap(null);
        }
        Map<String, Map<String, String>> externalDetails = extensionsManager.getExternalAccessDetails(host, vmTO.getExternalDetails());
        stopCommand.setVirtualMachine(vmTO);
        stopCommand.setExternalDetails(externalDetails);
    }

    protected void updateRebootCommandWithExternalDetails(Host host, VirtualMachineTO vmTO, RebootCommand rebootCmd) {
        if (!HypervisorType.External.equals(host.getHypervisorType())) {
            return;
        }
        Map<String, Map<String, String>> externalDetails = extensionsManager.getExternalAccessDetails(host, vmTO.getExternalDetails());
        rebootCmd.setExternalDetails(externalDetails);
    }

    public void setVmNetworkDetails(VMInstanceVO vm, VirtualMachineTO vmTO) {
        Map<Long, String> networkToNetworkNameMap = new HashMap<>();
        if (VirtualMachine.Type.User.equals(vm.getType())) {
            List<UserVmJoinVO> userVmJoinVOs = userVmJoinDao.searchByIds(vm.getId());
            if (userVmJoinVOs != null && !userVmJoinVOs.isEmpty()) {
                for (UserVmJoinVO userVmJoinVO : userVmJoinVOs) {
                    addToNetworkNameMap(userVmJoinVO.getNetworkId(), vm.getDataCenterId(), networkToNetworkNameMap);
                }
                vmTO.setNetworkIdToNetworkNameMap(networkToNetworkNameMap);
            }
        } else if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            List<DomainRouterJoinVO> routerJoinVO = domainRouterJoinDao.getRouterByIdAndTrafficType(vm.getId(), Networks.TrafficType.Guest);
            for (DomainRouterJoinVO router : routerJoinVO) {
                NetworkVO guestNetwork = _networkDao.findById(router.getNetworkId());
                if (guestNetwork.getVpcId() == null && guestNetwork.getBroadcastDomainType() == Networks.BroadcastDomainType.NSX) {
                    addToNetworkNameMap(router.getNetworkId(), vm.getDataCenterId(), networkToNetworkNameMap);
                }
            }
            vmTO.setNetworkIdToNetworkNameMap(networkToNetworkNameMap);
        }
    }

    private void addToNetworkNameMap(long networkId, long dataCenterId, Map<Long, String> networkToNetworkNameMap) {
        NetworkVO networkVO = _networkDao.findById(networkId);
        Account acc = accountDao.findById(networkVO.getAccountId());
        Domain domain = domainDao.findById(networkVO.getDomainId());
        DataCenter zone = _dcDao.findById(dataCenterId);
        if (Objects.isNull(zone)) {
            throw new CloudRuntimeException(String.format("Failed to find zone with ID: %s", dataCenterId));
        }
        if (Objects.isNull(acc)) {
            throw new CloudRuntimeException(String.format("Failed to find account with ID: %s", networkVO.getAccountId()));
        }
        if (Objects.isNull(domain)) {
            throw new CloudRuntimeException(String.format("Failed to find domain with ID: %s", networkVO.getDomainId()));
        }
        String networkName = String.format("D%s-A%s-Z%s", domain.getId(), acc.getId(), zone.getId());
        if (Objects.isNull(networkVO.getVpcId())) {
            networkName += "-S" + networkVO.getId();
        } else {
            VpcVO vpc = vpcDao.findById(networkVO.getVpcId());
            if (Objects.isNull(vpc)) {
                throw new CloudRuntimeException(String.format("Failed to find VPC with ID: %s", networkVO.getVpcId()));
            }
            networkName = String.format("%s-V%s-S%s", networkName, vpc.getId(), networkVO.getId());
        }
        networkToNetworkNameMap.put(networkVO.getId(), networkName);
    }

    private void updateOverCommitRatioForVmProfile(VirtualMachineProfile vmProfile, long clusterId) {
        final ClusterDetailsVO clusterDetailCpu = _clusterDetailsDao.findDetail(clusterId, VmDetailConstants.CPU_OVER_COMMIT_RATIO);
        final ClusterDetailsVO clusterDetailRam = _clusterDetailsDao.findDetail(clusterId, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO);
        final float parsedClusterCpuDetailCpu = Float.parseFloat(clusterDetailCpu.getValue());
        final float parsedClusterDetailRam = Float.parseFloat(clusterDetailRam.getValue());
        VMInstanceDetailVO vmDetailCpu = vmInstanceDetailsDao.findDetail(vmProfile.getId(), VmDetailConstants.CPU_OVER_COMMIT_RATIO);
        VMInstanceDetailVO vmDetailRam = vmInstanceDetailsDao.findDetail(vmProfile.getId(), VmDetailConstants.MEMORY_OVER_COMMIT_RATIO);

        if ((vmDetailCpu == null && parsedClusterCpuDetailCpu > 1f) ||
                (vmDetailCpu != null && Float.parseFloat(vmDetailCpu.getValue()) != parsedClusterCpuDetailCpu)) {
            vmInstanceDetailsDao.addDetail(vmProfile.getId(), VmDetailConstants.CPU_OVER_COMMIT_RATIO, clusterDetailCpu.getValue(), true);
        }
        if ((vmDetailRam == null && parsedClusterDetailRam > 1f) ||
                (vmDetailRam != null && Float.parseFloat(vmDetailRam.getValue()) != parsedClusterDetailRam)) {
            vmInstanceDetailsDao.addDetail(vmProfile.getId(), VmDetailConstants.MEMORY_OVER_COMMIT_RATIO, clusterDetailRam.getValue(), true);
        }

        vmProfile.setCpuOvercommitRatio(Float.parseFloat(clusterDetailCpu.getValue()));
        vmProfile.setMemoryOvercommitRatio(Float.parseFloat(clusterDetailRam.getValue()));
    }

    /**
     * Setting pod id to null can result in migration of Volumes across pods. This is not desirable for VMs which
     * have a volume in Ready state (happens when a VM is shutdown and started again).
     * So, we set it to null only when
     * migration of VM across cluster is enabled
     * Or, volumes are still in allocated state for that VM (happens when VM is Starting/deployed for the first time)
     */
    private void conditionallySetPodToDeployIn(VMInstanceVO vm) {
        if (MIGRATE_VM_ACROSS_CLUSTERS.valueIn(vm.getDataCenterId()) || areAllVolumesAllocated(vm.getId())) {
            vm.setPodIdToDeployIn(null);
        }
    }

    boolean areAllVolumesAllocated(long vmId) {
        final List<VolumeVO> vols = _volsDao.findByInstance(vmId);
        return CollectionUtils.isEmpty(vols) || vols.stream().allMatch(v -> Volume.State.Allocated.equals(v.getState()));
    }

    private void logBootModeParameters(Map<VirtualMachineProfile.Param, Object> params) {
        if (params == null) {
          return;
        }

        StringBuilder msgBuf = new StringBuilder("Uefi params ");
        boolean log = false;
        if (params.get(VirtualMachineProfile.Param.UefiFlag) != null) {
            msgBuf.append(String.format("UefiFlag: %s ", params.get(VirtualMachineProfile.Param.UefiFlag)));
            log = true;
        }
        if (params.get(VirtualMachineProfile.Param.BootType) != null) {
            msgBuf.append(String.format("Boot Type: %s ", params.get(VirtualMachineProfile.Param.BootType)));
            log = true;
        }
        if (params.get(VirtualMachineProfile.Param.BootMode) != null) {
            msgBuf.append(String.format("Boot Mode: %s ", params.get(VirtualMachineProfile.Param.BootMode)));
            log = true;
        }
        if (params.get(VirtualMachineProfile.Param.BootIntoSetup) != null) {
            msgBuf.append(String.format("Boot into Setup: %s ", params.get(VirtualMachineProfile.Param.BootIntoSetup)));
            log = true;
        }
        if (params.get(VirtualMachineProfile.Param.ConsiderLastHost) != null) {
            msgBuf.append(String.format("Consider last host: %s ", params.get(VirtualMachineProfile.Param.ConsiderLastHost)));
            log = true;
        }
        if (log) {
            logger.info(msgBuf.toString());
        }
    }

    private void resetVmNicsDeviceId(Long vmId) {
        final List<NicVO> nics = _nicsDao.listByVmId(vmId);
        Collections.sort(nics, new Comparator<NicVO>() {
            @Override
            public int compare(NicVO nic1, NicVO nic2) {
                Long nicDevId1 = Long.valueOf(nic1.getDeviceId());
                Long nicDevId2 = Long.valueOf(nic2.getDeviceId());
                return nicDevId1.compareTo(nicDevId2);
            }
        });
        int deviceId = 0;
        for (final NicVO nic : nics) {
            if (nic.getDeviceId() != deviceId) {
                nic.setDeviceId(deviceId);
                _nicsDao.update(nic.getId(),nic);
            }
            deviceId ++;
        }
    }

    private void addExtraConfig(VirtualMachineTO vmTO) {
        Map<String, String> details = vmTO.getDetails();
        for (String key : details.keySet()) {
            if (key.startsWith(ApiConstants.EXTRA_CONFIG)) {
                vmTO.addExtraConfig(key, details.get(key));
            }
        }
    }

    private void handlePath(final DiskTO[] disks, final HypervisorType hypervisorType) {
        if (hypervisorType != HypervisorType.KVM) {
            return;
        }

        if (disks != null) {
            for (final DiskTO disk : disks) {
                final Map<String, String> details = disk.getDetails();
                final boolean isManaged = details != null && Boolean.parseBoolean(details.get(DiskTO.MANAGED));

                if (isManaged && disk.getPath() == null) {
                    final Long volumeId = disk.getData().getId();
                    final VolumeVO volume = _volsDao.findById(volumeId);

                    disk.setPath(volume.get_iScsiName());

                    if (disk.getData() instanceof VolumeObjectTO) {
                        final VolumeObjectTO volTo = (VolumeObjectTO)disk.getData();

                        volTo.setPath(volume.get_iScsiName());
                    }

                    volume.setPath(volume.get_iScsiName());

                    _volsDao.update(volumeId, volume);
                }
            }
        }
    }

    private void handlePath(final DiskTO[] disks, final Map<String, Map<String, String>> iqnToData) {
        if (disks != null && iqnToData != null) {
            for (final DiskTO disk : disks) {
                final Map<String, String> details = disk.getDetails();
                final boolean isManaged = details != null && Boolean.parseBoolean(details.get(DiskTO.MANAGED));

                if (isManaged) {
                    final Long volumeId = disk.getData().getId();
                    final VolumeVO volume = _volsDao.findById(volumeId);
                    final String iScsiName = volume.get_iScsiName();

                    boolean update = false;

                    final Map<String, String> data = iqnToData.get(iScsiName);

                    if (data != null) {
                        final String path = data.get(StartAnswer.PATH);

                        if (path != null) {
                            volume.setPath(path);

                            update = true;
                        }

                        final String imageFormat = data.get(StartAnswer.IMAGE_FORMAT);

                        if (imageFormat != null) {
                            volume.setFormat(ImageFormat.valueOf(imageFormat));

                            update = true;
                        }

                        if (update) {
                            _volsDao.update(volumeId, volume);
                        }
                    }
                }
            }
        }
    }

    private void syncDiskChainChange(final StartAnswer answer) {
        final VirtualMachineTO vmSpec = answer.getVirtualMachine();

        for (final DiskTO disk : vmSpec.getDisks()) {
            if (disk.getType() != Volume.Type.ISO) {
                final VolumeObjectTO vol = (VolumeObjectTO)disk.getData();
                final VolumeVO volume = _volsDao.findById(vol.getId());
                if (vmSpec.getDeployAsIsInfo() != null && org.apache.commons.lang3.StringUtils.isNotBlank(vol.getPath())) {
                    volume.setPath(vol.getPath());
                    _volsDao.update(volume.getId(), volume);
                }

                if(vol.getPath() != null) {
                    volumeMgr.updateVolumeDiskChain(vol.getId(), vol.getPath(), vol.getChainInfo(), vol.getUpdatedDataStoreUUID());
                } else {
                    volumeMgr.updateVolumeDiskChain(vol.getId(), volume.getPath(), vol.getChainInfo(), vol.getUpdatedDataStoreUUID());
                }
            }
        }
    }

    @Override
    public void stop(final String vmUuid) throws ResourceUnavailableException {
        try {
            advanceStop(vmUuid, false);
        } catch (final OperationTimedoutException e) {
            throw new AgentUnavailableException(String.format("Unable to stop vm [%s] because the operation to stop timed out", vmUuid), e.getAgentId(), e);
        } catch (final ConcurrentOperationException e) {
            throw new CloudRuntimeException(String.format("Unable to stop vm because of a concurrent operation", vmUuid), e);
        }

    }

    @Override
    public void stopForced(String vmUuid) throws ResourceUnavailableException {
        try {
            advanceStop(vmUuid, true);
        } catch (final OperationTimedoutException e) {
            throw new AgentUnavailableException(String.format("Unable to stop vm [%s] because the operation to stop timed out", vmUuid), e.getAgentId(), e);
        } catch (final ConcurrentOperationException e) {
            throw new CloudRuntimeException(String.format("Unable to stop vm [%s] because of a concurrent operation", vmUuid), e);
        }
    }

    @Override
    public boolean getExecuteInSequence(final HypervisorType hypervisorType) {
        if (null == hypervisorType) {
            return ExecuteInSequence.value();
        }

        if (Set.of(HypervisorType.KVM, HypervisorType.XenServer, HypervisorType.Hyperv, HypervisorType.LXC).contains(hypervisorType)) {
            return false;
        } else if (hypervisorType.equals(HypervisorType.VMware)) {
            return StorageManager.shouldExecuteInSequenceOnVmware();
        }
        return ExecuteInSequence.value();
    }

    @Override
    public boolean unmanage(String vmUuid) {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (vm == null || vm.getRemoved() != null) {
            throw new CloudRuntimeException("Could not find VM with id = " + vmUuid);
        }

        final List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, vm.getId());
        if (CollectionUtils.isNotEmpty(pendingWorkJobs) || _haMgr.hasPendingHaWork(vm.getId())) {
            String msg = String.format("There are pending jobs or HA tasks working on the VM: %s, can't unmanage the VM.", vm);
            logger.info(msg);
            throw new ConcurrentOperationException(msg);
        }

        Boolean result = Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {

                logger.debug("Unmanaging VM {}", vm);

                final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
                final VirtualMachineGuru guru = getVmGuru(vm);

                try {
                    unmanageVMSnapshots(vm);
                    unmanageVMNics(profile, vm);
                    unmanageVMVolumes(vm);

                    guru.finalizeUnmanage(vm);
                } catch (Exception e) {
                    logger.error("Error while unmanaging VM {}", vm, e);
                    return false;
                }

                return true;
            }
        });

        return BooleanUtils.isTrue(result);
    }

    /**
     * Clean up VM snapshots (if any) from DB
     */
    private void unmanageVMSnapshots(VMInstanceVO vm) {
        _vmSnapshotMgr.deleteVMSnapshotsFromDB(vm.getId(), true);
    }

    /**
     * Clean up volumes for a VM to be unmanaged from CloudStack
     */
    private void unmanageVMVolumes(VMInstanceVO vm) {
        final Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
        if (hostId != null) {
            volumeMgr.revokeAccess(vm.getId(), hostId);
        }
        volumeMgr.unmanageVolumes(vm.getId());

        List<Map<String, String>> targets = getTargets(hostId, vm.getId());
        if (hostId != null && CollectionUtils.isNotEmpty(targets)) {
            removeDynamicTargets(hostId, targets);
        }
    }

    /**
     * Clean up NICs for a VM to be unmanaged from CloudStack:
     * - If 'unmanage.vm.preserve.nics' = true: then the NICs are not removed but still Allocated, to preserve MAC addresses
     * - If 'unmanage.vm.preserve.nics' = false: then the NICs are removed while unmanaging
     */
    private void unmanageVMNics(VirtualMachineProfile profile, VMInstanceVO vm) {
        logger.debug("Cleaning up NICs of {}.", vm.toString());
        Boolean preserveNics = UnmanagedVMsManager.UnmanageVMPreserveNic.valueIn(vm.getDataCenterId());
        if (BooleanUtils.isTrue(preserveNics)) {
            logger.debug("Preserve NICs configuration enabled");
            profile.setParameter(VirtualMachineProfile.Param.PreserveNics, true);
        }
        _networkMgr.unmanageNics(profile);
    }

    private List<Map<String, String>> getVolumesToDisconnect(VirtualMachine vm) {
        List<Map<String, String>> volumesToDisconnect = new ArrayList<>();

        List<VolumeVO> volumes = _volsDao.findByInstance(vm.getId());

        if (CollectionUtils.isEmpty(volumes)) {
            return volumesToDisconnect;
        }

        for (VolumeVO volume : volumes) {
            StoragePoolVO storagePool = _storagePoolDao.findById(volume.getPoolId());

            if (storagePool != null && storagePool.isManaged()) {
                Map<String, String> info = new HashMap<>();

                info.put(DiskTO.STORAGE_HOST, storagePool.getHostAddress());
                info.put(DiskTO.STORAGE_PORT, String.valueOf(storagePool.getPort()));
                info.put(DiskTO.IQN, volume.get_iScsiName());
                info.put(DiskTO.PROTOCOL_TYPE, (volume.getPoolType() != null) ? volume.getPoolType().toString() : null);

                volumesToDisconnect.add(info);
            }
        }

        return volumesToDisconnect;
    }

    protected boolean sendStop(final VirtualMachineGuru guru, final VirtualMachineProfile profile, final boolean force, final boolean checkBeforeCleanup) {
        final VirtualMachine vm = profile.getVirtualMachine();
        Map<String, Boolean> vlanToPersistenceMap = getVlanToPersistenceMapForVM(vm.getId());

        StopCommand stpCmd = new StopCommand(vm, getExecuteInSequence(vm.getHypervisorType()), checkBeforeCleanup);
        updateStopCommandForExternalHypervisorType(vm.getHypervisorType(), profile, stpCmd);
        if (MapUtils.isNotEmpty(vlanToPersistenceMap)) {
            stpCmd.setVlanToPersistenceMap(vlanToPersistenceMap);
        }
        stpCmd.setControlIp(getControlNicIpForVM(vm));
        stpCmd.setVolumesToDisconnect(getVolumesToDisconnect(vm));
        final StopCommand stop = stpCmd;
        try {
            Answer answer = null;
            if(vm.getHostId() != null) {
                answer = _agentMgr.send(vm.getHostId(), stop);
            }
            if (answer != null && answer instanceof StopAnswer) {
                final StopAnswer stopAns = (StopAnswer)answer;
                if (vm.getType() == VirtualMachine.Type.User) {
                    final String platform = stopAns.getPlatform();
                    if (platform != null) {
                        final UserVmVO userVm = _userVmDao.findById(vm.getId());
                        _userVmDao.loadDetails(userVm);
                        userVm.setDetail(VmDetailConstants.PLATFORM, platform);
                        _userVmDao.saveDetails(userVm);
                    }
                }

                final GPUDeviceTO gpuDevice = stop.getGpuDevice();
                _resourceMgr.updateGPUDetailsForVmStop(vm, gpuDevice);
                if (!answer.getResult()) {
                    final String details = answer.getDetails();
                    logger.debug("Unable to stop VM due to {}", details);
                    return false;
                }

                guru.finalizeStop(profile, answer);

                final UserVmVO userVm = _userVmDao.findById(vm.getId());
                if (vm.getType() == VirtualMachine.Type.User) {
                    if (userVm != null) {
                        userVm.setPowerState(PowerState.PowerOff);
                        _userVmDao.update(userVm.getId(), userVm);
                    }
                }
            } else {
                logger.error("Invalid answer received in response to a StopCommand for {}", vm.getInstanceName());
                return false;
            }

        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            logger.warn("Unable to stop {} due to [{}].", vm.toString(), e.getMessage(), e);
            if (!force) {
                return false;
            }
        }

        return true;
    }

    protected boolean cleanup(final VirtualMachineGuru guru, final VirtualMachineProfile profile, final ItWorkVO work, final Event event, final boolean cleanUpEvenIfUnableToStop) {
        final VirtualMachine vm = profile.getVirtualMachine();
        final State state = vm.getState();
        logger.debug("Cleaning up resources for the vm {} in {} state", vm, state);
        try {
            if (state == State.Starting) {
                if (work != null) {
                    final Step step = work.getStep();
                    if (step == Step.Starting && !cleanUpEvenIfUnableToStop) {
                        logger.warn("Unable to cleanup vm {}; work state is incorrect: {}", vm, step);
                        return false;
                    }

                    if (step == Step.Started || step == Step.Starting || step == Step.Release) {
                        if (vm.getHostId() != null) {
                            if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                                logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Starting);
                                return false;
                            }
                        }
                    }

                    if (step != Step.Release && step != Step.Prepare && step != Step.Started && step != Step.Starting) {
                        logger.debug("Cleanup is not needed for vm {}; work state is incorrect: {}", vm, step);
                        return true;
                    }
                } else {
                    if (vm.getHostId() != null) {
                        if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                            logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Starting);
                            return false;
                        }
                    }
                }

            } else if (state == State.Stopping) {
                if (vm.getHostId() != null) {
                    if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                        logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Stopping);
                        return false;
                    }
                }
            } else if (state == State.Migrating) {
                if (vm.getHostId() != null || vm.getLastHostId() != null) {
                    if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                        logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Migrating);
                        return false;
                    }
                }
            } else if (state == State.Running) {
                if (!sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                    logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Running);
                    return false;
                }
            }
        } finally {
            releaseVmResources(profile, cleanUpEvenIfUnableToStop);
        }

        return true;
    }

    protected void releaseVmResources(final VirtualMachineProfile profile, final boolean forced) {
        final VirtualMachine vm = profile.getVirtualMachine();
        final State state = vm.getState();
        try {
            _networkMgr.release(profile, forced);
            logger.debug("Successfully released network resources for the VM {} in {} state", vm, state);
        } catch (final Exception e) {
            logger.warn("Unable to release some network resources for the VM {} in {} state", vm, state, e);
        }

        try {
            if (vm.getHypervisorType() != HypervisorType.BareMetal && vm.getHypervisorType() != HypervisorType.External) {
                volumeMgr.release(profile);
                logger.debug("Successfully released storage resources for the VM {} in {} state", vm, state);
            }
        } catch (final Exception e) {
            logger.warn("Unable to release storage resources for the VM {} in {} state", vm, state, e);
        }

        logger.debug("Successfully cleaned up resources for the VM {} in {} state", vm, state);
    }

    @Override
    public void advanceStop(final String vmUuid, final boolean cleanUpEvenIfUnableToStop)
            throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {

            VmWorkJobVO placeHolder = null;
            final VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateStop(vmUuid, cleanUpEvenIfUnableToStop);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }

        } else {
            final Outcome<VirtualMachine> outcome = stopVmThroughJobQueue(vmUuid, cleanUpEvenIfUnableToStop);

            retrieveVmFromJobOutcome(outcome, vmUuid, "stopVm");

            try {
                retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (ResourceUnavailableException | InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }
        }
    }

    private void orchestrateStop(final String vmUuid, final boolean cleanUpEvenIfUnableToStop) throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        advanceStop(vm, cleanUpEvenIfUnableToStop);
    }

    private void updatePersistenceMap(Map<String, Boolean> vlanToPersistenceMap, NetworkVO networkVO) {
        if (networkVO == null) {
            return;
        }
        NetworkOfferingVO offeringVO = networkOfferingDao.findById(networkVO.getNetworkOfferingId());
        if (offeringVO == null) {
            return;
        }
        Pair<String, Boolean> data = getVMNetworkDetails(networkVO, offeringVO.isPersistent());
        Boolean shouldDeleteNwResource = (MapUtils.isNotEmpty(vlanToPersistenceMap) && data != null) ? vlanToPersistenceMap.get(data.first()) : null;
        if (data != null && (shouldDeleteNwResource == null || shouldDeleteNwResource)) {
            vlanToPersistenceMap.put(data.first(), data.second());
        }
    }

    private Map<String, Boolean> getVlanToPersistenceMapForVM(long vmId) {
        List<UserVmJoinVO> userVmJoinVOs = userVmJoinDao.searchByIds(vmId);
        Map<String, Boolean> vlanToPersistenceMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(userVmJoinVOs)) {
            for (UserVmJoinVO userVmJoinVO : userVmJoinVOs) {
                NetworkVO networkVO = _networkDao.findById(userVmJoinVO.getNetworkId());
                updatePersistenceMap(vlanToPersistenceMap, networkVO);
            }
        } else {
            VMInstanceVO vmInstanceVO = _vmDao.findById(vmId);
            if (vmInstanceVO != null && vmInstanceVO.getType() == VirtualMachine.Type.DomainRouter) {
                DomainRouterJoinVO routerVO = domainRouterJoinDao.findById(vmId);
                NetworkVO networkVO = _networkDao.findById(routerVO.getNetworkId());
                updatePersistenceMap(vlanToPersistenceMap, networkVO);
            }
        }
        return vlanToPersistenceMap;
    }

    /**
     *
     * @param networkVO - the network object used to determine the vlanId from the broadcast URI
     * @param isPersistent - indicates if the corresponding network's network offering is Persistent
     *
     * @return <VlanId, ShouldKVMBridgeBeDeleted> - basically returns the vlan ID which is used to determine the
     * bridge name for KVM hypervisor and based on the network and isolation type and persistent setting of the offering
     * we decide whether the bridge is to be deleted (KVM) if the last VM in that host is destroyed / migrated
     */
    private Pair<String, Boolean> getVMNetworkDetails(NetworkVO networkVO, boolean isPersistent) {
        URI broadcastUri = networkVO.getBroadcastUri();
        if (broadcastUri != null) {
            String scheme = broadcastUri.getScheme();
            String vlanId = Networks.BroadcastDomainType.getValue(broadcastUri);
            boolean shouldDelete = !((networkVO.getGuestType() == Network.GuestType.L2 || networkVO.getGuestType() == Network.GuestType.Isolated) &&
                    (scheme != null && scheme.equalsIgnoreCase("vlan"))
                    && isPersistent);
            if (shouldDelete) {
                int persistentNetworksCount = _networkDao.getOtherPersistentNetworksCount(networkVO.getId(), networkVO.getBroadcastUri().toString(), true);
                if (persistentNetworksCount > 0) {
                    shouldDelete = false;
                }
            }
            return new Pair<>(vlanId, shouldDelete);
        }
        return null;
    }

    private void advanceStop(final VMInstanceVO vm, final boolean cleanUpEvenIfUnableToStop) throws AgentUnavailableException, OperationTimedoutException,
    ConcurrentOperationException {
        final State state = vm.getState();
        if (state == State.Stopped) {
            logger.debug("VM is already stopped: {}", vm);
            return;
        }

        if (state == State.Destroyed || state == State.Expunging || state == State.Error) {
            logger.debug("Stopped called on {} but the state is {}", vm, state);
            return;
        }

        final ItWorkVO work = _workDao.findByOutstandingWork(vm.getId(), vm.getState());
        if (work != null) {
            logger.debug("Found an outstanding work item for this vm {} with state: {}, work id: {}", vm, vm.getState(), work.getId());
        }
        final Long hostId = vm.getHostId();
        if (hostId == null) {
            if (!cleanUpEvenIfUnableToStop) {
                logger.debug("HostId is null but this is not a forced stop, cannot stop vm {} with state: {}", vm, vm.getState());
                throw new CloudRuntimeException("Unable to stop " + vm);
            }
            try {
                stateTransitTo(vm, Event.AgentReportStopped, null, null);
            } catch (final NoTransitionException e) {
                logger.warn(e.getMessage());
            }

            if (work != null) {
                logger.debug("Updating work item to Done, id: {}", work.getId());
                work.setStep(Step.Done);
                _workDao.update(work.getId(), work);
            }
            return;
        } else {
            HostVO host = _hostDao.findById(hostId);
            if (!cleanUpEvenIfUnableToStop && vm.getState() == State.Running && host.getResourceState() == ResourceState.PrepareForMaintenance) {
                logger.debug("Host is in PrepareForMaintenance state - Stop VM operation on the VM: {} is not allowed", vm);
                throw new CloudRuntimeException(String.format("Stop VM operation on the VM %s is not allowed as host is preparing for maintenance mode", vm));
            }
        }

        final VirtualMachineGuru vmGuru = getVmGuru(vm);
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);

        try {
            if (!stateTransitTo(vm, Event.StopRequested, vm.getHostId())) {
                throw new ConcurrentOperationException(String.format("%s is being operated on.", vm.toString()));
            }
        } catch (final NoTransitionException e1) {
            if (!cleanUpEvenIfUnableToStop) {
                throw new CloudRuntimeException("We cannot stop " + vm + " when it is in state " + vm.getState());
            }
            final boolean doCleanup = true;
            logger.warn("Unable to transition the state but we're moving on because it's forced stop", e1);

            if (doCleanup) {
                if (cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.StopRequested, cleanUpEvenIfUnableToStop)) {
                    try {
                        if (work != null) {
                            logger.debug("Updating work item to Done, id: {}", work.getId());
                        }
                        if (!changeState(vm, Event.AgentReportStopped, null, work, Step.Done)) {
                            throw new CloudRuntimeException("Unable to stop " + vm);
                        }

                    } catch (final NoTransitionException e) {
                        logger.warn("Unable to cleanup {}", vm);
                        throw new CloudRuntimeException("Unable to stop " + vm, e);
                    }
                } else {
                    logger.debug("Failed to cleanup VM: {}", vm);
                    throw new CloudRuntimeException("Failed to cleanup " + vm + " , current state " + vm.getState());
                }
            }
        }

        if (vm.getState() != State.Stopping) {
            throw new CloudRuntimeException("We cannot proceed with stop VM " + vm + " since it is not in 'Stopping' state, current state: " + vm.getState());
        }

        vmGuru.prepareStop(profile);

        Map<String, Boolean> vlanToPersistenceMap = getVlanToPersistenceMapForVM(vm.getId());
        final StopCommand stop = new StopCommand(vm, getExecuteInSequence(vm.getHypervisorType()), false, cleanUpEvenIfUnableToStop);
        stop.setControlIp(getControlNicIpForVM(vm));
        updateStopCommandForExternalHypervisorType(vm.getHypervisorType(), profile, stop);
        if (MapUtils.isNotEmpty(vlanToPersistenceMap)) {
            stop.setVlanToPersistenceMap(vlanToPersistenceMap);
        }

        boolean stopped = false;
        Answer answer = null;
        try {
            answer = _agentMgr.send(vm.getHostId(), stop);
            if (answer != null) {
                if (answer instanceof StopAnswer) {
                    final StopAnswer stopAns = (StopAnswer)answer;
                    if (vm.getType() == VirtualMachine.Type.User) {
                        final String platform = stopAns.getPlatform();
                        if (platform != null) {
                            final UserVmVO userVm = _userVmDao.findById(vm.getId());
                            _userVmDao.loadDetails(userVm);
                            userVm.setDetail(VmDetailConstants.PLATFORM, platform);
                            _userVmDao.saveDetails(userVm);
                        }
                    }
                }
                stopped = answer.getResult();
                if (!stopped) {
                    throw new CloudRuntimeException("Unable to stop the virtual machine due to " + answer.getDetails());
                }
                vmGuru.finalizeStop(profile, answer);
                final GPUDeviceTO gpuDevice = stop.getGpuDevice();
                _resourceMgr.updateGPUDetailsForVmStop(vm, gpuDevice);
            } else {
                throw new CloudRuntimeException("Invalid answer received in response to a StopCommand on " + vm.instanceName);
            }

        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.warn("Unable to stop {} due to [{}].", profile.toString(), e.toString(), e);
        } finally {
            if (!stopped) {
                if (!cleanUpEvenIfUnableToStop) {
                    logger.warn("Unable to stop vm {}", vm);
                    try {
                        stateTransitTo(vm, Event.OperationFailed, vm.getHostId());
                    } catch (final NoTransitionException e) {
                        logger.warn("Unable to transition the state " + vm, e);
                    }
                    throw new CloudRuntimeException("Unable to stop " + vm);
                } else {
                    logger.warn("Unable to actually stop {} but continue with release because it's a force stop", vm);
                    vmGuru.finalizeStop(profile, answer);
                    if (HypervisorType.External.equals(profile.getHypervisorType())) {
                        try {
                            stateTransitTo(vm, VirtualMachine.Event.OperationSucceeded, null);
                        } catch (final NoTransitionException e) {
                            logger.warn("Unable to transition the state " + vm, e);
                        }
                    }

                }
            } else {
                if (VirtualMachine.systemVMs.contains(vm.getType())) {
                    HostVO systemVmHost = ApiDBUtils.findHostByTypeNameAndZoneId(vm.getDataCenterId(), vm.getHostName(),
                            VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType()) ? Host.Type.SecondaryStorageVM : Host.Type.ConsoleProxy);
                    if (systemVmHost != null) {
                        _agentMgr.agentStatusTransitTo(systemVmHost, Status.Event.ShutdownRequested, _nodeId);
                    }
                }
            }
        }

        logger.debug("{} is stopped on the host.  Proceeding to release resource held.", vm);

        releaseVmResources(profile, cleanUpEvenIfUnableToStop);

        try {
            if (work != null) {
                logger.debug("Updating the outstanding work item to Done, id: {}", work.getId());
                work.setStep(Step.Done);
                _workDao.update(work.getId(), work);
            }

            boolean result = Transaction.execute(new TransactionCallbackWithException<Boolean, NoTransitionException>() {
                @Override
                public Boolean doInTransaction(TransactionStatus status) throws NoTransitionException {
                    boolean result = stateTransitTo(vm, Event.OperationSucceeded, null);

                    if (result && VirtualMachine.Type.User.equals(vm.type) && ResourceCountRunningVMsonly.value()) {
                        ServiceOfferingVO offering = _offeringDao.findById(vm.getId(), vm.getServiceOfferingId());
                        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
                        _resourceLimitMgr.decrementVmResourceCount(vm.getAccountId(), vm.isDisplay(), offering, template);
                    }
                    return result;
                }
            });

            if (!result) {
                throw new CloudRuntimeException("unable to stop " + vm);
            }
        } catch (final NoTransitionException e) {
            String message = String.format("Unable to stop %s due to [%s].", vm.toString(), e.getMessage());
            logger.warn(message, e);
            throw new CloudRuntimeException(message, e);
        }
    }

    private void setStateMachine() {
        _stateMachine = VirtualMachine.State.getStateMachine();
    }

    protected boolean stateTransitTo(final VMInstanceVO vm, final VirtualMachine.Event e, final Long hostId, final String reservationId) throws NoTransitionException {
        vm.setReservationId(reservationId);
        return _stateMachine.transitTo(vm, e, new Pair<>(vm.getHostId(), hostId), _vmDao);
    }

    @Override
    public boolean stateTransitTo(final VirtualMachine vm1, final VirtualMachine.Event e, final Long hostId) throws NoTransitionException {
        final VMInstanceVO vm = (VMInstanceVO)vm1;

        final State oldState = vm.getState();
        if (oldState == State.Starting) {
            if (e == Event.OperationSucceeded) {
                vm.setLastHostId(hostId);
            }
        } else if (oldState == State.Stopping) {
            if (e == Event.OperationSucceeded) {
                vm.setLastHostId(vm.getHostId());
            }
        }

        if (e.equals(VirtualMachine.Event.DestroyRequested) || e.equals(VirtualMachine.Event.ExpungeOperation)) {
            _reservationDao.setResourceId(Resource.ResourceType.user_vm, null);
            _reservationDao.setResourceId(Resource.ResourceType.cpu, null);
            _reservationDao.setResourceId(Resource.ResourceType.memory, null);
            _reservationDao.setResourceId(Resource.ResourceType.gpu, null);
        }
        return _stateMachine.transitTo(vm, e, new Pair<>(vm.getHostId(), hostId), _vmDao);
    }

    @Override
    public void destroy(final String vmUuid, final boolean expunge) throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (vm == null || vm.getState() == State.Destroyed || vm.getState() == State.Expunging || vm.getRemoved() != null) {
            logger.debug("Unable to find vm or vm is destroyed: {}", vm);
            return;
        }

        logger.debug("Destroying vm {}, expunge flag {}", vm, (expunge ? "on" : "off"));

        advanceStop(vmUuid, VmDestroyForcestop.value());

        deleteVMSnapshots(vm, expunge);

        gpuService.deallocateAllGpuDevicesForVm(vm.getId());

        Transaction.execute(new TransactionCallbackWithExceptionNoReturn<CloudRuntimeException>() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) throws CloudRuntimeException {
                VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
                try {
                    if (!stateTransitTo(vm, VirtualMachine.Event.DestroyRequested, vm.getHostId())) {
                        logger.debug("Unable to destroy the vm because it is not in the correct state: {}", vm);
                        throw new CloudRuntimeException("Unable to destroy " + vm);
                    } else {
                        if (expunge) {
                            if (!stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, vm.getHostId())) {
                                logger.debug("Unable to expunge the vm because it is not in the correct state: {}", vm);
                                throw new CloudRuntimeException("Unable to expunge " + vm);
                            }
                        }
                    }
                } catch (final NoTransitionException e) {
                    String message = String.format("Unable to destroy %s due to [%s].", vm.toString(), e.getMessage());
                    logger.debug(message, e);
                    throw new CloudRuntimeException(message, e);
                }
            }
        });
    }

    /**
     * Delete vm snapshots depending on vm's hypervisor type. For Vmware, vm snapshots removal is delegated to vm cleanup thread
     * to reduce tasks sent to hypervisor (one tasks to delete vm snapshots and vm itself
     * instead of one task for each vm snapshot plus another for the vm)
     * @param vm vm
     * @param expunge indicates if vm should be expunged
     */
    private void deleteVMSnapshots(VMInstanceVO vm, boolean expunge) {
        if (! vm.getHypervisorType().equals(HypervisorType.VMware)) {
            if (!_vmSnapshotMgr.deleteAllVMSnapshots(vm.getId(), null)) {
                logger.debug("Unable to delete all snapshots for {}", vm);
                throw new CloudRuntimeException("Unable to delete vm snapshots for " + vm);
            }
        }
        else {
            if (expunge) {
                _vmSnapshotMgr.deleteVMSnapshotsFromDB(vm.getId(), false);
            }
        }
    }

    protected boolean checkVmOnHost(final VirtualMachine vm, final long hostId) throws AgentUnavailableException, OperationTimedoutException {
        final Answer answer = _agentMgr.send(hostId, new CheckVirtualMachineCommand(vm.getInstanceName()));
        if (answer == null || !answer.getResult()) {
            return false;
        }
        if (answer instanceof CheckVirtualMachineAnswer) {
            final CheckVirtualMachineAnswer vmAnswer = (CheckVirtualMachineAnswer)answer;
            if (vmAnswer.getState() == PowerState.PowerOff) {
                return false;
            }
        }

        UserVmVO userVm = _userVmDao.findById(vm.getId());
        if (userVm != null) {
            List<VMSnapshotVO> vmSnapshots = _vmSnapshotDao.findByVm(vm.getId());
            RestoreVMSnapshotCommand command = _vmSnapshotMgr.createRestoreCommand(userVm, vmSnapshots);
            if (command != null) {
                RestoreVMSnapshotAnswer restoreVMSnapshotAnswer = (RestoreVMSnapshotAnswer) _agentMgr.send(hostId, command);
                if (restoreVMSnapshotAnswer == null || !restoreVMSnapshotAnswer.getResult()) {
                    logger.warn("Unable to restore the vm snapshot from image file after live migration of vm with vmsnapshots: {}", restoreVMSnapshotAnswer == null ? "null answer" : restoreVMSnapshotAnswer.getDetails());
                }
            }
        }

        return true;
    }

    @Override
    public void storageMigration(final String vmUuid, final Map<Long, Long> volumeToPool) {
        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {

            final VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateStorageMigration(vmUuid, volumeToPool);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            final Outcome<VirtualMachine> outcome = migrateVmStorageThroughJobQueue(vmUuid, volumeToPool);

            retrieveVmFromJobOutcome(outcome, vmUuid, "migrateVmStorage");

            try {
                retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (ResourceUnavailableException | InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }
        }
    }

    private void orchestrateStorageMigration(final String vmUuid, final Map<Long, Long> volumeToPool) {
        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        Map<Volume, StoragePool> volumeToPoolMap = prepareVmStorageMigration(vm, volumeToPool);

        try {
            logger.debug("Offline migration of {} vm {} with volumes",
                            vm.getHypervisorType().toString(),
                            vm.getInstanceName());

            migrateThroughHypervisorOrStorage(vm, volumeToPoolMap);

        } catch (ConcurrentOperationException
                | InsufficientCapacityException
                | StorageUnavailableException e) {
            String msg = String.format("Failed to migrate VM: %s", vmUuid);
            logger.warn(msg, e);
            throw new CloudRuntimeException(msg, e);
        } finally {
            try {
                stateTransitTo(vm, Event.AgentReportStopped, null);
            } catch (final NoTransitionException e) {
                String anotherMEssage = String.format("failed to change vm state of VM: %s", vmUuid);
                logger.warn(anotherMEssage, e);
                throw new CloudRuntimeException(anotherMEssage, e);
            }
        }
    }

    private Answer[] attemptHypervisorMigration(VMInstanceVO vm, Map<Volume, StoragePool> volumeToPool, Long hostId) {
        if (hostId == null) {
            return null;
        }
        final HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vm.getHypervisorType());

        List<Command> commandsToSend = hvGuru.finalizeMigrate(vm, volumeToPool);

        if (CollectionUtils.isNotEmpty(commandsToSend)) {
            Commands commandsContainer = new Commands(Command.OnError.Stop);
            commandsContainer.addCommands(commandsToSend);

            try {
                return  _agentMgr.send(hostId, commandsContainer);
            } catch (AgentUnavailableException | OperationTimedoutException e) {
                logger.warn("Hypervisor migration failed for the VM: {}", vm, e);
            }
        }
        return null;
    }

    private void afterHypervisorMigrationCleanup(VMInstanceVO vm, Map<Volume, StoragePool> volumeToPool, Long sourceClusterId, Answer[] hypervisorMigrationResults) throws InsufficientCapacityException {
        logger.debug("Cleaning up after hypervisor pool migration volumes for VM {}({})", vm.getInstanceName(), vm.getUuid());

        StoragePool rootVolumePool = null;
        if (MapUtils.isNotEmpty(volumeToPool)) {
            for (Map.Entry<Volume, StoragePool> entry : volumeToPool.entrySet()) {
                if (Type.ROOT.equals(entry.getKey().getVolumeType())) {
                    rootVolumePool = entry.getValue();
                    break;
                }
            }
        }
        setDestinationPoolAndReallocateNetwork(rootVolumePool, vm);
        Long destClusterId = rootVolumePool != null ? rootVolumePool.getClusterId() : null;
        if (destClusterId != null && !destClusterId.equals(sourceClusterId)) {
            logger.debug("Resetting lastHost for VM {}({})", vm.getInstanceName(), vm.getUuid());
            vm.setLastHostId(null);
            vm.setPodIdToDeployIn(rootVolumePool.getPodId());
        }

        markVolumesInPool(vm, hypervisorMigrationResults);
    }

    private void markVolumesInPool(VMInstanceVO vm, Answer[] hypervisorMigrationResults) {
        MigrateVmToPoolAnswer relevantAnswer = null;
        if (hypervisorMigrationResults.length == 1 && !hypervisorMigrationResults[0].getResult()) {
            throw new CloudRuntimeException(String.format("VM ID: %s migration failed. %s", vm.getUuid(), hypervisorMigrationResults[0].getDetails()));
        }
        for (Answer answer : hypervisorMigrationResults) {
            logger.debug("Received an {}: {}", answer.getClass().getSimpleName(), answer);
            if (answer instanceof MigrateVmToPoolAnswer) {
                relevantAnswer = (MigrateVmToPoolAnswer) answer;
            }
        }
        if (relevantAnswer == null) {
            throw new CloudRuntimeException("No relevant migration results found");
        }
        List<VolumeObjectTO> results = relevantAnswer.getVolumeTos();
        if (results == null) {
            results = new ArrayList<>();
        }
        List<VolumeVO> volumes = _volsDao.findUsableVolumesForInstance(vm.getId());
        logger.debug("Found {} volumes for VM {}(uuid:{}, id:{})", results.size(), vm.getInstanceName(), vm.getUuid(), vm.getId());
        for (VolumeObjectTO result : results ) {
            logger.debug("Updating volume ({}) with path '{}' on pool '{}'", result.getUuid(), result.getPath(), result.getDataStoreUuid());
            VolumeVO volume = _volsDao.findById(result.getId());
            StoragePool pool = _storagePoolDao.findPoolByUUID(result.getDataStoreUuid());
            if (volume == null || pool == null) {
                continue;
            }
            volume.setPath(result.getPath());
            volume.setPoolId(pool.getId());
            if (result.getChainInfo() != null) {
                volume.setChainInfo(result.getChainInfo());
            }
            _volsDao.update(volume.getId(), volume);
        }
    }

    private void migrateThroughHypervisorOrStorage(VMInstanceVO vm, Map<Volume, StoragePool> volumeToPool) throws StorageUnavailableException, InsufficientCapacityException {
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        Pair<Long, Long> vmClusterAndHost = findClusterAndHostIdForVm(vm);
        final Long sourceClusterId = vmClusterAndHost.first();
        final Long sourceHostId = vmClusterAndHost.second();
        Answer[] hypervisorMigrationResults = attemptHypervisorMigration(vm, volumeToPool, sourceHostId);
        boolean migrationResult = false;
        if (hypervisorMigrationResults == null) {
            migrationResult = volumeMgr.storageMigration(profile, volumeToPool);
            if (migrationResult) {
                postStorageMigrationCleanup(vm, volumeToPool, _hostDao.findById(sourceHostId), sourceClusterId);
            } else {
                logger.debug("Storage migration failed");
            }
        } else {
            afterHypervisorMigrationCleanup(vm, volumeToPool, sourceClusterId, hypervisorMigrationResults);
        }
    }

    private Map<Volume, StoragePool> prepareVmStorageMigration(VMInstanceVO vm, Map<Long, Long> volumeToPool) {
        Map<Volume, StoragePool> volumeToPoolMap = new HashMap<>();
        if (MapUtils.isEmpty(volumeToPool)) {
            throw new CloudRuntimeException(String.format("Unable to migrate %s: missing volume to pool mapping.", vm.toString()));
        }
        Cluster cluster = null;
        Long dataCenterId = null;
        for (Map.Entry<Long, Long> entry: volumeToPool.entrySet()) {
            StoragePool pool = _storagePoolDao.findById(entry.getValue());
            if (pool.getClusterId() != null) {
                cluster = _clusterDao.findById(pool.getClusterId());
                break;
            }
            dataCenterId = pool.getDataCenterId();
        }
        Long podId = null;
        Long clusterId = null;
        if (cluster != null) {
            dataCenterId = cluster.getDataCenterId();
            podId = cluster.getPodId();
            clusterId = cluster.getId();
        }
        if (dataCenterId == null) {
            String msg = "Unable to migrate vm: failed to create deployment destination with given volume to pool map";
            logger.debug(msg);
            throw new CloudRuntimeException(msg);
        }
        final DataCenterDeployment destination = new DataCenterDeployment(dataCenterId, podId, clusterId, null, null, null);
        // Create a map of which volume should go in which storage pool.
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        volumeToPoolMap = createMappingVolumeAndStoragePool(profile, destination, volumeToPool);
        try {
            stateTransitTo(vm, Event.StorageMigrationRequested, null);
        } catch (final NoTransitionException e) {
            String msg = String.format("Unable to migrate vm: %s", vm.getUuid());
            logger.warn(msg, e);
            throw new CloudRuntimeException(msg, e);
        }
        return volumeToPoolMap;
    }

    private void checkDestinationForTags(StoragePool destPool, VMInstanceVO vm) {
        List<VolumeVO> vols = _volsDao.findUsableVolumesForInstance(vm.getId());

        List<String> storageTags = storageMgr.getStoragePoolTagList(destPool.getId());
        for(Volume vol : vols) {
            DiskOfferingVO diskOffering = _diskOfferingDao.findById(vol.getDiskOfferingId());
            List<String> volumeTags = StringUtils.csvTagsToList(diskOffering.getTags());
            if(! matches(volumeTags, storageTags)) {
                String msg = String.format("destination pool '%s' with tags '%s', does not support the volume diskoffering for volume '%s' (tags: '%s') ",
                        destPool.getName(),
                        StringUtils.listToCsvTags(storageTags),
                        vol.getName(),
                        StringUtils.listToCsvTags(volumeTags)
                );
                throw new CloudRuntimeException(msg);
            }
        }
    }

    static boolean matches(List<String> volumeTags, List<String> storagePoolTags) {
        boolean result = true;
        if (volumeTags != null) {
            for (String tag : volumeTags) {
                if (storagePoolTags == null || !storagePoolTags.contains(tag)) {
                    result = false;
                    break;
                }
            }
        }
        return result;
    }

    private void postStorageMigrationCleanup(VMInstanceVO vm, Map<Volume, StoragePool> volumeToPool, HostVO srcHost, Long srcClusterId) throws InsufficientCapacityException {
        StoragePool rootVolumePool = null;
        if (MapUtils.isNotEmpty(volumeToPool)) {
            for (Map.Entry<Volume, StoragePool> entry : volumeToPool.entrySet()) {
                if (Type.ROOT.equals(entry.getKey().getVolumeType())) {
                    rootVolumePool = entry.getValue();
                    break;
                }
            }
        }
        setDestinationPoolAndReallocateNetwork(rootVolumePool, vm);

        vm.setLastHostId(null);
        if (rootVolumePool != null) {
            vm.setPodIdToDeployIn(rootVolumePool.getPodId());
        }

        if (vm.getHypervisorType().equals(HypervisorType.VMware)) {
            afterStorageMigrationVmwareVMCleanup(rootVolumePool, vm, srcHost, srcClusterId);
        }
    }

    private void setDestinationPoolAndReallocateNetwork(StoragePool destPool, VMInstanceVO vm) throws InsufficientCapacityException {
        if (destPool != null && destPool.getPodId() != null && !destPool.getPodId().equals(vm.getPodIdToDeployIn())) {
            logger.debug("as the pod for vm {} has changed we are reallocating its network", vm.getInstanceName());
            final DataCenterDeployment plan = new DataCenterDeployment(vm.getDataCenterId(), destPool.getPodId(), null, null, null, null);
            final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vm, null, null, null, null);
            _networkMgr.reallocate(vmProfile, plan);
        }
    }

    private void afterStorageMigrationVmwareVMCleanup(StoragePool destPool, VMInstanceVO vm, HostVO srcHost, Long srcClusterId) {
        final Long destClusterId = destPool.getClusterId();
        if (srcClusterId != null && destClusterId != null && ! srcClusterId.equals(destClusterId) && srcHost != null) {
            final String srcDcName = _clusterDetailsDao.getVmwareDcName(srcClusterId);
            final String destDcName = _clusterDetailsDao.getVmwareDcName(destClusterId);
            if (srcDcName != null && destDcName != null && !srcDcName.equals(destDcName)) {
                removeStaleVmFromSource(vm, srcHost);
            }
        }
    }

    private void removeStaleVmFromSource(VMInstanceVO vm, HostVO srcHost) {
        logger.debug("Since VM's storage was successfully migrated across VMware Datacenters, unregistering VM: {} from source host: {}",
                vm, srcHost);
        final UnregisterVMCommand uvc = new UnregisterVMCommand(vm.getInstanceName());
        uvc.setCleanupVmFiles(true);
        try {
            _agentMgr.send(srcHost.getId(), uvc);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException(String.format(
                    "Failed to unregister VM: %s from source host: %s after successfully migrating VM's storage across VMware Datacenters",
                    vm, srcHost), e);
        }
    }

    @Override
    public void migrate(final String vmUuid, final long srcHostId, final DeployDestination dest)
            throws ResourceUnavailableException, ConcurrentOperationException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateMigrate(vmUuid, srcHostId, dest);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            final Outcome<VirtualMachine> outcome = migrateVmThroughJobQueue(vmUuid, srcHostId, dest);

            retrieveVmFromJobOutcome(outcome, vmUuid, "migrateVm");

            try {
                retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }
        }
    }

    private void orchestrateMigrate(final String vmUuid, final long srcHostId, final DeployDestination dest) throws ResourceUnavailableException, ConcurrentOperationException {
        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (vm == null) {
            logger.debug("Unable to find the vm {}", vmUuid);
            throw new CloudRuntimeException("Unable to find a virtual machine with id " + vmUuid);
        }
        migrate(vm, srcHostId, dest);
    }

    protected void migrate(final VMInstanceVO vm, final long srcHostId, final DeployDestination dest) throws ResourceUnavailableException, ConcurrentOperationException {
        logger.info("Migrating {} to {}", vm, dest);
        final long dstHostId = dest.getHost().getId();
        final Host fromHost = _hostDao.findById(srcHostId);
        if (fromHost == null) {
            logger.info("Unable to find the host to migrate from: {}", srcHostId);
            throw new CloudRuntimeException("Unable to find the host to migrate from: " + srcHostId);
        }

        if (fromHost.getClusterId() != dest.getCluster().getId() && vm.getHypervisorType() != HypervisorType.VMware) {
            final List<VolumeVO> volumes = _volsDao.findCreatedByInstance(vm.getId());
            for (final VolumeVO volume : volumes) {
                if (!_storagePoolDao.findById(volume.getPoolId()).getScope().equals(ScopeType.ZONE)) {
                    logger.info("Source and destination host are not in same cluster and all volumes are not on zone wide primary store, unable to migrate to host: {}",
                            dest.getHost());
                    throw new CloudRuntimeException(String.format(
                            "Source and destination host are not in same cluster and all volumes are not on zone wide primary store, unable to migrate to host: %s",
                            dest.getHost()));
                }
            }
        }

        final VirtualMachineGuru vmGuru = getVmGuru(vm);

        if (vm.getState() != State.Running) {
            logger.debug("VM is not Running, unable to migrate the vm {}", vm);
            throw new CloudRuntimeException("VM is not Running, unable to migrate the vm currently " + vm + " , current state: " + vm.getState().toString());
        }

        AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM_MIGRATE;
        if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER_MIGRATE;
        } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY_MIGRATE;
        }

        final VirtualMachineProfile vmSrc = new VirtualMachineProfileImpl(vm);
        vmSrc.setHost(fromHost);
        for (final NicProfile nic : _networkMgr.getNicProfiles(vm)) {
            vmSrc.addNic(nic);
        }

        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, _offeringDao.findById(vm.getId(), vm.getServiceOfferingId()), null, null);
        profile.setHost(dest.getHost());

        _networkMgr.prepareNicForMigration(profile, dest);
        volumeMgr.prepareForMigration(profile, dest);
        profile.setConfigDriveLabel(VmConfigDriveLabel.value());
        updateOverCommitRatioForVmProfile(profile, dest.getHost().getClusterId());

        final VirtualMachineTO to = toVmTO(profile);
        final PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(to);
        setVmNetworkDetails(vm, to);

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Migrating, vm.getType(), vm.getId());
        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(dstHostId);
        work = _workDao.persist(work);

        Answer pfma = null;
        try {
            pfma = _agentMgr.send(dstHostId, pfmc);
            if (pfma == null || !pfma.getResult()) {
                final String details = pfma != null ? pfma.getDetails() : "null answer returned";
                final String msg = "Unable to prepare for migration due to " + details;
                pfma = null;
                throw new AgentUnavailableException(msg, dstHostId);
            }
        } catch (final OperationTimedoutException e1) {
            throw new AgentUnavailableException("Operation timed out", dstHostId);
        } finally {
            if (pfma == null) {
                _networkMgr.rollbackNicForMigration(vmSrc, profile);
                volumeMgr.release(vm.getId(), dstHostId);
                work.setStep(Step.Done);
                _workDao.update(work.getId(), work);
            }
        }

        vm.setLastHostId(srcHostId);
        _vmDao.resetVmPowerStateTracking(vm.getId());
        try {
            if (vm.getHostId() == null || vm.getHostId() != srcHostId || !changeState(vm, Event.MigrationRequested, dstHostId, work, Step.Migrating)) {
                _networkMgr.rollbackNicForMigration(vmSrc, profile);
                if (vm != null) {
                    volumeMgr.release(vm.getId(), dstHostId);
                }

                logger.info("Migration cancelled because state has changed: {}", vm);
                throw new ConcurrentOperationException("Migration cancelled because state has changed: " + vm);
            }
        } catch (final NoTransitionException e1) {
            _networkMgr.rollbackNicForMigration(vmSrc, profile);
            volumeMgr.release(vm.getId(), dstHostId);
            logger.info("Migration cancelled because {}", e1.getMessage());
            throw new ConcurrentOperationException("Migration cancelled because " + e1.getMessage());
        } catch (final CloudRuntimeException e2) {
            _networkMgr.rollbackNicForMigration(vmSrc, profile);
            volumeMgr.release(vm.getId(), dstHostId);
            logger.info("Migration cancelled because {}", e2.getMessage());
            work.setStep(Step.Done);
            _workDao.update(work.getId(), work);
            try {
                stateTransitTo(vm, Event.OperationFailed, srcHostId);
            } catch (final NoTransitionException e3) {
                logger.warn(e3.getMessage());
            }
            throw new CloudRuntimeException("Migration cancelled because " + e2.getMessage());
        }

        boolean migrated = false;
        Map<String, DpdkTO> dpdkInterfaceMapping = new HashMap<>();
        try {
            final MigrateCommand mc = buildMigrateCommand(vm, to, dest, pfma, dpdkInterfaceMapping);

            try {
                final Answer ma = _agentMgr.send(vm.getLastHostId(), mc);
                if (ma == null || !ma.getResult()) {
                    final String details = ma != null ? ma.getDetails() : "null answer returned";
                    throw new CloudRuntimeException(details);
                }
            } catch (final OperationTimedoutException e) {
                boolean success = false;
                if (HypervisorType.KVM.equals(vm.getHypervisorType())) {
                    try {
                        final Answer answer = _agentMgr.send(vm.getHostId(), new CheckVirtualMachineCommand(vm.getInstanceName()));
                        if (answer != null && answer.getResult() && answer instanceof CheckVirtualMachineAnswer) {
                            final CheckVirtualMachineAnswer vmAnswer = (CheckVirtualMachineAnswer) answer;
                            if (VirtualMachine.PowerState.PowerOn.equals(vmAnswer.getState())) {
                                logger.info(String.format("Vm %s is found on destination host %s. Migration is successful", vm, vm.getHostId()));
                                success = true;
                            }
                        }
                    } catch (Exception ex) {
                        logger.error(String.format("Failed to get state of VM %s on destination host %s: %s", vm, vm.getHostId(), ex.getMessage()));
                    }
                }
                if (!success) {
                    if (e.isActive()) {
                        logger.warn("Active migration command so scheduling a restart for {}", vm, e);
                        _haMgr.scheduleRestart(vm, true);

                        throw new AgentUnavailableException("Operation timed out on migrating " + vm, dstHostId);
                    }
                }
            }

            try {
                if (!changeState(vm, VirtualMachine.Event.OperationSucceeded, dstHostId, work, Step.Started)) {
                    throw new ConcurrentOperationException("Unable to change the state for " + vm);
                }
            } catch (final NoTransitionException e1) {
                throw new ConcurrentOperationException("Unable to change state due to " + e1.getMessage());
            }

            try {
                if (!checkVmOnHost(vm, dstHostId)) {
                    logger.error("Unable to complete migration for {}", vm);
                    try {
                        _agentMgr.send(srcHostId, new Commands(cleanup(vm, dpdkInterfaceMapping)), null);
                    } catch (final AgentUnavailableException e) {
                        logger.error("AgentUnavailableException while cleanup on source host: {}", fromHost, e);
                    }
                    cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.AgentReportStopped, true);
                    throw new CloudRuntimeException("Unable to complete migration for " + vm);
                }
            } catch (final OperationTimedoutException e) {
                logger.warn("Error while checking the vm {} on host {}", vm, dest.getHost(), e);
            }
            migrated = true;
        } finally {
            if (!migrated) {
                logger.info("Migration was unsuccessful.  Cleaning up: {}", vm);
                _networkMgr.rollbackNicForMigration(vmSrc, profile);
                volumeMgr.release(vm.getId(), dstHostId);
                // deallocate GPU devices for the VM on the destination host
                gpuService.deallocateGpuDevicesForVmOnHost(vm.getId(), dstHostId);

                _alertMgr.sendAlert(alertType, fromHost.getDataCenterId(), fromHost.getPodId(),
                        "Unable to migrate vm " + vm.getInstanceName() + " from host " + fromHost.getName() + " in zone " + dest.getDataCenter().getName() + " and pod " +
                                dest.getPod().getName(), "Migrate Command failed.  Please check logs.");
                try {
                    _agentMgr.send(dstHostId, new Commands(cleanup(vm, dpdkInterfaceMapping)), null);
                } catch (final AgentUnavailableException ae) {
                    logger.warn("Looks like the destination Host is unavailable for cleanup", ae);
                }
                _networkMgr.setHypervisorHostname(profile, dest, false);
                try {
                    stateTransitTo(vm, Event.OperationFailed, srcHostId);
                } catch (final NoTransitionException e) {
                    logger.warn(e.getMessage());
                }
            } else {
                _networkMgr.commitNicForMigration(vmSrc, profile);
                volumeMgr.release(vm.getId(), srcHostId);
                // deallocate GPU devices for the VM on the src host after migration is complete
                gpuService.deallocateGpuDevicesForVmOnHost(vm.getId(), srcHostId);
                _networkMgr.setHypervisorHostname(profile, dest, true);
                recreateCheckpointsKvmOnVmAfterMigration(vm, dstHostId);

                updateVmPod(vm, dstHostId);
            }

            work.setStep(Step.Done);
            _workDao.update(work.getId(), work);
        }
    }

    /**
     * Create and set parameters for the {@link MigrateCommand} used in the migration and scaling of VMs.
     */
    protected MigrateCommand buildMigrateCommand(VMInstanceVO vmInstance, VirtualMachineTO virtualMachineTO, DeployDestination destination, Answer answer,
                                                 Map<String, DpdkTO> dpdkInterfaceMapping) {
        final boolean isWindows = _guestOsCategoryDao.findById(_guestOsDao.findById(vmInstance.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");
        final MigrateCommand migrateCommand = new MigrateCommand(vmInstance.getInstanceName(), destination.getHost().getPrivateIpAddress(), isWindows, virtualMachineTO,
                getExecuteInSequence(vmInstance.getHypervisorType()));

        Map<String, Boolean> vlanToPersistenceMap = getVlanToPersistenceMapForVM(vmInstance.getId());
        if (MapUtils.isNotEmpty(vlanToPersistenceMap)) {
            logger.debug("Setting VLAN persistence to [{}] as part of migrate command for VM [{}].", new Gson().toJson(vlanToPersistenceMap), virtualMachineTO);
            migrateCommand.setVlanToPersistenceMap(vlanToPersistenceMap);
        }

        migrateCommand.setAutoConvergence(StorageManager.KvmAutoConvergence.value());
        migrateCommand.setHostGuid(destination.getHost().getGuid());

        PrepareForMigrationAnswer prepareForMigrationAnswer = (PrepareForMigrationAnswer) answer;

        Map<String, DpdkTO> answerDpdkInterfaceMapping = prepareForMigrationAnswer.getDpdkInterfaceMapping();
        if (MapUtils.isNotEmpty(answerDpdkInterfaceMapping) && dpdkInterfaceMapping != null) {
            logger.debug("Setting DPDK interface mapping to [{}] as part of migrate command for VM [{}].", new Gson().toJson(vlanToPersistenceMap),
                    virtualMachineTO);
            dpdkInterfaceMapping.putAll(answerDpdkInterfaceMapping);
            migrateCommand.setDpdkInterfaceMapping(dpdkInterfaceMapping);
        }

        Integer newVmCpuShares = prepareForMigrationAnswer.getNewVmCpuShares();
        if (newVmCpuShares != null) {
            logger.debug("Setting CPU shares to [{}] as part of migrate command for VM [{}].", newVmCpuShares, virtualMachineTO);
            migrateCommand.setNewVmCpuShares(newVmCpuShares);
        }

        return migrateCommand;
    }

    private void updateVmPod(VMInstanceVO vm, long dstHostId) {
        // update the VMs pod
        HostVO host = _hostDao.findById(dstHostId);
        VMInstanceVO newVm = _vmDao.findById(vm.getId());
        newVm.setPodIdToDeployIn(host.getPodId());
        _vmDao.persist(newVm);
    }

    /**
     * We create the mapping of volumes and storage pool to migrate the VMs according to the information sent by the user.
     * If the user did not enter a complete mapping, the volumes that were left behind will be auto mapped using {@link #createStoragePoolMappingsForVolumes(VirtualMachineProfile, DataCenterDeployment, Map, List)}
     */
    protected Map<Volume, StoragePool> createMappingVolumeAndStoragePool(VirtualMachineProfile profile, Host targetHost, Map<Long, Long> userDefinedMapOfVolumesAndStoragePools) {
        return createMappingVolumeAndStoragePool(profile,
                new DataCenterDeployment(targetHost.getDataCenterId(), targetHost.getPodId(), targetHost.getClusterId(), targetHost.getId(), null, null),
                userDefinedMapOfVolumesAndStoragePools);
    }

    private Map<Volume, StoragePool> createMappingVolumeAndStoragePool(final VirtualMachineProfile profile, final DataCenterDeployment plan, final Map<Long, Long> userDefinedMapOfVolumesAndStoragePools) {
        Host targetHost = null;
        if (plan.getHostId() != null) {
            targetHost = _hostDao.findById(plan.getHostId());
        }
        Map<Volume, StoragePool> volumeToPoolObjectMap = buildMapUsingUserInformation(profile, targetHost, userDefinedMapOfVolumesAndStoragePools);

        List<Volume> volumesNotMapped = findVolumesThatWereNotMappedByTheUser(profile, volumeToPoolObjectMap);
        createStoragePoolMappingsForVolumes(profile, plan, volumeToPoolObjectMap, volumesNotMapped);
        return volumeToPoolObjectMap;
    }

    /**
     *  Given the map of volume to target storage pool entered by the user, we check for other volumes that the VM might have and were not configured.
     *  This map can be then used by CloudStack to find new target storage pools according to the target host.
     */
    protected List<Volume> findVolumesThatWereNotMappedByTheUser(VirtualMachineProfile profile, Map<Volume, StoragePool> volumeToStoragePoolObjectMap) {
        List<VolumeVO> allVolumes = _volsDao.findUsableVolumesForInstance(profile.getId());
        List<Volume> volumesNotMapped = new ArrayList<>();
        for (Volume volume : allVolumes) {
            if (!volumeToStoragePoolObjectMap.containsKey(volume)) {
                volumesNotMapped.add(volume);
            }
        }
        return volumesNotMapped;
    }

    /**
     *  Builds the map of storage pools and volumes with the information entered by the user. Before creating the an entry we validate if the migration is feasible checking if the migration is allowed and if the target host can access the defined target storage pool.
     */
    protected Map<Volume, StoragePool> buildMapUsingUserInformation(VirtualMachineProfile profile, Host targetHost, Map<Long, Long> userDefinedVolumeToStoragePoolMap) {
        Map<Volume, StoragePool> volumeToPoolObjectMap = new HashMap<>();
        if (MapUtils.isEmpty(userDefinedVolumeToStoragePoolMap)) {
            return volumeToPoolObjectMap;
        }
        for(Long volumeId: userDefinedVolumeToStoragePoolMap.keySet()) {
            VolumeVO volume = _volsDao.findById(volumeId);

            Long poolId = userDefinedVolumeToStoragePoolMap.get(volumeId);
            StoragePoolVO targetPool = _storagePoolDao.findById(poolId);
            StoragePoolVO currentPool = _storagePoolDao.findById(volume.getPoolId());

            executeManagedStorageChecksWhenTargetStoragePoolProvided(currentPool, volume, targetPool);
            if (targetHost != null && _poolHostDao.findByPoolHost(targetPool.getId(), targetHost.getId()) == null) {
                throw new CloudRuntimeException(
                        String.format("Cannot migrate the volume [%s] to the storage pool [%s] while migrating VM [%s] to target host [%s]. The host does not have access to the storage pool entered.",
                                volume.getUuid(), targetPool.getUuid(), profile.getUuid(), targetHost.getUuid()));
            }
            if (currentPool.getId() == targetPool.getId()) {
                logger.info("The volume [{}] is already allocated in storage pool [{}].", volume.getUuid(), targetPool.getUuid());
            }
            volumeToPoolObjectMap.put(volume, targetPool);
        }
        return volumeToPoolObjectMap;
    }

    /**
     *  Executes the managed storage checks for the mapping<volume, storage pool> entered by the user. The checks execute by this method are the following.
     *  <ul>
     *      <li> If the current storage pool of the volume is not a managed storage, we do not need to validate anything here.
     *      <li> If the current storage pool is a managed storage and the target storage pool ID is different from the current one, we throw an exception.
     *      <li> If the current storage pool is a managed storage and explicitly declared its capable of migration to alternate storage pools
     *  </ul>
     */
    protected void executeManagedStorageChecksWhenTargetStoragePoolProvided(StoragePoolVO currentPool, VolumeVO volume, StoragePoolVO targetPool) {
        if (!currentPool.isManaged() || currentPool.getPoolType().equals(Storage.StoragePoolType.PowerFlex)) {
            return;
        }
        if (currentPool.getId() == targetPool.getId()) {
            return;
        }

        Map<String, String> details = _storagePoolDao.getDetails(currentPool.getId());
        if (details != null && Boolean.parseBoolean(details.get(Storage.Capability.ALLOW_MIGRATE_OTHER_POOLS.toString()))) {
            return;
        }
        throw new CloudRuntimeException(String.format("Currently, a volume on managed storage can only be 'migrated' to itself " + "[volumeId=%s, currentStoragePoolId=%s, targetStoragePoolId=%s].",
                volume.getUuid(), currentPool.getUuid(), targetPool.getUuid()));
    }

    /**
     * For each one of the volumes we will map it to a storage pool that is available via the target host.
     * An exception is thrown if we cannot find a storage pool that is accessible in the target host to migrate the volume to.
     */
    protected void createStoragePoolMappingsForVolumes(VirtualMachineProfile profile, DataCenterDeployment plan, Map<Volume, StoragePool> volumeToPoolObjectMap, List<Volume> volumesNotMapped) {
        for (Volume volume : volumesNotMapped) {
            StoragePoolVO currentPool = _storagePoolDao.findById(volume.getPoolId());

            Host targetHost = null;
            if (plan.getHostId() != null) {
                targetHost = _hostDao.findById(plan.getHostId());
            }
            executeManagedStorageChecksWhenTargetStoragePoolNotProvided(targetHost, currentPool, volume);
            if (ScopeType.HOST.equals(currentPool.getScope()) || isStorageCrossClusterMigration(plan.getClusterId(), currentPool)) {
                createVolumeToStoragePoolMappingIfPossible(profile, plan, volumeToPoolObjectMap, volume, currentPool);
            } else if (shouldMapVolume(profile, currentPool)){
                volumeToPoolObjectMap.put(volume, currentPool);
            }
        }
    }

    /**
     * Returns true if it should map the volume for a storage pool to migrate.
     * <br><br>
     * Some context: VMware migration workflow requires all volumes to be mapped (even if volume stays on its current pool);
     *  however, this is not necessary/desirable for the KVM flow.
     */
    protected boolean shouldMapVolume(VirtualMachineProfile profile, StoragePoolVO currentPool) {
        boolean isManaged = currentPool.isManaged();
        boolean isNotKvm = HypervisorType.KVM != profile.getHypervisorType();
        return isNotKvm || isManaged;
    }

    /**
     *  Executes the managed storage checks for the volumes that the user has not entered a mapping of <volume, storage pool>. The following checks are performed.
     *   <ul>
     *      <li> If the current storage pool is not a managed storage, we do not need to proceed with this method;
     *      <li> We check if the target host has access to the current managed storage pool. If it does not have an exception will be thrown.
     *   </ul>
     */
    protected void executeManagedStorageChecksWhenTargetStoragePoolNotProvided(Host targetHost, StoragePoolVO currentPool, Volume volume) {
        if (!currentPool.isManaged()) {
            return;
        }
        if (targetHost != null && _poolHostDao.findByPoolHost(currentPool.getId(), targetHost.getId()) == null) {
            throw new CloudRuntimeException(String.format("The target host does not have access to the volume's managed storage pool. [volumeId=%s, storageId=%s, targetHostId=%s].", volume.getUuid(),
                    currentPool.getUuid(), targetHost.getUuid()));
        }
    }

    /**
     *  Return true if the VM migration is a cross cluster migration. To execute that, we check if the volume current storage pool cluster is different from the target cluster.
     */
    protected boolean isStorageCrossClusterMigration(Long clusterId, StoragePoolVO currentPool) {
        return clusterId != null && ScopeType.CLUSTER.equals(currentPool.getScope()) && !currentPool.getClusterId().equals(clusterId);
    }

    /**
     * We will add a mapping of volume to storage pool if needed. The conditions to add a mapping are the following:
     * <ul>
     *  <li> The candidate storage pool where the volume is to be allocated can be accessed by the target host
     *  <li> If no storage pool is found to allocate the volume we throw an exception.
     * </ul>
     *
     * Side note: this method should only be called if the volume is on local storage or if we are executing a cross cluster migration.
     */
    protected void createVolumeToStoragePoolMappingIfPossible(VirtualMachineProfile profile, DataCenterDeployment plan, Map<Volume, StoragePool> volumeToPoolObjectMap, Volume volume,
            StoragePoolVO currentPool) {
        List<StoragePool> storagePoolList = getCandidateStoragePoolsToMigrateLocalVolume(profile, plan, volume);

        if (CollectionUtils.isEmpty(storagePoolList)) {
            String msg;
            if (plan.getHostId() != null) {
                Host targetHost = _hostDao.findById(plan.getHostId());
                msg = String.format("There are no storage pools available at the target host [%s] to migrate volume [%s]", targetHost.getUuid(), volume.getUuid());
            } else {
                Cluster targetCluster = _clusterDao.findById(plan.getClusterId());
                msg = String.format("There are no storage pools available in the target cluster [%s] to migrate volume [%s]", targetCluster.getUuid(), volume.getUuid());
            }
            throw new CloudRuntimeException(msg);
        }

        Collections.shuffle(storagePoolList);
        boolean candidatePoolsListContainsVolumeCurrentStoragePool = false;
        for (StoragePool storagePool : storagePoolList) {
            if (storagePool.getId() == currentPool.getId()) {
                candidatePoolsListContainsVolumeCurrentStoragePool = true;
                break;
            }

        }
        if (!candidatePoolsListContainsVolumeCurrentStoragePool) {
            volumeToPoolObjectMap.put(volume, _storagePoolDao.findByUuid(storagePoolList.get(0).getUuid()));
        }
    }

    /**
     * We use {@link StoragePoolAllocator} objects to find storage pools for given DataCenterDeployment where we would be able to allocate the given volume.
     */
    protected List<StoragePool> getCandidateStoragePoolsToMigrateLocalVolume(VirtualMachineProfile profile, DataCenterDeployment plan, Volume volume) {
        List<StoragePool> poolList = new ArrayList<>();

        DiskOfferingVO diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
        DiskProfile diskProfile = new DiskProfile(volume, diskOffering, profile.getHypervisorType());
        ExcludeList avoid = new ExcludeList();

        StoragePoolVO volumeStoragePool = _storagePoolDao.findById(volume.getPoolId());
        if (volumeStoragePool.isLocal()) {
            diskProfile.setUseLocalStorage(true);
        }
        for (StoragePoolAllocator allocator : _storagePoolAllocators) {
            List<StoragePool> poolListFromAllocator = allocator.allocateToPool(diskProfile, profile, plan, avoid, StoragePoolAllocator.RETURN_UPTO_ALL);
            if (CollectionUtils.isEmpty(poolListFromAllocator)) {
                continue;
            }
            for (StoragePool pool : poolListFromAllocator) {
                if (pool.isLocal() || isStorageCrossClusterMigration(plan.getClusterId(), volumeStoragePool)) {
                    poolList.add(pool);
                }
            }
        }
        return poolList;
    }

    private <T extends VMInstanceVO> void moveVmToMigratingState(final T vm, final Long hostId, final ItWorkVO work) throws ConcurrentOperationException {
        try {
            if (!changeState(vm, Event.MigrationRequested, hostId, work, Step.Migrating)) {
                logger.error("Migration cancelled because state has changed: " + vm);
                throw new ConcurrentOperationException("Migration cancelled because state has changed: " + vm);
            }
        } catch (final NoTransitionException e) {
            logger.error("Migration cancelled because " + e.getMessage(), e);
            throw new ConcurrentOperationException("Migration cancelled because " + e.getMessage());
        }
    }

    private <T extends VMInstanceVO> void moveVmOutofMigratingStateOnSuccess(final T vm, final Long hostId, final ItWorkVO work) throws ConcurrentOperationException {
        try {
            if (!changeState(vm, Event.OperationSucceeded, hostId, work, Step.Started)) {
                logger.error("Unable to change the state for " + vm);
                throw new ConcurrentOperationException("Unable to change the state for " + vm);
            }
        } catch (final NoTransitionException e) {
            logger.error("Unable to change state due to " + e.getMessage(), e);
            throw new ConcurrentOperationException("Unable to change state due to " + e.getMessage());
        }
    }

    @Override
    public void migrateWithStorage(final String vmUuid, final long srcHostId, final long destHostId, final Map<Long, Long> volumeToPool)
            throws ResourceUnavailableException, ConcurrentOperationException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateMigrateWithStorage(vmUuid, srcHostId, destHostId, volumeToPool);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            final Outcome<VirtualMachine> outcome = migrateVmWithStorageThroughJobQueue(vmUuid, srcHostId, destHostId, volumeToPool);

            retrieveVmFromJobOutcome(outcome, vmUuid, "migrateVmWithStorage");

            try {
                retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }
        }
    }

    private void orchestrateMigrateWithStorage(final String vmUuid, final long srcHostId, final long destHostId, final Map<Long, Long> volumeToPool) throws ResourceUnavailableException,
    ConcurrentOperationException {

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        final HostVO srcHost = _hostDao.findById(srcHostId);
        final HostVO destHost = _hostDao.findById(destHostId);
        final VirtualMachineGuru vmGuru = getVmGuru(vm);

        final DataCenterVO dc = _dcDao.findById(destHost.getDataCenterId());
        final HostPodVO pod = _podDao.findById(destHost.getPodId());
        final Cluster cluster = _clusterDao.findById(destHost.getClusterId());
        final DeployDestination destination = new DeployDestination(dc, pod, cluster, destHost);

        final VirtualMachineProfile vmSrc = new VirtualMachineProfileImpl(vm);
        vmSrc.setHost(srcHost);
        for (final NicProfile nic : _networkMgr.getNicProfiles(vm)) {
            vmSrc.addNic(nic);
        }

        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, _offeringDao.findById(vm.getId(), vm.getServiceOfferingId()), null, null);
        profile.setHost(destHost);

        final Map<Volume, StoragePool> volumeToPoolMap = createMappingVolumeAndStoragePool(profile, destHost, volumeToPool);

        if (volumeToPoolMap == null || volumeToPoolMap.isEmpty()) {
            throw new InvalidParameterValueException("Migration of the vm " + vm + "from host " + srcHost + " to destination host " + destHost +
                    " doesn't involve migrating the volumes.");
        }

        AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM_MIGRATE;
        if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER_MIGRATE;
        } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY_MIGRATE;
        }

        _networkMgr.prepareNicForMigration(profile, destination);
        volumeMgr.prepareForMigration(profile, destination);
        final HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vm.getHypervisorType());
        final VirtualMachineTO to = hvGuru.implement(profile);

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Migrating, vm.getType(), vm.getId());
        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(destHostId);
        work = _workDao.persist(work);

        vm.setLastHostId(srcHostId);
        vm.setPodIdToDeployIn(destHost.getPodId());
        moveVmToMigratingState(vm, destHostId, work);

        boolean migrated = false;
        try {
            Nic defaultNic = _networkModel.getDefaultNic(vm.getId());

            if (defaultNic != null && VirtualMachine.Type.User.equals(vm.getType())) {
                UserVmVO userVm = _userVmDao.findById(vm.getId());
                Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vm.getId());
                userVm.setDetails(details);

                Network network = _networkModel.getNetwork(defaultNic.getNetworkId());
                if (_networkModel.isSharedNetworkWithoutServices(network.getId())) {
                    final String serviceOffering = _serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId()).getDisplayText();
                    boolean isWindows = _guestOSCategoryDao.findById(_guestOSDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");
                    List<String[]> vmData = _networkModel.generateVmData(userVm.getUserData(), userVm.getUserDataDetails(), serviceOffering, vm.getDataCenterId(), vm.getInstanceName(), vm.getHostName(), vm.getId(),
                            vm.getUuid(), defaultNic.getMacAddress(), userVm.getDetail("SSH.PublicKey"), (String) profile.getParameter(VirtualMachineProfile.Param.VmPassword), isWindows,
                            VirtualMachineManager.getHypervisorHostname(destination.getHost() != null ? destination.getHost().getName() : ""));
                    String vmName = vm.getInstanceName();
                    String configDriveIsoRootFolder = "/tmp";
                    String isoFile = configDriveIsoRootFolder + "/" + vmName + "/configDrive/" + vmName + ".iso";
                    profile.setVmData(vmData);
                    profile.setConfigDriveLabel(VmConfigDriveLabel.value());
                    profile.setConfigDriveIsoRootFolder(configDriveIsoRootFolder);
                    profile.setConfigDriveIsoFile(isoFile);

                    AttachOrDettachConfigDriveCommand dettachCommand = new AttachOrDettachConfigDriveCommand(vm.getInstanceName(), vmData, VmConfigDriveLabel.value(), false);
                    try {
                        _agentMgr.send(srcHost.getId(), dettachCommand);
                        logger.debug("Deleted config drive ISO for  vm {} in host {}", vm.getInstanceName(), srcHost);
                    } catch (OperationTimedoutException e) {
                        logger.error("TIme out occurred while exeuting command AttachOrDettachConfigDrive {}", e.getMessage(), e);

                    }
                }
            }

            volumeMgr.migrateVolumes(vm, to, srcHost, destHost, volumeToPoolMap);

            moveVmOutofMigratingStateOnSuccess(vm, destHost.getId(), work);

            try {
                if (!checkVmOnHost(vm, destHostId)) {
                    logger.error("Vm not found on destination host. Unable to complete migration for {}", vm);
                    try {
                        _agentMgr.send(srcHostId, new Commands(cleanup(vm.getInstanceName())), null);
                    } catch (final AgentUnavailableException e) {
                        logger.error("AgentUnavailableException while cleanup on source host: {}", srcHost, e);
                    }
                    cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.AgentReportStopped, true);
                    throw new CloudRuntimeException("VM not found on destination host. Unable to complete migration for " + vm);
                }
            } catch (final OperationTimedoutException e) {
                logger.error("Error while checking the vm {} is on host {}", vm, destHost, e);
            }
            migrated = true;
        } finally {
            if (!migrated) {
                logger.info("Migration was unsuccessful.  Cleaning up: {}", vm);
                _networkMgr.rollbackNicForMigration(vmSrc, profile);
                volumeMgr.release(vm.getId(), destHostId);

                _alertMgr.sendAlert(alertType, srcHost.getDataCenterId(), srcHost.getPodId(),
                        "Unable to migrate vm " + vm.getInstanceName() + " from host " + srcHost.getName() + " in zone " + dc.getName() + " and pod " + dc.getName(),
                        "Migrate Command failed.  Please check logs.");
                try {
                    _agentMgr.send(destHostId, new Commands(cleanup(vm.getInstanceName())), null);
                    vm.setPodIdToDeployIn(srcHost.getPodId());
                    stateTransitTo(vm, Event.OperationFailed, srcHostId);
                } catch (final AgentUnavailableException e) {
                    logger.warn("Looks like the destination Host is unavailable for cleanup.", e);
                } catch (final NoTransitionException e) {
                    logger.error("Error while transitioning vm from migrating to running state.", e);
                }
                _networkMgr.setHypervisorHostname(profile, destination, false);
            } else {
                _networkMgr.commitNicForMigration(vmSrc, profile);
                volumeMgr.release(vm.getId(), srcHostId);
                _networkMgr.setHypervisorHostname(profile, destination, true);
                endSnapshotChainForVolumes(volumeToPoolMap, vm.getHypervisorType());
            }

            work.setStep(Step.Done);
            _workDao.update(work.getId(), work);
        }
    }

    protected void endSnapshotChainForVolumes(Map<Volume, StoragePool> volumeToPoolMap, HypervisorType hypervisorType) {
        Set<Volume> volumes = volumeToPoolMap.keySet();
        volumes.forEach(volume -> {
            Volume volumeOnDestination = _volsDao.findByPoolIdName(volumeToPoolMap.get(volume).getId(), volume.getName());
            snapshotManager.endSnapshotChainForVolume(volumeOnDestination.getId(), hypervisorType);
        });
    }

    protected void recreateCheckpointsKvmOnVmAfterMigration(VMInstanceVO vm, long hostId) {
        if (!HypervisorType.KVM.equals(vm.getHypervisorType())) {
            logger.debug("Will not recreate checkpoint on VM as it is not running on KVM, thus it is not needed.");
            return;
        }

        List<VolumeObjectTO> volumes = getVmVolumesWithCheckpointsToRecreate(vm);

        if (volumes.isEmpty()) {
            logger.debug("Will not recreate checkpoints on VM as its volumes do not have any checkpoints associated with them.");
            return;
        }

        RecreateCheckpointsCommand recreateCheckpointsCommand = new RecreateCheckpointsCommand(volumes, vm.getInstanceName());
        Answer answer = null;
        try {
            logger.debug(String.format("Recreating the volume checkpoints with URLs [%s] of volumes [%s] on %s as part of the migration process.", volumes.stream().map(VolumeObjectTO::getCheckpointPaths).collect(Collectors.toList()), volumes, vm));
            answer = _agentMgr.send(hostId, recreateCheckpointsCommand);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.error(String.format("Exception while sending command to host [%s] to recreate checkpoints with URLs [%s] of volumes [%s] on %s due to: [%s].", hostId, volumes.stream().map(VolumeObjectTO::getCheckpointPaths).collect(Collectors.toList()), volumes, vm, e.getMessage()), e);
            throw new CloudRuntimeException(e);
        } finally {
            if (answer != null && answer.getResult()) {
                logger.debug(String.format("Successfully recreated checkpoints on VM [%s].", vm));
                return;
            }

            logger.debug(String.format("Migration on VM [%s] was successful; however, we weren't able to recreate the checkpoints on it. Marking the snapshot chain as ended." +
                    " Next snapshot will create a new snapshot chain.", vm));

            volumes.forEach(volumeObjectTO -> snapshotManager.endSnapshotChainForVolume(volumeObjectTO.getId(), HypervisorType.KVM));
        }
    }


    protected List<VolumeObjectTO> getVmVolumesWithCheckpointsToRecreate(VMInstanceVO vm) {
        List<VolumeVO> vmVolumes = _volsDao.findByInstance(vm.getId());
        List<VolumeObjectTO> volumes = new ArrayList<>();

        for (VolumeVO volume : vmVolumes) {
            Pair<List<String>, Set<String>> volumeCheckpointPathsAndImageStoreUrls = volumeMgr.getVolumeCheckpointPathsAndImageStoreUrls(volume.getId(), HypervisorType.KVM);
            if (volumeCheckpointPathsAndImageStoreUrls.first().isEmpty()) {
                continue;
            }
            VolumeObjectTO volumeTo = new VolumeObjectTO();
            volumeTo.setCheckpointPaths(volumeCheckpointPathsAndImageStoreUrls.first());
            volumeTo.setCheckpointImageStoreUrls(volumeCheckpointPathsAndImageStoreUrls.second());
            volumeTo.setPath(volume.getPath());
            volumes.add(volumeTo);
        }
        return volumes;
    }


    @Override
    public VirtualMachineTO toVmTO(final VirtualMachineProfile profile) {
        final HypervisorGuru hvGuru = _hvGuruMgr.getGuru(profile.getVirtualMachine().getHypervisorType());
        final VirtualMachineTO to = hvGuru.implement(profile);
        return to;
    }

    protected void cancelWorkItems(final long nodeId) {
        final GlobalLock scanLock = GlobalLock.getInternLock("vmmgr.cancel.workitem");

        try {
            if (scanLock.lock(3)) {
                try {
                    final List<ItWorkVO> works = _workDao.listWorkInProgressFor(nodeId);
                    for (final ItWorkVO work : works) {
                        logger.info("Handling unfinished work item: {}", work);
                        try {
                            final VMInstanceVO vm = _vmDao.findById(work.getInstanceId());
                            if (vm != null) {
                                if (work.getType() == State.Starting) {
                                    _haMgr.scheduleRestart(vm, true);
                                    work.setManagementServerId(_nodeId);
                                    work.setStep(Step.Done);
                                    _workDao.update(work.getId(), work);
                                } else if (work.getType() == State.Stopping) {
                                    _haMgr.scheduleStop(vm, vm.getHostId(), WorkType.CheckStop);
                                    work.setManagementServerId(_nodeId);
                                    work.setStep(Step.Done);
                                    _workDao.update(work.getId(), work);
                                } else if (work.getType() == State.Migrating) {
                                    _haMgr.scheduleMigration(vm);
                                    work.setStep(Step.Done);
                                    _workDao.update(work.getId(), work);
                                }
                            }
                        } catch (final Exception e) {
                            logger.error("Error while handling {}", work, e);
                        }
                    }
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }
    }

    @Override
    public void migrateAway(final String vmUuid, final long srcHostId) throws InsufficientServerCapacityException {
        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                try {
                    orchestrateMigrateAway(vmUuid, srcHostId, null);
                } catch (final InsufficientServerCapacityException e) {
                    logger.warn("Failed to deploy vm {} with original planner, sending HAPlanner", vmUuid);
                    orchestrateMigrateAway(vmUuid, srcHostId, _haMgr.getHAPlanner());
                }
            } finally {
                _workJobDao.expunge(placeHolder.getId());
            }
        } else {
            final Outcome<VirtualMachine> outcome = migrateVmAwayThroughJobQueue(vmUuid, srcHostId);

            retrieveVmFromJobOutcome(outcome, vmUuid, "migrateVmAway");

            try {
                retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (ResourceUnavailableException | InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }
        }
    }

    private void orchestrateMigrateAway(final String vmUuid, final long srcHostId, final DeploymentPlanner planner) throws InsufficientServerCapacityException {
        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (vm == null) {
            String message = String.format("Unable to find VM with uuid [%s].", vmUuid);
            logger.warn(message);
            throw new CloudRuntimeException(message);
        }

        ServiceOfferingVO offeringVO = _offeringDao.findById(vm.getId(), vm.getServiceOfferingId());
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, offeringVO, null, null);

        final Long hostId = vm.getHostId();
        if (hostId == null) {
            String message = String.format("Unable to migrate %s due to it does not have a host id.", vm.toString());
            logger.warn(message);
            throw new CloudRuntimeException(message);
        }

        final Host host = _hostDao.findById(hostId);
        Long poolId = null;
        final List<VolumeVO> vols = _volsDao.findReadyRootVolumesByInstance(vm.getId());
        for (final VolumeVO rootVolumeOfVm : vols) {
            final StoragePoolVO rootDiskPool = _storagePoolDao.findById(rootVolumeOfVm.getPoolId());
            if (rootDiskPool != null) {
                poolId = rootDiskPool.getId();
            }
        }

        final ExcludeList excludes = new ExcludeList();
        excludes.addHost(hostId);
        DataCenterDeployment plan = getMigrationDeployment(vm, host, poolId, excludes);

        DeployDestination dest = null;
        while (true) {

            try {
                plan.setMigrationPlan(true);
                dest = _dpMgr.planDeployment(profile, plan, excludes, planner);
            } catch (final AffinityConflictException e2) {
                String message = String.format("Unable to create deployment, affinity rules associated to the %s conflict.", vm.toString());
                logger.warn(message, e2);
                throw new CloudRuntimeException(message, e2);
            }
            if (dest == null) {
                logger.warn("Unable to find destination for migrating the vm {}", profile);
                throw new InsufficientServerCapacityException("Unable to find a server to migrate to.", DataCenter.class, host.getDataCenterId());
            }
            logger.debug("Found destination {} for migrating to.", dest);

            excludes.addHost(dest.getHost().getId());
            try {
                migrate(vm, srcHostId, dest);
                return;
            } catch (ResourceUnavailableException | ConcurrentOperationException e) {
                logger.warn("Unable to migrate {} to {} due to [{}]", vm.toString(), dest.getHost().toString(), e.getMessage(), e);
            }

            try {
                advanceStop(vmUuid, true);
                throw new CloudRuntimeException("Unable to migrate " + vm);
            } catch (final ResourceUnavailableException | ConcurrentOperationException | OperationTimedoutException e) {
                logger.error("Unable to stop {} due to [{}].", vm.toString(), e.getMessage(), e);
                throw new CloudRuntimeException("Unable to migrate " + vm);
            }
        }
    }

    /**
     * Check if the virtual machine has any volume in cluster-wide pool
     * @param vmId id of the virtual machine
     * @return true if volume exists on cluster-wide pool else false
     */
    @Override
    public boolean checkIfVmHasClusterWideVolumes(Long vmId) {
        final List<VolumeVO> volumesList = _volsDao.findCreatedByInstance(vmId);

        return volumesList.parallelStream()
                .anyMatch(vol -> _storagePoolDao.findById(vol.getPoolId()).getScope().equals(ScopeType.CLUSTER));

    }

    @Override
    public DataCenterDeployment getMigrationDeployment(final VirtualMachine vm, final Host host, final Long poolId, final ExcludeList excludes) {
        if (MIGRATE_VM_ACROSS_CLUSTERS.valueIn(host.getDataCenterId()) &&
                (HypervisorType.VMware.equals(host.getHypervisorType()) || !checkIfVmHasClusterWideVolumes(vm.getId()))) {
            logger.info("Searching for hosts in the zone for vm migration");
            List<Long> clustersToExclude = _clusterDao.listAllClusterIds(host.getDataCenterId());
            List<ClusterVO> clusterList = _clusterDao.listByDcHyType(host.getDataCenterId(), host.getHypervisorType().toString());
            for (ClusterVO cluster : clusterList) {
                clustersToExclude.remove(cluster.getId());
            }
            for (Long clusterId : clustersToExclude) {
                excludes.addCluster(clusterId);
            }
            if (VirtualMachine.systemVMs.contains(vm.getType())) {
                return new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), null, null, poolId, null);
            }
            return new DataCenterDeployment(host.getDataCenterId(), null, null, null, poolId, null);
        }
        return new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), null, poolId, null);
    }

    protected class CleanupTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            logger.debug("VM Operation Thread Running");
            try {
                _workDao.cleanup(VmOpCleanupWait.value());
                final Date cutDate = new Date(DateUtil.currentGMTTime().getTime() - VmOpCleanupInterval.value() * 1000);
                _workJobDao.expungeCompletedWorkJobs(cutDate);
            } catch (final Exception e) {
                logger.error("VM Operations failed due to ", e);
            }
        }
    }

    @Override
    public boolean isVirtualMachineUpgradable(final VirtualMachine vm, final ServiceOffering offering) {
        boolean isMachineUpgradable = true;
        for (final HostAllocator allocator : hostAllocators) {
            isMachineUpgradable = allocator.isVirtualMachineUpgradable(vm, offering);
            if (!isMachineUpgradable) {
                break;
            }
        }

        return isMachineUpgradable;
    }

    @Override
    public void reboot(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params) throws InsufficientCapacityException, ResourceUnavailableException {
        try {
            advanceReboot(vmUuid, params);
        } catch (final ConcurrentOperationException e) {
            throw new CloudRuntimeException("Unable to reboot a VM due to concurrent operation", e);
        }
    }

    @Override
    public void advanceReboot(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if ( jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                logger.debug("reboot parameter value of {} == {} at orchestration", VirtualMachineProfile.Param.BootIntoSetup.getName(),
                        (params == null? "<very null>":params.get(VirtualMachineProfile.Param.BootIntoSetup)));
                orchestrateReboot(vmUuid, params);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            logger.debug("reboot parameter value of {} == {} through job-queue", VirtualMachineProfile.Param.BootIntoSetup.getName(),
                    (params == null? "<very null>":params.get(VirtualMachineProfile.Param.BootIntoSetup)));
            final Outcome<VirtualMachine> outcome = rebootVmThroughJobQueue(vmUuid, params);

            retrieveVmFromJobOutcome(outcome, vmUuid, "rebootVm");

            retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
        }
    }

    private void orchestrateReboot(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params) throws InsufficientCapacityException, ConcurrentOperationException,
    ResourceUnavailableException {
        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (_vmSnapshotMgr.hasActiveVMSnapshotTasks(vm.getId())) {
            logger.error("Unable to reboot VM {} due to: {} has active VM snapshot tasks", vm, vm.getInstanceName());
            throw new CloudRuntimeException("Unable to reboot VM " + vm + " due to: " + vm.getInstanceName() + " has active VM snapshots tasks");
        }
        final DataCenter dc = _entityMgr.findById(DataCenter.class, vm.getDataCenterId());
        final Host host = _hostDao.findById(vm.getHostId());
        if (host == null) {
            throw new CloudRuntimeException("Unable to retrieve host with id " + vm.getHostId());
        }
        final Cluster cluster = _entityMgr.findById(Cluster.class, host.getClusterId());
        final Pod pod = _entityMgr.findById(Pod.class, host.getPodId());
        final DeployDestination dest = new DeployDestination(dc, pod, cluster, host);

        try {
            final Commands cmds = new Commands(Command.OnError.Stop);
            RebootCommand rebootCmd = new RebootCommand(vm.getInstanceName(), getExecuteInSequence(vm.getHypervisorType()));
            VirtualMachineTO vmTo = getVmTO(vm.getId());
            checkAndSetEnterSetupMode(vmTo, params);
            rebootCmd.setVirtualMachine(vmTo);
            updateRebootCommandWithExternalDetails(host, vmTo, rebootCmd);
            cmds.addCommand(rebootCmd);
            _agentMgr.send(host.getId(), cmds);

            final Answer rebootAnswer = cmds.getAnswer(RebootAnswer.class);
            if (rebootAnswer != null && rebootAnswer.getResult()) {
                boolean isVmSecurityGroupEnabled = _securityGroupManager.isVmSecurityGroupEnabled(vm.getId());
                if (isVmSecurityGroupEnabled && vm.getType() == VirtualMachine.Type.User) {
                    List<Long> affectedVms = new ArrayList<>();
                    affectedVms.add(vm.getId());
                    _securityGroupManager.scheduleRulesetUpdateToHosts(affectedVms, true, null);
                }
                if (vmTo.getGpuDevice() != null) {
                    _resourceMgr.updateGPUDetailsForVmStart(host.getId(), vm.getId(), vmTo.getGpuDevice());
                }
                return;
            }

            String errorMsg = "Unable to reboot VM " + vm + " on " + dest.getHost() + " due to " + (rebootAnswer == null ? "no reboot response" : rebootAnswer.getDetails());
            logger.info(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        } catch (final OperationTimedoutException e) {
            logger.warn("Unable to send the reboot command to host {} for the vm {} due to operation timeout.", dest.getHost(), vm, e);
            throw new CloudRuntimeException("Failed to reboot the vm on host " + dest.getHost(), e);
        }
    }

    private void checkAndSetEnterSetupMode(VirtualMachineTO vmTo, Map<VirtualMachineProfile.Param, Object> params) {
        Boolean enterSetup = null;
        if (params != null) {
            enterSetup = (Boolean) params.get(VirtualMachineProfile.Param.BootIntoSetup);
        }
        logger.debug("Orchestrating VM reboot for '{}' {} set to {}", vmTo.getName(), VirtualMachineProfile.Param.BootIntoSetup, enterSetup);
        vmTo.setEnterHardwareSetup(enterSetup == null ? false : enterSetup);
    }

    protected VirtualMachineTO getVmTO(Long vmId) {
        final VMInstanceVO vm = _vmDao.findById(vmId);
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        final List<NicVO> nics = _nicsDao.listByVmId(profile.getId());
        Collections.sort(nics, new Comparator<NicVO>() {
            @Override
            public int compare(NicVO nic1, NicVO nic2) {
                Long nicId1 = Long.valueOf(nic1.getDeviceId());
                Long nicId2 = Long.valueOf(nic2.getDeviceId());
                return nicId1.compareTo(nicId2);
            }
        });

        for (final NicVO nic : nics) {
            final Network network = _networkModel.getNetwork(nic.getNetworkId());
            final NicProfile nicProfile =
                    new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), null, _networkModel.isSecurityGroupSupportedInNetwork(network),
                            _networkModel.getNetworkTag(profile.getHypervisorType(), network));
            profile.addNic(nicProfile);
        }
        final VirtualMachineTO to = toVmTO(profile);
        return to;
    }

    public Command cleanup(final VirtualMachine vm, Map<String, DpdkTO> dpdkInterfaceMapping) {
        StopCommand cmd = new StopCommand(vm, getExecuteInSequence(vm.getHypervisorType()), false);
        cmd.setControlIp(getControlNicIpForVM(vm));
        if (MapUtils.isNotEmpty(dpdkInterfaceMapping)) {
            cmd.setDpdkInterfaceMapping(dpdkInterfaceMapping);
        }
        Map<String, Boolean> vlanToPersistenceMap = getVlanToPersistenceMapForVM(vm.getId());
        if (MapUtils.isNotEmpty(vlanToPersistenceMap)) {
            cmd.setVlanToPersistenceMap(vlanToPersistenceMap);
        }
        return cmd;
    }

    private String getControlNicIpForVM(VirtualMachine vm) {
        if (null == vm.getType()) {
            return null;
        }

        switch (vm.getType()) {
            case ConsoleProxy:
            case SecondaryStorageVm:
                NicVO nic = _nicsDao.getControlNicForVM(vm.getId());
                return nic.getIPv4Address();
            case DomainRouter:
                return vm.getPrivateIpAddress();
            default:
                logger.debug("{} is a [{}], returning null for control Nic IP.", vm.toString(), vm.getType());
                return null;
        }
    }
    public Command cleanup(final String vmName) {
        VirtualMachine vm = _vmDao.findVMByInstanceName(vmName);

        StopCommand cmd = new StopCommand(vmName, getExecuteInSequence(null), false);
        cmd.setControlIp(getControlNicIpForVM(vm));
        Map<String, Boolean> vlanToPersistenceMap = getVlanToPersistenceMapForVM(vm.getId());
        if (MapUtils.isNotEmpty(vlanToPersistenceMap)) {
            cmd.setVlanToPersistenceMap(vlanToPersistenceMap);
        }
        return cmd;
    }

    public void syncVMMetaData(final Map<String, String> vmMetadatum) {
        if (vmMetadatum == null || vmMetadatum.isEmpty()) {
            return;
        }
        List<Pair<Pair<String, VirtualMachine.Type>, Pair<Long, String>>> vmDetails = _userVmDao.getVmsDetailByNames(vmMetadatum.keySet(), "platform");
        for (final Map.Entry<String, String> entry : vmMetadatum.entrySet()) {
            final String name = entry.getKey();
            final String platform = entry.getValue();
            if (platform == null || platform.isEmpty()) {
                continue;
            }

            boolean found = false;
            for(Pair<Pair<String, VirtualMachine.Type>, Pair<Long, String>> vmDetail : vmDetails ) {
                Pair<String, VirtualMachine.Type> vmNameTypePair = vmDetail.first();
                if(vmNameTypePair.first().equals(name)) {
                    found = true;
                    if(vmNameTypePair.second() == VirtualMachine.Type.User) {
                        Pair<Long, String> detailPair = vmDetail.second();
                        String platformDetail = detailPair.second();

                        if (platformDetail != null && platformDetail.equals(platform)) {
                            break;
                        }
                        updateVmMetaData(detailPair.first(), platform);
                    }
                    break;
                }
            }

            if(!found) {
                VMInstanceVO vm = _vmDao.findVMByInstanceName(name);
                if(vm != null && vm.getType() == VirtualMachine.Type.User) {
                    updateVmMetaData(vm.getId(), platform);
                }
            }
        }
    }

    private void updateVmMetaData(Long vmId, String platform) {
        UserVmVO userVm = _userVmDao.findById(vmId);
        _userVmDao.loadDetails(userVm);
        if ( userVm.details.containsKey(VmDetailConstants.TIME_OFFSET)) {
            userVm.details.remove(VmDetailConstants.TIME_OFFSET);
        }
        userVm.setDetail(VmDetailConstants.PLATFORM,  platform);
        String pvdriver = "xenserver56";
        if ( platform.contains("device_id")) {
            pvdriver = "xenserver61";
        }
        if (!userVm.details.containsKey(VmDetailConstants.HYPERVISOR_TOOLS_VERSION) || !userVm.details.get(VmDetailConstants.HYPERVISOR_TOOLS_VERSION).equals(pvdriver)) {
            userVm.setDetail(VmDetailConstants.HYPERVISOR_TOOLS_VERSION, pvdriver);
        }
        _userVmDao.saveDetails(userVm);
    }

    @Override
    public boolean isRecurring() {
        return true;
    }

    @Override
    public boolean processAnswers(final long agentId, final long seq, final Answer[] answers) {
        for (final Answer answer : answers) {
            if ( answer instanceof ClusterVMMetaDataSyncAnswer) {
                final ClusterVMMetaDataSyncAnswer cvms = (ClusterVMMetaDataSyncAnswer)answer;
                if (!cvms.isExecuted()) {
                    syncVMMetaData(cvms.getVMMetaDatum());
                    cvms.setExecuted();
                }
            }
        }
        return true;
    }

    @Override
    public boolean processTimeout(final long agentId, final long seq) {
        return true;
    }

    @Override
    public int getTimeout() {
        return -1;
    }

    @Override
    public boolean processCommands(final long agentId, final long seq, final Command[] cmds) {
        boolean processed = false;
        for (final Command cmd : cmds) {
            if (cmd instanceof PingRoutingCommand) {
                final PingRoutingCommand ping = (PingRoutingCommand)cmd;
                if (ping.getHostVmStateReport() != null) {
                    _syncMgr.processHostVmStatePingReport(agentId, ping.getHostVmStateReport(), ping.getOutOfBand());
                }
                scanStalledVMInTransitionStateOnUpHost(agentId);
                processed = true;
            }
        }
        return processed;
    }

    @Override
    public AgentControlAnswer processControlCommand(final long agentId, final AgentControlCommand cmd) {
        return null;
    }

    @Override
    public boolean processDisconnect(final long agentId, final Status state) {
        return true;
    }

    @Override
    public void processHostAboutToBeRemoved(long hostId) {
    }

    @Override
    public void processHostRemoved(long hostId, long clusterId) {
    }

    @Override
    public void processHostAdded(long hostId) {
    }

    @Override
    public void processConnect(final Host agent, final StartupCommand cmd, final boolean forRebalance) throws ConnectionException {
        if (!(cmd instanceof StartupRoutingCommand)) {
            return;
        }

        logger.debug("Received startup command from hypervisor host. host: {}", agent);

        _syncMgr.resetHostSyncState(agent);

        if (forRebalance) {
            logger.debug("Not processing listener {} as connect happens on rebalance process", this);
            return;
        }
        final Long clusterId = agent.getClusterId();
        final long agentId = agent.getId();

        if (agent.getHypervisorType() == HypervisorType.XenServer) {
            final ClusterVMMetaDataSyncCommand syncVMMetaDataCmd = new ClusterVMMetaDataSyncCommand(ClusterVMMetaDataSyncInterval.value(), clusterId);
            try {
                final long seq_no = _agentMgr.send(agentId, new Commands(syncVMMetaDataCmd), this);
                logger.debug("Cluster VM metadata sync started with jobid {}", seq_no);
            } catch (final AgentUnavailableException e) {
                logger.fatal("The Cluster VM metadata sync process failed for cluster {} with {}", _clusterDao.findById(clusterId), e);
            }
        }
    }

    protected class TransitionTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            final GlobalLock lock = GlobalLock.getInternLock("TransitionChecking");
            if (lock == null) {
                logger.debug("Couldn't get the global lock");
                return;
            }

            if (!lock.lock(30)) {
                logger.debug("Couldn't lock the db");
                return;
            }
            try {
                scanStalledVMInTransitionStateOnDisconnectedHosts();

                final List<VMInstanceVO> instances = _vmDao.findVMInTransition(new Date(DateUtil.currentGMTTime().getTime() - AgentManager.Wait.value() * 1000), State.Starting, State.Stopping);
                for (final VMInstanceVO instance : instances) {
                    final State state = instance.getState();
                    if (state == State.Stopping) {
                        _haMgr.scheduleStop(instance, instance.getHostId(), WorkType.CheckStop);
                    } else if (state == State.Starting) {
                        _haMgr.scheduleRestart(instance, true);
                    }
                }
            } catch (final Exception e) {
                logger.warn("Caught the following exception on transition checking", e);
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public VMInstanceVO findById(final long vmId) {
        return _vmDao.findById(vmId);
    }

    @Override
    public void checkIfCanUpgrade(final VirtualMachine vmInstance, final ServiceOffering newServiceOffering) {
        if (newServiceOffering == null) {
            throw new InvalidParameterValueException("Invalid parameter, newServiceOffering can't be null");
        }

        if (ServiceOffering.State.Inactive.equals(newServiceOffering.getState())) {
            throw new InvalidParameterValueException(String.format("New service offering is inactive: [%s].", newServiceOffering.getUuid()));
        }

        if (!(vmInstance.getState().equals(State.Stopped) || vmInstance.getState().equals(State.Running))) {
            logger.warn("Unable to upgrade virtual machine {} in state {}", vmInstance.toString(), vmInstance.getState());
            throw new InvalidParameterValueException("Unable to upgrade virtual machine " + vmInstance.toString() + " " + " in state " + vmInstance.getState() +
                    "; make sure the virtual machine is stopped/running");
        }

        if (!newServiceOffering.isDynamic() && vmInstance.getServiceOfferingId() == newServiceOffering.getId()) {
            logger.info("Not upgrading vm {} since it already has the requested service offering ({})", vmInstance.toString(), newServiceOffering.getName());

            throw new InvalidParameterValueException("Not upgrading vm " + vmInstance.toString() + " since it already " + "has the requested service offering (" +
                    newServiceOffering.getName() + ")");
        }

        final ServiceOfferingVO currentServiceOffering = _offeringDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        final DiskOfferingVO currentDiskOffering = _diskOfferingDao.findByIdIncludingRemoved(currentServiceOffering.getDiskOfferingId());
        final DiskOfferingVO newDiskOffering = _diskOfferingDao.findById(newServiceOffering.getDiskOfferingId());

        checkIfNewOfferingStorageScopeMatchesStoragePool(vmInstance, newDiskOffering);

        if (currentServiceOffering.isSystemUse() != newServiceOffering.isSystemUse()) {
            throw new InvalidParameterValueException("isSystem property is different for current service offering and new service offering");
        }

        if (!isVirtualMachineUpgradable(vmInstance, newServiceOffering)) {
            throw new InvalidParameterValueException("Unable to upgrade virtual machine, not enough resources available " + "for an offering of " +
                    newServiceOffering.getCpu() + " cpu(s) at " + newServiceOffering.getSpeed() + " Mhz, and " + newServiceOffering.getRamSize() + " MB of memory");
        }

        final List<String> currentTags = StringUtils.csvTagsToList(currentDiskOffering.getTags());
        final List<String> newTags = StringUtils.csvTagsToList(newDiskOffering.getTags());
        if (VolumeApiServiceImpl.MatchStoragePoolTagsWithDiskOffering.valueIn(vmInstance.getDataCenterId())) {
            if (!VolumeApiServiceImpl.doesNewDiskOfferingHasTagsAsOldDiskOffering(currentDiskOffering, newDiskOffering)) {
                    throw new InvalidParameterValueException("Unable to upgrade virtual machine; the current service offering " + " should have tags as subset of " +
                            "the new service offering tags. Current service offering tags: " + currentTags + "; " + "new service " + "offering tags: " + newTags);
            }
        }
    }

    /**
     * Throws an InvalidParameterValueException in case the new service offerings does not match the storage scope (e.g. local or shared).
     */
    protected void checkIfNewOfferingStorageScopeMatchesStoragePool(VirtualMachine vmInstance, DiskOffering newDiskOffering) {
        boolean isRootVolumeOnLocalStorage = isRootVolumeOnLocalStorage(vmInstance.getId());

        if (newDiskOffering.isUseLocalStorage() && !isRootVolumeOnLocalStorage) {
            String message = String .format("Unable to upgrade virtual machine %s, target offering use local storage but the storage pool where "
                    + "the volume is allocated is a shared storage.", vmInstance.toString());
            throw new InvalidParameterValueException(message);
        }

        if (!newDiskOffering.isUseLocalStorage() && isRootVolumeOnLocalStorage) {
            String message = String.format("Unable to upgrade virtual machine %s, target offering use shared storage but the storage pool where "
                    + "the volume is allocated is a local storage.", vmInstance.toString());
            throw new InvalidParameterValueException(message);
        }
    }

    public boolean isRootVolumeOnLocalStorage(long vmId) {
        ScopeType poolScope = ScopeType.ZONE;
        List<VolumeVO> volumes = _volsDao.findByInstanceAndType(vmId, Type.ROOT);
        if(CollectionUtils.isNotEmpty(volumes)) {
            VolumeVO rootDisk = volumes.get(0);
            Long poolId = rootDisk.getPoolId();
            if (poolId != null) {
                StoragePoolVO storagePoolVO = _storagePoolDao.findById(poolId);
                poolScope = storagePoolVO.getScope();
            }
        }
        return ScopeType.HOST == poolScope;
    }

    @Override
    public boolean upgradeVmDb(final long vmId, final ServiceOffering newServiceOffering, ServiceOffering currentServiceOffering) {

        final VMInstanceVO vmForUpdate = _vmDao.findById(vmId);
        vmForUpdate.setServiceOfferingId(newServiceOffering.getId());
        final ServiceOffering newSvcOff = _entityMgr.findById(ServiceOffering.class, newServiceOffering.getId());
        vmForUpdate.setHaEnabled(newSvcOff.isOfferHA());
        vmForUpdate.setLimitCpuUse(newSvcOff.getLimitCpuUse());
        vmForUpdate.setServiceOfferingId(newSvcOff.getId());
        if (newServiceOffering.isDynamic()) {
            saveCustomOfferingDetails(vmId, newServiceOffering);
        }
        if (currentServiceOffering.isDynamic() && !newServiceOffering.isDynamic()) {
            removeCustomOfferingDetails(vmId);
        }
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmForUpdate.getTemplateId());
        boolean dynamicScalingEnabled = _userVmMgr.checkIfDynamicScalingCanBeEnabled(vmForUpdate, newServiceOffering, template, vmForUpdate.getDataCenterId());
        vmForUpdate.setDynamicallyScalable(dynamicScalingEnabled);
        return _vmDao.update(vmId, vmForUpdate);
    }

    @Override
    public NicProfile addVmToNetwork(final VirtualMachine vm, final Network network, final NicProfile requested)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId(), network.getUuid());
            try {
                return orchestrateAddVmToNetwork(vm, network, requested);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            final Outcome<VirtualMachine> outcome = addVmToNetworkThroughJobQueue(vm, network, requested);

            retrieveVmFromJobOutcome(outcome, vm.getUuid(), "addVmToNetwork");

            Object jobResult = retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);

            if (jobResult != null && jobResult instanceof NicProfile) {
                return (NicProfile) jobResult;
            }

            throw new RuntimeException("null job execution result");
        }
    }

    /**
     * duplicated in {@see UserVmManagerImpl} for a {@see UserVmVO}
     */
    private void checkIfNetworkExistsForUserVM(VirtualMachine virtualMachine, Network network) {
        if (virtualMachine.getType() != VirtualMachine.Type.User) {
            return; // others may have multiple nics in the same network
        }
        List<NicVO> allNics = _nicsDao.listByVmId(virtualMachine.getId());
        for (NicVO nic : allNics) {
            if (nic.getNetworkId() == network.getId()) {
                throw new CloudRuntimeException("A NIC already exists for VM:" + virtualMachine.getInstanceName() + " in network: " + network.getUuid());
            }
        }
    }

    private NicProfile orchestrateAddVmToNetwork(final VirtualMachine vm, final Network network, final NicProfile requested) throws ConcurrentOperationException, ResourceUnavailableException,
    InsufficientCapacityException {
        final CallContext cctx = CallContext.current();

        checkIfNetworkExistsForUserVM(vm, network);
        logger.debug("Adding vm {} to network {}; requested nic profile {}", vm, network, requested);
        final VMInstanceVO vmVO = _vmDao.findById(vm.getId());
        final ReservationContext context = new ReservationContextImpl(null, null, cctx.getCallingUser(), cctx.getCallingAccount());

        final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmVO, null, null, null, null);

        final DataCenter dc = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        final Host host = _hostDao.findById(vm.getHostId());
        final DeployDestination dest = new DeployDestination(dc, null, null, host);

        if (vm.getState() == State.Running) {
            final NicProfile nic = _networkMgr.createNicForVm(network, requested, context, vmProfile, true);

            final HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vmProfile.getVirtualMachine().getHypervisorType());
            final VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

            final NicTO nicTO = toNicTO(nic, vmProfile.getVirtualMachine().getHypervisorType());

            //4) plug the nic to the vm
            logger.debug("Plugging nic for vm {} in network {}", vm, network);

            boolean result = false;
            try {
                result = plugNic(network, nicTO, vmTO, context, dest);
                if (result) {
                    _userVmMgr.setupVmForPvlan(true, vm.getHostId(), nic);
                    logger.debug("Nic is plugged successfully for vm {} in network {}. VM is a part of network now.", vm, network);
                    final long isDefault = nic.isDefaultNic() ? 1 : 0;

                    if(VirtualMachine.Type.User.equals(vmVO.getType())) {
                        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vmVO.getAccountId(), vmVO.getDataCenterId(), vmVO.getId(),
                                Long.toString(nic.getId()), network.getNetworkOfferingId(), null, isDefault, VirtualMachine.class.getName(), vmVO.getUuid(), vm.isDisplay());
                    }
                    return nic;
                } else {
                    logger.warn("Failed to plug nic to the vm {} in network {}", vm, network);
                    return null;
                }
            } finally {
                if (!result) {
                    logger.debug("Removing nic {} from vm {} as nic plug failed on the backend.", nic, vmProfile.getVirtualMachine());
                    _networkMgr.removeNic(vmProfile, _nicsDao.findById(nic.getId()));
                }
            }
        } else if (vm.getState() == State.Stopped) {
            return _networkMgr.createNicForVm(network, requested, context, vmProfile, false);
        } else {
            logger.warn("Unable to add vm {} to network {}", vm, network);
            throw new ResourceUnavailableException("Unable to add vm " + vm + " to network, is not in the right state", DataCenter.class, vm.getDataCenterId());
        }
    }

    @Override
    public NicTO toNicTO(final NicProfile nic, final HypervisorType hypervisorType) {
        final HypervisorGuru hvGuru = _hvGuruMgr.getGuru(hypervisorType);
        return hvGuru.toNicTO(nic);
    }

    @Override
    public boolean removeNicFromVm(final VirtualMachine vm, final Nic nic)
            throws ConcurrentOperationException, ResourceUnavailableException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                return orchestrateRemoveNicFromVm(vm, nic);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }

        } else {
            final Outcome<VirtualMachine> outcome = removeNicFromVmThroughJobQueue(vm, nic);

            retrieveVmFromJobOutcome(outcome, vm.getUuid(), "removeNicFromVm");

            try {
                Object jobResult = retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
                if (jobResult != null && jobResult instanceof Boolean) {
                    return (Boolean) jobResult;
                }
            } catch (InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }

            throw new RuntimeException("Job failed with un-handled exception");
        }
    }

    private boolean orchestrateRemoveNicFromVm(final VirtualMachine vm, final Nic nic) throws ConcurrentOperationException, ResourceUnavailableException {
        final CallContext cctx = CallContext.current();
        final VMInstanceVO vmVO = _vmDao.findById(vm.getId());
        final NetworkVO network = _networkDao.findById(nic.getNetworkId());
        final ReservationContext context = new ReservationContextImpl(null, null, cctx.getCallingUser(), cctx.getCallingAccount());

        final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmVO, null, null, null, null);

        final DataCenter dc = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        final Host host = _hostDao.findById(vm.getHostId());
        final DeployDestination dest = new DeployDestination(dc, null, null, host);
        final HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vmProfile.getVirtualMachine().getHypervisorType());
        final VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

        final NicProfile nicProfile =
                new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), _networkModel.getNetworkRate(network.getId(), vm.getId()),
                        _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vmProfile.getVirtualMachine().getHypervisorType(), network));

        if (vm.getState() == State.Running) {
            final NicTO nicTO = toNicTO(nicProfile, vmProfile.getVirtualMachine().getHypervisorType());
            logger.debug("Un-plugging nic {} for vm {} from network {}.", nic, vm, network);
            final boolean result = unplugNic(network, nicTO, vmTO, context, dest);
            if (result) {
                _userVmMgr.setupVmForPvlan(false, vm.getHostId(), nicProfile);
                logger.debug("Nic is unplugged successfully for vm {} in network {}.", vm, network);
                final long isDefault = nic.isDefaultNic() ? 1 : 0;
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                        Long.toString(nic.getId()), network.getNetworkOfferingId(), null, isDefault, VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
            } else {
                logger.warn("Failed to unplug nic for the vm {} from network {}.", vm, network);
                return false;
            }
        } else if (vm.getState() != State.Stopped) {
            logger.warn("Unable to remove vm {} from network {}", vm, network);
            throw new ResourceUnavailableException("Unable to remove vm " + vm + " from network, is not in the right state", DataCenter.class, vm.getDataCenterId());
        }

        _networkMgr.releaseNic(vmProfile, nic);
        logger.debug("Successfully released nic {} for vm {}", nic, vm);

        _networkMgr.removeNic(vmProfile, nic);
        _nicsDao.remove(nic.getId());
        return true;
    }

    @Override
    @DB
    public boolean removeVmFromNetwork(final VirtualMachine vm, final Network network, final URI broadcastUri) throws ConcurrentOperationException, ResourceUnavailableException {
        return orchestrateRemoveVmFromNetwork(vm, network, broadcastUri);
    }

    @DB
    private boolean orchestrateRemoveVmFromNetwork(final VirtualMachine vm, final Network network, final URI broadcastUri) throws ConcurrentOperationException, ResourceUnavailableException {
        final CallContext cctx = CallContext.current();
        final VMInstanceVO vmVO = _vmDao.findById(vm.getId());
        final ReservationContext context = new ReservationContextImpl(null, null, cctx.getCallingUser(), cctx.getCallingAccount());

        final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmVO, null, null, null, null);

        final DataCenter dc = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        final Host host = _hostDao.findById(vm.getHostId());
        final DeployDestination dest = new DeployDestination(dc, null, null, host);
        final HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vmProfile.getVirtualMachine().getHypervisorType());
        final VirtualMachineTO vmTO = hvGuru.implement(vmProfile);

        Nic nic = null;
        if (broadcastUri != null) {
            nic = _nicsDao.findByNetworkIdInstanceIdAndBroadcastUri(network.getId(), vm.getId(), broadcastUri.toString());
        } else {
            nic = _networkModel.getNicInNetwork(vm.getId(), network.getId());
        }

        if (nic == null) {
            logger.warn("Could not get a nic with {}", network);
            return false;
        }

        if (nic.isDefaultNic() && vm.getType() == VirtualMachine.Type.User) {
            logger.warn("Failed to remove nic from {} in {}, nic is default.", vm, network);
            throw new CloudRuntimeException("Failed to remove nic from " + vm + " in " + network + ", nic is default.");
        }

        final Nic lock = _nicsDao.acquireInLockTable(nic.getId());
        if (lock == null) {
            if (_nicsDao.findById(nic.getId()) == null) {
                logger.debug("Not need to remove the vm {} from network {} as the vm doesn't have nic in this network.", vm, network);
                return true;
            }
            throw new ConcurrentOperationException(String.format("Unable to lock nic %s", nic));
        }

        logger.debug("Lock is acquired for nic {} as a part of remove vm {} from network {}", lock, vm, network);

        try {
            final NicProfile nicProfile =
                    new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), _networkModel.getNetworkRate(network.getId(), vm.getId()),
                            _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(vmProfile.getVirtualMachine().getHypervisorType(), network));

            if (vm.getState() == State.Running) {
                final NicTO nicTO = toNicTO(nicProfile, vmProfile.getVirtualMachine().getHypervisorType());
                logger.debug("Un-plugging nic for vm {} from network {}", vm, network);
                final boolean result = unplugNic(network, nicTO, vmTO, context, dest);
                if (result) {
                    logger.debug("Nic is unplugged successfully for vm {} in network {}", vm, network);
                } else {
                    logger.warn("Failed to unplug nic for the vm {} from network {}", vm, network);
                    return false;
                }
            } else if (vm.getState() != State.Stopped) {
                logger.warn("Unable to remove vm {} from network {}", vm, network);
                throw new ResourceUnavailableException("Unable to remove vm " + vm + " from network, is not in the right state", DataCenter.class, vm.getDataCenterId());
            }

            _networkMgr.releaseNic(vmProfile, nic);
            logger.debug("Successfully released nic {} for vm {}", nic, vm);

            _networkMgr.removeNic(vmProfile, nic);
            return true;
        } finally {
            _nicsDao.releaseFromLockTable(lock.getId());
            logger.debug("Lock is released for nic {} as a part of remove vm {} from network {}", lock, vm, network);
        }
    }

    @Override
    public void findHostAndMigrate(final String vmUuid, final Long newSvcOfferingId, final Map<String, String> customParameters, final ExcludeList excludes) throws InsufficientCapacityException, ConcurrentOperationException,
    ResourceUnavailableException {

        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        if (vm == null) {
            throw new CloudRuntimeException("Unable to find " + vmUuid);
        }
        ServiceOfferingVO newServiceOffering = _offeringDao.findById(newSvcOfferingId);
        if (newServiceOffering.isDynamic()) {
            newServiceOffering.setDynamicFlag(true);
            newServiceOffering = _offeringDao.getComputeOffering(newServiceOffering, customParameters);
        }
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, newServiceOffering, null, null);

        final Long srcHostId = vm.getHostId();
        final Long oldSvcOfferingId = vm.getServiceOfferingId();
        if (srcHostId == null) {
            throw new CloudRuntimeException("Unable to scale the vm because it doesn't have a host id");
        }
        final Host host = _hostDao.findById(srcHostId);
        final DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), null, null, null);
        excludes.addHost(vm.getHostId());
        vm.setServiceOfferingId(newSvcOfferingId);

        DeployDestination dest = null;

        try {
            dest = _dpMgr.planDeployment(profile, plan, excludes, null);
        } catch (final AffinityConflictException e2) {
            String message = String.format("Unable to create deployment, affinity rules associated to the %s conflict.", vm.toString());
            logger.warn(message, e2);
            throw new CloudRuntimeException(message);
        }

        if (dest != null) {
            logger.debug("Found {} for scaling the vm to.", dest);
        }

        if (dest == null) {
            throw new InsufficientServerCapacityException("Unable to find a server to scale the vm to.", host.getClusterId());
        }

        excludes.addHost(dest.getHost().getId());
        try {
            migrateForScale(vm.getUuid(), srcHostId, dest, oldSvcOfferingId);
        } catch (ResourceUnavailableException | ConcurrentOperationException e) {
            logger.warn("Unable to migrate {} to {} due to [{}]", vm.toString(), dest.getHost().toString(), e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void migrateForScale(final String vmUuid, final long srcHostId, final DeployDestination dest, final Long oldSvcOfferingId)
            throws ResourceUnavailableException, ConcurrentOperationException {
        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                orchestrateMigrateForScale(vmUuid, srcHostId, dest, oldSvcOfferingId);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            final Outcome<VirtualMachine> outcome = migrateVmForScaleThroughJobQueue(vmUuid, srcHostId, dest, oldSvcOfferingId);

            retrieveVmFromJobOutcome(outcome, vmUuid, "migrateVmForScale");

            try {
                retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }
        }
    }

    private void orchestrateMigrateForScale(final String vmUuid, final long srcHostId, final DeployDestination dest, final Long oldSvcOfferingId)
            throws ResourceUnavailableException, ConcurrentOperationException {

        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        logger.info("Migrating {} to {}", vm, dest);

        vm.getServiceOfferingId();
        final long dstHostId = dest.getHost().getId();
        final Host fromHost = _hostDao.findById(srcHostId);
        if (fromHost == null) {
            String logMessageUnableToFindHost = String.format("Unable to find host to migrate from %s.", srcHostId);
            logger.info(logMessageUnableToFindHost);
            throw new CloudRuntimeException(logMessageUnableToFindHost);
        }

        Host dstHost = _hostDao.findById(dstHostId);
        long destHostClusterId = dest.getCluster().getId();
        long fromHostClusterId = fromHost.getClusterId();
        if (fromHostClusterId != destHostClusterId) {
            String logMessageHostsOnDifferentCluster = String.format("Source and destination host are not in same cluster, unable to migrate to %s", fromHost);
            logger.info(logMessageHostsOnDifferentCluster);
            throw new CloudRuntimeException(logMessageHostsOnDifferentCluster);
        }

        final VirtualMachineGuru vmGuru = getVmGuru(vm);

        vm = _vmDao.findByUuid(vmUuid);
        if (vm == null) {
            String message = String.format("Unable to find VM {\"uuid\": \"%s\"}.", vmUuid);
            logger.warn(message);
            throw new CloudRuntimeException(message);
        }

        if (vm.getState() != State.Running) {
            String message = String.format("%s is not in \"Running\" state, unable to migrate it. Current state [%s].", vm.toString(), vm.getState());
            logger.warn(message);
            throw new CloudRuntimeException(message);
        }

        AlertManager.AlertType alertType = AlertManager.AlertType.ALERT_TYPE_USERVM_MIGRATE;
        if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER_MIGRATE;
        } else if (VirtualMachine.Type.ConsoleProxy.equals(vm.getType())) {
            alertType = AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY_MIGRATE;
        }

        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        _networkMgr.prepareNicForMigration(profile, dest);

        volumeMgr.prepareForMigration(profile, dest);

        final VirtualMachineTO to = toVmTO(profile);
        final PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(to);

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Migrating, vm.getType(), vm.getId());
        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(dstHostId);
        work = _workDao.persist(work);

        Answer pfma = null;
        try {
            pfma = _agentMgr.send(dstHostId, pfmc);
            if (pfma == null || !pfma.getResult()) {
                final String details = pfma != null ? pfma.getDetails() : "null answer returned";
                pfma = null;
                throw new AgentUnavailableException(String.format("Unable to prepare for migration to destination host [%s] due to [%s].", dest.getHost(), details), dstHostId);
            }
        } catch (final OperationTimedoutException e1) {
            throw new AgentUnavailableException("Operation timed out", dstHostId);
        } finally {
            if (pfma == null) {
                work.setStep(Step.Done);
                _workDao.update(work.getId(), work);
            }
        }

        vm.setLastHostId(srcHostId);
        try {
            if (vm.getHostId() == null || vm.getHostId() != srcHostId || !changeState(vm, Event.MigrationRequested, dstHostId, work, Step.Migrating)) {
                String message = String.format("Migration of %s cancelled because state has changed.", vm.toString());
                logger.warn(message);
                throw new ConcurrentOperationException(message);
            }
        } catch (final NoTransitionException e1) {
            String message = String.format("Migration of %s cancelled due to [%s].", vm.toString(), e1.getMessage());
            logger.error(message, e1);
            throw new ConcurrentOperationException(message);
        }

        boolean migrated = false;
        try {
            final MigrateCommand mc = buildMigrateCommand(vm, to, dest, pfma, null);

            try {
                final Answer ma = _agentMgr.send(vm.getLastHostId(), mc);
                if (ma == null || !ma.getResult()) {
                    String msg = String.format("Unable to migrate %s due to [%s].", vm.toString(), ma != null ? ma.getDetails() : "null answer returned");
                    logger.error(msg);
                    throw new CloudRuntimeException(msg);
                }
            } catch (final OperationTimedoutException e) {
                if (e.isActive()) {
                    logger.warn("Active migration command so scheduling a restart for {}", vm, e);
                    _haMgr.scheduleRestart(vm, true);
                }
                throw new AgentUnavailableException("Operation timed out on migrating " + vm, dstHostId, e);
            }

            try {
                final long newServiceOfferingId = vm.getServiceOfferingId();
                vm.setServiceOfferingId(oldSvcOfferingId);
                if (!changeState(vm, VirtualMachine.Event.OperationSucceeded, dstHostId, work, Step.Started)) {
                    throw new ConcurrentOperationException("Unable to change the state for " + vm);
                }
                vm.setServiceOfferingId(newServiceOfferingId);
            } catch (final NoTransitionException e1) {
                throw new ConcurrentOperationException("Unable to change state due to " + e1.getMessage());
            }

            try {
                if (!checkVmOnHost(vm, dstHostId)) {
                    logger.error("Unable to complete migration for {}", vm);
                    try {
                        _agentMgr.send(srcHostId, new Commands(cleanup(vm.getInstanceName())), null);
                    } catch (final AgentUnavailableException e) {
                        logger.error("Unable to cleanup source host [{}] due to [{}].", fromHost, e.getMessage(), e);
                    }
                    cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.AgentReportStopped, true);
                    throw new CloudRuntimeException("Unable to complete migration for " + vm);
                }
            } catch (final OperationTimedoutException e) {
                logger.debug("Error while checking the {} on {}", vm, dstHost, e);
            }

            migrated = true;
        } finally {
            if (!migrated) {
                logger.info("Migration was unsuccessful.  Cleaning up: {}", vm);

                String alertSubject = String.format("Unable to migrate %s from %s in Zone [%s] and Pod [%s].",
                        vm.getInstanceName(), fromHost, dest.getDataCenter().getName(), dest.getPod().getName());
                String alertBody = "Migrate Command failed. Please check logs.";
                _alertMgr.sendAlert(alertType, fromHost.getDataCenterId(), fromHost.getPodId(), alertSubject, alertBody);
                try {
                    _agentMgr.send(dstHostId, new Commands(cleanup(vm.getInstanceName())), null);
                } catch (final AgentUnavailableException ae) {
                    logger.info("Looks like the destination Host is unavailable for cleanup");
                }
                _networkMgr.setHypervisorHostname(profile, dest, false);
                try {
                    stateTransitTo(vm, Event.OperationFailed, srcHostId);
                } catch (final NoTransitionException e) {
                    logger.warn(e.getMessage(), e);
                }
            } else {
                _networkMgr.setHypervisorHostname(profile, dest, true);

                updateVmPod(vm, dstHostId);
            }

            work.setStep(Step.Done);
            _workDao.update(work.getId(), work);
        }
    }

    @Override
    public boolean replugNic(final Network network, final NicTO nic, final VirtualMachineTO vm, final Host host) throws ConcurrentOperationException,
    ResourceUnavailableException, InsufficientCapacityException {
        boolean result = true;

        final VMInstanceVO router = _vmDao.findById(vm.getId());
        if (router.getState() == State.Running) {
            try {
                final ReplugNicCommand replugNicCmd = new ReplugNicCommand(nic, vm.getName(), vm.getType(), vm.getDetails());
                final Commands cmds = new Commands(Command.OnError.Stop);
                cmds.addCommand("replugnic", replugNicCmd);
                _agentMgr.send(host.getId(), cmds);
                final ReplugNicAnswer replugNicAnswer = cmds.getAnswer(ReplugNicAnswer.class);
                if (replugNicAnswer == null || !replugNicAnswer.getResult()) {
                    logger.warn("Unable to replug nic for vm {}", vm.getName());
                    result = false;
                }
            } catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException("Unable to plug nic for router " + vm.getName() + " in network " + network, host.getId(), e);
            }
        } else {
            String message = String.format("Unable to apply ReplugNic, VM [%s] is not in the right state (\"Running\"). VM state [%s].", router.toString(), router.getState());
            logger.warn(message);

            throw new ResourceUnavailableException(message, DataCenter.class, router.getDataCenterId());
        }

        return result;
    }

    public boolean plugNic(final Network network, final NicTO nic, final VirtualMachineTO vm, final ReservationContext context, final DeployDestination dest) throws ConcurrentOperationException,
    ResourceUnavailableException, InsufficientCapacityException {
        boolean result = true;

        final VMInstanceVO router = _vmDao.findById(vm.getId());
        if (router.getState() == State.Running) {
            try {
                NetworkDetailVO pvlanTypeDetail = networkDetailsDao.findDetail(network.getId(), ApiConstants.ISOLATED_PVLAN_TYPE);
                if (pvlanTypeDetail != null) {
                    Map<NetworkOffering.Detail, String> nicDetails = nic.getDetails() == null ? new HashMap<>() : nic.getDetails();
                    logger.debug("Found PVLAN type: {} on network details, adding it as part of the PlugNicCommand", pvlanTypeDetail.getValue());
                    nicDetails.putIfAbsent(NetworkOffering.Detail.pvlanType, pvlanTypeDetail.getValue());
                    nic.setDetails(nicDetails);
                }
                final PlugNicCommand plugNicCmd = new PlugNicCommand(nic, vm.getName(), vm.getType(), vm.getDetails());
                final Commands cmds = new Commands(Command.OnError.Stop);
                cmds.addCommand("plugnic", plugNicCmd);
                _agentMgr.send(dest.getHost().getId(), cmds);
                final PlugNicAnswer plugNicAnswer = cmds.getAnswer(PlugNicAnswer.class);
                if (plugNicAnswer == null || !plugNicAnswer.getResult()) {
                    logger.warn("Unable to plug nic for vm {}", vm.getName());
                    result = false;
                }
            } catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException("Unable to plug nic for router " + vm.getName() + " in network " + network, dest.getHost().getId(), e);
            }
        } else {
            String message = String.format("Unable to apply PlugNic, VM [%s] is not in the right state (\"Running\"). VM state [%s].", router.toString(), router.getState());
            logger.warn(message);

            throw new ResourceUnavailableException(message, DataCenter.class,
                    router.getDataCenterId());
        }

        return result;
    }

    public boolean unplugNic(final Network network, final NicTO nic, final VirtualMachineTO vm, final ReservationContext context, final DeployDestination dest) throws ConcurrentOperationException,
    ResourceUnavailableException {

        boolean result = true;
        final VMInstanceVO router = _vmDao.findById(vm.getId());

        if (router.getState() == State.Running) {
            UserVmVO userVm = _userVmDao.findById(vm.getId());
            if (userVm != null && userVm.getType() == VirtualMachine.Type.User) {
                _userVmService.collectVmNetworkStatistics(userVm);
            }
            try {
                final Commands cmds = new Commands(Command.OnError.Stop);
                final UnPlugNicCommand unplugNicCmd = new UnPlugNicCommand(nic, vm.getName());
                Map<String, Boolean> vlanToPersistenceMap = getVlanToPersistenceMapForVM(vm.getId());
                if (MapUtils.isNotEmpty(vlanToPersistenceMap)) {
                    unplugNicCmd.setVlanToPersistenceMap(vlanToPersistenceMap);
                }
                cmds.addCommand("unplugnic", unplugNicCmd);
                _agentMgr.send(dest.getHost().getId(), cmds);

                final UnPlugNicAnswer unplugNicAnswer = cmds.getAnswer(UnPlugNicAnswer.class);
                if (unplugNicAnswer == null || !unplugNicAnswer.getResult()) {
                    logger.warn("Unable to unplug nic from router {}", router);
                    result = false;
                }
            } catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException("Unable to unplug nic from rotuer " + router + " from network " + network, dest.getHost().getId(), e);
            }
        } else if (router.getState() == State.Stopped || router.getState() == State.Stopping) {
            logger.debug("Vm {} is in {}, so not sending unplug nic command to the backend", router.getInstanceName(), router.getState());
        } else {
            String message = String.format("Unable to apply unplug nic, VM [%s] is not in the right state (\"Running\"). VM state [%s].", router.toString(), router.getState());
            logger.warn(message);

            throw new ResourceUnavailableException(message, DataCenter.class, router.getDataCenterId());
        }

        return result;
    }

    @Override
    public VMInstanceVO reConfigureVm(final String vmUuid, final ServiceOffering oldServiceOffering, final ServiceOffering newServiceOffering,
            Map<String, String> customParameters, final boolean reconfiguringOnExistingHost)
                    throws ResourceUnavailableException, InsufficientServerCapacityException, ConcurrentOperationException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = _vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                return orchestrateReConfigureVm(vmUuid, oldServiceOffering, newServiceOffering, reconfiguringOnExistingHost);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            final Outcome<VirtualMachine> outcome = reconfigureVmThroughJobQueue(vmUuid, oldServiceOffering, newServiceOffering, customParameters, reconfiguringOnExistingHost);

            VirtualMachine vm = retrieveVmFromJobOutcome(outcome, vmUuid, "reconfigureVm");

            Object result = null;
            try {
                result = retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (Exception ex) {
                throw new RuntimeException("Unhandled exception", ex);
            }

            if (result != null) {
                throw new RuntimeException(String.format("Unexpected job execution result [%s]", result));
            }

            return (VMInstanceVO)vm;
        }
    }

    private VMInstanceVO orchestrateReConfigureVm(String vmUuid, ServiceOffering oldServiceOffering, ServiceOffering newServiceOffering,
                                                  boolean reconfiguringOnExistingHost) throws ResourceUnavailableException, ConcurrentOperationException {
        final VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

        HostVO hostVo = _hostDao.findById(vm.getHostId());

        Long clustedId = hostVo.getClusterId();
        Float memoryOvercommitRatio = CapacityManager.MemOverprovisioningFactor.valueIn(clustedId);
        Float cpuOvercommitRatio = CapacityManager.CpuOverprovisioningFactor.valueIn(clustedId);
        boolean divideMemoryByOverprovisioning = HypervisorGuruBase.VmMinMemoryEqualsMemoryDividedByMemOverprovisioningFactor.valueIn(clustedId);
        boolean divideCpuByOverprovisioning = HypervisorGuruBase.VmMinCpuSpeedEqualsCpuSpeedDividedByCpuOverprovisioningFactor.valueIn(clustedId);

        int minMemory = (int)(newServiceOffering.getRamSize() / (divideMemoryByOverprovisioning ? memoryOvercommitRatio : 1));
        int minSpeed = (int)(newServiceOffering.getSpeed() / (divideCpuByOverprovisioning ? cpuOvercommitRatio : 1));

        ScaleVmCommand scaleVmCommand =
                new ScaleVmCommand(vm.getInstanceName(), newServiceOffering.getCpu(), minSpeed,
                        newServiceOffering.getSpeed(), minMemory * 1024L * 1024L, newServiceOffering.getRamSize() * 1024L * 1024L, newServiceOffering.getLimitCpuUse());

        scaleVmCommand.getVirtualMachine().setId(vm.getId());
        scaleVmCommand.getVirtualMachine().setUuid(vm.getUuid());
        scaleVmCommand.getVirtualMachine().setType(vm.getType());

        Long dstHostId = vm.getHostId();

        if (vm.getHypervisorType().equals(HypervisorType.VMware)) {
            HypervisorGuru hvGuru = _hvGuruMgr.getGuru(vm.getHypervisorType());
            Map<String, String> details = hvGuru.getClusterSettings(vm.getId());
            scaleVmCommand.getVirtualMachine().setDetails(details);
        }

        ItWorkVO work = new ItWorkVO(UUID.randomUUID().toString(), _nodeId, State.Running, vm.getType(), vm.getId());

        work.setStep(Step.Prepare);
        work.setResourceType(ItWorkVO.ResourceType.Host);
        work.setResourceId(vm.getHostId());
        _workDao.persist(work);

        try {
            Answer reconfigureAnswer = _agentMgr.send(vm.getHostId(), scaleVmCommand);

            if (reconfigureAnswer == null || !reconfigureAnswer.getResult()) {
                logger.error("Unable to scale vm due to {}", (reconfigureAnswer == null ? "" : reconfigureAnswer.getDetails()));
                throw new CloudRuntimeException("Unable to scale vm due to " + (reconfigureAnswer == null ? "" : reconfigureAnswer.getDetails()));
            }

            upgradeVmDb(vm.getId(), newServiceOffering, oldServiceOffering);

            if (vm.getType().equals(VirtualMachine.Type.User)) {
                _userVmMgr.generateUsageEvent(vm, vm.isDisplayVm(), EventTypes.EVENT_VM_DYNAMIC_SCALE);
            }

            if (reconfiguringOnExistingHost) {
                vm.setServiceOfferingId(oldServiceOffering.getId());
                _capacityMgr.releaseVmCapacity(vm, false, false, vm.getHostId());
                vm.setServiceOfferingId(newServiceOffering.getId());
                _capacityMgr.allocateVmCapacity(vm, false);
            }

        } catch (final OperationTimedoutException e) {
            throw new AgentUnavailableException("Operation timed out on reconfiguring " + vm, dstHostId);
        } catch (final AgentUnavailableException e) {
            throw e;
        }

        return vm;

    }

    private void removeCustomOfferingDetails(long vmId) {
        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
        details.remove(UsageEventVO.DynamicParameters.cpuNumber.name());
        details.remove(UsageEventVO.DynamicParameters.cpuSpeed.name());
        details.remove(UsageEventVO.DynamicParameters.memory.name());
        List<VMInstanceDetailVO> detailList = new ArrayList<>();
        for(Map.Entry<String, String> entry: details.entrySet()) {
            VMInstanceDetailVO detailVO = new VMInstanceDetailVO(vmId, entry.getKey(), entry.getValue(), true);
            detailList.add(detailVO);
        }
        vmInstanceDetailsDao.saveDetails(detailList);
    }

    private void saveCustomOfferingDetails(long vmId, ServiceOffering serviceOffering) {
        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
        details.put(UsageEventVO.DynamicParameters.cpuNumber.name(), serviceOffering.getCpu().toString());
        details.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), serviceOffering.getSpeed().toString());
        details.put(UsageEventVO.DynamicParameters.memory.name(), serviceOffering.getRamSize().toString());
        List<VMInstanceDetailVO> detailList = new ArrayList<>();
        for (Map.Entry<String, String> entry: details.entrySet()) {
            VMInstanceDetailVO detailVO = new VMInstanceDetailVO(vmId, entry.getKey(), entry.getValue(), true);
            detailList.add(detailVO);
        }
        vmInstanceDetailsDao.saveDetails(detailList);
    }

    @Override
    public String getConfigComponentName() {
        return VirtualMachineManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] { ClusterDeltaSyncInterval, StartRetry, VmDestroyForcestop, VmOpCancelInterval, VmOpCleanupInterval, VmOpCleanupWait,
                VmOpLockStateRetry, VmOpWaitInterval, ExecuteInSequence, VmJobCheckInterval, VmJobTimeout, VmJobStateReportInterval,
                VmConfigDriveLabel, VmConfigDriveOnPrimaryPool, VmConfigDriveForceHostCacheUse, VmConfigDriveUseHostCacheOnUnsupportedPool,
                HaVmRestartHostUp, ResourceCountRunningVMsonly, AllowExposeHypervisorHostname, AllowExposeHypervisorHostnameAccountLevel, SystemVmRootDiskSize,
                AllowExposeDomainInMetadata, MetadataCustomCloudName, VmMetadataManufacturer, VmMetadataProductName,
                VmSyncPowerStateTransitioning
        };
    }

    public List<StoragePoolAllocator> getStoragePoolAllocators() {
        return _storagePoolAllocators;
    }

    @Inject
    public void setStoragePoolAllocators(final List<StoragePoolAllocator> storagePoolAllocators) {
        _storagePoolAllocators = storagePoolAllocators;
    }

    /**
     * PowerState report handling for out-of-band changes and handling of left-over transitional VM states
     */

    @MessageHandler(topic = Topics.VM_POWER_STATE)
    protected void HandlePowerStateReport(final String subject, final String senderAddress, final Object args) {
        assert args != null;
        final Long vmId = (Long)args;

        final List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                VirtualMachine.Type.Instance, vmId);
        if (CollectionUtils.isEmpty(pendingWorkJobs) && !_haMgr.hasPendingHaWork(vmId)) {
            final VMInstanceVO vm = _vmDao.findById(vmId);
            if (vm != null) {
                switch (vm.getPowerState()) {
                case PowerOn:
                    handlePowerOnReportWithNoPendingJobsOnVM(vm);
                    break;

                case PowerOff:
                case PowerReportMissing:
                    handlePowerOffReportWithNoPendingJobsOnVM(vm);
                    break;
                case PowerUnknown:
                default:
                    assert false;
                    break;
                }
            } else {
                logger.warn("VM {} no longer exists when processing VM state report.", vmId);
            }
        } else {
            logger.info("There is pending job or HA tasks working on the VM. vm: {}, postpone power-change report by resetting power-change counters.", () -> _vmDao.findById(vmId));
            _vmDao.resetVmPowerStateTracking(vmId);
        }
    }

    private ApiCommandResourceType getApiCommandResourceTypeForVm(VirtualMachine vm) {
        switch (vm.getType()) {
            case DomainRouter:
                return ApiCommandResourceType.DomainRouter;
            case ConsoleProxy:
                return ApiCommandResourceType.ConsoleProxy;
            case SecondaryStorageVm:
                return ApiCommandResourceType.SystemVm;
        }
        return ApiCommandResourceType.VirtualMachine;
    }

    private void handlePowerOnReportWithNoPendingJobsOnVM(final VMInstanceVO vm) {
        Host host = _hostDao.findById(vm.getHostId());
        Host poweredHost = _hostDao.findById(vm.getPowerHostId());

        switch (vm.getState()) {
        case Starting:
            logger.info("VM {} is at {} and we received a power-on report while there is no pending jobs on it.", vm.getInstanceName(), vm.getState());

            try {
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }

            logger.info("VM {} is sync-ed to at Running state according to power-on report from hypervisor.", vm.getInstanceName());

            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, "VM " + vm.getHostName() + "(" + vm.getInstanceName()
                    + ") state is sync-ed (Starting -> Running) from out-of-context transition. VM network environment may need to be reset");
            break;

        case Running:
            try {
                if (vm.getHostId() != null && !vm.getHostId().equals(vm.getPowerHostId())) {
                    logger.info("Detected out of band VM migration from host {} to host {}", () -> _hostDao.findById(vm.getHostId()), () -> _hostDao.findById(vm.getPowerHostId()));
                }
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }

            break;

        case Stopping:
        case Stopped:
            logger.info("VM {} is at {} and we received a power-on report while there is no pending jobs on it.", vm.getInstanceName(), vm.getState());

            try {
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }
            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, "VM " + vm.getHostName() + "(" + vm.getInstanceName() + ") state is sync-ed (" + vm.getState()
                    + " -> Running) from out-of-context transition. VM network environment may need to be reset");

            ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM, vm.getDomainId(),
                EventTypes.EVENT_VM_START, "Out of band VM power on", vm.getId(), getApiCommandResourceTypeForVm(vm).toString());
            logger.info("VM {} is sync-ed to at Running state according to power-on report from hypervisor.", vm.getInstanceName());
            break;

        case Destroyed:
        case Expunging:
            logger.info("Receive power on report when VM is in destroyed or expunging state. vm: {}, state: {}.", vm, vm.getState());
            break;

        case Migrating:
            logger.info("VM {} is at {} and we received a power-on report while there is no pending jobs on it.", vm, vm.getState());
            try {
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOnReport, vm.getPowerHostId());
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }
            logger.info("VM {} is sync-ed to at Running state according to power-on report from hypervisor.", vm);
            break;

        case Error:
        default:
            logger.info("Receive power on report when VM is in error or unexpected state. vm: {}, state: {}.", vm, vm.getState());
            break;
        }
    }

    private void handlePowerOffReportWithNoPendingJobsOnVM(final VMInstanceVO vm) {
        switch (vm.getState()) {
        case Starting:
        case Stopping:
        case Running:
        case Stopped:
            ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM,vm.getDomainId(),
                    EventTypes.EVENT_VM_STOP, "Out of band VM power off", vm.getId(), getApiCommandResourceTypeForVm(vm).toString());
        case Migrating:
            logger.info("VM {} is at {} and we received a {} report while there is no pending jobs on it"
                            , vm, vm.getState(), vm.getPowerState());
            if((HighAvailabilityManager.ForceHA.value() || vm.isHaEnabled()) && vm.getState() == State.Running
                    && HaVmRestartHostUp.value()
                    && vm.getHypervisorType() != HypervisorType.VMware
                    && vm.getHypervisorType() != HypervisorType.Hyperv) {
                logger.info("Detected out-of-band stop of a HA enabled VM {}, will schedule restart.", vm);
                if (!_haMgr.hasPendingHaWork(vm.getId())) {
                    _haMgr.scheduleRestart(vm, true);
                } else {
                    logger.info("VM {} already has a pending HA task working on it.", vm);
                }
                return;
            }

            if (PowerState.PowerOff.equals(vm.getPowerState())) {
                final VirtualMachineGuru vmGuru = getVmGuru(vm);
                final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
                if (!sendStop(vmGuru, profile, true, true)) {
                    return;
                } else {
                    // Release resources on StopCommand success
                    releaseVmResources(profile, true);
                }
            } else if (PowerState.PowerReportMissing.equals(vm.getPowerState())) {
                final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
                // VM will be sync-ed to Stopped state, release the resources
                releaseVmResources(profile, true);
            }

            try {
                stateTransitTo(vm, VirtualMachine.Event.FollowAgentPowerOffReport, null);
            } catch (final NoTransitionException e) {
                logger.warn("Unexpected VM state transition exception, race-condition?", e);
            }

            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, String.format("VM %s(%s) state is sync-ed (%s -> Stopped) from out-of-context transition.",
                            vm.getHostName(), vm, vm.getState()));

            logger.info("VM {} is sync-ed to at Stopped state according to power-off report from hypervisor.", vm);

            break;

        case Destroyed:
        case Expunging:
            break;

        case Error:
        default:
            break;
        }
    }

    /**
     * Scans stalled VMs in transition states on an UP host and processes them accordingly.
     *
     * <p>This method is executed only when the {@code syncTransitioningVmPowerState} flag is enabled. It identifies
     * VMs stuck in specific states (e.g., Starting, Stopping, Migrating) on a host that is UP, except for those
     * in the Expunging state, which require special handling.</p>
     *
     * <p>The following conditions are checked during the scan:
     * <ul>
     *     <li>No pending {@code VmWork} job exists for the VM.</li>
     *     <li>The VM is associated with the given {@code hostId}, and the host is UP.</li>
     * </ul>
     * </p>
     *
     * <p>When a host is UP, a state report for the VMs will typically be received. However, certain scenarios
     * (e.g., out-of-band changes or behavior specific to hypervisors like XenServer or KVM) might result in
     * missing reports, preventing the state-sync logic from running. To address this, the method scans VMs
     * based on their last update timestamp. If a VM remains stalled without a status update while its host is UP,
     * it is assumed to be powered off, which is generally a safe assumption.</p>
     *
     * @param hostId the ID of the host to scan for stalled VMs in transition states.
     */
    private void scanStalledVMInTransitionStateOnUpHost(final long hostId) {
        if (!syncTransitioningVmPowerState) {
            return;
        }
        if (!_hostDao.isHostUp(hostId)) {
            return;
        }
        final long stallThresholdInMs = VmJobStateReportInterval.value() * 2;
        final long cutTime = new Date(DateUtil.currentGMTTime().getTime() - stallThresholdInMs).getTime();
        final List<VMInstanceVO> hostTransitionVms = _vmDao.listByHostAndState(hostId, State.Starting, State.Stopping, State.Migrating);

        final List<VMInstanceVO> mostLikelyStoppedVMs = listStalledVMInTransitionStateOnUpHost(hostTransitionVms, cutTime);
        for (final VMInstanceVO vm : mostLikelyStoppedVMs) {
            handlePowerOffReportWithNoPendingJobsOnVM(vm);
        }

        final List<VMInstanceVO> vmsWithRecentReport = listVMInTransitionStateWithRecentReportOnUpHost(hostTransitionVms, cutTime);
        for (final VMInstanceVO vm : vmsWithRecentReport) {
            if (vm.getPowerState() == PowerState.PowerOn) {
                handlePowerOnReportWithNoPendingJobsOnVM(vm);
            } else {
                handlePowerOffReportWithNoPendingJobsOnVM(vm);
            }
        }
    }


    private void scanStalledVMInTransitionStateOnDisconnectedHosts() {
        final Date cutTime = new Date(DateUtil.currentGMTTime().getTime() - VmOpWaitInterval.value() * 1000);
        final List<Long> stuckAndUncontrollableVMs = listStalledVMInTransitionStateOnDisconnectedHosts(cutTime);
        for (final Long vmId : stuckAndUncontrollableVMs) {
            final VMInstanceVO vm = _vmDao.findById(vmId);

            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SYNC, vm.getDataCenterId(), vm.getPodIdToDeployIn(),
                    VM_SYNC_ALERT_SUBJECT, String.format("VM %s(%s) is stuck in %s state and its host is unreachable for too long",
                            vm.getHostName(), vm, vm.getState()));
        }
    }

    private List<VMInstanceVO> listStalledVMInTransitionStateOnUpHost(
            final List<VMInstanceVO> transitioningVms, final long cutTime) {
        if (CollectionUtils.isEmpty(transitioningVms)) {
            return transitioningVms;
        }
        List<Long> vmIdsInProgress = vmIdsInProgressCache.get();
        return transitioningVms.stream()
                .filter(v -> v.getPowerStateUpdateTime().getTime() < cutTime && !vmIdsInProgress.contains(v.getId()))
                .collect(Collectors.toList());
    }

    private List<VMInstanceVO> listVMInTransitionStateWithRecentReportOnUpHost(
            final List<VMInstanceVO> transitioningVms, final long cutTime) {
        if (CollectionUtils.isEmpty(transitioningVms)) {
            return transitioningVms;
        }
        List<Long> vmIdsInProgress = vmIdsInProgressCache.get();
        return transitioningVms.stream()
                .filter(v -> v.getPowerStateUpdateTime().getTime() > cutTime && !vmIdsInProgress.contains(v.getId()))
                .collect(Collectors.toList());
    }

    private List<Long> listStalledVMInTransitionStateOnDisconnectedHosts(final Date cutTime) {
        final String sql = "SELECT i.* " +
                "FROM vm_instance AS i " +
                "INNER JOIN host AS h ON i.host_id = h.id " +
                "WHERE h.status != 'UP' " +
                "  AND i.power_state_update_time < ? " +
                "  AND i.state IN ('Starting', 'Stopping', 'Migrating') " +
                "  AND i.id NOT IN (SELECT vm_instance_id FROM vm_work_job AS w " +
                "                    INNER JOIN async_job AS j ON w.id = j.id " +
                "                    WHERE j.job_status = ?) " +
                "  AND i.removed IS NULL";

        final List<Long> l = new ArrayList<>();
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        String cutTimeStr = DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), cutTime);
        int jobStatusInProgress = JobInfo.Status.IN_PROGRESS.ordinal();

        try {
            PreparedStatement pstmt = txn.prepareAutoCloseStatement(sql);

            pstmt.setString(1, cutTimeStr);
            pstmt.setInt(2, jobStatusInProgress);
            final ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                l.add(rs.getLong(1));
            }
        } catch (final SQLException e) {
            logger.error("Unable to execute SQL [{}] with params {\"i.power_state_update_time\": \"{}\", \"j.job_status\": {}} due to [{}].", sql, cutTimeStr, jobStatusInProgress, e.getMessage(), e);
        }
        return l;
    }

    public class VmStateSyncOutcome extends OutcomeImpl<VirtualMachine> {
        private long _vmId;

        public VmStateSyncOutcome(final AsyncJob job, final PowerState desiredPowerState, final long vmId, final Long srcHostIdForMigration) {
            super(VirtualMachine.class, job, VmJobCheckInterval.value(), new Predicate() {
                @Override
                public boolean checkCondition() {
                    final AsyncJobVO jobVo = _entityMgr.findById(AsyncJobVO.class, job.getId());
                    return jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS;
                }
            }, Topics.VM_POWER_STATE, AsyncJob.Topics.JOB_STATE);
            _vmId = vmId;
        }

        @Override
        protected VirtualMachine retrieve() {
            return _vmDao.findById(_vmId);
        }
    }

    public class VmJobVirtualMachineOutcome extends OutcomeImpl<VirtualMachine> {
        private long _vmId;

        public VmJobVirtualMachineOutcome(final AsyncJob job, final long vmId) {
            super(VirtualMachine.class, job, VmJobCheckInterval.value(), new Predicate() {
                @Override
                public boolean checkCondition() {
                    final AsyncJobVO jobVo = _entityMgr.findById(AsyncJobVO.class, job.getId());
                    return jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS;
                }
            }, AsyncJob.Topics.JOB_STATE);
            _vmId = vmId;
        }

        @Override
        protected VirtualMachine retrieve() {
            return _vmDao.findById(_vmId);
        }
    }

    public Outcome<VirtualMachine> startVmThroughJobQueue(final String vmUuid,
            final Map<VirtualMachineProfile.Param, Object> params,
            final DeploymentPlan planToDeploy, final DeploymentPlanner planner) {
        String commandName = VmWorkStart.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, VmWorkJobVO.Step.Starting, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkStart workInfo = new VmWorkStart(newVmWorkJobAndInfo.second());

            workInfo.setPlan(planToDeploy);
            workInfo.setParams(params);
            if (planner != null) {
                workInfo.setDeploymentPlanner(planner.getName());
            }
            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob,
                VirtualMachine.PowerState.PowerOn, vmId, null);
    }

    public Outcome<VirtualMachine> stopVmThroughJobQueue(final String vmUuid, final boolean cleanup) {
        String commandName = VmWorkStop.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(null, vmUuid, null, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, VmWorkJobVO.Step.Prepare, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkStop workInfo = new VmWorkStop(newVmWorkJobAndInfo.second(), cleanup);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob,
                VirtualMachine.PowerState.PowerOff, vmId, null);
    }

    public Outcome<VirtualMachine> rebootVmThroughJobQueue(final String vmUuid,
            final Map<VirtualMachineProfile.Param, Object> params) {
        String commandName = VmWorkReboot.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, VmWorkJobVO.Step.Prepare, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkReboot workInfo = new VmWorkReboot(newVmWorkJobAndInfo.second(), params);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob,
                vmId);
    }

    public Outcome<VirtualMachine> migrateVmThroughJobQueue(final String vmUuid, final long srcHostId, final DeployDestination dest) {
        Map<Volume, StoragePool> volumeStorageMap = dest.getStorageForDisks();
        if (volumeStorageMap != null) {
            for (Volume vol : volumeStorageMap.keySet()) {
                checkConcurrentJobsPerDatastoreThreshhold(volumeStorageMap.get(vol));
            }
        }

        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        Long vmId = vm.getId();

        String commandName = VmWorkMigrate.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, vmUuid, VirtualMachine.Type.Instance, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkMigrate workInfo = new VmWorkMigrate(newVmWorkJobAndInfo.second(), srcHostId, dest);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob,
                VirtualMachine.PowerState.PowerOn, vmId, vm.getPowerHostId());
    }

    public Outcome<VirtualMachine> migrateVmAwayThroughJobQueue(final String vmUuid, final long srcHostId) {
        VMInstanceVO vm = _vmDao.findByUuid(vmUuid);
        Long vmId = vm.getId();

        String commandName = VmWorkMigrateAway.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, vmUuid, VirtualMachine.Type.Instance, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkMigrateAway workInfo = new VmWorkMigrateAway(newVmWorkJobAndInfo.second(), srcHostId);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }


        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob, VirtualMachine.PowerState.PowerOn, vmId, vm.getPowerHostId());
    }

    public Outcome<VirtualMachine> migrateVmWithStorageThroughJobQueue(
            final String vmUuid, final long srcHostId, final long destHostId,
            final Map<Long, Long> volumeToPool) {
        String commandName = VmWorkMigrateWithStorage.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkMigrateWithStorage workInfo = new VmWorkMigrateWithStorage(newVmWorkJobAndInfo.second(), srcHostId, destHostId, volumeToPool);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob,
                VirtualMachine.PowerState.PowerOn, vmId, destHostId);
    }

    public Outcome<VirtualMachine> migrateVmForScaleThroughJobQueue(
            final String vmUuid, final long srcHostId, final DeployDestination dest, final Long newSvcOfferingId) {
        String commandName = VmWorkMigrateForScale.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkMigrateForScale workInfo = new VmWorkMigrateForScale(newVmWorkJobAndInfo.second(), srcHostId, dest, newSvcOfferingId);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    private void checkConcurrentJobsPerDatastoreThreshhold(final StoragePool destPool) {
        final Long threshold = VolumeApiService.ConcurrentMigrationsThresholdPerDatastore.value();
        if (threshold != null && threshold > 0) {
            long count = _jobMgr.countPendingJobs("\"storageid\":\"" + destPool.getUuid() + "\"", MigrateVMCmd.class.getName(), MigrateVolumeCmd.class.getName(), MigrateVolumeCmdByAdmin.class.getName());
            if (count > threshold) {
                throw new CloudRuntimeException("Number of concurrent migration jobs per datastore exceeded the threshold: " + threshold.toString() + ". Please try again after some time.");
            }
        }
    }

    public Outcome<VirtualMachine> migrateVmStorageThroughJobQueue(final String vmUuid, final Map<Long, Long> volumeToPool) {
        Collection<Long> poolIds = volumeToPool.values();
        Set<Long> uniquePoolIds = new HashSet<>(poolIds);
        for (Long poolId : uniquePoolIds) {
            StoragePoolVO pool = _storagePoolDao.findById(poolId);
            checkConcurrentJobsPerDatastoreThreshhold(pool);
        }

        String commandName = VmWorkStorageMigration.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkStorageMigration workInfo = new VmWorkStorageMigration(newVmWorkJobAndInfo.second(),  volumeToPool);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    public Outcome<VirtualMachine> addVmToNetworkThroughJobQueue(
            final VirtualMachine vm, final Network network, final NicProfile requested) {
        Long vmId = vm.getId();
        String commandName = VmWorkAddVmToNetwork.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        final List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(
                VirtualMachine.Type.Instance, vm.getId(),
                VmWorkAddVmToNetwork.class.getName(), network.getUuid());

        VmWorkJobVO workJob = null;
        if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
            if (pendingWorkJobs.size() > 1) {
                throw new CloudRuntimeException(String.format("The number of jobs to add network %s to vm %s are %d", network.getUuid(), vm.getInstanceName(), pendingWorkJobs.size()));
            }
            workJob = pendingWorkJobs.get(0);
        } else {
            logger.trace("no jobs to add network {} for vm {} yet", network, vm);

            workJob = createVmWorkJobToAddNetwork(vm, network, requested, context, user, account);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vm.getId());
    }

    private VmWorkJobVO createVmWorkJobToAddNetwork(
            VirtualMachine vm,
            Network network,
            NicProfile requested,
            CallContext context,
            User user,
            Account account) {
        VmWorkJobVO workJob;
        workJob = new VmWorkJobVO(context.getContextId());

        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        workJob.setCmd(VmWorkAddVmToNetwork.class.getName());

        workJob.setAccountId(account.getId());
        workJob.setUserId(user.getId());
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(vm.getId());
        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());
        workJob.setSecondaryObjectIdentifier(network.getUuid());

        // save work context info as there might be some duplicates
        final VmWorkAddVmToNetwork workInfo = new VmWorkAddVmToNetwork(user.getId(), account.getId(), vm.getId(),
                VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, network.getId(), requested);
        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

        try {
            _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
        } catch (CloudRuntimeException e) {
            if (e.getCause() instanceof EntityExistsException) {
                String msg = String.format("A job to add a nic for network %s to vm %s already exists", network.getUuid(), vm.getUuid());
                logger.warn(msg, e);
            }
            throw e;
        }

        return workJob;
    }

    public Outcome<VirtualMachine> removeNicFromVmThroughJobQueue(
            final VirtualMachine vm, final Nic nic) {
        Long vmId = vm.getId();
        String commandName = VmWorkRemoveNicFromVm.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkRemoveNicFromVm workInfo = new VmWorkRemoveNicFromVm(newVmWorkJobAndInfo.second(), nic.getId());

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    public Outcome<VirtualMachine> removeVmFromNetworkThroughJobQueue(
            final VirtualMachine vm, final Network network, final URI broadcastUri) {
        Long vmId = vm.getId();
        String commandName = VmWorkRemoveVmFromNetwork.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkRemoveVmFromNetwork workInfo = new VmWorkRemoveVmFromNetwork(newVmWorkJobAndInfo.second(), network, broadcastUri);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    public Outcome<VirtualMachine> reconfigureVmThroughJobQueue(
            final String vmUuid, final ServiceOffering oldServiceOffering, final ServiceOffering newServiceOffering, Map<String, String> customParameters, final boolean reconfiguringOnExistingHost) {
        String commandName = VmWorkReconfigure.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkReconfigure workInfo = new VmWorkReconfigure(newVmWorkJobAndInfo.second(), oldServiceOffering.getId(), newServiceOffering.getId(), customParameters, reconfiguringOnExistingHost);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateStart(final VmWorkStart work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());

        Boolean enterSetup = (Boolean)work.getParams().get(VirtualMachineProfile.Param.BootIntoSetup);
        logger.debug("orchestrating VM start for '{}' {} set to {}", vm.getInstanceName(), VirtualMachineProfile.Param.BootIntoSetup, enterSetup);

        try {
            orchestrateStart(vm.getUuid(), work.getParams(), work.getPlan(), _dpMgr.getDeploymentPlannerByName(work.getDeploymentPlanner()));
        } catch (CloudRuntimeException e){
            logger.error("Unable to orchestrate start {} due to [{}].", vm, e.getMessage());
            CloudRuntimeException ex = new CloudRuntimeException(String.format("Unable to orchestrate the start of VM instance %s.",
                    ReflectionToStringBuilderUtils.reflectOnlySelectedFields(vm, "instanceName", "uuid")));
            return new Pair<>(JobInfo.Status.FAILED, JobSerializerHelper.toObjectSerializedString(ex));
        }
        return new Pair<>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateStop(final VmWorkStop work) throws Exception {
        final VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, work.getVmId());
        if (vm == null) {
            String message = String.format("Unable to find VM [%s].", work.getVmId());
            logger.warn(message);
            throw new CloudRuntimeException(message);
        }

        orchestrateStop(vm.getUuid(), work.isCleanup());
        return new Pair<>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateMigrate(final VmWorkMigrate work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());

        orchestrateMigrate(vm.getUuid(), work.getSrcHostId(), work.getDeployDestination());
        return new Pair<>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateMigrateAway(final VmWorkMigrateAway work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());

        try {
            orchestrateMigrateAway(vm.getUuid(), work.getSrcHostId(), null);
        } catch (final InsufficientServerCapacityException e) {
            logger.warn("Failed to deploy vm {} with original planner, sending HAPlanner.", vm, e);
            orchestrateMigrateAway(vm.getUuid(), work.getSrcHostId(), _haMgr.getHAPlanner());
        }

        return new Pair<>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateMigrateWithStorage(final VmWorkMigrateWithStorage work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());
        orchestrateMigrateWithStorage(vm.getUuid(),
                work.getSrcHostId(),
                work.getDestHostId(),
                work.getVolumeToPool());
        return new Pair<>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateMigrateForScale(final VmWorkMigrateForScale work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());
        orchestrateMigrateForScale(vm.getUuid(),
                work.getSrcHostId(),
                work.getDeployDestination(),
                work.getNewServiceOfferringId());
        return new Pair<>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateReboot(final VmWorkReboot work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());
        orchestrateReboot(vm.getUuid(), work.getParams());
        return new Pair<>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateAddVmToNetwork(final VmWorkAddVmToNetwork work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());

        final Network network = _networkDao.findById(work.getNetworkId());
        final NicProfile nic = orchestrateAddVmToNetwork(vm, network,
                work.getRequestedNicProfile());

        return new Pair<>(JobInfo.Status.SUCCEEDED, _jobMgr.marshallResultObject(nic));
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateRemoveNicFromVm(final VmWorkRemoveNicFromVm work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());
        final NicVO nic = _entityMgr.findById(NicVO.class, work.getNicId());
        final boolean result = orchestrateRemoveNicFromVm(vm, nic);
        return new Pair<>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(result));
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateRemoveVmFromNetwork(final VmWorkRemoveVmFromNetwork work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());
        final boolean result = orchestrateRemoveVmFromNetwork(vm,
                work.getNetwork(), work.getBroadcastUri());
        return new Pair<>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(result));
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateReconfigure(final VmWorkReconfigure work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());
        ServiceOfferingVO oldServiceOffering = _offeringDao.findById(work.getOldServiceOfferingId());
        ServiceOfferingVO newServiceOffering = _offeringDao.findById(work.getNewServiceOfferingId());
        if (newServiceOffering.isDynamic()) {
            newServiceOffering = _offeringDao.getComputeOffering(newServiceOffering, work.getCustomParameters());
        }

        reConfigureVm(vm.getUuid(), oldServiceOffering, newServiceOffering, work.getCustomParameters(),
                work.isSameHost());
        return new Pair<>(JobInfo.Status.SUCCEEDED, null);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateStorageMigration(final VmWorkStorageMigration work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());
        orchestrateStorageMigration(vm.getUuid(), work.getVolumeToPool());
        return new Pair<>(JobInfo.Status.SUCCEEDED, null);
    }

    @Override
    public Pair<JobInfo.Status, String> handleVmWorkJob(final VmWork work) throws Exception {
        return _jobHandlerProxy.handleVmWorkJob(work);
    }

    private VmWorkJobVO createPlaceHolderWork(final long instanceId) {
        return createPlaceHolderWork(instanceId, null);
    }

    private VmWorkJobVO createPlaceHolderWork(final long instanceId, String secondaryObjectIdentifier) {
        final VmWorkJobVO workJob = new VmWorkJobVO("");

        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_PLACEHOLDER);
        workJob.setCmd("");
        workJob.setCmdInfo("");

        workJob.setAccountId(0);
        workJob.setUserId(0);
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(instanceId);
        if(org.apache.commons.lang3.StringUtils.isNotBlank(secondaryObjectIdentifier)) {
            workJob.setSecondaryObjectIdentifier(secondaryObjectIdentifier);
        }
        workJob.setInitMsid(ManagementServerNode.getManagementServerId());

        _workJobDao.persist(workJob);

        return workJob;
    }

    @Override
    public UserVm restoreVirtualMachine(final long vmId, final Long newTemplateId, final Long rootDiskOfferingId, final boolean expunge, final Map<String, String> details) throws ResourceUnavailableException, InsufficientCapacityException {
        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            VmWorkJobVO placeHolder = null;
            placeHolder = createPlaceHolderWork(vmId);
            try {
                return orchestrateRestoreVirtualMachine(vmId, newTemplateId, rootDiskOfferingId, expunge, details);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            final Outcome<VirtualMachine> outcome = restoreVirtualMachineThroughJobQueue(vmId, newTemplateId, rootDiskOfferingId, expunge, details);

            retrieveVmFromJobOutcome(outcome, String.valueOf(vmId), "restoreVirtualMachine");

            Object jobResult = retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);

            if (jobResult != null && jobResult instanceof HashMap) {
                HashMap<Long, String> passwordMap = (HashMap<Long, String>)jobResult;
                UserVmVO userVm = _userVmDao.findById(vmId);
                userVm.setPassword(passwordMap.get(vmId));
                return userVm;
            }

            throw new RuntimeException("Unexpected job execution result");
        }
    }

    private UserVm orchestrateRestoreVirtualMachine(final long vmId, final Long newTemplateId, final Long rootDiskOfferingId, final boolean expunge, final Map<String, String> details) throws ResourceUnavailableException, InsufficientCapacityException {
        logger.debug("Restoring vm {} with templateId: {}, diskOfferingId: {}, details: {}", vmId, newTemplateId, rootDiskOfferingId, details);
        final CallContext context = CallContext.current();
        final Account account = context.getCallingAccount();
        return _userVmService.restoreVirtualMachine(account, vmId, newTemplateId, rootDiskOfferingId, expunge, details);
    }

    public Outcome<VirtualMachine> restoreVirtualMachineThroughJobQueue(final long vmId, final Long newTemplateId, final Long rootDiskOfferingId, final boolean expunge, Map<String, String> details) {
        String commandName = VmWorkRestore.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkRestore workInfo = new VmWorkRestore(newVmWorkJobAndInfo.second(), newTemplateId, rootDiskOfferingId, expunge, details);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateRestoreVirtualMachine(final VmWorkRestore work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());
        UserVm uservm = orchestrateRestoreVirtualMachine(vm.getId(), work.getTemplateId(), work.getRootDiskOfferingId(), work.getExpunge(), work.getDetails());
        HashMap<Long, String> passwordMap = new HashMap<>();
        passwordMap.put(uservm.getId(), uservm.getPassword());
        return new Pair<>(JobInfo.Status.SUCCEEDED, _jobMgr.marshallResultObject(passwordMap));
    }

    @Override
    public Boolean updateDefaultNicForVM(final VirtualMachine vm, final Nic nic, final Nic defaultNic) {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
            try {
                return orchestrateUpdateDefaultNicForVM(vm, nic, defaultNic);
            } finally {
                if (placeHolder != null) {
                    _workJobDao.expunge(placeHolder.getId());
                }
            }
        } else {
            final Outcome<VirtualMachine> outcome = updateDefaultNicForVMThroughJobQueue(vm, nic, defaultNic);

            retrieveVmFromJobOutcome(outcome, vm.getUuid(), "updateDefaultNicForVM");

            try {
                Object jobResult = retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);

                if (jobResult != null && jobResult instanceof Boolean) {
                    return (Boolean)jobResult;
                }
            } catch (ResourceUnavailableException | InsufficientCapacityException ex) {
                throw new RuntimeException("Unhandled exception", ex);
            }

            throw new RuntimeException("Unexpected job execution result");
        }
    }

    private Boolean orchestrateUpdateDefaultNicForVM(final VirtualMachine vm, final Nic nic, final Nic defaultNic) {

        logger.debug("Updating default nic of vm {} from nic {} to nic {}", vm, defaultNic.getUuid(), nic.getUuid());
        Integer chosenID = nic.getDeviceId();
        Integer existingID = defaultNic.getDeviceId();
        NicVO nicVO = _nicsDao.findById(nic.getId());
        NicVO defaultNicVO = _nicsDao.findById(defaultNic.getId());

        nicVO.setDefaultNic(true);
        nicVO.setDeviceId(existingID);
        defaultNicVO.setDefaultNic(false);
        defaultNicVO.setDeviceId(chosenID);

        _nicsDao.persist(nicVO);
        _nicsDao.persist(defaultNicVO);
        return true;
    }

    public Outcome<VirtualMachine> updateDefaultNicForVMThroughJobQueue(final VirtualMachine vm, final Nic nic, final Nic defaultNic) {
        Long vmId = vm.getId();
        String commandName = VmWorkUpdateDefaultNic.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkUpdateDefaultNic workInfo = new VmWorkUpdateDefaultNic(newVmWorkJobAndInfo.second(), nic.getId(), defaultNic.getId());

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @ReflectionUse
    private Pair<JobInfo.Status, String> orchestrateUpdateDefaultNic(final VmWorkUpdateDefaultNic work) throws Exception {
        VMInstanceVO vm = findVmById(work.getVmId());
        final NicVO nic = _entityMgr.findById(NicVO.class, work.getNicId());
        if (nic == null) {
            throw new CloudRuntimeException("Unable to find nic " + work.getNicId());
        }
        final NicVO defaultNic = _entityMgr.findById(NicVO.class, work.getDefaultNicId());
        if (defaultNic == null) {
            throw new CloudRuntimeException("Unable to find default nic " + work.getDefaultNicId());
        }
        final boolean result = orchestrateUpdateDefaultNicForVM(vm, nic, defaultNic);
        return new Pair<>(JobInfo.Status.SUCCEEDED,
                _jobMgr.marshallResultObject(result));
    }

    private Pair<Long, Long> findClusterAndHostIdForVmFromVolumes(long vmId) {
        Long clusterId = null;
        Long hostId = null;
        List<VolumeVO> volumes = _volsDao.findByInstance(vmId);
        for (VolumeVO volume : volumes) {
            if (Volume.State.Ready.equals(volume.getState()) &&
                    volume.getPoolId() != null) {
                StoragePoolVO pool = _storagePoolDao.findById(volume.getPoolId());
                if (pool != null && pool.getClusterId() != null) {
                    clusterId = pool.getClusterId();
                    // hostId to be used only for sending commands, capacity check skipped
                    List<HostVO> hosts = _hostDao.findHypervisorHostInCluster(pool.getClusterId());
                    if (CollectionUtils.isNotEmpty(hosts)) {
                        hostId = hosts.get(0).getId();
                        break;
                    }
                }
            }
        }
        return new Pair<>(clusterId, hostId);
    }

    @Override
    public Pair<Long, Long> findClusterAndHostIdForVm(VirtualMachine vm, boolean skipCurrentHostForStartingVm) {
        Long hostId = null;
        Host host = null;
        if (!skipCurrentHostForStartingVm || !State.Starting.equals(vm.getState())) {
            hostId = vm.getHostId();
        }
        Long clusterId = null;
        if (hostId == null) {
            if (vm.getLastHostId() == null) {
                return findClusterAndHostIdForVmFromVolumes(vm.getId());
            }
            hostId = vm.getLastHostId();
            host = _hostDao.findById(hostId);
            logger.debug("host id is null, using last host {} with id {}", host, hostId);
        }
        host = host == null ? _hostDao.findById(hostId) : host;
        if (host != null) {
            clusterId = host.getClusterId();
        }
        return new Pair<>(clusterId, hostId);
    }

    private Pair<Long, Long> findClusterAndHostIdForVm(VirtualMachine vm) {
        return findClusterAndHostIdForVm(vm, false);
    }

    @Override
    public Pair<Long, Long> findClusterAndHostIdForVm(long vmId) {
        VMInstanceVO vm = _vmDao.findById(vmId);
        if (vm == null) {
            return new Pair<>(null, null);
        }
        return findClusterAndHostIdForVm(vm);
    }

    protected VirtualMachine retrieveVmFromJobOutcome(Outcome<VirtualMachine> jobOutcome, String vmUuid, String jobName) {
        try {
            return jobOutcome.get();
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(String.format("Unable to retrieve result from job \"%s\" due to [%s]. VM {\"uuid\": \"%s\"}.", jobName, e.getMessage(), vmUuid), e);
        }
    }

    protected Object retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(Outcome<VirtualMachine> outcome) throws ResourceUnavailableException, InsufficientCapacityException{
        Object jobResult = _jobMgr.unmarshallResultObject(outcome.getJob());

        if (jobResult == null) {
            return null;
        }

        if (jobResult instanceof AgentUnavailableException) {
           throw (AgentUnavailableException) jobResult;
        }

        if (jobResult instanceof InsufficientServerCapacityException) {
           throw (InsufficientServerCapacityException) jobResult;
        }

        if (jobResult instanceof ResourceUnavailableException) {
           throw (ResourceUnavailableException) jobResult;
        }

        if (jobResult instanceof InsufficientCapacityException) {
           throw (InsufficientCapacityException) jobResult;
        }

        if (jobResult instanceof ConcurrentOperationException) {
           throw (ConcurrentOperationException) jobResult;
        }

        if (jobResult instanceof RuntimeException) {
           throw (RuntimeException) jobResult;
        }

        if (jobResult instanceof Throwable) {
           throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
        }

        return jobResult;
    }

    protected Pair<VmWorkJobVO, Long> retrievePendingWorkJob(String vmUuid, String commandName) {
        return retrievePendingWorkJob(null, vmUuid, VirtualMachine.Type.Instance, commandName);
    }

    protected Pair<VmWorkJobVO, Long> retrievePendingWorkJob(Long id, String commandName) {
        return retrievePendingWorkJob(id, null, VirtualMachine.Type.Instance, commandName);
    }

    protected Pair<VmWorkJobVO, Long> retrievePendingWorkJob(Long vmId, String vmUuid, VirtualMachine.Type vmType, String commandName) {
        if (vmId == null) {
            VMInstanceVO vm = _vmDao.findByUuid(vmUuid);

            if (vm == null) {
                String message = String.format("Could not find a VM with the uuid [%s]. Unable to continue validations with command [%s] through job queue.", vmUuid, commandName);
                logger.error(message);
                throw new RuntimeException(message);
            }

            vmId = vm.getId();

            if (vmType == null) {
                vmType = vm.getType();
            }
        }

        List<VmWorkJobVO> pendingWorkJobs = _workJobDao.listPendingWorkJobs(vmType, vmId, commandName);

        if (CollectionUtils.isNotEmpty(pendingWorkJobs)) {
            return new Pair<>(pendingWorkJobs.get(0), vmId);
        }

        return new Pair<>(null, vmId);
    }

    protected Pair<VmWorkJobVO, VmWork> createWorkJobAndWorkInfo(String commandName, Long vmId) {
        return createWorkJobAndWorkInfo(commandName, null, vmId);
    }

    protected Pair<VmWorkJobVO, VmWork> createWorkJobAndWorkInfo(String commandName, VmWorkJobVO.Step step, Long vmId) {
        CallContext context = CallContext.current();
        long userId = context.getCallingUser().getId();
        long accountId = context.getCallingAccount().getId();

        VmWorkJobVO workJob = new VmWorkJobVO(context.getContextId());
        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        workJob.setCmd(commandName);
        workJob.setAccountId(accountId);
        workJob.setUserId(userId);

        if (step != null) {
            workJob.setStep(step);
        }

        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(vmId);
        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

        VmWork workInfo = new VmWork(userId,  accountId, vmId, VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER);

        return new Pair<>(workJob, workInfo);
    }

    protected void setCmdInfoAndSubmitAsyncJob(VmWorkJobVO workJob, VmWork workInfo, Long vmId) {
        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));
        _jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vmId);
    }

    protected VMInstanceVO findVmById(Long vmId) {
        VMInstanceVO vm = _entityMgr.findById(VMInstanceVO.class, vmId);

        if (vm == null) {
            logger.warn("Could not find VM [{}].", vmId);
        }

        assert vm != null;
        return vm;
    }

    @Override
    public HashMap<Long, ? extends VmStats> getVirtualMachineStatistics(Host host, List<Long> vmIds) {
        HashMap<Long, VmStatsEntry> vmStatsById = new HashMap<>();
        if (CollectionUtils.isEmpty(vmIds)) {
            return vmStatsById;
        }
        Map<String, Long> vmMap = _vmDao.getNameIdMapForVmIds(vmIds);
        return getVirtualMachineStatistics(host, vmMap);
    }

    @Override
    public HashMap<Long, ? extends VmStats> getVirtualMachineStatistics(Host host, Map<String, Long> vmInstanceNameIdMap) {
        HashMap<Long, VmStatsEntry> vmStatsById = new HashMap<>();
        if (MapUtils.isEmpty(vmInstanceNameIdMap)) {
            return vmStatsById;
        }
        Answer answer = _agentMgr.easySend(host.getId(), new GetVmStatsCommand(
                new ArrayList<>(vmInstanceNameIdMap.keySet()), host.getGuid(), host.getName()));
        if (answer == null || !answer.getResult()) {
            logger.warn("Unable to obtain VM statistics.");
            return vmStatsById;
        } else {
            HashMap<String, VmStatsEntry> vmStatsByName = ((GetVmStatsAnswer)answer).getVmStatsMap();
            if (vmStatsByName == null) {
                logger.warn("Unable to obtain VM statistics.");
                return vmStatsById;
            }
            for (Map.Entry<String, VmStatsEntry> entry : vmStatsByName.entrySet()) {
                vmStatsById.put(vmInstanceNameIdMap.get(entry.getKey()), entry.getValue());
            }
        }
        return vmStatsById;
    }

    @Override
    public HashMap<Long, List<? extends VmDiskStats>> getVmDiskStatistics(Host host, Map<String, Long> vmInstanceNameIdMap) {
        HashMap<Long, List<? extends  VmDiskStats>> vmDiskStatsById = new HashMap<>();
        if (MapUtils.isEmpty(vmInstanceNameIdMap)) {
            return vmDiskStatsById;
        }
        Answer answer = _agentMgr.easySend(host.getId(), new GetVmDiskStatsCommand(
                new ArrayList<>(vmInstanceNameIdMap.keySet()), host.getGuid(), host.getName()));
        if (answer == null || !answer.getResult()) {
            logger.warn("Unable to obtain VM disk statistics.");
            return vmDiskStatsById;
        } else {
            HashMap<String, List<VmDiskStatsEntry>> vmDiskStatsByName = ((GetVmDiskStatsAnswer)answer).getVmDiskStatsMap();
            if (vmDiskStatsByName == null) {
                logger.warn("Unable to obtain VM disk statistics.");
                return vmDiskStatsById;
            }
            for (Map.Entry<String, List<VmDiskStatsEntry>> entry: vmDiskStatsByName.entrySet()) {
                vmDiskStatsById.put(vmInstanceNameIdMap.get(entry.getKey()), entry.getValue());
            }
        }
        return vmDiskStatsById;
    }

    @Override
    public HashMap<Long, List<? extends VmNetworkStats>> getVmNetworkStatistics(Host host, Map<String, Long> vmInstanceNameIdMap) {
        HashMap<Long, List<? extends VmNetworkStats>> vmNetworkStatsById = new HashMap<>();
        if (MapUtils.isEmpty(vmInstanceNameIdMap)) {
            return vmNetworkStatsById;
        }
        Answer answer = _agentMgr.easySend(host.getId(), new GetVmNetworkStatsCommand(
                new ArrayList<>(vmInstanceNameIdMap.keySet()), host.getGuid(), host.getName()));
        if (answer == null || !answer.getResult()) {
            logger.warn("Unable to obtain VM network statistics.");
            return vmNetworkStatsById;
        } else {
            HashMap<String, List<VmNetworkStatsEntry>> vmNetworkStatsByName = ((GetVmNetworkStatsAnswer)answer).getVmNetworkStatsMap();
            if (vmNetworkStatsByName == null) {
                logger.warn("Unable to obtain VM network statistics.");
                return vmNetworkStatsById;
            }
            for (Map.Entry<String, List<VmNetworkStatsEntry>> entry: vmNetworkStatsByName.entrySet()) {
                vmNetworkStatsById.put(vmInstanceNameIdMap.get(entry.getKey()), entry.getValue());
            }
        }
        return vmNetworkStatsById;
    }

    protected boolean isDiskOfferingSuitableForVm(VMInstanceVO vm, VirtualMachineProfile profile, long podId, long clusterId, long hostId, long diskOfferingId) {

        DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
        VolumeVO dummyVolume = new VolumeVO("Data", vm.getDataCenterId(), podId, vm.getAccountId(),
                vm.getDomainId(), vm.getId(), null, null, diskOffering.getProvisioningType(), diskOffering.getDiskSize(), Type.DATADISK);
        try {
            Field idField = dummyVolume.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(dummyVolume, Volume.DISK_OFFERING_SUITABILITY_CHECK_VOLUME_ID);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return false;
        }
        dummyVolume.setDiskOfferingId(diskOfferingId);
        DiskProfile diskProfile = new DiskProfile(dummyVolume, diskOffering, profile.getHypervisorType());
        diskProfile.setMinIops(diskOffering.getMinIops());
        diskProfile.setMaxIops(diskOffering.getMaxIops());
        ExcludeList avoid = new ExcludeList();
        DataCenterDeployment plan = new DataCenterDeployment(vm.getDataCenterId(), podId, clusterId, hostId, null, null);
        for (StoragePoolAllocator allocator : _storagePoolAllocators) {
            List<StoragePool> poolListFromAllocator = allocator.allocateToPool(diskProfile, profile, plan, avoid, 1);
            if (CollectionUtils.isNotEmpty(poolListFromAllocator)) {
                logger.debug("Found a suitable pool: {} for disk offering: {}", poolListFromAllocator.get(0).getName(), diskOffering.getName());
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<Long, Boolean> getDiskOfferingSuitabilityForVm(long vmId, List<Long> diskOfferingIds) {
        VMInstanceVO vm = _vmDao.findById(vmId);
        if (vmInstanceDetailsDao.findDetail(vm.getId(), VmDetailConstants.DEPLOY_VM) != null) {
            return new HashMap<>();
        }
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        Pair<Long, Long> clusterAndHost = findClusterAndHostIdForVm(vm, false);
        Long clusterId = clusterAndHost.first();
        Cluster cluster = _clusterDao.findById(clusterId);
        Map<Long, Boolean> result = new HashMap<>();
        for (Long diskOfferingId : diskOfferingIds) {
            result.put(diskOfferingId, isDiskOfferingSuitableForVm(vm, profile, cluster.getPodId(), clusterId, clusterAndHost.second(), diskOfferingId));
        }
        return result;
    }

    @Override
    public void checkDeploymentPlan(VirtualMachine virtualMachine, VirtualMachineTemplate template,
            ServiceOffering serviceOffering, Account systemAccount, DeploymentPlan plan)
            throws InsufficientServerCapacityException {
        final VirtualMachineProfileImpl vmProfile =
                new VirtualMachineProfileImpl(virtualMachine, template, serviceOffering, systemAccount, null);
        DeployDestination destination =
                _dpMgr.planDeployment(vmProfile, plan, new DeploymentPlanner.ExcludeList(), null);
        if (destination == null) {
            throw new InsufficientServerCapacityException(String.format("Unable to create a deployment for %s",
                    vmProfile), DataCenter.class, plan.getDataCenterId(), areAffinityGroupsAssociated(vmProfile));
        }
    }
}
