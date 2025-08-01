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

import static com.cloud.hypervisor.Hypervisor.HypervisorType.Functionality;
import static com.cloud.storage.Volume.IOPS_LIMIT;
import static com.cloud.utils.NumbersUtil.toHumanReadableSize;
import static org.apache.cloudstack.api.ApiConstants.MAX_IOPS;
import static org.apache.cloudstack.api.ApiConstants.MIN_IOPS;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.affinity.AffinityGroupService;
import org.apache.cloudstack.affinity.AffinityGroupVMMapVO;
import org.apache.cloudstack.affinity.AffinityGroupVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.api.command.admin.vm.AssignVMCmd;
import org.apache.cloudstack.api.command.admin.vm.DeployVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.ExpungeVMCmd;
import org.apache.cloudstack.api.command.admin.vm.RecoverVMCmd;
import org.apache.cloudstack.api.command.user.vm.AddNicToVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVnfApplianceCmd;
import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.api.command.user.vm.RebootVMCmd;
import org.apache.cloudstack.api.command.user.vm.RemoveNicFromVMCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMSSHKeyCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMUserDataCmd;
import org.apache.cloudstack.api.command.user.vm.RestoreVMCmd;
import org.apache.cloudstack.api.command.user.vm.ScaleVMCmd;
import org.apache.cloudstack.api.command.user.vm.SecurityGroupAction;
import org.apache.cloudstack.api.command.user.vm.StartVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateDefaultNicForVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicIpCmd;
import org.apache.cloudstack.api.command.user.vm.UpgradeVMCmd;
import org.apache.cloudstack.api.command.user.vmgroup.CreateVMGroupCmd;
import org.apache.cloudstack.api.command.user.vmgroup.DeleteVMGroupCmd;
import org.apache.cloudstack.api.command.user.volume.ChangeOfferingForVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.cloud.entity.api.VirtualMachineEntity;
import org.apache.cloudstack.engine.cloud.entity.api.db.dao.VMNetworkMapDao;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.service.api.OrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.query.QueryService;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.template.VnfTemplateManager;
import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.cloudstack.utils.bytescale.ByteScaleUtils;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.apache.cloudstack.vm.UnmanagedVMsManager;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.cloudstack.vm.schedule.VMScheduleManager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.GetVmDiskStatsAnswer;
import com.cloud.agent.api.GetVmDiskStatsCommand;
import com.cloud.agent.api.GetVmIpAddressCommand;
import com.cloud.agent.api.GetVmNetworkStatsAnswer;
import com.cloud.agent.api.GetVmNetworkStatsCommand;
import com.cloud.agent.api.GetVolumeStatsAnswer;
import com.cloud.agent.api.GetVolumeStatsCommand;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.PvlanSetupCommand;
import com.cloud.agent.api.RestoreVMSnapshotAnswer;
import com.cloud.agent.api.RestoreVMSnapshotCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.VmDiskStatsEntry;
import com.cloud.agent.api.VmNetworkStatsEntry;
import com.cloud.agent.api.VolumeStatsEntry;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.api.to.deployasis.OVFNetworkTO;
import com.cloud.agent.api.to.deployasis.OVFPropertyTO;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.ServiceOfferingJoinDao;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityManager;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.deploy.PlannerHostReservationVO;
import com.cloud.deploy.dao.PlannerHostReservationDao;
import com.cloud.deployasis.UserVmDeployAsIsDetailVO;
import com.cloud.deployasis.dao.TemplateDeployAsIsDetailsDao;
import com.cloud.deployasis.dao.UserVmDeployAsIsDetailsDao;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.event.UsageEventVO;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.CloudException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.gpu.GPU;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.kubernetes.cluster.KubernetesServiceHelper;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.IpAddresses;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.as.AutoScaleManager;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.router.CommandSetupHelper;
import com.cloud.network.router.NetworkHelper;
import com.cloud.network.router.VpcVirtualNetworkApplianceManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.security.SecurityGroup;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.network.security.SecurityGroupService;
import com.cloud.network.security.dao.SecurityGroupDao;
import com.cloud.network.vpc.VpcManager;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.org.Cluster;
import com.cloud.org.Grouping;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.server.ManagementService;
import com.cloud.server.ResourceTag;
import com.cloud.server.StatsCollector;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOSCategoryVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.Storage.TemplateType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.template.TemplateApiService;
import com.cloud.template.TemplateManager;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.User;
import com.cloud.user.UserData;
import com.cloud.user.UserStatisticsVO;
import com.cloud.user.UserVO;
import com.cloud.user.VmDiskStatisticsVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.user.dao.UserDao;
import com.cloud.user.dao.UserDataDao;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.user.dao.VmDiskStatisticsDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Journal;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.crypt.RSAHelper;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.db.UUIDManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionProxyObject;
import com.cloud.utils.exception.ExecutionException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.InstanceGroupDao;
import com.cloud.vm.dao.InstanceGroupVMMapDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicExtraDhcpOptionDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.dao.VmStatsDao;
import com.cloud.vm.snapshot.VMSnapshotManager;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;


public class UserVmManagerImpl extends ManagerBase implements UserVmManager, VirtualMachineGuru, Configurable {

    /**
     * The number of seconds to wait before timing out when trying to acquire a global lock.
     */
    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 3;

    private static final long GiB_TO_BYTES = 1024 * 1024 * 1024;

    @Inject
    private EntityManager _entityMgr;
    @Inject
    private HostDao _hostDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    private VMTemplateDao _templateDao;
    @Inject
    private VMTemplateZoneDao _templateZoneDao;
    @Inject
    protected TemplateDataStoreDao _templateStoreDao;
    @Inject
    private DomainDao _domainDao;
    @Inject
    private UserVmDao _vmDao;
    @Inject
    private VolumeDao _volsDao;
    @Inject
    private DataCenterDao _dcDao;
    @Inject
    private FirewallRulesDao _rulesDao;
    @Inject
    private LoadBalancerVMMapDao _loadBalancerVMMapDao;
    @Inject
    private PortForwardingRulesDao _portForwardingDao;
    @Inject
    private IPAddressDao _ipAddressDao;
    @Inject
    private HostPodDao _podDao;
    @Inject
    private NetworkModel _networkModel;
    @Inject
    private NetworkOrchestrationService _networkMgr;
    @Inject
    private AgentManager _agentMgr;
    @Inject
    private ConfigurationManager _configMgr;
    @Inject
    private AccountDao _accountDao;
    @Inject
    private UserDao _userDao;
    @Inject
    private SnapshotDao _snapshotDao;
    @Inject
    private GuestOSDao _guestOSDao;
    @Inject
    private HighAvailabilityManager _haMgr;
    @Inject
    private AlertManager _alertMgr;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private AccountService _accountService;
    @Inject
    private ClusterDao _clusterDao;
    @Inject
    private PrimaryDataStoreDao _storagePoolDao;
    @Inject
    private SecurityGroupManager _securityGroupMgr;
    @Inject
    private NetworkOfferingDao _networkOfferingDao;
    @Inject
    private InstanceGroupDao _vmGroupDao;
    @Inject
    private InstanceGroupVMMapDao _groupVMMapDao;
    @Inject
    private VirtualMachineManager _itMgr;
    @Inject
    private NetworkDao _networkDao;
    @Inject
    private NicDao _nicDao;
    @Inject
    private RulesManager _rulesMgr;
    @Inject
    private LoadBalancingRulesManager _lbMgr;
    @Inject
    private SSHKeyPairDao _sshKeyPairDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private HypervisorCapabilitiesDao _hypervisorCapabilitiesDao;
    @Inject
    private SecurityGroupDao _securityGroupDao;
    @Inject
    private CapacityManager _capacityMgr;
    @Inject
    private VMInstanceDao _vmInstanceDao;
    @Inject
    private ResourceLimitService _resourceLimitMgr;
    @Inject
    private FirewallManager _firewallMgr;
    @Inject
    private ResourceManager _resourceMgr;
    @Inject
    private NetworkServiceMapDao _ntwkSrvcDao;
    @Inject
    private PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    private VpcManager _vpcMgr;
    @Inject
    private TemplateManager _templateMgr;
    @Inject
    private GuestOSCategoryDao _guestOSCategoryDao;
    @Inject
    private UsageEventDao _usageEventDao;
    @Inject
    private VmDiskStatisticsDao _vmDiskStatsDao;
    @Inject
    private VMSnapshotDao _vmSnapshotDao;
    @Inject
    private VMSnapshotManager _vmSnapshotMgr;
    @Inject
    private AffinityGroupVMMapDao _affinityGroupVMMapDao;
    @Inject
    private AffinityGroupDao _affinityGroupDao;
    @Inject
    private DedicatedResourceDao _dedicatedDao;
    @Inject
    private AffinityGroupService _affinityGroupService;
    @Inject
    private PlannerHostReservationDao _plannerHostReservationDao;
    @Inject
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Inject
    private UserStatisticsDao _userStatsDao;
    @Inject
    private VlanDao _vlanDao;
    @Inject
    private VolumeService _volService;
    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private UUIDManager _uuidMgr;
    @Inject
    private DeploymentPlanningManager _planningMgr;
    @Inject
    private VolumeApiService _volumeService;
    @Inject
    private DataStoreManager _dataStoreMgr;
    @Inject
    private VpcVirtualNetworkApplianceManager _virtualNetAppliance;
    @Inject
    private DomainRouterDao _routerDao;
    @Inject
    private VMNetworkMapDao _vmNetworkMapDao;
    @Inject
    private IpAddressManager _ipAddrMgr;
    @Inject
    private NicExtraDhcpOptionDao _nicExtraDhcpOptionDao;
    @Inject
    private TemplateApiService _tmplService;
    @Inject
    private ConfigurationDao _configDao;
    @Inject
    private DpdkHelper dpdkHelper;
    @Inject
    private ResourceTagDao resourceTagDao;
    @Inject
    private TemplateDeployAsIsDetailsDao templateDeployAsIsDetailsDao;
    @Inject
    private UserVmDeployAsIsDetailsDao userVmDeployAsIsDetailsDao;
    @Inject
    private DataStoreProviderManager _dataStoreProviderMgr;
    @Inject
    private StorageManager storageManager;
    @Inject
    private ServiceOfferingJoinDao serviceOfferingJoinDao;
    @Inject
    private BackupDao backupDao;
    @Inject
    private BackupManager backupManager;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    private VmStatsDao vmStatsDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private MessageBus messageBus;
    @Inject
    protected CommandSetupHelper commandSetupHelper;
    @Autowired
    @Qualifier("networkHelper")
    protected NetworkHelper nwHelper;
    @Inject
    ReservationDao reservationDao;
    @Inject
    ResourceLimitService resourceLimitService;

    @Inject
    private StatsCollector statsCollector;
    @Inject
    private UserDataDao userDataDao;

    @Inject
    protected SnapshotHelper snapshotHelper;

    @Inject
    private AutoScaleManager autoScaleManager;

    @Inject
    VMScheduleManager vmScheduleManager;
    @Inject
    NsxProviderDao nsxProviderDao;

    @Inject
    NetworkService networkService;

    @Inject
    SnapshotDataFactory snapshotDataFactory;

    private ScheduledExecutorService _executor = null;
    private ScheduledExecutorService _vmIpFetchExecutor = null;
    private int _expungeInterval;
    private int _expungeDelay;
    private boolean _dailyOrHourly = false;
    private int capacityReleaseInterval;
    private ExecutorService _vmIpFetchThreadExecutor;
    private List<KubernetesServiceHelper> kubernetesServiceHelpers;


    private String _instance;
    private boolean _instanceNameFlag;
    private int _scaleRetry;
    private Map<Long, VmAndCountDetails> vmIdCountMap = new ConcurrentHashMap<>();

    protected static long ROOT_DEVICE_ID = 0;

    private static final int MAX_HTTP_GET_LENGTH = 2 * MAX_USER_DATA_LENGTH_BYTES;
    private static final int NUM_OF_2K_BLOCKS = 512;
    private static final int MAX_HTTP_POST_LENGTH = NUM_OF_2K_BLOCKS * MAX_USER_DATA_LENGTH_BYTES;

    public List<KubernetesServiceHelper> getKubernetesServiceHelpers() {
        return kubernetesServiceHelpers;
    }

    public void setKubernetesServiceHelpers(final List<KubernetesServiceHelper> kubernetesServiceHelpers) {
        this.kubernetesServiceHelpers = kubernetesServiceHelpers;
    }

    @Inject
    private OrchestrationService _orchSrvc;

    @Inject
    private VolumeOrchestrationService volumeMgr;

    @Inject
    private ManagementService _mgr;

    @Inject
    private UserDataManager userDataManager;

    @Inject
    VnfTemplateManager vnfTemplateManager;

    private static final ConfigKey<Integer> VmIpFetchWaitInterval = new ConfigKey<Integer>("Advanced", Integer.class, "externaldhcp.vmip.retrieval.interval", "180",
            "Wait Interval (in seconds) for shared network vm dhcp ip addr fetch for next iteration ", true);

    private static final ConfigKey<Integer> VmIpFetchTrialMax = new ConfigKey<Integer>("Advanced", Integer.class, "externaldhcp.vmip.max.retry", "10",
            "The max number of retrieval times for shared entwork vm dhcp ip fetch, in case of failures", true);

    private static final ConfigKey<Integer> VmIpFetchThreadPoolMax = new ConfigKey<Integer>("Advanced", Integer.class, "externaldhcp.vmipFetch.threadPool.max", "10",
            "number of threads for fetching vms ip address", true);

    private static final ConfigKey<Integer> VmIpFetchTaskWorkers = new ConfigKey<Integer>("Advanced", Integer.class, "externaldhcp.vmipfetchtask.workers", "10",
            "number of worker threads for vm ip fetch task ", true);

    private static final ConfigKey<Boolean> AllowDeployVmIfGivenHostFails = new ConfigKey<Boolean>("Advanced", Boolean.class, "allow.deploy.vm.if.deploy.on.given.host.fails", "false",
            "allow vm to deploy on different host if vm fails to deploy on the given host ", true);

    private static final ConfigKey<Boolean> EnableAdditionalVmConfig = new ConfigKey<>("Advanced", Boolean.class,
            "enable.additional.vm.configuration", "false", "allow additional arbitrary configuration to vm", true, ConfigKey.Scope.Account);

    private static final ConfigKey<String> KvmAdditionalConfigAllowList = new ConfigKey<>(String.class,
    "allow.additional.vm.configuration.list.kvm", "Advanced", "", "Comma separated list of allowed additional configuration options.", true, ConfigKey.Scope.Account, null, null, EnableAdditionalVmConfig.key(), null, null, ConfigKey.Kind.CSV, null);

    private static final ConfigKey<String> XenServerAdditionalConfigAllowList = new ConfigKey<>(String.class,
    "allow.additional.vm.configuration.list.xenserver", "Advanced", "", "Comma separated list of allowed additional configuration options", true, ConfigKey.Scope.Global, null, null, EnableAdditionalVmConfig.key(), null, null, ConfigKey.Kind.CSV, null);

    private static final ConfigKey<String> VmwareAdditionalConfigAllowList = new ConfigKey<>(String.class,
    "allow.additional.vm.configuration.list.vmware", "Advanced", "", "Comma separated list of allowed additional configuration options.", true, ConfigKey.Scope.Global, null, null, EnableAdditionalVmConfig.key(), null, null, ConfigKey.Kind.CSV, null);

    private static final ConfigKey<Boolean> VmDestroyForcestop = new ConfigKey<Boolean>("Advanced", Boolean.class, "vm.destroy.forcestop", "false",
            "On destroy, force-stop takes this value ", true);

    @Override
    public UserVmVO getVirtualMachine(long vmId) {
        return _vmDao.findById(vmId);
    }

    @Override
    public List<? extends UserVm> getVirtualMachines(long hostId) {
        return _vmDao.listByHostId(hostId);
    }

    protected void resourceCountIncrement(long accountId, Boolean displayVm, ServiceOffering serviceOffering, VirtualMachineTemplate template) {
        if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            _resourceLimitMgr.incrementVmResourceCount(accountId, displayVm, serviceOffering, template);
        }
    }

    protected void resourceCountDecrement(long accountId, Boolean displayVm, ServiceOffering serviceOffering, VirtualMachineTemplate template) {
        if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            _resourceLimitMgr.decrementVmResourceCount(accountId, displayVm, serviceOffering, template);
        }
    }

    public class VmAndCountDetails {
        long vmId;
        int  retrievalCount = VmIpFetchTrialMax.value();


        public VmAndCountDetails() {
        }

        public VmAndCountDetails (long vmId, int retrievalCount) {
            this.vmId = vmId;
            this.retrievalCount = retrievalCount;
        }

        public VmAndCountDetails (long vmId) {
            this.vmId = vmId;
        }

        public int getRetrievalCount() {
            return retrievalCount;
        }

        public void setRetrievalCount(int retrievalCount) {
            this.retrievalCount = retrievalCount;
        }

        public long getVmId() {
            return vmId;
        }

        public void setVmId(long vmId) {
            this.vmId = vmId;
        }

        public void decrementCount() {
            this.retrievalCount--;

        }
    }

    private class VmIpAddrFetchThread extends ManagedContextRunnable {
        long nicId;
        long vmId;
        String vmName;
        String vmUuid;
        boolean isWindows;
        Long hostId;
        String networkCidr;
        String macAddress;

        public VmIpAddrFetchThread(long vmId, long nicId, String instanceName, boolean windows, Long hostId, String networkCidr, String macAddress) {
            this.vmId = vmId;
            this.vmUuid = vmUuid;
            this.nicId = nicId;
            this.vmName = instanceName;
            this.isWindows = windows;
            this.hostId = hostId;
            this.networkCidr = networkCidr;
            this.macAddress = macAddress;
        }

        @Override
        protected void runInContext() {
            GetVmIpAddressCommand cmd = new GetVmIpAddressCommand(vmName, networkCidr, isWindows, macAddress);
            boolean decrementCount = true;

            NicVO nic = _nicDao.findById(nicId);
            try {
                logger.debug("Trying IP retrieval for VM [id: {}, uuid: {}, name: {}], nic {}", vmId, vmUuid, vmName, nic);
                Answer answer = _agentMgr.send(hostId, cmd);
                if (answer.getResult()) {
                    String vmIp = answer.getDetails();

                    if (NetUtils.isValidIp4(vmIp)) {
                        // set this vm ip addr in vm nic.
                        if (nic != null) {
                            nic.setIPv4Address(vmIp);
                            _nicDao.update(nicId, nic);
                            logger.debug("Vm [id: {}, uuid: {}, name: {}] - IP {} retrieved successfully", vmId, vmUuid, vmName, vmIp);
                            vmIdCountMap.remove(nicId);
                            decrementCount = false;
                            ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM,
                                    Domain.ROOT_DOMAIN, EventTypes.EVENT_NETWORK_EXTERNAL_DHCP_VM_IPFETCH,
                                    String.format("VM [id: %d, uuid: %s, name: %s], nic %s, IP address %s got fetched successfully",
                                            vmId, vmUuid, vmName, nic, vmIp), vmId, ApiCommandResourceType.VirtualMachine.toString());
                        }
                    }
                } else {
                    //previously vm has ip and nic table has ip address. After vm restart or stop/start
                    //if vm doesnot get the ip then set the ip in nic table to null
                    if (nic.getIPv4Address() != null) {
                        nic.setIPv4Address(null);
                        _nicDao.update(nicId, nic);
                    }
                    if (answer.getDetails() != null) {
                        logger.debug("Failed to get vm ip for Vm [id: {}, uuid: {}, name: {}], details: {}",
                                vmId, vmUuid, vmName, answer.getDetails());
                    }
                }
            } catch (OperationTimedoutException e) {
                logger.warn("Timed Out", e);
            } catch (AgentUnavailableException e) {
                logger.warn("Agent Unavailable ", e);
            } finally {
                if (decrementCount) {
                    VmAndCountDetails vmAndCount = vmIdCountMap.get(nicId);
                    vmAndCount.decrementCount();
                    logger.debug("Ip is not retrieved for VM [id: {}, uuid: {}, name: {}] nic {} ... decremented count to {}",
                            vmId, vmUuid, vmName, nic, vmAndCount.getRetrievalCount());
                    vmIdCountMap.put(nicId, vmAndCount);
                }
            }
        }
    }

    private void addVmUefiBootOptionsToParams(Map<VirtualMachineProfile.Param, Object> params, String bootType, String bootMode) {
        if (logger.isTraceEnabled()) {
            logger.trace(String.format("Adding boot options (%s, %s, %s) into the param map for VM start as UEFI detail(%s=%s) found for the VM",
                    VirtualMachineProfile.Param.UefiFlag.getName(),
                    VirtualMachineProfile.Param.BootType.getName(),
                    VirtualMachineProfile.Param.BootMode.getName(),
                    bootType,
                    bootMode));
        }
        params.put(VirtualMachineProfile.Param.UefiFlag, "Yes");
        params.put(VirtualMachineProfile.Param.BootType, bootType);
        params.put(VirtualMachineProfile.Param.BootMode, bootMode);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_RESETPASSWORD, eventDescription = "resetting Vm password", async = true)
    public UserVm resetVMPassword(ResetVMPasswordCmd cmd, String password) throws ResourceUnavailableException, InsufficientCapacityException {
        Account caller = CallContext.current().getCallingAccount();
        Long vmId = cmd.getId();
        UserVmVO userVm = _vmDao.findById(cmd.getId());

        // Do parameters input validation
        if (userVm == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + cmd.getId());
        }

        _vmDao.loadDetails(userVm);

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(userVm.getTemplateId());
        if (template == null || !template.isEnablePassword()) {
            throw new InvalidParameterValueException("Fail to reset password for the virtual machine, the template is not password enabled");
        }

        if (userVm.getState() == State.Error || userVm.getState() == State.Expunging) {
            logger.error("vm is not in the right state: {}", userVm);
            throw new InvalidParameterValueException(String.format("Vm %s is not in the right state", userVm));
        }

        if (userVm.getState() != State.Stopped) {
            logger.error("vm is not in the right state: {}", userVm);
            throw new InvalidParameterValueException(String.format("Vm %s should be stopped to do password reset", userVm));
        }

        _accountMgr.checkAccess(caller, null, true, userVm);

        boolean result = resetVMPasswordInternal(vmId, password);

        if (result) {
            userVm.setPassword(password);
        } else {
            throw new CloudRuntimeException("Failed to reset password for the virtual machine ");
        }

        return userVm;
    }

    private boolean resetVMPasswordInternal(Long vmId, String password) throws ResourceUnavailableException, InsufficientCapacityException {
        Long userId = CallContext.current().getCallingUserId();
        VMInstanceVO vmInstance = _vmDao.findById(vmId);

        if (password == null || password.equals("")) {
            return false;
        }

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
        if (template.isEnablePassword()) {
            Nic defaultNic = _networkModel.getDefaultNic(vmId);
            if (defaultNic == null) {
                logger.error("Unable to reset password for vm " + vmInstance + " as the instance doesn't have default nic");
                return false;
            }

            Network defaultNetwork = _networkDao.findById(defaultNic.getNetworkId());
            NicProfile defaultNicProfile = new NicProfile(defaultNic, defaultNetwork, null, null, null, _networkModel.isSecurityGroupSupportedInNetwork(defaultNetwork),
                    _networkModel.getNetworkTag(template.getHypervisorType(), defaultNetwork));
            VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vmInstance);
            vmProfile.setParameter(VirtualMachineProfile.Param.VmPassword, password);

            UserDataServiceProvider element = _networkMgr.getPasswordResetProvider(defaultNetwork);
            if (element == null) {
                throw new CloudRuntimeException("Can't find network element for " + Service.UserData.getName() + " provider needed for password reset");
            }

            boolean result = element.savePassword(defaultNetwork, defaultNicProfile, vmProfile);

            // Need to reboot the virtual machine so that the password gets
            // redownloaded from the DomR, and reset on the VM
            if (!result) {
                logger.debug("Failed to reset password for the virtual machine; no need to reboot the vm");
                return false;
            } else {
                final UserVmVO userVm = _vmDao.findById(vmId);
                _vmDao.loadDetails(userVm);
                // update the password in vm_details table too
                // Check if an SSH key pair was selected for the instance and if so
                // use it to encrypt & save the vm password
                encryptAndStorePassword(userVm, password);

                if (vmInstance.getState() == State.Stopped) {
                    logger.debug("Vm " + vmInstance + " is stopped, not rebooting it as a part of password reset");
                    return true;
                }

                if (rebootVirtualMachine(userId, vmId, false, false) == null) {
                    logger.warn("Failed to reboot the vm " + vmInstance);
                    return false;
                } else {
                    logger.debug("Vm " + vmInstance + " is rebooted successfully as a part of password reset");
                    return true;
                }
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Reset password called for a vm that is not using a password enabled template");
            }
            return false;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_RESETUSERDATA, eventDescription = "resetting VM userdata", async = true)
    public UserVm resetVMUserData(ResetVMUserDataCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException {

        Account caller = CallContext.current().getCallingAccount();
        Long vmId = cmd.getId();
        UserVmVO userVm = _vmDao.findById(cmd.getId());

        if (userVm == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine by id" + cmd.getId());
        }
        if (UserVmManager.SHAREDFSVM.equals(userVm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }
        if (Hypervisor.HypervisorType.External.equals(userVm.getHypervisorType())) {
            logger.error("Reset VM userdata not supported for {} as it is {} hypervisor instance",
                    userVm, Hypervisor.HypervisorType.External.name());
            throw new InvalidParameterValueException(String.format("Operation not supported for instance: %s",
                    userVm.getName()));
        }
        _accountMgr.checkAccess(caller, null, true, userVm);

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(userVm.getTemplateId());

        // Do parameters input validation

        if (userVm.getState() != State.Stopped) {
            logger.error("vm ({}) should be stopped to do UserData reset. current state: {}", userVm, userVm.getState());
            throw new InvalidParameterValueException(String.format("VM %s should be stopped to do UserData reset", userVm));
        }

        String userData = cmd.getUserData();
        Long userDataId = cmd.getUserdataId();
        String userDataDetails = null;
        if (MapUtils.isNotEmpty(cmd.getUserdataDetails())) {
            userDataDetails = cmd.getUserdataDetails().toString();
        }
        userData = finalizeUserData(userData, userDataId, template);
        userData = userDataManager.validateUserData(userData, cmd.getHttpMethod());

        userVm.setUserDataId(userDataId);
        userVm.setUserData(userData);
        userVm.setUserDataDetails(userDataDetails);
        _vmDao.update(userVm.getId(), userVm);

        updateUserData(userVm);

        UserVmVO vm = _vmDao.findById(vmId);
        _vmDao.loadDetails(vm);
        return vm;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_RESETSSHKEY, eventDescription = "resetting Vm SSHKey", async = true)
    public UserVm resetVMSSHKey(ResetVMSSHKeyCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException {

        Account caller = CallContext.current().getCallingAccount();
        Account owner = _accountMgr.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
        Long vmId = cmd.getId();
        UserVmVO userVm = _vmDao.findById(cmd.getId());

        if (userVm == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine by id" + cmd.getId());
        }
        if (UserVmManager.SHAREDFSVM.equals(userVm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }
        if (Hypervisor.HypervisorType.External.equals(userVm.getHypervisorType())) {
            logger.error("Reset VM SSH key not supported for {} as it is {} hypervisor instance",
                    userVm, Hypervisor.HypervisorType.External.name());
            throw new InvalidParameterValueException(String.format("Operation not supported for instance: %s",
                    userVm.getName()));
        }

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(userVm.getTemplateId());

        // Do parameters input validation

        if (userVm.getState() == State.Error || userVm.getState() == State.Expunging) {
            logger.error("vm ({}) is not in the right state: {}", userVm, userVm.getState());
            throw new InvalidParameterValueException("Vm with specified id is not in the right state");
        }
        if (userVm.getState() != State.Stopped) {
            logger.error(String.format("vm (%s) is not in the stopped state. current state: %s", userVm, userVm.getState()));
            throw new InvalidParameterValueException("Vm " + userVm + " should be stopped to do SSH Key reset");
        }

        if (cmd.getNames() == null || cmd.getNames().isEmpty()) {
            throw new InvalidParameterValueException("'keypair' or 'keypairs' must be specified");
        }

        String keypairnames = "";
        String sshPublicKeys = "";
        List<SSHKeyPairVO> pairs = new ArrayList<>();

        pairs = _sshKeyPairDao.findByNames(owner.getAccountId(), owner.getDomainId(), cmd.getNames());
        if (pairs == null || pairs.size() != cmd.getNames().size()) {
            throw new InvalidParameterValueException("Not all specified keypairs exist");
        }
        sshPublicKeys = pairs.stream().map(p -> p.getPublicKey()).collect(Collectors.joining("\n"));
        keypairnames = String.join(",", cmd.getNames());

        _accountMgr.checkAccess(caller, null, true, userVm);

        boolean result = resetVMSSHKeyInternal(vmId, sshPublicKeys, keypairnames);

        UserVmVO vm = _vmDao.findById(vmId);
        _vmDao.loadDetails(vm);
        if (!result) {
            throw new CloudRuntimeException("Failed to reset SSH Key for the virtual machine ");
        }

        removeEncryptedPasswordFromUserVmVoDetails(vmId);

        _vmDao.loadDetails(userVm);
        return userVm;
    }

    protected void removeEncryptedPasswordFromUserVmVoDetails(long vmId) {
        vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.ENCRYPTED_PASSWORD);
    }

    private boolean resetVMSSHKeyInternal(Long vmId, String sshPublicKeys, String keypairnames) throws ResourceUnavailableException, InsufficientCapacityException {
        Long userId = CallContext.current().getCallingUserId();
        VMInstanceVO vmInstance = _vmDao.findById(vmId);

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
        Nic defaultNic = _networkModel.getDefaultNic(vmId);
        if (defaultNic == null) {
            logger.error("Unable to reset SSH Key for vm " + vmInstance + " as the instance doesn't have default nic");
            return false;
        }

        Network defaultNetwork = _networkDao.findById(defaultNic.getNetworkId());
        NicProfile defaultNicProfile = new NicProfile(defaultNic, defaultNetwork, null, null, null, _networkModel.isSecurityGroupSupportedInNetwork(defaultNetwork),
                _networkModel.getNetworkTag(template.getHypervisorType(), defaultNetwork));

        VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vmInstance);

        UserDataServiceProvider element = _networkMgr.getSSHKeyResetProvider(defaultNetwork);
        if (element == null) {
            throw new CloudRuntimeException("Can't find network element for " + Service.UserData.getName() + " provider needed for SSH Key reset");
        }
        boolean result = element.saveSSHKey(defaultNetwork, defaultNicProfile, vmProfile, sshPublicKeys);
        // Need to reboot the virtual machine so that the password gets redownloaded from the DomR, and reset on the VM
        if (!result) {
            logger.debug("Failed to reset SSH Key for the virtual machine; no need to reboot the vm");
            return false;
        } else {
            final UserVmVO userVm = _vmDao.findById(vmId);
            _vmDao.loadDetails(userVm);
            userVm.setDetail(VmDetailConstants.SSH_PUBLIC_KEY, sshPublicKeys);
            userVm.setDetail(VmDetailConstants.SSH_KEY_PAIR_NAMES, keypairnames);
            _vmDao.saveDetails(userVm);

            if (vmInstance.getState() == State.Stopped) {
                logger.debug("Vm " + vmInstance + " is stopped, not rebooting it as a part of SSH Key reset");
                return true;
            }
            if (rebootVirtualMachine(userId, vmId, false, false) == null) {
                logger.warn("Failed to reboot the vm " + vmInstance);
                return false;
            } else {
                logger.debug("Vm " + vmInstance + " is rebooted successfully as a part of SSH Key reset");
                return true;
            }
        }
    }

    @Override
    public boolean stopVirtualMachine(long userId, long vmId) {
        boolean status = false;
        UserVmVO vm = _vmDao.findById(vmId);
        if (logger.isDebugEnabled()) {
            logger.debug("Stopping vm {} with id {}", vm, vmId);
        }
        if (vm == null || vm.getRemoved() != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("VM is either removed or deleted.");
            }
            return true;
        }

        _userDao.findById(userId);
        try {
            VirtualMachineEntity vmEntity = _orchSrvc.getVirtualMachine(vm.getUuid());
            status = vmEntity.stop(Long.toString(userId));
        } catch (ResourceUnavailableException e) {
            logger.debug("Unable to stop due to ", e);
            status = false;
        } catch (CloudException e) {
            throw new CloudRuntimeException("Unable to contact the agent to stop the virtual machine " + vm, e);
        }
        return status;
    }

    private UserVm rebootVirtualMachine(long userId, long vmId, boolean enterSetup, boolean forced) throws InsufficientCapacityException, ResourceUnavailableException {
        UserVmVO vm = _vmDao.findById(vmId);

        if (logger.isTraceEnabled()) {
            logger.trace("reboot {} with enterSetup set to {}", vm, Boolean.toString(enterSetup));
        }

        if (vm == null || vm.getState() == State.Destroyed || vm.getState() == State.Expunging || vm.getRemoved() != null) {
            logger.warn("Vm {} with id={} doesn't exist or is not in correct state", vm, vmId);
            return null;
        }

        if (vm.getState() == State.Running && vm.getHostId() != null) {
            collectVmDiskAndNetworkStatistics(vm, State.Running);

            if (forced) {
                Host vmOnHost = _hostDao.findById(vm.getHostId());
                if (vmOnHost == null || vmOnHost.getResourceState() != ResourceState.Enabled || vmOnHost.getStatus() != Status.Up ) {
                    throw new CloudRuntimeException("Unable to force reboot the VM as the host: " + vm.getHostId() + " is not in the right state");
                }
                return forceRebootVirtualMachine(vm, vm.getHostId(), enterSetup);
            }

            DataCenterVO dc = _dcDao.findById(vm.getDataCenterId());
            try {
                if (dc.getNetworkType() == DataCenter.NetworkType.Advanced) {
                    //List all networks of vm
                    List<Long> vmNetworks = _vmNetworkMapDao.getNetworks(vmId);
                    List<DomainRouterVO> routers = new ArrayList<DomainRouterVO>();
                    //List the stopped routers
                    for(long vmNetworkId : vmNetworks) {
                        List<DomainRouterVO> router = _routerDao.listStopped(vmNetworkId);
                        routers.addAll(router);
                    }
                    //A vm may not have many nics attached and even fewer routers might be stopped (only in exceptional cases)
                    //Safe to start the stopped router serially, this is consistent with the way how multiple networks are added to vm during deploy
                    //and routers are started serially ,may revisit to make this process parallel
                    for(DomainRouterVO routerToStart : routers) {
                        logger.warn("Trying to start router {} as part of vm: {} reboot", routerToStart, vm);
                        _virtualNetAppliance.startRouter(routerToStart.getId(),true);
                    }
                }
            } catch (ConcurrentOperationException e) {
                throw new CloudRuntimeException("Concurrent operations on starting router. " + e);
            } catch (Exception ex){
                throw new CloudRuntimeException("Router start failed due to" + ex);
            } finally {
                if (logger.isInfoEnabled()) {
                    logger.info("Rebooting vm {}{}.", vm, enterSetup ? " entering hardware setup menu" : " as is");
                }
                Map<VirtualMachineProfile.Param,Object> params = null;
                if (enterSetup) {
                    params = new HashMap();
                    params.put(VirtualMachineProfile.Param.BootIntoSetup, Boolean.TRUE);
                    if (logger.isTraceEnabled()) {
                        logger.trace(String.format("Adding %s to paramlist", VirtualMachineProfile.Param.BootIntoSetup));
                    }
                }
                _itMgr.reboot(vm.getUuid(), params);
            }
            return _vmDao.findById(vmId);
        } else {
            logger.error("Vm {} is not in Running state, failed to reboot", vm);
            return null;
        }
    }

    private UserVm forceRebootVirtualMachine(UserVmVO vm, long hostId, boolean enterSetup) {
        try {
            if (stopVirtualMachine(vm.getId(), false) != null) {
                Map<VirtualMachineProfile.Param,Object> params = new HashMap<>();
                if (enterSetup) {
                    params.put(VirtualMachineProfile.Param.BootIntoSetup, Boolean.TRUE);
                }
                return startVirtualMachine(vm.getId(), null, null, hostId, params, null, false).first();
            }
        } catch (CloudException e) {
            throw new CloudRuntimeException(String.format("Unable to reboot the VM: %s", vm), e);
        }
        return null;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_UPGRADE, eventDescription = "upgrading Vm")
    /*
     * TODO: cleanup eventually - Refactored API call
     */
    // This method will be deprecated as we use ScaleVMCmd for both stopped VMs and running VMs
    public UserVm upgradeVirtualMachine(UpgradeVMCmd cmd) throws ResourceAllocationException {
        Long vmId = cmd.getId();
        Long svcOffId = cmd.getServiceOfferingId();
        Account caller = CallContext.current().getCallingAccount();

        // Verify input parameters
        //UserVmVO vmInstance = _vmDao.findById(vmId);
        VMInstanceVO vmInstance = _vmInstanceDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        } else if (!(vmInstance.getState().equals(State.Stopped))) {
            throw new InvalidParameterValueException("Unable to upgrade virtual machine " + vmInstance.toString() + " " + " in state " + vmInstance.getState()
            + "; make sure the virtual machine is stopped");
        }

        _accountMgr.checkAccess(caller, null, true, vmInstance);

        upgradeStoppedVirtualMachine(vmId, svcOffId, cmd.getDetails());

        // Generate usage event for VM upgrade
        UserVmVO userVm = _vmDao.findById(vmId);
        generateUsageEvent( userVm, userVm.isDisplayVm(), EventTypes.EVENT_VM_UPGRADE);

        return userVm;
    }

    /**
     Updates the instance details map with the current values for absent details. This only applies to details {@value VmDetailConstants#CPU_SPEED},
     {@value VmDetailConstants#MEMORY}, and {@value VmDetailConstants#CPU_NUMBER}. This method only updates the map passed as parameter, not the database.
     @param details Map containing the instance details.
     @param vmInstance The virtual machine instance to retrieve the current values.
     @param newServiceOfferingId The ID of the new service offering.
     */

    protected void updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(Map<String, String> details, VirtualMachine vmInstance, Long newServiceOfferingId) {
        ServiceOfferingVO currentServiceOffering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        ServiceOfferingVO newServiceOffering = serviceOfferingDao.findById(newServiceOfferingId);
        addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(newServiceOffering.getSpeed(), details, VmDetailConstants.CPU_SPEED, currentServiceOffering.getSpeed());
        addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(newServiceOffering.getRamSize(), details, VmDetailConstants.MEMORY, currentServiceOffering.getRamSize());
        addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(newServiceOffering.getCpu(), details, VmDetailConstants.CPU_NUMBER, currentServiceOffering.getCpu());
    }

    /**
     * Adds the current detail value to the instance details map if a new value was not specified to it.
     *
     * @param newValue the new value to be set.
     * @param details a map of instance details.
     * @param detailKey the detail to be updated.
     * @param currentValue the current value of the detail constant.
     */

    protected void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(Integer newValue, Map<String, String> details, String detailKey, Integer currentValue) {
        if (newValue == null && details.get(detailKey) == null) {
            String currentValueString = String.valueOf(currentValue);
            logger.debug("{} was not specified, keeping the current value: {}.", detailKey, currentValueString);
            details.put(detailKey, currentValueString);
        }
    }


    private void validateOfferingMaxResource(ServiceOfferingVO offering) {
        Integer maxCPUCores = ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES.value() == 0 ? Integer.MAX_VALUE: ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES.value();
        if (offering.getCpu() > maxCPUCores) {
            throw new InvalidParameterValueException("Invalid cpu cores value, please choose another service offering with cpu cores between 1 and " + maxCPUCores);
        }
        Integer maxRAMSize = ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE.value() == 0 ? Integer.MAX_VALUE: ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE.value();
        if (offering.getRamSize() > maxRAMSize) {
            throw new InvalidParameterValueException("Invalid memory value, please choose another service offering with memory between 32 and " + maxRAMSize + " MB");
        }
    }

    @Override
    public void validateCustomParameters(ServiceOfferingVO serviceOffering, Map<String, String> customParameters) {
        //TODO need to validate custom cpu, and memory against min/max CPU/Memory ranges from service_offering_details table
        if (customParameters.size() != 0) {
            Map<String, String> offeringDetails = serviceOfferingDetailsDao.listDetailsKeyPairs(serviceOffering.getId());
            if (serviceOffering.getCpu() == null) {
                int minCPU = NumbersUtil.parseInt(offeringDetails.get(ApiConstants.MIN_CPU_NUMBER), 1);
                int maxCPU = NumbersUtil.parseInt(offeringDetails.get(ApiConstants.MAX_CPU_NUMBER), Integer.MAX_VALUE);
                int cpuNumber = NumbersUtil.parseInt(customParameters.get(UsageEventVO.DynamicParameters.cpuNumber.name()), -1);
                Integer maxCPUCores = ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES.value() == 0 ? Integer.MAX_VALUE: ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES.value();
                if (cpuNumber < minCPU || cpuNumber > maxCPU || cpuNumber > maxCPUCores) {
                    throw new InvalidParameterValueException(String.format("Invalid cpu cores value, specify a value between %d and %d", minCPU, Math.min(maxCPUCores, maxCPU)));
                }
            } else if (customParameters.containsKey(UsageEventVO.DynamicParameters.cpuNumber.name())) {
                throw new InvalidParameterValueException("The cpu cores of this offering id:" + serviceOffering.getUuid()
                + " is not customizable. This is predefined in the template.");
            }

            if (serviceOffering.getSpeed() == null) {
                String cpuSpeed = customParameters.get(UsageEventVO.DynamicParameters.cpuSpeed.name());
                if ((cpuSpeed == null) || (NumbersUtil.parseInt(cpuSpeed, -1) <= 0)) {
                    throw new InvalidParameterValueException("Invalid cpu speed value, specify a value between 1 and " + Integer.MAX_VALUE);
                }
            } else if (!serviceOffering.isCustomCpuSpeedSupported() && customParameters.containsKey(UsageEventVO.DynamicParameters.cpuSpeed.name())) {
                throw new InvalidParameterValueException("The cpu speed of this offering id:" + serviceOffering.getUuid()
                + " is not customizable. This is predefined in the template.");
            }

            if (serviceOffering.getRamSize() == null) {
                int minMemory = NumbersUtil.parseInt(offeringDetails.get(ApiConstants.MIN_MEMORY), 32);
                int maxMemory = NumbersUtil.parseInt(offeringDetails.get(ApiConstants.MAX_MEMORY), Integer.MAX_VALUE);
                int memory = NumbersUtil.parseInt(customParameters.get(UsageEventVO.DynamicParameters.memory.name()), -1);
                Integer maxRAMSize = ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE.value() == 0 ? Integer.MAX_VALUE: ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE.value();
                if (memory < minMemory || memory > maxMemory || memory > maxRAMSize) {
                    throw new InvalidParameterValueException(String.format("Invalid memory value, specify a value between %d and %d", minMemory, Math.min(maxRAMSize, maxMemory)));
                }
            } else if (customParameters.containsKey(UsageEventVO.DynamicParameters.memory.name())) {
                throw new InvalidParameterValueException("The memory of this offering id:" + serviceOffering.getUuid() + " is not customizable. This is predefined in the template.");
            }
        } else {
            throw new InvalidParameterValueException("Need to specify custom parameter values cpu, cpu speed and memory when using custom offering");
        }
    }

    private UserVm upgradeStoppedVirtualMachine(Long vmId, Long svcOffId, Map<String, String> customParameters) throws ResourceAllocationException {

        VMInstanceVO vmInstance = _vmInstanceDao.findById(vmId);
        // Check resource limits for CPU and Memory.
        ServiceOfferingVO newServiceOffering = serviceOfferingDao.findById(svcOffId);
        if (newServiceOffering.getState() == ServiceOffering.State.Inactive) {
            throw new InvalidParameterValueException(String.format("Unable to upgrade virtual machine %s with an inactive service offering %s", vmInstance.getUuid(), newServiceOffering.getUuid()));
        }
        if (newServiceOffering.isDynamic()) {
            newServiceOffering.setDynamicFlag(true);
            validateCustomParameters(newServiceOffering, customParameters);
            newServiceOffering = serviceOfferingDao.getComputeOffering(newServiceOffering, customParameters);
        } else {
            validateOfferingMaxResource(newServiceOffering);
        }
        ServiceOfferingVO currentServiceOffering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());

        validateDiskOfferingChecks(currentServiceOffering, newServiceOffering);

        int newCpu = newServiceOffering.getCpu();
        int newMemory = newServiceOffering.getRamSize();
        int currentCpu = currentServiceOffering.getCpu();
        int currentMemory = currentServiceOffering.getRamSize();

        Account owner = _accountMgr.getActiveAccountById(vmInstance.getAccountId());
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
        if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            _resourceLimitMgr.checkVmResourceLimitsForServiceOfferingChange(owner, vmInstance.isDisplay(), (long) currentCpu, (long) newCpu,
                    (long) currentMemory, (long) newMemory, currentServiceOffering, newServiceOffering, template);
        }

        // Check that the specified service offering ID is valid
        _itMgr.checkIfCanUpgrade(vmInstance, newServiceOffering);

        // Check if the new service offering can be applied to vm instance
        _accountMgr.checkAccess(owner, newServiceOffering, _dcDao.findById(vmInstance.getDataCenterId()));

        // resize and migrate the root volume if required
        DiskOfferingVO newDiskOffering = _diskOfferingDao.findById(newServiceOffering.getDiskOfferingId());
        changeDiskOfferingForRootVolume(vmId, newDiskOffering, customParameters, vmInstance.getDataCenterId());

        _itMgr.upgradeVmDb(vmId, newServiceOffering, currentServiceOffering);

        // Increment or decrement CPU and Memory count accordingly.
        if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            _resourceLimitMgr.updateVmResourceCountForServiceOfferingChange(owner.getAccountId(), vmInstance.isDisplay(), (long) currentCpu, (long) newCpu,
                    (long) currentMemory, (long) newMemory, currentServiceOffering, newServiceOffering, template);
        }

        return _vmDao.findById(vmInstance.getId());

    }

    /**
     * Prepares the Resize Volume Command and verifies if the disk offering from the new service offering can be resized.
     * <br>
     * If the Service Offering was configured with a root disk size (size > 0) then it can only resize to an offering with a larger disk
     * or to an offering with a root size of zero, which is the default behavior.
     */
    protected ResizeVolumeCmd prepareResizeVolumeCmd(VolumeVO rootVolume, DiskOfferingVO currentRootDiskOffering, DiskOfferingVO newRootDiskOffering) {
        if (rootVolume == null) {
            throw new InvalidParameterValueException("Could not find Root volume for the VM while preparing the Resize Volume Command.");
        }
        if (currentRootDiskOffering == null) {
            throw new InvalidParameterValueException("Could not find Disk Offering matching the provided current Root Offering ID.");
        }
        if (newRootDiskOffering == null) {
            throw new InvalidParameterValueException("Could not find Disk Offering matching the provided Offering ID for resizing Root volume.");
        }

        ResizeVolumeCmd resizeVolumeCmd = new ResizeVolumeCmd(rootVolume.getId(), newRootDiskOffering.getMinIops(), newRootDiskOffering.getMaxIops());

        long newNewOfferingRootSizeInBytes = newRootDiskOffering.getDiskSize();
        long newNewOfferingRootSizeInGiB = newNewOfferingRootSizeInBytes / GiB_TO_BYTES;
        long currentRootDiskOfferingGiB = currentRootDiskOffering.getDiskSize() / GiB_TO_BYTES;
        if (newNewOfferingRootSizeInBytes > currentRootDiskOffering.getDiskSize()) {
            resizeVolumeCmd = new ResizeVolumeCmd(rootVolume.getId(), newRootDiskOffering.getMinIops(), newRootDiskOffering.getMaxIops(), newRootDiskOffering.getId());
            logger.debug("Preparing command to resize VM Root disk from {} GB to {} GB; current offering: {}, new offering: {}.",
                    currentRootDiskOfferingGiB, newNewOfferingRootSizeInGiB, currentRootDiskOffering, newRootDiskOffering);
        } else if (newNewOfferingRootSizeInBytes > 0l && newNewOfferingRootSizeInBytes < currentRootDiskOffering.getDiskSize()) {
            throw new InvalidParameterValueException(String.format(
                    "Failed to resize Root volume. The new Service Offering [%s] has a smaller disk size [%d GB] than the current disk [%d GB].",
                    newRootDiskOffering, newNewOfferingRootSizeInGiB, currentRootDiskOfferingGiB));
        }
        return resizeVolumeCmd;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NIC_CREATE, eventDescription = "Creating Nic", async = true)
    public UserVm addNicToVirtualMachine(AddNicToVMCmd cmd) throws InvalidParameterValueException, PermissionDeniedException, CloudRuntimeException {
        Long vmId = cmd.getVmId();
        Long networkId = cmd.getNetworkId();
        String ipAddress = cmd.getIpAddress();
        String macAddress = cmd.getMacAddress();
        Account caller = CallContext.current().getCallingAccount();

        UserVmVO vmInstance = _vmDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        }

        // Check that Vm does not have VM Snapshots
        if (_vmSnapshotDao.findByVm(vmId).size() > 0) {
            throw new InvalidParameterValueException("NIC cannot be added to VM with VM Snapshots");
        }

        NetworkVO network = _networkDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("unable to find a network with id " + networkId);
        }

        if (UserVmManager.SHAREDFSVM.equals(vmInstance.getUserVmType()) &&  network.getGuestType() == Network.GuestType.Shared) {
            if ((network.getAclType() != ControlledEntity.ACLType.Account) ||
                    (network.getDomainId() != vmInstance.getDomainId()) ||
                    (network.getAccountId() != vmInstance.getAccountId())) {
                throw new InvalidParameterValueException("Shared network which is not Account scoped and not belonging to the same account can not be added to a Shared FileSystem Instance");
            }
        }

        Account vmOwner = _accountMgr.getAccount(vmInstance.getAccountId());
        _networkModel.checkNetworkPermissions(vmOwner, network);

        checkIfNetExistsForVM(vmInstance, network);

        macAddress = validateOrReplaceMacAddress(macAddress, network);

        if(_nicDao.findByNetworkIdAndMacAddress(networkId, macAddress) != null) {
            throw new CloudRuntimeException("A NIC with this MAC address exists for network: " + network.getUuid());
        }

        NicProfile profile = new NicProfile(ipAddress, null, macAddress);
        if (ipAddress != null) {
            if (!(NetUtils.isValidIp4(ipAddress) || NetUtils.isValidIp6(ipAddress))) {
                throw new InvalidParameterValueException("Invalid format for IP address parameter: " + ipAddress);
            }
        }

        // Perform permission check on VM
        _accountMgr.checkAccess(caller, null, true, vmInstance);

        // Verify that zone is not Basic
        DataCenterVO dc = _dcDao.findById(vmInstance.getDataCenterId());
        if (dc.getNetworkType() == DataCenter.NetworkType.Basic) {
            throw new CloudRuntimeException(String.format("Zone %s, has a NetworkType of Basic. Can't add a new NIC to a VM on a Basic Network", dc));
        }

        //ensure network belongs in zone
        if (network.getDataCenterId() != vmInstance.getDataCenterId()) {
            throw new CloudRuntimeException(String.format("%s is in zone: %s but %s is in zone: %s",
                    vmInstance, dc, network, dataCenterDao.findById(network.getDataCenterId())));
        }

        if(_networkModel.getNicInNetwork(vmInstance.getId(),network.getId()) != null){
            logger.debug("VM {} already in network {} going to add another NIC", vmInstance, network);
        } else {
            //* get all vms hostNames in the network
            List<String> hostNames = _vmInstanceDao.listDistinctHostNames(network.getId());
            //* verify that there are no duplicates
            if (hostNames.contains(vmInstance.getHostName())) {
                throw new CloudRuntimeException("Network " + network.getName() + " already has a vm with host name: " + vmInstance.getHostName());
            }
        }

        setNicAsDefaultIfNeeded(vmInstance, profile);

        NicProfile guestNic = null;
        boolean cleanUp = true;

        try {
            guestNic = _itMgr.addVmToNetwork(vmInstance, network, profile);
            saveExtraDhcpOptions(guestNic.getId(), cmd.getDhcpOptionsMap());
            _networkMgr.configureExtraDhcpOptions(network, guestNic.getId(), cmd.getDhcpOptionsMap());
            cleanUp = false;
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("Unable to add NIC to " + vmInstance + ": " + e);
        } catch (InsufficientCapacityException e) {
            throw new CloudRuntimeException("Insufficient capacity when adding NIC to " + vmInstance + ": " + e);
        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException("Concurrent operations on adding NIC to " + vmInstance + ": " + e);
        } finally {
            if(cleanUp) {
                try {
                    _itMgr.removeVmFromNetwork(vmInstance, network, null);
                } catch (ResourceUnavailableException e) {
                    throw new CloudRuntimeException("Error while cleaning up NIC " + e);
                }
            }
        }
        CallContext.current().putContextParameter(Nic.class, guestNic.getUuid());
        logger.debug(String.format("Successful addition of %s from %s through %s", network, vmInstance, guestNic));
        return _vmDao.findById(vmInstance.getId());
    }

    /**
     * Set NIC as default if VM has no default NIC
     * @param vmInstance VM instance to be checked
     * @param nicProfile NIC profile to be updated
     */
    public void setNicAsDefaultIfNeeded(UserVmVO vmInstance, NicProfile nicProfile) {
        if (_networkModel.getDefaultNic(vmInstance.getId()) == null) {
            logger.debug(String.format("Setting NIC %s as default as VM %s has no default NIC.", nicProfile.getName(), vmInstance.getName()));
            nicProfile.setDefaultNic(true);
        }
    }

    /**
     * duplicated in {@see VirtualMachineManagerImpl} for a {@see VMInstanceVO}
     */
    private void checkIfNetExistsForVM(VirtualMachine virtualMachine, Network network) {
        List<NicVO> allNics = _nicDao.listByVmId(virtualMachine.getId());
        for (NicVO nic : allNics) {
            if (nic.getNetworkId() == network.getId()) {
                throw new CloudRuntimeException("A NIC already exists for VM:" + virtualMachine.getInstanceName() + " in network: " + network.getUuid());
            }
        }
    }

    /**
     * If the given MAC address is invalid it replaces the given MAC with the next available MAC address
     */
    protected String validateOrReplaceMacAddress(String macAddress, NetworkVO network) {
        if (!NetUtils.isValidMac(macAddress)) {
            try {
                macAddress = _networkModel.getNextAvailableMacAddressInNetwork(network.getId());
            } catch (InsufficientAddressCapacityException e) {
                throw new CloudRuntimeException(String.format("A MAC address cannot be generated for this NIC in the network [%s] ", network));
            }
        }
        return macAddress;
    }

    private void saveExtraDhcpOptions(long nicId, Map<Integer, String> dhcpOptions) {
        List<NicExtraDhcpOptionVO> nicExtraDhcpOptionVOList = dhcpOptions
                .entrySet()
                .stream()
                .map(entry -> new NicExtraDhcpOptionVO(nicId, entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());

        _nicExtraDhcpOptionDao.saveExtraDhcpOptions(nicExtraDhcpOptionVOList);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NIC_DELETE, eventDescription = "Removing Nic", async = true)
    public UserVm removeNicFromVirtualMachine(RemoveNicFromVMCmd cmd) throws InvalidParameterValueException, PermissionDeniedException, CloudRuntimeException {
        Long vmId = cmd.getVmId();
        Long nicId = cmd.getNicId();
        Account caller = CallContext.current().getCallingAccount();

        UserVmVO vmInstance = _vmDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("Unable to find a virtual machine with id " + vmId);
        }

        // Check that Vm does not have VM Snapshots
        if (_vmSnapshotDao.findByVm(vmId).size() > 0) {
            throw new InvalidParameterValueException("NIC cannot be removed from VM with VM Snapshots");
        }

        NicVO nic = _nicDao.findById(nicId);
        if (nic == null) {
            throw new InvalidParameterValueException("Unable to find a nic with id " + nicId);
        }

        NetworkVO network = _networkDao.findById(nic.getNetworkId());
        if (network == null) {
            throw new InvalidParameterValueException("Unable to find a network with id " + nic.getNetworkId());
        }

        // Perform permission check on VM
        _accountMgr.checkAccess(caller, null, true, vmInstance);

        // Verify that zone is not Basic
        DataCenterVO dc = _dcDao.findById(vmInstance.getDataCenterId());
        if (dc.getNetworkType() == DataCenter.NetworkType.Basic) {
            throw new InvalidParameterValueException(String.format("Zone %s, has a NetworkType of Basic. Can't remove a NIC from a VM on a Basic Network", dc));
        }

        // check to see if nic is attached to VM
        if (nic.getInstanceId() != vmId) {
            throw new InvalidParameterValueException(nic + " is not a nic on " + vmInstance);
        }

        // don't delete default NIC on a user VM
        if (nic.isDefaultNic() && vmInstance.getType() == VirtualMachine.Type.User) {
            throw new InvalidParameterValueException("Unable to remove nic from " + vmInstance + " in " + network + ", nic is default.");
        }

        // if specified nic is associated with PF/LB/Static NAT
        if (_rulesMgr.listAssociatedRulesForGuestNic(nic).size() > 0) {
            throw new InvalidParameterValueException("Unable to remove nic from " + vmInstance + " in " + network + ", nic has associated Port forwarding or Load balancer or Static NAT rules.");
        }

        boolean nicremoved = false;
        try {
            nicremoved = _itMgr.removeNicFromVm(vmInstance, nic);
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("Unable to remove " + network + " from " + vmInstance + ": " + e);

        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException("Concurrent operations on removing " + network + " from " + vmInstance + ": " + e);
        }

        if (!nicremoved) {
            throw new CloudRuntimeException("Unable to remove " + network + " from " + vmInstance);
        }

        logger.debug("Successful removal of " + network + " from " + vmInstance);
        return _vmDao.findById(vmInstance.getId());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NIC_UPDATE, eventDescription = "Creating Nic", async = true)
    public UserVm updateDefaultNicForVirtualMachine(UpdateDefaultNicForVMCmd cmd) throws InvalidParameterValueException, CloudRuntimeException {
        Long vmId = cmd.getVmId();
        Long nicId = cmd.getNicId();
        Account caller = CallContext.current().getCallingAccount();

        UserVmVO vmInstance = _vmDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        }

        // Check that Vm does not have VM Snapshots
        if (_vmSnapshotDao.findByVm(vmId).size() > 0) {
            throw new InvalidParameterValueException("NIC cannot be updated for VM with VM Snapshots");
        }

        NicVO nic = _nicDao.findById(nicId);
        if (nic == null) {
            throw new InvalidParameterValueException("unable to find a nic with id " + nicId);
        }
        NetworkVO network = _networkDao.findById(nic.getNetworkId());
        if (network == null) {
            throw new InvalidParameterValueException("unable to find a network with id " + nic.getNetworkId());
        }

        // Perform permission check on VM
        _accountMgr.checkAccess(caller, null, true, vmInstance);

        // Verify that zone is not Basic
        DataCenterVO dc = _dcDao.findById(vmInstance.getDataCenterId());
        if (dc.getNetworkType() == DataCenter.NetworkType.Basic) {
            throw new CloudRuntimeException(String.format("Zone %s, has a NetworkType of Basic. Can't change default NIC on a Basic Network", dc));
        }

        // no need to check permissions for network, we'll enumerate the ones they already have access to
        Network existingdefaultnet = _networkModel.getDefaultNetworkForVm(vmId);

        //check to see if nic is attached to VM
        if (nic.getInstanceId() != vmId) {
            throw new InvalidParameterValueException(nic + " is not a nic on  " + vmInstance);
        }
        // if current default equals chosen new default, Throw an exception
        if (nic.isDefaultNic()) {
            throw new CloudRuntimeException("refusing to set default nic because chosen nic is already the default");
        }

        //make sure the VM is Running or Stopped
        if ((vmInstance.getState() != State.Running) && (vmInstance.getState() != State.Stopped)) {
            throw new CloudRuntimeException("refusing to set default " + vmInstance + " is not Running or Stopped");
        }

        NicProfile existing = null;
        List<NicProfile> nicProfiles = _networkMgr.getNicProfiles(vmInstance);
        for (NicProfile nicProfile : nicProfiles) {
            if (nicProfile.isDefaultNic() && existingdefaultnet != null && nicProfile.getNetworkId() == existingdefaultnet.getId()) {
                existing = nicProfile;
            }
        }

        if (existing == null) {
            logger.warn("Failed to update default nic, no nic profile found for existing default network");
            throw new CloudRuntimeException("Failed to find a nic profile for the existing default network. This is bad and probably means some sort of configuration corruption");
        }

        Network oldDefaultNetwork = null;
        oldDefaultNetwork = _networkModel.getDefaultNetworkForVm(vmId);
        String oldNicIdString = Long.toString(_networkModel.getDefaultNic(vmId).getId());
        long oldNetworkOfferingId = -1L;

        if (oldDefaultNetwork != null) {
            oldNetworkOfferingId = oldDefaultNetwork.getNetworkOfferingId();
        }
        NicVO existingVO = _nicDao.findById(existing.id);
        Integer chosenID = nic.getDeviceId();
        Integer existingID = existing.getDeviceId();

        Network newdefault = null;
        if (_itMgr.updateDefaultNicForVM(vmInstance, nic, existingVO)) {
            newdefault = _networkModel.getDefaultNetworkForVm(vmId);
        }

        if (newdefault == null) {
            nic.setDefaultNic(false);
            nic.setDeviceId(chosenID);
            existingVO.setDefaultNic(true);
            existingVO.setDeviceId(existingID);

            nic = _nicDao.persist(nic);
            _nicDao.persist(existingVO);

            newdefault = _networkModel.getDefaultNetworkForVm(vmId);
            if (newdefault.getId() == existingdefaultnet.getId()) {
                throw new CloudRuntimeException("Setting a default nic failed, and we had no default nic, but we were able to set it back to the original");
            }
            throw new CloudRuntimeException("Failed to change default nic to " + nic + " and now we have no default");
        } else if (newdefault.getId() == nic.getNetworkId()) {
            logger.debug("successfully set default network to " + network + " for " + vmInstance);
            String nicIdString = Long.toString(nic.getId());
            long newNetworkOfferingId = network.getNetworkOfferingId();
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, vmInstance.getAccountId(), vmInstance.getDataCenterId(), vmInstance.getId(),
                    oldNicIdString, oldNetworkOfferingId, null, 1L, VirtualMachine.class.getName(), vmInstance.getUuid(), vmInstance.isDisplay());
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vmInstance.getAccountId(), vmInstance.getDataCenterId(), vmInstance.getId(), nicIdString,
                    newNetworkOfferingId, null, 1L, VirtualMachine.class.getName(), vmInstance.getUuid(), vmInstance.isDisplay());
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, vmInstance.getAccountId(), vmInstance.getDataCenterId(), vmInstance.getId(), nicIdString,
                    newNetworkOfferingId, null, 0L, VirtualMachine.class.getName(), vmInstance.getUuid(), vmInstance.isDisplay());
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vmInstance.getAccountId(), vmInstance.getDataCenterId(), vmInstance.getId(),
                    oldNicIdString, oldNetworkOfferingId, null, 0L, VirtualMachine.class.getName(), vmInstance.getUuid(), vmInstance.isDisplay());

            if (vmInstance.getState() == State.Running) {
                try {
                    VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vmInstance);
                    User callerUser = _accountMgr.getActiveUser(CallContext.current().getCallingUserId());
                    ReservationContext context = new ReservationContextImpl(null, null, callerUser, caller);
                    DeployDestination dest = new DeployDestination(dc, null, null, null);
                    _networkMgr.prepare(vmProfile, dest, context);
                } catch (final Exception e) {
                    logger.info("Got exception: ", e);
                }
            }

            return _vmDao.findById(vmInstance.getId());
        }

        throw new CloudRuntimeException(String.format("something strange happened, new default network(%s) is not null, and is not equal to the network(%d) of the chosen nic", newdefault, nic.getNetworkId()));
    }

    @Override
    public UserVm updateNicIpForVirtualMachine(UpdateVmNicIpCmd cmd) {
        Long nicId = cmd.getNicId();
        String ipaddr = cmd.getIpaddress();
        Account caller = CallContext.current().getCallingAccount();

        //check whether the nic belongs to user vm.
        NicVO nicVO = _nicDao.findById(nicId);
        if (nicVO == null) {
            throw new InvalidParameterValueException("There is no nic for the " + nicId);
        }

        if (nicVO.getVmType() != VirtualMachine.Type.User) {
            throw new InvalidParameterValueException("The nic is not belongs to user vm");
        }

        UserVm vm = _vmDao.findById(nicVO.getInstanceId());
        if (vm == null) {
            throw new InvalidParameterValueException("There is no vm with the nic");
        }

        Network network = _networkDao.findById(nicVO.getNetworkId());
        if (network == null) {
            throw new InvalidParameterValueException("There is no network with the nic");
        }
        // Don't allow to update vm nic ip if network is not in Implemented/Setup/Allocated state
        if (!(network.getState() == Network.State.Allocated || network.getState() == Network.State.Implemented || network.getState() == Network.State.Setup)) {
            throw new InvalidParameterValueException("Network is not in the right state to update vm nic ip. Correct states are: " + Network.State.Allocated + ", " + Network.State.Implemented + ", "
                    + Network.State.Setup);
        }

        NetworkOfferingVO offering = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (offering == null) {
            throw new InvalidParameterValueException("There is no network offering with the network");
        }
        if (!_networkModel.listNetworkOfferingServices(offering.getId()).isEmpty() && vm.getState() != State.Stopped) {
            InvalidParameterValueException ex = new InvalidParameterValueException(
                    "VM is not Stopped, unable to update the vm nic having the specified id");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        // verify permissions
        _accountMgr.checkAccess(caller, null, true, vm);
        Account ipOwner = _accountDao.findByIdIncludingRemoved(vm.getAccountId());

        // verify ip address
        logger.debug("Calling the ip allocation ...");
        DataCenter dc = _dcDao.findById(network.getDataCenterId());
        if (dc == null) {
            throw new InvalidParameterValueException("There is no dc with the nic");
        }
        if (dc.getNetworkType() == NetworkType.Advanced && network.getGuestType() == Network.GuestType.Isolated) {
            try {
                ipaddr = _ipAddrMgr.allocateGuestIP(network, ipaddr);
            } catch (InsufficientAddressCapacityException e) {
                throw new InvalidParameterValueException(String.format("Allocating ip to guest nic %s failed, for insufficient address capacity", nicVO));
            }
            if (ipaddr == null) {
                throw new InvalidParameterValueException(String.format("Allocating ip to guest nic %s failed, please choose another ip", nicVO));
            }

            if (nicVO.getIPv4Address() != null) {
                updatePublicIpDnatVmIp(vm.getId(), network.getId(), nicVO.getIPv4Address(), ipaddr);
                updateLoadBalancerRulesVmIp(vm.getId(), network.getId(), nicVO.getIPv4Address(), ipaddr);
                updatePortForwardingRulesVmIp(vm.getId(), network.getId(), nicVO.getIPv4Address(), ipaddr);
            }

        } else if (dc.getNetworkType() == NetworkType.Basic || network.getGuestType()  == Network.GuestType.Shared) {
            //handle the basic networks here
            //for basic zone, need to provide the podId to ensure proper ip alloation
            Long podId = null;
            if (dc.getNetworkType() == NetworkType.Basic) {
                podId = vm.getPodIdToDeployIn();
                if (podId == null) {
                    throw new InvalidParameterValueException("vm pod id is null in Basic zone; can't decide the range for ip allocation");
                }
            }

            try {
                ipaddr = _ipAddrMgr.allocatePublicIpForGuestNic(network, podId, ipOwner, ipaddr);
                if (ipaddr == null) {
                    throw new InvalidParameterValueException("Allocating ip to guest nic " + nicVO.getUuid() + " failed, please choose another ip");
                }

                final IPAddressVO newIp = _ipAddressDao.findByIpAndSourceNetworkId(network.getId(), ipaddr);
                final Vlan vlan = _vlanDao.findById(newIp.getVlanId());
                nicVO.setIPv4Gateway(vlan.getVlanGateway());
                nicVO.setIPv4Netmask(vlan.getVlanNetmask());

                final IPAddressVO ip = _ipAddressDao.findByIpAndSourceNetworkId(nicVO.getNetworkId(), nicVO.getIPv4Address());
                if (ip != null) {
                    Transaction.execute(new TransactionCallbackNoReturn() {
                        @Override
                        public void doInTransactionWithoutResult(TransactionStatus status) {
                            _ipAddrMgr.markIpAsUnavailable(ip.getId());
                            _ipAddressDao.unassignIpAddress(ip.getId());
                        }
                    });
                }
            } catch (InsufficientAddressCapacityException e) {
                logger.error("Allocating ip to guest nic {} failed, for insufficient address capacity", nicVO);
                return null;
            }
        } else {
            throw new InvalidParameterValueException("UpdateVmNicIpCmd is not supported in L2 network");
        }

        logger.debug("Updating IPv4 address of NIC " + nicVO + " to " + ipaddr + "/" + nicVO.getIPv4Netmask() + " with gateway " + nicVO.getIPv4Gateway());
        nicVO.setIPv4Address(ipaddr);
        _nicDao.persist(nicVO);

        return vm;
    }

    private void updatePublicIpDnatVmIp(long vmId, long networkId, String oldIp, String newIp) {
        if (!_networkModel.areServicesSupportedInNetwork(networkId, Service.StaticNat)) {
            return;
        }
        List<IPAddressVO> publicIps = _ipAddressDao.listByAssociatedVmId(vmId);
        for (IPAddressVO publicIp : publicIps) {
            if (oldIp.equals(publicIp.getVmIp()) && publicIp.getAssociatedWithNetworkId() == networkId) {
                publicIp.setVmIp(newIp);
                _ipAddressDao.persist(publicIp);
            }
        }
    }

    private void updateLoadBalancerRulesVmIp(long vmId, long networkId, String oldIp, String newIp) {
        if (!_networkModel.areServicesSupportedInNetwork(networkId, Service.Lb)) {
            return;
        }
        List<LoadBalancerVMMapVO> loadBalancerVMMaps = _loadBalancerVMMapDao.listByInstanceId(vmId);
        for (LoadBalancerVMMapVO map : loadBalancerVMMaps) {
            long lbId = map.getLoadBalancerId();
            FirewallRuleVO rule = _rulesDao.findById(lbId);
            if (oldIp.equals(map.getInstanceIp()) && networkId == rule.getNetworkId()) {
                map.setInstanceIp(newIp);
                _loadBalancerVMMapDao.persist(map);
            }
        }
    }

    private void updatePortForwardingRulesVmIp(long vmId, long networkId, String oldIp, String newIp) {
        if (!_networkModel.areServicesSupportedInNetwork(networkId, Service.PortForwarding)) {
            return;
        }
        List<PortForwardingRuleVO> firewallRules = _portForwardingDao.listByVm(vmId);
        for (PortForwardingRuleVO firewallRule : firewallRules) {
            FirewallRuleVO rule = _rulesDao.findById(firewallRule.getId());
            if (oldIp.equals(firewallRule.getDestinationIpAddress().toString()) && networkId == rule.getNetworkId()) {
                firewallRule.setDestinationIpAddress(new Ip(newIp));
                _portForwardingDao.persist(firewallRule);
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_UPGRADE, eventDescription = "Upgrading VM", async = true)
    public UserVm upgradeVirtualMachine(ScaleVMCmd cmd) throws ResourceUnavailableException, ConcurrentOperationException, ManagementServerException,
    VirtualMachineMigrationException {

        Long vmId = cmd.getId();
        Long newServiceOfferingId = cmd.getServiceOfferingId();
        VirtualMachine vm = (VirtualMachine) this._entityMgr.findById(VirtualMachine.class, vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find VM's UUID");
        }
        if (Hypervisor.HypervisorType.External.equals(vm.getHypervisorType())) {
            logger.error("Scale VM not supported for {} as it is {} hypervisor instance",
                    vm, Hypervisor.HypervisorType.External.name());
            throw new InvalidParameterValueException(String.format("Operation not supported for instance: %s",
                    vm.getName()));
        }
        CallContext.current().setEventDetails("Vm Id: " + vm.getUuid());

        Map<String, String> cmdDetails = cmd.getDetails();

        updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(cmdDetails, vm, newServiceOfferingId);

        boolean result = upgradeVirtualMachine(vmId, newServiceOfferingId, cmdDetails);
        if (result) {
            UserVmVO vmInstance = _vmDao.findById(vmId);
            if (vmInstance.getState().equals(State.Stopped)) {
                // Generate usage event for VM upgrade
                generateUsageEvent(vmInstance, vmInstance.isDisplayVm(), EventTypes.EVENT_VM_UPGRADE);
            }
            return vmInstance;
        } else {
            throw new CloudRuntimeException("Failed to scale the VM");
        }
    }

    @Override
    public boolean upgradeVirtualMachine(Long vmId, Long newServiceOfferingId, Map<String, String> customParameters) throws ResourceUnavailableException,
    ConcurrentOperationException, ManagementServerException, VirtualMachineMigrationException {

        VMInstanceVO vmInstance = _vmInstanceDao.findById(vmId);

        Account caller = CallContext.current().getCallingAccount();
        _accountMgr.checkAccess(caller, null, true, vmInstance);
        if (vmInstance == null) {
            logger.error(String.format("VM instance with id [%s] is null, it is not possible to upgrade a null VM.", vmId));
            return false;
        }

        if (State.Stopped.equals(vmInstance.getState())) {
            upgradeStoppedVirtualMachine(vmId, newServiceOfferingId, customParameters);
            return true;
        }

        if (State.Running.equals(vmInstance.getState())) {
            ServiceOfferingVO newServiceOfferingVO = serviceOfferingDao.findById(newServiceOfferingId);
            VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
            HostVO instanceHost = _hostDao.findById(vmInstance.getHostId());
            _hostDao.loadHostTags(instanceHost);

            Set<String> strictHostTags = UserVmManager.getStrictHostTags();
            if (!instanceHost.checkHostServiceOfferingAndTemplateTags(newServiceOfferingVO, template, strictHostTags)) {
                logger.error("Cannot upgrade VM {} as the new service offering {} does not have the required host tags {}.",
                        vmInstance, newServiceOfferingVO,
                        instanceHost.getHostServiceOfferingAndTemplateMissingTags(newServiceOfferingVO, template, strictHostTags));
                return false;
            }
        }
        return upgradeRunningVirtualMachine(vmId, newServiceOfferingId, customParameters);
    }

    private boolean upgradeRunningVirtualMachine(Long vmId, Long newServiceOfferingId, Map<String, String> customParameters) throws ResourceUnavailableException,
    ConcurrentOperationException, ManagementServerException, VirtualMachineMigrationException {

        Account caller = CallContext.current().getCallingAccount();
        VMInstanceVO vmInstance = _vmInstanceDao.findById(vmId);
        Account owner = _accountDao.findById(vmInstance.getAccountId());

        Set<HypervisorType> supportedHypervisorTypes = new HashSet<>();
        supportedHypervisorTypes.add(HypervisorType.XenServer);
        supportedHypervisorTypes.add(HypervisorType.VMware);
        supportedHypervisorTypes.add(HypervisorType.Simulator);
        supportedHypervisorTypes.add(HypervisorType.KVM);

        HypervisorType vmHypervisorType = vmInstance.getHypervisorType();

        if (!supportedHypervisorTypes.contains(vmHypervisorType)) {
            String message = String.format("Scaling the VM dynamically is not supported for VMs running on Hypervisor [%s].", vmInstance.getHypervisorType());
            logger.info(message);
            throw new InvalidParameterValueException(message);
        }

        _accountMgr.checkAccess(caller, null, true, vmInstance);

        //Check if its a scale "up"
        ServiceOfferingVO newServiceOffering = serviceOfferingDao.findById(newServiceOfferingId);
        if (newServiceOffering.isDynamic()) {
            newServiceOffering.setDynamicFlag(true);
            validateCustomParameters(newServiceOffering, customParameters);
            newServiceOffering = serviceOfferingDao.getComputeOffering(newServiceOffering, customParameters);
        }

        // Check that the specified service offering ID is valid
        _itMgr.checkIfCanUpgrade(vmInstance, newServiceOffering);

        ServiceOfferingVO currentServiceOffering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        if (newServiceOffering.isDynamicScalingEnabled() != currentServiceOffering.isDynamicScalingEnabled()) {
            throw new InvalidParameterValueException("Unable to Scale VM: since dynamic scaling enabled flag is not same for new service offering and old service offering");
        }

        validateDiskOfferingChecks(currentServiceOffering, newServiceOffering);

        int newCpu = newServiceOffering.getCpu();
        int newMemory = newServiceOffering.getRamSize();
        int newSpeed = newServiceOffering.getSpeed();
        int currentCpu = currentServiceOffering.getCpu();
        int currentMemory = currentServiceOffering.getRamSize();
        int currentSpeed = currentServiceOffering.getSpeed();
        int memoryDiff = newMemory - currentMemory;
        int cpuDiff = newCpu * newSpeed - currentCpu * currentSpeed;

        // Don't allow to scale when (Any of the new values less than current values) OR (All current and new values are same)
        if ((newSpeed < currentSpeed || newMemory < currentMemory || newCpu < currentCpu) || (newSpeed == currentSpeed && newMemory == currentMemory && newCpu == currentCpu)) {
            String message = String.format("While the VM is running, only scalling up it is supported. New service offering {\"memory\": %s, \"speed\": %s, \"cpu\": %s} should"
              + " have at least one value (ram, speed or cpu) greater than the current values {\"memory\": %s, \"speed\": %s, \"cpu\": %s}.", newMemory, newSpeed, newCpu,
              currentMemory, currentSpeed, currentCpu);

            throw new InvalidParameterValueException(message);
        }

        if (vmHypervisorType.equals(HypervisorType.KVM) && !currentServiceOffering.isDynamic()) {
            String message = String.format("Unable to live scale VM on KVM when current service offering is a \"Fixed Offering\". KVM needs the tag \"maxMemory\" to live scale and it is only configured when VM is deployed with a custom service offering and \"Dynamic Scalable\" is enabled.");
            logger.info(message);
            throw new InvalidParameterValueException(message);
        }

        serviceOfferingDao.loadDetails(currentServiceOffering);
        serviceOfferingDao.loadDetails(newServiceOffering);

        Map<String, String> currentDetails = currentServiceOffering.getDetails();
        Map<String, String> newDetails = newServiceOffering.getDetails();
        String currentVgpuType = currentDetails.get("vgpuType");
        String newVgpuType = newDetails.get("vgpuType");

        if (currentVgpuType != null && (newVgpuType == null || !newVgpuType.equalsIgnoreCase(currentVgpuType))) {
            throw new InvalidParameterValueException(String.format("Dynamic scaling of vGPU type is not supported. VM has vGPU Type: [%s].", currentVgpuType));
        }

        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());

        // Check resource limits
        _resourceLimitMgr.checkVmResourceLimitsForServiceOfferingChange(owner, vmInstance.isDisplay(), (long) currentCpu, (long) newCpu,
                (long) currentMemory, (long) newMemory, currentServiceOffering, newServiceOffering, template);

        // Dynamically upgrade the running vms
        boolean success = false;
        if (vmInstance.getState().equals(State.Running)) {
            int retry = _scaleRetry;
            ExcludeList excludes = new ExcludeList();

            // Check zone wide flag
            boolean enableDynamicallyScaleVm = EnableDynamicallyScaleVm.valueIn(vmInstance.getDataCenterId());
            if (!enableDynamicallyScaleVm) {
                throw new PermissionDeniedException("Dynamically scaling virtual machines is disabled for this zone, please contact your admin.");
            }

            // Check vm flag
            if (!vmInstance.isDynamicallyScalable()) {
                throw new CloudRuntimeException(String.format("Unable to scale %s as it does not have tools to support dynamic scaling.", vmInstance.toString()));
            }

            // Check disable threshold for cluster is not crossed
            HostVO host = _hostDao.findById(vmInstance.getHostId());
            _hostDao.loadDetails(host);
            if (_capacityMgr.checkIfClusterCrossesThreshold(host.getClusterId(), cpuDiff, memoryDiff)) {
                throw new CloudRuntimeException(String.format("Unable to scale %s due to insufficient resources.", vmInstance.toString()));
            }

            while (retry-- != 0) { // It's != so that it can match -1.
                try {
                    boolean existingHostHasCapacity = false;

                    // Increment CPU and Memory count accordingly.
                    _resourceLimitMgr.updateVmResourceCountForServiceOfferingChange(caller.getAccountId(), vmInstance.isDisplay(),
                            (long) currentCpu, (long) newCpu, (long) currentMemory, (long) newMemory,
                            currentServiceOffering, newServiceOffering, template);

                    // #1 Check existing host has capacity & and the correct tags
                    if (!excludes.shouldAvoid(ApiDBUtils.findHostById(vmInstance.getHostId()))) {
                        existingHostHasCapacity = _capacityMgr.checkIfHostHasCpuCapability(host, newCpu, newSpeed)
                                && _capacityMgr.checkIfHostHasCapacity(host, cpuDiff, ByteScaleUtils.mebibytesToBytes(memoryDiff), false,
                                        _capacityMgr.getClusterOverProvisioningFactor(host.getClusterId(), Capacity.CAPACITY_TYPE_CPU),
                                        _capacityMgr.getClusterOverProvisioningFactor(host.getClusterId(), Capacity.CAPACITY_TYPE_MEMORY), false)
                                && checkEnforceStrictHostTagCheck(vmInstance, host);
                        excludes.addHost(vmInstance.getHostId());
                    }

                    // #2 migrate the vm if host doesn't have capacity or is in avoid set
                    if (!existingHostHasCapacity) {
                        _itMgr.findHostAndMigrate(vmInstance.getUuid(), newServiceOfferingId, customParameters, excludes);
                    }

                    // #3 resize or migrate the root volume if required
                    DiskOfferingVO newDiskOffering = _diskOfferingDao.findById(newServiceOffering.getDiskOfferingId());
                    changeDiskOfferingForRootVolume(vmId, newDiskOffering, customParameters, vmInstance.getDataCenterId());

                    // #4 scale the vm now
                    vmInstance = _vmInstanceDao.findById(vmId);
                    _itMgr.reConfigureVm(vmInstance.getUuid(), currentServiceOffering, newServiceOffering, customParameters, existingHostHasCapacity);
                    success = true;
                    return success;
                } catch (InsufficientCapacityException | ResourceUnavailableException | ConcurrentOperationException e) {
                    logger.error(String.format("Unable to scale %s due to [%s].", vmInstance.toString(), e.getMessage()), e);
                } finally {
                    if (!success) {
                        // Decrement CPU and Memory count accordingly.
                        _resourceLimitMgr.updateVmResourceCountForServiceOfferingChange(caller.getAccountId(), vmInstance.isDisplay(),
                                (long) newCpu, (long) currentCpu, (long) newMemory, (long) currentMemory,
                                newServiceOffering, currentServiceOffering, template);
                    }
                }
            }
        }
        return success;
    }

    protected void validateDiskOfferingChecks(ServiceOfferingVO currentServiceOffering, ServiceOfferingVO newServiceOffering) {
        if (currentServiceOffering.getDiskOfferingStrictness() != newServiceOffering.getDiskOfferingStrictness()) {
            throw new InvalidParameterValueException("Unable to Scale VM, since disk offering strictness flag is not same for new service offering and old service offering");
        }

        if (currentServiceOffering.getDiskOfferingStrictness() && !currentServiceOffering.getDiskOfferingId().equals(newServiceOffering.getDiskOfferingId())) {
            throw new InvalidParameterValueException("Unable to Scale VM, since disk offering id associated with the old service offering is not same for new service offering");
        }

        _volService.validateChangeDiskOfferingEncryptionType(currentServiceOffering.getDiskOfferingId(), newServiceOffering.getDiskOfferingId());
    }

    private void changeDiskOfferingForRootVolume(Long vmId, DiskOfferingVO newDiskOffering, Map<String, String> customParameters, Long zoneId) throws ResourceAllocationException {

        if (!AllowDiskOfferingChangeDuringScaleVm.valueIn(zoneId)) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Changing the disk offering of the root volume during the compute offering change operation is disabled. Please check the setting [%s].", AllowDiskOfferingChangeDuringScaleVm.key()));
            }
            return;
        }

        List<VolumeVO> vols = _volsDao.findReadyAndAllocatedRootVolumesByInstance(vmId);

        for (final VolumeVO rootVolumeOfVm : vols) {
            DiskOfferingVO currentRootDiskOffering = _diskOfferingDao.findById(rootVolumeOfVm.getDiskOfferingId());
            Long rootDiskSize= null;
            Long rootDiskSizeBytes = null;
            if (customParameters.containsKey(ApiConstants.ROOT_DISK_SIZE)) {
                rootDiskSize = Long.parseLong(customParameters.get(ApiConstants.ROOT_DISK_SIZE));
                rootDiskSizeBytes = rootDiskSize << 30;
            }
            if (currentRootDiskOffering.getId() == newDiskOffering.getId() &&
                    (!newDiskOffering.isCustomized() || (newDiskOffering.isCustomized() && Objects.equals(rootVolumeOfVm.getSize(), rootDiskSizeBytes)))) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Volume {} is already having disk offering {}", rootVolumeOfVm, newDiskOffering);
                }
                continue;
            }
            HypervisorType hypervisorType = _volsDao.getHypervisorType(rootVolumeOfVm.getId());
            if (HypervisorType.Simulator != hypervisorType) {
                Long minIopsInNewDiskOffering = null;
                Long maxIopsInNewDiskOffering = null;
                boolean autoMigrate = false;
                boolean shrinkOk = false;
                if (customParameters.containsKey(MIN_IOPS)) {
                    minIopsInNewDiskOffering = Long.parseLong(customParameters.get(MIN_IOPS));
                }

                if (customParameters.containsKey(IOPS_LIMIT)) {
                    maxIopsInNewDiskOffering = Long.parseLong(customParameters.get(IOPS_LIMIT));
                } else if (customParameters.containsKey(MAX_IOPS)) {
                    maxIopsInNewDiskOffering = Long.parseLong(customParameters.get(MAX_IOPS));
                }
                if (customParameters.containsKey(ApiConstants.AUTO_MIGRATE)) {
                    autoMigrate = Boolean.parseBoolean(customParameters.get(ApiConstants.AUTO_MIGRATE));
                }
                if (customParameters.containsKey(ApiConstants.SHRINK_OK)) {
                    shrinkOk = Boolean.parseBoolean(customParameters.get(ApiConstants.SHRINK_OK));
                }
                ChangeOfferingForVolumeCmd changeOfferingForVolumeCmd = new ChangeOfferingForVolumeCmd(rootVolumeOfVm.getId(), newDiskOffering.getId(), minIopsInNewDiskOffering, maxIopsInNewDiskOffering, autoMigrate, shrinkOk);
                if (rootDiskSize != null) {
                    changeOfferingForVolumeCmd.setSize(rootDiskSize);
                }
                Volume result = _volumeService.changeDiskOfferingForVolume(changeOfferingForVolumeCmd);
                if (result == null) {
                    throw new CloudRuntimeException("Failed to change disk offering of the root volume");
                }
            } else if (newDiskOffering.getDiskSize() > 0 && currentRootDiskOffering.getDiskSize() != newDiskOffering.getDiskSize()) {
                throw new InvalidParameterValueException("Hypervisor " + hypervisorType + " does not support volume resize");
            }
        }
    }

    @Override
    public HashMap<String, VolumeStatsEntry> getVolumeStatistics(long clusterId, String poolUuid, StoragePoolType poolType,  int timeout) {
        List<HostVO> neighbors = _resourceMgr.listHostsInClusterByStatus(clusterId, Status.Up);
        StoragePoolVO storagePool = _storagePoolDao.findPoolByUUID(poolUuid);
        HashMap<String, VolumeStatsEntry> volumeStatsByUuid = new HashMap<>();

        for (HostVO neighbor : neighbors) {

            // - zone wide storage for specific hypervisortypes
            if ((ScopeType.ZONE.equals(storagePool.getScope()) && storagePool.getHypervisor() != neighbor.getHypervisorType())) {
                // skip this neighbour if their hypervisor type is not the same as that of the store
                continue;
            }

            List<String> volumeLocators = getVolumesByHost(neighbor, storagePool);
            if (!CollectionUtils.isEmpty(volumeLocators)) {

                GetVolumeStatsCommand cmd = new GetVolumeStatsCommand(poolType, poolUuid, volumeLocators);
                Answer answer = null;

                DataStoreProvider storeProvider = _dataStoreProviderMgr
                        .getDataStoreProvider(storagePool.getStorageProviderName());
                DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();

                if (storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver) storeDriver).canProvideVolumeStats()) {
                    // Get volume stats from the pool directly instead of sending cmd to host
                    answer = storageManager.getVolumeStats(storagePool, cmd);
                } else {
                    if (timeout > 0) {
                        cmd.setWait(timeout/1000);
                    }

                    answer = _agentMgr.easySend(neighbor.getId(), cmd);
                }

                if (answer != null && answer instanceof GetVolumeStatsAnswer){
                    GetVolumeStatsAnswer volstats = (GetVolumeStatsAnswer)answer;
                    if (volstats.getVolumeStats() != null) {
                        volumeStatsByUuid.putAll(volstats.getVolumeStats());
                    }
                }
            }
        }
        return volumeStatsByUuid.size() > 0 ? volumeStatsByUuid : null;
    }

    private List<String> getVolumesByHost(HostVO host, StoragePool pool){
        List<VMInstanceVO> vmsPerHost = _vmInstanceDao.listByHostId(host.getId());
        return vmsPerHost.stream()
                .flatMap(vm -> _volsDao.findByInstanceIdAndPoolId(vm.getId(),pool.getId()).stream().map(vol ->
                vol.getState() == Volume.State.Ready ? (vol.getFormat() == ImageFormat.OVA ? vol.getChainInfo() : vol.getPath()) : null).filter(Objects::nonNull))
                .collect(Collectors.toList());
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VM_RECOVER, eventDescription = "Recovering VM")
    public UserVm recoverVirtualMachine(RecoverVMCmd cmd) throws ResourceAllocationException, CloudRuntimeException {

        final Long vmId = cmd.getId();
        Account caller = CallContext.current().getCallingAccount();
        final Long userId = caller.getAccountId();

        // Verify input parameters
        final UserVmVO vm = _vmDao.findById(vmId);

        if (vm == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        }
        if (UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }

        // When trying to expunge, permission is denied when the caller is not an admin and the AllowUserExpungeRecoverVm is false for the caller.
        if (!_accountMgr.isAdmin(userId) && !AllowUserExpungeRecoverVm.valueIn(userId)) {
            throw new PermissionDeniedException("Recovering a vm can only be done by an Admin. Or when the allow.user.expunge.recover.vm key is set.");
        }

        if (vm.getRemoved() != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to find vm. vm is removed: {}", vm);
            }
            throw new InvalidParameterValueException("Unable to find vm by id " + vm.getUuid());
        }

        if (vm.getState() != State.Destroyed) {
            if (logger.isDebugEnabled()) {
                logger.debug("vm {} is not in the Destroyed state. current sate: {}", vm, vm.getState());
            }
            throw new InvalidParameterValueException("Vm with id " + vm.getUuid() + " is not in the right state");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Recovering vm {}", vm);
        }

        Transaction.execute(new TransactionCallbackWithExceptionNoReturn<ResourceAllocationException>() {
            @Override public void doInTransactionWithoutResult(TransactionStatus status) throws ResourceAllocationException {

                Account account = _accountDao.lockRow(vm.getAccountId(), true);

                // if the account is deleted, throw error
                if (account.getRemoved() != null) {
                    throw new CloudRuntimeException("Unable to recover VM as the account is deleted");
                }

                // Get serviceOffering for Virtual Machine
                ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
                VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());

                // First check that the maximum number of UserVMs, CPU and Memory limit for the given
                // accountId will not be exceeded
                if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
                    resourceLimitService.checkVmResourceLimit(account, vm.isDisplayVm(), serviceOffering, template);
                }

                _haMgr.cancelDestroy(vm, vm.getHostId());

                try {
                    if (!_itMgr.stateTransitTo(vm, VirtualMachine.Event.RecoveryRequested, null)) {
                        logger.debug("Unable to recover the vm {} because it is not in the correct state. current state: {}", vm, vm.getState());
                        throw new InvalidParameterValueException(String.format("Unable to recover the vm %s because it is not in the correct state. current state: %s", vm, vm.getState()));
                    }
                } catch (NoTransitionException e) {
                    throw new InvalidParameterValueException(String.format("Unable to recover the vm %s because it is not in the correct state. current state: %s", vm, vm.getState()));
                }

                // Recover the VM's disks
                List<VolumeVO> volumes = _volsDao.findByInstance(vmId);
                for (VolumeVO volume : volumes) {
                    if (volume.getVolumeType().equals(Volume.Type.ROOT)) {
                        recoverRootVolume(volume, vmId);
                        break;
                    }
                }

                //Update Resource Count for the given account
                resourceCountIncrement(account.getId(), vm.isDisplayVm(), serviceOffering, template);
            }
        });

        return _vmDao.findById(vmId);
    }

    protected void recoverRootVolume(VolumeVO volume, Long vmId) {
        if (Volume.State.Destroy.equals(volume.getState())) {
            _volumeService.recoverVolume(volume.getId());
            _volsDao.attachVolume(volume.getId(), vmId, ROOT_DEVICE_ID);
        } else {
            _volumeService.publishVolumeCreationUsageEvent(volume);
        }
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        _name = name;

        if (_configDao == null) {
            throw new ConfigurationException("Unable to get the configuration dao.");
        }

        Map<String, String> configs = _configDao.getConfiguration("AgentManager", params);

        _instance = configs.get("instance.name");
        if (_instance == null) {
            _instance = "DEFAULT";
        }

        String workers = configs.get("expunge.workers");
        int wrks = NumbersUtil.parseInt(workers, 10);
        capacityReleaseInterval = NumbersUtil.parseInt(_configDao.getValue(Config.CapacitySkipcountingHours.key()), 3600);

        String time = configs.get("expunge.interval");
        _expungeInterval = NumbersUtil.parseInt(time, 86400);
        time = configs.get("expunge.delay");
        _expungeDelay = NumbersUtil.parseInt(time, _expungeInterval);

        _executor = Executors.newScheduledThreadPool(wrks, new NamedThreadFactory("UserVm-Scavenger"));

        String vmIpWorkers = configs.get(VmIpFetchTaskWorkers.value());
        int vmipwrks = NumbersUtil.parseInt(vmIpWorkers, 10);

        _vmIpFetchExecutor =   Executors.newScheduledThreadPool(vmipwrks, new NamedThreadFactory("UserVm-ipfetch"));

        String aggregationRange = configs.get("usage.stats.job.aggregation.range");
        int _usageAggregationRange  = NumbersUtil.parseInt(aggregationRange, 1440);
        int HOURLY_TIME = 60;
        final int DAILY_TIME = 60 * 24;
        if (_usageAggregationRange == DAILY_TIME) {
            _dailyOrHourly = true;
        } else if (_usageAggregationRange == HOURLY_TIME) {
            _dailyOrHourly = true;
        } else {
            _dailyOrHourly = false;
        }

        _itMgr.registerGuru(VirtualMachine.Type.User, this);

        VirtualMachine.State.getStateMachine().registerListener(new UserVmStateListener(_usageEventDao, _networkDao, _nicDao, serviceOfferingDao, _vmDao, this, _configDao));

        String value = _configDao.getValue(Config.SetVmInternalNameUsingDisplayName.key());
        _instanceNameFlag = (value == null) ? false : Boolean.parseBoolean(value);

        _scaleRetry = NumbersUtil.parseInt(configs.get(Config.ScaleRetry.key()), 2);

        _vmIpFetchThreadExecutor = Executors.newFixedThreadPool(VmIpFetchThreadPoolMax.value(), new NamedThreadFactory("vmIpFetchThread"));

        logger.info("User VM Manager is configured.");

        return true;
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    public boolean start() {
        _executor.scheduleWithFixedDelay(new ExpungeTask(), _expungeInterval, _expungeInterval, TimeUnit.SECONDS);
        _vmIpFetchExecutor.scheduleWithFixedDelay(new VmIpFetchTask(), VmIpFetchWaitInterval.value(), VmIpFetchWaitInterval.value(), TimeUnit.SECONDS);
        loadVmDetailsInMapForExternalDhcpIp();
        return true;
    }

    private void loadVmDetailsInMapForExternalDhcpIp() {

        List<NetworkVO> networks = _networkDao.listByGuestType(Network.GuestType.Shared);
        networks.addAll(_networkDao.listByGuestType(Network.GuestType.L2));

        for (NetworkVO network: networks) {
            if (GuestType.L2.equals(network.getGuestType()) || _networkModel.isSharedNetworkWithoutServices(network.getId())) {
                List<NicVO> nics = _nicDao.listByNetworkId(network.getId());

                for (NicVO nic : nics) {
                    if (nic.getIPv4Address() == null) {
                        long nicId = nic.getId();
                        long vmId = nic.getInstanceId();
                        VMInstanceVO vmInstance = _vmInstanceDao.findById(vmId);

                        // only load running vms. For stopped vms get loaded on starting
                        if (vmInstance != null && vmInstance.getState() == State.Running) {
                            VmAndCountDetails vmAndCount = new VmAndCountDetails(vmId, VmIpFetchTrialMax.value());
                            vmIdCountMap.put(nicId, vmAndCount);
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean stop() {
        _executor.shutdown();
        _vmIpFetchExecutor.shutdown();
        return true;
    }

    public String getRandomPrivateTemplateName() {
        return UUID.randomUUID().toString();
    }

    @Override
    public boolean expunge(UserVmVO vm) {
        vm = _vmDao.acquireInLockTable(vm.getId());
        if (vm == null) {
            return false;
        }
        try {

            if (vm.getBackupOfferingId() != null) {
                List<Backup> backupsForVm = backupDao.listByVmId(vm.getDataCenterId(), vm.getId());
                if (CollectionUtils.isEmpty(backupsForVm)) {
                    backupManager.removeVMFromBackupOffering(vm.getId(), true);
                } else {
                    throw new CloudRuntimeException(String.format("This VM [uuid: %s, name: %s] has a "
                            + "Backup Offering [id: %s, external id: %s] with %s backups. Please, remove the backup offering "
                            + "before proceeding to VM exclusion!", vm.getUuid(), vm.getInstanceName(), vm.getBackupOfferingId(),
                            vm.getBackupExternalId(), backupsForVm.size()));
                }
            }

            autoScaleManager.removeVmFromVmGroup(vm.getId());

            releaseNetworkResourcesOnExpunge(vm.getId());

            List<VolumeVO> rootVol = _volsDao.findByInstanceAndType(vm.getId(), Volume.Type.ROOT);
            // expunge the vm
            _itMgr.advanceExpunge(vm.getUuid());

            // Only if vm is not expunged already, cleanup it's resources
            if (vm.getRemoved() == null) {
                // Cleanup vm resources - all the PF/LB/StaticNat rules
                // associated with vm
                logger.debug("Starting cleaning up vm " + vm + " resources...");
                if (cleanupVmResources(vm)) {
                    logger.debug("Successfully cleaned up vm " + vm + " resources as a part of expunge process");
                } else {
                    logger.warn("Failed to cleanup resources as a part of vm " + vm + " expunge");
                    return false;
                }

                if (vm.getUserDataId() != null) {
                    vm.setUserDataId(null);
                    _vmDao.update(vm.getId(), vm);
                }

                _vmDao.remove(vm.getId());
            }

            return true;

        } catch (ResourceUnavailableException e) {
            logger.warn("Unable to expunge  " + vm, e);
            return false;
        } catch (OperationTimedoutException e) {
            logger.warn("Operation time out on expunging " + vm, e);
            return false;
        } catch (ConcurrentOperationException e) {
            logger.warn("Concurrent operations on expunging " + vm, e);
            return false;
        } finally {
            _vmDao.releaseFromLockTable(vm.getId());
        }
    }

    /**
     * Release network resources, it was done on vm stop previously.
     * @param id vm id
     * @throws ConcurrentOperationException
     * @throws ResourceUnavailableException
     */
    private void releaseNetworkResourcesOnExpunge(long id) throws ConcurrentOperationException, ResourceUnavailableException {
        final VMInstanceVO vmInstance = _vmDao.findById(id);
        if (vmInstance != null){
            final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vmInstance);
            _networkMgr.release(profile, false);
        }
        else {
            logger.error("Couldn't find vm with id = " + id + ", unable to release network resources");
        }
    }

    private boolean cleanupVmResources(UserVmVO vm) {
        long vmId = vm.getId();
        boolean success = true;
        // Remove vm from security groups
        _securityGroupMgr.removeInstanceFromGroups(vm);

        // Remove vm from instance group
        removeInstanceFromInstanceGroup(vmId);

        // cleanup firewall rules
        if (_firewallMgr.revokeFirewallRulesForVm(vmId)) {
            logger.debug("Firewall rules are removed successfully as a part of vm {} expunge", vm);
        } else {
            success = false;
            logger.warn("Fail to remove firewall rules as a part of vm {} expunge", vm);
        }

        // cleanup port forwarding rules
        VMInstanceVO vmInstanceVO = _vmInstanceDao.findById(vmId);
        NsxProviderVO nsx = nsxProviderDao.findByZoneId(vmInstanceVO.getDataCenterId());
        if (Objects.isNull(nsx) || Objects.isNull(kubernetesServiceHelpers.get(0).findByVmId(vmId))) {
            if (_rulesMgr.revokePortForwardingRulesForVm(vmId)) {
                logger.debug("Port forwarding rules are removed successfully as a part of vm {} expunge", vm);
            } else {
                success = false;
                logger.warn("Fail to remove port forwarding rules as a part of vm {} expunge", vm);
            }
        }

        // cleanup load balancer rules
        if (_lbMgr.removeVmFromLoadBalancers(vmId)) {
            logger.debug("Removed vm {} from all load balancers as a part of expunge process", vm);
        } else {
            success = false;
            logger.warn("Fail to remove vm {} from load balancers as a part of expunge process", vm);
        }

        // If vm is assigned to static nat, disable static nat for the ip
        // address and disassociate ip if elasticIP is enabled
        List<IPAddressVO> ips = _ipAddressDao.findAllByAssociatedVmId(vmId);

        for (IPAddressVO ip : ips) {
            try {
                if (_rulesMgr.disableStaticNat(ip.getId(), _accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM), User.UID_SYSTEM, true)) {
                    logger.debug("Disabled 1-1 nat for ip address {} as a part of vm {} expunge", ip, vm);
                } else {
                    logger.warn("Failed to disable static nat for ip address {} as a part of vm {} expunge", ip, vm);
                    success = false;
                }
            } catch (ResourceUnavailableException e) {
                success = false;
                logger.warn("Failed to disable static nat for ip address {} as a part of vm {} expunge because resource is unavailable", ip, vm, e);
            }
        }

        return success;
    }

    @Override
    public void deletePrivateTemplateRecord(Long templateId) {
        if (templateId != null) {
            _templateDao.remove(templateId);
        }
    }

    // used for vm transitioning to error state
    private void updateVmStateForFailedVmCreation(Long vmId, Long hostId) {

        UserVmVO vm = _vmDao.findById(vmId);

        if (vm != null) {
            if (vm.getState().equals(State.Stopped)) {
                HostVO host = _hostDao.findById(hostId);
                logger.debug("Destroying vm {} as it failed to create on Host: {} with id {}", vm, host, hostId);
                try {
                    _itMgr.stateTransitTo(vm, VirtualMachine.Event.OperationFailedToError, null);
                } catch (NoTransitionException e1) {
                    logger.warn(e1.getMessage());
                }
                // destroy associated volumes for vm in error state
                // get all volumes in non destroyed state
                List<VolumeVO> volumesForThisVm = _volsDao.findUsableVolumesForInstance(vm.getId());
                for (VolumeVO volume : volumesForThisVm) {
                    if (volume.getState() != Volume.State.Destroy) {
                        volumeMgr.destroyVolume(volume);
                    }
                }
                String msg = String.format("Failed to deploy Vm %s, on Host %s with Id: %d", vm, host, hostId);
                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);

                // Get serviceOffering and template for Virtual Machine
                ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
                VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());

                // Update Resource Count for the given account
                resourceCountDecrement(vm.getAccountId(), vm.isDisplayVm(), offering, template);
            }
        }
    }



    private class VmIpFetchTask extends ManagedContextRunnable {

        @Override
        protected void runInContext() {
            GlobalLock scanLock = GlobalLock.getInternLock("vmIpFetch");
            try {
                if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                    try {

                        for (Entry<Long, VmAndCountDetails> entry:   vmIdCountMap.entrySet()) {
                            long nicId = entry.getKey();
                            VmAndCountDetails vmIdAndCount = entry.getValue();
                            long vmId = vmIdAndCount.getVmId();

                            if (vmIdAndCount.getRetrievalCount() <= 0) {
                                vmIdCountMap.remove(nicId);
                                logger.debug("Vm " + vmId +" nic "+nicId + " count is zero .. removing vm nic from map ");

                                ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM,
                                        Domain.ROOT_DOMAIN, EventTypes.EVENT_NETWORK_EXTERNAL_DHCP_VM_IPFETCH,
                                        "VM " + vmId + " nic id "+ nicId + " ip addr fetch failed ", vmId, ApiCommandResourceType.VirtualMachine.toString());

                                continue;
                            }


                            UserVm userVm = _vmDao.findById(vmId);
                            VMInstanceVO vmInstance = _vmInstanceDao.findById(vmId);
                            NicVO nicVo = _nicDao.findById(nicId);
                            NetworkVO network = _networkDao.findById(nicVo.getNetworkId());

                            VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(userVm);
                            VirtualMachine vm = vmProfile.getVirtualMachine();
                            boolean isWindows = _guestOSCategoryDao.findById(_guestOSDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");
                            _vmIpFetchThreadExecutor.execute(new VmIpAddrFetchThread(vmId, nicId, vmInstance.getInstanceName(),
                                    isWindows, vm.getHostId(), network.getCidr(), nicVo.getMacAddress()));

                        }
                    } catch (Exception e) {
                        logger.error("Caught the Exception in VmIpFetchTask", e);
                    } finally {
                        scanLock.unlock();
                    }
                }
            } finally {
                scanLock.releaseRef();
            }

        }
    }


    private class ExpungeTask extends ManagedContextRunnable {
        public ExpungeTask() {
        }

        @Override
        protected void runInContext() {
            GlobalLock scanLock = GlobalLock.getInternLock("UserVMExpunge");
            try {
                if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                    try {
                        List<UserVmVO> vms = _vmDao.findDestroyedVms(new Date(System.currentTimeMillis() - ((long)_expungeDelay << 10)));
                        if (logger.isInfoEnabled()) {
                            if (vms.size() == 0) {
                                logger.trace("Found " + vms.size() + " vms to expunge.");
                            } else {
                                logger.info("Found " + vms.size() + " vms to expunge.");
                            }
                        }
                        for (UserVmVO vm : vms) {
                            try {
                                expungeVm(vm.getId());
                            } catch (Exception e) {
                                logger.warn("Unable to expunge " + vm, e);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Caught the following Exception", e);
                    } finally {
                        scanLock.unlock();
                    }
                }
            } finally {
                scanLock.releaseRef();
            }
        }
    }

    protected void verifyVmLimits(UserVmVO vmInstance, Map<String, String> details) {
        Account owner = _accountDao.findById(vmInstance.getAccountId());
        if (owner == null) {
            throw new InvalidParameterValueException("The owner of " + vmInstance + " does not exist: " + vmInstance.getAccountId());
        }

        long newCpu = NumberUtils.toLong(details.get(VmDetailConstants.CPU_NUMBER));
        long newMemory = NumberUtils.toLong(details.get(VmDetailConstants.MEMORY));
        ServiceOfferingVO currentServiceOffering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        ServiceOfferingVO svcOffering = serviceOfferingDao.findById(vmInstance.getServiceOfferingId());
        boolean isDynamic = currentServiceOffering.isDynamic();
        if (isDynamic) {
            Map<String, String> customParameters = new HashMap<>();
            customParameters.put(VmDetailConstants.CPU_NUMBER, String.valueOf(newCpu));
            customParameters.put(VmDetailConstants.MEMORY, String.valueOf(newMemory));
            if (svcOffering.isCustomCpuSpeedSupported()) {
                customParameters.put(VmDetailConstants.CPU_SPEED, details.get(VmDetailConstants.CPU_SPEED));
            }
            validateCustomParameters(svcOffering, customParameters);
        }
        if (VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            return;
        }
        long currentCpu = currentServiceOffering.getCpu();
        long currentMemory = currentServiceOffering.getRamSize();
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
        Long currentGpu = currentServiceOffering.getGpuCount() != null ? Long.valueOf(currentServiceOffering.getGpuCount()) : 0L;
        Long newGpu = svcOffering.getGpuCount() != null ? Long.valueOf(svcOffering.getGpuCount()) : 0L;
        try {
            checkVmLimits(owner, vmInstance, svcOffering, template, newCpu, currentCpu, newMemory, currentMemory, newGpu, currentGpu);
        } catch (ResourceAllocationException e) {
            logger.error(String.format("Failed to updated VM due to: %s", e.getLocalizedMessage()));
            throw new InvalidParameterValueException(e.getLocalizedMessage());
        }
        adjustVmLimits(owner, vmInstance, svcOffering, template, newCpu, currentCpu, newMemory, currentMemory, newGpu, currentGpu);
    }

    private void checkVmLimits(Account owner, UserVmVO vmInstance, ServiceOfferingVO svcOffering,
            VMTemplateVO template, Long newCpu, Long currentCpu, Long newMemory, Long currentMemory,
            Long newGpu, Long currentGpu
    ) throws ResourceAllocationException {
        if (newCpu > currentCpu) {
            _resourceLimitMgr.checkVmCpuResourceLimit(owner, vmInstance.isDisplay(), svcOffering,
                    template, newCpu - currentCpu);
        }
        if (newMemory > currentMemory) {
            _resourceLimitMgr.checkVmMemoryResourceLimit(owner, vmInstance.isDisplay(), svcOffering,
                    template, newMemory - currentMemory);
        }
        if (newGpu > currentGpu) {
            _resourceLimitMgr.checkVmGpuResourceLimit(owner, vmInstance.isDisplay(), svcOffering,
                    template, newGpu - currentGpu);
        }
    }

    private void adjustVmLimits(Account owner, UserVmVO vmInstance, ServiceOfferingVO svcOffering,
            VMTemplateVO template, Long newCpu, Long currentCpu, Long newMemory, Long currentMemory,
            Long newGpu, Long currentGpu
    ) {
        if (newCpu > currentCpu) {
            _resourceLimitMgr.incrementVmCpuResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, newCpu - currentCpu);
        } else if (newCpu > 0 && currentCpu > newCpu){
            _resourceLimitMgr.decrementVmCpuResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, currentCpu - newCpu);
        }
        if (newMemory > currentMemory) {
            _resourceLimitMgr.incrementVmMemoryResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, newMemory - currentMemory);
        } else if (newMemory > 0 && currentMemory > newMemory){
            _resourceLimitMgr.decrementVmMemoryResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, currentMemory - newMemory);
        }
        if (newGpu > currentGpu) {
            _resourceLimitMgr.incrementVmGpuResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, newGpu - currentGpu);
        } else if (newGpu > 0 && currentGpu > newGpu){
            _resourceLimitMgr.decrementVmGpuResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, currentGpu - newGpu);
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_UPDATE, eventDescription = "updating Vm")
    public UserVm updateVirtualMachine(UpdateVMCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException {
        validateInputsAndPermissionForUpdateVirtualMachineCommand(cmd);

        String displayName = cmd.getDisplayName();
        String group = cmd.getGroup();
        Boolean ha = cmd.getHaEnable();
        Boolean isDisplayVm = cmd.getDisplayVm();
        Long id = cmd.getId();
        Long osTypeId = cmd.getOsTypeId();
        Boolean isDynamicallyScalable = cmd.isDynamicallyScalable();
        String hostName = cmd.getHostName();
        Map<String,String> details = cmd.getDetails();
        List<Long> securityGroupIdList = getSecurityGroupIdList(cmd);
        boolean cleanupDetails = cmd.isCleanupDetails();
        String extraConfig = cmd.getExtraConfig();

        UserVmVO vmInstance = _vmDao.findById(cmd.getId());
        VMTemplateVO template = _templateDao.findById(vmInstance.getTemplateId());
        if (MapUtils.isNotEmpty(details) || cmd.isCleanupDetails()) {
            if (template != null && template.isDeployAsIs()) {
                throw new CloudRuntimeException("Detail settings are read from OVA, it cannot be changed by API call.");
            }
        }
        UserVmVO userVm = _vmDao.findById(cmd.getId());
        if (userVm != null && UserVmManager.SHAREDFSVM.equals(userVm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }

        String userData = cmd.getUserData();
        Long userDataId = cmd.getUserdataId();
        String userDataDetails = null;
        if (MapUtils.isNotEmpty(cmd.getUserdataDetails())) {
            userDataDetails = cmd.getUserdataDetails().toString();
        }
        userData = finalizeUserData(userData, userDataId, template);
        userData = userDataManager.validateUserData(userData, cmd.getHttpMethod());

        long accountId = vmInstance.getAccountId();

        if (isDisplayVm != null && isDisplayVm != vmInstance.isDisplay()) {
            updateDisplayVmFlag(isDisplayVm, id, vmInstance);
        }
        final Account caller = CallContext.current().getCallingAccount();
        final List<String> userDenyListedSettings = Stream.of(QueryService.UserVMDeniedDetails.value().split(","))
                .map(item -> (item).trim())
                .collect(Collectors.toList());
        userDenyListedSettings.addAll(QueryService.RootAdminOnlyVmSettings);
        final List<String> userReadOnlySettings = Stream.of(QueryService.UserVMReadOnlyDetails.value().split(","))
                .map(item -> (item).trim())
                .collect(Collectors.toList());
        List<VMInstanceDetailVO> existingDetails = vmInstanceDetailsDao.listDetails(id);
        if (cleanupDetails){
            if (caller != null && caller.getType() == Account.Type.ADMIN) {
                for (final VMInstanceDetailVO detail : existingDetails) {
                    if (detail != null && detail.isDisplay() && !isExtraConfig(detail.getName())) {
                        vmInstanceDetailsDao.removeDetail(id, detail.getName());
                    }
                }
            } else {
                for (final VMInstanceDetailVO detail : existingDetails) {
                    if (detail != null && !userDenyListedSettings.contains(detail.getName())
                            && !userReadOnlySettings.contains(detail.getName()) && detail.isDisplay()
                            && !isExtraConfig(detail.getName())) {
                        vmInstanceDetailsDao.removeDetail(id, detail.getName());
                    }
                }
            }
        } else {
            if (MapUtils.isNotEmpty(details)) {
                // error out if lease related keys are passed in details
                if (details.containsKey(VmDetailConstants.INSTANCE_LEASE_EXECUTION)
                        || details.containsKey(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE)
                        || details.containsKey(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION)) {
                    throw new InvalidParameterValueException("lease parameters should not be included in details as key");
                }

                if (details.containsKey("extraconfig")) {
                    throw new InvalidParameterValueException("'extraconfig' should not be included in details as key");
                }

                details.entrySet().removeIf(detail -> isExtraConfig(detail.getKey()));

                if (caller != null && caller.getType() != Account.Type.ADMIN) {
                    // Ensure denied or read-only detail is not passed by non-root-admin user
                    for (final String detailName : details.keySet()) {
                        if (userDenyListedSettings.contains(detailName)) {
                            throw new InvalidParameterValueException("You're not allowed to add or edit the restricted setting: " + detailName);
                        }
                        if (userReadOnlySettings.contains(detailName)) {
                            throw new InvalidParameterValueException("You're not allowed to add or edit the read-only setting: " + detailName);
                        }
                        if (existingDetails.stream().anyMatch(d -> Objects.equals(d.getName(), detailName) && !d.isDisplay())){
                            throw new InvalidParameterValueException("You're not allowed to add or edit the non-displayable setting: " + detailName);
                        }
                    }
                    // Add any existing user denied or read-only details. We do it here because admins would already provide these (or can delete them).
                    for (final VMInstanceDetailVO detail : existingDetails) {
                        if (userDenyListedSettings.contains(detail.getName()) || userReadOnlySettings.contains(detail.getName())) {
                            details.put(detail.getName(), detail.getValue());
                        }
                    }
                }

                // ensure details marked as non-displayable are maintained, regardless of admin or not
                for (final VMInstanceDetailVO existingDetail : existingDetails) {
                    if (!existingDetail.isDisplay() || isExtraConfig(existingDetail.getName())) {
                        details.put(existingDetail.getName(), existingDetail.getValue());
                    }
                }

                verifyVmLimits(vmInstance, details);
                vmInstance.setDetails(details);
                _vmDao.saveDetails(vmInstance);
            }
            if (StringUtils.isNotBlank(extraConfig)) {
                if (EnableAdditionalVmConfig.valueIn(accountId)) {
                    logger.info("Adding extra configuration to user vm: " + vmInstance.getUuid());
                    addExtraConfig(vmInstance, extraConfig);
                } else {
                    throw new InvalidParameterValueException("attempted setting extraconfig but enable.additional.vm.configuration is disabled");
                }
            }
        }

        if (VMLeaseManager.InstanceLeaseEnabled.value() && cmd.getLeaseDuration() != null) {
            applyLeaseOnUpdateInstance(vmInstance, cmd.getLeaseDuration(), cmd.getLeaseExpiryAction());
        }

        return updateVirtualMachine(id, displayName, group, ha, isDisplayVm,
                cmd.getDeleteProtection(), osTypeId, userData,
                userDataId, userDataDetails, isDynamicallyScalable, cmd.getHttpMethod(),
                cmd.getCustomId(), hostName, cmd.getInstanceName(), securityGroupIdList,
                cmd.getDhcpOptionsMap());
    }

    private boolean isExtraConfig(String detailName) {
        return detailName != null && detailName.startsWith(ApiConstants.EXTRA_CONFIG);
    }

    protected void updateDisplayVmFlag(Boolean isDisplayVm, Long id, UserVmVO vmInstance) {
        vmInstance.setDisplayVm(isDisplayVm);

        // Resource limit changes
        ServiceOffering offering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
        if (isDisplayVm) {
            resourceCountIncrement(vmInstance.getAccountId(), true, offering, template);
        } else {
            resourceCountDecrement(vmInstance.getAccountId(), true, offering, template);
        }

        // Usage
        saveUsageEvent(vmInstance);

        // take care of the root volume as well.
        List<VolumeVO> rootVols = _volsDao.findByInstanceAndType(id, Volume.Type.ROOT);
        if (!rootVols.isEmpty()) {
            _volumeService.updateDisplay(rootVols.get(0), isDisplayVm);
        }

        // take care of the data volumes as well.
        List<VolumeVO> dataVols = _volsDao.findByInstanceAndType(id, Volume.Type.DATADISK);
        for (Volume dataVol : dataVols) {
            _volumeService.updateDisplay(dataVol, isDisplayVm);
        }
    }

    protected void validateInputsAndPermissionForUpdateVirtualMachineCommand(UpdateVMCmd cmd) {
        UserVmVO vmInstance = _vmDao.findById(cmd.getId());
        if (vmInstance == null) {
            throw new InvalidParameterValueException("unable to find virtual machine with id: " + cmd.getId());
        }
        validateGuestOsIdForUpdateVirtualMachineCommand(cmd);
        Account caller = CallContext.current().getCallingAccount();
        _accountMgr.checkAccess(caller, null, true, vmInstance);
    }

    protected void validateGuestOsIdForUpdateVirtualMachineCommand(UpdateVMCmd cmd) {
        Long osTypeId = cmd.getOsTypeId();
        if (osTypeId != null) {
            GuestOSVO guestOS = _guestOSDao.findById(osTypeId);
            if (guestOS == null) {
                throw new InvalidParameterValueException("Please specify a valid guest OS ID.");
            }
        }
    }

    private void saveUsageEvent(UserVmVO vm) {

        // If vm not destroyed
        if( vm.getState() != State.Destroyed && vm.getState() != State.Expunging && vm.getState() != State.Error){

            if(vm.isDisplayVm()){
                //1. Allocated VM Usage Event
                generateUsageEvent(vm, true, EventTypes.EVENT_VM_CREATE);

                if(vm.getState() == State.Running || vm.getState() == State.Stopping){
                    //2. Running VM Usage Event
                    generateUsageEvent(vm, true, EventTypes.EVENT_VM_START);

                    // 3. Network offering usage
                    generateNetworkUsageForVm(vm, true, EventTypes.EVENT_NETWORK_OFFERING_ASSIGN);
                }

            }else {
                //1. Allocated VM Usage Event
                generateUsageEvent(vm, true, EventTypes.EVENT_VM_DESTROY);

                if(vm.getState() == State.Running || vm.getState() == State.Stopping){
                    //2. Running VM Usage Event
                    generateUsageEvent(vm, true, EventTypes.EVENT_VM_STOP);

                    // 3. Network offering usage
                    generateNetworkUsageForVm(vm, true, EventTypes.EVENT_NETWORK_OFFERING_REMOVE);
                }
            }
        }

    }

    private void generateNetworkUsageForVm(VirtualMachine vm, boolean isDisplay, String eventType){

        List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (NicVO nic : nics) {
            NetworkVO network = _networkDao.findById(nic.getNetworkId());
            long isDefault = (nic.isDefaultNic()) ? 1 : 0;
            UsageEventUtils.publishUsageEvent(eventType, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                    Long.toString(nic.getId()), network.getNetworkOfferingId(), null, isDefault, vm.getClass().getName(), vm.getUuid(), isDisplay);
        }

    }

    @Override
    public UserVm updateVirtualMachine(long id, String displayName, String group, Boolean ha,
                                       Boolean isDisplayVmEnabled, Boolean deleteProtection,
                                       Long osTypeId, String userData, Long userDataId,
                                       String userDataDetails, Boolean isDynamicallyScalable,
                                       HTTPMethod httpMethod, String customId, String hostName,
                                       String instanceName, List<Long> securityGroupIdList,
                                       Map<String, Map<Integer, String>> extraDhcpOptionsMap
    ) throws ResourceUnavailableException, InsufficientCapacityException {
        UserVmVO vm = _vmDao.findById(id);
        if (vm == null) {
            throw new CloudRuntimeException("Unable to find virtual machine with id " + id);
        }

        if(instanceName != null){
            VMInstanceVO vmInstance = _vmInstanceDao.findVMByInstanceName(instanceName);
            if(vmInstance != null && vmInstance.getId() != id){
                throw new CloudRuntimeException("Instance name : " + instanceName + " is not unique");
            }
        }

        if (vm.getState() == State.Error || vm.getState() == State.Expunging) {
            logger.error("vm {} is not in the correct state. current state: {}", vm, vm.getState());
            throw new InvalidParameterValueException(String.format("Vm %s is not in the right state", vm));
        }

        if (displayName == null) {
            displayName = vm.getDisplayName();
        }

        if (ha == null) {
            ha = vm.isHaEnabled();
        }

        ServiceOffering offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
        if (!offering.isOfferHA() && ha) {
            throw new InvalidParameterValueException("Can't enable ha for the vm as it's created from the Service offering having HA disabled");
        }

        if (isDisplayVmEnabled == null) {
            isDisplayVmEnabled = vm.isDisplayVm();
        }

        if (deleteProtection == null) {
            deleteProtection = vm.isDeleteProtection();
        }

        boolean updateUserdata = false;
        if (userData != null) {
            // check and replace newlines
            userData = userData.replace("\\n", "");
            userData = userDataManager.validateUserData(userData, httpMethod);
            // update userData on domain router.
            updateUserdata = true;
        } else {
            userData = vm.getUserData();
        }

        if (userDataId == null) {
            userDataId = vm.getUserDataId();
        }

        if (userDataDetails == null) {
            userDataDetails = vm.getUserDataDetails();
        }

        if (osTypeId == null) {
            osTypeId = vm.getGuestOSId();
        }

        if (group != null) {
            addInstanceToGroup(id, group);
        }

        if (isDynamicallyScalable == null) {
            isDynamicallyScalable = vm.isDynamicallyScalable();
        } else {
            if (isDynamicallyScalable == true) {
                VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
                if (!template.isDynamicallyScalable()) {
                    throw new InvalidParameterValueException("Dynamic Scaling cannot be enabled for the VM since its template does not have dynamic scaling enabled");
                }
                if (!offering.isDynamicScalingEnabled()) {
                    throw new InvalidParameterValueException("Dynamic Scaling cannot be enabled for the VM since its service offering does not have dynamic scaling enabled");
                }
                if (!UserVmManager.EnableDynamicallyScaleVm.valueIn(vm.getDataCenterId())) {
                    logger.debug("Dynamic Scaling cannot be enabled for the VM {} since the global setting enable.dynamic.scale.vm is set to false", vm);
                    throw new InvalidParameterValueException("Dynamic Scaling cannot be enabled for the VM since corresponding global setting is set to false");
                }
            }
        }

        List<? extends Nic> nics = _nicDao.listByVmId(vm.getId());
        if (hostName != null) {
            // Check is hostName is RFC compliant
            checkNameForRFCCompliance(hostName);

            if (vm.getHostName().equals(hostName)) {
                logger.debug("Vm " + vm + " is already set with the hostName specified: " + hostName);
                hostName = null;
            }

            // Verify that vm's hostName is unique

            List<NetworkVO> vmNtwks = new ArrayList<NetworkVO>(nics.size());
            for (Nic nic : nics) {
                vmNtwks.add(_networkDao.findById(nic.getNetworkId()));
            }
            checkIfHostNameUniqueInNtwkDomain(hostName, vmNtwks);
        }

        List<NetworkVO> networks = nics.stream()
                .map(nic -> _networkDao.findById(nic.getNetworkId()))
                .collect(Collectors.toList());

        verifyExtraDhcpOptionsNetwork(extraDhcpOptionsMap, networks);
        for (Nic nic : nics) {
            _networkMgr.saveExtraDhcpOptions(networks.stream()
                    .filter(network -> network.getId() == nic.getNetworkId())
                    .findFirst()
                    .get()
                    .getUuid(), nic.getId(), extraDhcpOptionsMap);
        }

        checkAndUpdateSecurityGroupForVM(securityGroupIdList, vm, networks);

        _vmDao.updateVM(id, displayName, ha, osTypeId, userData, userDataId,
                userDataDetails, isDisplayVmEnabled, isDynamicallyScalable,
                deleteProtection, customId, hostName, instanceName);

        if (updateUserdata) {
            updateUserData(vm);
        }

        if (State.Running == vm.getState()) {
            updateDns(vm, hostName);
        }

        return _vmDao.findById(id);
    }

    private void checkAndUpdateSecurityGroupForVM(List<Long> securityGroupIdList, UserVmVO vm, List<NetworkVO> networks) {
        boolean isVMware = (vm.getHypervisorType() == HypervisorType.VMware);

        if (securityGroupIdList != null && isVMware) {
            throw new InvalidParameterValueException("Security group feature is not supported for VMware hypervisor");
        } else if (securityGroupIdList != null) {
            DataCenterVO zone = _dcDao.findById(vm.getDataCenterId());
            List<Long> networkIds = new ArrayList<>();
            try {
                if (zone.getNetworkType() == NetworkType.Basic) {
                    // Get default guest network in Basic zone
                    Network defaultNetwork = _networkModel.getExclusiveGuestNetwork(zone.getId());
                    networkIds.add(defaultNetwork.getId());
                } else {
                    networkIds = networks.stream().map(Network::getId).collect(Collectors.toList());
                }
            } catch (InvalidParameterValueException e) {
                if(logger.isDebugEnabled()) {
                    logger.debug(e.getMessage(),e);
                }
            }

            if (_networkModel.checkSecurityGroupSupportForNetwork(
                            _accountMgr.getActiveAccountById(vm.getAccountId()),
                            zone, networkIds, securityGroupIdList)
            ) {
                updateSecurityGroup(vm, securityGroupIdList);
            }
        }
    }

    private void updateSecurityGroup(UserVmVO vm, List<Long> securityGroupIdList) {
        if (vm.getState() == State.Stopped) {
            // Remove instance from security groups
            _securityGroupMgr.removeInstanceFromGroups(vm);
            // Add instance in provided groups
            _securityGroupMgr.addInstanceToGroups(vm, securityGroupIdList);
        } else {
            throw new InvalidParameterValueException(String.format("VM %s must be stopped prior to update security groups", vm.getUuid()));
        }
    }

    protected void updateUserData(UserVm vm) throws ResourceUnavailableException, InsufficientCapacityException {
        boolean result = updateUserDataInternal(vm);
        if (result) {
            logger.debug("User data successfully updated for vm id:  {}", vm);
        } else {
            throw new CloudRuntimeException("Failed to reset userdata for the virtual machine ");
        }
    }

    private void updateDns(UserVmVO vm, String hostName) throws ResourceUnavailableException, InsufficientCapacityException {
        if (!StringUtils.isEmpty(hostName)) {
            vm.setHostName(hostName);
            try {
                List<NicVO> nicVOs = _nicDao.listByVmId(vm.getId());
                for (NicVO nic : nicVOs) {
                    List<DomainRouterVO> routers = _routerDao.findByNetwork(nic.getNetworkId());
                    for (DomainRouterVO router : routers) {
                        if (router.getState() != State.Running) {
                            logger.warn("Unable to update DNS for VM {}, as virtual router: {} is not in the right state: {} ", vm, router, router.getState());
                            continue;
                        }
                        Commands commands = new Commands(Command.OnError.Stop);
                        commandSetupHelper.createDhcpEntryCommand(router, vm, nic, false, commands);
                        if (!nwHelper.sendCommandsToRouter(router, commands)) {
                            throw new CloudRuntimeException(String.format("Unable to send commands to virtual router: %s", router.getHostId()));
                        }
                        Answer answer = commands.getAnswer("dhcp");
                        if (answer == null || !answer.getResult()) {
                            throw new CloudRuntimeException("Failed to update hostname");
                        }
                        updateUserData(vm);
                    }
                }
            } catch (CloudRuntimeException e) {
                throw new CloudRuntimeException(String.format("Failed to update hostname of VM %s to %s", vm.getInstanceName(), vm.getHostName()));
            }
        }
    }

    private boolean updateUserDataInternal(UserVm vm) throws ResourceUnavailableException, InsufficientCapacityException {
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());

        List<? extends Nic> nics = _nicDao.listByVmId(vm.getId());
        if (nics == null || nics.isEmpty()) {
            logger.error("unable to find any nics for vm {}", vm);
            return false;
        }

        boolean userDataApplied = false;
        for (Nic nic : nics) {
            userDataApplied |= applyUserData(template.getHypervisorType(), vm, nic);
        }
        return userDataApplied;
    }

    protected boolean applyUserData(HypervisorType hyperVisorType, UserVm vm, Nic nic) throws ResourceUnavailableException, InsufficientCapacityException {
        Network network = _networkDao.findById(nic.getNetworkId());
        NicProfile nicProfile = new NicProfile(nic, network, null, null, null, _networkModel.isSecurityGroupSupportedInNetwork(network), _networkModel.getNetworkTag(
                hyperVisorType, network));
        VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vm);

        if (_networkModel.areServicesSupportedByNetworkOffering(network.getNetworkOfferingId(), Service.UserData)) {
            UserDataServiceProvider element = _networkModel.getUserDataUpdateProvider(network);
            if (element == null) {
                throw new CloudRuntimeException("Can't find network element for " + Service.UserData.getName() + " provider needed for UserData update");
            }
            boolean result = element.saveUserData(network, nicProfile, vmProfile);
            if (!result) {
                logger.error("Failed to update userdata for vm " + vm + " and nic " + nic);
            } else {
                return true;
            }
        } else {
            logger.debug("Not applying userdata for nic {} in vm {} because it is not supported in network {}", nic, vmProfile, network);
        }
        return false;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_START, eventDescription = "starting Vm", async = true)
    public UserVm startVirtualMachine(StartVMCmd cmd) throws ExecutionException, ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        Map<VirtualMachineProfile.Param, Object> additonalParams = new HashMap<>();
        if (cmd.getBootIntoSetup() != null) {
            if (logger.isTraceEnabled()) {
                logger.trace(String.format("Adding %s into the param map", VirtualMachineProfile.Param.BootIntoSetup.getName()));
            }
            additonalParams.put(VirtualMachineProfile.Param.BootIntoSetup, cmd.getBootIntoSetup());
        }
        VMInstanceDetailVO uefiDetail = vmInstanceDetailsDao.findDetail(cmd.getId(), ApiConstants.BootType.UEFI.toString());
        if (uefiDetail != null) {
            addVmUefiBootOptionsToParams(additonalParams, uefiDetail.getName(), uefiDetail.getValue());
        }
        if (cmd.getConsiderLastHost() != null) {
            additonalParams.put(VirtualMachineProfile.Param.ConsiderLastHost, cmd.getConsiderLastHost().toString());
        }

        return startVirtualMachine(cmd.getId(), cmd.getPodId(), cmd.getClusterId(), cmd.getHostId(), additonalParams, cmd.getDeploymentPlanner()).first();
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_START, eventDescription = "starting Vm", async = true)
    public void startVirtualMachine(UserVm vm, DeploymentPlan plan) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException {
        _itMgr.advanceStart(vm.getUuid(), null, plan, null);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_START, eventDescription = "restarting VM for HA", async = true)
    public void startVirtualMachineForHA(VirtualMachine vm, Map<VirtualMachineProfile.Param, Object> params,
           DeploymentPlanner planner) throws InsufficientCapacityException, ResourceUnavailableException,
            ConcurrentOperationException, OperationTimedoutException {
        _itMgr.advanceStart(vm.getUuid(), params, planner);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_REBOOT, eventDescription = "rebooting Vm", async = true)
    public UserVm rebootVirtualMachine(RebootVMCmd cmd) throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException {
        Account caller = CallContext.current().getCallingAccount();
        Long vmId = cmd.getId();

        // Verify input parameters
        UserVmVO vmInstance = _vmDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("Unable to find a virtual machine with id " + vmId);
        }

        if (vmInstance.getState() != State.Running) {
            throw new InvalidParameterValueException(String.format("The VM %s (%s) is not running, unable to reboot it",
                    vmInstance.getUuid(), vmInstance.getDisplayNameOrHostName()));
        }

        _accountMgr.checkAccess(caller, null, true, vmInstance);

        checkIfHostOfVMIsInPrepareForMaintenanceState(vmInstance, "Reboot");

        // If the VM is Volatile in nature, on reboot discard the VM's root disk and create a new root disk for it: by calling restoreVM
        long serviceOfferingId = vmInstance.getServiceOfferingId();
        ServiceOfferingVO offering = serviceOfferingDao.findById(vmInstance.getId(), serviceOfferingId);
        if (offering != null && offering.getRemoved() == null) {
            if (offering.isVolatileVm()) {
                return restoreVMInternal(caller, vmInstance);
            }
        } else {
            throw new InvalidParameterValueException("Unable to find service offering: " + serviceOfferingId + " corresponding to the vm");
        }

        Boolean enterSetup = cmd.getBootIntoSetup();
        if (enterSetup != null && enterSetup && !HypervisorType.VMware.equals(vmInstance.getHypervisorType())) {
            throw new InvalidParameterValueException("Booting into a hardware setup menu is not implemented on " + vmInstance.getHypervisorType());
        }

        UserVm userVm = rebootVirtualMachine(CallContext.current().getCallingUserId(), vmId, enterSetup == null ? false : cmd.getBootIntoSetup(), cmd.isForced());
        if (userVm != null ) {
            // update the vmIdCountMap if the vm is in advanced shared network with out services
            final List<NicVO> nics = _nicDao.listByVmId(vmId);
            for (NicVO nic : nics) {
                Network network = _networkModel.getNetwork(nic.getNetworkId());
                if (GuestType.L2.equals(network.getGuestType()) || _networkModel.isSharedNetworkWithoutServices(network.getId())) {
                    logger.debug("Adding vm " +vmId +" nic id "+ nic.getId() +" into vmIdCountMap as part of vm " +
                            "reboot for vm ip fetch ");
                    vmIdCountMap.put(nic.getId(), new VmAndCountDetails(nic.getInstanceId(), VmIpFetchTrialMax.value()));
                }
            }
            return  userVm;
        }
        return  null;
    }

    /**
     *  Encapsulates AllowUserExpungeRecoverVm so we can unit test checkExpungeVmPermission.
     */
    protected boolean getConfigAllowUserExpungeRecoverVm(Long accountId) {
        return AllowUserExpungeRecoverVm.valueIn(accountId);
    }

    protected void checkExpungeVmPermission (Account callingAccount) {
        logger.debug(String.format("Checking if [%s] has permission for expunging VMs.", callingAccount));
        if (!_accountMgr.isAdmin(callingAccount.getId()) && !getConfigAllowUserExpungeRecoverVm(callingAccount.getId())) {
            logger.error(String.format("Parameter [%s] can only be passed by Admin accounts or when the allow.user.expunge.recover.vm key is true.", ApiConstants.EXPUNGE));
            throw new PermissionDeniedException("Account does not have permission for expunging.");
        }
        try {
            _accountMgr.checkApiAccess(callingAccount, BaseCmd.getCommandNameByClass(ExpungeVMCmd.class));
        } catch (PermissionDeniedException ex) {
            logger.error(String.format("Role [%s] of [%s] does not have permission for expunging VMs.", callingAccount.getRoleId(), callingAccount));
            throw new PermissionDeniedException("Account does not have permission for expunging.");
        }
    }

    protected void checkPluginsIfVmCanBeDestroyed(UserVm vm) {
        try {
            KubernetesServiceHelper kubernetesServiceHelper =
                    ComponentContext.getDelegateComponentOfType(KubernetesServiceHelper.class);
            kubernetesServiceHelper.checkVmCanBeDestroyed(vm);
        } catch (NoSuchBeanDefinitionException ignored) {
            logger.debug("No KubernetesClusterHelper bean found");
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_DESTROY, eventDescription = "destroying Vm", async = true)
    public UserVm destroyVm(DestroyVMCmd cmd) throws ResourceUnavailableException, ConcurrentOperationException {
        CallContext ctx = CallContext.current();
        long vmId = cmd.getId();
        boolean expunge = cmd.getExpunge();

        if (expunge) {
            checkExpungeVmPermission(ctx.getCallingAccount());
        }

        // check if VM exists
        UserVmVO vm = _vmDao.findById(vmId);

        if (vm == null || vm.getRemoved() != null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        }
        if (UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }

        if (Arrays.asList(State.Destroyed, State.Expunging).contains(vm.getState()) && !expunge) {
            logger.debug("Vm {} is already destroyed", vm);
            return vm;
        }

        if (vm.isDeleteProtection()) {
            throw new InvalidParameterValueException(String.format(
                    "Instance [id = %s, name = %s] has delete protection enabled and cannot be deleted.",
                    vm.getUuid(), vm.getName()));
        }

        // check if vm belongs to AutoScale vm group in Disabled state
        autoScaleManager.checkIfVmActionAllowed(vmId);

        // check if vm belongs to any plugin resources
        checkPluginsIfVmCanBeDestroyed(vm);

        // check if there are active volume snapshots tasks
        logger.debug("Checking if there are any ongoing snapshots on the ROOT volumes associated with VM {}", vm);
        if (checkStatusOfVolumeSnapshots(vm, Volume.Type.ROOT)) {
            throw new CloudRuntimeException("There is/are unbacked up snapshot(s) on ROOT volume, vm destroy is not permitted, please try again later.");
        }
        logger.debug("Found no ongoing snapshots on volume of type ROOT, for the vm {}", vm);

        List<VolumeVO> volumesToBeDeleted = getVolumesFromIds(cmd);

        checkForUnattachedVolumes(vmId, volumesToBeDeleted);
        validateVolumes(volumesToBeDeleted);

        final ControlledEntity[] volumesToDelete = volumesToBeDeleted.toArray(new ControlledEntity[0]);
        _accountMgr.checkAccess(ctx.getCallingAccount(), null, true, volumesToDelete);

        stopVirtualMachine(vmId, VmDestroyForcestop.value());

        // Detach all data disks from VM
        List<VolumeVO> dataVols = _volsDao.findByInstanceAndType(vmId, Volume.Type.DATADISK);
        detachVolumesFromVm(vm, dataVols);

        UserVm destroyedVm = destroyVm(vmId, expunge);
        if (expunge && !expunge(vm)) {
            throw new CloudRuntimeException("Failed to expunge vm " + destroyedVm);
        }

        autoScaleManager.removeVmFromVmGroup(vmId);

        deleteVolumesFromVm(vm, volumesToBeDeleted, expunge);

        if (getDestroyRootVolumeOnVmDestruction(vm.getDomainId())) {
            VolumeVO rootVolume = _volsDao.getInstanceRootVolume(vm.getId());
            if (rootVolume != null) {
                _volService.destroyVolume(rootVolume.getId());
            } else {
                logger.warn("Tried to destroy ROOT volume for VM [{}], but couldn't retrieve it.", vm);
            }
        }

        return destroyedVm;
    }

    private List<VolumeVO> getVolumesFromIds(DestroyVMCmd cmd) {
        List<VolumeVO> volumes = new ArrayList<>();
        if (cmd.getVolumeIds() != null) {
            for (Long volId : cmd.getVolumeIds()) {
                VolumeVO vol = _volsDao.findById(volId);

                if (vol == null) {
                    throw new InvalidParameterValueException("Unable to find volume with ID: " + volId);
                }
                volumes.add(vol);
            }
        }
        return volumes;
    }

    @Override
    @DB
    public InstanceGroupVO createVmGroup(CreateVMGroupCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long domainId = cmd.getDomainId();
        String accountName = cmd.getAccountName();
        String groupName = cmd.getGroupName();
        Long projectId = cmd.getProjectId();

        Account owner = _accountMgr.finalizeOwner(caller, accountName, domainId, projectId);
        long accountId = owner.getId();

        // Check if name is already in use by this account
        boolean isNameInUse = _vmGroupDao.isNameInUse(accountId, groupName);

        if (isNameInUse) {
            throw new InvalidParameterValueException(String.format("Unable to create vm group, a group with name %s already exists for account %s", groupName, owner));
        }

        return createVmGroup(groupName, accountId);
    }

    @DB
    private InstanceGroupVO createVmGroup(String groupName, long accountId) {
        Account account = null;
        try {
            account = _accountDao.acquireInLockTable(accountId); // to ensure
            // duplicate
            // vm group
            // names are
            // not
            // created.
            if (account == null) {
                logger.warn("Failed to acquire lock on account");
                return null;
            }
            InstanceGroupVO group = _vmGroupDao.findByAccountAndName(accountId, groupName);
            if (group == null) {
                group = new InstanceGroupVO(groupName, accountId);
                group = _vmGroupDao.persist(group);
            }
            return group;
        } finally {
            if (account != null) {
                _accountDao.releaseFromLockTable(accountId);
            }
        }
    }

    @Override
    public boolean deleteVmGroup(DeleteVMGroupCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long groupId = cmd.getId();

        // Verify input parameters
        InstanceGroupVO group = _vmGroupDao.findById(groupId);
        if ((group == null) || (group.getRemoved() != null)) {
            throw new InvalidParameterValueException("unable to find a vm group with id " + groupId);
        }

        _accountMgr.checkAccess(caller, null, true, group);

        return deleteVmGroup(groupId);
    }

    @Override
    public boolean deleteVmGroup(long groupId) {
        InstanceGroupVO group = _vmGroupDao.findById(groupId);
        annotationDao.removeByEntityType(AnnotationService.EntityType.INSTANCE_GROUP.name(), group.getUuid());
        // delete all the mappings from group_vm_map table
        List<InstanceGroupVMMapVO> groupVmMaps = _groupVMMapDao.listByGroupId(groupId);
        for (InstanceGroupVMMapVO groupMap : groupVmMaps) {
            SearchCriteria<InstanceGroupVMMapVO> sc = _groupVMMapDao.createSearchCriteria();
            sc.addAnd("instanceId", SearchCriteria.Op.EQ, groupMap.getInstanceId());
            _groupVMMapDao.expunge(sc);
        }

        if (_vmGroupDao.remove(groupId)) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    @DB
    public boolean addInstanceToGroup(final long userVmId, String groupName) {
        UserVmVO vm = _vmDao.findById(userVmId);

        InstanceGroupVO group = _vmGroupDao.findByAccountAndName(vm.getAccountId(), groupName);
        // Create vm group if the group doesn't exist for this account
        if (group == null) {
            group = createVmGroup(groupName, vm.getAccountId());
        }

        if (group != null) {
            UserVm userVm = _vmDao.acquireInLockTable(userVmId);
            if (userVm == null) {
                logger.warn("Failed to acquire lock on user vm {} with id {}", vm, userVmId);
            }
            try {
                final InstanceGroupVO groupFinal = group;
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) {
                        // don't let the group be deleted when we are assigning vm to
                        // it.
                        InstanceGroupVO ngrpLock = _vmGroupDao.lockRow(groupFinal.getId(), false);
                        if (ngrpLock == null) {
                            logger.warn("Failed to acquire lock on vm group {}", groupFinal);
                            throw new CloudRuntimeException(String.format("Failed to acquire lock on vm group %s", groupFinal));
                        }

                        // Currently don't allow to assign a vm to more than one group
                        if (_groupVMMapDao.listByInstanceId(userVmId) != null) {
                            // Delete all mappings from group_vm_map table
                            List<InstanceGroupVMMapVO> groupVmMaps = _groupVMMapDao.listByInstanceId(userVmId);
                            for (InstanceGroupVMMapVO groupMap : groupVmMaps) {
                                SearchCriteria<InstanceGroupVMMapVO> sc = _groupVMMapDao.createSearchCriteria();
                                sc.addAnd("instanceId", SearchCriteria.Op.EQ, groupMap.getInstanceId());
                                _groupVMMapDao.expunge(sc);
                            }
                        }
                        InstanceGroupVMMapVO groupVmMapVO = new InstanceGroupVMMapVO(groupFinal.getId(), userVmId);
                        _groupVMMapDao.persist(groupVmMapVO);

                    }
                });

                return true;
            } finally {
                if (userVm != null) {
                    _vmDao.releaseFromLockTable(userVmId);
                }
            }
        }
        return false;
    }

    @Override
    public InstanceGroupVO getGroupForVm(long vmId) {
        // TODO - in future releases vm can be assigned to multiple groups; but
        // currently return just one group per vm
        try {
            List<InstanceGroupVMMapVO> groupsToVmMap = _groupVMMapDao.listByInstanceId(vmId);

            if (groupsToVmMap != null && groupsToVmMap.size() != 0) {
                InstanceGroupVO group = _vmGroupDao.findById(groupsToVmMap.get(0).getGroupId());
                return group;
            } else {
                return null;
            }
        } catch (Exception e) {
            logger.warn("Error trying to get group for a vm: ", e);
            return null;
        }
    }

    @Override
    public void removeInstanceFromInstanceGroup(long vmId) {
        try {
            List<InstanceGroupVMMapVO> groupVmMaps = _groupVMMapDao.listByInstanceId(vmId);
            for (InstanceGroupVMMapVO groupMap : groupVmMaps) {
                SearchCriteria<InstanceGroupVMMapVO> sc = _groupVMMapDao.createSearchCriteria();
                sc.addAnd("instanceId", SearchCriteria.Op.EQ, groupMap.getInstanceId());
                _groupVMMapDao.expunge(sc);
            }
        } catch (Exception e) {
            logger.warn("Error trying to remove vm from group: ", e);
        }
    }

    private boolean validPassword(String password) {
        if (password == null || password.length() == 0) {
            return false;
        }
        for (int i = 0; i < password.length(); i++) {
            if (password.charAt(i) == ' ') {
                return false;
            }
        }
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_CREATE, eventDescription = "deploying Vm", create = true)
    public UserVm createBasicSecurityGroupVirtualMachine(DataCenter zone, ServiceOffering serviceOffering, VirtualMachineTemplate template, List<Long> securityGroupIdList,
                                                         Account owner, String hostName, String displayName, Long diskOfferingId, Long diskSize, String group, HypervisorType hypervisor, HTTPMethod httpmethod,
                                                         String userData, Long userDataId, String userDataDetails, List<String> sshKeyPairs, Map<Long, IpAddresses> requestedIps, IpAddresses defaultIps, Boolean displayVm, String keyboard, List<Long> affinityGroupIdList,
                                                         Map<String, String> customParametes, String customId, Map<String, Map<Integer, String>> dhcpOptionMap,
                                                         Map<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap, Map<String, String> userVmOVFProperties, boolean dynamicScalingEnabled, Long overrideDiskOfferingId, Volume volume, Snapshot snapshot) throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException,
    StorageUnavailableException, ResourceAllocationException {

        Account caller = CallContext.current().getCallingAccount();
        List<NetworkVO> networkList = new ArrayList<NetworkVO>();

        // Verify that caller can perform actions in behalf of vm owner
        _accountMgr.checkAccess(caller, null, true, owner);

        // Verify that owner can use the service offering
        _accountMgr.checkAccess(owner, serviceOffering, zone);
        _accountMgr.checkAccess(owner, _diskOfferingDao.findById(diskOfferingId), zone);

        // Get default guest network in Basic zone
        Network defaultNetwork = _networkModel.getExclusiveGuestNetwork(zone.getId());

        if (defaultNetwork == null) {
            throw new InvalidParameterValueException("Unable to find a default network to start a vm");
        } else {
            networkList.add(_networkDao.findById(defaultNetwork.getId()));
        }

        boolean isVmWare = (template.getHypervisorType() == HypervisorType.VMware || (hypervisor != null && hypervisor == HypervisorType.VMware));

        if (securityGroupIdList != null && isVmWare) {
            throw new InvalidParameterValueException("Security group feature is not supported for VMware hypervisor");
        } else if (!isVmWare && _networkModel.isSecurityGroupSupportedInNetwork(defaultNetwork) && _networkModel.canAddDefaultSecurityGroup()) {
            //add the default securityGroup only if no security group is specified
            if (securityGroupIdList == null || securityGroupIdList.isEmpty()) {
                if (securityGroupIdList == null) {
                    securityGroupIdList = new ArrayList<Long>();
                }
                SecurityGroup defaultGroup = _securityGroupMgr.getDefaultSecurityGroup(owner.getId());
                if (defaultGroup != null) {
                    securityGroupIdList.add(defaultGroup.getId());
                } else {
                    // create default security group for the account
                    if (logger.isDebugEnabled()) {
                        logger.debug("Couldn't find default security group for the account " + owner + " so creating a new one");
                    }
                    defaultGroup = _securityGroupMgr.createSecurityGroup(SecurityGroupManager.DEFAULT_GROUP_NAME, SecurityGroupManager.DEFAULT_GROUP_DESCRIPTION,
                            owner.getDomainId(), owner.getId(), owner.getAccountName());
                    securityGroupIdList.add(defaultGroup.getId());
                }
            }
        }

        return createVirtualMachine(zone, serviceOffering, template, hostName, displayName, owner, diskOfferingId, diskSize, networkList, securityGroupIdList, group, httpmethod,
                userData, userDataId, userDataDetails, sshKeyPairs, hypervisor, caller, requestedIps, defaultIps, displayVm, keyboard, affinityGroupIdList, customParametes, customId, dhcpOptionMap,
                dataDiskTemplateToDiskOfferingMap, userVmOVFProperties, dynamicScalingEnabled, null, overrideDiskOfferingId, volume, snapshot);

    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_CREATE, eventDescription = "deploying Vm", create = true)
    public UserVm createAdvancedSecurityGroupVirtualMachine(DataCenter zone, ServiceOffering serviceOffering, VirtualMachineTemplate template, List<Long> networkIdList,
                                                            List<Long> securityGroupIdList, Account owner, String hostName, String displayName, Long diskOfferingId, Long diskSize, String group, HypervisorType hypervisor,
                                                            HTTPMethod httpmethod, String userData, Long userDataId, String userDataDetails, List<String> sshKeyPairs, Map<Long, IpAddresses> requestedIps, IpAddresses defaultIps, Boolean displayVm, String keyboard,
                                                            List<Long> affinityGroupIdList, Map<String, String> customParameters, String customId, Map<String, Map<Integer, String>> dhcpOptionMap,
                                                            Map<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap, Map<String, String> userVmOVFProperties, boolean dynamicScalingEnabled, Long overrideDiskOfferingId, String vmType, Volume volume, Snapshot snapshot) throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException, StorageUnavailableException, ResourceAllocationException {

        Account caller = CallContext.current().getCallingAccount();
        List<NetworkVO> networkList = new ArrayList<NetworkVO>();
        boolean isSecurityGroupEnabledNetworkUsed = false;
        boolean isVmWare = (template.getHypervisorType() == HypervisorType.VMware || (hypervisor != null && hypervisor == HypervisorType.VMware));

        // Verify that caller can perform actions in behalf of vm owner
        _accountMgr.checkAccess(caller, null, true, owner);

        // Verify that owner can use the service offering
        _accountMgr.checkAccess(owner, serviceOffering, zone);
        _accountMgr.checkAccess(owner, _diskOfferingDao.findById(diskOfferingId), zone);

        // If no network is specified, find system security group enabled network
        if (networkIdList == null || networkIdList.isEmpty()) {
            Network networkWithSecurityGroup = _networkModel.getNetworkWithSGWithFreeIPs(owner, zone.getId());
            if (networkWithSecurityGroup == null) {
                throw new InvalidParameterValueException("No network with security enabled is found in zone id=" + zone.getUuid());
            }

            networkList.add(_networkDao.findById(networkWithSecurityGroup.getId()));
            isSecurityGroupEnabledNetworkUsed = true;

        } else if (securityGroupIdList != null && !securityGroupIdList.isEmpty()) {
            if (isVmWare) {
                throw new InvalidParameterValueException("Security group feature is not supported for VMware hypervisor");
            }
            // Only one network can be specified, and it should be security group enabled
            if (networkIdList.size() > 1 && template.getHypervisorType() != HypervisorType.KVM && hypervisor != HypervisorType.KVM) {
                throw new InvalidParameterValueException("Only support one network per VM if security group enabled");
            }

            for (Long networkId : networkIdList) {
                NetworkVO network = _networkDao.findById(networkId);
                NetworkOffering ntwkOffering = _networkOfferingDao.findById(network.getNetworkOfferingId());

                if (network == null) {
                    throw new InvalidParameterValueException("Unable to find network by id " + networkId);
                }

                if (!_networkModel.isSecurityGroupSupportedInNetwork(network) && (ntwkOffering.getGuestType() != GuestType.L2)) {
                    throw new InvalidParameterValueException(String.format("Network is not security group enabled or not L2 network: %s", network));
                }

                _accountMgr.checkAccess(owner, AccessType.UseEntry, false, network);

                networkList.add(network);
            }
            isSecurityGroupEnabledNetworkUsed = true;

        } else {
            // Verify that all the networks are Shared/Guest; can't create combination of SG enabled and disabled networks
            for (Long networkId : networkIdList) {
                NetworkVO network = _networkDao.findById(networkId);

                if (network == null) {
                    throw new InvalidParameterValueException("Unable to find network by id " + networkIdList.get(0).longValue());
                }

                boolean isSecurityGroupEnabled = _networkModel.isSecurityGroupSupportedInNetwork(network);
                if (isSecurityGroupEnabled) {
                    isSecurityGroupEnabledNetworkUsed = true;
                }

                if (network.getTrafficType() != TrafficType.Guest || !Arrays.asList(GuestType.Shared, GuestType.L2).contains(network.getGuestType())) {
                    throw new InvalidParameterValueException("Can specify only Shared or L2 Guest networks when deploy vm in Advance Security Group enabled zone");
                }

                _accountMgr.checkAccess(owner, AccessType.UseEntry, false, network);

                networkList.add(network);
            }
        }

        // if network is security group enabled, and no security group is specified, then add the default security group automatically
        if (isSecurityGroupEnabledNetworkUsed && !isVmWare && _networkModel.canAddDefaultSecurityGroup()) {

            //add the default securityGroup only if no security group is specified
            if (securityGroupIdList == null || securityGroupIdList.isEmpty()) {
                if (securityGroupIdList == null) {
                    securityGroupIdList = new ArrayList<Long>();
                }

                SecurityGroup defaultGroup = _securityGroupMgr.getDefaultSecurityGroup(owner.getId());
                if (defaultGroup != null) {
                    securityGroupIdList.add(defaultGroup.getId());
                } else {
                    // create default security group for the account
                    if (logger.isDebugEnabled()) {
                        logger.debug("Couldn't find default security group for the account " + owner + " so creating a new one");
                    }
                    defaultGroup = _securityGroupMgr.createSecurityGroup(SecurityGroupManager.DEFAULT_GROUP_NAME, SecurityGroupManager.DEFAULT_GROUP_DESCRIPTION,
                            owner.getDomainId(), owner.getId(), owner.getAccountName());
                    messageBus.publish(_name, SecurityGroupService.MESSAGE_CREATE_TUNGSTEN_SECURITY_GROUP_EVENT,
                        PublishScope.LOCAL, defaultGroup);
                    securityGroupIdList.add(defaultGroup.getId());
                }
            }
        }

        return createVirtualMachine(zone, serviceOffering, template, hostName, displayName, owner, diskOfferingId, diskSize, networkList, securityGroupIdList, group, httpmethod,
                userData, userDataId, userDataDetails, sshKeyPairs, hypervisor, caller, requestedIps, defaultIps, displayVm, keyboard, affinityGroupIdList, customParameters, customId, dhcpOptionMap, dataDiskTemplateToDiskOfferingMap,
                userVmOVFProperties, dynamicScalingEnabled, vmType, overrideDiskOfferingId, volume, snapshot);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_CREATE, eventDescription = "deploying Vm", create = true)
    public UserVm createAdvancedVirtualMachine(DataCenter zone, ServiceOffering serviceOffering, VirtualMachineTemplate template, List<Long> networkIdList, Account owner,
                                               String hostName, String displayName, Long diskOfferingId, Long diskSize, String group, HypervisorType hypervisor, HTTPMethod httpmethod, String userData,
                                               Long userDataId, String userDataDetails, List<String> sshKeyPairs, Map<Long, IpAddresses> requestedIps, IpAddresses defaultIps, Boolean displayvm, String keyboard, List<Long> affinityGroupIdList,
                                               Map<String, String> customParametrs, String customId, Map<String, Map<Integer, String>> dhcpOptionsMap, Map<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap,
                                               Map<String, String> userVmOVFPropertiesMap, boolean dynamicScalingEnabled, String vmType, Long overrideDiskOfferingId, Volume volume, Snapshot snapshot) throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException,
    StorageUnavailableException, ResourceAllocationException {

        Account caller = CallContext.current().getCallingAccount();
        List<NetworkVO> networkList = new ArrayList<NetworkVO>();

        // Verify that caller can perform actions in behalf of vm owner
        _accountMgr.checkAccess(caller, null, true, owner);

        // Verify that owner can use the service offering
        _accountMgr.checkAccess(owner, serviceOffering, zone);

        DiskOffering diskOffering =_diskOfferingDao.findById(diskOfferingId);
        _accountMgr.checkAccess(owner, diskOffering, zone);

        List<HypervisorType> vpcSupportedHTypes = _vpcMgr.getSupportedVpcHypervisors();
        if (networkIdList == null || networkIdList.isEmpty()) {
            NetworkVO defaultNetwork = getDefaultNetwork(zone, owner, false);
            if (defaultNetwork != null) {
                networkList.add(defaultNetwork);
            }
        } else {
            for (Long networkId : networkIdList) {
                NetworkVO network = _networkDao.findById(networkId);
                if (network == null) {
                    throw new InvalidParameterValueException("Unable to find network by id " + networkIdList.get(0).longValue());
                }
                if (network.getVpcId() != null) {
                    // Only ISOs, XenServer, KVM, and VmWare template types are
                    // supported for vpc networks
                    if (template.getFormat() != ImageFormat.ISO && !vpcSupportedHTypes.contains(template.getHypervisorType())) {
                        throw new InvalidParameterValueException("Can't create vm from template with hypervisor " + template.getHypervisorType() + " in vpc network " + network);
                    } else if (template.getFormat() == ImageFormat.ISO && !vpcSupportedHTypes.contains(hypervisor)) {
                        // Only XenServer, KVM, and VMware hypervisors are supported
                        // for vpc networks
                        throw new InvalidParameterValueException("Can't create vm of hypervisor type " + hypervisor + " in vpc network");

                    }
                }

                _networkModel.checkNetworkPermissions(owner, network);

                // don't allow to use system networks
                NetworkOffering networkOffering = _entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
                if (networkOffering.isSystemOnly()) {
                    throw new InvalidParameterValueException(String.format("Network id=%s is system only and can't be used for vm deployment", network.getUuid()));
                }
                networkList.add(network);
            }
        }
        verifyExtraDhcpOptionsNetwork(dhcpOptionsMap, networkList);
        return createVirtualMachine(zone, serviceOffering, template, hostName, displayName, owner, diskOfferingId, diskSize, networkList, null, group, httpmethod, userData,
                userDataId, userDataDetails, sshKeyPairs, hypervisor, caller, requestedIps, defaultIps, displayvm, keyboard, affinityGroupIdList, customParametrs, customId, dhcpOptionsMap,
                dataDiskTemplateToDiskOfferingMap, userVmOVFPropertiesMap, dynamicScalingEnabled, vmType, overrideDiskOfferingId, volume, snapshot);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_CREATE, eventDescription = "deploying Vm")
    public UserVm finalizeCreateVirtualMachine(long vmId) {
        logger.info("Loading UserVm " + vmId + " from DB");
        UserVm userVm = getUserVm(vmId);
        if (userVm == null) {
            logger.warn("UserVm with {} does not exist in DB", vmId);
        } else {
            logger.info("Loaded UserVm {} from DB", userVm);
        }
        return userVm;
    }

    private NetworkVO getNetworkToAddToNetworkList(VirtualMachineTemplate template, Account owner, HypervisorType hypervisor,
            List<HypervisorType> vpcSupportedHTypes, Long networkId) {
        NetworkVO network = _networkDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Unable to find network by id " + networkId);
        }
        if (network.getVpcId() != null) {
            // Only ISOs, XenServer, KVM, and VmWare template types are
            // supported for vpc networks
            if (template.getFormat() != ImageFormat.ISO && !vpcSupportedHTypes.contains(template.getHypervisorType())) {
                throw new InvalidParameterValueException("Can't create vm from template with hypervisor " + template.getHypervisorType() + " in vpc network " + network);
            } else if (template.getFormat() == ImageFormat.ISO && !vpcSupportedHTypes.contains(hypervisor)) {
                // Only XenServer, KVM, and VMware hypervisors are supported
                // for vpc networks
                throw new InvalidParameterValueException("Can't create vm of hypervisor type " + hypervisor + " in vpc network");
            }
        }

        _networkModel.checkNetworkPermissions(owner, network);

        // don't allow to use system networks
        NetworkOffering networkOffering = _entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
        if (networkOffering.isSystemOnly()) {
            throw new InvalidParameterValueException(String.format("Network id=%s is system only and can't be used for vm deployment", network.getUuid()));
        }
        return network;
    }

    private NetworkVO getDefaultNetwork(DataCenter zone, Account owner, boolean selectAny) throws InsufficientCapacityException, ResourceAllocationException {
        NetworkVO defaultNetwork = null;

        // if no network is passed in
        // Check if default virtual network offering has
        // Availability=Required. If it's true, search for corresponding
        // network
        // * if network is found, use it. If more than 1 virtual network is
        // found, throw an error
        // * if network is not found, create a new one and use it

        List<NetworkOfferingVO> requiredOfferings = _networkOfferingDao.listByAvailability(Availability.Required, false);
        if (requiredOfferings.size() < 1) {
            throw new InvalidParameterValueException("Unable to find network offering with availability=" + Availability.Required
                    + " to automatically create the network as a part of vm creation");
        }

        if (requiredOfferings.get(0).getState() == NetworkOffering.State.Enabled) {
            // get Virtual networks
            List<? extends Network> virtualNetworks = _networkModel.listNetworksForAccount(owner.getId(), zone.getId(), Network.GuestType.Isolated);
            if (virtualNetworks == null) {
                throw new InvalidParameterValueException("No (virtual) networks are found for account " + owner);
            }
            if (virtualNetworks.isEmpty()) {
                defaultNetwork = createDefaultNetworkForAccount(zone, owner, requiredOfferings);
            } else if (virtualNetworks.size() > 1 && !selectAny) {
                throw new InvalidParameterValueException("More than 1 default Isolated networks are found for account " + owner + "; please specify networkIds");
            } else {
                defaultNetwork = _networkDao.findById(virtualNetworks.get(0).getId());
            }
        } else {
            throw new InvalidParameterValueException(String.format("Required network offering %s is not in %s", requiredOfferings.get(0), NetworkOffering.State.Enabled));
        }

        return defaultNetwork;
    }

    private NetworkVO createDefaultNetworkForAccount(DataCenter zone, Account owner, List<NetworkOfferingVO> requiredOfferings)
            throws InsufficientCapacityException, ResourceAllocationException {
        NetworkVO defaultNetwork = null;
        long physicalNetworkId = _networkModel.findPhysicalNetworkId(zone.getId(), requiredOfferings.get(0).getTags(), requiredOfferings.get(0).getTrafficType());
        // Validate physical network
        PhysicalNetwork physicalNetwork = _physicalNetworkDao.findById(physicalNetworkId);
        if (physicalNetwork == null) {
            throw new InvalidParameterValueException("Unable to find physical network with id: " + physicalNetworkId + " and tag: "
                    + requiredOfferings.get(0).getTags());
        }
        logger.debug("Creating network for account {} from the network offering {} as a part of deployVM process", owner, requiredOfferings.get(0));
        Network newNetwork = _networkMgr.createGuestNetwork(requiredOfferings.get(0).getId(), owner.getAccountName() + "-network", owner.getAccountName() + "-network",
                null, null, null, false, null, owner, null, physicalNetwork, zone.getId(), ACLType.Account, null, null, null, null, true, null, null,
                null, null, null, null, null, null, null, null, null);
        if (newNetwork != null) {
            defaultNetwork = _networkDao.findById(newNetwork.getId());
        }
        return defaultNetwork;
    }

    private void verifyExtraDhcpOptionsNetwork(Map<String, Map<Integer, String>> dhcpOptionsMap, List<NetworkVO> networkList) throws InvalidParameterValueException {
        if (dhcpOptionsMap != null) {
            for (String networkUuid : dhcpOptionsMap.keySet()) {
                boolean networkFound = false;
                for (NetworkVO network : networkList) {
                    if (network.getUuid().equals(networkUuid)) {
                        networkFound = true;
                        break;
                    }
                }

                if (!networkFound) {
                    throw new InvalidParameterValueException("VM does not has a nic in the Network (" + networkUuid + ") that is specified in the extra dhcp options.");
                }
            }
        }
    }

    public void checkNameForRFCCompliance(String name) {
        if (!NetUtils.verifyDomainNameLabel(name, true)) {
            throw new InvalidParameterValueException("Invalid name. Vm name can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                    + "and the hyphen ('-'), must be between 1 and 63 characters long, and can't start or end with \"-\" and can't start with digit");
        }
    }

    @DB
    private UserVm createVirtualMachine(DataCenter zone, ServiceOffering serviceOffering, VirtualMachineTemplate tmplt, String hostName, String displayName, Account owner,
                                        Long diskOfferingId, Long diskSize, List<NetworkVO> networkList, List<Long> securityGroupIdList, String group, HTTPMethod httpmethod, String userData,
                                        Long userDataId, String userDataDetails, List<String> sshKeyPairs, HypervisorType hypervisor, Account caller, Map<Long, IpAddresses> requestedIps, IpAddresses defaultIps, Boolean isDisplayVm, String keyboard,
                                        List<Long> affinityGroupIdList, Map<String, String> customParameters, String customId, Map<String, Map<Integer, String>> dhcpOptionMap,
                                        Map<Long, DiskOffering> datadiskTemplateToDiskOfferringMap,
                                        Map<String, String> userVmOVFPropertiesMap, boolean dynamicScalingEnabled, String vmType, Long overrideDiskOfferingId, Volume volume, Snapshot snapshot) throws InsufficientCapacityException, ResourceUnavailableException,
    ConcurrentOperationException, StorageUnavailableException, ResourceAllocationException {

        _accountMgr.checkAccess(caller, null, true, owner);

        if (owner.getState() == Account.State.DISABLED) {
            throw new PermissionDeniedException("The owner of vm to deploy is disabled: " + owner);
        }
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(tmplt.getId());
        if (template != null) {
            _templateDao.loadDetails(template);
        }

        HypervisorType hypervisorType = null;
        if (template.getHypervisorType() == null || template.getHypervisorType() == HypervisorType.None) {
            if (hypervisor == null || hypervisor == HypervisorType.None) {
                throw new InvalidParameterValueException("hypervisor parameter is needed to deploy VM or the hypervisor parameter value passed is invalid");
            }
            hypervisorType = hypervisor;
        } else {
            if (hypervisor != null && hypervisor != HypervisorType.None && hypervisor != template.getHypervisorType()) {
                throw new InvalidParameterValueException("Hypervisor passed to the deployVm call, is different from the hypervisor type of the template");
            }
            hypervisorType = template.getHypervisorType();
        }

        long accountId = owner.getId();

        assert !(requestedIps != null && (defaultIps.getIp4Address() != null || defaultIps.getIp6Address() != null)) : "requestedIp list and defaultNetworkIp should never be specified together";

        if (Grouping.AllocationState.Disabled == zone.getAllocationState()
                && !_accountMgr.isRootAdmin(caller.getId())) {
            throw new PermissionDeniedException(
                    String.format("Cannot perform this operation, Zone is currently disabled: %s", zone));
        }

        // check if zone is dedicated
        DedicatedResourceVO dedicatedZone = _dedicatedDao.findByZoneId(zone.getId());
        if (dedicatedZone != null) {
            DomainVO domain = _domainDao.findById(dedicatedZone.getDomainId());
            if (domain == null) {
                throw new CloudRuntimeException("Unable to find the domain " + zone.getDomainId() + " for the zone: " + zone);
            }
            // check that caller can operate with domain
            _configMgr.checkZoneAccess(caller, zone);
            // check that vm owner can create vm in the domain
            _configMgr.checkZoneAccess(owner, zone);
        }

        ServiceOfferingVO offering = serviceOfferingDao.findById(serviceOffering.getId());

        if (offering.isDynamic()) {
            offering.setDynamicFlag(true);
            validateCustomParameters(offering, customParameters);
            offering = serviceOfferingDao.getComputeOffering(offering, customParameters);
        } else {
            validateOfferingMaxResource(offering);
        }
        // check if account/domain is with in resource limits to create a new vm
        boolean isIso = Storage.ImageFormat.ISO == template.getFormat();

        Long rootDiskOfferingId = offering.getDiskOfferingId();
        if (isIso) {
            if (diskOfferingId == null) {
                DiskOfferingVO diskOffering = _diskOfferingDao.findById(rootDiskOfferingId);
                if (diskOffering.isComputeOnly()) {
                    throw new InvalidParameterValueException("Installing from ISO requires a disk offering to be specified for the root disk.");
                }
            } else {
                rootDiskOfferingId = diskOfferingId;
                diskOfferingId = null;
            }
            if (!customParameters.containsKey(VmDetailConstants.ROOT_DISK_SIZE) && diskSize != null) {
                customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, String.valueOf(diskSize));
            }
        }
        if (!offering.getDiskOfferingStrictness() && overrideDiskOfferingId != null) {
            rootDiskOfferingId = overrideDiskOfferingId;
        }

        DiskOfferingVO rootDiskOffering = _diskOfferingDao.findById(rootDiskOfferingId);
        long volumesSize = 0;
        if (volume != null) {
            volumesSize = volume.getSize();
        } else if (snapshot != null) {
            VolumeVO volumeVO = _volsDao.findById(snapshot.getVolumeId());
            volumesSize = volumeVO != null ? volumeVO.getSize() : 0;
        } else {
            volumesSize = configureCustomRootDiskSize(customParameters, template, hypervisorType, rootDiskOffering);
        }

        if (rootDiskOffering.getEncrypt() && hypervisorType != HypervisorType.KVM) {
            throw new InvalidParameterValueException("Root volume encryption is not supported for hypervisor type " + hypervisorType);
        }

        long additionalDiskSize = 0L;
        if (!isIso && diskOfferingId != null) {
            DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
            additionalDiskSize = verifyAndGetDiskSize(diskOffering, diskSize);
        }
        UserVm vm = getCheckedUserVmResource(zone, hostName, displayName, owner, diskOfferingId, diskSize, networkList, securityGroupIdList, group, httpmethod, userData, userDataId, userDataDetails, sshKeyPairs, caller, requestedIps, defaultIps, isDisplayVm, keyboard, affinityGroupIdList, customParameters, customId, dhcpOptionMap, datadiskTemplateToDiskOfferringMap, userVmOVFPropertiesMap, dynamicScalingEnabled, vmType, template, hypervisorType, accountId, offering, isIso, rootDiskOfferingId, volumesSize, additionalDiskSize, volume, snapshot);

        _securityGroupMgr.addInstanceToGroups(vm, securityGroupIdList);

        if (affinityGroupIdList != null && !affinityGroupIdList.isEmpty()) {
            _affinityGroupVMMapDao.updateMap(vm.getId(), affinityGroupIdList);
        }

        CallContext.current().putContextParameter(VirtualMachine.class, vm.getUuid());
        return vm;
    }

    private UserVm getCheckedUserVmResource(DataCenter zone, String hostName, String displayName, Account owner,
        Long diskOfferingId, Long diskSize, List<NetworkVO> networkList, List<Long> securityGroupIdList, String group,
        HTTPMethod httpmethod, String userData, Long userDataId, String userDataDetails, List<String> sshKeyPairs,
        Account caller, Map<Long, IpAddresses> requestedIps, IpAddresses defaultIps, Boolean isDisplayVm,
        String keyboard, List<Long> affinityGroupIdList, Map<String, String> customParameters, String customId,
        Map<String, Map<Integer, String>> dhcpOptionMap, Map<Long, DiskOffering> datadiskTemplateToDiskOfferringMap,
        Map<String, String> userVmOVFPropertiesMap, boolean dynamicScalingEnabled, String vmType, VMTemplateVO template,
        HypervisorType hypervisorType, long accountId, ServiceOfferingVO offering, boolean isIso,
        Long rootDiskOfferingId, long volumesSize, long additionalDiskSize, Volume volume, Snapshot snapshot) throws ResourceAllocationException {
        if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            List<String> resourceLimitHostTags = resourceLimitService.getResourceLimitHostTags(offering, template);
            try (CheckedReservation vmReservation = new CheckedReservation(owner, ResourceType.user_vm, resourceLimitHostTags, 1l, reservationDao, resourceLimitService);
                 CheckedReservation cpuReservation = new CheckedReservation(owner, ResourceType.cpu, resourceLimitHostTags, Long.valueOf(offering.getCpu()), reservationDao, resourceLimitService);
                 CheckedReservation memReservation = new CheckedReservation(owner, ResourceType.memory, resourceLimitHostTags, Long.valueOf(offering.getRamSize()), reservationDao, resourceLimitService);
                 CheckedReservation gpuReservation = offering.getGpuCount() != null && offering.getGpuCount() > 0 ?
                         new CheckedReservation(owner, ResourceType.gpu, resourceLimitHostTags, Long.valueOf(offering.getGpuCount()), reservationDao, resourceLimitService) : null;
            ) {
                return getUncheckedUserVmResource(zone, hostName, displayName, owner, diskOfferingId, diskSize, networkList, securityGroupIdList, group, httpmethod, userData, userDataId, userDataDetails, sshKeyPairs, caller, requestedIps, defaultIps, isDisplayVm, keyboard, affinityGroupIdList, customParameters, customId, dhcpOptionMap, datadiskTemplateToDiskOfferringMap, userVmOVFPropertiesMap, dynamicScalingEnabled, vmType, template, hypervisorType, accountId, offering, isIso, rootDiskOfferingId, volumesSize, additionalDiskSize, volume, snapshot);
            } catch (ResourceAllocationException | CloudRuntimeException  e) {
                throw e;
            } catch (Exception e) {
                logger.error("error during resource reservation and allocation", e);
                throw new CloudRuntimeException(e);
            }

        } else {
            return getUncheckedUserVmResource(zone, hostName, displayName, owner, diskOfferingId, diskSize, networkList, securityGroupIdList, group, httpmethod, userData, userDataId, userDataDetails, sshKeyPairs, caller, requestedIps, defaultIps, isDisplayVm, keyboard, affinityGroupIdList, customParameters, customId, dhcpOptionMap, datadiskTemplateToDiskOfferringMap, userVmOVFPropertiesMap, dynamicScalingEnabled, vmType, template, hypervisorType, accountId, offering, isIso, rootDiskOfferingId, volumesSize, additionalDiskSize, volume, snapshot);
        }
    }

    protected List<String> getResourceLimitStorageTags(long diskOfferingId) {
        DiskOfferingVO diskOfferingVO = _diskOfferingDao.findById(diskOfferingId);
        return resourceLimitService.getResourceLimitStorageTags(diskOfferingVO);
    }

    private UserVm getUncheckedUserVmResource(DataCenter zone, String hostName, String displayName, Account owner,
        Long diskOfferingId, Long diskSize, List<NetworkVO> networkList, List<Long> securityGroupIdList, String group,
        HTTPMethod httpmethod, String userData, Long userDataId, String userDataDetails, List<String> sshKeyPairs,
        Account caller, Map<Long, IpAddresses> requestedIps, IpAddresses defaultIps, Boolean isDisplayVm,
        String keyboard, List<Long> affinityGroupIdList, Map<String, String> customParameters, String customId,
        Map<String, Map<Integer, String>> dhcpOptionMap, Map<Long, DiskOffering> datadiskTemplateToDiskOfferringMap,
        Map<String, String> userVmOVFPropertiesMap, boolean dynamicScalingEnabled, String vmType, VMTemplateVO template,
        HypervisorType hypervisorType, long accountId, ServiceOfferingVO offering, boolean isIso,
        Long rootDiskOfferingId, long volumesSize, long additionalDiskSize, Volume volume, Snapshot snapshot) throws ResourceAllocationException
    {
        List<String> rootResourceLimitStorageTags = getResourceLimitStorageTags(rootDiskOfferingId != null ? rootDiskOfferingId : offering.getDiskOfferingId());
        List<String> additionalResourceLimitStorageTags = diskOfferingId != null ? getResourceLimitStorageTags(diskOfferingId) : null;

        try (CheckedReservation rootVolumeReservation = new CheckedReservation(owner, ResourceType.volume, rootResourceLimitStorageTags, 1L, reservationDao, resourceLimitService);
             CheckedReservation additionalVolumeReservation = diskOfferingId != null ? new CheckedReservation(owner, ResourceType.volume, additionalResourceLimitStorageTags, 1L, reservationDao, resourceLimitService) : null;
             CheckedReservation rootPrimaryStorageReservation = new CheckedReservation(owner, ResourceType.primary_storage, rootResourceLimitStorageTags, volumesSize, reservationDao, resourceLimitService);
             CheckedReservation additionalPrimaryStorageReservation = diskOfferingId != null ? new CheckedReservation(owner, ResourceType.primary_storage, additionalResourceLimitStorageTags, additionalDiskSize, reservationDao, resourceLimitService) : null;
        ) {

            // verify security group ids
            if (securityGroupIdList != null) {
                for (Long securityGroupId : securityGroupIdList) {
                    SecurityGroup sg = _securityGroupDao.findById(securityGroupId);
                    if (sg == null) {
                        throw new InvalidParameterValueException("Unable to find security group by id " + securityGroupId);
                    } else {
                        // verify permissions
                        _accountMgr.checkAccess(caller, null, true, owner, sg);
                    }
                }
            }

            if (datadiskTemplateToDiskOfferringMap != null && !datadiskTemplateToDiskOfferringMap.isEmpty()) {
                for (Entry<Long, DiskOffering> datadiskTemplateToDiskOffering : datadiskTemplateToDiskOfferringMap.entrySet()) {
                    VMTemplateVO dataDiskTemplate = _templateDao.findById(datadiskTemplateToDiskOffering.getKey());
                    DiskOffering dataDiskOffering = datadiskTemplateToDiskOffering.getValue();

                    if (dataDiskTemplate == null
                            || (!dataDiskTemplate.getTemplateType().equals(TemplateType.DATADISK)) && (dataDiskTemplate.getState().equals(VirtualMachineTemplate.State.Active))) {
                        throw new InvalidParameterValueException("Invalid template id specified for Datadisk template" + datadiskTemplateToDiskOffering.getKey());
                    }
                    long dataDiskTemplateId = datadiskTemplateToDiskOffering.getKey();
                    if (!dataDiskTemplate.getParentTemplateId().equals(template.getId())) {
                        throw new InvalidParameterValueException(String.format("Invalid Datadisk template. Specified Datadisk template %s doesn't belong to template %s", dataDiskTemplate, template));
                    }
                    if (dataDiskOffering == null) {
                        throw new InvalidParameterValueException(String.format("Invalid disk offering %s specified for datadisk template %s", datadiskTemplateToDiskOffering.getValue(), dataDiskTemplate));
                    }
                    if (dataDiskOffering.isCustomized()) {
                        throw new InvalidParameterValueException(String.format("Invalid disk offering %s specified for datadisk template %s. Custom Disk offerings are not supported for Datadisk templates", dataDiskOffering, dataDiskTemplate));
                    }
                    if (dataDiskOffering.getDiskSize() < dataDiskTemplate.getSize()) {
                        throw new InvalidParameterValueException(String.format("Invalid disk offering %s specified for datadisk template %s. Disk offering size should be greater than or equal to the template size", dataDiskOffering, dataDiskTemplate));
                    }
                    _templateDao.loadDetails(dataDiskTemplate);
                    resourceLimitService.checkVolumeResourceLimit(owner, true, dataDiskOffering.getDiskSize(), dataDiskOffering);
                }
            }

            // check that the affinity groups exist
            if (affinityGroupIdList != null) {
                for (Long affinityGroupId : affinityGroupIdList) {
                    AffinityGroupVO ag = _affinityGroupDao.findById(affinityGroupId);
                    if (ag == null) {
                        throw new InvalidParameterValueException("Unable to find affinity group " + ag);
                    } else if (!_affinityGroupService.isAffinityGroupProcessorAvailable(ag.getType())) {
                        throw new InvalidParameterValueException("Affinity group type is not supported for group: " + ag + " ,type: " + ag.getType()
                                + " , Please try again after removing the affinity group");
                    } else {
                        // verify permissions
                        if (ag.getAclType() == ACLType.Domain) {
                            _accountMgr.checkAccess(caller, null, false, owner, ag);
                            // Root admin has access to both VM and AG by default,
                            // but
                            // make sure the owner of these entities is same
                            if (caller.getId() == Account.ACCOUNT_ID_SYSTEM || _accountMgr.isRootAdmin(caller.getId())) {
                                if (!_affinityGroupService.isAffinityGroupAvailableInDomain(ag.getId(), owner.getDomainId())) {
                                    throw new PermissionDeniedException("Affinity Group " + ag + " does not belong to the VM's domain");
                                }
                            }
                        } else {
                            _accountMgr.checkAccess(caller, null, true, owner, ag);
                            // Root admin has access to both VM and AG by default,
                            // but
                            // make sure the owner of these entities is same
                            if (caller.getId() == Account.ACCOUNT_ID_SYSTEM || _accountMgr.isRootAdmin(caller.getId())) {
                                if (ag.getAccountId() != owner.getAccountId()) {
                                    throw new PermissionDeniedException("Affinity Group " + ag + " does not belong to the VM's account");
                                }
                            }
                        }
                    }
                }
            }

            if (hypervisorType != HypervisorType.BareMetal && hypervisorType != HypervisorType.External) {
                // check if we have available pools for vm deployment
                long availablePools = _storagePoolDao.countPoolsByStatus(StoragePoolStatus.Up);
                if (availablePools < 1) {
                    throw new StorageUnavailableException("There are no available pools in the UP state for vm deployment", -1);
                }
            }

            if (template.getTemplateType().equals(TemplateType.SYSTEM) && !CKS_NODE.equals(vmType) && !SHAREDFSVM.equals(vmType)) {
                throw new InvalidParameterValueException(String.format("Unable to use system template %s to deploy a user vm", template));
            }

            if (volume != null) {
                if (zone.getId() != volume.getDataCenterId()) {
                    throw new InvalidParameterValueException(String.format("The volume's zone [%s] is not the same as the provided zone [%s]", volume.getDataCenterId(), zone.getId()));
                }
            } else if (snapshot != null) {
                List<SnapshotInfo> snapshotsOnZone = snapshotDataFactory.getSnapshots(snapshot.getId(), zone.getId());
                if (CollectionUtils.isEmpty(snapshotsOnZone)) {
                    throw new InvalidParameterValueException("The snapshot does not exist on zone " + zone.getId());
                }
            } else {
                List<VMTemplateZoneVO> listZoneTemplate = _templateZoneDao.listByZoneTemplate(zone.getId(), template.getId());
                if (listZoneTemplate == null || listZoneTemplate.isEmpty()) {
                    throw new InvalidParameterValueException("The template " + template.getId() + " is not available for use");
                }
            }

            if (isIso && !template.isBootable()) {
                throw new InvalidParameterValueException(String.format("Installing from ISO requires an ISO that is bootable: %s", template));
            }

            // Check templates permissions
            _accountMgr.checkAccess(owner, AccessType.UseEntry, false, template);

            // check if the user data is correct
            userData = userDataManager.validateUserData(userData, httpmethod);

            // Find an SSH public key corresponding to the key pair name, if one is
            // given
            String sshPublicKeys = "";
            String keypairnames = "";
            if (!sshKeyPairs.isEmpty()) {
                List<SSHKeyPairVO> pairs = _sshKeyPairDao.findByNames(owner.getAccountId(), owner.getDomainId(), sshKeyPairs);
                if (pairs == null || pairs.size() != sshKeyPairs.size()) {
                    throw new InvalidParameterValueException("Not all specified keypairs exist");
                }

                sshPublicKeys = pairs.stream().map(p -> p.getPublicKey()).collect(Collectors.joining("\n"));
                keypairnames = String.join(",", sshKeyPairs);
            }

            LinkedHashMap<String, List<NicProfile>> networkNicMap = new LinkedHashMap<>();

            short defaultNetworkNumber = 0;
            boolean securityGroupEnabled = false;
            int networkIndex = 0;
            for (NetworkVO network : networkList) {
                if ((network.getDataCenterId() != zone.getId())) {
                    if (!network.isStrechedL2Network()) {
                        throw new InvalidParameterValueException(String.format("Network %s doesn't belong to zone %s", network, zone));
                    }

                    NetworkOffering ntwkOffering = _networkOfferingDao.findById(network.getNetworkOfferingId());
                    Long physicalNetworkId = _networkModel.findPhysicalNetworkId(zone.getId(), ntwkOffering.getTags(), ntwkOffering.getTrafficType());

                    String provider = _ntwkSrvcDao.getProviderForServiceInNetwork(network.getId(), Service.Connectivity);
                    if (!_networkModel.isProviderEnabledInPhysicalNetwork(physicalNetworkId, provider)) {
                        throw new InvalidParameterValueException("Network in which is VM getting deployed could not be" +
                                " streched to the zone, as we could not find a valid physical network");
                    }
                }

                _accountMgr.checkAccess(owner, AccessType.UseEntry, false, network);

                IpAddresses requestedIpPair = null;
                if (requestedIps != null && !requestedIps.isEmpty()) {
                    requestedIpPair = requestedIps.get(network.getId());
                }

                if (requestedIpPair == null) {
                    requestedIpPair = new IpAddresses(null, null);
                } else {
                    _networkModel.checkRequestedIpAddresses(network.getId(), requestedIpPair);
                }

                NicProfile profile = new NicProfile(requestedIpPair.getIp4Address(), requestedIpPair.getIp6Address(), requestedIpPair.getMacAddress());
                profile.setOrderIndex(networkIndex);
                if (defaultNetworkNumber == 0) {
                    defaultNetworkNumber++;
                    // if user requested specific ip for default network, add it
                    if (defaultIps.getIp4Address() != null || defaultIps.getIp6Address() != null) {
                        _networkModel.checkRequestedIpAddresses(network.getId(), defaultIps);
                        profile = new NicProfile(defaultIps.getIp4Address(), defaultIps.getIp6Address());
                    } else if (defaultIps.getMacAddress() != null) {
                        profile = new NicProfile(null, null, defaultIps.getMacAddress());
                    }

                    profile.setDefaultNic(true);
                    if (!_networkModel.areServicesSupportedInNetwork(network.getId(), new Service[]{Service.UserData})) {
                        if ((userData != null) && (!userData.isEmpty())) {
                            throw new InvalidParameterValueException(String.format("Unable to deploy VM as UserData is provided while deploying the VM, but there is no support for %s service in the default network %s/%s.", Service.UserData.getName(), network.getName(), network.getUuid()));
                        }

                        if ((sshPublicKeys != null) && (!sshPublicKeys.isEmpty())) {
                            throw new InvalidParameterValueException(String.format("Unable to deploy VM as SSH keypair is provided while deploying the VM, but there is no support for %s service in the default network %s/%s", Service.UserData.getName(), network.getName(), network.getUuid()));
                        }

                        if (template.isEnablePassword()) {
                            throw new InvalidParameterValueException(String.format("Unable to deploy VM as template %s is password enabled, but there is no support for %s service in the default network %s/%s", template, Service.UserData.getName(), network.getName(), network.getUuid()));
                        }
                    }
                }

                if (_networkModel.isSecurityGroupSupportedInNetwork(network)) {
                    securityGroupEnabled = true;
                }
                List<NicProfile> profiles = networkNicMap.get(network.getUuid());
                if (CollectionUtils.isEmpty(profiles)) {
                    profiles = new ArrayList<>();
                }
                profiles.add(profile);
                networkNicMap.put(network.getUuid(), profiles);
                networkIndex++;
            }

            if (securityGroupIdList != null && !securityGroupIdList.isEmpty() && !securityGroupEnabled) {
                throw new InvalidParameterValueException("Unable to deploy vm with security groups as SecurityGroup service is not enabled for the vm's network");
            }

            // Verify network information - network default network has to be set;
            // and vm can't have more than one default network
            // This is a part of business logic because default network is required
            // by Agent Manager in order to configure default
            // gateway for the vm
            if (defaultNetworkNumber == 0) {
                throw new InvalidParameterValueException("At least 1 default network has to be specified for the vm");
            } else if (defaultNetworkNumber > 1) {
                throw new InvalidParameterValueException("Only 1 default network per vm is supported");
            }

            long id = _vmDao.getNextInSequence(Long.class, "id");

            if (hostName != null) {
                // Check is hostName is RFC compliant
                checkNameForRFCCompliance(hostName);
            }

            String instanceName = null;
            String instanceSuffix = _instance;
            String uuidName = _uuidMgr.generateUuid(UserVm.class, customId);
            if (_instanceNameFlag && HypervisorType.VMware.equals(hypervisorType)) {
                if (StringUtils.isNotEmpty(hostName)) {
                    instanceSuffix = hostName;
                }
                if (hostName == null) {
                    if (displayName != null) {
                        hostName = displayName;
                    } else {
                        hostName = generateHostName(uuidName);
                    }
                }
                // If global config vm.instancename.flag is set to true, then CS will set guest VM's name as it appears on the hypervisor, to its hostname.
                // In case of VMware since VM name must be unique within a DC, check if VM with the same hostname already exists in the zone.
                VMInstanceVO vmByHostName = _vmInstanceDao.findVMByHostNameInZone(hostName, zone.getId());
                if (vmByHostName != null && vmByHostName.getState() != State.Expunging) {
                    throw new InvalidParameterValueException("There already exists a VM by the name: " + hostName + ".");
                }
            } else {
                if (hostName == null) {
                    //Generate name using uuid and instance.name global config
                    hostName = generateHostName(uuidName);
                }
            }

            if (hostName != null) {
                // Check is hostName is RFC compliant
                checkNameForRFCCompliance(hostName);
            }
            instanceName = VirtualMachineName.getVmName(id, owner.getId(), instanceSuffix);
            if (_instanceNameFlag && HypervisorType.VMware.equals(hypervisorType) && !instanceSuffix.equals(_instance)) {
                customParameters.put(VmDetailConstants.NAME_ON_HYPERVISOR, instanceName);
            }

            // Check if VM with instanceName already exists.
            VMInstanceVO vmObj = _vmInstanceDao.findVMByInstanceName(instanceName);
            if (vmObj != null && vmObj.getState() != State.Expunging) {
                throw new InvalidParameterValueException("There already exists a VM by the display name supplied");
            }

            checkIfHostNameUniqueInNtwkDomain(hostName, networkList);

            long userId = CallContext.current().getCallingUserId();
            if (CallContext.current().getCallingAccount().getId() != owner.getId()) {
                List<UserVO> userVOs = _userDao.listByAccount(owner.getAccountId());
                if (!userVOs.isEmpty()) {
                    userId = userVOs.get(0).getId();
                }
            }

            dynamicScalingEnabled = dynamicScalingEnabled && checkIfDynamicScalingCanBeEnabled(null, offering, template, zone.getId());

            UserVmVO vm = commitUserVm(zone, template, hostName, displayName, owner, diskOfferingId, diskSize, userData, userDataId, userDataDetails, caller, isDisplayVm, keyboard, accountId, userId, offering,
                    isIso, sshPublicKeys, networkNicMap, id, instanceName, uuidName, hypervisorType, customParameters, dhcpOptionMap,
                    datadiskTemplateToDiskOfferringMap, userVmOVFPropertiesMap, dynamicScalingEnabled, vmType, rootDiskOfferingId, keypairnames, volume, snapshot);

            assignInstanceToGroup(group, id);
            return vm;
        } catch (ResourceAllocationException | CloudRuntimeException  e) {
            throw e;
        } catch (Exception e) {
            logger.error("error during resource reservation and allocation", e);
            throw new CloudRuntimeException(e);
        }
    }

    private void assignInstanceToGroup(String group, long id) {
        // Assign instance to the group
        try {
            if (group != null) {
                boolean addToGroup = addInstanceToGroup(Long.valueOf(id), group);
                if (!addToGroup) {
                    throw new CloudRuntimeException("Unable to assign Vm to the group " + group);
                }
            }
        } catch (Exception ex) {
            throw new CloudRuntimeException("Unable to assign Vm to the group " + group);
        }
    }

    private long verifyAndGetDiskSize(DiskOfferingVO diskOffering, Long diskSize) {
        long size = 0l;
        if (diskOffering == null) {
            throw new InvalidParameterValueException("Specified disk offering cannot be found");
        }
        if (diskOffering.isCustomized() && !diskOffering.isComputeOnly()) {
            if (diskSize == null) {
                throw new InvalidParameterValueException("This disk offering requires a custom size specified");
            }
            _volumeService.validateCustomDiskOfferingSizeRange(diskSize);
            size = diskSize * GiB_TO_BYTES;
        } else {
            size = diskOffering.getDiskSize();
        }
        _volumeService.validateVolumeSizeInBytes(size);
        return size;
    }

    @Override
    public boolean checkIfDynamicScalingCanBeEnabled(VirtualMachine vm, ServiceOffering offering, VirtualMachineTemplate template, Long zoneId) {
        boolean canEnableDynamicScaling = (vm != null ? vm.isDynamicallyScalable() : true) && offering.isDynamicScalingEnabled() && template.isDynamicallyScalable() && UserVmManager.EnableDynamicallyScaleVm.valueIn(zoneId);
        if (!canEnableDynamicScaling) {
            logger.info("VM cannot be configured to be dynamically scalable if any of the service offering's dynamic scaling property, template's dynamic scaling property or global setting is false");
        }

        return canEnableDynamicScaling;
    }

    /**
     * Configures the Root disk size via User`s custom parameters.
     * If the Service Offering has the Root Disk size field configured then the User`s root disk custom parameter is overwritten by the service offering.
     */
    protected long configureCustomRootDiskSize(Map<String, String> customParameters, VMTemplateVO template, HypervisorType hypervisorType, DiskOfferingVO rootDiskOffering) {
        verifyIfHypervisorSupportsRootdiskSizeOverride(hypervisorType);
        Long rootDiskSizeCustomParam = null;
        if (customParameters.containsKey(VmDetailConstants.ROOT_DISK_SIZE)) {
            rootDiskSizeCustomParam = NumbersUtil.parseLong(customParameters.get(VmDetailConstants.ROOT_DISK_SIZE), -1);
            if (rootDiskSizeCustomParam <= 0) {
                throw new InvalidParameterValueException("Root disk size should be a positive number.");
            }
        }
        long rootDiskSizeInBytes = verifyAndGetDiskSize(rootDiskOffering, rootDiskSizeCustomParam);
        if (rootDiskSizeInBytes > 0) { //if the size at DiskOffering is not zero then the Service Offering had it configured, it holds priority over the User custom size
            _volumeService.validateVolumeSizeInBytes(rootDiskSizeInBytes);
            long rootDiskSizeInGiB = rootDiskSizeInBytes / GiB_TO_BYTES;
            customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, String.valueOf(rootDiskSizeInGiB));
            return rootDiskSizeInBytes;
        }

        if (customParameters.containsKey(VmDetailConstants.ROOT_DISK_SIZE)) {
            Long rootDiskSize = NumbersUtil.parseLong(customParameters.get(VmDetailConstants.ROOT_DISK_SIZE), -1);
            if (rootDiskSize <= 0) {
                throw new InvalidParameterValueException("Root disk size should be a positive number.");
            }
            rootDiskSize = rootDiskSizeCustomParam * GiB_TO_BYTES;
            _volumeService.validateVolumeSizeInBytes(rootDiskSize);
            return rootDiskSize;
        } else {
            // For baremetal, size can be 0 (zero)
            Long templateSize = _templateDao.findById(template.getId()).getSize();
            if (templateSize != null) {
                return templateSize;
            }
        }
        return 0;
    }

    /**
     * Only KVM, XenServer and VMware supports rootdisksize override
     * @throws InvalidParameterValueException if the hypervisor does not support rootdisksize override
     */
    protected void verifyIfHypervisorSupportsRootdiskSizeOverride(HypervisorType hypervisorType) {
        if (!hypervisorType.isFunctionalitySupported(Functionality.RootDiskSizeOverride)) {
            throw new InvalidParameterValueException("Hypervisor " + hypervisorType + " does not support rootdisksize override");
        }
    }

    private void checkIfHostNameUniqueInNtwkDomain(String hostName, List<? extends Network> networkList) {
        // Check that hostName is unique in the network domain
        Map<String, List<Long>> ntwkDomains = new HashMap<String, List<Long>>();
        for (Network network : networkList) {
            String ntwkDomain = network.getNetworkDomain();
            if (!ntwkDomains.containsKey(ntwkDomain)) {
                List<Long> ntwkIds = new ArrayList<Long>();
                ntwkIds.add(network.getId());
                ntwkDomains.put(ntwkDomain, ntwkIds);
            } else {
                List<Long> ntwkIds = ntwkDomains.get(ntwkDomain);
                ntwkIds.add(network.getId());
                ntwkDomains.put(ntwkDomain, ntwkIds);
            }
        }

        for (Entry<String, List<Long>> ntwkDomain : ntwkDomains.entrySet()) {
            for (Long ntwkId : ntwkDomain.getValue()) {
                // * get all vms hostNames in the network
                List<String> hostNames = _vmInstanceDao.listDistinctHostNames(ntwkId);
                // * verify that there are no duplicates
                if (hostNames.contains(hostName)) {
                    throw new InvalidParameterValueException("The vm with hostName " + hostName + " already exists in the network domain: " + ntwkDomain.getKey() + "; network="
                            + ((_networkModel.getNetwork(ntwkId) != null) ? _networkModel.getNetwork(ntwkId).getName() : "<unknown>"));
                }
            }
        }
    }

    private String generateHostName(String uuidName) {
        return _instance + "-" + uuidName;
    }

    private UserVmVO commitUserVm(final boolean isImport, final DataCenter zone, final Host host, final Host lastHost, final VirtualMachineTemplate template, final String hostName, final String displayName, final Account owner,
                                  final Long diskOfferingId, final Long diskSize, final String userData, Long userDataId, String userDataDetails, final Boolean isDisplayVm, final String keyboard,
                                  final long accountId, final long userId, final ServiceOffering offering, final boolean isIso, final String sshPublicKeys, final LinkedHashMap<String, List<NicProfile>> networkNicMap,
                                  final long id, final String instanceName, final String uuidName, final HypervisorType hypervisorType, final Map<String, String> customParameters,
                                  final Map<String, Map<Integer, String>> extraDhcpOptionMap, final Map<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap,
                                  final Map<String, String> userVmOVFPropertiesMap, final VirtualMachine.PowerState powerState, final boolean dynamicScalingEnabled, String vmType, final Long rootDiskOfferingId, String sshkeypairs, Volume volume, Snapshot snapshot) throws InsufficientCapacityException {
        UserVmVO vm = new UserVmVO(id, instanceName, displayName, template.getId(), hypervisorType, template.getGuestOSId(), offering.isOfferHA(),
                offering.getLimitCpuUse(), owner.getDomainId(), owner.getId(), userId, offering.getId(), userData, userDataId, userDataDetails, hostName);
        vm.setUuid(uuidName);
        vm.setDynamicallyScalable(dynamicScalingEnabled);

        Map<String, String> details = template.getDetails();
        if (details != null && !details.isEmpty()) {
            vm.details.putAll(details);
        }

        if (StringUtils.isNotBlank(sshPublicKeys)) {
            vm.setDetail(VmDetailConstants.SSH_PUBLIC_KEY, sshPublicKeys);
        }

        if (StringUtils.isNotBlank(sshkeypairs)) {
            vm.setDetail(VmDetailConstants.SSH_KEY_PAIR_NAMES, sshkeypairs);
        }

        if (keyboard != null && !keyboard.isEmpty()) {
            vm.setDetail(VmDetailConstants.KEYBOARD, keyboard);
        }

        if (!isImport && isIso) {
            vm.setIsoId(template.getId());
        }

        long guestOSId = template.getGuestOSId();
        GuestOSVO guestOS = _guestOSDao.findById(guestOSId);
        long guestOSCategoryId = guestOS.getCategoryId();
        GuestOSCategoryVO guestOSCategory = _guestOSCategoryDao.findById(guestOSCategoryId);
        if (hypervisorType.equals(HypervisorType.VMware)) {
            updateVMDiskController(vm, customParameters, guestOS);
        }

        Long rootDiskSize = null;
        // custom root disk size, resizes base template to larger size
        if (customParameters.containsKey(VmDetailConstants.ROOT_DISK_SIZE)) {
            // already verified for positive number
            rootDiskSize = Long.parseLong(customParameters.get(VmDetailConstants.ROOT_DISK_SIZE));

            VMTemplateVO templateVO = _templateDao.findById(template.getId());
            if (templateVO == null) {
                InvalidParameterValueException ipve = new InvalidParameterValueException("Unable to look up template by id " + template.getId());
                ipve.add(VirtualMachine.class, vm.getUuid());
                throw ipve;
            }

            validateRootDiskResize(hypervisorType, rootDiskSize, templateVO, vm, customParameters);
        }

        if (isDisplayVm != null) {
            vm.setDisplayVm(isDisplayVm);
        } else {
            vm.setDisplayVm(true);
        }

        setVmRequiredFieldsForImport(isImport, vm, zone, hypervisorType, host, lastHost, powerState);

        setVncPasswordForKvmIfAvailable(customParameters, vm);

        vm.setUserVmType(vmType);
        _vmDao.persist(vm);
        for (String key : customParameters.keySet()) {
            // BIOS was explicitly passed as the boot type, so honour it
            if (key.equalsIgnoreCase(ApiConstants.BootType.BIOS.toString())) {
                vm.details.remove(ApiConstants.BootType.UEFI.toString());
                continue;
            }

            // Deploy as is, Don't care about the boot type or template settings
            if (key.equalsIgnoreCase(ApiConstants.BootType.UEFI.toString()) && template.isDeployAsIs()) {
                vm.details.remove(ApiConstants.BootType.UEFI.toString());
                continue;
            }

            if (!hypervisorType.equals(HypervisorType.KVM)) {
                if (key.equalsIgnoreCase(VmDetailConstants.IOTHREADS)) {
                    vm.details.remove(VmDetailConstants.IOTHREADS);
                    continue;
                }
                if (key.equalsIgnoreCase(VmDetailConstants.IO_POLICY)) {
                    vm.details.remove(VmDetailConstants.IO_POLICY);
                    continue;
                }
            }

            if (key.equalsIgnoreCase(VmDetailConstants.CPU_NUMBER) ||
                    key.equalsIgnoreCase(VmDetailConstants.CPU_SPEED) ||
                    key.equalsIgnoreCase(VmDetailConstants.MEMORY)) {
                // handle double byte strings.
                vm.setDetail(key, Integer.toString(Integer.parseInt(customParameters.get(key))));
            } else {
                vm.setDetail(key, customParameters.get(key));
            }
        }
        vm.setDetail(VmDetailConstants.DEPLOY_VM, "true");

        persistVMDeployAsIsProperties(vm, userVmOVFPropertiesMap);

        List<String> hiddenDetails = new ArrayList<>();
        if (customParameters.containsKey(VmDetailConstants.NAME_ON_HYPERVISOR)) {
            hiddenDetails.add(VmDetailConstants.NAME_ON_HYPERVISOR);
        }
        _vmDao.saveDetails(vm, hiddenDetails);
        if (!isImport) {
            logger.debug("Allocating in the DB for vm");
            DataCenterDeployment plan = new DataCenterDeployment(zone.getId());

            List<String> computeTags = new ArrayList<String>();
            computeTags.add(offering.getHostTag());

            List<String> rootDiskTags = new ArrayList<String>();
            DiskOfferingVO rootDiskOfferingVO = _diskOfferingDao.findById(rootDiskOfferingId);
            rootDiskTags.add(rootDiskOfferingVO.getTags());

            orchestrateVirtualMachineCreate(vm, guestOSCategory, computeTags, rootDiskTags, plan, rootDiskSize, template, hostName, displayName, owner,
                    diskOfferingId, diskSize, offering, isIso,networkNicMap, hypervisorType, extraDhcpOptionMap, dataDiskTemplateToDiskOfferingMap,
                    rootDiskOfferingId, volume, snapshot);

        }
        CallContext.current().setEventDetails("Vm Id: " + vm.getUuid());

        if (!isImport) {
            if (!offering.isDynamic()) {
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_CREATE, accountId, zone.getId(), vm.getId(), vm.getHostName(), offering.getId(), template.getId(),
                        hypervisorType.toString(), VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplayVm());
            } else {
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_CREATE, accountId, zone.getId(), vm.getId(), vm.getHostName(), offering.getId(), template.getId(),
                        hypervisorType.toString(), VirtualMachine.class.getName(), vm.getUuid(), customParameters, vm.isDisplayVm());
            }

            try {
                //Update Resource Count for the given account
                resourceCountIncrement(accountId, isDisplayVm, offering, template);
            } catch (CloudRuntimeException cre) {
                ArrayList<ExceptionProxyObject> epoList =  cre.getIdProxyList();
                if (epoList == null || !epoList.stream().anyMatch( e -> e.getUuid().equals(vm.getUuid()))) {
                    cre.addProxyObject(vm.getUuid(), ApiConstants.VIRTUAL_MACHINE_ID);
                }
                throw cre;
            }
        }
        return vm;
    }

    private void orchestrateVirtualMachineCreate(UserVmVO vm, GuestOSCategoryVO guestOSCategory, List<String> computeTags, List<String> rootDiskTags, DataCenterDeployment plan, Long rootDiskSize, VirtualMachineTemplate template, String hostName, String displayName, Account owner,
                                        Long diskOfferingId, Long diskSize,
                                        ServiceOffering offering, boolean isIso, LinkedHashMap<String, List<NicProfile>> networkNicMap,
                                        HypervisorType hypervisorType,
                                        Map<String, Map<Integer, String>> extraDhcpOptionMap, Map<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap,
                                        Long rootDiskOfferingId, Volume volume, Snapshot snapshot) throws InsufficientCapacityException{
        try {
            if (isIso) {
                _orchSrvc.createVirtualMachineFromScratch(vm.getUuid(), Long.toString(owner.getAccountId()), vm.getIsoId().toString(), hostName, displayName,
                        hypervisorType.name(), guestOSCategory.getName(), offering.getCpu(), offering.getSpeed(), offering.getRamSize(), diskSize, computeTags, rootDiskTags,
                        networkNicMap, plan, extraDhcpOptionMap, rootDiskOfferingId, volume, snapshot);
            } else {
                _orchSrvc.createVirtualMachine(vm.getUuid(), Long.toString(owner.getAccountId()), Long.toString(template.getId()), hostName, displayName, hypervisorType.name(),
                        offering.getCpu(), offering.getSpeed(), offering.getRamSize(), diskSize, computeTags, rootDiskTags, networkNicMap, plan, rootDiskSize, extraDhcpOptionMap,
                        dataDiskTemplateToDiskOfferingMap, diskOfferingId, rootDiskOfferingId, volume, snapshot);
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Successfully allocated DB entry for " + vm);
            }
        } catch (CloudRuntimeException cre) {
            ArrayList<ExceptionProxyObject> epoList = cre.getIdProxyList();
            if (epoList == null || !epoList.stream().anyMatch(e -> e.getUuid().equals(vm.getUuid()))) {
                cre.addProxyObject(vm.getUuid(), ApiConstants.VIRTUAL_MACHINE_ID);

            }
            throw cre;
        } catch (InsufficientCapacityException ice) {
            ArrayList idList = ice.getIdProxyList();
            if (idList == null || !idList.stream().anyMatch(i -> i.equals(vm.getUuid()))) {
                ice.addProxyObject(vm.getUuid());
            }
            throw ice;
        }
    }

    protected void setVmRequiredFieldsForImport(boolean isImport, UserVmVO vm, DataCenter zone, HypervisorType hypervisorType,
                                                Host host, Host lastHost, VirtualMachine.PowerState powerState) {
        if (isImport) {
            vm.setDataCenterId(zone.getId());
            if (List.of(HypervisorType.VMware, HypervisorType.KVM).contains(hypervisorType) && host != null) {
                vm.setHostId(host.getId());
            }
            if (lastHost != null) {
                vm.setLastHostId(lastHost.getId());
            }
            vm.setPowerState(powerState);
            if (powerState == VirtualMachine.PowerState.PowerOn) {
                vm.setState(State.Running);
            }
        }
    }

    private void updateVMDiskController(UserVmVO vm, Map<String, String> customParameters, GuestOSVO guestOS) {
        // If hypervisor is vSphere and OS is OS X, set special settings.
        if (guestOS.getDisplayName().toLowerCase().contains("apple mac os")) {
            vm.setDetail(VmDetailConstants.SMC_PRESENT, "TRUE");
            vm.setDetail(VmDetailConstants.ROOT_DISK_CONTROLLER, "scsi");
            vm.setDetail(VmDetailConstants.DATA_DISK_CONTROLLER, "scsi");
            vm.setDetail(VmDetailConstants.FIRMWARE, "efi");
            logger.info("guestOS is OSX : overwrite root disk controller to scsi, use smc and efi");
        } else {
            String rootDiskControllerSetting = customParameters.get(VmDetailConstants.ROOT_DISK_CONTROLLER);
            String dataDiskControllerSetting = customParameters.get(VmDetailConstants.DATA_DISK_CONTROLLER);
            if (StringUtils.isNotEmpty(rootDiskControllerSetting)) {
                vm.setDetail(VmDetailConstants.ROOT_DISK_CONTROLLER, rootDiskControllerSetting);
            }

            if (StringUtils.isNotEmpty(dataDiskControllerSetting)) {
                vm.setDetail(VmDetailConstants.DATA_DISK_CONTROLLER, dataDiskControllerSetting);
            }

            // Don't override if VM already has root/data disk controller detail
            if (vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER) == null) {
                String vmwareRootDiskControllerTypeFromSetting = StringUtils.defaultIfEmpty(_configDao.getValue(Config.VmwareRootDiskControllerType.key()),
                        Config.VmwareRootDiskControllerType.getDefaultValue());
                vm.setDetail(VmDetailConstants.ROOT_DISK_CONTROLLER, vmwareRootDiskControllerTypeFromSetting);
            }

            if (vm.getDetail(VmDetailConstants.DATA_DISK_CONTROLLER) == null) {
                String finalRootDiskController = vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER);
                // Set the data disk controller detail same as the final scsi root disk controller if VM doesn't have data disk controller detail
                // This is to ensure the disk controller is available for the data disks, as all the SCSI controllers are created with same controller type
                String scsiControllerPattern = "(?i)\\b(scsi|lsilogic|lsilogicsas|lsisas1068|buslogic|pvscsi)\\b";
                if (finalRootDiskController.matches(scsiControllerPattern)) {
                    logger.info(String.format("Data disk controller was not defined, but root disk is using SCSI controller [%s]." +
                            "To ensure disk controllers are available for the data disks, the data disk controller is updated to match the root disk controller.", finalRootDiskController));
                    vm.setDetail(VmDetailConstants.DATA_DISK_CONTROLLER, finalRootDiskController);
                } else {
                    logger.info("Data disk controller was not defined; defaulting to 'osdefault'.");
                    vm.setDetail(VmDetailConstants.DATA_DISK_CONTROLLER, "osdefault");
                }
            }
        }
    }

    /**
     * take the properties and set them on the vm.
     * consider should we be complete, and make sure all default values are copied as well if known?
     * I.E. iterate over the template details as well to copy any that are not defined yet.
     */
    private void persistVMDeployAsIsProperties(UserVmVO vm, Map<String, String> userVmOVFPropertiesMap) {
        if (MapUtils.isNotEmpty(userVmOVFPropertiesMap)) {
            for (String key : userVmOVFPropertiesMap.keySet()) {
                String detailKey = key;
                String value = userVmOVFPropertiesMap.get(key);

                // Sanitize boolean values to expected format and encrypt passwords
                if (StringUtils.isNotBlank(value)) {
                    if (value.equalsIgnoreCase("True")) {
                        value = "True";
                    } else if (value.equalsIgnoreCase("False")) {
                        value = "False";
                    } else {
                        OVFPropertyTO propertyTO = templateDeployAsIsDetailsDao.findPropertyByTemplateAndKey(vm.getTemplateId(), key);
                        if (propertyTO != null && propertyTO.isPassword()) {
                            value = DBEncryptionUtil.encrypt(value);
                        }
                    }
                } else if (value == null) {
                    value = "";
                }
                if (logger.isTraceEnabled()) {
                    logger.trace(String.format("setting property '%s' as '%s' with value '%s'", key, detailKey, value));
                }
                UserVmDeployAsIsDetailVO detail = new UserVmDeployAsIsDetailVO(vm.getId(), detailKey, value);
                userVmDeployAsIsDetailsDao.persist(detail);
            }
        }
    }

    private UserVmVO commitUserVm(final DataCenter zone, final VirtualMachineTemplate template, final String hostName, final String displayName, final Account owner,
                                  final Long diskOfferingId, final Long diskSize, final String userData, Long userDataId, String userDataDetails, final Account caller, final Boolean isDisplayVm, final String keyboard,
                                  final long accountId, final long userId, final ServiceOfferingVO offering, final boolean isIso, final String sshPublicKeys, final LinkedHashMap<String, List<NicProfile>> networkNicMap,
                                  final long id, final String instanceName, final String uuidName, final HypervisorType hypervisorType, final Map<String, String> customParameters, final Map<String,
            Map<Integer, String>> extraDhcpOptionMap, final Map<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap,
                                  Map<String, String> userVmOVFPropertiesMap, final boolean dynamicScalingEnabled, String vmType, final Long rootDiskOfferingId, String sshkeypairs, Volume volume, Snapshot snapshot) throws InsufficientCapacityException {
        return commitUserVm(false, zone, null, null, template, hostName, displayName, owner,
                diskOfferingId, diskSize, userData, userDataId, userDataDetails, isDisplayVm, keyboard,
                accountId, userId, offering, isIso, sshPublicKeys, networkNicMap,
                id, instanceName, uuidName, hypervisorType, customParameters,
                extraDhcpOptionMap, dataDiskTemplateToDiskOfferingMap,
                userVmOVFPropertiesMap, null, dynamicScalingEnabled, vmType, rootDiskOfferingId, sshkeypairs, volume, snapshot);
    }

    public void validateRootDiskResize(final HypervisorType hypervisorType, Long rootDiskSize, VMTemplateVO templateVO, UserVmVO vm, final Map<String, String> customParameters) throws InvalidParameterValueException
    {
        // rootdisksize must be larger than template.
        boolean isIso = ImageFormat.ISO == templateVO.getFormat();
        if ((rootDiskSize << 30) < templateVO.getSize()) {
            String error = String.format("Unsupported: rootdisksize override (%s GB) is smaller than template size %s", rootDiskSize, toHumanReadableSize(templateVO.getSize()));
            logger.error(error);
            throw new InvalidParameterValueException(error);
        } else if ((rootDiskSize << 30) > templateVO.getSize()) {
            if (hypervisorType == HypervisorType.VMware && (vm.getDetails() == null || vm.getDetails().get(VmDetailConstants.ROOT_DISK_CONTROLLER) == null)) {
                logger.warn("If Root disk controller parameter is not overridden, then Root disk resize may fail because current Root disk controller value is NULL.");
            } else if (hypervisorType == HypervisorType.VMware && vm.getDetails().get(VmDetailConstants.ROOT_DISK_CONTROLLER).toLowerCase().contains("ide") && !isIso) {
                String error = String.format("Found unsupported root disk controller [%s].", vm.getDetails().get(VmDetailConstants.ROOT_DISK_CONTROLLER));
                logger.error(error);
                throw new InvalidParameterValueException(error);
            } else {
                logger.debug("Rootdisksize override validation successful. Template root disk size " + toHumanReadableSize(templateVO.getSize()) + " Root disk size specified " + rootDiskSize + " GB");
            }
        } else {
            logger.debug("Root disk size specified is " + toHumanReadableSize(rootDiskSize << 30) + " and Template root disk size is " + toHumanReadableSize(templateVO.getSize()) + ". Both are equal so no need to override");
            customParameters.remove(VmDetailConstants.ROOT_DISK_SIZE);
        }
    }


    @Override
    public void generateUsageEvent(VirtualMachine vm, boolean isDisplay, String eventType){
        ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
        if (!serviceOffering.isDynamic()) {
            UsageEventUtils.publishUsageEvent(eventType, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                    vm.getHostName(), serviceOffering.getId(), vm.getTemplateId(), vm.getHypervisorType().toString(),
                    VirtualMachine.class.getName(), vm.getUuid(), isDisplay);
        }
        else {
            Map<String, String> customParameters = new HashMap<String, String>();
            customParameters.put(UsageEventVO.DynamicParameters.cpuNumber.name(), serviceOffering.getCpu().toString());
            customParameters.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), serviceOffering.getSpeed().toString());
            customParameters.put(UsageEventVO.DynamicParameters.memory.name(), serviceOffering.getRamSize().toString());
            UsageEventUtils.publishUsageEvent(eventType, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                    vm.getHostName(), serviceOffering.getId(), vm.getTemplateId(), vm.getHypervisorType().toString(),
                    VirtualMachine.class.getName(), vm.getUuid(), customParameters, isDisplay);
        }
    }

    @Override
    public void collectVmNetworkStatistics (final UserVm userVm) {
        if (!userVm.getHypervisorType().equals(HypervisorType.KVM)) {
            return;
        }
        logger.debug("Collect vm network statistics from host before stopping Vm");
        long hostId = userVm.getHostId();
        List<String> vmNames = new ArrayList<String>();
        vmNames.add(userVm.getInstanceName());
        final HostVO host = _hostDao.findById(hostId);
        Account account = _accountMgr.getAccount(userVm.getAccountId());

        GetVmNetworkStatsAnswer networkStatsAnswer = null;
        try {
            networkStatsAnswer = (GetVmNetworkStatsAnswer) _agentMgr.easySend(hostId, new GetVmNetworkStatsCommand(vmNames, host.getGuid(), host.getName()));
        } catch (Exception e) {
            logger.warn("Error while collecting network stats for vm: {} from host: {}", userVm, host, e);
            return;
        }
        if (networkStatsAnswer != null) {
            if (!networkStatsAnswer.getResult()) {
                logger.warn("Error while collecting network stats vm: {} from host: {}; details: {}", userVm, host, networkStatsAnswer.getDetails());
                return;
            }
            try {
                final GetVmNetworkStatsAnswer networkStatsAnswerFinal = networkStatsAnswer;
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) {
                        HashMap<String, List<VmNetworkStatsEntry>> vmNetworkStatsByName = networkStatsAnswerFinal.getVmNetworkStatsMap();
                        if (vmNetworkStatsByName == null) {
                            return;
                        }
                        List<VmNetworkStatsEntry> vmNetworkStats = vmNetworkStatsByName.get(userVm.getInstanceName());
                        if (vmNetworkStats == null) {
                            return;
                        }

                        for (VmNetworkStatsEntry vmNetworkStat:vmNetworkStats) {
                            SearchCriteria<NicVO> sc_nic = _nicDao.createSearchCriteria();
                            sc_nic.addAnd("macAddress", SearchCriteria.Op.EQ, vmNetworkStat.getMacAddress());
                            NicVO nic = _nicDao.search(sc_nic, null).get(0);
                            List<VlanVO> vlan = _vlanDao.listVlansByNetworkId(nic.getNetworkId());
                            if (vlan == null || vlan.size() == 0 || vlan.get(0).getVlanType() != VlanType.DirectAttached)
                            {
                                break; // only get network statistics for DirectAttached network (shared networks in Basic zone and Advanced zone with/without SG)
                            }
                            UserStatisticsVO previousvmNetworkStats = _userStatsDao.findBy(userVm.getAccountId(), userVm.getDataCenterId(), nic.getNetworkId(), nic.getIPv4Address(), userVm.getId(), "UserVm");
                            if (previousvmNetworkStats == null) {
                                previousvmNetworkStats = new UserStatisticsVO(userVm.getAccountId(), userVm.getDataCenterId(),nic.getIPv4Address(), userVm.getId(), "UserVm", nic.getNetworkId());
                                _userStatsDao.persist(previousvmNetworkStats);
                            }
                            UserStatisticsVO vmNetworkStat_lock = _userStatsDao.lock(userVm.getAccountId(), userVm.getDataCenterId(), nic.getNetworkId(), nic.getIPv4Address(), userVm.getId(), "UserVm");

                            if ((vmNetworkStat.getBytesSent() == 0) && (vmNetworkStat.getBytesReceived() == 0)) {
                                logger.debug("bytes sent and received are all 0. Not updating user_statistics");
                                continue;
                            }

                            if (vmNetworkStat_lock == null) {
                                logger.warn("unable to find vm network stats from host for account: {} with vm: {} and nic: {}", account, userVm, nic);
                                continue;
                            }

                            if (previousvmNetworkStats != null
                                    && ((previousvmNetworkStats.getCurrentBytesSent() != vmNetworkStat_lock.getCurrentBytesSent())
                                            || (previousvmNetworkStats.getCurrentBytesReceived() != vmNetworkStat_lock.getCurrentBytesReceived()))) {
                                logger.debug("vm network stats changed from the time GetNmNetworkStatsCommand was sent. " +
                                        "Ignoring current answer. Host: " + host  + " . VM: " + vmNetworkStat.getVmName() +
                                        " Sent(Bytes): " + toHumanReadableSize(vmNetworkStat.getBytesSent()) + " Received(Bytes): " + toHumanReadableSize(vmNetworkStat.getBytesReceived()));
                                continue;
                            }

                            if (vmNetworkStat_lock.getCurrentBytesSent() > vmNetworkStat.getBytesSent()) {
                                if (logger.isDebugEnabled()) {
                                   logger.debug("Sent # of bytes that's less than the last one.  Assuming something went wrong and persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                                           host, vmNetworkStat.getVmName(), toHumanReadableSize(vmNetworkStat.getBytesSent()), toHumanReadableSize(vmNetworkStat_lock.getCurrentBytesSent()));
                                }
                                vmNetworkStat_lock.setNetBytesSent(vmNetworkStat_lock.getNetBytesSent() + vmNetworkStat_lock.getCurrentBytesSent());
                            }
                            vmNetworkStat_lock.setCurrentBytesSent(vmNetworkStat.getBytesSent());

                            if (vmNetworkStat_lock.getCurrentBytesReceived() > vmNetworkStat.getBytesReceived()) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug("Received # of bytes that's less than the last one.  Assuming something went wrong and persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                                            host, vmNetworkStat.getVmName(), toHumanReadableSize(vmNetworkStat.getBytesReceived()), toHumanReadableSize(vmNetworkStat_lock.getCurrentBytesReceived()));
                                }
                                vmNetworkStat_lock.setNetBytesReceived(vmNetworkStat_lock.getNetBytesReceived() + vmNetworkStat_lock.getCurrentBytesReceived());
                            }
                            vmNetworkStat_lock.setCurrentBytesReceived(vmNetworkStat.getBytesReceived());

                            if (! _dailyOrHourly) {
                                //update agg bytes
                                vmNetworkStat_lock.setAggBytesReceived(vmNetworkStat_lock.getNetBytesReceived() + vmNetworkStat_lock.getCurrentBytesReceived());
                                vmNetworkStat_lock.setAggBytesSent(vmNetworkStat_lock.getNetBytesSent() + vmNetworkStat_lock.getCurrentBytesSent());
                            }

                            _userStatsDao.update(vmNetworkStat_lock.getId(), vmNetworkStat_lock);
                        }
                    }
                });
            } catch (Exception e) {
                logger.warn("Unable to update vm network statistics for vm: {} from host: {}", userVm, host, e);
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_CREATE, eventDescription = "deploying Vm", async = true)
    public UserVm startVirtualMachine(DeployVMCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException, ConcurrentOperationException, ResourceAllocationException {
        long vmId = cmd.getEntityId();
        if (!cmd.getStartVm()) {
            return getUserVm(vmId);
        }
        Long podId = null;
        Long clusterId = null;
        Long hostId = cmd.getHostId();
        Map<VirtualMachineProfile.Param, Object> additionalParams =  new HashMap<>();
        Map<Long, DiskOffering> diskOfferingMap = cmd.getDataDiskTemplateToDiskOfferingMap();
        if (cmd instanceof DeployVMCmdByAdmin) {
            DeployVMCmdByAdmin adminCmd = (DeployVMCmdByAdmin)cmd;
            podId = adminCmd.getPodId();
            clusterId = adminCmd.getClusterId();
        }
        VMInstanceDetailVO uefiDetail = vmInstanceDetailsDao.findDetail(cmd.getEntityId(), ApiConstants.BootType.UEFI.toString());
        if (uefiDetail != null) {
            addVmUefiBootOptionsToParams(additionalParams, uefiDetail.getName(), uefiDetail.getValue());
        }
        if (cmd.getBootIntoSetup() != null) {
            additionalParams.put(VirtualMachineProfile.Param.BootIntoSetup, cmd.getBootIntoSetup());
        }

        if (StringUtils.isNotBlank(cmd.getPassword())) {
            additionalParams.put(VirtualMachineProfile.Param.VmPassword, cmd.getPassword());
        }

        return startVirtualMachine(vmId, podId, clusterId, hostId, diskOfferingMap, additionalParams, cmd.getDeploymentPlanner());
    }

    private UserVm startVirtualMachine(long vmId, Long podId, Long clusterId, Long hostId, Map<Long, DiskOffering> diskOfferingMap
            , Map<VirtualMachineProfile.Param, Object> additonalParams, String deploymentPlannerToUse)
            throws ResourceUnavailableException,
            InsufficientCapacityException, ConcurrentOperationException, ResourceAllocationException {
        UserVmVO vm = _vmDao.findById(vmId);
        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> vmParamPair = null;

        try {
            vmParamPair = startVirtualMachine(vmId, podId, clusterId, hostId, additonalParams, deploymentPlannerToUse);
            vm = vmParamPair.first();

            // At this point VM should be in "Running" state
            UserVmVO tmpVm = _vmDao.findById(vm.getId());
            if (!tmpVm.getState().equals(State.Running)) {
                // Some other thread changed state of VM, possibly vmsync
                logger.error("VM " + tmpVm + " unexpectedly went to " + tmpVm.getState() + " state");
                throw new ConcurrentOperationException("Failed to deploy VM "+vm);
            }

            try {
                if (!diskOfferingMap.isEmpty()) {
                    List<VolumeVO> vols = _volsDao.findByInstance(tmpVm.getId());
                    for (VolumeVO vol : vols) {
                        if (vol.getVolumeType() == Volume.Type.DATADISK) {
                            DiskOffering doff =  _entityMgr.findById(DiskOffering.class, vol.getDiskOfferingId());
                            _volService.resizeVolumeOnHypervisor(vol.getId(), doff.getDiskSize(), tmpVm.getHostId(), vm.getInstanceName());
                        }
                    }
                }
            }
            catch (Exception e) {
                logger.fatal("Unable to resize the data disk for vm {} due to {}", vm, e.getMessage(), e);
            }

        } finally {
            updateVmStateForFailedVmCreation(vm.getId(), hostId);
        }

        // Check that the password was passed in and is valid
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
        if (template.isEnablePassword()) {
            // this value is not being sent to the backend; need only for api
            // display purposes
            vm.setPassword((String)vmParamPair.second().get(VirtualMachineProfile.Param.VmPassword));
        }

        return vm;
    }

    private void addUserVMCmdlineArgs(Long vmId, VirtualMachineProfile profile, DeployDestination dest, StringBuilder buf) {
        UserVmVO vm = _vmDao.findById(vmId);
        buf.append(" template=domP");
        buf.append(" name=").append(profile.getHostName());
        buf.append(" type=").append(vm.getUserVmType());
        for (NicProfile nic : profile.getNics()) {
            int deviceId = nic.getDeviceId();
            if (nic.getIPv4Address() == null) {
                buf.append(" eth").append(deviceId).append("ip=").append("0.0.0.0");
                buf.append(" eth").append(deviceId).append("mask=").append("0.0.0.0");
            } else {
                buf.append(" eth").append(deviceId).append("ip=").append(nic.getIPv4Address());
                buf.append(" eth").append(deviceId).append("mask=").append(nic.getIPv4Netmask());
            }

            if (nic.isDefaultNic()) {
                buf.append(" gateway=").append(nic.getIPv4Gateway());
            }

            if (nic.getTrafficType() == TrafficType.Management) {
                String mgmt_cidr = _configDao.getValue(Config.ManagementNetwork.key());
                if (NetUtils.isValidIp4Cidr(mgmt_cidr)) {
                    buf.append(" mgmtcidr=").append(mgmt_cidr);
                }
                buf.append(" localgw=").append(dest.getPod().getGateway());
            }
        }
        DataCenterVO dc = _dcDao.findById(profile.getVirtualMachine().getDataCenterId());
        buf.append(" internaldns1=").append(dc.getInternalDns1());
        if (dc.getInternalDns2() != null) {
            buf.append(" internaldns2=").append(dc.getInternalDns2());
        }
        buf.append(" dns1=").append(dc.getDns1());
        if (dc.getDns2() != null) {
            buf.append(" dns2=").append(dc.getDns2());
        }
        logger.info("cmdline details: "+ buf.toString());
    }

    @Override
    public boolean finalizeVirtualMachineProfile(VirtualMachineProfile profile, DeployDestination dest, ReservationContext context) {
        UserVmVO vm = _vmDao.findById(profile.getId());
        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vm.getId());
        vm.setDetails(details);
        StringBuilder buf = profile.getBootArgsBuilder();
        if (CKS_NODE.equals(vm.getUserVmType()) || SHAREDFSVM.equals(vm.getUserVmType())) {
            addUserVMCmdlineArgs(vm.getId(), profile, dest, buf);
        }
        // add userdata info into vm profile
        Nic defaultNic = _networkModel.getDefaultNic(vm.getId());
        if(defaultNic != null) {
            Network network = _networkModel.getNetwork(defaultNic.getNetworkId());
            if (_networkModel.isSharedNetworkWithoutServices(network.getId())) {
                final String serviceOffering = serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId()).getDisplayText();
                boolean isWindows = _guestOSCategoryDao.findById(_guestOSDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");
                String destHostname = VirtualMachineManager.getHypervisorHostname(dest.getHost() != null ? dest.getHost().getName() : "");
                List<String[]> vmData = _networkModel.generateVmData(vm.getUserData(), vm.getUserDataDetails(), serviceOffering, vm.getDataCenterId(), vm.getInstanceName(), vm.getHostName(), vm.getId(),
                        vm.getUuid(), defaultNic.getIPv4Address(), vm.getDetail(VmDetailConstants.SSH_PUBLIC_KEY), (String) profile.getParameter(VirtualMachineProfile.Param.VmPassword), isWindows, destHostname);
                String vmName = vm.getInstanceName();
                String configDriveIsoRootFolder = "/tmp";
                String isoFile = configDriveIsoRootFolder + "/" + vmName + "/configDrive/" + vmName + ".iso";
                profile.setVmData(vmData);
                profile.setConfigDriveLabel(VirtualMachineManager.VmConfigDriveLabel.value());
                profile.setConfigDriveIsoRootFolder(configDriveIsoRootFolder);
                profile.setConfigDriveIsoFile(isoFile);
            }
        }

        _templateMgr.prepareIsoForVmProfile(profile, dest);
        return true;
    }

    @Override
    public boolean setupVmForPvlan(boolean add, Long hostId, NicProfile nic) {
        if (!nic.getBroadCastUri().getScheme().equals("pvlan")) {
            return false;
        }
        String op = "add";
        if (!add) {
            // "delete" would remove all the rules(if using ovs) related to this vm
            op = "delete";
        }
        Network network = _networkDao.findById(nic.getNetworkId());
        Host host = _hostDao.findById(hostId);
        String networkTag = _networkModel.getNetworkTag(host.getHypervisorType(), network);
        PvlanSetupCommand cmd = PvlanSetupCommand.createVmSetup(op, nic.getBroadCastUri(), networkTag, nic.getMacAddress());
        Answer answer = null;
        try {
            answer = _agentMgr.send(hostId, cmd);
        } catch (OperationTimedoutException e) {
            logger.warn("Timed Out", e);
            return false;
        } catch (AgentUnavailableException e) {
            logger.warn("Agent Unavailable ", e);
            return false;
        }

        boolean result = true;
        if (answer == null || !answer.getResult()) {
            result = false;
        }
        return result;
    }

    @Override
    public boolean finalizeDeployment(Commands cmds, VirtualMachineProfile profile, DeployDestination dest, ReservationContext context) {
        UserVmVO userVm = _vmDao.findById(profile.getId());
        List<NicVO> nics = _nicDao.listByVmId(userVm.getId());
        for (NicVO nic : nics) {
            NetworkVO network = _networkDao.findById(nic.getNetworkId());
            if (network.getTrafficType() == TrafficType.Guest || network.getTrafficType() == TrafficType.Public) {
                userVm.setPrivateIpAddress(nic.getIPv4Address());
                userVm.setPrivateMacAddress(nic.getMacAddress());
                _vmDao.update(userVm.getId(), userVm);
            }
        }

        List<VolumeVO> volumes = _volsDao.findByInstance(userVm.getId());
        VmDiskStatisticsVO diskstats = null;
        for (VolumeVO volume : volumes) {
            diskstats = _vmDiskStatsDao.findBy(userVm.getAccountId(), userVm.getDataCenterId(), userVm.getId(), volume.getId());
            if (diskstats == null) {
                diskstats = new VmDiskStatisticsVO(userVm.getAccountId(), userVm.getDataCenterId(), userVm.getId(), volume.getId());
                _vmDiskStatsDao.persist(diskstats);
            }
        }

        finalizeCommandsOnStart(cmds, profile);
        return true;
    }

    @Override
    public boolean finalizeCommandsOnStart(Commands cmds, VirtualMachineProfile profile) {
        UserVmVO vm = _vmDao.findById(profile.getId());
        List<VMSnapshotVO> vmSnapshots = _vmSnapshotDao.findByVm(vm.getId());
        RestoreVMSnapshotCommand command = _vmSnapshotMgr.createRestoreCommand(vm, vmSnapshots);
        if (command != null) {
            cmds.addCommand("restoreVMSnapshot", command);
        }
        return true;
    }

    @Override
    public boolean finalizeStart(VirtualMachineProfile profile, long hostId, Commands cmds, ReservationContext context) {
        UserVmVO vm = _vmDao.findById(profile.getId());

        Answer[] answersToCmds = cmds.getAnswers();
        if (answersToCmds == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Returning from finalizeStart() since there are no answers to read");
            }
            return true;
        }
        Answer startAnswer = cmds.getAnswer(StartAnswer.class);
        String returnedIp = null;
        String originalIp = null;
        String originalVncPassword = profile.getVirtualMachine().getVncPassword();
        String returnedVncPassword = null;
        if (startAnswer != null) {
            StartAnswer startAns = (StartAnswer)startAnswer;
            VirtualMachineTO vmTO = startAns.getVirtualMachine();
            for (NicTO nicTO : vmTO.getNics()) {
                if (nicTO.getType() == TrafficType.Guest) {
                    returnedIp = nicTO.getIp();
                }
            }
            returnedVncPassword = vmTO.getVncPassword();
        }

        List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        NicVO guestNic = null;
        NetworkVO guestNetwork = null;
        for (NicVO nic : nics) {
            NetworkVO network = _networkDao.findById(nic.getNetworkId());
            long isDefault = (nic.isDefaultNic()) ? 1 : 0;
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vm.getAccountId(), vm.getDataCenterId(), vm.getId(), Long.toString(nic.getId()),
                    network.getNetworkOfferingId(), null, isDefault, VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
            if (network.getTrafficType() == TrafficType.Guest) {
                originalIp = nic.getIPv4Address();
                guestNic = nic;
                guestNetwork = network;
                // In vmware, we will be effecting pvlan settings in portgroups in StartCommand.
                if (profile.getHypervisorType() != HypervisorType.VMware) {
                    if (nic.getBroadcastUri().getScheme().equals("pvlan")) {
                        NicProfile nicProfile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 0, false, "pvlan-nic");
                        if (!setupVmForPvlan(true, hostId, nicProfile)) {
                            return false;
                        }
                    }
                }
            }
        }
        boolean ipChanged = false;
        if (originalIp != null && !originalIp.equalsIgnoreCase(returnedIp)) {
            if (returnedIp != null && guestNic != null) {
                guestNic.setIPv4Address(returnedIp);
                ipChanged = true;
            }
        }
        if (returnedIp != null && !returnedIp.equalsIgnoreCase(originalIp)) {
            if (guestNic != null) {
                guestNic.setIPv4Address(returnedIp);
                ipChanged = true;
            }
        }
        if (ipChanged) {
            _dcDao.findById(vm.getDataCenterId());
            UserVmVO userVm = _vmDao.findById(profile.getId());
            // dc.getDhcpProvider().equalsIgnoreCase(Provider.ExternalDhcpServer.getName())
            if (_ntwkSrvcDao.canProviderSupportServiceInNetwork(guestNetwork.getId(), Service.Dhcp, Provider.ExternalDhcpServer)) {
                _nicDao.update(guestNic.getId(), guestNic);
                userVm.setPrivateIpAddress(guestNic.getIPv4Address());
                _vmDao.update(userVm.getId(), userVm);

                logger.info("Detected that ip changed in the answer, updated nic in the db with new ip " + returnedIp);
            }
        }

        updateVncPasswordIfItHasChanged(originalVncPassword, returnedVncPassword, profile);

        // get system ip and create static nat rule for the vm
        try {
            _rulesMgr.getSystemIpAndEnableStaticNatForVm(profile.getVirtualMachine(), false);
        } catch (Exception ex) {
            logger.warn("Failed to get system ip and enable static nat for the vm " + profile.getVirtualMachine() + " due to exception ", ex);
            return false;
        }

        Answer answer = cmds.getAnswer("restoreVMSnapshot");
        if (answer != null && answer instanceof RestoreVMSnapshotAnswer) {
            RestoreVMSnapshotAnswer restoreVMSnapshotAnswer = (RestoreVMSnapshotAnswer) answer;
            if (restoreVMSnapshotAnswer == null || !restoreVMSnapshotAnswer.getResult()) {
                logger.warn("Unable to restore the vm snapshot from image file to the VM: " + restoreVMSnapshotAnswer.getDetails());
            }
        }

        final VirtualMachineProfile vmProfile = profile;
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                final UserVmVO vm = _vmDao.findById(vmProfile.getId());
                final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
                for (NicVO nic : nics) {
                    Network network = _networkModel.getNetwork(nic.getNetworkId());
                    if (GuestType.L2.equals(network.getGuestType()) || _networkModel.isSharedNetworkWithoutServices(network.getId())) {
                        vmIdCountMap.put(nic.getId(), new VmAndCountDetails(nic.getInstanceId(), VmIpFetchTrialMax.value()));
                    }
                }
            }
        });

        return true;
    }

    protected void updateVncPasswordIfItHasChanged(String originalVncPassword, String returnedVncPassword, VirtualMachineProfile profile) {
        if (returnedVncPassword != null && !originalVncPassword.equals(returnedVncPassword)) {
            UserVmVO userVm = _vmDao.findById(profile.getId());
            userVm.setVncPassword(returnedVncPassword);
            _vmDao.update(userVm.getId(), userVm);
        }
    }

    @Override
    public void finalizeExpunge(VirtualMachine vm) {
    }

    private void checkForceStopVmPermission(Account callingAccount) {
        if (!AllowUserForceStopVm.valueIn(callingAccount.getId())) {
            logger.error("Parameter [{}] can only be passed by Admin accounts or when the allow.user.force.stop.vm config is true for the account.", ApiConstants.FORCED);
            throw new PermissionDeniedException("Account does not have the permission to force stop the vm.");
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_STOP, eventDescription = "stopping Vm", async = true)
    public UserVm stopVirtualMachine(long vmId, boolean forced) throws ConcurrentOperationException {
        // Input validation
        Account caller = CallContext.current().getCallingAccount();
        Long userId = CallContext.current().getCallingUserId();

        // if account is removed, return error
        if (caller != null && caller.getRemoved() != null) {
            throw new PermissionDeniedException("The account " + caller.getUuid() + " is removed");
        }

        UserVmVO vm = _vmDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        }

        if (forced) {
            checkForceStopVmPermission(caller);
        }

        // check if vm belongs to AutoScale vm group in Disabled state
        autoScaleManager.checkIfVmActionAllowed(vmId);

        boolean status = false;
        try {
            VirtualMachineEntity vmEntity = _orchSrvc.getVirtualMachine(vm.getUuid());

            if(forced) {
                status = vmEntity.stopForced(Long.toString(userId));
            } else {
                status = vmEntity.stop(Long.toString(userId));
            }
            if (status) {
                return _vmDao.findById(vmId);
            } else {
                return null;
            }
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("Unable to contact the agent to stop the virtual machine " + vm, e);
        } catch (CloudException e) {
            throw new CloudRuntimeException("Unable to contact the agent to stop the virtual machine " + vm, e);
        }
    }

    @Override
    public void finalizeStop(VirtualMachineProfile profile, Answer answer) {
        VirtualMachine vm = profile.getVirtualMachine();
        // release elastic IP here
        IPAddressVO ip = _ipAddressDao.findByAssociatedVmId(profile.getId());
        if (ip != null && ip.getSystem()) {
            CallContext ctx = CallContext.current();
            try {
                long networkId = ip.getAssociatedWithNetworkId();
                Network guestNetwork = _networkDao.findById(networkId);
                NetworkOffering offering = _entityMgr.findById(NetworkOffering.class, guestNetwork.getNetworkOfferingId());
                assert (offering.isAssociatePublicIP() == true) : "User VM should not have system owned public IP associated with it when offering configured not to associate public IP.";
                _rulesMgr.disableStaticNat(ip.getId(), ctx.getCallingAccount(), ctx.getCallingUserId(), true);
            } catch (Exception ex) {
                logger.warn("Failed to disable static nat and release system ip " + ip + " as a part of vm " + profile.getVirtualMachine() + " stop due to exception ", ex);
            }
        }

        final List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (final NicVO nic : nics) {
            final NetworkVO network = _networkDao.findById(nic.getNetworkId());
            if (network != null && network.getTrafficType() == TrafficType.Guest) {
                if (nic.getBroadcastUri() != null && nic.getBroadcastUri().getScheme().equals("pvlan")) {
                    NicProfile nicProfile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 0, false, "pvlan-nic");
                    setupVmForPvlan(false, vm.getHostId(), nicProfile);
                }
            }
        }
    }

    @Override
    public Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> startVirtualMachine(long vmId, Long hostId, @NotNull Map<VirtualMachineProfile.Param, Object> additionalParams,
            String deploymentPlannerToUse) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        return startVirtualMachine(vmId, null, null, hostId, additionalParams, deploymentPlannerToUse);
    }

    @Override
    public Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> startVirtualMachine(long vmId, Long podId, Long clusterId, Long hostId,
            @NotNull Map<VirtualMachineProfile.Param, Object> additionalParams, String deploymentPlannerToUse)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        return startVirtualMachine(vmId, podId, clusterId, hostId, additionalParams, deploymentPlannerToUse, true);
    }

    @Override
    public Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> startVirtualMachine(long vmId, Long podId, Long clusterId, Long hostId,
            @NotNull Map<VirtualMachineProfile.Param, Object> additionalParams, String deploymentPlannerToUse, boolean isExplicitHost)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        // Input validation
        final Account callerAccount = CallContext.current().getCallingAccount();
        UserVO callerUser = _userDao.findById(CallContext.current().getCallingUserId());

        // if account is removed, return error
        if (callerAccount == null || callerAccount.getRemoved() != null) {
            throw new InvalidParameterValueException(String.format("The account %s is removed", callerAccount));
        }

        UserVmVO vm = _vmDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        }

        if (vm.getState() == State.Running) {
            throw new InvalidParameterValueException(String.format("The virtual machine %s (%s) is already running",
                    vm.getUuid(), vm.getDisplayNameOrHostName()));
        }

        _accountMgr.checkAccess(callerAccount, null, true, vm);

        Account owner = _accountDao.findById(vm.getAccountId());

        if (owner == null) {
            throw new InvalidParameterValueException("The owner of " + vm + " does not exist: " + vm.getAccountId());
        }

        if (owner.getState() == Account.State.DISABLED) {
            throw new PermissionDeniedException(String.format("The owner of %s is disabled: %s", vm, owner));
        }
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
        if (VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            // check if account/domain is with in resource limits to start a new vm
            ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
            resourceLimitService.checkVmResourceLimit(owner, vm.isDisplayVm(), offering, template);
        }
        // check if vm is security group enabled
        if (_securityGroupMgr.isVmSecurityGroupEnabled(vmId) && _securityGroupMgr.getSecurityGroupsForVm(vmId).isEmpty()
                && !_securityGroupMgr.isVmMappedToDefaultSecurityGroup(vmId) && _networkModel.canAddDefaultSecurityGroup()) {
            // if vm is not mapped to security group, create a mapping
            if (logger.isDebugEnabled()) {
                logger.debug("Vm " + vm + " is security group enabled, but not mapped to default security group; creating the mapping automatically");
            }

            SecurityGroup defaultSecurityGroup = _securityGroupMgr.getDefaultSecurityGroup(vm.getAccountId());
            if (defaultSecurityGroup != null) {
                List<Long> groupList = new ArrayList<Long>();
                groupList.add(defaultSecurityGroup.getId());
                _securityGroupMgr.addInstanceToGroups(vm, groupList);
            }
        }
        // Choose deployment planner
        // Host takes 1st preference, Cluster takes 2nd preference and Pod takes 3rd
        // Default behaviour is invoked when host, cluster or pod are not specified
        boolean isRootAdmin = _accountService.isRootAdmin(callerAccount.getId());
        Pod destinationPod = getDestinationPod(podId, isRootAdmin);
        Cluster destinationCluster = getDestinationCluster(clusterId, isRootAdmin);
        HostVO destinationHost = getDestinationHost(hostId, isRootAdmin, isExplicitHost);
        DataCenterDeployment plan = null;
        boolean deployOnGivenHost = false;
        if (destinationHost != null) {
            logger.debug("Destination Host to deploy the VM is specified, specifying a deployment plan to deploy the VM");
            _hostDao.loadHostTags(destinationHost);
            validateStrictHostTagCheck(vm, destinationHost);

            final ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
            Pair<Boolean, Boolean> cpuCapabilityAndCapacity = _capacityMgr.checkIfHostHasCpuCapabilityAndCapacity(destinationHost, offering, false);
            if (!cpuCapabilityAndCapacity.first() || !cpuCapabilityAndCapacity.second()) {
                String errorMsg;
                if (!cpuCapabilityAndCapacity.first()) {
                    errorMsg = String.format("Cannot deploy the VM to specified host %s, requested CPU and speed is more than the host capability", destinationHost);
                } else {
                    errorMsg = String.format("Cannot deploy the VM to specified host %s, host does not have enough free CPU or RAM, please check the logs", destinationHost);
                }
                logger.info(errorMsg);
                if (!AllowDeployVmIfGivenHostFails.value()) {
                    throw new InvalidParameterValueException(errorMsg);
                };
            } else {
                plan = new DataCenterDeployment(vm.getDataCenterId(), destinationHost.getPodId(), destinationHost.getClusterId(), destinationHost.getId(), null, null);
                if (!AllowDeployVmIfGivenHostFails.value()) {
                    deployOnGivenHost = true;
                }
            }
        } else if (destinationCluster != null) {
            logger.debug("Destination Cluster to deploy the VM is specified, specifying a deployment plan to deploy the VM");
            plan = new DataCenterDeployment(vm.getDataCenterId(), destinationCluster.getPodId(), destinationCluster.getId(), null, null, null);
            if (!AllowDeployVmIfGivenHostFails.value()) {
                deployOnGivenHost = true;
            }
        } else if (destinationPod != null) {
            logger.debug("Destination Pod to deploy the VM is specified, specifying a deployment plan to deploy the VM");
            plan = new DataCenterDeployment(vm.getDataCenterId(), destinationPod.getId(), null, null, null, null);
            if (!AllowDeployVmIfGivenHostFails.value()) {
                deployOnGivenHost = true;
            }
        }

        // Set parameters
        Map<VirtualMachineProfile.Param, Object> params = null;
        if (vm.isUpdateParameters()) {
            _vmDao.loadDetails(vm);

            String password = getCurrentVmPasswordOrDefineNewPassword(String.valueOf(additionalParams.getOrDefault(VirtualMachineProfile.Param.VmPassword, "")), vm, template);

            if (!validPassword(password)) {
                throw new InvalidParameterValueException("A valid password for this virtual machine was not provided.");
            }

            // Check if an SSH key pair was selected for the instance and if so
            // use it to encrypt & save the vm password
            encryptAndStorePassword(vm, password);

            params = createParameterInParameterMap(params, additionalParams, VirtualMachineProfile.Param.VmPassword, password);
        }

        if(additionalParams.containsKey(VirtualMachineProfile.Param.BootIntoSetup)) {
            if (! HypervisorType.VMware.equals(vm.getHypervisorType())) {
                throw new InvalidParameterValueException(ApiConstants.BOOT_INTO_SETUP + " makes no sense for " + vm.getHypervisorType());
            }
            Object paramValue = additionalParams.get(VirtualMachineProfile.Param.BootIntoSetup);
            if (logger.isTraceEnabled()) {
                    logger.trace("It was specified whether to enter setup mode: " + paramValue.toString());
            }
            params = createParameterInParameterMap(params, additionalParams, VirtualMachineProfile.Param.BootIntoSetup, paramValue);
        }

        VirtualMachineEntity vmEntity = _orchSrvc.getVirtualMachine(vm.getUuid());

        DeploymentPlanner planner = null;
        if (deploymentPlannerToUse != null) {
            // if set to null, the deployment planner would be later figured out either from global config var, or from
            // the service offering
            planner = _planningMgr.getDeploymentPlannerByName(deploymentPlannerToUse);
            if (planner == null) {
                throw new InvalidParameterValueException("Can't find a planner by name " + deploymentPlannerToUse);
            }
        }
        vmEntity.setParamsToEntity(additionalParams);

        String reservationId = vmEntity.reserve(planner, plan, new ExcludeList(), Long.toString(callerUser.getId()));
        vmEntity.deploy(reservationId, Long.toString(callerUser.getId()), params, deployOnGivenHost);

        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> vmParamPair = new Pair(vm, params);
        if (vm != null && vm.isUpdateParameters()) {
            // this value is not being sent to the backend; need only for api
            // display purposes
            if (template.isEnablePassword()) {
                if (vm.getDetail(VmDetailConstants.PASSWORD) != null) {
                    vmInstanceDetailsDao.removeDetail(vm.getId(), VmDetailConstants.PASSWORD);
                }
                vm.setUpdateParameters(false);
                _vmDao.update(vm.getId(), vm);
            }
        }

        return vmParamPair;
    }

    /**
     * If the template is password enabled and the VM already has a password, returns it.
     * If the template is password enabled and the VM does not have a password, sets the password to the password defined by the user and returns it. If no password is informed,
     * sets it to a random password and returns it.
     * If the template is not password enabled, returns saved_password.
     * @param newPassword The new password informed by the user in order to set the password of the VM.
     * @param vm The VM to retrieve the password from.
     * @param template The template to be checked if the password is enabled or not.
     * @return The password of the VM or saved_password.
     */
    protected String getCurrentVmPasswordOrDefineNewPassword(String newPassword, UserVmVO vm, VMTemplateVO template) {
        String password = "saved_password";

        if (template.isEnablePassword()) {
            if (vm.getDetail("password") != null) {
                logger.debug(String.format("Decrypting VM [%s] current password.", vm));
                password = DBEncryptionUtil.decrypt(vm.getDetail("password"));
            } else if (StringUtils.isNotBlank(newPassword)) {
                logger.debug(String.format("A password for VM [%s] was informed. Setting VM password to value defined by user.", vm));
                password = newPassword;
                vm.setPassword(password);
            } else {
                logger.debug(String.format("Setting VM [%s] password to a randomly generated password.", vm));
                password = _mgr.generateRandomPassword();
                vm.setPassword(password);
            }
        } else if (StringUtils.isNotBlank(newPassword)) {
            logger.debug(String.format("A password was informed; however, the template [%s] is not password enabled. Ignoring the parameter.", template));
        }

        return password;
    }

    private Map<VirtualMachineProfile.Param, Object> createParameterInParameterMap(Map<VirtualMachineProfile.Param, Object> params, Map<VirtualMachineProfile.Param, Object> parameterMap, VirtualMachineProfile.Param parameter,
            Object parameterValue) {
        if (logger.isTraceEnabled()) {
            logger.trace(String.format("createParameterInParameterMap(%s, %s)", parameter, parameterValue));
        }
        if (params == null) {
            if (logger.isTraceEnabled()) {
                logger.trace("creating new Parameter map");
            }
            params = new HashMap<>();
            if (parameterMap != null) {
                params.putAll(parameterMap);
            }
        }
        params.put(parameter, parameterValue);
        return params;
    }

    private Pod getDestinationPod(Long podId, boolean isRootAdmin) {
        Pod destinationPod = null;
        if (podId != null) {
            if (!isRootAdmin) {
                throw new PermissionDeniedException(
                        "Parameter " + ApiConstants.POD_ID + " can only be specified by a Root Admin, permission denied");
            }
            destinationPod = _podDao.findById(podId);
            if (destinationPod == null) {
                throw new InvalidParameterValueException("Unable to find the pod to deploy the VM, pod id=" + podId);
            }
        }
        return destinationPod;
    }

    private Cluster getDestinationCluster(Long clusterId, boolean isRootAdmin) {
        Cluster destinationCluster = null;
        if (clusterId != null) {
            if (!isRootAdmin) {
                throw new PermissionDeniedException(
                        "Parameter " + ApiConstants.CLUSTER_ID + " can only be specified by a Root Admin, permission denied");
            }
            destinationCluster = _clusterDao.findById(clusterId);
            if (destinationCluster == null) {
                throw new InvalidParameterValueException("Unable to find the cluster to deploy the VM, cluster id=" + clusterId);
            }
        }
        return destinationCluster;
    }

    private HostVO getDestinationHost(Long hostId, boolean isRootAdmin, boolean isExplicitHost) {
        HostVO destinationHost = null;
        if (hostId != null) {
            if (isExplicitHost && !isRootAdmin) {
                throw new PermissionDeniedException(
                        "Parameter " + ApiConstants.HOST_ID + " can only be specified by a Root Admin, permission denied");
            }
            destinationHost = _hostDao.findById(hostId);
            if (destinationHost == null) {
                throw new InvalidParameterValueException("Unable to find the host to deploy the VM, host id=" + hostId);
            } else if (destinationHost.getResourceState() != ResourceState.Enabled || destinationHost.getStatus() != Status.Up ) {
                throw new InvalidParameterValueException("Unable to deploy the VM as the host: " + destinationHost.getName() + " is not in the right state");
            }
        }
        return destinationHost;
    }

    @Override
    public UserVm destroyVm(long vmId, boolean expunge) throws ResourceUnavailableException, ConcurrentOperationException {
        // Verify input parameters
        UserVmVO vm = _vmDao.findById(vmId);
        if (vm == null || vm.getRemoved() != null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find a virtual machine with specified vmId");
            throw ex;
        }

        if (vm.getState() == State.Destroyed || vm.getState() == State.Expunging) {
            logger.trace("Vm {} is already destroyed", vm);
            return vm;
        }

        vmStatsDao.removeAllByVmId(vmId);

        boolean status;
        State vmState = vm.getState();

        Account owner = _accountMgr.getAccount(vm.getAccountId());

        ServiceOfferingVO offering = serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId());

        try (CheckedReservation vmReservation = new CheckedReservation(owner, ResourceType.user_vm, vmId, null, -1L, reservationDao, resourceLimitService);
             CheckedReservation cpuReservation = new CheckedReservation(owner, ResourceType.cpu, vmId, null, -1 * Long.valueOf(offering.getCpu()), reservationDao, resourceLimitService);
             CheckedReservation memReservation = new CheckedReservation(owner, ResourceType.memory, vmId, null, -1 * Long.valueOf(offering.getRamSize()), reservationDao, resourceLimitService);
             CheckedReservation gpuReservation = offering.getGpuCount() != null && offering.getGpuCount() > 0 ?
                     new CheckedReservation(owner, ResourceType.gpu, vmId, null, -1 * Long.valueOf(offering.getGpuCount()), reservationDao, resourceLimitService) : null;
        ) {
            try {
                VirtualMachineEntity vmEntity = _orchSrvc.getVirtualMachine(vm.getUuid());
                status = vmEntity.destroy(expunge);
            } catch (CloudException e) {
                CloudRuntimeException ex = new CloudRuntimeException("Unable to destroy with specified vmId", e);
                ex.addProxyObject(vm.getUuid(), "vmId");
                throw ex;
            }

            if (status) {
                // Mark the account's volumes as destroyed
                List<VolumeVO> volumes = _volsDao.findByInstance(vmId);
                for (VolumeVO volume : volumes) {
                    if (volume.getVolumeType().equals(Volume.Type.ROOT)) {
                        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                                Volume.class.getName(), volume.getUuid(), volume.isDisplayVolume());
                    }
                }

                if (vmState != State.Error) {
                    // Get serviceOffering and template for Virtual Machine
                    VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
                    //Update Resource Count for the given account
                    resourceCountDecrement(vm.getAccountId(), vm.isDisplayVm(), offering, template);
                }
                return _vmDao.findById(vmId);
            } else {
                CloudRuntimeException ex = new CloudRuntimeException("Failed to destroy vm with specified vmId");
                ex.addProxyObject(vm.getUuid(), "vmId");
                throw ex;
            }
        } catch (Exception e) {
                throw new CloudRuntimeException("Failed to destroy vm with specified vmId", e);
        }

    }

    @Override
    public void collectVmDiskStatistics(final UserVm userVm) {
        // Only supported for KVM and VMware
        if (!(userVm.getHypervisorType().equals(HypervisorType.KVM) || userVm.getHypervisorType().equals(HypervisorType.VMware))) {
            return;
        }
        logger.debug("Collect vm disk statistics from host before stopping VM");
        if (userVm.getHostId() == null) {
            logger.error("Unable to collect vm disk statistics for VM as the host is null, skipping VM disk statistics collection");
            return;
        }
        long hostId = userVm.getHostId();
        List<String> vmNames = new ArrayList<String>();
        vmNames.add(userVm.getInstanceName());
        final HostVO host = _hostDao.findById(hostId);
        Account account = _accountMgr.getAccount(userVm.getAccountId());

        GetVmDiskStatsAnswer diskStatsAnswer = null;
        try {
            diskStatsAnswer = (GetVmDiskStatsAnswer)_agentMgr.easySend(hostId, new GetVmDiskStatsCommand(vmNames, host.getGuid(), host.getName()));
        } catch (Exception e) {
            logger.warn("Error while collecting disk stats for vm: {} from host: {}", userVm, host, e);
            return;
        }
        if (diskStatsAnswer != null) {
            if (!diskStatsAnswer.getResult()) {
                logger.warn("Error while collecting disk stats vm: {} from host: {}; details: {}", userVm, host, diskStatsAnswer.getDetails());
                return;
            }
            try {
                final GetVmDiskStatsAnswer diskStatsAnswerFinal = diskStatsAnswer;
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) {
                        HashMap<String, List<VmDiskStatsEntry>> vmDiskStatsByName = diskStatsAnswerFinal.getVmDiskStatsMap();
                        if (vmDiskStatsByName == null) {
                            return;
                        }
                        List<VmDiskStatsEntry> vmDiskStats = vmDiskStatsByName.get(userVm.getInstanceName());
                        if (vmDiskStats == null) {
                            return;
                        }

                        for (VmDiskStatsEntry vmDiskStat : vmDiskStats) {
                            SearchCriteria<VolumeVO> sc_volume = _volsDao.createSearchCriteria();
                            sc_volume.addAnd("path", SearchCriteria.Op.EQ, vmDiskStat.getPath());
                            List<VolumeVO> volumes = _volsDao.search(sc_volume, null);
                            if ((volumes == null) || (volumes.size() == 0)) {
                                break;
                            }
                            VolumeVO volume = volumes.get(0);
                            VmDiskStatisticsVO previousVmDiskStats = _vmDiskStatsDao.findBy(userVm.getAccountId(), userVm.getDataCenterId(), userVm.getId(), volume.getId());
                            VmDiskStatisticsVO vmDiskStat_lock = _vmDiskStatsDao.lock(userVm.getAccountId(), userVm.getDataCenterId(), userVm.getId(), volume.getId());

                            if ((vmDiskStat.getIORead() == 0) && (vmDiskStat.getIOWrite() == 0) && (vmDiskStat.getBytesRead() == 0) && (vmDiskStat.getBytesWrite() == 0)) {
                                logger.debug("Read/Write of IO and Bytes are both 0. Not updating vm_disk_statistics");
                                continue;
                            }

                            if (vmDiskStat_lock == null) {
                                logger.warn("unable to find vm disk stats from host for account: {} with vm: {} and volume: {}", account, userVm, volume);
                                continue;
                            }

                            if (previousVmDiskStats != null
                                    && ((previousVmDiskStats.getCurrentIORead() != vmDiskStat_lock.getCurrentIORead()) || ((previousVmDiskStats.getCurrentIOWrite() != vmDiskStat_lock
                                    .getCurrentIOWrite())
                                            || (previousVmDiskStats.getCurrentBytesRead() != vmDiskStat_lock.getCurrentBytesRead()) || (previousVmDiskStats
                                                    .getCurrentBytesWrite() != vmDiskStat_lock.getCurrentBytesWrite())))) {
                                logger.debug("vm disk stats changed from the time" +
                                        " GetVmDiskStatsCommand was sent. Ignoring current " +
                                        "answer. Host: {} . VM: {} IO Read: {} IO Write: {} " +
                                        "Bytes Read: {} Bytes Write: {}",
                                        host, vmDiskStat, vmDiskStat.getIORead(), vmDiskStat.getIOWrite(),
                                        vmDiskStat.getBytesRead(), vmDiskStat.getBytesWrite());
                                continue;
                            }

                            if (vmDiskStat_lock.getCurrentIORead() > vmDiskStat.getIORead()) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug("Read # of IO that's less than " +
                                            "the last one.  Assuming something went wrong and " +
                                            "persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                                            host, vmDiskStat, vmDiskStat.getIORead(), vmDiskStat_lock.getCurrentIORead());
                                }
                                vmDiskStat_lock.setNetIORead(vmDiskStat_lock.getNetIORead() + vmDiskStat_lock.getCurrentIORead());
                            }
                            vmDiskStat_lock.setCurrentIORead(vmDiskStat.getIORead());
                            if (vmDiskStat_lock.getCurrentIOWrite() > vmDiskStat.getIOWrite()) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug("Write # of IO that's less than " +
                                            "the last one. Assuming something went wrong and " +
                                            "persisting it. Host: {}. VM: {} Reported: {} Stored: {}",
                                            host, vmDiskStat, vmDiskStat.getIOWrite(), vmDiskStat_lock.getCurrentIOWrite());
                                }
                                vmDiskStat_lock.setNetIOWrite(vmDiskStat_lock.getNetIOWrite() + vmDiskStat_lock.getCurrentIOWrite());
                            }
                            vmDiskStat_lock.setCurrentIOWrite(vmDiskStat.getIOWrite());
                            if (vmDiskStat_lock.getCurrentBytesRead() > vmDiskStat.getBytesRead()) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug("Read # of Bytes that's less " +
                                            "than the last one. Assuming something went wrong and" +
                                            " persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                                            host, vmDiskStat, toHumanReadableSize(vmDiskStat.getBytesRead()),
                                            toHumanReadableSize(vmDiskStat_lock.getCurrentBytesRead()));
                                }
                                vmDiskStat_lock.setNetBytesRead(vmDiskStat_lock.getNetBytesRead() + vmDiskStat_lock.getCurrentBytesRead());
                            }
                            vmDiskStat_lock.setCurrentBytesRead(vmDiskStat.getBytesRead());
                            if (vmDiskStat_lock.getCurrentBytesWrite() > vmDiskStat.getBytesWrite()) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug("Write # of Bytes that's less " +
                                            "than the last one.  Assuming something went wrong " +
                                            "and persisting it. Host: {} . VM: {} Reported: {} Stored: {}",
                                            host, vmDiskStat, toHumanReadableSize(vmDiskStat.getBytesWrite()),
                                            toHumanReadableSize(vmDiskStat_lock.getCurrentBytesWrite()));
                                }
                                vmDiskStat_lock.setNetBytesWrite(vmDiskStat_lock.getNetBytesWrite() + vmDiskStat_lock.getCurrentBytesWrite());
                            }
                            vmDiskStat_lock.setCurrentBytesWrite(vmDiskStat.getBytesWrite());

                            if (!_dailyOrHourly) {
                                //update agg bytes
                                vmDiskStat_lock.setAggIORead(vmDiskStat_lock.getNetIORead() + vmDiskStat_lock.getCurrentIORead());
                                vmDiskStat_lock.setAggIOWrite(vmDiskStat_lock.getNetIOWrite() + vmDiskStat_lock.getCurrentIOWrite());
                                vmDiskStat_lock.setAggBytesRead(vmDiskStat_lock.getNetBytesRead() + vmDiskStat_lock.getCurrentBytesRead());
                                vmDiskStat_lock.setAggBytesWrite(vmDiskStat_lock.getNetBytesWrite() + vmDiskStat_lock.getCurrentBytesWrite());
                            }

                            _vmDiskStatsDao.update(vmDiskStat_lock.getId(), vmDiskStat_lock);
                        }
                    }
                });
            } catch (Exception e) {
                logger.warn("Unable to update VM disk statistics for {} from {}", userVm, host, e);
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_EXPUNGE, eventDescription = "expunging Vm", async = true)
    public UserVm expungeVm(long vmId) throws ResourceUnavailableException, ConcurrentOperationException {
        Account caller = CallContext.current().getCallingAccount();
        Long callerId = caller.getId();

        // Verify input parameters
        UserVmVO vm = _vmDao.findById(vmId);
        if (vm == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find a virtual machine with specified vmId");
            ex.addProxyObject(String.valueOf(vmId), "vmId");
            throw ex;
        }
        if (UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }

        if (vm.getRemoved() != null) {
            logger.trace("Vm {} is already expunged", vm);
            return vm;
        }

        if (!(vm.getState() == State.Destroyed || vm.getState() == State.Expunging || vm.getState() == State.Error)) {
            CloudRuntimeException ex = new CloudRuntimeException("Please destroy vm with specified vmId before expunge");
            ex.addProxyObject(String.valueOf(vmId), "vmId");
            throw ex;
        }

        // When trying to expunge, permission is denied when the caller is not an admin and the AllowUserExpungeRecoverVm is false for the caller.
        if (!_accountMgr.isAdmin(callerId) && !AllowUserExpungeRecoverVm.valueIn(callerId)) {
            throw new PermissionDeniedException("Expunging a vm can only be done by an Admin. Or when the allow.user.expunge.recover.vm key is set.");
        }

        // check if vm belongs to AutoScale vm group in Disabled state
        autoScaleManager.checkIfVmActionAllowed(vmId);

        _vmSnapshotMgr.deleteVMSnapshotsFromDB(vmId, false);

        boolean status;

        status = expunge(vm);
        if (status) {
            return _vmDao.findByIdIncludingRemoved(vmId);
        } else {
            CloudRuntimeException ex = new CloudRuntimeException("Failed to expunge vm with specified vmId");
            ex.addProxyObject(String.valueOf(vmId), "vmId");
            throw ex;
        }

    }

    @Override
    public HypervisorType getHypervisorTypeOfUserVM(long vmId) {
        UserVmVO userVm = _vmDao.findById(vmId);
        if (userVm == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("unable to find a virtual machine with specified id");
            ex.addProxyObject(String.valueOf(vmId), "vmId");
            throw ex;
        }

        return userVm.getHypervisorType();
    }

    @Override
    public String finalizeUserData(String userData, Long userDataId, VirtualMachineTemplate template) {
        if (StringUtils.isEmpty(userData) && userDataId == null && (template == null || template.getUserDataId() == null)) {
            return null;
        }

        if (userDataId != null && StringUtils.isNotEmpty(userData)) {
            throw new InvalidParameterValueException("Both userdata and userdata ID inputs are not allowed, please provide only one");
        }
        if (template != null && template.getUserDataId() != null) {
            switch (template.getUserDataOverridePolicy()) {
                case DENYOVERRIDE:
                    if (StringUtils.isNotEmpty(userData) || userDataId != null) {
                        String msg = String.format("UserData input is not allowed here since template %s is configured to deny any userdata", template.getName());
                        throw new CloudRuntimeException(msg);
                    }
                case ALLOWOVERRIDE:
                    if (userDataId != null) {
                        UserData apiUserDataVO = userDataDao.findById(userDataId);
                        return apiUserDataVO.getUserData();
                    } else if (StringUtils.isNotEmpty(userData)) {
                        return userData;
                    } else {
                        UserData templateUserDataVO = userDataDao.findById(template.getUserDataId());
                        if (templateUserDataVO == null) {
                            String msg = String.format("UserData linked to the template %s is not found", template.getName());
                            throw new CloudRuntimeException(msg);
                        }
                        return templateUserDataVO.getUserData();
                    }
                case APPEND:
                    UserData templateUserDataVO = userDataDao.findById(template.getUserDataId());
                    if (templateUserDataVO == null) {
                        String msg = String.format("UserData linked to the template %s is not found", template.getName());
                        throw new CloudRuntimeException(msg);
                    }
                    if (userDataId != null) {
                        UserData apiUserDataVO = userDataDao.findById(userDataId);
                        return userDataManager.concatenateUserData(templateUserDataVO.getUserData(), apiUserDataVO.getUserData(), null);
                    } else if (StringUtils.isNotEmpty(userData)) {
                        return userDataManager.concatenateUserData(templateUserDataVO.getUserData(), userData, null);
                    } else {
                        return templateUserDataVO.getUserData();
                    }
                default:
                    String msg = String.format("This userdataPolicy %s is not supported for use with this feature", template.getUserDataOverridePolicy().toString());
                    throw new CloudRuntimeException(msg);            }
        } else {
            if (userDataId != null) {
                UserData apiUserDataVO = userDataDao.findById(userDataId);
                return apiUserDataVO.getUserData();
            } else if (StringUtils.isNotEmpty(userData)) {
                return userData;
            }
        }
        return null;
    }

    @Override
    public UserVm createVirtualMachine(DeployVMCmd cmd) throws InsufficientCapacityException, ResourceUnavailableException, ConcurrentOperationException,
    StorageUnavailableException, ResourceAllocationException {
        //Verify that all objects exist before passing them to the service
        Account owner = _accountService.getActiveAccountById(cmd.getEntityOwnerId());

        verifyDetails(cmd.getDetails());

        Long zoneId = cmd.getZoneId();

        DataCenter zone = _entityMgr.findById(DataCenter.class, zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id=" + zoneId);
        }

        Long serviceOfferingId = cmd.getServiceOfferingId();
        Long overrideDiskOfferingId = cmd.getOverrideDiskOfferingId();

        ServiceOffering serviceOffering = _entityMgr.findById(ServiceOffering.class, serviceOfferingId);
        if (serviceOffering == null) {
            throw new InvalidParameterValueException("Unable to find service offering: " + serviceOfferingId);
        }

        if (ServiceOffering.State.Inactive.equals(serviceOffering.getState())) {
            throw new InvalidParameterValueException(String.format("Service offering is inactive: [%s].", serviceOffering.getUuid()));
        }

        if (serviceOffering.getDiskOfferingStrictness() && overrideDiskOfferingId != null) {
            throw new InvalidParameterValueException(String.format("Cannot override disk offering id %d since provided service offering is strictly mapped to its disk offering", overrideDiskOfferingId));
        }

        if (!serviceOffering.isDynamic()) {
            for(String detail: cmd.getDetails().keySet()) {
                if(detail.equalsIgnoreCase(VmDetailConstants.CPU_NUMBER) || detail.equalsIgnoreCase(VmDetailConstants.CPU_SPEED) || detail.equalsIgnoreCase(VmDetailConstants.MEMORY)) {
                    throw new InvalidParameterValueException("cpuNumber or cpuSpeed or memory should not be specified for static service offering");
                }
            }
        }

        Account caller = CallContext.current().getCallingAccount();
        Long callerId = caller.getId();

        Long templateId = cmd.getTemplateId();
        VolumeInfo volume = null;
        SnapshotVO snapshot = null;

        if (cmd.getVolumeId() != null) {
            volume = getVolume(cmd.getVolumeId(), templateId, false);
            if (volume == null) {
                throw new InvalidParameterValueException("Could not find volume with id=" + cmd.getVolumeId());
            }
            _accountMgr.checkAccess(caller, null, true, volume);
            templateId = volume.getTemplateId();
            overrideDiskOfferingId = volume.getDiskOfferingId();
        } else if (cmd.getSnapshotId() != null) {
            snapshot = _snapshotDao.findById(cmd.getSnapshotId());
            if (snapshot == null) {
                throw new InvalidParameterValueException("Could not find snapshot with id=" + cmd.getSnapshotId());
            }
            _accountMgr.checkAccess(caller, null, true, snapshot);
            VolumeInfo volumeOfSnapshot = getVolume(snapshot.getVolumeId(), templateId, true);
            templateId = volumeOfSnapshot.getTemplateId();
            overrideDiskOfferingId = volumeOfSnapshot.getDiskOfferingId();
        }

        boolean dynamicScalingEnabled = cmd.isDynamicScalingEnabled();

        VirtualMachineTemplate template = null;
        if (volume != null || snapshot != null) {
            template = _entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId);
        } else {
            template = _entityMgr.findById(VirtualMachineTemplate.class, templateId);
        }
        // Make sure a valid template ID was specified
        if (template == null) {
            throw new InvalidParameterValueException("Unable to use template " + templateId);
        }
        if (TemplateType.VNF.equals(template.getTemplateType())) {
            vnfTemplateManager.validateVnfApplianceNics(template, cmd.getNetworkIds());
        } else if (cmd instanceof DeployVnfApplianceCmd) {
            throw new InvalidParameterValueException("Can't deploy VNF appliance from a non-VNF template");
        }

        if (cmd.isVolumeOrSnapshotProvided() &&
                (!(HypervisorType.KVM.equals(template.getHypervisorType()) || HypervisorType.KVM.equals(cmd.getHypervisor())))) {
            throw new InvalidParameterValueException("Deploying a virtual machine with existing volume/snapshot is supported only from KVM hypervisors");
        }
        ServiceOfferingJoinVO svcOffering = serviceOfferingJoinDao.findById(serviceOfferingId);

        if (template.isDeployAsIs()) {
            if (svcOffering != null && svcOffering.getRootDiskSize() != null && svcOffering.getRootDiskSize() > 0) {
                throw new InvalidParameterValueException("Failed to deploy Virtual Machine as a service offering with root disk size specified cannot be used with a deploy as-is template");
            }

            if (cmd.getDetails().get("rootdisksize") != null) {
                throw new InvalidParameterValueException("Overriding root disk size isn't supported for VMs deployed from deploy as-is templates");
            }

            // Bootmode and boottype are not supported on VMWare dpeloy-as-is templates (since 4.15)
            if ((cmd.getBootMode() != null || cmd.getBootType() != null)) {
                throw new InvalidParameterValueException("Boot type and boot mode are not supported on VMware for templates registered as deploy-as-is, as we honour what is defined in the template.");
            }
        }

        Long diskOfferingId = cmd.getDiskOfferingId();
        DiskOffering diskOffering = null;
        if (diskOfferingId != null) {
            diskOffering = _entityMgr.findById(DiskOffering.class, diskOfferingId);
            if (diskOffering == null) {
                throw new InvalidParameterValueException("Unable to find disk offering " + diskOfferingId);
            }
            if (diskOffering.isComputeOnly()) {
                throw new InvalidParameterValueException(String.format("The disk offering %s provided is directly mapped to a service offering, please provide an individual disk offering", diskOffering));
            }
        }

        if (!zone.isLocalStorageEnabled()) {
            DiskOffering diskOfferingMappedInServiceOffering = _entityMgr.findById(DiskOffering.class, serviceOffering.getDiskOfferingId());
            if (diskOfferingMappedInServiceOffering.isUseLocalStorage()) {
                throw new InvalidParameterValueException("Zone is not configured to use local storage but disk offering " + diskOfferingMappedInServiceOffering.getName() + " mapped in service offering uses it");
            }
            if (diskOffering != null && diskOffering.isUseLocalStorage()) {
                throw new InvalidParameterValueException("Zone is not configured to use local storage but disk offering " + diskOffering.getName() + " uses it");
            }
        }

        boolean isLeaseFeatureEnabled = VMLeaseManager.InstanceLeaseEnabled.value();
        if (isLeaseFeatureEnabled) {
            validateLeaseProperties(cmd.getLeaseDuration(), cmd.getLeaseExpiryAction());
        }

        List<Long> networkIds = cmd.getNetworkIds();
        LinkedHashMap<Integer, Long> userVmNetworkMap = getVmOvfNetworkMapping(zone, owner, template, cmd.getVmNetworkMap());
        if (MapUtils.isNotEmpty(userVmNetworkMap)) {
            networkIds = new ArrayList<>(userVmNetworkMap.values());
        }

        String userData = cmd.getUserData();
        Long userDataId = cmd.getUserdataId();
        String userDataDetails = null;
        if (MapUtils.isNotEmpty(cmd.getUserdataDetails())) {
            userDataDetails = cmd.getUserdataDetails().toString();
        }
        userData = finalizeUserData(userData, userDataId, template);
        userData = userDataManager.validateUserData(userData, cmd.getHttpMethod());

        boolean isRootAdmin = _accountService.isRootAdmin(callerId);

        Long hostId = cmd.getHostId();
        getDestinationHost(hostId, isRootAdmin, true);

        String ipAddress = cmd.getIpAddress();
        String ip6Address = cmd.getIp6Address();
        String macAddress = cmd.getMacAddress();
        String name = cmd.getName();
        String displayName = cmd.getDisplayName();
        UserVm vm = null;
        IpAddresses addrs = new IpAddresses(ipAddress, ip6Address, macAddress);
        Long size = cmd.getSize();
        String group = cmd.getGroup();
        List<String> sshKeyPairNames = cmd.getSSHKeyPairNames();
        Boolean displayVm = cmd.isDisplayVm();
        String keyboard = cmd.getKeyboard();
        Map<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap = cmd.getDataDiskTemplateToDiskOfferingMap();
        Map<String, String> userVmOVFProperties = cmd.getVmProperties();
        if (zone.getNetworkType() == NetworkType.Basic) {
            if (networkIds != null) {
                throw new InvalidParameterValueException("Can't specify network Ids in Basic zone");
            } else {
                vm = createBasicSecurityGroupVirtualMachine(zone, serviceOffering, template, getSecurityGroupIdList(cmd, zone, template, owner), owner, name, displayName, diskOfferingId,
                        size , group , cmd.getHypervisor(), cmd.getHttpMethod(), userData, userDataId, userDataDetails, sshKeyPairNames, cmd.getIpToNetworkMap(), addrs, displayVm , keyboard , cmd.getAffinityGroupIdList(),
                        cmd.getDetails(), cmd.getCustomId(), cmd.getDhcpOptionsMap(),
                        dataDiskTemplateToDiskOfferingMap, userVmOVFProperties, dynamicScalingEnabled, overrideDiskOfferingId, volume, snapshot);
            }
        } else {
            if (_networkModel.checkSecurityGroupSupportForNetwork(owner, zone, networkIds,
                    cmd.getSecurityGroupIdList()))  {
                vm = createAdvancedSecurityGroupVirtualMachine(zone, serviceOffering, template, networkIds, getSecurityGroupIdList(cmd, zone, template, owner), owner, name,
                        displayName, diskOfferingId, size, group, cmd.getHypervisor(), cmd.getHttpMethod(), userData, userDataId, userDataDetails, sshKeyPairNames, cmd.getIpToNetworkMap(), addrs, displayVm, keyboard,
                        cmd.getAffinityGroupIdList(), cmd.getDetails(), cmd.getCustomId(), cmd.getDhcpOptionsMap(),
                        dataDiskTemplateToDiskOfferingMap, userVmOVFProperties, dynamicScalingEnabled, overrideDiskOfferingId, null, volume, snapshot);

            } else {
                if (cmd.getSecurityGroupIdList() != null && !cmd.getSecurityGroupIdList().isEmpty()) {
                    throw new InvalidParameterValueException("Can't create vm with security groups; security group feature is not enabled per zone");
                }
                vm = createAdvancedVirtualMachine(zone, serviceOffering, template, networkIds, owner, name, displayName, diskOfferingId, size, group,
                        cmd.getHypervisor(), cmd.getHttpMethod(), userData, userDataId, userDataDetails, sshKeyPairNames, cmd.getIpToNetworkMap(), addrs, displayVm, keyboard, cmd.getAffinityGroupIdList(), cmd.getDetails(),
                        cmd.getCustomId(), cmd.getDhcpOptionsMap(), dataDiskTemplateToDiskOfferingMap, userVmOVFProperties, dynamicScalingEnabled, null, overrideDiskOfferingId, volume, snapshot);
                if (cmd instanceof DeployVnfApplianceCmd) {
                    vnfTemplateManager.createIsolatedNetworkRulesForVnfAppliance(zone, template, owner, vm, (DeployVnfApplianceCmd) cmd);
                }
            }
        }

        // check if this templateId has a child ISO
        List<VMTemplateVO> child_templates = _templateDao.listByParentTemplatetId(templateId);
        for (VMTemplateVO tmpl: child_templates){
            if (tmpl.getFormat() == Storage.ImageFormat.ISO){
                logger.info("MDOV trying to attach disk {} to the VM {}", tmpl, vm);
                _tmplService.attachIso(tmpl.getId(), vm.getId(), true);
            }
        }

        // Add extraConfig to vm_instance_details table
        String extraConfig = cmd.getExtraConfig();
        if (StringUtils.isNotBlank(extraConfig)) {
            if (EnableAdditionalVmConfig.valueIn(callerId)) {
                logger.info("Adding extra configuration to user vm: {}", vm);
                addExtraConfig(vm, extraConfig);
            } else {
                throw new InvalidParameterValueException("attempted setting extraconfig but enable.additional.vm.configuration is disabled");
            }
        }

        if (cmd.getCopyImageTags()) {
            VMTemplateVO templateOrIso = _templateDao.findById(templateId);
            if (templateOrIso != null) {
                final ResourceTag.ResourceObjectType templateType = (templateOrIso.getFormat() == ImageFormat.ISO) ? ResourceTag.ResourceObjectType.ISO : ResourceTag.ResourceObjectType.Template;
                final List<? extends ResourceTag> resourceTags = resourceTagDao.listBy(templateId, templateType);
                for (ResourceTag resourceTag : resourceTags) {
                    final ResourceTagVO copyTag = new ResourceTagVO(resourceTag.getKey(), resourceTag.getValue(), resourceTag.getAccountId(), resourceTag.getDomainId(), vm.getId(), ResourceTag.ResourceObjectType.UserVm, resourceTag.getCustomer(), vm.getUuid());
                    resourceTagDao.persist(copyTag);
                }
            }
        }
        if (isLeaseFeatureEnabled) {
            applyLeaseOnCreateInstance(vm, cmd.getLeaseDuration(), cmd.getLeaseExpiryAction(), svcOffering);
        }
        return vm;
    }

    protected void validateLeaseProperties(Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction) {
        if (ObjectUtils.allNull(leaseDuration, leaseExpiryAction) // both are null
                || (leaseDuration != null && leaseDuration == -1)) { // special condition to disable lease for instance
            return;
        }

        // any one of them have value
        // validate leaseduration
        if (leaseDuration == null || leaseDuration < 1 || leaseDuration > VMLeaseManager.MAX_LEASE_DURATION_DAYS) {
            throw new InvalidParameterValueException("Invalid leaseduration: must be a natural number (>=1) or -1, max supported value is 36500");
        }

        if (leaseExpiryAction == null) {
            throw new InvalidParameterValueException("Provide values for both: leaseduration and leaseexpiryaction");
        }
    }

    /**
     * if lease feature is enabled
     * use leaseDuration and leaseExpiryAction passed in the cmd
     * get leaseDuration from service_offering if leaseDuration is not passed
     * @param vm
     * @param leaseDuration
     * @param leaseExpiryAction
     * @param serviceOfferingJoinVO
     */
    void applyLeaseOnCreateInstance(UserVm vm, Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction, ServiceOfferingJoinVO serviceOfferingJoinVO) {
        if (leaseDuration == null) {
            leaseDuration = serviceOfferingJoinVO.getLeaseDuration();
        }
        // if leaseDuration is null or < 1, instance will never expire, nothing to be done
        if  (leaseDuration == null || leaseDuration < 1) {
            return;
        }
        leaseExpiryAction = leaseExpiryAction != null ? leaseExpiryAction : serviceOfferingJoinVO.getLeaseExpiryAction();
        if (leaseExpiryAction == null) {
            return;
        }
        addLeaseDetailsForInstance(vm, leaseDuration, leaseExpiryAction);
    }

    protected void applyLeaseOnUpdateInstance(UserVm instance, Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction) {
        validateLeaseProperties(leaseDuration, leaseExpiryAction);
        String instanceUuid = instance.getUuid();

        // vm must have active lease associated during deployment
        Map<String, String> vmDetails = vmInstanceDetailsDao.listDetailsKeyPairs(instance.getId(),
                List.of(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, VmDetailConstants.INSTANCE_LEASE_EXECUTION));
        String leaseExecution = vmDetails.get(VmDetailConstants.INSTANCE_LEASE_EXECUTION);
        String leaseExpiryDate = vmDetails.get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE);

        if (StringUtils.isEmpty(leaseExpiryDate)) {
            String errorMsg = "Lease can't be applied on instance with id: " + instanceUuid + ", it doesn't have lease associated during deployment";
            logger.debug(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        if (!VMLeaseManager.LeaseActionExecution.PENDING.name().equals(leaseExecution)) {
            String errorMsg = "Lease can't be applied on instance with id: " + instanceUuid + ", it doesn't have active lease";
            logger.debug(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        // proceed if lease is yet to expire
        long leaseExpiryTimeDiff;
        try {
             leaseExpiryTimeDiff = DateUtil.getTimeDifference(
                     DateUtil.parseDateString(TimeZone.getTimeZone("UTC"), leaseExpiryDate), new Date());
        } catch (Exception ex) {
            logger.error("Error occurred computing time difference for instance lease expiry, " +
                            "will skip applying lease for vm with id: {}", instanceUuid, ex);
            return;
        }
        if (leaseExpiryTimeDiff < 0) {
            logger.debug("Lease has expired for instance with id: {}, can't modify lease information", instanceUuid);
            throw new CloudRuntimeException("Lease is not allowed to be redefined on expired leased instance");
        }

        if (leaseDuration < 1) {
            vmInstanceDetailsDao.addDetail(instance.getId(), VmDetailConstants.INSTANCE_LEASE_EXECUTION,
                    VMLeaseManager.LeaseActionExecution.DISABLED.name(), false);
            ActionEventUtils.onActionEvent(CallContext.current().getCallingUserId(), instance.getAccountId(), instance.getDomainId(),
                    EventTypes.VM_LEASE_DISABLED, "Disabling lease on the instance", instance.getId(), ApiCommandResourceType.VirtualMachine.toString());
            return;
        }
        addLeaseDetailsForInstance(instance, leaseDuration, leaseExpiryAction);
    }

    protected void addLeaseDetailsForInstance(UserVm vm, Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction) {
        if (ObjectUtils.anyNull(vm, leaseDuration) || leaseDuration < 1) {
            logger.debug("Lease can't be applied for given vm: {}, leaseduration: {} and leaseexpiryaction: {}", vm, leaseDuration, leaseExpiryAction);
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime leaseExpiryDateTime = now.plusDays(leaseDuration);
        Date leaseExpiryDate = Date.from(leaseExpiryDateTime.atZone(ZoneOffset.UTC).toInstant());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String formattedLeaseExpiryDate = sdf.format(leaseExpiryDate);
        vmInstanceDetailsDao.addDetail(vm.getId(), VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, formattedLeaseExpiryDate, false);
        vmInstanceDetailsDao.addDetail(vm.getId(), VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION, leaseExpiryAction.name(), false);
        vmInstanceDetailsDao.addDetail(vm.getId(), VmDetailConstants.INSTANCE_LEASE_EXECUTION, "PENDING", false);
        logger.debug("Instance lease for instanceId: {} is configured to expire on: {} with action: {}", vm.getUuid(), formattedLeaseExpiryDate, leaseExpiryAction);
    }

    private VolumeInfo getVolume(long id, Long templateId, boolean isSnapshot) {
        VolumeInfo volume = volFactory.getVolume(id);
        if (volume != null) {
            if (volume.getDataStore() == null || !ScopeType.ZONE.equals(volume.getDataStore().getScope().getScopeType())) {
                throw new InvalidParameterValueException("Deployment of virtual machine is supported only for Zone-wide storage pools");
            }
            checkIfVolumeTemplateIsTheSameAsTheProvided(volume, templateId);
            if (volume.getInstanceId() != null && !isSnapshot) {
                throw new InvalidParameterValueException(String.format("The volume %s is already attached to a VM %s", volume, volume.getInstanceId()));
            }
        }
        return volume;
    }

    private void checkIfVolumeTemplateIsTheSameAsTheProvided(VolumeInfo volume, Long templateId) {
        if (volume.getTemplateId() != null) {
            if (templateId != null && !volume.getTemplateId().equals(templateId)) {
                throw new InvalidParameterValueException(String.format("The volume's template %s is not the same as the provided one %s", volume.getTemplateId(), templateId));
            }
        } else {
            throw new InvalidParameterValueException("The provided volume/snapshot doesn't have a template to deploy a VM");
        }
    }

    /**
     * Persist extra configuration data in the vm_instance_details table as key/value pair
     * @param decodedUrl String consisting of the extra config data to appended onto the vmx file for VMware instances
     */
    protected void persistExtraConfigVmware(String decodedUrl, UserVm vm) {
        boolean isValidConfig = isValidKeyValuePair(decodedUrl);
        if (isValidConfig) {
            String[] extraConfigs = decodedUrl.split("\\r?\\n");
            for (String cfg : extraConfigs) {
                // Validate cfg against unsupported operations set by admin here
                String[] allowedKeyList = VmwareAdditionalConfigAllowList.value().split(",");
                boolean validXenOrVmwareConfiguration = isValidXenOrVmwareConfiguration(cfg, allowedKeyList);
                String[] paramArray = cfg.split("=");
                if (validXenOrVmwareConfiguration && paramArray.length == 2) {
                    vmInstanceDetailsDao.addDetail(vm.getId(), paramArray[0].trim(), paramArray[1].trim(), true);
                } else {
                    throw new CloudRuntimeException("Extra config " + cfg + " is not on the list of allowed keys for VMware hypervisor hosts.");
                }
            }
        } else {
            throw new CloudRuntimeException("The passed extra config string " + decodedUrl + "contains an invalid key/value pair pattern");
        }
    }

    /**
     * Used to persist extra configuration settings in vm_instance_details table for the XenServer hypervisor
     * persists config as key/value pair e.g key = extraconfig-1 , value="PV-bootloader=pygrub" and so on to extraconfig-N where
     * N denotes the number of extra configuration settings passed by user
     *
     * @param decodedUrl A string containing extra configuration settings as key/value pairs seprated by newline escape character
     *                   e.x PV-bootloader=pygrub\nPV-args=console\nHV-Boot-policy=""
     */
    protected void persistExtraConfigXenServer(String decodedUrl, UserVm vm) {
        boolean isValidConfig = isValidKeyValuePair(decodedUrl);
        if (isValidConfig) {
            String[] extraConfigs = decodedUrl.split("\\r?\\n");
            int i = 1;
            String extraConfigKey = ApiConstants.EXTRA_CONFIG + "-";
            for (String cfg : extraConfigs) {
                // Validate cfg against unsupported operations set by admin here
                String[] allowedKeyList = XenServerAdditionalConfigAllowList.value().split(",");
                boolean validXenOrVmwareConfiguration = isValidXenOrVmwareConfiguration(cfg, allowedKeyList);
                if (validXenOrVmwareConfiguration) {
                    vmInstanceDetailsDao.addDetail(vm.getId(), extraConfigKey + String.valueOf(i), cfg, true);
                    i++;
                } else {
                    throw new CloudRuntimeException("Extra config " + cfg + " is not on the list of allowed keys for XenServer hypervisor hosts.");
                }
            }
        } else {
            String msg = String.format("The passed extra config string '%s' contains an invalid key/value pair pattern", decodedUrl);
            throw new CloudRuntimeException(msg);
        }
    }

    /**
     * Used to valid extraconfig keylvalue pair for Vmware and XenServer
     * Example of tested valid config for VMware as taken from VM instance vmx file
     * <p>
     * nvp.vm-uuid=34b3d5ea-1c25-4bb0-9250-8dc3388bfa9b
     * migrate.hostLog=i-2-67-VM-5130f8ab.hlog
     * ethernet0.address=02:00:5f:51:00:41
     * </p>
     * <p>
     * Examples of tested valid configs for XenServer
     * <p>
     * is-a-template=true\nHVM-boot-policy=\nPV-bootloader=pygrub\nPV-args=hvc0
     * </p>
     *
     * Allow the following character set {', ", -, ., =, a-z, 0-9, empty space, \n}
     *
     * @param decodedUrl String conprising of extra config key/value pairs for XenServer and Vmware
     * @return True if extraconfig is valid key/value pair
     */
    protected boolean isValidKeyValuePair(String decodedUrl) {
        // Valid pairs should look like "key-1=value1, param:key-2=value2, my.config.v0=False"
        Pattern pattern = Pattern.compile("^(?:[\\w-\\s\\.:]*=[\\w-\\s\\.'\":]*(?:\\s+|$))+$");
        Matcher matcher = pattern.matcher(decodedUrl);
        return matcher.matches();
    }

    /**
     * Validates key/value pair strings passed as extra configuration for XenServer and Vmware
     * @param cfg configuration key-value pair
     * @param allowedKeyList list of allowed configuration keys for XenServer and VMware
     * @return
     */
    protected boolean isValidXenOrVmwareConfiguration(String cfg, String[] allowedKeyList) {
        // This should be of minimum length 1
        // Value is ignored in case it is empty
        String[] cfgKeyValuePair = cfg.split("=");
        if (cfgKeyValuePair.length >= 1) {
            for (String allowedKey : allowedKeyList) {
                if (cfgKeyValuePair[0].equalsIgnoreCase(allowedKey.trim())) {
                    return true;
                }
            }
        } else {
            String msg = String.format("An incorrect configuration %s has been passed", cfg);
            throw new CloudRuntimeException(msg);
        }
        return false;
    }

    /**
     * Persist extra configuration data on KVM
     * persisted in the vm_instance_details DB as extraconfig-1, and so on depending on the number of configurations
     * For KVM, extra config is passed as XML
     * @param decodedUrl string containing xml configuration to be persisted into vm_instance_details table
     * @param vm
     */
    protected void persistExtraConfigKvm(String decodedUrl, UserVm vm) {
        // validate config against denied cfg commands
        validateKvmExtraConfig(decodedUrl, vm.getAccountId());
        String[] extraConfigs = decodedUrl.split("\n\n");
        for (String cfg : extraConfigs) {
            int i = 1;
            String[] cfgParts = cfg.split("\n");
            String extraConfigKey = ApiConstants.EXTRA_CONFIG;
            String extraConfigValue;
            if (cfgParts[0].matches("\\S+:$")) {
                extraConfigKey += "-" + cfgParts[0].substring(0, cfgParts[0].length() - 1);
                extraConfigValue = cfg.replace(cfgParts[0] + "\n", "");
            } else {
                extraConfigKey += "-" + String.valueOf(i);
                extraConfigValue = cfg;
            }
            vmInstanceDetailsDao.addDetail(vm.getId(), extraConfigKey, extraConfigValue, true);
            i++;
        }
    }
    /**
     * This method is used to validate if extra config is valid
     */
    @Override
    public void validateExtraConfig(long accountId, HypervisorType hypervisorType, String extraConfig) {
        if (!EnableAdditionalVmConfig.valueIn(accountId)) {
            throw new CloudRuntimeException("Additional VM configuration is not enabled for this account");
        }
        if (HypervisorType.KVM.equals(hypervisorType)) {
            validateKvmExtraConfig(extraConfig, accountId);
        }
    }

    /**
     * This method is called by the persistExtraConfigKvm
     * Validates passed extra configuration data for KVM and validates against deny-list of unwanted commands
     * controlled by Root admin
     * @param decodedUrl string containing xml configuration to be validated
     */
    protected void validateKvmExtraConfig(String decodedUrl, long accountId) {
        String[] allowedConfigOptionList = KvmAdditionalConfigAllowList.valueIn(accountId).split(",");
        // Skip allowed keys validation for DPDK
        if (!decodedUrl.contains(":")) {
            try {
                DocumentBuilder builder = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder();
                InputSource src = new InputSource();
                src.setCharacterStream(new StringReader(String.format("<config>\n%s\n</config>", decodedUrl)));
                Document doc = builder.parse(src);
                doc.getDocumentElement().normalize();
                NodeList nodeList=doc.getElementsByTagName("*");
                for (int i = 1; i < nodeList.getLength(); i++) { // First element is config so skip it
                    Element element = (Element)nodeList.item(i);
                    boolean isValidConfig = false;
                    String currentConfig = element.getNodeName().trim();
                    for (String tag : allowedConfigOptionList) {
                        if (currentConfig.equals(tag.trim())) {
                            isValidConfig = true;
                        }
                    }
                    if (!isValidConfig) {
                        throw new CloudRuntimeException(String.format("Extra config '%s' is not on the list of allowed keys for KVM hypervisor hosts", currentConfig));
                    }
                }
            } catch (ParserConfigurationException | IOException | SAXException e) {
                throw new CloudRuntimeException("Failed to parse additional XML configuration: " + e.getMessage());
            }
        }
    }

    /**
     * Adds extra config data to guest VM instances
     * @param extraConfig Extra Configuration settings to be added in UserVm instances for KVM, XenServer and VMware
     */
    protected void addExtraConfig(UserVm vm, String extraConfig) {
        String decodedUrl = decodeExtraConfig(extraConfig);
        HypervisorType hypervisorType = vm.getHypervisorType();

        if (hypervisorType.equals(HypervisorType.XenServer)) {
            persistExtraConfigXenServer(decodedUrl, vm);
        } else if (hypervisorType.equals(HypervisorType.KVM)) {
            persistExtraConfigKvm(decodedUrl, vm);
        } else if (hypervisorType.equals(HypervisorType.VMware)) {
            persistExtraConfigVmware(decodedUrl, vm);
        } else {
            String msg = String.format("This hypervisor %s is not supported for use with this feature", hypervisorType.toString());
            throw new CloudRuntimeException(msg);
        }
    }

    /**
     * Decodes an URL encoded string passed as extra configuration for guest VMs
     * @param encodeString URL encoded string
     * @return String result of decoded URL
     */
    protected String decodeExtraConfig(String encodeString) {
        String decodedUrl;
        try {
            decodedUrl = URLDecoder.decode(encodeString, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new CloudRuntimeException("Failed to provided decode URL string: " + e.getMessage());
        }
        return decodedUrl;
    }

    protected List<Long> getSecurityGroupIdList(SecurityGroupAction cmd) {
        if (cmd.getSecurityGroupNameList() != null && cmd.getSecurityGroupIdList() != null) {
            throw new InvalidParameterValueException("securitygroupids parameter is mutually exclusive with securitygroupnames parameter");
        }

        //transform group names to ids here
        if (cmd.getSecurityGroupNameList() != null) {
            List<Long> securityGroupIds = new ArrayList<Long>();
            for (String groupName : cmd.getSecurityGroupNameList()) {
                SecurityGroup sg = _securityGroupMgr.getSecurityGroup(groupName, cmd.getEntityOwnerId());
                if (sg == null) {
                    throw new InvalidParameterValueException("Unable to find group by name " + groupName);
                } else {
                    securityGroupIds.add(sg.getId());
                }
            }
            return securityGroupIds;
        } else {
            return cmd.getSecurityGroupIdList();
        }
    }

    protected List<Long> getSecurityGroupIdList(SecurityGroupAction cmd, DataCenter zone, VirtualMachineTemplate template, Account owner) {
        List<Long> securityGroupIdList = getSecurityGroupIdList(cmd);
        if (cmd instanceof DeployVnfApplianceCmd) {
            SecurityGroup securityGroup = vnfTemplateManager.createSecurityGroupForVnfAppliance(zone, template, owner, (DeployVnfApplianceCmd) cmd);
            if (securityGroup != null) {
                if (securityGroupIdList == null) {
                    securityGroupIdList = new ArrayList<>();
                }
                securityGroupIdList.add(securityGroup.getId());
            }
        }
        return securityGroupIdList;
    }

    // this is an opportunity to verify that parameters that came in via the Details Map are OK
    // for example, minIops and maxIops should either both be specified or neither be specified and,
    // if specified, minIops should be <= maxIops
    private void verifyDetails(Map<String,String> details) {
        if (details != null) {
            String minIops = details.get(MIN_IOPS);
            String maxIops = details.get(MAX_IOPS);

            verifyMinAndMaxIops(minIops, maxIops);

            minIops = details.get("minIopsDo");
            maxIops = details.get("maxIopsDo");

            verifyMinAndMaxIops(minIops, maxIops);

            if (details.containsKey("extraconfig")) {
                throw new InvalidParameterValueException("'extraconfig' should not be included in details as key");
            }

            for (String detailName : details.keySet()) {
                if (isExtraConfig(detailName)) {
                    throw new InvalidParameterValueException("detail name should not start with extraconfig");
                }
            }
        }
    }

    private void verifyMinAndMaxIops(String minIops, String maxIops) {
        if ((minIops != null && maxIops == null) || (minIops == null && maxIops != null)) {
            throw new InvalidParameterValueException("Either 'Min IOPS' and 'Max IOPS' must both be specified or neither be specified.");
        }

        long lMinIops;

        try {
            if (minIops != null) {
                lMinIops = Long.parseLong(minIops);
            }
            else {
                lMinIops = 0;
            }
        }
        catch (NumberFormatException ex) {
            throw new InvalidParameterValueException("'Min IOPS' must be a whole number.");
        }

        long lMaxIops;

        try {
            if (maxIops != null) {
                lMaxIops = Long.parseLong(maxIops);
            }
            else {
                lMaxIops = 0;
            }
        }
        catch (NumberFormatException ex) {
            throw new InvalidParameterValueException("'Max IOPS' must be a whole number.");
        }

        if (lMinIops > lMaxIops) {
            throw new InvalidParameterValueException("'Min IOPS' must be less than or equal to 'Max IOPS'.");
        }
    }

    @Override
    public UserVm getUserVm(long vmId) {
        return _vmDao.findById(vmId);
    }

    @Override
    public VirtualMachine getVm(long vmId) {
        return _vmInstanceDao.findById(vmId);
    }

    private VMInstanceVO preVmStorageMigrationCheck(Long vmId) {
        // access check - only root admin can migrate VM
        Account caller = CallContext.current().getCallingAccount();
        if (!_accountMgr.isRootAdmin(caller.getId())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Caller is not a root admin, permission denied to migrate the VM");
            }
            throw new PermissionDeniedException("No permission to migrate VM, Only Root Admin can migrate a VM!");
        }

        VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find the VM by id=" + vmId);
        }

        if (vm.getState() != State.Stopped) {
            InvalidParameterValueException ex = new InvalidParameterValueException("VM is not Stopped, unable to migrate the vm having the specified id");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        HypervisorType hypervisorType = vm.getHypervisorType();
        List<HypervisorType> supportedHypervisorsForNonUserVMStorageMigration = HypervisorType.getListOfHypervisorsSupportingFunctionality(Functionality.VmStorageMigration)
                .stream().filter(hypervisor -> !hypervisor.equals(HypervisorType.XenServer)).collect(Collectors.toList());
        if (vm.getType() != VirtualMachine.Type.User && !supportedHypervisorsForNonUserVMStorageMigration.contains(hypervisorType)) {
            throw new InvalidParameterValueException(String.format(
                    "Unable to migrate storage of non-user VMs for hypervisor [%s]. Operation only supported for the following hypervisors: [%s].",
                    hypervisorType, supportedHypervisorsForNonUserVMStorageMigration));
        }

        List<VolumeVO> vols = _volsDao.findByInstance(vm.getId());
        if (vols.size() > 1 &&
            !(HypervisorType.VMware.equals(hypervisorType) || HypervisorType.KVM.equals(hypervisorType))) {
               throw new InvalidParameterValueException("Data disks attached to the vm, can not migrate. Need to detach data disks first");
        }

        // Check that Vm does not have VM Snapshots
        if (_vmSnapshotDao.findByVm(vmId).size() > 0) {
            throw new InvalidParameterValueException("VM's disk cannot be migrated, please remove all the VM Snapshots for this VM");
        }

        return vm;
    }

    private VirtualMachine findMigratedVm(long vmId, VirtualMachine.Type vmType) {
        if (VirtualMachine.Type.User.equals(vmType)) {
            return _vmDao.findById(vmId);
        }
        return _vmInstanceDao.findById(vmId);
    }

    @Override
    public VirtualMachine vmStorageMigration(Long vmId, StoragePool destPool) {
        VMInstanceVO vm = preVmStorageMigrationCheck(vmId);
        Map<Long, Long> volumeToPoolIds = new HashMap<>();
        checkDestinationHypervisorType(destPool, vm);
        checkIfDestinationPoolHasSameStorageAccessGroups(destPool, vm);
        List<VolumeVO> volumes = _volsDao.findByInstance(vm.getId());
        StoragePoolVO destinationPoolVo = _storagePoolDao.findById(destPool.getId());
        Long destPoolPodId = ScopeType.CLUSTER.equals(destinationPoolVo.getScope()) || ScopeType.HOST.equals(destinationPoolVo.getScope()) ?
                destinationPoolVo.getPodId() : null;
        for (VolumeVO volume : volumes) {
            snapshotHelper.checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volume, vm.getHypervisorType());
            if (!VirtualMachine.Type.User.equals(vm.getType())) {
                // Migrate within same pod as source storage and same cluster for all disks only. Hypervisor check already done
                StoragePoolVO pool = _storagePoolDao.findById(volume.getPoolId());
                if (destPoolPodId != null &&
                        (ScopeType.CLUSTER.equals(pool.getScope()) || ScopeType.HOST.equals(pool.getScope())) &&
                        !destPoolPodId.equals(pool.getPodId())) {
                    throw new InvalidParameterValueException("Storage migration of non-user VMs cannot be done between storage pools of different pods");
                }
            }
            Pair<Boolean, String> checkResult = storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(destPool, volume);
            if (!checkResult.first()) {
                throw new CloudRuntimeException(String.format("Storage suitability check failed for volume %s with error, %s", volume, checkResult.second()));
            }
            volumeToPoolIds.put(volume.getId(), destPool.getId());
        }
        _itMgr.storageMigration(vm.getUuid(), volumeToPoolIds);
        return findMigratedVm(vm.getId(), vm.getType());
    }

    @Override
    public VirtualMachine vmStorageMigration(Long vmId, Map<String, String> volumeToPool) {
        VMInstanceVO vm = preVmStorageMigrationCheck(vmId);
        Map<Long, Long> volumeToPoolIds = new HashMap<>();
        Long poolClusterId = null;
        for (Map.Entry<String, String> entry : volumeToPool.entrySet()) {
            VolumeVO volume = _volsDao.findByUuid(entry.getKey());
            snapshotHelper.checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volume, vm.getHypervisorType());
            StoragePoolVO pool = _storagePoolDao.findPoolByUUID(entry.getValue());
            if (poolClusterId != null &&
                    (ScopeType.CLUSTER.equals(pool.getScope()) || ScopeType.HOST.equals(pool.getScope())) &&
                    !poolClusterId.equals(pool.getClusterId())) {
                throw new InvalidParameterValueException("VM's disk cannot be migrated, input destination storage pools belong to different clusters");
            }
            if (pool.getClusterId() != null) {
                poolClusterId = pool.getClusterId();
            }
            checkDestinationHypervisorType(pool, vm);
            Pair<Boolean, String> checkResult = storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool, volume);
            if (!checkResult.first()) {
                throw new CloudRuntimeException(String.format("Storage suitability check failed for volume %s with error %s", volume, checkResult.second()));
            }

            volumeToPoolIds.put(volume.getId(), pool.getId());
        }
        _itMgr.storageMigration(vm.getUuid(), volumeToPoolIds);
        return findMigratedVm(vm.getId(), vm.getType());
    }

    private void checkIfDestinationPoolHasSameStorageAccessGroups(StoragePool destPool, VMInstanceVO vm) {
        Long hostId = vm.getHostId();
        if (hostId != null) {
            Host host = _hostDao.findById(hostId);
            if (!storageManager.checkIfHostAndStoragePoolHasCommonStorageAccessGroups(host, destPool)) {
                throw new InvalidParameterValueException(String.format("Destination pool %s does not have matching storage access groups as host %s", destPool.getName(), host.getName()));
            }
        }
    }

    private void checkDestinationHypervisorType(StoragePool destPool, VMInstanceVO vm) {
        HypervisorType destHypervisorType = destPool.getHypervisor();
        if (destHypervisorType == null) {
            destHypervisorType = _clusterDao.findById(
                    destPool.getClusterId()).getHypervisorType();
        }

        if (vm.getHypervisorType() != destHypervisorType && destHypervisorType != HypervisorType.Any) {
            throw new InvalidParameterValueException("hypervisor is not compatible: dest: " + destHypervisorType.toString() + ", vm: " + vm.getHypervisorType().toString());
        }

    }

    public boolean isVMUsingLocalStorage(VMInstanceVO vm) {
        List<VolumeVO> volumes = _volsDao.findByInstance(vm.getId());
        return isAnyVmVolumeUsingLocalStorage(volumes);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_MIGRATE, eventDescription = "migrating VM", async = true)
    public VirtualMachine migrateVirtualMachine(Long vmId, Host destinationHost) throws ResourceUnavailableException, ConcurrentOperationException, ManagementServerException,
    VirtualMachineMigrationException {
        // access check - only root admin can migrate VM
        Account caller = CallContext.current().getCallingAccount();
        if (!_accountMgr.isRootAdmin(caller.getId())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Caller is not a root admin, permission denied to migrate the VM");
            }
            throw new PermissionDeniedException("No permission to migrate VM, Only Root Admin can migrate a VM!");
        }

        VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find the VM by id=" + vmId);
        }
        // business logic
        if (vm.getState() != State.Running) {
            if (logger.isDebugEnabled()) {
                logger.debug("VM is not Running, unable to migrate the vm " + vm);
            }
            InvalidParameterValueException ex = new InvalidParameterValueException("VM is not Running, unable to migrate the vm with specified id");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        checkIfHostOfVMIsInPrepareForMaintenanceState(vm, "Migrate");

        if(serviceOfferingDetailsDao.findDetail(vm.getServiceOfferingId(), GPU.Keys.pciDevice.toString()) != null) {
            throw new InvalidParameterValueException("Live Migration of GPU enabled VM is not supported");
        }

        if (!isOnSupportedHypevisorForMigration(vm)) {
            logger.error(vm + " is not XenServer/VMware/KVM/Ovm/Hyperv, cannot migrate this VM from hypervisor type " + vm.getHypervisorType());
            throw new InvalidParameterValueException("Unsupported Hypervisor Type for VM migration, we support XenServer/VMware/KVM/Ovm/Hyperv/Ovm3 only");
        }

        if (vm.getType().equals(VirtualMachine.Type.User) && vm.getHypervisorType().equals(HypervisorType.LXC)) {
            throw new InvalidParameterValueException("Unsupported Hypervisor Type for User VM migration, we support XenServer/VMware/KVM/Ovm/Hyperv/Ovm3 only");
        }

        if (isVMUsingLocalStorage(vm)) {
            logger.error(vm + " is using Local Storage, cannot migrate this VM.");
            throw new InvalidParameterValueException("Unsupported operation, VM uses Local storage, cannot migrate");
        }

        // check if migrating to same host
        long srcHostId = vm.getHostId();
        Host srcHost = _resourceMgr.getHost(srcHostId);
        if (srcHost == null) {
            throw new InvalidParameterValueException("Cannot migrate VM, host with id: " + srcHostId + " for VM not found");
        }

        DeployDestination dest = null;
        if (destinationHost == null) {
            dest = chooseVmMigrationDestination(vm, srcHost, null);
        } else {
            dest = checkVmMigrationDestination(vm, srcHost, destinationHost);
        }

        // If no suitable destination found then throw exception
        if (dest == null) {
            throw new CloudRuntimeException("Unable to find suitable destination to migrate VM " + vm.getInstanceName());
        }

        collectVmDiskAndNetworkStatistics(vmId, State.Running);
        _itMgr.migrate(vm.getUuid(), srcHostId, dest);
        return findMigratedVm(vm.getId(), vm.getType());
    }

    private DeployDestination chooseVmMigrationDestination(VMInstanceVO vm, Host srcHost, Long poolId) {
        vm.setLastHostId(null); // Last host does not have higher priority in vm migration
        final ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null, offering, null, null);
        final Long srcHostId = srcHost.getId();
        final Host host = _hostDao.findById(srcHostId);
        ExcludeList excludes = new ExcludeList();
        excludes.addHost(srcHostId);
        final DataCenterDeployment plan = _itMgr.getMigrationDeployment(vm, host, poolId, excludes);
        try {
            return _planningMgr.planDeployment(profile, plan, excludes, null);
        } catch (final AffinityConflictException e2) {
            logger.warn("Unable to create deployment, affinity rules associated to the VM conflict", e2);
            throw new CloudRuntimeException("Unable to create deployment, affinity rules associated to the VM conflict");
        } catch (final InsufficientServerCapacityException e3) {
            throw new CloudRuntimeException("Unable to find a server to migrate the vm to");
        }
    }

    protected boolean checkEnforceStrictHostTagCheck(VMInstanceVO vm, HostVO host) {
        ServiceOffering serviceOffering = serviceOfferingDao.findByIdIncludingRemoved(vm.getServiceOfferingId());
        VirtualMachineTemplate template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
        return checkEnforceStrictHostTagCheck(host, serviceOffering, template);
    }

    private boolean checkEnforceStrictHostTagCheck(HostVO host, ServiceOffering serviceOffering, VirtualMachineTemplate template) {
        Set<String> strictHostTags = UserVmManager.getStrictHostTags();
        return host.checkHostServiceOfferingAndTemplateTags(serviceOffering, template, strictHostTags);
    }

    protected void validateStorageAccessGroupsOnHosts(Host srcHost, Host destinationHost) {
        String[] storageAccessGroupsOnSrcHost = storageManager.getStorageAccessGroups(null, null, null, srcHost.getId());
        String[] storageAccessGroupsOnDestHost = storageManager.getStorageAccessGroups(null, null, null, destinationHost.getId());

        List<String> srcHostStorageAccessGroupsList = storageAccessGroupsOnSrcHost != null ? Arrays.asList(storageAccessGroupsOnSrcHost) : Collections.emptyList();
        List<String> destHostStorageAccessGroupsList = storageAccessGroupsOnDestHost != null ? Arrays.asList(storageAccessGroupsOnDestHost) : Collections.emptyList();

        if (CollectionUtils.isEmpty(srcHostStorageAccessGroupsList)) {
            return;
        }

        if (CollectionUtils.isEmpty(destHostStorageAccessGroupsList)) {
            throw new CloudRuntimeException("Source host has storage access groups, but destination host has none.");
        }

        if (!destHostStorageAccessGroupsList.containsAll(srcHostStorageAccessGroupsList)) {
            throw new CloudRuntimeException("Storage access groups on the source and destination hosts did not match.");
        }
    }

    protected void validateStrictHostTagCheck(VMInstanceVO vm, HostVO host) {
        ServiceOffering serviceOffering = serviceOfferingDao.findByIdIncludingRemoved(vm.getServiceOfferingId());
        VirtualMachineTemplate template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());

        if (!checkEnforceStrictHostTagCheck(host, serviceOffering, template)) {
            Set<String> missingTags = host.getHostServiceOfferingAndTemplateMissingTags(serviceOffering, template, UserVmManager.getStrictHostTags());
            logger.error("Cannot deploy VM: {} to host : {} due to tag mismatch. host tags: {}, " +
                            "strict host tags: {} serviceOffering tags: {}, template tags: {}, missing tags: {}",
                    vm, host, host.getHostTags(), UserVmManager.getStrictHostTags(), serviceOffering.getHostTag(), template.getTemplateTag(), missingTags);
            throw new InvalidParameterValueException(String.format("Cannot deploy VM, destination host: %s " +
                    "is not compatible for the VM", host.getName()));
        }
    }

    private DeployDestination checkVmMigrationDestination(VMInstanceVO vm, Host srcHost, Host destinationHost) throws VirtualMachineMigrationException {
        if (destinationHost == null) {
            return null;
        }
        if (destinationHost.getId() == srcHost.getId()) {
            throw new InvalidParameterValueException("Cannot migrate VM, VM is already present on this host, please specify valid destination host to migrate the VM");
        }

        // check if host is UP
        if (destinationHost.getState() != com.cloud.host.Status.Up || destinationHost.getResourceState() != ResourceState.Enabled) {
            throw new InvalidParameterValueException("Cannot migrate VM, destination host is not in correct state, has status: " + destinationHost.getState() + ", state: "
                    + destinationHost.getResourceState());
        }

        if (vm.getType() != VirtualMachine.Type.User) {
            // for System VMs check that the destination host is within the same pod
            if (srcHost.getPodId() != null && !srcHost.getPodId().equals(destinationHost.getPodId())) {
                throw new InvalidParameterValueException("Cannot migrate the VM, destination host is not in the same pod as current host of the VM");
            }
        }

        if (dpdkHelper.isVMDpdkEnabled(vm.getId()) && !dpdkHelper.isHostDpdkEnabled(destinationHost.getId())) {
            throw new CloudRuntimeException("Cannot migrate VM, VM is DPDK enabled VM but destination host is not DPDK enabled");
        }

        HostVO destinationHostVO = _hostDao.findById(destinationHost.getId());
        _hostDao.loadHostTags(destinationHostVO);
        validateStrictHostTagCheck(vm, destinationHostVO);
        validateStorageAccessGroupsOnHosts(srcHost, destinationHost);

        checkHostsDedication(vm, srcHost.getId(), destinationHost.getId());

        // call to core process
        DataCenterVO dcVO = _dcDao.findById(destinationHost.getDataCenterId());
        HostPodVO pod = _podDao.findById(destinationHost.getPodId());
        Cluster cluster = _clusterDao.findById(destinationHost.getClusterId());
        DeployDestination dest = new DeployDestination(dcVO, pod, cluster, destinationHost);

        // check max guest vm limit for the destinationHost
        if (_capacityMgr.checkIfHostReachMaxGuestLimit(destinationHostVO)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Host: {} already has max Running VMs(count includes system VMs), cannot migrate to this host", destinationHost);
            }
            throw new VirtualMachineMigrationException(String.format("Destination host: %s already has max Running VMs(count includes system VMs), cannot migrate to this host", destinationHost));
        }
        //check if there are any ongoing volume snapshots on the volumes associated with the VM.
        Long vmId = vm.getId();
        logger.debug("Checking if there are any ongoing snapshots volumes associated with VM {}", vm);
        if (checkStatusOfVolumeSnapshots(vm, null)) {
            throw new CloudRuntimeException("There is/are unbacked up snapshot(s) on volume(s) attached to this VM, VM Migration is not permitted, please try again later.");
        }
        logger.debug("Found no ongoing snapshots on volumes associated with the vm {}", vm);

        return dest;
    }

    private boolean isOnSupportedHypevisorForMigration(VMInstanceVO vm) {
        return (vm.getHypervisorType().equals(HypervisorType.XenServer) ||
                vm.getHypervisorType().equals(HypervisorType.VMware) ||
                vm.getHypervisorType().equals(HypervisorType.KVM) ||
                vm.getHypervisorType().equals(HypervisorType.Ovm) ||
                vm.getHypervisorType().equals(HypervisorType.Hyperv) ||
                vm.getHypervisorType().equals(HypervisorType.LXC) ||
                vm.getHypervisorType().equals(HypervisorType.Simulator) ||
                vm.getHypervisorType().equals(HypervisorType.Ovm3));
    }

    private boolean checkIfHostIsDedicated(HostVO host) {
        long hostId = host.getId();
        DedicatedResourceVO dedicatedHost = _dedicatedDao.findByHostId(hostId);
        DedicatedResourceVO dedicatedClusterOfHost = _dedicatedDao.findByClusterId(host.getClusterId());
        DedicatedResourceVO dedicatedPodOfHost = _dedicatedDao.findByPodId(host.getPodId());
        if (dedicatedHost != null || dedicatedClusterOfHost != null || dedicatedPodOfHost != null) {
            return true;
        } else {
            return false;
        }
    }

    private void checkIfHostOfVMIsInPrepareForMaintenanceState(VirtualMachine vm, String operation) {
        long hostId = vm.getHostId();
        HostVO host = _hostDao.findById(hostId);
        if (host.getResourceState() != ResourceState.PrepareForMaintenance) {
            return;
        }

        logger.debug("Host is in PrepareForMaintenance state - {} VM operation on the VM: {} is not allowed", operation, vm);
        throw new InvalidParameterValueException(String.format("%s VM operation on the VM: %s is not allowed as host is preparing for maintenance mode", operation, vm));
    }

    private Long accountOfDedicatedHost(HostVO host) {
        long hostId = host.getId();
        DedicatedResourceVO dedicatedHost = _dedicatedDao.findByHostId(hostId);
        DedicatedResourceVO dedicatedClusterOfHost = _dedicatedDao.findByClusterId(host.getClusterId());
        DedicatedResourceVO dedicatedPodOfHost = _dedicatedDao.findByPodId(host.getPodId());
        if (dedicatedHost != null) {
            return dedicatedHost.getAccountId();
        }
        if (dedicatedClusterOfHost != null) {
            return dedicatedClusterOfHost.getAccountId();
        }
        if (dedicatedPodOfHost != null) {
            return dedicatedPodOfHost.getAccountId();
        }
        return null;
    }

    private Long domainOfDedicatedHost(HostVO host) {
        long hostId = host.getId();
        DedicatedResourceVO dedicatedHost = _dedicatedDao.findByHostId(hostId);
        DedicatedResourceVO dedicatedClusterOfHost = _dedicatedDao.findByClusterId(host.getClusterId());
        DedicatedResourceVO dedicatedPodOfHost = _dedicatedDao.findByPodId(host.getPodId());
        if (dedicatedHost != null) {
            return dedicatedHost.getDomainId();
        }
        if (dedicatedClusterOfHost != null) {
            return dedicatedClusterOfHost.getDomainId();
        }
        if (dedicatedPodOfHost != null) {
            return dedicatedPodOfHost.getDomainId();
        }
        return null;
    }

    public void checkHostsDedication(VMInstanceVO vm, long srcHostId, long destHostId) {
        HostVO srcHost = _hostDao.findById(srcHostId);
        HostVO destHost = _hostDao.findById(destHostId);
        boolean srcExplDedicated = checkIfHostIsDedicated(srcHost);
        boolean destExplDedicated = checkIfHostIsDedicated(destHost);
        //if srcHost is explicitly dedicated and destination Host is not
        if (srcExplDedicated && !destExplDedicated) {
            //raise an alert
            String msg = String.format("VM is being migrated from a explicitly dedicated host %s to non-dedicated host %s", srcHost, destHost);
            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
            logger.warn(msg);
        }
        //if srcHost is non dedicated but destination Host is explicitly dedicated
        if (!srcExplDedicated && destExplDedicated) {
            //raise an alert
            String msg = String.format("VM is being migrated from a non dedicated host %s to a explicitly dedicated host %s", srcHost, destHost);
            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
            logger.warn(msg);
        }

        //if hosts are dedicated to different account/domains, raise an alert
        if (srcExplDedicated && destExplDedicated) {
            if (!((accountOfDedicatedHost(srcHost) == null) || (accountOfDedicatedHost(srcHost).equals(accountOfDedicatedHost(destHost))))) {
                String msg = String.format("VM is being migrated from host %s explicitly dedicated to account %d to host %s explicitly dedicated to account %d",
                        srcHost, accountOfDedicatedHost(srcHost), destHost, accountOfDedicatedHost(destHost));
                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
                logger.warn(msg);
            }
            if (!((domainOfDedicatedHost(srcHost) == null) || (domainOfDedicatedHost(srcHost).equals(domainOfDedicatedHost(destHost))))) {
                String msg = String.format("VM is being migrated from host %s explicitly dedicated to domain %d to host %s explicitly dedicated to domain %d",
                        srcHost, domainOfDedicatedHost(srcHost), destHost, domainOfDedicatedHost(destHost));
                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
                logger.warn(msg);
            }
        }

        // Checks for implicitly dedicated hosts
        ServiceOfferingVO deployPlanner = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
        if (deployPlanner.getDeploymentPlanner() != null && deployPlanner.getDeploymentPlanner().equals("ImplicitDedicationPlanner")) {
            //VM is deployed using implicit planner
            long accountOfVm = vm.getAccountId();
            String msg = String.format("VM of account %d with implicit deployment planner being migrated to host %s", accountOfVm, destHost);
            //Get all vms on destination host
            boolean emptyDestination = false;
            List<VMInstanceVO> vmsOnDest = getVmsOnHost(destHostId);
            if (vmsOnDest == null || vmsOnDest.isEmpty()) {
                emptyDestination = true;
            }

            if (!emptyDestination) {
                //Check if vm is deployed using strict implicit planner
                if (!isServiceOfferingUsingPlannerInPreferredMode(vm.getServiceOfferingId())) {
                    //Check if all vms on destination host are created using strict implicit mode
                    if (!checkIfAllVmsCreatedInStrictMode(accountOfVm, vmsOnDest)) {
                        msg = String.format("VM of account %d with strict implicit deployment planner being migrated to host %s not having all vms strict implicitly dedicated to account %d", accountOfVm, destHost, accountOfVm);
                    }
                } else {
                    //If vm is deployed using preferred implicit planner, check if all vms on destination host must be
                    //using implicit planner and must belong to same account
                    for (VMInstanceVO vmsDest : vmsOnDest) {
                        ServiceOfferingVO destPlanner = serviceOfferingDao.findById(vm.getId(), vmsDest.getServiceOfferingId());
                        if (!((destPlanner.getDeploymentPlanner() != null && destPlanner.getDeploymentPlanner().equals("ImplicitDedicationPlanner")) && vmsDest.getAccountId() == accountOfVm)) {
                            msg = String.format("VM of account %d with preffered implicit deployment planner being migrated to host %s not having all vms implicitly dedicated to account %d", accountOfVm, destHost, accountOfVm);
                        }
                    }
                }
            }
            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
            logger.warn(msg);

        } else {
            //VM is not deployed using implicit planner, check if it migrated between dedicated hosts
            List<PlannerHostReservationVO> reservedHosts = _plannerHostReservationDao.listAllDedicatedHosts();
            boolean srcImplDedicated = false;
            boolean destImplDedicated = false;
            String msg = null;
            for (PlannerHostReservationVO reservedHost : reservedHosts) {
                if (reservedHost.getHostId() == srcHostId) {
                    srcImplDedicated = true;
                }
                if (reservedHost.getHostId() == destHostId) {
                    destImplDedicated = true;
                }
            }
            if (srcImplDedicated) {
                if (destImplDedicated) {
                    msg = String.format("VM is being migrated from implicitly dedicated host %s to another implicitly dedicated host %s", srcHost, destHost);
                } else {
                    msg = String.format("VM is being migrated from implicitly dedicated host %s to shared host %s", srcHost, destHost);
                }
                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
                logger.warn(msg);
            } else {
                if (destImplDedicated) {
                    msg = String.format("VM is being migrated from shared host %s to implicitly dedicated host %s", srcHost, destHost);
                    _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);
                    logger.warn(msg);
                }
            }
        }
    }

    private List<VMInstanceVO> getVmsOnHost(long hostId) {
        List<VMInstanceVO> vms =  _vmInstanceDao.listUpByHostId(hostId);
        List<VMInstanceVO> vmsByLastHostId = _vmInstanceDao.listByLastHostId(hostId);
        if (vmsByLastHostId.size() > 0) {
            // check if any VMs are within skip.counting.hours, if yes we have to consider the host.
            for (VMInstanceVO stoppedVM : vmsByLastHostId) {
                long secondsSinceLastUpdate = (DateUtil.currentGMTTime().getTime() - stoppedVM.getUpdateTime().getTime()) / 1000;
                if (secondsSinceLastUpdate < capacityReleaseInterval) {
                    vms.add(stoppedVM);
                }
            }
        }

        return vms;
    }

    private boolean isServiceOfferingUsingPlannerInPreferredMode(long serviceOfferingId) {
        boolean preferred = false;
        Map<String, String> details = serviceOfferingDetailsDao.listDetailsKeyPairs(serviceOfferingId);
        if (details != null && !details.isEmpty()) {
            String preferredAttribute = details.get("ImplicitDedicationMode");
            if (preferredAttribute != null && preferredAttribute.equals("Preferred")) {
                preferred = true;
            }
        }
        return preferred;
    }

    private boolean checkIfAllVmsCreatedInStrictMode(Long accountId, List<VMInstanceVO> allVmsOnHost) {
        boolean createdByImplicitStrict = true;
        if (allVmsOnHost.isEmpty()) {
            return false;
        }
        for (VMInstanceVO vm : allVmsOnHost) {
            if (!isImplicitPlannerUsedByOffering(vm.getServiceOfferingId()) || vm.getAccountId() != accountId) {
                logger.info("Host {} for VM {} found to be running a vm created by a planner other than implicit, or running vms of other account",
                        _hostDao.findById(vm.getHostId()), vm);
                createdByImplicitStrict = false;
                break;
            } else if (isServiceOfferingUsingPlannerInPreferredMode(vm.getServiceOfferingId()) || vm.getAccountId() != accountId) {
                logger.info("Host {} for VM {} found to be running a vm created by an implicit planner in preferred mode, or running vms of other account",
                        _hostDao.findById(vm.getHostId()), vm);
                createdByImplicitStrict = false;
                break;
            }
        }
        return createdByImplicitStrict;
    }

    private boolean isImplicitPlannerUsedByOffering(long offeringId) {
        boolean implicitPlannerUsed = false;
        ServiceOfferingVO offering = serviceOfferingDao.findByIdIncludingRemoved(offeringId);
        if (offering == null) {
            logger.error("Couldn't retrieve the offering by the given id : " + offeringId);
        } else {
            String plannerName = offering.getDeploymentPlanner();
            if (plannerName != null) {
                if (plannerName.equals("ImplicitDedicationPlanner")) {
                    implicitPlannerUsed = true;
                }
            }
        }

        return implicitPlannerUsed;
    }

    protected boolean isAnyVmVolumeUsingLocalStorage(final List<VolumeVO> volumes) {
        for (VolumeVO vol : volumes) {
            DiskOfferingVO diskOffering = _diskOfferingDao.findById(vol.getDiskOfferingId());
            if (diskOffering.isUseLocalStorage()) {
                return true;
            }
            StoragePoolVO storagePool = _storagePoolDao.findById(vol.getPoolId());
            if (storagePool.isLocal()) {
                return true;
            }
        }
        return false;
    }

    protected boolean isAllVmVolumesOnZoneWideStore(final List<VolumeVO> volumes) {
        if (CollectionUtils.isEmpty(volumes)) {
            return false;
        }
        for (Volume volume : volumes) {
            if (volume == null || volume.getPoolId() == null) {
                return false;
            }
            StoragePoolVO pool = _storagePoolDao.findById(volume.getPoolId());
            if (pool == null || !ScopeType.ZONE.equals(pool.getScope())) {
                return false;
            }
        }
        return true;
    }

    private Pair<Host, Host> getHostsForMigrateVmWithStorage(VMInstanceVO vm, Host destinationHost) throws VirtualMachineMigrationException {
        long srcHostId = vm.getHostId();
        Host srcHost = _resourceMgr.getHost(srcHostId);

        if (srcHost == null) {
            throw new InvalidParameterValueException("Cannot migrate VM, host with ID: " + srcHostId + " for VM not found");
        }

        if (destinationHost == null) {
            return new Pair<>(srcHost, null);
        }

        // Check if source and destination hosts are valid and migrating to same host
        if (destinationHost.getId() == srcHostId) {
            throw new InvalidParameterValueException(String.format("Cannot migrate VM as it is already present on host %s (ID: %s), please specify valid destination host to migrate the VM",
                    destinationHost.getName(), destinationHost.getUuid()));
        }

        String srcHostVersion = srcHost.getHypervisorVersion();
        String destHostVersion = destinationHost.getHypervisorVersion();

        // Check if the source and destination hosts are of the same type and support storage motion.
        if (!srcHost.getHypervisorType().equals(destinationHost.getHypervisorType())) {
            throw new CloudRuntimeException("The source and destination hosts are not of the same type and version. Source hypervisor type and version: " +
                    srcHost.getHypervisorType().toString() + " " + srcHostVersion + ", Destination hypervisor type and version: " +
                    destinationHost.getHypervisorType().toString() + " " + destHostVersion);
        }

        if (!VirtualMachine.Type.User.equals(vm.getType())) {
            // for System VMs check that the destination host is within the same pod
            if (srcHost.getPodId() != null && !srcHost.getPodId().equals(destinationHost.getPodId())) {
                throw new InvalidParameterValueException("Cannot migrate the VM, destination host is not in the same pod as current host of the VM");
            }
        }

        if (HypervisorType.KVM.equals(srcHost.getHypervisorType())) {
            if (srcHostVersion == null) {
                srcHostVersion = "";
            }

            if (destHostVersion == null) {
                destHostVersion = "";
            }
        }

        if (!_hypervisorCapabilitiesDao.isStorageMotionSupported(srcHost.getHypervisorType(), srcHostVersion)) {
            throw new CloudRuntimeException(String.format("Migration with storage isn't supported for source host %s (ID: %s) on hypervisor %s with version %s", srcHost.getName(), srcHost.getUuid(), srcHost.getHypervisorType(), srcHost.getHypervisorVersion()));
        }

        if (srcHostVersion == null || !srcHostVersion.equals(destHostVersion)) {
            if (!_hypervisorCapabilitiesDao.isStorageMotionSupported(destinationHost.getHypervisorType(), destHostVersion)) {
                throw new CloudRuntimeException(String.format("Migration with storage isn't supported for target host %s (ID: %s) on hypervisor %s with version %s", destinationHost.getName(), destinationHost.getUuid(), destinationHost.getHypervisorType(), destinationHost.getHypervisorVersion()));
            }
        }

        // Check if destination host is up.
        if (destinationHost.getState() != com.cloud.host.Status.Up || destinationHost.getResourceState() != ResourceState.Enabled) {
            throw new CloudRuntimeException(String.format("Cannot migrate VM, destination host %s (ID: %s) is not in correct state, has status: %s, state: %s",
                    destinationHost.getName(), destinationHost.getUuid(), destinationHost.getState(), destinationHost.getResourceState()));
        }

        // Check max guest vm limit for the destinationHost.
        if (_capacityMgr.checkIfHostReachMaxGuestLimit(destinationHost)) {
            throw new VirtualMachineMigrationException(String.format("Cannot migrate VM as destination host %s (ID: %s) already has max running vms (count includes system VMs)",
                    destinationHost.getName(), destinationHost.getUuid()));
        }

        validateStorageAccessGroupsOnHosts(srcHost, destinationHost);

        return new Pair<>(srcHost, destinationHost);
    }

    private List<VolumeVO> getVmVolumesForMigrateVmWithStorage(VMInstanceVO vm) {
        List<VolumeVO> vmVolumes = _volsDao.findUsableVolumesForInstance(vm.getId());
        for (VolumeVO volume : vmVolumes) {
            if (volume.getState() != Volume.State.Ready) {
                throw new CloudRuntimeException(String.format("Volume %s (ID: %s) of the VM is not in Ready state. Cannot migrate the VM %s (ID: %s) with its volumes", volume.getName(), volume.getUuid(), vm.getInstanceName(), vm.getUuid()));
            }
        }
        return vmVolumes;
    }

    private Map<Long, Long> getVolumePoolMappingForMigrateVmWithStorage(VMInstanceVO vm, Map<String, String> volumeToPool) {
        Map<Long, Long> volToPoolObjectMap = new HashMap<Long, Long>();

        List<VolumeVO> vmVolumes = getVmVolumesForMigrateVmWithStorage(vm);

        if (MapUtils.isNotEmpty(volumeToPool)) {
            // Check if all the volumes and pools passed as parameters are valid.
            for (Map.Entry<String, String> entry : volumeToPool.entrySet()) {
                VolumeVO volume = _volsDao.findByUuid(entry.getKey());
                StoragePoolVO pool = _storagePoolDao.findByUuid(entry.getValue());
                if (volume == null) {
                    throw new InvalidParameterValueException("There is no volume present with the given id " + entry.getKey());
                } else if (pool == null) {
                    throw new InvalidParameterValueException("There is no storage pool present with the given id " + entry.getValue());
                } else if (pool.isInMaintenance()) {
                    throw new InvalidParameterValueException("Cannot migrate volume " + volume + "to the destination storage pool " + pool.getName() +
                            " as the storage pool is in maintenance mode.");
                } else {
                    // Verify the volume given belongs to the vm.
                    if (!vmVolumes.contains(volume)) {
                        throw new InvalidParameterValueException(String.format("Volume " + volume + " doesn't belong to the VM %s (ID: %s) that has to be migrated", vm.getInstanceName(), vm.getUuid()));
                    }
                    volToPoolObjectMap.put(volume.getId(), pool.getId());
                }
                HostVO host = _hostDao.findById(vm.getHostId());
                if (!storageManager.checkIfHostAndStoragePoolHasCommonStorageAccessGroups(host, pool)) {
                    throw new InvalidParameterValueException(String.format("Destination pool %s for the volume %s does not have matching storage access groups as host %s", pool.getName(), volume.getName(), host.getName()));
                }

                HypervisorType hypervisorType = _volsDao.getHypervisorType(volume.getId());
                try {
                    snapshotHelper.checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volume, hypervisorType);
                } catch (CloudRuntimeException ex) {
                    throw new CloudRuntimeException(String.format("Unable to migrate %s to the destination storage pool [%s] due to [%s]", volume,
                            new ToStringBuilder(pool, ToStringStyle.JSON_STYLE).append("uuid", pool.getUuid()).append("name", pool.getName()).toString(), ex.getMessage()), ex);
                }

                if (hypervisorType.equals(HypervisorType.VMware)) {
                    try {
                        DiskOffering diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
                        DiskProfile diskProfile = new DiskProfile(volume, diskOffering, _volsDao.getHypervisorType(volume.getId()));
                        Pair<Volume, DiskProfile> volumeDiskProfilePair = new Pair<>(volume, diskProfile);
                        boolean isStoragePoolStoragepolicyCompliance = storageManager.isStoragePoolCompliantWithStoragePolicy(Arrays.asList(volumeDiskProfilePair), pool);
                        if (!isStoragePoolStoragepolicyCompliance) {
                            throw new CloudRuntimeException(String.format("Storage pool %s is not storage policy compliance with the volume %s", pool.getUuid(), volume.getUuid()));
                        }
                    } catch (StorageUnavailableException e) {
                        throw new CloudRuntimeException(String.format("Could not verify storage policy compliance against storage pool %s due to exception %s", pool.getUuid(), e.getMessage()));
                    }
                }
            }
        }
        return volToPoolObjectMap;
    }

    protected boolean isVmCanBeMigratedWithoutStorage(Host srcHost, Host destinationHost, List<VolumeVO> volumes,
          Map<String, String> volumeToPool) {
        return !isAnyVmVolumeUsingLocalStorage(volumes) &&
                MapUtils.isEmpty(volumeToPool) && destinationHost != null
                && (destinationHost.getClusterId().equals(srcHost.getClusterId()) || isAllVmVolumesOnZoneWideStore(volumes));
    }

    protected Host chooseVmMigrationDestinationUsingVolumePoolMap(VMInstanceVO vm, Host srcHost, Map<Long, Long> volToPoolObjectMap) {
        Long poolId = null;
        if (MapUtils.isNotEmpty(volToPoolObjectMap)) {
            poolId = new ArrayList<>(volToPoolObjectMap.values()).get(0);
        }
        DeployDestination deployDestination = chooseVmMigrationDestination(vm, srcHost, poolId);
        if (deployDestination == null || deployDestination.getHost() == null) {
            throw new CloudRuntimeException("Unable to find suitable destination to migrate VM " + vm.getInstanceName());
        }
        return deployDestination.getHost();
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_MIGRATE, eventDescription = "migrating VM", async = true)
    public VirtualMachine migrateVirtualMachineWithVolume(Long vmId, Host destinationHost, Map<String, String> volumeToPool) throws ResourceUnavailableException,
    ConcurrentOperationException, ManagementServerException, VirtualMachineMigrationException {
        // Access check - only root administrator can migrate VM.
        Account caller = CallContext.current().getCallingAccount();
        if (!_accountMgr.isRootAdmin(caller.getId())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Caller is not a root admin, permission denied to migrate the VM");
            }
            throw new PermissionDeniedException("No permission to migrate VM, Only Root Admin can migrate a VM!");
        }

        VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find the VM by ID " + vmId);
        }

        // OfflineVmwareMigration: this would be it ;) if multiple paths exist: unify
        if (vm.getState() != State.Running) {
            // OfflineVmwareMigration: and not vmware
            if (logger.isDebugEnabled()) {
                logger.debug("VM is not Running, unable to migrate the vm " + vm);
            }
            CloudRuntimeException ex = new CloudRuntimeException(String.format("Unable to migrate the VM %s (ID: %s) as it is not in Running state", vm.getInstanceName(), vm.getUuid()));
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        if(serviceOfferingDetailsDao.findDetail(vm.getServiceOfferingId(), GPU.Keys.pciDevice.toString()) != null) {
            throw new InvalidParameterValueException("Live Migration of GPU enabled VM is not supported");
        }

        if (!vm.getHypervisorType().isFunctionalitySupported(Functionality.VmStorageMigration)) {
            throw new InvalidParameterValueException(
                    String.format("Unsupported hypervisor: %s for VM migration, we support [%s] only",
                            vm.getHypervisorType(),
                            HypervisorType.getListOfHypervisorsSupportingFunctionality(Functionality.VmStorageMigration)));
        }

        if (_vmSnapshotDao.findByVm(vmId).size() > 0) {
            throw new InvalidParameterValueException("VM with VM Snapshots cannot be migrated with storage, please remove all VM snapshots");
        }

        Pair<Host, Host> sourceDestinationHosts = getHostsForMigrateVmWithStorage(vm, destinationHost);
        Host srcHost = sourceDestinationHosts.first();

        final List<VolumeVO> volumes = _volsDao.findCreatedByInstance(vm.getId());
        if (isVmCanBeMigratedWithoutStorage(srcHost, destinationHost, volumes, volumeToPool)) {
            // If volumes do not have to be migrated
            // call migrateVirtualMachine for non-user VMs else throw exception
            if (!VirtualMachine.Type.User.equals(vm.getType())) {
                return migrateVirtualMachine(vmId, destinationHost);
            }
            throw new InvalidParameterValueException(String.format("Migration of the VM: %s (ID: %s) from host %s (ID: %s) to destination host  %s (ID: %s) doesn't involve migrating the volumes",
                    vm.getInstanceName(), vm.getUuid(), srcHost.getName(), srcHost.getUuid(), destinationHost.getName(), destinationHost.getUuid()));
        }

        Map<Long, Long> volToPoolObjectMap = getVolumePoolMappingForMigrateVmWithStorage(vm, volumeToPool);

        if (destinationHost == null) {
            destinationHost = chooseVmMigrationDestinationUsingVolumePoolMap(vm, srcHost, volToPoolObjectMap);
        }

        checkHostsDedication(vm, srcHost.getId(), destinationHost.getId());

        _itMgr.migrateWithStorage(vm.getUuid(), srcHost.getId(), destinationHost.getId(), volToPoolObjectMap);
        return findMigratedVm(vm.getId(), vm.getType());
    }

    protected void checkVolumesLimits(Account account, List<VolumeVO> volumes) throws ResourceAllocationException {
        Long totalVolumes = 0L;
        Long totalVolumesSize = 0L;
        Map<Long, List<String>> diskOfferingTagsMap = new HashMap<>();
        Map<String, Long> tagVolumeCountMap = new HashMap<>();
        Map<String, Long> tagSizeMap = new HashMap<>();
        for (VolumeVO volume : volumes) {
            if (!volume.isDisplay()) {
                continue;
            }
            totalVolumes++;
            totalVolumesSize += volume.getSize();
            if (!diskOfferingTagsMap.containsKey(volume.getDiskOfferingId())) {
                diskOfferingTagsMap.put(volume.getDiskOfferingId(), _resourceLimitMgr.getResourceLimitStorageTags(
                        _diskOfferingDao.findById(volume.getDiskOfferingId())));
            }
            List<String> tags = diskOfferingTagsMap.get(volume.getDiskOfferingId());
            for (String tag : tags) {
                if (tagVolumeCountMap.containsKey(tag)) {
                    tagVolumeCountMap.put(tag, tagVolumeCountMap.get(tag) + 1);
                    tagSizeMap.put(tag, tagSizeMap.get(tag) + volume.getSize());
                } else {
                    tagVolumeCountMap.put(tag, 1L);
                    tagSizeMap.put(tag, volume.getSize());
                }
            }
        }
        _resourceLimitMgr.checkResourceLimit(account, ResourceType.volume, totalVolumes);
        _resourceLimitMgr.checkResourceLimit(account, ResourceType.primary_storage, totalVolumesSize);
        for (String tag : tagVolumeCountMap.keySet()) {
            resourceLimitService.checkResourceLimitWithTag(account, ResourceType.volume, tag, tagVolumeCountMap.get(tag));
            resourceLimitService.checkResourceLimitWithTag(account, ResourceType.primary_storage, tag, tagSizeMap.get(tag));
        }
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_MOVE, eventDescription = "move VM to another user", async = false)
    public UserVm moveVmToUser(final AssignVMCmd cmd) throws ResourceAllocationException, ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        Account caller = CallContext.current().getCallingAccount();
        Long callerId = caller.getId();
        logger.trace("Verifying if caller [{}] is root or domain admin.", caller);
        if (!_accountMgr.isRootAdmin(callerId) && !_accountMgr.isDomainAdmin(callerId)) {
            throw new InvalidParameterValueException(String.format("Only root or domain admins are allowed to assign VMs. Caller [%s] is of type [%s].", caller, caller.getType()));
        }

        Long vmId = cmd.getVmId();
        final UserVmVO vm = _vmDao.findById(vmId);
        validateIfVmSupportsMigration(vm, vmId);

        Long domainId = cmd.getDomainId();
        Long projectId = cmd.getProjectId();
        Long oldAccountId = vm.getAccountId();
        String newAccountName = cmd.getAccountName();
        final Account oldAccount = _accountService.getActiveAccountById(oldAccountId);
        final Account newAccount = _accountMgr.finalizeOwner(caller, newAccountName, domainId, projectId);
        validateOldAndNewAccounts(oldAccount, newAccount, oldAccountId, newAccountName, domainId);

        checkCallerAccessToAccounts(caller, oldAccount, newAccount);

        logger.trace("Verifying if the provided domain ID [{}] is valid.", domainId);
        if (projectId != null && domainId == null) {
            throw new InvalidParameterValueException("Please provide a valid domain ID; cannot assign VM to a project if domain ID is NULL.");
        }

        validateIfVmHasNoRules(vm, vmId);

        final List<VolumeVO> volumes = _volsDao.findByInstance(vmId);
        validateIfVolumesHaveNoSnapshots(volumes);

        final ServiceOfferingVO offering = serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId());
        VirtualMachineTemplate template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());

        verifyResourceLimitsForAccountAndStorage(newAccount, vm, offering, volumes, template);

        validateIfNewOwnerHasAccessToTemplate(vm, newAccount, template);

        DomainVO domain = _domainDao.findById(domainId);
        logger.trace("Verifying if the new account [{}] has access to the specified domain [{}].", newAccount, domain);
        _accountMgr.checkAccess(newAccount, domain);

        Network newNetwork = ensureDestinationNetwork(cmd, vm, newAccount);
        try {
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    executeStepsToChangeOwnershipOfVm(cmd, caller, oldAccount, newAccount, vm, offering, volumes, template, domainId);
                }
            });
        } catch (Exception e) {
            if (newNetwork != null) {
                logger.debug("Cleaning up the created network.");
                networkService.deleteNetwork(newNetwork.getId(), false);
            }
            throw e;
        }

        logger.info("VM [{}] now belongs to account [{}].", vm.getInstanceName(), newAccountName);
        return vm;
    }

    protected void validateIfVmSupportsMigration(UserVmVO vm, Long vmId) {
        logger.trace("Validating if VM [{}] exists and is not in state [{}].", vmId, State.Running);

        if (vm == null) {
            throw new InvalidParameterValueException(String.format("There is no VM by ID [%s].", vmId));
        } else if (vm.getState() == State.Running) {
            throw new InvalidParameterValueException(String.format("Unable to move VM [%s] in [%s] state.", vm, vm.getState()));
        } else if (UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Migration is not supported for Shared FileSystem Instances.");
        }
    }

    /**
     * Validates if the provided VM does not have any existing Port Forwarding, Load Balancer, Static Nat, and One to One Nat rules.
     * If any rules exist, throws a {@link InvalidParameterValueException}.
     * @param vm the VM to be checked for the rules.
     * @param vmId the ID of the VM to be checked.
     * @throws InvalidParameterValueException
     */
    protected void validateIfVmHasNoRules(UserVmVO vm, Long vmId) throws InvalidParameterValueException {
        logger.trace("Validating if VM [{}] has no Port Forwarding, Static Nat, Load Balancing or One to One Nat rules.", vm);

        List<PortForwardingRuleVO> portForwardingRules = _portForwardingDao.listByVm(vmId);
        if (CollectionUtils.isNotEmpty(portForwardingRules)) {
            throw new InvalidParameterValueException(String.format("Remove any Port Forwarding rules for VM [%s] before assigning it to another user.", vm));
        }

        List<FirewallRuleVO> staticNatRules = _rulesDao.listStaticNatByVmId(vmId);
        if (CollectionUtils.isNotEmpty(staticNatRules)) {
            throw new InvalidParameterValueException(String.format("Remove the StaticNat rules for VM [%s] before assigning it to another user.", vm));
        }

        List<LoadBalancerVMMapVO> loadBalancerVmMaps = _loadBalancerVMMapDao.listByInstanceId(vmId);
        if (CollectionUtils.isNotEmpty(loadBalancerVmMaps)) {
            throw new InvalidParameterValueException(String.format("Remove the Load Balancing rules for VM [%s] before assigning it to another user.", vm));
        }

        List<IPAddressVO> ips = _ipAddressDao.findAllByAssociatedVmId(vmId);
        for (IPAddressVO ip : ips) {
            if (ip.isOneToOneNat()) {
                throw new InvalidParameterValueException(String.format("Remove the One to One Nat rule for VM [%s] for IP [%s].", vm, ip));
            }
        }
    }

    protected void validateIfVolumesHaveNoSnapshots(List<VolumeVO> volumes) throws InvalidParameterValueException {
        logger.trace("Verifying if there are any snapshots for any of the VM volumes.");
        for (VolumeVO volume : volumes) {
            logger.trace("Verifying snapshots for volume [{}].", volume);
            List<SnapshotVO> snapshots = _snapshotDao.listByStatusNotIn(volume.getId(), Snapshot.State.Destroyed, Snapshot.State.Error);
            if (CollectionUtils.isNotEmpty(snapshots)) {
                throw new InvalidParameterValueException(String.format("Snapshots exist for volume [%s]. Detach volume or remove snapshots for the volume before assigning VM to "
                        + "another user.", volume.getName()));
            }
        }
    }

    /**
     * Verifies if the CPU, RAM and volume size do not exceed the account and the primary storage limit.
     * If any limit is exceeded, throws a {@link ResourceAllocationException}.
     * @param account The account to check if CPU and RAM limit has been exceeded.
     * @param vm The VM which can exceed resource limits.
     * @param offering The service offering which can exceed resource limits.
     * @param volumes The volumes whose total size can exceed resource limits.
     * @throws ResourceAllocationException
     */
    protected void verifyResourceLimitsForAccountAndStorage(Account account, UserVmVO vm, ServiceOfferingVO offering, List<VolumeVO> volumes, VirtualMachineTemplate template)
            throws ResourceAllocationException {

        logger.trace("Verifying if CPU and RAM for VM [{}] do not exceed account [{}] limit.", vm, account);

        if (!countOnlyRunningVmsInResourceLimitation()) {
            resourceLimitService.checkVmResourceLimit(account, vm.isDisplayVm(), offering, template);
        }

        logger.trace("Verifying if volume size for VM [{}] does not exceed account [{}] limit.", vm, account);

        checkVolumesLimits(account, volumes);
    }

    protected boolean countOnlyRunningVmsInResourceLimitation() {
        return VirtualMachineManager.ResourceCountRunningVMsonly.value();
    }

    protected void validateIfNewOwnerHasAccessToTemplate(UserVmVO vm, Account newAccount, VirtualMachineTemplate template) {
        logger.trace("Validating if new owner [{}] has access to the template specified for VM [{}].", newAccount, vm);

        if (template == null) {
            throw new InvalidParameterValueException(String.format("Template for VM [%s] cannot be found.", vm.getUuid()));
        }

        logger.debug("Verifying if new owner [{}] has access to the template [{}].", newAccount, template.getUuid());
        try {
            _accountMgr.checkAccess(newAccount, AccessType.UseEntry, true, template);
        } catch (PermissionDeniedException e) {
            String newMsg = String.format("New owner [%s] does not have access to the template specified for VM [%s].", newAccount, vm);
            throw new PermissionDeniedException(newMsg, e);
        }
    }

    /**
     * This method will create an isolated network for the new account to allocate the virtual machine if:
     * <ul>
     * <li>no networks were specified to the command, AND</li>
     * <li>the zone uses advanced networks without security groups, AND</li>
     * <li>the VM does not belong to any shared or L2 network that the new owner can access, AND</li>
     * <li>the new owner does not have any isolated networks</li>
     * </ul>
     * @return the created isolated network, or null if it was not created.
     */
    protected Network ensureDestinationNetwork(AssignVMCmd cmd, UserVmVO vm, Account newAccount) throws InsufficientCapacityException, ResourceAllocationException {
        DataCenterVO zone = _dcDao.findById(vm.getDataCenterId());
        if (zone.getNetworkType() == NetworkType.Basic) {
            logger.debug("No need to ensure an isolated network for the VM because the zone uses basic networks.");
            return null;
        }

        List<Long> networkIdList = cmd.getNetworkIds();
        List<Long> securityGroupIdList = cmd.getSecurityGroupIdList();
        if (_networkModel.checkSecurityGroupSupportForNetwork(newAccount, zone, networkIdList, securityGroupIdList)) {
            logger.debug("No need to ensure an isolated network for the VM because security groups is enabled for this zone.");
            return null;
        }
        if (CollectionUtils.isNotEmpty(securityGroupIdList)) {
            throw new InvalidParameterValueException("Cannot move VM with security groups; security group feature is not enabled in this zone.");
        }

        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<>();
        addNetworksToNetworkIdList(vm, newAccount, networkIdList, applicableNetworks, new HashMap<>(), new HashMap<>());
        if (!applicableNetworks.isEmpty()) {
            logger.debug("No need to create an isolated network for the VM because there are other applicable networks.");
            return null;
        }

        List<? extends Network> virtualNetworks = _networkModel.listNetworksForAccount(newAccount.getId(), zone.getId(), Network.GuestType.Isolated);
        if (!virtualNetworks.isEmpty()) {
            logger.debug("No need create a new isolated network for the VM because the owner already has existing isolated networks.");
            return null;
        }

        return createApplicableNetworkToCreateVm(newAccount, zone);
    }

    /**
     * @return a network offering with required availability that will be used to create a new isolated network for the VM
     * assignment process.
     */
    protected NetworkOfferingVO getOfferingWithRequiredAvailabilityForNetworkCreation() {
        List<NetworkOfferingVO> requiredOfferings = _networkOfferingDao.listByAvailability(Availability.Required, false);
        if (CollectionUtils.isEmpty(requiredOfferings)) {
            throw new InvalidParameterValueException(String.format("Unable to find network offering with availability [%s] to automatically create the network as a part of VM "
                    + "creation.", Availability.Required));
        }
        NetworkOfferingVO firstRequiredOffering = requiredOfferings.get(0);
        if (firstRequiredOffering.getState() != NetworkOffering.State.Enabled) {
            throw new InvalidParameterValueException(String.format("Required network offering ID [%s] is not in [%s] state.", firstRequiredOffering.getId(),
                    NetworkOffering.State.Enabled));
        }
        return firstRequiredOffering;
    }

    /**
     * Executes all ownership steps necessary to assign a VM to another user:
     * generating a destroy VM event ({@link EventTypes}),
     * decrementing the old user resource count ({@link #resourceCountDecrement(long, Boolean, ServiceOffering, VirtualMachineTemplate)}),
     * removing the VM from its instance group ({@link #removeInstanceFromInstanceGroup(long)}),
     * updating the VM owner to the new account ({@link #updateVmOwner(Account, UserVmVO, Long, Long)}),
     * updating the volumes to the new account ({@link #updateVolumesOwner(List, Account, Account, Long)}),
     * updating the network for the VM ({@link #updateVmNetwork(AssignVMCmd, Account, UserVmVO, Account, VirtualMachineTemplate)}),
     * incrementing the new user resource count ({@link #resourceCountIncrement(long, Boolean, ServiceOffering, VirtualMachineTemplate)}),
     * and generating a create VM event ({@link EventTypes}).
     * @param cmd The assignVMCmd.
     * @param caller The account calling the assignVMCmd.
     * @param oldAccount The old account from whom the VM will be moved.
     * @param newAccount The new account to whom the VM will move.
     * @param vm The VM to be moved between accounts.
     * @param offering The service offering which will be used to decrement and increment resource counts.
     * @param volumes The volumes of the VM which will be assigned to another user.
     * @param template The template of the VM which will be assigned to another user.
     * @param domainId The ID of the domain where the VM which will be assigned to another user is.
     */
    protected void executeStepsToChangeOwnershipOfVm(AssignVMCmd cmd, Account caller, Account oldAccount, Account newAccount, UserVmVO vm, ServiceOfferingVO offering,
                                                     List<VolumeVO> volumes, VirtualMachineTemplate template, Long domainId) {

        logger.trace("Generating destroy event for VM [{}].", vm);
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_DESTROY, vm.getAccountId(), vm.getDataCenterId(), vm.getId(), vm.getHostName(), vm.getServiceOfferingId(),
                vm.getTemplateId(), vm.getHypervisorType().toString(), VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplayVm());

        logger.trace("Decrementing old account [{}] resource count.", oldAccount);
        resourceCountDecrement(oldAccount.getAccountId(), vm.isDisplayVm(), offering, template);

        logger.trace("Removing VM [{}] from its instance group.", vm);
        removeInstanceFromInstanceGroup(vm.getId());

        Long newAccountId = newAccount.getAccountId();
        updateVmOwner(newAccount, vm, domainId, newAccountId);

        updateVolumesOwner(volumes, oldAccount, newAccount, newAccountId);

        try {
            updateVmNetwork(cmd, caller, vm, newAccount, template);
        } catch (InsufficientCapacityException | ResourceAllocationException e) {
            throw new CloudRuntimeException(String.format("Unable to update networks when assigning VM [%s] due to [%s].", vm, e.getMessage()), e);
        }

        logger.trace(String.format("Incrementing new account [%s] resource count.", newAccount));
        if (!isResourceCountRunningVmsOnlyEnabled()) {
            resourceCountIncrement(newAccountId, vm.isDisplayVm(), offering, template);
        }

        logger.trace(String.format("Generating create event for VM [%s].", vm));
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_CREATE, vm.getAccountId(), vm.getDataCenterId(), vm.getId(), vm.getHostName(), vm.getServiceOfferingId(),
                vm.getTemplateId(), vm.getHypervisorType().toString(), VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplayVm());
    }

    protected void updateVmOwner(Account newAccount, UserVmVO vm, Long domainId, Long newAccountId) {
        logger.debug("Updating VM [{}] owner to [{}].", vm, newAccount);

        vm.setAccountId(newAccountId);
        vm.setDomainId(domainId);

        _vmDao.persist(vm);
    }

    protected void updateVolumesOwner(final List<VolumeVO> volumes, Account oldAccount, Account newAccount, Long newAccountId) {
        logger.debug("Updating volumes owner from old account [{}] to new account [{}].", oldAccount, newAccount);

        for (VolumeVO volume : volumes) {
            logger.trace("Generating a delete volume event for volume [{}].", volume);
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                    Volume.class.getName(), volume.getUuid(), volume.isDisplayVolume());

            logger.trace("Decrementing volume [{}] and primary storage resource count for the old account [{}].", volume, oldAccount);
            DiskOfferingVO diskOfferingVO = _diskOfferingDao.findById(volume.getDiskOfferingId());
            _resourceLimitMgr.decrementVolumeResourceCount(oldAccount.getAccountId(), volume.isDisplay(), volume.getSize(), diskOfferingVO);

            logger.trace("Setting the new account [{}] and domain [{}] for volume [{}].", newAccount, newAccount.getDomainId(), volume);
            volume.setAccountId(newAccountId);
            volume.setDomainId(newAccount.getDomainId());

            _volsDao.persist(volume);

            logger.trace("Incrementing volume [{}] and primary storage resource count for the new account [{}].", volume, newAccount);
            _resourceLimitMgr.incrementVolumeResourceCount(newAccount.getAccountId(), volume.isDisplay(), volume.getSize(), diskOfferingVO);

            logger.trace("Generating a create volume event for volume [{}].", volume);
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_CREATE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                    volume.getDiskOfferingId(), volume.getTemplateId(), volume.getSize(), Volume.class.getName(), volume.getUuid(), volume.isDisplayVolume());
        }
    }

    /**
     * Updates the network for a VM being assigned to a new account.
     * If the network type for the zone is basic, calls
     * {@link #updateBasicTypeNetworkForVm(UserVmVO, Account, VirtualMachineTemplate, VirtualMachineProfileImpl, DataCenterVO, List, List)}.
     * If the network type for the zone is advanced, calls
     * {@link #updateAdvancedTypeNetworkForVm(Account, UserVmVO, Account, VirtualMachineTemplate, VirtualMachineProfileImpl, DataCenterVO, List, List)}.
     * @param cmd The assignVMCmd.
     * @param caller The account calling the assignVMCmd.
     * @param vm The VM to be assigned to another user, which has to have networks updated.
     * @param newAccount The account to whom the VM will be assigned to.
     * @param template The template of the VM which will be assigned to another account.
     * @throws InsufficientCapacityException
     * @throws ResourceAllocationException
     */
    protected void updateVmNetwork(AssignVMCmd cmd, Account caller, UserVmVO vm, Account newAccount, VirtualMachineTemplate template)
            throws InsufficientCapacityException, ResourceAllocationException {

        logger.trace("Updating network for VM [{}].", vm);

        VirtualMachine vmoi = _itMgr.findById(vm.getId());
        VirtualMachineProfileImpl vmOldProfile = new VirtualMachineProfileImpl(vmoi);

        DataCenterVO zone = _dcDao.findById(vm.getDataCenterId());

        List<Long> networkIdList = cmd.getNetworkIds();
        List<Long> securityGroupIdList = cmd.getSecurityGroupIdList();

        if (zone.getNetworkType() == NetworkType.Basic) {
            updateBasicTypeNetworkForVm(vm, newAccount, template, vmOldProfile, zone, networkIdList, securityGroupIdList);
            return;
        }

        updateAdvancedTypeNetworkForVm(caller, vm, newAccount, template, vmOldProfile, zone, networkIdList, securityGroupIdList);
    }

    /**
     * Validates if the old account exists, the new account exists and is not disabled, and they are different from each other.
     * If any of the validations fail, throws a {@link InvalidParameterValueException}.
     * @param oldAccount The old account which will be checked if exists, and if it is different from the new account.
     * @param newAccount The new account which will be checked if exists, if it is different from the old account, and if it is not disabled.
     * @param oldAccountId The ID of the old account to be checked.
     * @param newAccountName The name of the new account to be checked.
     * @param domainId The domain where to validate the conditions.
     * @throws InvalidParameterValueException
     */
    protected void validateOldAndNewAccounts(Account oldAccount, Account newAccount, Long oldAccountId, String newAccountName, Long domainId)
            throws InvalidParameterValueException {

        logger.trace("Validating old [{}] and new accounts [{}].", oldAccount, newAccount);

        if (oldAccount == null) {
            throw new InvalidParameterValueException(String.format("Invalid old account [%s] for VM in domain [%s].", oldAccountId, domainId));
        }

        if (newAccount == null) {
            throw new InvalidParameterValueException(String.format("Invalid new account [%s] for VM in domain [%s].", newAccountName, domainId));
        }

        if (newAccount.getState() == Account.State.DISABLED) {
            throw new InvalidParameterValueException(String.format("The new account owner [%s] is disabled.", newAccount));
        }

        if (oldAccount.getAccountId() == newAccount.getAccountId()) {
            throw new InvalidParameterValueException(String.format("The new account [%s] is the same as the old account.", newAccount));
        }
    }

    protected void checkCallerAccessToAccounts(Account caller, Account oldAccount, Account newAccount) {
        logger.trace("Verifying if caller [{}] has access to old account [{}].", caller, oldAccount);
        _accountMgr.checkAccess(caller, null, true, oldAccount);

        logger.trace("Verifying if caller [{}] has access to new account [{}].", caller, newAccount);
        _accountMgr.checkAccess(caller, null, true, newAccount);
    }

    protected Boolean isResourceCountRunningVmsOnlyEnabled() {
        return VirtualMachineManager.ResourceCountRunningVMsonly.value();
    }

    /**
     * Updates a basic type network by:
     * cleaning up the old network ({@link #cleanupOfOldOwnerNicsForNetwork(VirtualMachineProfileImpl)}),
     * allocating all networks ({@link #allocateNetworksForVm(UserVmVO, LinkedHashMap)}),
     * and adding security groups to the VM ({@link #addSecurityGroupsToVm(Account, UserVmVO, VirtualMachineTemplate, List, Network)}).
     * If the network has network IDs, throws a {@link InvalidParameterValueException}.
     * @param vm The VM for which the networks are allocated.
     * @param newAccount The new account to which the VM will be assigned to.
     * @param template The template of the VM.
     * @param vmOldProfile The VM profile.
     * @param zone The zone where the network has to be allocated.
     * @param networkIdList The list of network IDs provided to the assignVMCmd.
     * @param securityGroupIdList The list of security groups provided to the assignVMCmd.
     * @throws InsufficientCapacityException
     */
    protected void updateBasicTypeNetworkForVm(UserVmVO vm, Account newAccount, VirtualMachineTemplate template, VirtualMachineProfileImpl vmOldProfile,
                                               DataCenterVO zone, List<Long> networkIdList, List<Long> securityGroupIdList) throws InsufficientCapacityException {

        if (networkIdList != null && !networkIdList.isEmpty()) {
            throw new InvalidParameterValueException("Cannot move VM with Network IDs; this is a basic zone VM.");
        }

        logger.trace("Cleanup of old security groups for VM [{}]. They will be recreated for the new account once the VM is started.", vm);
        _securityGroupMgr.removeInstanceFromGroups(vm);

        cleanupOfOldOwnerNicsForNetwork(vmOldProfile);

        List<NetworkVO> networkList = new ArrayList<>();
        Network defaultNetwork = _networkModel.getExclusiveGuestNetwork(zone.getId());
        addDefaultNetworkToNetworkList(networkList, defaultNetwork);

        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<>();
        NicProfile profile = new NicProfile();
        profile.setDefaultNic(true);
        networks.put(networkList.get(0), new ArrayList<>(Arrays.asList(profile)));

        allocateNetworksForVm(vm, networks);

        addSecurityGroupsToVm(newAccount, vm, template, securityGroupIdList, defaultNetwork);
    }

    /**
     * Updates an advanced type network by:
     * adding NICs to the networks ({@link #addNicsToApplicableNetworksAndReturnDefaultNetwork(LinkedHashSet, Map, Map, LinkedHashMap)}),
     * allocating - if security groups are enabled ({@link #allocateNetworksForVm(UserVmVO, LinkedHashMap)}) -
     * or selecting applicable networks otherwise ({@link #selectApplicableNetworkToCreateVm(Account, DataCenterVO, Set)}),
     * and adding security groups to the VM ({@link #addSecurityGroupsToVm(Account, UserVmVO, VirtualMachineTemplate, List, Network)}) - if enabled in the zone.
     * If no applicable network is provided and the zone has security groups enabled, throws a {@link InvalidParameterValueException}.
     * If security groups are not enabled, but security groups have been provided, throws a {@link InvalidParameterValueException}.
     * @param caller The caller of the assignVMCmd.
     * @param vm The VM for which the networks are allocated or selected.
     * @param newAccount The new account to which the VM will be assigned to.
     * @param template The template of the VM.
     * @param vmOldProfile The VM profile.
     * @param zone The zone where the network has to be allocated or selected.
     * @param networkIdList The list of network IDs provided to the assignVMCmd.
     * @param securityGroupIdList The list of security groups provided to the assignVMCmd.
     * @throws InsufficientCapacityException
     * @throws ResourceAllocationException
     * @throws InvalidParameterValueException
     */
    protected void updateAdvancedTypeNetworkForVm(Account caller, UserVmVO vm, Account newAccount, VirtualMachineTemplate template,
                                                  VirtualMachineProfileImpl vmOldProfile, DataCenterVO zone, List<Long> networkIdList, List<Long> securityGroupIdList)
            throws InsufficientCapacityException, ResourceAllocationException, InvalidParameterValueException {

        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<>();
        Map<Long, String> requestedIPv4ForNics = new HashMap<>();
        Map<Long, String> requestedIPv6ForNics = new HashMap<>();
        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<>();

        if (_networkModel.checkSecurityGroupSupportForNetwork(newAccount, zone, networkIdList, securityGroupIdList))  {
            logger.debug("Cleanup of old security groups for VM [{}]. They will be recreated for the new account once the VM is started.", vm);
            _securityGroupMgr.removeInstanceFromGroups(vm);

            addNetworksToNetworkIdList(vm, newAccount, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
            cleanupOfOldOwnerNicsForNetwork(vmOldProfile);

            NetworkVO defaultNetwork = addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

            if (applicableNetworks.isEmpty()) {
                throw new InvalidParameterValueException("No network is specified, please specify one when you move the VM. For now, please add a network to VM on NICs tab.");
            } else {
                allocateNetworksForVm(vm, networks);
            }

            addSecurityGroupsToVm(newAccount, vm, template, securityGroupIdList, defaultNetwork);
            return;
        }

        if (securityGroupIdList != null && !securityGroupIdList.isEmpty()) {
            throw new InvalidParameterValueException("Cannot move VM with security groups; security group feature is not enabled in this zone.");
        }

        addNetworksToNetworkIdList(vm, newAccount, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
        cleanupOfOldOwnerNicsForNetwork(vmOldProfile);

        if (applicableNetworks.isEmpty()) {
            selectApplicableNetworkToCreateVm(newAccount, zone, applicableNetworks);
        }

        addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

        allocateNetworksForVm(vm, networks);
        logger.debug("Adding [{}] networks to VM [{}].", networks.size(), vm.getInstanceName());
    }

    protected void cleanupOfOldOwnerNicsForNetwork(VirtualMachineProfileImpl vmOldProfile) {
        logger.trace("Cleanup of old owner network for VM [{}].", vmOldProfile);

        _networkMgr.cleanupNics(vmOldProfile);
        _networkMgr.removeNics(vmOldProfile);
    }

    protected void addDefaultNetworkToNetworkList(List<NetworkVO> networkList, Network defaultNetwork) {
        logger.trace("Adding default network to network list.");

        if (defaultNetwork == null) {
            throw new InvalidParameterValueException("Unable to find a default network to start a VM.");
        }

        networkList.add(_networkDao.findById(defaultNetwork.getId()));
    }

    protected void allocateNetworksForVm(UserVmVO vm, LinkedHashMap<Network, List<? extends NicProfile>> networks) throws InsufficientCapacityException {
        logger.trace("Allocating networks for VM [{}].", vm);

        VirtualMachine vmi = _itMgr.findById(vm.getId());
        VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmi);
        _networkMgr.allocate(vmProfile, networks, null);
    }

    protected void addSecurityGroupsToVm(Account newAccount, UserVmVO vm, VirtualMachineTemplate template, List<Long> securityGroupIdList, Network defaultNetwork) {
        int securityIdList = securityGroupIdList != null ? securityGroupIdList.size() : 0;
        logger.debug("Adding security groups no " + securityIdList + " to " + vm.getInstanceName());

        boolean isVmWare = (template.getHypervisorType() == HypervisorType.VMware);
        if (securityGroupIdList != null && isVmWare) {
            throw new InvalidParameterValueException("Security group feature is not supported for VMWare hypervisor.");
        } else if (!isVmWare && (defaultNetwork == null || _networkModel.isSecurityGroupSupportedInNetwork(defaultNetwork)) && _networkModel.canAddDefaultSecurityGroup()) {
            if (securityGroupIdList == null) {
                securityGroupIdList = new ArrayList<>();
            }

            addDefaultSecurityGroupToSecurityGroupIdList(newAccount, securityGroupIdList);
        }

        _securityGroupMgr.addInstanceToGroups(vm, securityGroupIdList);
    }

    /**
     * Adds all networks to the list of network IDs by:
     * attempting to keep the shared network for the VM ({@link #keepOldSharedNetworkForVm(UserVmVO, Account, List, Set, Map, Map)}),
     * adding any additional applicable networks to the VM ({@link #addAdditionalNetworksToVm(UserVmVO, Account, List, Set, Map, Map)}),
     * @param vm The VM to add the networks to.
     * @param newAccount The account to access the networks.
     * @param networkIdList The network IDs which have to be added to the VM.
     * @param applicableNetworks The applicable networks which have to be added to the VM.
     * @param requestedIPv4ForNics All requested IPv4 for NICs.
     * @param requestedIPv6ForNics All requested IPv6 for NICs.
     */
    protected void addNetworksToNetworkIdList(UserVmVO vm, Account newAccount, List<Long> networkIdList, Set<NetworkVO> applicableNetworks,
                                              Map<Long, String> requestedIPv4ForNics, Map<Long, String> requestedIPv6ForNics) {

        logger.trace("Adding networks to network list.");

        keepOldSharedNetworkForVm(vm, newAccount, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        addAdditionalNetworksToVm(vm, newAccount, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
    }

    /**
     * Adds NICs to the applicable networks. The first applicable network is considered the default network, and is associated to the default NIC.
     * @param applicableNetworks The applicable networks which will be associated with NICs.
     * @param requestedIPv4ForNics All requested IPv4 for NICs.
     * @param requestedIPv6ForNics All requested IPv6 for NICs.
     * @param networks The networks to which the networks and NICs have to be added.
     * @return The default network, if it exists. Otherwise, returns null.
     */
    @Nullable
    protected NetworkVO addNicsToApplicableNetworksAndReturnDefaultNetwork(LinkedHashSet<NetworkVO> applicableNetworks, Map<Long, String> requestedIPv4ForNics,
                                                                           Map<Long, String> requestedIPv6ForNics, LinkedHashMap<Network, List<? extends NicProfile>> networks) {

        logger.trace("Adding NICs to applicable networks.");

        NetworkVO defaultNetwork = null;

        if (!applicableNetworks.isEmpty()) {
            NicProfile defaultNic = new NicProfile();
            defaultNic.setDefaultNic(true);
            defaultNetwork = applicableNetworks.iterator().next();

            for (NetworkVO appNet : applicableNetworks) {
                defaultNic.setRequestedIPv4(requestedIPv4ForNics.get(appNet.getId()));
                defaultNic.setRequestedIPv6(requestedIPv6ForNics.get(appNet.getId()));
                networks.put(appNet, new ArrayList<>(Arrays.asList(defaultNic)));

                defaultNic = new NicProfile();
            }
        }
        return defaultNetwork;
    }

    /**
     * Selects the default network as the applicable network to be used to create the VM. If none exists, creates a new one.
     * If no network offerings are applicable, throws a {@link InvalidParameterValueException}.
     * If the network offering applicable is not enabled, throws a {@link InvalidParameterValueException}.
     * If more than one default isolated network is related to the account, throws a {@link InvalidParameterValueException}, since the ID of the network to be used has to be
     * specified.
     * @param newAccount The new account associated to the selected network.
     * @param zone The zone where the network is selected.
     * @param applicableNetworks The applicable networks to which the selected network has to be added to.
     * @throws InsufficientCapacityException
     * @throws ResourceAllocationException
     */
    protected void selectApplicableNetworkToCreateVm(Account newAccount, DataCenterVO zone, Set<NetworkVO> applicableNetworks)
            throws InsufficientCapacityException, ResourceAllocationException {

        logger.trace("Selecting the applicable network to create the VM.");

        NetworkVO defaultNetwork;
        List<? extends Network> virtualNetworks = _networkModel.listNetworksForAccount(newAccount.getId(), zone.getId(), Network.GuestType.Isolated);
        if (virtualNetworks.isEmpty()) {
            throw new CloudRuntimeException(String.format("Could not find an applicable network to create virtual machine for account [%s].", newAccount));
        } else if (virtualNetworks.size() > 1) {
            throw new InvalidParameterValueException(String.format("More than one default isolated network has been found for account [%s]; please specify networkIDs.",
                    newAccount));
        } else {
            defaultNetwork = _networkDao.findById(virtualNetworks.get(0).getId());
        }

        applicableNetworks.add(defaultNetwork);
    }

    /**
     * Adds the default security group to a security group ID list. If the default security group does not exist, creates a new one.
     * @param newAccount The account to be checked for the security groups.
     * @param securityGroupIdList The list of security group IDs.
     */
    protected void addDefaultSecurityGroupToSecurityGroupIdList(Account newAccount, List<Long> securityGroupIdList) {
        logger.debug("Adding default security group to security group list if not already in it.");

        Long newAccountId = newAccount.getId();
        SecurityGroup defaultGroup = _securityGroupMgr.getDefaultSecurityGroup(newAccountId);
        boolean defaultGroupPresent = false;

        if (defaultGroup != null) {
            if (securityGroupIdList.contains(defaultGroup.getId())) {
                defaultGroupPresent = true;
            }
        } else {
            logger.debug("Could not find a default security group for account [{}]. Creating a new one.", newAccount);
            defaultGroup = _securityGroupMgr.createSecurityGroup(SecurityGroupManager.DEFAULT_GROUP_NAME, SecurityGroupManager.DEFAULT_GROUP_DESCRIPTION, newAccount.getDomainId(),
                    newAccountId, newAccount.getAccountName());
        }

        if (!defaultGroupPresent) {
            securityGroupIdList.add(defaultGroup.getId());
        }
    }

    /**
     * Attempts to keep the old shared network for the VM to be assigned to a new account by checking if:
     * any old shared network exists,
     * and the new account can use the old shared network.
     * @param vm The VM to be associated to the network.
     * @param newAccount The account which has to be able to access the old shared network.
     * @param networkIdList The IDs of the networks to be checked for.
     * @param applicableNetworks The applicable networks, which will contain the old shared network if applicable.
     * @param requestedIPv4ForNics All requested IPv4 for NICs.
     * @param requestedIPv6ForNics All requested IPv6 for NICs.
     */
    protected void keepOldSharedNetworkForVm(UserVmVO vm, Account newAccount, List<Long> networkIdList, Set<NetworkVO> applicableNetworks, Map<Long, String> requestedIPv4ForNics,
                                             Map<Long, String> requestedIPv6ForNics) {
        logger.trace("Attempting to keep old shared network for VM [{}].", vm);

        if (CollectionUtils.isNotEmpty(networkIdList)) {
            return;
        }

        NicVO defaultNicOld = _nicDao.findDefaultNicForVM(vm.getId());
        if (defaultNicOld == null) {
            return;
        }

        NetworkVO defaultNetworkOld = _networkDao.findById(defaultNicOld.getNetworkId());
        if (canAccountUseNetwork(newAccount, defaultNetworkOld)) {
            applicableNetworks.add(defaultNetworkOld);

            Long defaultNetworkOldId = defaultNetworkOld.getId();
            requestedIPv4ForNics.put(defaultNetworkOldId, defaultNicOld.getIPv4Address());
            requestedIPv6ForNics.put(defaultNetworkOldId, defaultNicOld.getIPv6Address());

            logger.debug("Using old shared network [{}] with old IP [{}] on default NIC of VM [{}].", defaultNicOld.getIPv4Address(), defaultNetworkOld, vm);
        }
    }

    /**
     * Adds any additional networks used by the VM assigned to another user.
     * If one of the networks does not exist, throws a {@link InvalidParameterValueException}.
     * If any of the network offerings is system only, throws a {@link InvalidParameterValueException}.
     * @param vm The VM to which the networks are associated to.
     * @param newAccount The new account which will access the VM.
     * @param networkIdList The list of network IDs to be checked if they can be added to the VM.
     * @param applicableNetworks The list of applicable networks to be added to the VM.
     * @param requestedIPv4ForNics All requested IPv4 for NICs.
     * @param requestedIPv6ForNics All requested IPv6 for NICs.
     */
    protected void addAdditionalNetworksToVm(UserVmVO vm, Account newAccount, List<Long> networkIdList, Set<NetworkVO> applicableNetworks, Map<Long, String> requestedIPv4ForNics,
                                             Map<Long, String> requestedIPv6ForNics) {
        logger.trace("Adding additional networks to VM [{}].", vm);

        if (CollectionUtils.isEmpty(networkIdList)) {
            return;
        }

        for (Long networkId : networkIdList) {
            NetworkVO network = _networkDao.findById(networkId);
            if (network == null) {
                InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find specified Network ID.");
                ex.addProxyObject(networkId.toString(), "networkId");
                throw ex;
            }

            _networkModel.checkNetworkPermissions(newAccount, network);

            NetworkOffering networkOffering = _entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
            if (networkOffering.isSystemOnly()) {
                throw new InvalidParameterValueException(String.format("Specified network [%s] is system only and cannot be used for VM deployment.", network));
            }

            if (network.getGuestType() == Network.GuestType.Shared && network.getAclType() == ACLType.Domain) {
                NicVO nicOld = _nicDao.findByNtwkIdAndInstanceId(networkId, vm.getId());
                if (nicOld != null) {
                    requestedIPv4ForNics.put(networkId, nicOld.getIPv4Address());
                    requestedIPv6ForNics.put(networkId, nicOld.getIPv6Address());
                    logger.debug("Using old shared network [{}] with old IP [{}] on NIC of VM [{}].", network, nicOld.getIPv4Address(), vm);
                }
            }
            logger.debug("Added network [{}] to VM [{}].", network.getName(), vm.getId());
            applicableNetworks.add(network);
        }
    }

    /**
     * Attempts to create a network suitable for the creation of a VM ({@link NetworkOrchestrationService#createGuestNetwork}).
     * If no physical network is found, throws a {@link InvalidParameterValueException}.
     * @param caller The account which calls for the network creation.
     * @param newAccount The account to which the network will be created.
     * @param zone The zone where the network will be created.
     * @param requiredOffering The network offering required to create the network.
     * @return The NetworkVO for the network created.
     * @throws InsufficientCapacityException
     * @throws ResourceAllocationException
     */
    protected NetworkVO createApplicableNetworkToCreateVm(Account newAccount, DataCenterVO zone)
            throws InsufficientCapacityException, ResourceAllocationException {

        logger.trace("Creating an applicable network to create the VM.");

        NetworkVO defaultNetwork;
        Long zoneId = zone.getId();
        Account caller = CallContext.current().getCallingAccount();
        NetworkOfferingVO requiredOffering = getOfferingWithRequiredAvailabilityForNetworkCreation();
        String requiredOfferingTags = requiredOffering.getTags();

        long physicalNetworkId = _networkModel.findPhysicalNetworkId(zoneId, requiredOfferingTags, requiredOffering.getTrafficType());

        PhysicalNetwork physicalNetwork = _physicalNetworkDao.findById(physicalNetworkId);
        if (physicalNetwork == null) {
            throw new InvalidParameterValueException(String.format("Unable to find physical network with ID [%s] and tag [%s].", physicalNetworkId, requiredOfferingTags));
        }

        long requiredOfferingId = requiredOffering.getId();
        logger.debug("Creating network for account [{}] from the network offering [{}] as a part of VM deployment process.", newAccount, requiredOfferingId);

        String networkName = String.format("%s-network", newAccount.getAccountName());
        Network newNetwork = _networkMgr.createGuestNetwork(requiredOfferingId, networkName, networkName, null, null, null,
                false, null, newAccount, null, physicalNetwork, zoneId, ACLType.Account, null, null, null, null, true, null,
                null, null, null, null, null, null, null, null, null, null);

        if (requiredOffering.isPersistent()) {
            newNetwork = implementNetwork(caller, zone, newNetwork);
        }

        defaultNetwork = _networkDao.findById(newNetwork.getId());
        return defaultNetwork;
    }

    protected Network implementNetwork(Account caller, DataCenterVO zone, Network newNetwork) {
        logger.trace("Implementing network [{}].", newNetwork);

        DeployDestination dest = new DeployDestination(zone, null, null, null);

        Journal journal = new Journal.LogJournal("Implementing " + newNetwork, logger);

        UserVO callerUser = _userDao.findById(CallContext.current().getCallingUserId());
        ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), journal, callerUser, caller);

        logger.debug("Implementing the network for account [{}] as a part of network provision for persistent networks.", newNetwork);

        try {
            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = _networkMgr.implementNetwork(newNetwork.getId(), dest, context);

            if (implementedNetwork == null || implementedNetwork.first() == null || implementedNetwork.second() == null) {
                logger.warn("Failed to implement network [{}].", newNetwork);
            } else {
                newNetwork = implementedNetwork.second();
            }
        } catch (Exception ex) {
            logger.warn("Failed to implement network [{}] elements and resources as a part of network provision for persistent network due to [{}].", newNetwork, ex.getMessage(), ex);
            throw new CloudRuntimeException(String.format("Failed to implement network [%s] elements and resources as a part of network provision.", newNetwork));
        }

        return newNetwork;
    }

    protected boolean canAccountUseNetwork(Account newAccount, Network network) {
        if (network != null && network.getAclType() == ACLType.Domain && (network.getGuestType() == Network.GuestType.Shared || network.getGuestType() == Network.GuestType.L2)) {
            try {
                _networkModel.checkNetworkPermissions(newAccount, network);
                return true;
            } catch (PermissionDeniedException e) {
                logger.debug("[{}] network [{}] cannot be used by new account [{}].", network.getGuestType(), network, newAccount);
                return false;
            }
        }
        return false;
    }

    private DiskOfferingVO validateAndGetDiskOffering(Long diskOfferingId, UserVmVO vm, Account caller) {
        DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
        if (diskOffering == null) {
            throw new InvalidParameterValueException("Cannot find disk offering with ID " + diskOfferingId);
        }
        DataCenterVO zone = dataCenterDao.findById(vm.getDataCenterId());
        _accountMgr.checkAccess(caller, diskOffering, zone);
        ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getServiceOfferingId());
        if (serviceOffering.getDiskOfferingStrictness() && !serviceOffering.getDiskOfferingId().equals(diskOfferingId)) {
            throw new InvalidParameterValueException("VM's service offering has a strict disk offering requirement, and the specified disk offering does not match");
        }
        return diskOffering;
    }

    @Override
    public UserVm restoreVM(RestoreVMCmd cmd) throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException {
        // Input validation
        Account caller = CallContext.current().getCallingAccount();

        long vmId = cmd.getVmId();
        Long newTemplateId = cmd.getTemplateId();
        Long rootDiskOfferingId = cmd.getRootDiskOfferingId();
        boolean expunge = cmd.getExpungeRootDisk();
        Map<String, String> details = cmd.getDetails();

        verifyDetails(details);

        UserVmVO vm = _vmDao.findById(vmId);
        if (vm == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Cannot find VM with ID " + vmId);
            ex.addProxyObject(String.valueOf(vmId), "vmId");
            throw ex;
        }
        if (UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }
        if (Hypervisor.HypervisorType.External.equals(vm.getHypervisorType())) {
            logger.error("Restore VM not supported for {} as it is {} hypervisor instance",
                    vm, Hypervisor.HypervisorType.External.name());
            throw new InvalidParameterValueException(String.format("Operation not supported for instance: %s",
                    vm.getName()));
        }
        _accountMgr.checkAccess(caller, null, true, vm);

        VMTemplateVO template;
        if (newTemplateId != null) {
            template = _templateDao.findById(newTemplateId);
            if (template == null) {
                throw new InvalidParameterValueException("Cannot find template with ID " + newTemplateId);
            }
        } else {
            template = _templateDao.findById(vm.getTemplateId());
            if (template == null) {
                throw new InvalidParameterValueException("Cannot find template linked with VM");
            }
        }
        DiskOffering diskOffering = rootDiskOfferingId != null ? validateAndGetDiskOffering(rootDiskOfferingId, vm, caller) : null;
        if (template.getSize() != null) {
            String rootDiskSize = details.get(VmDetailConstants.ROOT_DISK_SIZE);
            Long templateSize = template.getSize();
            if (StringUtils.isNumeric(rootDiskSize)) {
                if (Long.parseLong(rootDiskSize) * GiB_TO_BYTES < templateSize) {
                    throw new InvalidParameterValueException(String.format("Root disk size [%s] is smaller than the template size [%s]", rootDiskSize, templateSize));
                }
            } else if (diskOffering != null && diskOffering.getDiskSize() < templateSize) {
                throw new InvalidParameterValueException(String.format("Disk size for selected offering [%s] is less than the template's size [%s]", diskOffering.getDiskSize(), templateSize));
            }
        }

        if (HypervisorType.External.equals(vm.getHypervisorType())) {
            throw new InvalidParameterValueException("Restore VM instance operation is not allowed for External hypervisor type");
        }

        //check if there are any active snapshots on volumes associated with the VM
        logger.debug("Checking if there are any ongoing snapshots on the ROOT volumes associated with VM {}", vm);
        if (checkStatusOfVolumeSnapshots(vm, Volume.Type.ROOT)) {
            throw new CloudRuntimeException("There is/are unbacked up snapshot(s) on ROOT volume, Re-install VM is not permitted, please try again later.");
        }
        logger.debug("Found no ongoing snapshots on volume of type ROOT, for the vm {}", vm);
        return restoreVMInternal(caller, vm, newTemplateId, rootDiskOfferingId, expunge, details);
    }

    public UserVm restoreVMInternal(Account caller, UserVmVO vm, Long newTemplateId, Long rootDiskOfferingId, boolean expunge, Map<String, String> details) throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException {
        return _itMgr.restoreVirtualMachine(vm.getId(), newTemplateId, rootDiskOfferingId, expunge, details);
    }


    public UserVm restoreVMInternal(Account caller, UserVmVO vm) throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException {
        return restoreVMInternal(caller, vm, null, null, false, null);
    }

    private VMTemplateVO getRestoreVirtualMachineTemplate(Account caller, Long newTemplateId, List<VolumeVO> rootVols, UserVmVO vm) {
        VMTemplateVO template = null;
        if (CollectionUtils.isNotEmpty(rootVols)) {
            VolumeVO root = rootVols.get(0);
            Long templateId = root.getTemplateId();
            boolean isISO = false;
            if (templateId == null) {
                // Assuming that for a vm deployed using ISO, template ID is set to NULL
                isISO = true;
                templateId = vm.getIsoId();
            }
            //newTemplateId can be either template or ISO id. In the following snippet based on the vm deployment (from template or ISO) it is handled accordingly
            if (newTemplateId != null) {
                template = _templateDao.findById(newTemplateId);
                _accountMgr.checkAccess(caller, null, true, template);
                if (isISO) {
                    if (!template.getFormat().equals(ImageFormat.ISO)) {
                        throw new InvalidParameterValueException("VM has been created using an ISO therefore it can not be re-installed with a template");
                    }
                } else {
                    if (template.getFormat().equals(ImageFormat.ISO)) {
                        throw new InvalidParameterValueException("Invalid template id provided to restore the VM ");
                    }
                }
            } else {
                if (isISO && templateId == null) {
                    throw new CloudRuntimeException("Cannot restore the VM since there is no ISO attached to VM");
                }
                template = _templateDao.findById(templateId);
                if (template == null) {
                    InvalidParameterValueException ex = new InvalidParameterValueException("Cannot find template/ISO for specified volumeid and vmId");
                    ex.addProxyObject(vm.getUuid(), "vmId");
                    ex.addProxyObject(root.getUuid(), "volumeId");
                    throw ex;
                }
            }
        }

        return template;
    }

    @Override
    public UserVm restoreVirtualMachine(final Account caller, final long vmId, final Long newTemplateId,
            final Long rootDiskOfferingId,
            final boolean expunge, final Map<String, String> details) throws InsufficientCapacityException, ResourceUnavailableException {
        Long userId = caller.getId();
        _userDao.findById(userId);
        UserVmVO vm = _vmDao.findById(vmId);
        Account owner = _accountDao.findById(vm.getAccountId());
        boolean needRestart = false;

        // Input validation
        if (owner == null) {
            throw new InvalidParameterValueException("The owner of " + vm + " does not exist: " + vm.getAccountId());
        }

        if (owner.getState() == Account.State.DISABLED) {
            throw new PermissionDeniedException(String.format("The owner of %s is disabled: %s", vm, owner));
        }

        if (vm.getState() != VirtualMachine.State.Running && vm.getState() != VirtualMachine.State.Stopped) {
            throw new CloudRuntimeException("Vm " + vm.getUuid() + " currently in " + vm.getState() + " state, restore vm can only execute when VM in Running or Stopped");
        }

        if (vm.getState() == VirtualMachine.State.Running) {
            needRestart = true;
        }

        VMTemplateVO currentTemplate = _templateDao.findById(vm.getTemplateId());
        List<VolumeVO> rootVols = _volsDao.findByInstanceAndType(vmId, Volume.Type.ROOT);
        if (rootVols.isEmpty()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Can not find root volume for VM " + vm.getUuid());
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }
        if (rootVols.size() > 1 && currentTemplate != null && !currentTemplate.isDeployAsIs()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("There are " + rootVols.size() + " root volumes for VM " + vm.getUuid());
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        // If target VM has associated VM snapshots then don't allow restore of VM
        List<VMSnapshotVO> vmSnapshots = _vmSnapshotDao.findByVm(vmId);
        if (vmSnapshots.size() > 0) {
            throw new InvalidParameterValueException("Unable to restore VM, please remove VM snapshots before restoring VM");
        }

        VMTemplateVO template = getRestoreVirtualMachineTemplate(caller, newTemplateId, rootVols, vm);
        DiskOffering diskOffering = rootDiskOfferingId != null ? _diskOfferingDao.findById(rootDiskOfferingId) : null;
        try {
            checkRestoreVmFromTemplate(vm, template, rootVols, diskOffering, details);
        } catch (ResourceAllocationException e) {
            logger.error("Failed to restore VM {} due to {}", vm, e.getMessage(), e);
            throw new CloudRuntimeException("Failed to restore VM " + vm.getUuid() + " due to " + e.getMessage(), e);
        }

        if (needRestart) {
            try {
                _itMgr.stop(vm.getUuid());
            } catch (ResourceUnavailableException e) {
                logger.debug("Stop vm {} failed", vm, e);
                CloudRuntimeException ex = new CloudRuntimeException("Stop vm failed for specified vmId");
                ex.addProxyObject(vm.getUuid(), "vmId");
                throw ex;
            }
        }

        for (VolumeVO root : rootVols) {
            if ( !Volume.State.Allocated.equals(root.getState()) || newTemplateId != null || diskOffering != null) {
                _volumeService.validateDestroyVolume(root, caller, Volume.State.Allocated.equals(root.getState()) || expunge, false);
                final UserVmVO userVm = vm;
                Pair<UserVmVO, Volume> vmAndNewVol = Transaction.execute(new TransactionCallbackWithException<Pair<UserVmVO, Volume>, CloudRuntimeException>() {
                    @Override
                    public Pair<UserVmVO, Volume> doInTransaction(final TransactionStatus status) throws CloudRuntimeException {
                        Long templateId = root.getTemplateId();
                        boolean isISO = false;
                        if (templateId == null) {
                            // Assuming that for a vm deployed using ISO, template ID is set to NULL
                            isISO = true;
                            templateId = userVm.getIsoId();
                        }

                        /* If new template/ISO is provided allocate a new volume from new template/ISO otherwise allocate new volume from original template/ISO */
                        Volume newVol = null;
                        if (newTemplateId != null) {
                            if (isISO) {
                                newVol = volumeMgr.allocateDuplicateVolume(root, diskOffering, null);
                                userVm.setIsoId(newTemplateId);
                                userVm.setGuestOSId(template.getGuestOSId());
                                userVm.setTemplateId(newTemplateId);
                            } else {
                                newVol = volumeMgr.allocateDuplicateVolume(root, diskOffering, newTemplateId);
                                userVm.setGuestOSId(template.getGuestOSId());
                                userVm.setTemplateId(newTemplateId);
                            }
                            // check and update VM if it can be dynamically scalable with the new template
                            updateVMDynamicallyScalabilityUsingTemplate(userVm, newTemplateId);
                        } else {
                            newVol = volumeMgr.allocateDuplicateVolume(root, diskOffering, null);
                        }

                        getRootVolumeSizeForVmRestore(newVol, template, userVm, diskOffering, details, true);
                        volumeMgr.saveVolumeDetails(newVol.getDiskOfferingId(), newVol.getId());
                        newVol = _volsDao.findById(newVol.getId());

                        // 1. Save usage event and update resource count for user vm volumes
                        try {
                            _resourceLimitMgr.incrementVolumeResourceCount(userVm.getAccountId(), newVol.isDisplay(),
                                    newVol.getSize(), diskOffering != null ? diskOffering : _diskOfferingDao.findById(newVol.getDiskOfferingId()));
                        } catch (final CloudRuntimeException e) {
                            throw e;
                        } catch (final Exception e) {
                            logger.error("Unable to restore VM {}", userVm, e);
                            throw new CloudRuntimeException(e);
                        }

                        // 2. Create Usage event for the newly created volume
                        UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_VOLUME_CREATE, newVol.getAccountId(), newVol.getDataCenterId(), newVol.getId(), newVol.getName(), newVol.getDiskOfferingId(), template.getId(), newVol.getSize());
                        _usageEventDao.persist(usageEvent);

                        return new Pair<>(userVm, newVol);
                    }
                });

                vm = vmAndNewVol.first();
                Volume newVol = vmAndNewVol.second();

                handleManagedStorage(vm, root);

                _volsDao.attachVolume(newVol.getId(), vmId, newVol.getDeviceId());

                // Detach, destroy and create the usage event for the old root volume.
                _volsDao.detachVolume(root.getId());
                destroyVolumeInContext(vm, Volume.State.Allocated.equals(root.getState()) || expunge, root);

                if (currentTemplate.getId() != template.getId() && VirtualMachine.Type.User.equals(vm.type) && !VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
                    ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
                    _resourceLimitMgr.updateVmResourceCountForTemplateChange(vm.getAccountId(), vm.isDisplay(), serviceOffering, currentTemplate, template);
                }

                // For VMware hypervisor since the old root volume is replaced by the new root volume, force expunge old root volume if it has been created in storage
                if (vm.getHypervisorType() == HypervisorType.VMware) {
                    VolumeInfo volumeInStorage = volFactory.getVolume(root.getId());
                    if (volumeInStorage != null) {
                        logger.info("Expunging volume {} from primary data store", root);
                        AsyncCallFuture<VolumeApiResult> future = _volService.expungeVolumeAsync(volFactory.getVolume(root.getId()));
                        try {
                            future.get();
                        } catch (Exception e) {
                            logger.debug("Failed to expunge volume: {}", root, e);
                        }
                    }
                }
            }
        }

        Map<VirtualMachineProfile.Param, Object> params = null;
        String password = null;

        if (template.isEnablePassword()) {
            password = _mgr.generateRandomPassword();
            boolean result = resetVMPasswordInternal(vmId, password);
            if (!result) {
                throw new CloudRuntimeException("VM reset is completed but failed to reset password for the virtual machine ");
            }
            vm.setPassword(password);
        }
        if (needRestart) {
            try {
                if (Objects.nonNull(password)) {
                    params = new HashMap<>();
                    params.put(VirtualMachineProfile.Param.VmPassword, password);
                }
                _itMgr.start(vm.getUuid(), params);
                vm = _vmDao.findById(vmId);
                if (template.isEnablePassword()) {
                    // this value is not being sent to the backend; need only for api
                    // display purposes
                    vm.setPassword(password);
                    if (vm.isUpdateParameters()) {
                        vm.setUpdateParameters(false);
                        _vmDao.loadDetails(vm);
                        if (vm.getDetail(VmDetailConstants.PASSWORD) != null) {
                            vmInstanceDetailsDao.removeDetail(vm.getId(), VmDetailConstants.PASSWORD);
                        }
                        _vmDao.update(vm.getId(), vm);
                    }
                }
            } catch (Exception e) {
                logger.debug("Unable to start VM " + vm.getUuid(), e);
                CloudRuntimeException ex = new CloudRuntimeException("Unable to start VM with specified id" + e.getMessage());
                ex.addProxyObject(vm.getUuid(), "vmId");
                throw ex;
            }
        }

        logger.debug("Restore VM {} done successfully", vm);
        return vm;

    }

    Long getRootVolumeSizeForVmRestore(Volume vol, VMTemplateVO template, UserVmVO userVm, DiskOffering diskOffering, Map<String, String> details, boolean update) {
        VolumeVO resizedVolume = (VolumeVO) vol;
        Long size = null;
        if (template != null && template.getSize() != null) {
            VMInstanceDetailVO vmRootDiskSizeDetail = vmInstanceDetailsDao.findDetail(userVm.getId(), VmDetailConstants.ROOT_DISK_SIZE);
            if (vmRootDiskSizeDetail == null) {
                size = template.getSize();
            } else {
                long rootDiskSize = Long.parseLong(vmRootDiskSizeDetail.getValue()) * GiB_TO_BYTES;
                if (template.getSize() >= rootDiskSize) {
                    size = template.getSize();
                    if (update) {
                        vmInstanceDetailsDao.remove(vmRootDiskSizeDetail.getId());
                    }
                } else {
                    size = rootDiskSize;
                }
            }
            if (update) {
                resizedVolume.setSize(size);
            }
        }

        if (diskOffering != null) {
            if (update) {
                resizedVolume.setDiskOfferingId(diskOffering.getId());
            }
            // Size of disk offering should be greater than or equal to the template's size and this should be validated before this
            if (!diskOffering.isCustomized()) {
                size = diskOffering.getDiskSize();
                if (update) {
                    resizedVolume.setSize(diskOffering.getDiskSize());
                }
            }

            if (update) {
                if (diskOffering.getMinIops() != null) {
                    resizedVolume.setMinIops(diskOffering.getMinIops());
                }
                if (diskOffering.getMaxIops() != null) {
                    resizedVolume.setMaxIops(diskOffering.getMaxIops());
                }
            }
        }

        // Size of disk should be greater than or equal to the template's size and this should be validated before this
        if (MapUtils.isNotEmpty(details)) {
            if (StringUtils.isNumeric(details.get(VmDetailConstants.ROOT_DISK_SIZE))) {
                Long rootDiskSize = Long.parseLong(details.get(VmDetailConstants.ROOT_DISK_SIZE)) * GiB_TO_BYTES;
                size = rootDiskSize;
                if (update) {
                    resizedVolume.setSize(rootDiskSize);
                }
                VMInstanceDetailVO vmRootDiskSizeDetail = vmInstanceDetailsDao.findDetail(userVm.getId(), VmDetailConstants.ROOT_DISK_SIZE);
                if (update) {
                    if (vmRootDiskSizeDetail != null) {
                        vmRootDiskSizeDetail.setValue(details.get(VmDetailConstants.ROOT_DISK_SIZE));
                        vmInstanceDetailsDao.update(vmRootDiskSizeDetail.getId(), vmRootDiskSizeDetail);
                    } else {
                        vmInstanceDetailsDao.persist(new VMInstanceDetailVO(userVm.getId(), VmDetailConstants.ROOT_DISK_SIZE,
                                details.get(VmDetailConstants.ROOT_DISK_SIZE), true));
                    }
                }
            }
            if (update) {
                String minIops = details.get(MIN_IOPS);
                String maxIops = details.get(MAX_IOPS);

                if (StringUtils.isNumeric(minIops)) {
                    resizedVolume.setMinIops(Long.parseLong(minIops));
                }
                if (StringUtils.isNumeric(maxIops)) {
                    resizedVolume.setMinIops(Long.parseLong(maxIops));
                }
            }
        }
        if (update) {
            _volsDao.update(resizedVolume.getId(), resizedVolume);
        }
        return size;
    }

    private void updateVMDynamicallyScalabilityUsingTemplate(UserVmVO vm, Long newTemplateId) {
        ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getServiceOfferingId());
        VMTemplateVO newTemplate = _templateDao.findById(newTemplateId);
        boolean dynamicScalingEnabled = checkIfDynamicScalingCanBeEnabled(vm, serviceOffering, newTemplate, vm.getDataCenterId());
        vm.setDynamicallyScalable(dynamicScalingEnabled);
        _vmDao.update(vm.getId(), vm);
    }

    /**
     * Perform basic checkings to make sure restore is possible. If not, #InvalidParameterValueException is thrown.
     *
     * @param vm vm
     * @param template template
     * @throws InvalidParameterValueException if restore is not possible
     */
    private void checkRestoreVmFromTemplate(UserVmVO vm, VMTemplateVO template, List<VolumeVO> rootVolumes, DiskOffering newDiskOffering, Map<String,String> details) throws ResourceAllocationException {
        TemplateDataStoreVO tmplStore;
        if (!template.isDirectDownload()) {
            tmplStore = _templateStoreDao.findByTemplateZoneReady(template.getId(), vm.getDataCenterId());
            if (tmplStore == null) {
                throw new InvalidParameterValueException("Cannot restore the vm as the template " + template.getUuid() + " isn't available in the zone");
            }
        } else {
            tmplStore = _templateStoreDao.findByTemplate(template.getId(), DataStoreRole.Image);
            if (tmplStore == null || (tmplStore != null && !tmplStore.getDownloadState().equals(VMTemplateStorageResourceAssoc.Status.BYPASSED))) {
                throw new InvalidParameterValueException("Cannot restore the vm as the bypassed template " + template.getUuid() + " isn't available in the zone");
            }
        }

        AccountVO owner = _accountDao.findByIdIncludingRemoved(vm.getAccountId());
        if (vm.getTemplateId() != template.getId()) {
            ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
            VMTemplateVO currentTemplate = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
            _resourceLimitMgr.checkVmResourceLimitsForTemplateChange(owner, vm.isDisplay(), serviceOffering, currentTemplate, template);
        }

        for (Volume vol : rootVolumes) {
            Long newSize = getRootVolumeSizeForVmRestore(vol, template, vm, newDiskOffering, details, false);
            if (newSize == null) {
                newSize = vol.getSize();
            }
            if (newDiskOffering != null || !vol.getSize().equals(newSize)) {
                DiskOffering currentOffering = _diskOfferingDao.findById(vol.getDiskOfferingId());
                _resourceLimitMgr.checkVolumeResourceLimitForDiskOfferingChange(owner, vol.isDisplay(),
                        vol.getSize(), newSize, currentOffering, newDiskOffering);
            }
        }
    }

    private void handleManagedStorage(UserVmVO vm, VolumeVO root) {
        if (Volume.State.Allocated.equals(root.getState())) {
            return;
        }

        StoragePoolVO storagePool = _storagePoolDao.findById(root.getPoolId());

        if (storagePool != null && storagePool.isManaged()) {
            Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();

            if (hostId != null) {
                VolumeInfo volumeInfo = volFactory.getVolume(root.getId());
                Host host = _hostDao.findById(hostId);

                final Command cmd;

                if (host.getHypervisorType() == HypervisorType.XenServer) {
                    DiskTO disk = new DiskTO(volumeInfo.getTO(), root.getDeviceId(), root.getPath(), root.getVolumeType());

                    // it's OK in this case to send a detach command to the host for a root volume as this
                    // will simply lead to the SR that supports the root volume being removed
                    cmd = new DettachCommand(disk, vm.getInstanceName());

                    DettachCommand detachCommand = (DettachCommand)cmd;

                    detachCommand.setManaged(true);

                    detachCommand.setStorageHost(storagePool.getHostAddress());
                    detachCommand.setStoragePort(storagePool.getPort());

                    detachCommand.set_iScsiName(root.get_iScsiName());
                }
                else if (host.getHypervisorType() == HypervisorType.VMware) {
                    PrimaryDataStore primaryDataStore = (PrimaryDataStore)volumeInfo.getDataStore();
                    Map<String, String> details = primaryDataStore.getDetails();

                    if (details == null) {
                        details = new HashMap<>();

                        primaryDataStore.setDetails(details);
                    }

                    details.put(DiskTO.MANAGED, Boolean.TRUE.toString());

                    cmd = new DeleteCommand(volumeInfo.getTO());
                }
                else if (host.getHypervisorType() == HypervisorType.KVM) {
                    cmd = null;
                }
                else {
                    throw new CloudRuntimeException("This hypervisor type is not supported on managed storage for this command.");
                }

                if (cmd != null) {
                    Commands cmds = new Commands(Command.OnError.Stop);

                    cmds.addCommand(cmd);

                    try {
                        _agentMgr.send(hostId, cmds);
                    } catch (Exception ex) {
                        throw new CloudRuntimeException(ex.getMessage());
                    }

                    if (!cmds.isSuccessful()) {
                        for (Answer answer : cmds.getAnswers()) {
                            if (!answer.getResult()) {
                                logger.warn("Failed to reset vm {} due to: {}", vm, answer.getDetails());

                                throw new CloudRuntimeException("Unable to reset " + vm + " due to " + answer.getDetails());
                            }
                        }
                    }
                }

                // root.getPoolId() should be null if the VM we are detaching the disk from has never been started before
                DataStore dataStore = root.getPoolId() != null ? _dataStoreMgr.getDataStore(root.getPoolId(), DataStoreRole.Primary) : null;

                volumeMgr.revokeAccess(volFactory.getVolume(root.getId()), host, dataStore);

                if (dataStore != null) {
                    handleTargetsForVMware(host.getId(), storagePool.getHostAddress(), storagePool.getPort(), root.get_iScsiName());
                }
            }
        }
    }

    private void handleTargetsForVMware(long hostId, String storageAddress, int storagePort, String iScsiName) {
        HostVO host = _hostDao.findById(hostId);

        if (host.getHypervisorType() == HypervisorType.VMware) {
            ModifyTargetsCommand cmd = new ModifyTargetsCommand();

            List<Map<String, String>> targets = new ArrayList<>();

            Map<String, String> target = new HashMap<>();

            target.put(ModifyTargetsCommand.STORAGE_HOST, storageAddress);
            target.put(ModifyTargetsCommand.STORAGE_PORT, String.valueOf(storagePort));
            target.put(ModifyTargetsCommand.IQN, iScsiName);

            targets.add(target);

            cmd.setTargets(targets);
            cmd.setApplyToAllHostsInCluster(true);
            cmd.setAdd(false);
            cmd.setTargetTypeToRemove(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC);

            sendModifyTargetsCommand(cmd, host);
        }
    }

    private void sendModifyTargetsCommand(ModifyTargetsCommand cmd, HostVO host) {
        Answer answer = _agentMgr.easySend(host.getId(), cmd);

        if (answer == null) {
            String msg = "Unable to get an answer to the modify targets command";

            logger.warn(msg);
        }
        else if (!answer.getResult()) {
            String msg = String.format("Unable to modify target on the following host: %s", host);

            logger.warn(msg);
        }
    }

    @Override
    public void prepareStop(VirtualMachineProfile profile) {
        collectVmDiskAndNetworkStatistics(profile.getId(), State.Stopping);
    }

    @Override
    public void finalizeUnmanage(VirtualMachine vm) {
    }

    private void encryptAndStorePassword(UserVmVO vm, String password) {
        String sshPublicKeys = vm.getDetail(VmDetailConstants.SSH_PUBLIC_KEY);
        if (sshPublicKeys != null && !sshPublicKeys.equals("") && password != null && !password.equals("saved_password")) {
            if (!sshPublicKeys.startsWith("ssh-rsa")) {
                logger.warn("Only RSA public keys can be used to encrypt a vm password.");
                return;
            }
            String encryptedPasswd = RSAHelper.encryptWithSSHPublicKey(sshPublicKeys, password);
            if (encryptedPasswd == null) {
                throw new CloudRuntimeException("Error encrypting password");
            }

            vm.setDetail(VmDetailConstants.ENCRYPTED_PASSWORD, encryptedPasswd);
            _vmDao.saveDetails(vm);
        }
    }

    @Override
    public void persistDeviceBusInfo(UserVmVO vm, String rootDiskController) {
        String existingVmRootDiskController = vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER);
        if (StringUtils.isEmpty(existingVmRootDiskController) && StringUtils.isNotEmpty(rootDiskController)) {
            vm.setDetail(VmDetailConstants.ROOT_DISK_CONTROLLER, rootDiskController);
            _vmDao.saveDetails(vm);
            if (logger.isDebugEnabled()) {
                logger.debug("Persisted device bus information rootDiskController={} for vm: {}", rootDiskController, vm);
            }
        }
    }

    @Override
    public String getConfigComponentName() {
        return UserVmManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {EnableDynamicallyScaleVm, AllowDiskOfferingChangeDuringScaleVm, AllowUserExpungeRecoverVm, VmIpFetchWaitInterval, VmIpFetchTrialMax,
                VmIpFetchThreadPoolMax, VmIpFetchTaskWorkers, AllowDeployVmIfGivenHostFails, EnableAdditionalVmConfig, DisplayVMOVFProperties,
                KvmAdditionalConfigAllowList, XenServerAdditionalConfigAllowList, VmwareAdditionalConfigAllowList, DestroyRootVolumeOnVmDestruction,
                EnforceStrictResourceLimitHostTagCheck, StrictHostTags, AllowUserForceStopVm};
    }

    @Override
    public String getVmUserData(long vmId) {
        UserVmVO vm = _vmDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find virtual machine with id " + vmId);
        }

        _accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
        return vm.getUserData();
    }

    @Override
    public boolean isDisplayResourceEnabled(Long vmId) {
        UserVm vm = _vmDao.findById(vmId);
        if (vm != null) {
            return vm.isDisplayVm();
        }

        return true;
    }

    private boolean checkStatusOfVolumeSnapshots(VirtualMachine vm, Volume.Type type) {
        long vmId = vm.getId();
        List<VolumeVO> listVolumes = null;
        if (type == Volume.Type.ROOT) {
            listVolumes = _volsDao.findByInstanceAndType(vmId, type);
        } else if (type == Volume.Type.DATADISK) {
            listVolumes = _volsDao.findByInstanceAndType(vmId, type);
        } else {
            listVolumes = _volsDao.findByInstance(vmId);
        }
        logger.debug("Found {} no. of volumes of type {} for vm with VM ID {}", listVolumes.size(), type, vm);
        for (VolumeVO volume : listVolumes) {
            Long volumeId = volume.getId();
            logger.debug("Checking status of snapshots for Volume: {}", volume);
            List<SnapshotVO> ongoingSnapshots = _snapshotDao.listByStatus(volumeId, Snapshot.State.Creating, Snapshot.State.CreatedOnPrimary, Snapshot.State.BackingUp);
            int ongoingSnapshotsCount = ongoingSnapshots.size();
            logger.debug("The count of ongoing Snapshots for VM {} and disk type {} is {}", vm, type, ongoingSnapshotsCount);
            if (ongoingSnapshotsCount > 0) {
                logger.debug("Found "+ongoingSnapshotsCount+" no. of snapshots, on volume of type "+type+", which snapshots are not yet backed up");
                return true;
            }
        }
        return false;
    }

    private void checkForUnattachedVolumes(long vmId, List<VolumeVO> volumes) {

        StringBuilder sb = new StringBuilder();

        for (VolumeVO volume : volumes) {
            if (volume.getInstanceId() == null || vmId != volume.getInstanceId() || volume.getVolumeType() != Volume.Type.DATADISK) {
                sb.append(volume.toString() + "; ");
            }
        }

        if (!StringUtils.isEmpty(sb.toString())) {
            throw new InvalidParameterValueException("The following supplied volumes are not DATADISK attached to the VM: " + sb.toString());
        }
    }

    private void validateVolumes(List<VolumeVO> volumes) {

        for (VolumeVO volume : volumes) {
            if (!(volume.getVolumeType() == Volume.Type.ROOT || volume.getVolumeType() == Volume.Type.DATADISK)) {
                throw new InvalidParameterValueException("Please specify volume of type " + Volume.Type.DATADISK.toString() + " or " + Volume.Type.ROOT.toString());
            }
            if (volume.isDeleteProtection()) {
                throw new InvalidParameterValueException(String.format(
                        "Volume [id = %s, name = %s] has delete protection enabled and cannot be deleted",
                        volume.getUuid(), volume.getName()));
            }
        }
    }

    private void detachVolumesFromVm(UserVm vm, List<VolumeVO> volumes) {

        for (VolumeVO volume : volumes) {
            // Create new context and inject correct event resource type, id and details,
            // otherwise VOLUME.DETACH event will be associated with VirtualMachine and contain VM id and other information.
            CallContext volumeContext = CallContext.register(CallContext.current(), ApiCommandResourceType.Volume);
            volumeContext.setEventDetails("Volume Type: " + volume.getVolumeType() + " Volume Id: " + this._uuidMgr.getUuid(Volume.class, volume.getId()) + " Vm Id: " + this._uuidMgr.getUuid(VirtualMachine.class, volume.getInstanceId()));
            volumeContext.setEventResourceType(ApiCommandResourceType.Volume);
            volumeContext.setEventResourceId(volume.getId());

            Volume detachResult = null;
            try {
                detachResult = _volumeService.detachVolumeViaDestroyVM(volume.getInstanceId(), volume.getId());
            } finally {
                // Remove volumeContext and pop vmContext back
                CallContext.unregister();
            }

            if (detachResult == null) {
                logger.error("DestroyVM remove volume - failed to detach and delete volume {} from instance {}", volume, vm);
            }
        }
    }

    private void deleteVolumesFromVm(UserVmVO vm, List<VolumeVO> volumes, boolean expunge) {

        for (VolumeVO volume : volumes) {
            destroyVolumeInContext(vm, expunge, volume);
        }
    }

    private void destroyVolumeInContext(UserVmVO vm, boolean expunge, VolumeVO volume) {
        // Create new context and inject correct event resource type, id and details,
        // otherwise VOLUME.DESTROY event will be associated with VirtualMachine and contain VM id and other information.
        CallContext volumeContext = CallContext.register(CallContext.current(), ApiCommandResourceType.Volume);
        volumeContext.setEventDetails("Volume Type: " + volume.getVolumeType() + " Volume Id: " + this._uuidMgr.getUuid(Volume.class, volume.getId()) + " Vm Id: " + vm.getUuid());
        volumeContext.setEventResourceType(ApiCommandResourceType.Volume);
        volumeContext.setEventResourceId(volume.getId());
        try {
            Volume result = _volumeService.destroyVolume(volume.getId(), CallContext.current().getCallingAccount(), expunge, false);

            if (result == null) {
                logger.error("DestroyVM remove volume - failed to delete volume {} from instance {}", volume, vm);
            }
        } finally {
            // Remove volumeContext and pop vmContext back
            CallContext.unregister();
        }
    }

    @Override
    public UserVm importVM(final DataCenter zone, final Host host, final VirtualMachineTemplate template, final String instanceName, final String displayName,
                           final Account owner, final String userData, final Account caller, final Boolean isDisplayVm, final String keyboard,
                           final long accountId, final long userId, final ServiceOffering serviceOffering, final String sshPublicKeys,
                           final String hostName, final HypervisorType hypervisorType, final Map<String, String> customParameters,
                           final VirtualMachine.PowerState powerState, final LinkedHashMap<String, List<NicProfile>> networkNicMap) throws InsufficientCapacityException {
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to import virtual machine with invalid zone");
        }
        if (host == null && hypervisorType == HypervisorType.VMware) {
            throw new InvalidParameterValueException("Unable to import virtual machine with invalid host");
        }

        final long id = _vmDao.getNextInSequence(Long.class, "id");

        if (hostName != null) {
            // Check is hostName is RFC compliant
            checkNameForRFCCompliance(hostName);
        }

        final String uuidName = _uuidMgr.generateUuid(UserVm.class, null);
        final Host lastHost = powerState != VirtualMachine.PowerState.PowerOn ? host : null;
        final Boolean dynamicScalingEnabled = checkIfDynamicScalingCanBeEnabled(null, serviceOffering, template, zone.getId());
        return commitUserVm(true, zone, host, lastHost, template, hostName, displayName, owner,
                null, null, userData, null, null, isDisplayVm, keyboard,
                accountId, userId, serviceOffering, template.getFormat().equals(ImageFormat.ISO), sshPublicKeys, networkNicMap,
                id, instanceName, uuidName, hypervisorType, customParameters,
                null, null, null, powerState, dynamicScalingEnabled, null, serviceOffering.getDiskOfferingId(), null, null, null);
    }

    @Override
    public boolean unmanageUserVM(Long vmId) {
        UserVmVO vm = _vmDao.findById(vmId);
        if (vm == null || vm.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find a VM with ID = " + vmId);
        }

        vm = _vmDao.acquireInLockTable(vm.getId());
        boolean result;
        try {
            if (vm.getState() != State.Running && vm.getState() != State.Stopped) {
                logger.debug("VM {} is not running or stopped, cannot be unmanaged", vm);
                return false;
            }

            if (!UnmanagedVMsManager.isSupported(vm.getHypervisorType())) {
                throw new UnsupportedServiceException("Unmanaging a VM is currently not supported on hypervisor " +
                        vm.getHypervisorType().toString());
            }

            List<VolumeVO> volumes = _volsDao.findByInstance(vm.getId());
            checkUnmanagingVMOngoingVolumeSnapshots(vm);
            checkUnmanagingVMVolumes(vm, volumes);

            result = _itMgr.unmanage(vm.getUuid());
            if (result) {
                cleanupUnmanageVMResources(vm);
                unmanageVMFromDB(vm.getId());
                publishUnmanageVMUsageEvents(vm, volumes);
            } else {
                throw new CloudRuntimeException("Error while unmanaging VM: " + vm.getUuid());
            }
        } catch (Exception e) {
            logger.error("Could not unmanage VM {}", vm, e);
            throw new CloudRuntimeException(e);
        } finally {
            _vmDao.releaseFromLockTable(vm.getId());
        }

        return true;
    }

    /*
        Generate usage events related to unmanaging a VM
     */
    private void publishUnmanageVMUsageEvents(UserVmVO vm, List<VolumeVO> volumes) {
        postProcessingUnmanageVMVolumes(volumes, vm);
        postProcessingUnmanageVM(vm);
    }

    /*
        Cleanup the VM from resources and groups
     */
    private void cleanupUnmanageVMResources(UserVmVO vm) {
        cleanupVmResources(vm);
        removeVMFromAffinityGroups(vm.getId());
    }

    private void unmanageVMFromDB(long vmId) {
        VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        vmInstanceDetailsDao.removeDetails(vmId);
        vm.setState(State.Expunging);
        vm.setRemoved(new Date());
        _vmInstanceDao.update(vm.getId(), vm);
    }

    /*
        Remove VM from affinity groups after unmanaging
     */
    private void removeVMFromAffinityGroups(long vmId) {
        List<AffinityGroupVMMapVO> affinityGroups = _affinityGroupVMMapDao.listByInstanceId(vmId);
        if (affinityGroups.size() > 0) {
            logger.debug("Cleaning up VM from affinity groups after unmanaging");
            for (AffinityGroupVMMapVO map : affinityGroups) {
                _affinityGroupVMMapDao.expunge(map.getId());
            }
        }
    }

    /*
        Decrement VM resources and generate usage events after unmanaging VM
     */
    private void postProcessingUnmanageVM(UserVmVO vm) {
        ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getServiceOfferingId());
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
        // First generate a VM stop event if the VM was not stopped already
        boolean resourceNotDecremented = true;
        if (vm.getState() != State.Stopped) {
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_STOP, vm.getAccountId(), vm.getDataCenterId(),
                    vm.getId(), vm.getHostName(), vm.getServiceOfferingId(), vm.getTemplateId(),
                    vm.getHypervisorType().toString(), VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplayVm());

            resourceCountDecrement(vm.getAccountId(), vm.isDisplayVm(), offering, template);
            resourceNotDecremented = false;
        }
        // VM destroy usage event
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_DESTROY, vm.getAccountId(), vm.getDataCenterId(),
                vm.getId(), vm.getHostName(), vm.getServiceOfferingId(), vm.getTemplateId(),
                vm.getHypervisorType().toString(), VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplayVm());
        if (resourceNotDecremented) {
            resourceCountDecrement(vm.getAccountId(), vm.isDisplayVm(), offering, template);
        }
    }

    /*
        Decrement resources for volumes and generate usage event for ROOT volume after unmanaging VM.
        Usage events for DATA disks are published by the transition listener: @see VolumeStateListener#postStateTransitionEvent
     */
    private void postProcessingUnmanageVMVolumes(List<VolumeVO> volumes, UserVmVO vm) {
        for (VolumeVO volume : volumes) {
            if (volume.getVolumeType() == Volume.Type.ROOT) {
                //
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                        Volume.class.getName(), volume.getUuid(), volume.isDisplayVolume());
            }
            _resourceLimitMgr.decrementVolumeResourceCount(vm.getAccountId(), volume.isDisplayVolume(),
                    volume.getSize(), _diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId()));
        }
    }

    private void checkUnmanagingVMOngoingVolumeSnapshots(UserVmVO vm) {
        logger.debug("Checking if there are any ongoing snapshots on the ROOT volumes associated with VM {}", vm);
        if (checkStatusOfVolumeSnapshots(vm, Volume.Type.ROOT)) {
            throw new CloudRuntimeException("There is/are unbacked up snapshot(s) on ROOT volume, vm unmanage is not permitted, please try again later.");
        }
        logger.debug("Found no ongoing snapshots on volume of type ROOT, for the vm {}", vm);
    }

    private void checkUnmanagingVMVolumes(UserVmVO vm, List<VolumeVO> volumes) {
        for (VolumeVO volume : volumes) {
            if (volume.getInstanceId() == null || !volume.getInstanceId().equals(vm.getId())) {
                throw new CloudRuntimeException(String.format("Invalid state for volume %s of VM %s: it is not attached to VM", volume, vm));
            } else if (volume.getVolumeType() != Volume.Type.ROOT && volume.getVolumeType() != Volume.Type.DATADISK) {
                throw new CloudRuntimeException(String.format("Invalid type for volume %s: ROOT or DATADISK expected but got %s", volume, volume.getVolumeType()));
            }
        }
    }

    private LinkedHashMap<Integer, Long> getVmOvfNetworkMapping(DataCenter zone, Account owner, VirtualMachineTemplate template, Map<Integer, Long> vmNetworkMapping) throws InsufficientCapacityException, ResourceAllocationException {
        LinkedHashMap<Integer, Long> mapping = new LinkedHashMap<>();
        if (ImageFormat.OVA.equals(template.getFormat())) {
            List<OVFNetworkTO> OVFNetworkTOList =
                    templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(template.getId());
            if (CollectionUtils.isNotEmpty(OVFNetworkTOList)) {
                Network lastMappedNetwork = null;
                for (OVFNetworkTO OVFNetworkTO : OVFNetworkTOList) {
                    Long networkId = vmNetworkMapping.get(OVFNetworkTO.getInstanceID());
                    if (networkId == null && lastMappedNetwork == null) {
                        lastMappedNetwork = getNetworkForOvfNetworkMapping(zone, owner);
                    }
                    if (networkId == null) {
                        networkId = lastMappedNetwork.getId();
                    }
                    mapping.put(OVFNetworkTO.getInstanceID(), networkId);
                }
            }
        }
        return mapping;
    }

    private Network getNetworkForOvfNetworkMapping(DataCenter zone, Account owner) throws InsufficientCapacityException, ResourceAllocationException {
        Network network = null;
        if (zone.isSecurityGroupEnabled() || _networkModel.isSecurityGroupSupportedForZone(zone.getId())) {
            network = _networkModel.getNetworkWithSGWithFreeIPs(owner, zone.getId());
            if (network == null) {
                throw new InvalidParameterValueException("No network with security enabled is found in zone ID: " + zone.getUuid());
            }
        } else {
            network = getDefaultNetwork(zone, owner, true);
            if (network == null) {
                throw new InvalidParameterValueException(String.format("Default network not found for zone ID: %s and account ID: %s", zone.getUuid(), owner.getUuid()));
            }
        }
        return network;
    }

    private void collectVmDiskAndNetworkStatistics(Long vmId, State expectedState) {
        UserVmVO uservm = _vmDao.findById(vmId);
        if (uservm != null) {
            collectVmDiskAndNetworkStatistics(uservm, expectedState);
        } else {
            logger.info("Skip collecting vmId {} disk and network statistics as it is not user vm", vmId);
        }
    }

    private void collectVmDiskAndNetworkStatistics(UserVm vm, State expectedState) {
        if (expectedState == null || expectedState == vm.getState()) {
            collectVmDiskStatistics(vm);
            collectVmNetworkStatistics(vm);
        } else {
            logger.warn(String.format("Skip collecting vm %s disk and network statistics as the expected vm state is %s but actual state is %s", vm, expectedState, vm.getState()));
        }
    }

    public Boolean getDestroyRootVolumeOnVmDestruction(Long domainId){
        return DestroyRootVolumeOnVmDestruction.valueIn(domainId);
    }

    private void setVncPasswordForKvmIfAvailable(Map<String, String> customParameters, UserVmVO vm) {
        if (customParameters.containsKey(VmDetailConstants.KVM_VNC_PASSWORD)
                && StringUtils.isNotEmpty(customParameters.get(VmDetailConstants.KVM_VNC_PASSWORD))) {
            vm.setVncPassword(customParameters.get(VmDetailConstants.KVM_VNC_PASSWORD));
        }
    }
}
