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
package com.cloud.hypervisor.kvm.resource;

import static com.cloud.host.Host.HOST_INSTANCE_CONVERSION;
import static com.cloud.host.Host.HOST_OVFTOOL_VERSION;
import static com.cloud.host.Host.HOST_VIRTV2V_VERSION;
import static com.cloud.host.Host.HOST_VOLUME_ENCRYPTION;
import static org.apache.cloudstack.utils.linux.KVMHostInfo.isHostS390x;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.naming.ConfigurationException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.cloudstack.api.ApiConstants.IoDriverPolicy;
import org.apache.cloudstack.command.CommandInfo;
import org.apache.cloudstack.command.ReconcileCommandService;
import org.apache.cloudstack.command.ReconcileCommandUtils;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.gpu.GpuDevice;
import org.apache.cloudstack.storage.command.browser.ListDataStoreObjectsCommand;
import org.apache.cloudstack.storage.configdrive.ConfigDrive;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.volume.VolumeOnStorageTO;
import org.apache.cloudstack.utils.bytescale.ByteScaleUtils;
import org.apache.cloudstack.utils.cryptsetup.CryptSetup;
import org.apache.cloudstack.utils.hypervisor.HypervisorUtils;
import org.apache.cloudstack.utils.linux.CPUStat;
import org.apache.cloudstack.utils.linux.KVMHostInfo;
import org.apache.cloudstack.utils.linux.MemStat;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.cloudstack.utils.qemu.QemuObject;
import org.apache.cloudstack.utils.security.KeyStoreUtils;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.Logger;
import org.apache.xerces.impl.xpath.regex.Match;
import org.joda.time.Duration;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainBlockJobInfo;
import org.libvirt.DomainBlockStats;
import org.libvirt.DomainInfo;
import org.libvirt.DomainInfo.DomainState;
import org.libvirt.DomainInterfaceStats;
import org.libvirt.DomainSnapshot;
import org.libvirt.LibvirtException;
import org.libvirt.MemoryStatistic;
import org.libvirt.Network;
import org.libvirt.SchedParameter;
import org.libvirt.SchedUlongParameter;
import org.libvirt.Secret;
import org.libvirt.VcpuInfo;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.HostVmStateReportEntry;
import com.cloud.agent.api.PingAnswer;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PingRoutingWithNwGroupsCommand;
import com.cloud.agent.api.SecurityGroupRulesCmd;
import com.cloud.agent.api.SetupGuestNetworkCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.StartupStorageCommand;
import com.cloud.agent.api.VgpuTypesInfo;
import com.cloud.agent.api.VmDiskStatsEntry;
import com.cloud.agent.api.VmNetworkStatsEntry;
import com.cloud.agent.api.VmStatsEntry;
import com.cloud.agent.api.routing.IpAssocCommand;
import com.cloud.agent.api.routing.IpAssocVpcCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.routing.SetSourceNatCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.dao.impl.PropertiesStorage;
import com.cloud.agent.properties.AgentProperties;
import com.cloud.agent.properties.AgentPropertiesFileHandler;
import com.cloud.agent.resource.virtualnetwork.VRScripts;
import com.cloud.agent.resource.virtualnetwork.VirtualRouterDeployer;
import com.cloud.agent.resource.virtualnetwork.VirtualRoutingResource;
import com.cloud.configuration.Config;
import com.cloud.dc.Vlan;
import com.cloud.exception.InternalErrorException;
import com.cloud.host.Host.Type;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.hypervisor.kvm.resource.disconnecthook.DisconnectHook;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.ChannelDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.ClockDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.ConsoleDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.CpuModeDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.CpuTuneDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DevicesDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DeviceType;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiscardType;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskProtocol;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.FeaturesDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.FilesystemDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.GraphicDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.GuestDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.GuestResourceDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InputDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.MemBalloonDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.RngDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.RngDef.RngBackendModel;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.SCSIDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.SerialDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.TermPolicy;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.TpmDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.VideoDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.WatchDogDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.WatchDogDef.WatchDogAction;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.WatchDogDef.WatchDogModel;
import com.cloud.hypervisor.kvm.resource.rolling.maintenance.RollingMaintenanceAgentExecutor;
import com.cloud.hypervisor.kvm.resource.rolling.maintenance.RollingMaintenanceExecutor;
import com.cloud.hypervisor.kvm.resource.rolling.maintenance.RollingMaintenanceServiceExecutor;
import com.cloud.hypervisor.kvm.resource.wrapper.LibvirtRequestWrapper;
import com.cloud.hypervisor.kvm.resource.wrapper.LibvirtUtilitiesHelper;
import com.cloud.hypervisor.kvm.storage.IscsiStorageCleanupMonitor;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.KVMStorageProcessor;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.Networks.RouterPrivateIpStrategy;
import com.cloud.network.Networks.TrafficType;
import com.cloud.resource.AgentStatusUpdater;
import com.cloud.resource.RequestWrapper;
import com.cloud.resource.ResourceStatusUpdater;
import com.cloud.resource.ServerResource;
import com.cloud.resource.ServerResourceBase;
import com.cloud.storage.JavaStorageLayer;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.Volume;
import com.cloud.storage.resource.StorageSubsystemCommandHandler;
import com.cloud.storage.resource.StorageSubsystemCommandHandlerBase;
import com.cloud.utils.ExecutionResult;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.Ternary;
import com.cloud.utils.UuidUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.OutputInterpreter.AllLinesParser;
import com.cloud.utils.script.Script;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VmDetailConstants;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * LibvirtComputingResource execute requests on the computing/routing host using
 * the libvirt API
 *
 * @config {@table || Param Name | Description | Values | Default || ||
 *         hypervisor.type | type of local hypervisor | string | kvm || ||
 *         hypervisor.uri | local hypervisor to connect to | URI |
 *         qemu:///system || || domr.arch | instruction set for domr template |
 *         string | i686 || || private.bridge.name | private bridge where the
 *         domrs have their private interface | string | vmops0 || ||
 *         public.bridge.name | public bridge where the domrs have their public
 *         interface | string | br0 || || private.network.name | name of the
 *         network where the domrs have their private interface | string |
 *         vmops-private || || private.ipaddr.start | start of the range of
 *         private ip addresses for domrs | ip address | 192.168.166.128 || ||
 *         private.ipaddr.end | end of the range of private ip addresses for
 *         domrs | ip address | start + 126 || || private.macaddr.start | start
 *         of the range of private mac addresses for domrs | mac address |
 *         00:16:3e:77:e2:a0 || || private.macaddr.end | end of the range of
 *         private mac addresses for domrs | mac address | start + 126 || ||
 *         pool | the parent of the storage pool hierarchy * }
 **/
public class LibvirtComputingResource extends ServerResourceBase implements ServerResource, VirtualRouterDeployer, ResourceStatusUpdater {

    protected static Logger LOGGER = LogManager.getLogger(LibvirtComputingResource.class);
    private static final String CONFIG_VALUES_SEPARATOR = ",";


    private static final String LEGACY = "legacy";
    private static final String SECURE = "secure";

    /**
     * Machine type for s390x architecture
     */
    private static final String S390X_VIRTIO_DEVICE = "s390-ccw-virtio";

    /**
     * Machine type.
     */
    public static final String PC = isHostS390x() ? S390X_VIRTIO_DEVICE : "pc";
    public static final String VIRT = isHostS390x() ? S390X_VIRTIO_DEVICE : "virt";

    /**
     * Possible devices to add to VM.
     */
    private static final String TABLET = "tablet";
    private static final String USB = "usb";
    private static final String MOUSE = "mouse";
    private static final String KEYBOARD = "keyboard";

    /**
     * Policies used by VM.
     */
    private static final String RESTART = "restart";
    private static final String DESTROY = "destroy";

    private static final String KVMCLOCK = "kvmclock";
    private static final String HYPERVCLOCK = "hypervclock";
    private static final String WINDOWS = "Windows";
    private static final String Q35 = "q35";
    private static final String PTY = "pty";
    private static final String VNC = "vnc";

    /**
     * Acronym of System Management Mode. Perform low-level system management operations while an OS is running.
     */
    private static final String SMM = "smm";
    /**
     * Acronym of Advanced Configuration and Power Interface.<br>
     * Provides an open standard that operating systems can use to discover and configure
     * computer hardware components, to perform power management.
     */
    private static final String ACPI = "acpi";
    /**
     * Acronym of Advanced Programmable Interrupt Controllers.<br>
     * With an I/O APIC, operating systems can use more than 16 interrupt requests (IRQs)
     * and therefore avoid IRQ sharing for improved reliability.
     */
    private static final String APIC = "apic";
    /**
     * Acronym of Physical Address Extension. Feature implemented in modern x86 processors.<br>
     * PAE extends memory addressing capabilities, allowing more than 4 GB of random access memory (RAM) to be used.
     */
    private static final String PAE = "pae";
    /**
     * Libvirt supports guest CPU mode since 0.9.10.
     */
    private static final int MIN_LIBVIRT_VERSION_FOR_GUEST_CPU_MODE = 9010;
    /**
     * The CPU tune element provides details of the CPU tunable parameters for the domain.<br>
     * It is supported since Libvirt 0.9.0
     */
    private static final int MIN_LIBVIRT_VERSION_FOR_GUEST_CPU_TUNE = 9000;
    /**
     * Constant that defines ARM64 (aarch64) guest architectures.
     */
    private static final String AARCH64 = "aarch64";
    /**
     * Constant that defines IBM Z Arch (s390x) guest architectures.
     */
    private static final String S390X = "s390x";

    public static final String RESIZE_NOTIFY_ONLY = "NOTIFYONLY";
    public static final String BASEPATH = "/usr/share/cloudstack-common/vms/";

    public static final String TUNGSTEN_PATH = "scripts/vm/network/tungsten";

    public static final String INSTANCE_CONVERSION_SUPPORTED_CHECK_CMD = "virt-v2v --version";
    // virt-v2v --version => sample output: virt-v2v 1.42.0rhel=8,release=22.module+el8.10.0+1590+a67ab969
    public static final String OVF_EXPORT_SUPPORTED_CHECK_CMD = "ovftool --version";
    // ovftool --version => sample output: VMware ovftool 4.6.0 (build-21452615)
    public static final String OVF_EXPORT_TOOl_GET_VERSION_CMD = "ovftool --version | awk '{print $3}'";

    public static final String WINDOWS_GUEST_CONVERSION_SUPPORTED_CHECK_CMD = "rpm -qa | grep -i virtio-win";
    public static final String UBUNTU_WINDOWS_GUEST_CONVERSION_SUPPORTED_CHECK_CMD = "dpkg -l virtio-win";
    public static final String UBUNTU_NBDKIT_PKG_CHECK_CMD = "dpkg -l nbdkit";

    public static final int LIBVIRT_CGROUP_CPU_SHARES_MIN = 2;
    public static final int LIBVIRT_CGROUP_CPU_SHARES_MAX = 262144;
    /**
     * The minimal value for the LIBVIRT_CGROUPV2_WEIGHT_MIN is actually 1.
     * However, due to an old libvirt bug, it is raised to 2.
     * See: https://github.com/libvirt/libvirt/commit/38af6497610075e5fe386734b87186731d4c17ac
     */
    public static final int LIBVIRT_CGROUPV2_WEIGHT_MIN = 2;
    public static final int LIBVIRT_CGROUPV2_WEIGHT_MAX = 10000;

    public static final String COMMANDS_LOG_PATH = "/usr/share/cloudstack-agent/tmp/commands";

    public static final String CHECKPOINT_CREATE_COMMAND = "virsh checkpoint-create --domain %s --xmlfile %s --redefine";

    public static final String CHECKPOINT_DELETE_COMMAND = "virsh checkpoint-delete --domain %s --checkpointname %s  --metadata";

    protected int qcow2DeltaMergeTimeout;

    private String modifyVlanPath;
    private String versionStringPath;
    private String gpuDiscoveryPath;
    private String patchScriptPath;
    private String createVmPath;
    private String manageSnapshotPath;
    private String resizeVolumePath;
    private String createTmplPath;
    private String heartBeatPath;
    private String vmActivityCheckPath;
    private String nasBackupPath;
    private String securityGroupPath;
    private String ovsPvlanDhcpHostPath;
    private String ovsPvlanVmPath;
    private String routerProxyPath;
    private String ovsTunnelPath;

    private String setupTungstenVrouterPath;
    private String updateTungstenLoadbalancerStatsPath;
    private String updateTungstenLoadbalancerSslPath;
    private String host;

    private String dcId;
    private String clusterId;
    private final Properties uefiProperties = new Properties();
    private String hostHealthCheckScriptPath;

    private long hvVersion;
    private Duration timeout;
    /**
     * Since the memoryStats method returns an array that isn't ordered, we pass a big number to get all the array and then search for the information we want.
     * */
    private static final int NUMMEMSTATS = 20;

    /**
     * Unused memory's tag to search in the array returned by the Domain.memoryStats() method.
     * */
    private static final int UNUSEDMEMORY = 4;


    private KVMHAMonitor kvmhaMonitor;
    public static final String SSHPUBKEYPATH = SSHKEYSPATH + File.separator + "id_rsa.pub.cloud";
    public static final String DEFAULTDOMRSSHPORT = "3922";

    public final static String CONFIG_DIR = "config";
    private boolean enableIoUring;

    public static final String BASH_SCRIPT_PATH = "/bin/bash";

    private StorageLayer storageLayer;
    private KVMStoragePoolManager storagePoolManager;

    private VifDriver defaultVifDriver;
    private VifDriver tungstenVifDriver;
    private Map<TrafficType, VifDriver> trafficTypeVifDriverMap;

    protected static final String DEFAULT_OVS_VIF_DRIVER_CLASS_NAME = "com.cloud.hypervisor.kvm.resource.OvsVifDriver";
    protected static final String DEFAULT_BRIDGE_VIF_DRIVER_CLASS_NAME = "com.cloud.hypervisor.kvm.resource.BridgeVifDriver";
    protected static final String DEFAULT_TUNGSTEN_VIF_DRIVER_CLASS_NAME = "com.cloud.hypervisor.kvm.resource.VRouterVifDriver";
    private final static long HYPERVISOR_LIBVIRT_VERSION_SUPPORTS_IO_URING = 6003000;
    private final static long HYPERVISOR_QEMU_VERSION_SUPPORTS_IO_URING = 5000000;
    private final static long HYPERVISOR_QEMU_VERSION_IDE_DISCARD_FIXED = 7000000;

    private static final int MINIMUM_LIBVIRT_VERSION_FOR_INCREMENTAL_SNAPSHOT = 7006000;

    private static final int MINIMUM_QEMU_VERSION_FOR_INCREMENTAL_SNAPSHOT = 6001000;

    protected HypervisorType hypervisorType;
    protected String hypervisorURI;
    protected long hypervisorLibvirtVersion;
    protected long hypervisorQemuVersion;
    protected String hypervisorPath;
    protected String hostDistro;
    protected String networkDirectSourceMode;
    protected String networkDirectDevice;
    protected String sysvmISOPath;
    protected String privNwName;
    protected String privBridgeName;
    protected String linkLocalBridgeName;
    protected String publicBridgeName;
    protected String guestBridgeName;
    protected String privateIp;
    protected String pool;
    protected String localGateway;
    private boolean canBridgeFirewall;
    protected boolean noMemBalloon = false;
    protected String guestCpuArch;
    protected String guestCpuMode;
    protected String guestCpuModel;
    protected boolean noKvmClock;
    protected String videoHw;
    protected int videoRam;
    protected Pair<Integer,Integer> hostOsVersion;
    protected int migrateSpeed;
    protected int migrateDowntime;
    protected int migratePauseAfter;
    protected int migrateWait;
    protected boolean diskActivityCheckEnabled;
    protected RollingMaintenanceExecutor rollingMaintenanceExecutor;
    protected long diskActivityCheckFileSizeMin = 10485760; // 10MB
    protected int diskActivityCheckTimeoutSeconds = 120; // 120s
    protected long diskActivityInactiveThresholdMilliseconds = 30000; // 30s
    protected boolean rngEnable = false;
    protected RngBackendModel rngBackendModel = RngBackendModel.RANDOM;
    protected String rngPath = "/dev/random";
    protected int rngRatePeriod = 1000;
    protected int rngRateBytes = 2048;
    protected int manualCpuSpeed = 0;
    protected String agentHooksBasedir = "/etc/cloudstack/agent/hooks";

    protected String agentHooksLibvirtXmlScript = "libvirt-vm-xml-transformer.groovy";
    protected String agentHooksLibvirtXmlShellScript = "libvirt-vm-xml-transformer.sh";
    protected String agentHooksLibvirtXmlMethod = "transform";

    protected String agentHooksVmOnStartScript = "libvirt-vm-state-change.groovy";
    protected String agentHooksVmOnStartShellScript = "libvirt-vm-state-change.sh";
    protected String agentHooksVmOnStartMethod = "onStart";

    protected String agentHooksVmOnStopScript = "libvirt-vm-state-change.groovy";
    protected String agentHooksVmOnStopShellScript = "libvirt-vm-state-change.sh";
    protected String agentHooksVmOnStopMethod = "onStop";

    protected static final String LOCAL_STORAGE_PATH = "local.storage.path";
    protected static final String LOCAL_STORAGE_UUID = "local.storage.uuid";
    public static final String DEFAULT_LOCAL_STORAGE_PATH = "/var/lib/libvirt/images";

    protected List<String> localStoragePaths = new ArrayList<>();
    protected List<String> localStorageUUIDs = new ArrayList<>();

    private static final String CONFIG_DRIVE_ISO_DISK_LABEL = "hdd";
    private static final int CONFIG_DRIVE_ISO_DEVICE_ID = 4;

    protected File qemuSocketsPath;
    private final String qemuGuestAgentSocketName = "org.qemu.guest_agent.0";
    protected WatchDogAction watchDogAction = WatchDogAction.NONE;
    protected WatchDogModel watchDogModel = WatchDogModel.I6300ESB;

    private final Map <String, String> pifs = new HashMap<String, String>();
    private final Map<String, LibvirtExtendedVmStatsEntry> vmStats = new ConcurrentHashMap<>();

    private final Map<String, DomainBlockStats> vmDiskStats = new ConcurrentHashMap<>();

    protected static final HashMap<DomainState, PowerState> POWER_STATES_TABLE;
    static {
        POWER_STATES_TABLE = new HashMap<DomainState, PowerState>();
        POWER_STATES_TABLE.put(DomainState.VIR_DOMAIN_SHUTOFF, PowerState.PowerOff);
        POWER_STATES_TABLE.put(DomainState.VIR_DOMAIN_PAUSED, PowerState.PowerOn);
        POWER_STATES_TABLE.put(DomainState.VIR_DOMAIN_RUNNING, PowerState.PowerOn);
        POWER_STATES_TABLE.put(DomainState.VIR_DOMAIN_BLOCKED, PowerState.PowerOn);
        POWER_STATES_TABLE.put(DomainState.VIR_DOMAIN_NOSTATE, PowerState.PowerUnknown);
        POWER_STATES_TABLE.put(DomainState.VIR_DOMAIN_SHUTDOWN, PowerState.PowerOff);
    }

    public VirtualRoutingResource virtRouterResource;

    private String pingTestPath;

    private String updateHostPasswdPath;

    private long dom0MinMem;

    private long dom0OvercommitMem;

    private int dom0MinCpuCores;

    protected int cmdsTimeout;
    protected int stopTimeout;
    protected CPUStat cpuStat = new CPUStat();
    protected MemStat memStat = new MemStat(dom0MinMem, dom0OvercommitMem);
    private final LibvirtUtilitiesHelper libvirtUtilitiesHelper = new LibvirtUtilitiesHelper();
    private LibvirtDomainListener libvirtDomainListener;

    protected Boolean enableManuallySettingCpuTopologyOnKvmVm = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.ENABLE_MANUALLY_SETTING_CPU_TOPOLOGY_ON_KVM_VM);

    protected LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();

    private boolean isTungstenEnabled = false;

    private boolean isReconcileCommandsEnabled = false;

    private static Gson gson = new Gson();

    /**
     * Virsh command to set the memory balloon stats period.<br><br>
     * 1st parameter: the VM ID or name;<br>
     * 2nd parameter: the period (in seconds).
     */
    private static final String COMMAND_SET_MEM_BALLOON_STATS_PERIOD = "virsh dommemstat %s --period %s --live";

    private static int hostCpuMaxCapacity = 0;

    private static final String COMMAND_GET_CGROUP_HOST_VERSION = "stat -fc %T /sys/fs/cgroup/";

    public static final String CGROUP_V2 = "cgroup2fs";

    /**
     * Virsh command to merge (blockcommit) snapshot into the base file.<br><br>
     * 1st parameter: VM's name;<br>
     * 2nd parameter: disk's label (target.dev tag from VM's XML);<br>
     * 3rd parameter: the absolute path of the base file;
     */
    private static final String COMMAND_MERGE_SNAPSHOT = "virsh blockcommit %s %s --base %s";

    public long getHypervisorLibvirtVersion() {
        return hypervisorLibvirtVersion;
    }

    public long getHypervisorQemuVersion() {
        return hypervisorQemuVersion;
    }

    @Override
    public synchronized void registerStatusUpdater(AgentStatusUpdater updater) {
        if (AgentPropertiesFileHandler.getPropertyValue(AgentProperties.LIBVIRT_EVENTS_ENABLED)) {
            try {
                Connect conn = LibvirtConnection.getConnection();
                if (libvirtDomainListener != null) {
                    LOGGER.debug("Clearing old domain listener");
                    conn.removeLifecycleListener(libvirtDomainListener);
                }
                libvirtDomainListener = new LibvirtDomainListener(updater);
                conn.addLifecycleListener(libvirtDomainListener);
                LOGGER.debug("Set up the libvirt domain event lifecycle listener");
            } catch (LibvirtException e) {
                LOGGER.error("Failed to get libvirt connection for domain event lifecycle", e);
            }
        } else {
            LOGGER.debug("Libvirt event listening is disabled, not registering status updater");
        }
    }

    protected List<DisconnectHook> _disconnectHooks = new CopyOnWriteArrayList<>();

    @Override
    public ExecutionResult executeInVR(final String routerIp, final String script, final String args) {
        return executeInVR(routerIp, script, args, timeout);
    }

    @Override
    public ExecutionResult executeInVR(final String routerIp, final String script, final String args, final Duration timeout) {
        final Script command = new Script(routerProxyPath, timeout, LOGGER);
        final AllLinesParser parser = new AllLinesParser();
        command.add(script);
        command.add(routerIp);
        if (args != null) {
            command.add(args);
        }
        String details = command.execute(parser);
        if (details == null) {
            details = parser.getLines();
        }

        LOGGER.debug("Executing script in VR: " + script);

        return new ExecutionResult(command.getExitValue() == 0, details);
    }

    @Override
    public ExecutionResult createFileInVR(final String routerIp, final String path, final String filename, final String content) {
        final File permKey = new File("/root/.ssh/id_rsa.cloud");
        boolean success = true;
        String details = "Creating file in VR, with ip: " + routerIp + ", file: " + filename;
        LOGGER.debug(details);

        try {
            SshHelper.scpTo(routerIp, 3922, "root", permKey, null, path, content.getBytes(), filename, null);
        } catch (final Exception e) {
            LOGGER.warn("Failed to create file " + path + filename + " in VR " + routerIp, e);
            details = e.getMessage();
            success = false;
        }
        return new ExecutionResult(success, details);
    }

    @Override
    public ExecutionResult prepareCommand(final NetworkElementCommand cmd) {
        //Update IP used to access router
        cmd.setRouterAccessIp(cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP));
        assert cmd.getRouterAccessIp() != null;

        if (cmd instanceof IpAssocVpcCommand) {
            return prepareNetworkElementCommand((IpAssocVpcCommand)cmd);
        } else if (cmd instanceof IpAssocCommand) {
            return prepareNetworkElementCommand((IpAssocCommand)cmd);
        } else if (cmd instanceof SetupGuestNetworkCommand) {
            return prepareNetworkElementCommand((SetupGuestNetworkCommand)cmd);
        } else if (cmd instanceof SetSourceNatCommand) {
            return prepareNetworkElementCommand((SetSourceNatCommand)cmd);
        }
        return new ExecutionResult(true, null);
    }

    @Override
    public ExecutionResult cleanupCommand(final NetworkElementCommand cmd) {
        if (cmd instanceof IpAssocCommand && !(cmd instanceof IpAssocVpcCommand)) {
            return cleanupNetworkElementCommand((IpAssocCommand)cmd);
        }
        return new ExecutionResult(true, null);
    }

    /**
     * @return the host CPU max capacity according to the method {@link LibvirtComputingResource#calculateHostCpuMaxCapacity(int, Long)}; if the host utilizes cgroup v1, this
     * value is 0.
     */
    public int getHostCpuMaxCapacity() {
        return hostCpuMaxCapacity;
    }

    public void setHostCpuMaxCapacity(int hostCpuMaxCapacity) {
        LibvirtComputingResource.hostCpuMaxCapacity = hostCpuMaxCapacity;
    }

    public LibvirtKvmAgentHook getTransformer() throws IOException {
        return new LibvirtKvmAgentHook(agentHooksBasedir, agentHooksLibvirtXmlScript, agentHooksLibvirtXmlShellScript, agentHooksLibvirtXmlMethod);
    }

    public LibvirtKvmAgentHook getStartHook() throws IOException {
        return new LibvirtKvmAgentHook(agentHooksBasedir, agentHooksVmOnStartScript, agentHooksVmOnStartShellScript, agentHooksVmOnStartMethod);
    }

    public LibvirtKvmAgentHook getStopHook() throws IOException {
        return new LibvirtKvmAgentHook(agentHooksBasedir, agentHooksVmOnStopScript, agentHooksVmOnStopShellScript, agentHooksVmOnStopMethod);
    }

    public LibvirtUtilitiesHelper getLibvirtUtilitiesHelper() {
        return libvirtUtilitiesHelper;
    }

    public String getClusterId() {
        return clusterId;
    }

    public CPUStat getCPUStat() {
        return cpuStat;
    }

    public MemStat getMemStat() {
        memStat.refresh();
        return memStat;
    }

    public VirtualRoutingResource getVirtRouterResource() {
        return virtRouterResource;
    }

    public String getPublicBridgeName() {
        return publicBridgeName;
    }

    public KVMStoragePoolManager getStoragePoolMgr() {
        return storagePoolManager;
    }

    public String getPrivateIp() {
        return privateIp;
    }

    public int getMigrateDowntime() {
        return migrateDowntime;
    }

    public int getMigratePauseAfter() {
        return migratePauseAfter;
    }

    public int getMigrateWait() {
        return migrateWait;
    }

    public int getMigrateSpeed() {
        return migrateSpeed;
    }

    public RollingMaintenanceExecutor getRollingMaintenanceExecutor() {
        return rollingMaintenanceExecutor;
    }

    public String getPingTestPath() {
        return pingTestPath;
    }

    public String getUpdateHostPasswdPath() {
        return updateHostPasswdPath;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getOvsTunnelPath() {
        return ovsTunnelPath;
    }

    public KVMHAMonitor getMonitor() {
        return kvmhaMonitor;
    }

    public StorageLayer getStorage() {
        return storageLayer;
    }

    public String createTmplPath() {
        return createTmplPath;
    }

    public int getCmdsTimeout() {
        return cmdsTimeout;
    }

    public String manageSnapshotPath() {
        return manageSnapshotPath;
    }

    public String getGuestBridgeName() {
        return guestBridgeName;
    }

    public String getVmActivityCheckPath() {
        return vmActivityCheckPath;
    }

    public String getNasBackupPath() {
        return nasBackupPath;
    }

    public String getOvsPvlanDhcpHostPath() {
        return ovsPvlanDhcpHostPath;
    }

    public String getOvsPvlanVmPath() {
        return ovsPvlanVmPath;
    }

    public String getDirectDownloadTemporaryDownloadPath() {
        return directDownloadTemporaryDownloadPath;
    }

    public String getConfigPath() {
        return getCachePath() + "/" + CONFIG_DIR;
    }

    public String getCachePath() {
        return cachePath;
    }

    public String getResizeVolumePath() {
        return resizeVolumePath;
    }

    public StorageSubsystemCommandHandler getStorageHandler() {
        return storageHandler;
    }

    private static final class KeyValueInterpreter extends OutputInterpreter {
        private final Map<String, String> map = new HashMap<String, String>();

        @Override
        public String interpret(final BufferedReader reader) throws IOException {
            String line = null;
            int numLines = 0;
            while ((line = reader.readLine()) != null) {
                final String[] toks = line.trim().split("=");
                if (toks.length < 2) {
                    LOGGER.warn("Failed to parse Script output: " + line);
                } else {
                    map.put(toks[0].trim(), toks[1].trim());
                }
                numLines++;
            }
            if (numLines == 0) {
                LOGGER.warn("KeyValueInterpreter: no output lines?");
            }
            return null;
        }

        public Map<String, String> getKeyValues() {
            return map;
        }
    }

    @Override
    protected String getDefaultScriptsDir() {
        return null;
    }

    protected List<String> cpuFeatures;

    protected enum BridgeType {
        NATIVE, OPENVSWITCH, TUNGSTEN
    }

    protected enum HealthCheckResult {
        SUCCESS, FAILURE, IGNORE
    }

    protected BridgeType bridgeType;

    protected StorageSubsystemCommandHandler storageHandler;

    private boolean convertInstanceVerboseMode = false;
    protected boolean dpdkSupport = false;
    protected String dpdkOvsPath;
    protected String directDownloadTemporaryDownloadPath;
    protected String cachePath;
    protected String javaTempDir = System.getProperty("java.io.tmpdir");

    private String getEndIpFromStartIp(final String startIp, final int numIps) {
        final String[] tokens = startIp.split("[.]");
        assert tokens.length == 4;
        int lastbyte = Integer.parseInt(tokens[3]);
        lastbyte = lastbyte + numIps;
        tokens[3] = Integer.toString(lastbyte);
        final StringBuilder end = new StringBuilder(15);
        end.append(tokens[0]).append(".").append(tokens[1]).append(".").append(tokens[2]).append(".").append(tokens[3]);
        return end.toString();
    }

    private Map<String, Object> getDeveloperProperties() throws ConfigurationException {

        final File file = PropertiesUtil.findConfigFile("developer.properties");
        if (file == null) {
            throw new ConfigurationException("Unable to find developer.properties.");
        }

        LOGGER.info("developer.properties found at " + file.getAbsolutePath());
        try {
            final Properties properties = PropertiesUtil.loadFromFile(file);

            final String startMac = (String)properties.get("private.macaddr.start");
            if (startMac == null) {
                throw new ConfigurationException("Developers must specify start mac for private ip range");
            }

            final String startIp = (String)properties.get("private.ipaddr.start");
            if (startIp == null) {
                throw new ConfigurationException("Developers must specify start ip for private ip range");
            }
            final Map<String, Object> params = PropertiesUtil.toMap(properties);

            String endIp = (String)properties.get("private.ipaddr.end");
            if (endIp == null) {
                endIp = getEndIpFromStartIp(startIp, 16);
                params.put("private.ipaddr.end", endIp);
            }
            return params;
        } catch (final FileNotFoundException ex) {
            throw new CloudRuntimeException("Cannot find the file: " + file.getAbsolutePath(), ex);
        } catch (final IOException ex) {
            throw new CloudRuntimeException("IOException in reading " + file.getAbsolutePath(), ex);
        }
    }

    protected String getNetworkDirectSourceMode() {
        return networkDirectSourceMode;
    }

    protected String getNetworkDirectDevice() {
        return networkDirectDevice;
    }

    public boolean isConvertInstanceVerboseModeEnabled() {
        return convertInstanceVerboseMode;
    }

    /**
     * Defines resource's public and private network interface according to what is configured in agent.properties.
     */
    @Override
    protected void defineResourceNetworkInterfaces(Map<String, Object> params) {
        privBridgeName = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.PRIVATE_NETWORK_DEVICE);
        publicBridgeName = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.PUBLIC_NETWORK_DEVICE);

        privateNic = NetUtils.getNetworkInterface(privBridgeName);
        publicNic = NetUtils.getNetworkInterface(publicBridgeName);
    }

    public NetworkInterface getPrivateNic() {
        return privateNic;
    }

    public NetworkInterface getPublicNic() {
        return publicNic;
    }

    protected String getDefaultTungstenScriptsDir() {
        return TUNGSTEN_PATH;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        boolean success = super.configure(name, params);
        if (!success) {
            return false;
        }
        try {
            loadUefiProperties();
        } catch (FileNotFoundException e) {
            LOGGER.error("uefi properties file not found due to: " + e.getLocalizedMessage());
        }

        storageLayer = new JavaStorageLayer();
        storageLayer.configure("StorageLayer", params);

        String domrScriptsDir = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.DOMR_SCRIPTS_DIR);

        String hypervisorScriptsDir = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.HYPERVISOR_SCRIPTS_DIR);

        String kvmScriptsDir = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.KVM_SCRIPTS_DIR);

        String networkScriptsDir = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.NETWORK_SCRIPTS_DIR);

        String storageScriptsDir = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.STORAGE_SCRIPTS_DIR);

        String tungstenScriptsDir = (String) params.get("tungsten.scripts.dir");
        if (tungstenScriptsDir == null) {
            tungstenScriptsDir = getDefaultTungstenScriptsDir();
        }

        final String bridgeType = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.NETWORK_BRIDGE_TYPE);
        if (bridgeType == null) {
            this.bridgeType = BridgeType.NATIVE;
        } else {
            this.bridgeType = BridgeType.valueOf(bridgeType.toUpperCase());
        }

        Boolean dpdk = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.OPENVSWITCH_DPDK_ENABLED);
        if (this.bridgeType == BridgeType.OPENVSWITCH && BooleanUtils.isTrue(dpdk)) {
            dpdkSupport = true;
            dpdkOvsPath = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.OPENVSWITCH_DPDK_OVS_PATH);
            if (dpdkOvsPath != null && !dpdkOvsPath.endsWith("/")) {
                dpdkOvsPath += "/";
            }
        }

        directDownloadTemporaryDownloadPath = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.DIRECT_DOWNLOAD_TEMPORARY_DOWNLOAD_LOCATION);

        cachePath = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.HOST_CACHE_LOCATION);

        params.put("domr.scripts.dir", domrScriptsDir);

        virtRouterResource = new VirtualRoutingResource(this);
        success = virtRouterResource.configure(name, params);

        if (!success) {
            return false;
        }

        dcId = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.ZONE);

        clusterId = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.CLUSTER);

        updateHostPasswdPath = Script.findScript(hypervisorScriptsDir, VRScripts.UPDATE_HOST_PASSWD);
        if (updateHostPasswdPath == null) {
            throw new ConfigurationException("Unable to find update_host_passwd.sh");
        }

        modifyVlanPath = Script.findScript(networkScriptsDir, "modifyvlan.sh");
        if (modifyVlanPath == null) {
            throw new ConfigurationException("Unable to find modifyvlan.sh");
        }

        versionStringPath = Script.findScript(kvmScriptsDir, "versions.sh");
        if (versionStringPath == null) {
            throw new ConfigurationException("Unable to find versions.sh");
        }

        gpuDiscoveryPath = Script.findScript(kvmScriptsDir, "gpudiscovery.sh");
        if (gpuDiscoveryPath == null) {
            throw new ConfigurationException("Unable to find gpudiscovery.sh");
        }

        patchScriptPath = Script.findScript(kvmScriptsDir, "patch.sh");
        if (patchScriptPath == null) {
            throw new ConfigurationException("Unable to find patch.sh");
        }

        heartBeatPath = Script.findScript(kvmScriptsDir, "kvmheartbeat.sh");
        if (heartBeatPath == null) {
            throw new ConfigurationException("Unable to find kvmheartbeat.sh");
        }

        createVmPath = Script.findScript(storageScriptsDir, "createvm.sh");
        if (createVmPath == null) {
            throw new ConfigurationException("Unable to find the createvm.sh");
        }

        manageSnapshotPath = Script.findScript(storageScriptsDir, "managesnapshot.sh");
        if (manageSnapshotPath == null) {
            throw new ConfigurationException("Unable to find the managesnapshot.sh");
        }

        resizeVolumePath = Script.findScript(storageScriptsDir, "resizevolume.sh");
        if (resizeVolumePath == null) {
            throw new ConfigurationException("Unable to find the resizevolume.sh");
        }

        vmActivityCheckPath = Script.findScript(kvmScriptsDir, "kvmvmactivity.sh");
        if (vmActivityCheckPath == null) {
            throw new ConfigurationException("Unable to find kvmvmactivity.sh");
        }

        nasBackupPath = Script.findScript(kvmScriptsDir, "nasbackup.sh");
        if (nasBackupPath == null) {
            throw new ConfigurationException("Unable to find nasbackup.sh");
        }

        createTmplPath = Script.findScript(storageScriptsDir, "createtmplt.sh");
        if (createTmplPath == null) {
            throw new ConfigurationException("Unable to find the createtmplt.sh");
        }

        securityGroupPath = Script.findScript(networkScriptsDir, "security_group.py");
        if (securityGroupPath == null) {
            throw new ConfigurationException("Unable to find the security_group.py");
        }

        ovsTunnelPath = Script.findScript(networkScriptsDir, "ovstunnel.py");
        if (ovsTunnelPath == null) {
            throw new ConfigurationException("Unable to find the ovstunnel.py");
        }

        routerProxyPath = Script.findScript("scripts/network/domr/", "router_proxy.sh");
        if (routerProxyPath == null) {
            throw new ConfigurationException("Unable to find the router_proxy.sh");
        }

        ovsPvlanDhcpHostPath = Script.findScript(networkScriptsDir, "ovs-pvlan-kvm-dhcp-host.sh");
        if (ovsPvlanDhcpHostPath == null) {
            throw new ConfigurationException("Unable to find the ovs-pvlan-kvm-dhcp-host.sh");
        }

        ovsPvlanVmPath = Script.findScript(networkScriptsDir, "ovs-pvlan-kvm-vm.sh");
        if (ovsPvlanVmPath == null) {
            throw new ConfigurationException("Unable to find the ovs-pvlan-kvm-vm.sh");
        }

        hostHealthCheckScriptPath = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.HEALTH_CHECK_SCRIPT_PATH);
        if (StringUtils.isNotBlank(hostHealthCheckScriptPath) && !new File(hostHealthCheckScriptPath).exists()) {
            LOGGER.info(String.format("Unable to find the host health check script at: %s, " +
                    "discarding it", hostHealthCheckScriptPath));
        }

        setupTungstenVrouterPath = Script.findScript(tungstenScriptsDir, "setup_tungsten_vrouter.sh");
        if (setupTungstenVrouterPath == null) {
            throw new ConfigurationException("Unable to find the setup_tungsten_vrouter.sh");
        }

        updateTungstenLoadbalancerStatsPath = Script.findScript(tungstenScriptsDir, "update_tungsten_loadbalancer_stats.sh");
        if (updateTungstenLoadbalancerStatsPath == null) {
            throw new ConfigurationException("Unable to find the update_tungsten_loadbalancer_stats.sh");
        }

        updateTungstenLoadbalancerSslPath = Script.findScript(tungstenScriptsDir, "update_tungsten_loadbalancer_ssl.sh");
        if (updateTungstenLoadbalancerSslPath == null) {
            throw new ConfigurationException("Unable to find the update_tungsten_loadbalancer_ssl.sh");
        }

        final boolean isDeveloper = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.DEVELOPER);
        if (isDeveloper) {
            params.putAll(getDeveloperProperties());
        }

        convertInstanceVerboseMode = BooleanUtils.isTrue(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VIRTV2V_VERBOSE_ENABLED));

        pool = (String)params.get("pool");
        if (pool == null) {
            pool = "/root";
        }

        final String instance = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.INSTANCE);

        hypervisorType = HypervisorType.getType(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.HYPERVISOR_TYPE));

        String hooksDir = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.ROLLING_MAINTENANCE_HOOKS_DIR);
        rollingMaintenanceExecutor = BooleanUtils.isTrue(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.ROLLING_MAINTENANCE_SERVICE_EXECUTOR_DISABLED)) ? new RollingMaintenanceAgentExecutor(hooksDir) :
                new RollingMaintenanceServiceExecutor(hooksDir);

        hypervisorURI = LibvirtConnection.getHypervisorURI(hypervisorType.toString());

        networkDirectSourceMode = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.NETWORK_DIRECT_SOURCE_MODE);
        networkDirectDevice = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.NETWORK_DIRECT_DEVICE);

        pingTestPath = Script.findScript(kvmScriptsDir, "pingtest.sh");
        if (pingTestPath == null) {
            throw new ConfigurationException("Unable to find the pingtest.sh");
        }

        linkLocalBridgeName = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.PRIVATE_BRIDGE_NAME);
        if (linkLocalBridgeName == null) {
            if (isDeveloper) {
                linkLocalBridgeName = "cloud-" + instance + "-0";
            } else {
                linkLocalBridgeName = "cloud0";
            }
        }

        guestBridgeName = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.GUEST_NETWORK_DEVICE);
        if (guestBridgeName == null) {
            guestBridgeName = privBridgeName;
        }

        privNwName = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.PRIVATE_NETWORK_NAME);
        if (privNwName == null) {
            if (isDeveloper) {
                privNwName = "cloud-" + instance + "-private";
            } else {
                privNwName = "cloud-private";
            }
        }

        enableSSLForKvmAgent();
        configureLocalStorage();

        /* Directory to use for Qemu sockets like for the Qemu Guest Agent */
        String qemuSocketsPathVar = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.QEMU_SOCKETS_PATH);
        qemuSocketsPath = new File(qemuSocketsPathVar);

        // This value is never set. Default value is always used.
        String value = (String)params.get("scripts.timeout");
        timeout = Duration.standardSeconds(NumbersUtil.parseInt(value, 30 * 60));

        stopTimeout = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.STOP_SCRIPT_TIMEOUT) * 1000;

        cmdsTimeout = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.CMDS_TIMEOUT) * 1000;

        noMemBalloon = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_MEMBALLOON_DISABLE);
        qcow2DeltaMergeTimeout = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.QCOW2_DELTA_MERGE_TIMEOUT);
        qcow2DeltaMergeTimeout = qcow2DeltaMergeTimeout > 0 ? qcow2DeltaMergeTimeout : AgentProperties.QCOW2_DELTA_MERGE_TIMEOUT.getDefaultValue();

        manualCpuSpeed = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.HOST_CPU_MANUAL_SPEED_MHZ);

        videoHw = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_VIDEO_HARDWARE);

        videoRam = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_VIDEO_RAM);

        // Reserve 1GB unless admin overrides
        dom0MinMem = ByteScaleUtils.mebibytesToBytes(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.HOST_RESERVED_MEM_MB));

        dom0MinCpuCores = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.HOST_RESERVED_CPU_CORE_COUNT);

        // Support overcommit memory for host if host uses ZSWAP, KSM and other memory
        // compressing technologies
        dom0OvercommitMem = ByteScaleUtils.mebibytesToBytes(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.HOST_OVERCOMMIT_MEM_MB));

        if (BooleanUtils.isTrue(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.KVMCLOCK_DISABLE))) {
            noKvmClock = true;
        }

        if (BooleanUtils.isTrue(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_RNG_ENABLE))) {
            rngEnable = true;

            rngBackendModel = RngBackendModel.valueOf(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_RNG_MODEL).toUpperCase());

            rngPath = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_RNG_PATH);

            rngRateBytes = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_RNG_RATE_BYTES);

            rngRatePeriod = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_RNG_RATE_PERIOD);
        }

        watchDogModel = WatchDogModel.valueOf(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_WATCHDOG_MODEL).toUpperCase());

        watchDogAction = WatchDogAction.valueOf(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_WATCHDOG_ACTION).toUpperCase());

        LibvirtConnection.initialize(hypervisorURI);
        Connect conn = null;
        try {
            conn = LibvirtConnection.getConnection();

            if (this.bridgeType == BridgeType.OPENVSWITCH) {
                if (conn.getLibVirVersion() < 10 * 1000 + 0) {
                    throw new ConfigurationException("Libvirt version 0.10.0 required for openvswitch support, but version " + conn.getLibVirVersion() + " detected");
                }
            }
        } catch (final LibvirtException e) {
            throw new CloudRuntimeException(e.getMessage());
        }

        // destroy default network, see https://libvirt.org/sources/java/javadoc/org/libvirt/Network.html
        try {
            Network network = conn.networkLookupByName("default");
            LOGGER.debug("Found libvirt default network, destroying it and setting autostart to false");
            if (network.isActive() == 1) {
                network.destroy();
            }
            if (network.getAutostart()) {
                network.setAutostart(false);
            }
        } catch (final LibvirtException e) {
            LOGGER.warn("Ignoring libvirt error.", e);
        }

        if (HypervisorType.KVM == hypervisorType) {
            /* Does node support HVM guest? If not, exit */
            if (!IsHVMEnabled(conn)) {
                throw new ConfigurationException("NO HVM support on this machine, please make sure: " + "1. VT/SVM is supported by your CPU, or is enabled in BIOS. "
                        + "2. kvm modules are loaded (kvm, kvm_amd|kvm_intel)");
            }
        }

        hypervisorPath = getHypervisorPath(conn);
        try {
            hvVersion = conn.getVersion();
            hvVersion = hvVersion % 1000000 / 1000;
            hypervisorLibvirtVersion = conn.getLibVirVersion();
            hypervisorQemuVersion = conn.getVersion();
        } catch (final LibvirtException e) {
            LOGGER.trace("Ignoring libvirt error.", e);
        }

        // Enable/disable IO driver for Qemu (in case it is not set CloudStack can also detect if its supported by qemu)
        enableIoUring = isIoUringEnabled();
        LOGGER.info("IO uring driver for Qemu: " + (enableIoUring ? "enabled" : "disabled"));

        final String cpuArchOverride = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.GUEST_CPU_ARCH);
        if (StringUtils.isNotEmpty(cpuArchOverride)) {
            guestCpuArch = cpuArchOverride;
            LOGGER.info("Using guest CPU architecture: " + guestCpuArch);
        }

        guestCpuMode = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.GUEST_CPU_MODE);
        if (guestCpuMode != null) {
            guestCpuModel = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.GUEST_CPU_MODEL);

            if (hypervisorLibvirtVersion < 9 * 1000 + 10) {
                LOGGER.warn("Libvirt version 0.9.10 required for guest cpu mode, but version " + prettyVersion(hypervisorLibvirtVersion) +
                        " detected, so it will be disabled");
                guestCpuMode = "";
                guestCpuModel = "";
            }
            params.put("guest.cpu.mode", guestCpuMode);
            params.put("guest.cpu.model", guestCpuModel);
        }

        final String cpuFeatures = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.GUEST_CPU_FEATURES);
        if (cpuFeatures != null) {
            this.cpuFeatures = new ArrayList<String>();
            for (final String feature: cpuFeatures.split(" ")) {
                if (!feature.isEmpty()) {
                    this.cpuFeatures.add(feature);
                }
            }
        }

        final String[] info = NetUtils.getNetworkParams(privateNic);

        kvmhaMonitor = new KVMHAMonitor(null, info[0], heartBeatPath);
        final Thread ha = new Thread(kvmhaMonitor);
        ha.start();

        storagePoolManager = new KVMStoragePoolManager(storageLayer, kvmhaMonitor);

        final Map<String, String> bridges = new HashMap<String, String>();

        params.put("libvirt.host.bridges", bridges);
        params.put("libvirt.host.pifs", pifs);

        params.put("libvirt.computing.resource", this);
        params.put("libvirtVersion", hypervisorLibvirtVersion);


        configureVifDrivers(params);

        /*
        switch (_bridgeType) {
        case OPENVSWITCH:
            getOvsPifs();
            break;
        case NATIVE:
        default:
            getPifs();
            break;
        }
        */

        if (pifs.get("private") == null) {
            LOGGER.error("Failed to get private nic name");
            throw new ConfigurationException("Failed to get private nic name");
        }

        if (pifs.get("public") == null) {
            LOGGER.error("Failed to get public nic name");
            throw new ConfigurationException("Failed to get public nic name");
        }
        LOGGER.debug("Found pif: " + pifs.get("private") + " on " + privBridgeName + ", pif: " + pifs.get("public") + " on " + publicBridgeName);

        canBridgeFirewall = canBridgeFirewall(pifs.get("public"));

        localGateway = Script.runSimpleBashScript("ip route show default 0.0.0.0/0|head -1|awk '{print $3}'");
        if (localGateway == null) {
            LOGGER.warn("No default IPv4 gateway found");
        }

        migrateDowntime = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_MIGRATE_DOWNTIME);

        migratePauseAfter = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_MIGRATE_PAUSEAFTER);

        migrateWait = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_MIGRATE_WAIT);

        configureAgentHooks();

        migrateSpeed = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_MIGRATE_SPEED);
        if (migrateSpeed == -1) {
            //get guest network device speed
            migrateSpeed = 0;
            final String speed = Script.runSimpleBashScript("ethtool " + pifs.get("public") + " |grep Speed | cut -d \\  -f 2");
            if (speed != null) {
                final String[] tokens = speed.split("M");
                if (tokens.length == 2) {
                    try {
                        migrateSpeed = Integer.parseInt(tokens[0]);
                    } catch (final NumberFormatException e) {
                        LOGGER.trace("Ignoring migrateSpeed extraction error.", e);
                    }
                    LOGGER.debug("device " + pifs.get("public") + " has speed: " + String.valueOf(migrateSpeed));
                }
            }
            params.put("vm.migrate.speed", String.valueOf(migrateSpeed));
        }

        bridges.put("linklocal", linkLocalBridgeName);
        bridges.put("public", publicBridgeName);
        bridges.put("private", privBridgeName);
        bridges.put("guest", guestBridgeName);

        getVifDriver(TrafficType.Control).createControlNetwork(linkLocalBridgeName);

        configureDiskActivityChecks();

        final KVMStorageProcessor storageProcessor = new KVMStorageProcessor(storagePoolManager, this);
        storageProcessor.configure(name, params);
        storageHandler = new StorageSubsystemCommandHandlerBase(storageProcessor);

        Boolean iscsiCleanUpEnabled = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.ISCSI_SESSION_CLEANUP_ENABLED);

        if (BooleanUtils.isTrue(iscsiCleanUpEnabled)) {
            IscsiStorageCleanupMonitor isciCleanupMonitor = new IscsiStorageCleanupMonitor();
            final Thread cleanupMonitor = new Thread(isciCleanupMonitor);
            cleanupMonitor.start();
        } else {
            LOGGER.info("iscsi session clean up is disabled");
        }

        setupMemoryBalloonStatsPeriod(conn);

        return true;
    }

    /**
     * Gets the ID list of the VMs to set memory balloon stats period.
     * @param conn the Libvirt connection.
     * @return the list of VM IDs.
     */
    protected List<Integer> getVmsToSetMemoryBalloonStatsPeriod(Connect conn) {
        List<Integer> vmIdList = new ArrayList<>();
        Integer[] vmIds = null;
        try {
            vmIds = ArrayUtils.toObject(conn.listDomains());
        } catch (final LibvirtException e) {
            LOGGER.error("Unable to get the list of Libvirt domains on this host.", e);
            return vmIdList;
        }
        vmIdList.addAll(Arrays.asList(vmIds));
        LOGGER.debug(String.format("We have found a total of [%s] VMs (Libvirt domains) on this host: [%s].", vmIdList.size(), vmIdList.toString()));

        if (vmIdList.isEmpty()) {
            LOGGER.info("Skipping the memory balloon stats period setting, since there are no VMs (active Libvirt domains) on this host.");
        }
        return vmIdList;
    }

    /**
     * Gets the current VM balloon stats period from the agent.properties file.
     * @return the current VM balloon stats period.
     */
    protected Integer getCurrentVmBalloonStatsPeriod() {
        if (Boolean.TRUE.equals(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_MEMBALLOON_DISABLE))) {
            LOGGER.info(String.format("The [%s] property is set to 'true', so the memory balloon stats period will be set to 0 for all VMs.",
                    AgentProperties.VM_MEMBALLOON_DISABLE.getName()));
            return 0;
        }
        Integer vmBalloonStatsPeriod = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_MEMBALLOON_STATS_PERIOD);
        if (vmBalloonStatsPeriod == 0) {
            LOGGER.info(String.format("The [%s] property is set to '0', this prevents memory statistics from being displayed correctly. "
                    + "Adjust (increase) the value of this parameter to correct this.", AgentProperties.VM_MEMBALLOON_STATS_PERIOD.getName()));
        }
        return vmBalloonStatsPeriod;
    }

    /**
     * Sets the balloon driver of each VM to get the memory stats at the time interval defined in the agent.properties file.
     * @param conn the Libvirt connection.
     */
    protected void setupMemoryBalloonStatsPeriod(Connect conn) {
        List<Integer> vmIdList = getVmsToSetMemoryBalloonStatsPeriod(conn);
        Integer currentVmBalloonStatsPeriod = getCurrentVmBalloonStatsPeriod();
        for (Integer vmId : vmIdList) {
            Domain dm = null;
            try {
                dm = conn.domainLookupByID(vmId);
                parser.parseDomainXML(dm.getXMLDesc(0));
                MemBalloonDef memBalloon = parser.getMemBalloon();
                if (!MemBalloonDef.MemBalloonModel.VIRTIO.equals(memBalloon.getMemBalloonModel())) {
                    LOGGER.debug(String.format("Skipping the memory balloon stats period setting for the VM (Libvirt Domain) with ID [%s] and name [%s] because this VM has no memory"
                            + " balloon.", vmId, dm.getName()));
                }
                String setMemBalloonStatsPeriodCommand = String.format(COMMAND_SET_MEM_BALLOON_STATS_PERIOD, vmId, currentVmBalloonStatsPeriod);
                String setMemBalloonStatsPeriodResult = Script.runSimpleBashScript(setMemBalloonStatsPeriodCommand);
                if (StringUtils.isNotBlank(setMemBalloonStatsPeriodResult)) {
                    LOGGER.error(String.format("Unable to set up memory balloon stats period for VM (Libvirt Domain) with ID [%s] due to an error when running the [%s] "
                            + "command. Output: [%s].", vmId, setMemBalloonStatsPeriodCommand, setMemBalloonStatsPeriodResult));
                    continue;
                }
                LOGGER.debug(String.format("The memory balloon stats period [%s] has been set successfully for the VM (Libvirt Domain) with ID [%s] and name [%s].",
                        currentVmBalloonStatsPeriod, vmId, dm.getName()));
            } catch (final Exception e) {
                LOGGER.warn(String.format("Failed to set up memory balloon stats period for the VM %s with exception %s", parser.getName(), e.getMessage()));
            }
        }
    }

    private void enableSSLForKvmAgent() {
        final File keyStoreFile = PropertiesUtil.findConfigFile(KeyStoreUtils.KS_FILENAME);
        if (keyStoreFile == null) {
            LOGGER.info("Failed to find keystore file: " + KeyStoreUtils.KS_FILENAME);
            return;
        }
        String keystorePass = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.KEYSTORE_PASSPHRASE);
        if (StringUtils.isBlank(keystorePass)) {
            LOGGER.info("Failed to find passphrase for keystore: " + KeyStoreUtils.KS_FILENAME);
            return;
        }
        if (keyStoreFile.exists() && !keyStoreFile.isDirectory()) {
            System.setProperty("javax.net.ssl.trustStore", keyStoreFile.getAbsolutePath());
            System.setProperty("javax.net.ssl.trustStorePassword", keystorePass);
        }
    }

    protected void configureLocalStorage() throws ConfigurationException {
        String localStoragePath = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.LOCAL_STORAGE_PATH);
        LOGGER.debug(String.format("Local Storage Path set: [%s].", localStoragePath));

        String localStorageUUIDString = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.LOCAL_STORAGE_UUID);
        if (localStorageUUIDString == null) {
            localStorageUUIDString = UUID.randomUUID().toString();
        }
        LOGGER.debug(String.format("Local Storage UUID set: [%s].", localStorageUUIDString));

        String[] localStorageRelativePaths = localStoragePath.split(CONFIG_VALUES_SEPARATOR);
        String[] localStorageUUIDStrings = localStorageUUIDString.split(CONFIG_VALUES_SEPARATOR);
        if (localStorageRelativePaths.length != localStorageUUIDStrings.length) {
            String errorMessage = String.format("The path and UUID of the local storage pools have different length. Path: [%s], UUID: [%s].", localStoragePath,
                localStorageUUIDString);
            LOGGER.error(errorMessage);
            throw new ConfigurationException(errorMessage);
        }
        for (String localStorageRelativePath : localStorageRelativePaths) {
            final File storagePath = new File(localStorageRelativePath);
            localStoragePaths.add(storagePath.getAbsolutePath());
        }

        for (String localStorageUUID : localStorageUUIDStrings) {
            validateLocalStorageUUID(localStorageUUID);
            localStorageUUIDs.add(localStorageUUID);
        }
    }

    private void validateLocalStorageUUID(String localStorageUUID) throws ConfigurationException {
        if (StringUtils.isBlank(localStorageUUID)) {
            throw new ConfigurationException("The UUID of local storage pools must be non-blank");
        }
        try {
            UUID.fromString(localStorageUUID);
        } catch (IllegalArgumentException ex) {
            throw new ConfigurationException("The UUID of local storage pool is invalid : " + localStorageUUID);
        }
    }

    public boolean configureHostParams(final Map<String, String> params) {
        final File file = PropertiesUtil.findConfigFile("agent.properties");
        if (file == null) {
            LOGGER.error("Unable to find the file agent.properties");
            return false;
        }
        // Save configurations in agent.properties
        PropertiesStorage storage = new PropertiesStorage();
        storage.configure("Storage", new HashMap<String, Object>());
        Long longValue = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.ROUTER_AGGREGATION_COMMAND_EACH_TIMEOUT);
        if (longValue != null) {
            storage.persist(AgentProperties.ROUTER_AGGREGATION_COMMAND_EACH_TIMEOUT.getName(), String.valueOf(longValue));
        }

        if (params.get(Config.MigrateWait.toString()) != null) {
            String value = (String)params.get(Config.MigrateWait.toString());
            Integer intValue = NumbersUtil.parseInt(value, -1);
            storage.persist("vm.migrate.wait", String.valueOf(intValue));
            migrateWait = intValue;
        }

        if (params.get(NetworkOrchestrationService.TUNGSTEN_ENABLED.key()) != null) {
            isTungstenEnabled = Boolean.parseBoolean(params.get(NetworkOrchestrationService.TUNGSTEN_ENABLED.key()));
        }

        if (params.get(ReconcileCommandService.ReconcileCommandsEnabled.key()) != null) {
            isReconcileCommandsEnabled = Boolean.parseBoolean(params.get(ReconcileCommandService.ReconcileCommandsEnabled.key()));
        }
        if (isReconcileCommandsEnabled) {
            File commandsLogPath = new File(COMMANDS_LOG_PATH);
            if (!commandsLogPath.exists()) {
                commandsLogPath.mkdirs();
            }
            // Update state of reconcile commands
            getCommandInfosFromLogFiles(true);
        }

        return true;
    }

    private void configureAgentHooks() {
        agentHooksBasedir = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_BASEDIR);
        LOGGER.debug("agent.hooks.basedir is " + agentHooksBasedir);

        agentHooksLibvirtXmlScript = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_LIBVIRT_VM_XML_TRANSFORMER_SCRIPT);
        LOGGER.debug("agent.hooks.libvirt_vm_xml_transformer.script is " + agentHooksLibvirtXmlScript);

        agentHooksLibvirtXmlShellScript = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_LIBVIRT_VM_XML_TRANSFORMER_SHELL_SCRIPT);
        LOGGER.debug("agent.hooks.libvirt_vm_xml_transformer.shell_script is " + agentHooksLibvirtXmlShellScript);

        agentHooksLibvirtXmlMethod = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_LIBVIRT_VM_XML_TRANSFORMER_METHOD);
        LOGGER.debug("agent.hooks.libvirt_vm_xml_transformer.method is " + agentHooksLibvirtXmlMethod);

        agentHooksVmOnStartScript = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_LIBVIRT_VM_ON_START_SCRIPT);
        LOGGER.debug("agent.hooks.libvirt_vm_on_start.script is " + agentHooksVmOnStartScript);

        agentHooksVmOnStartShellScript = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_LIBVIRT_VM_ON_START_SHELL_SCRIPT);
        LOGGER.debug("agent.hooks.libvirt_vm_on_start.shell_script is " + agentHooksVmOnStartShellScript);

        agentHooksVmOnStartMethod = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_LIBVIRT_VM_ON_START_METHOD);
        LOGGER.debug("agent.hooks.libvirt_vm_on_start.method is " + agentHooksVmOnStartMethod);

        agentHooksVmOnStopScript = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_LIBVIRT_VM_ON_STOP_SCRIPT);
        LOGGER.debug("agent.hooks.libvirt_vm_on_stop.script is " + agentHooksVmOnStopScript);

        agentHooksVmOnStopShellScript = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_LIBVIRT_VM_ON_STOP_SHELL_SCRIPT);
        LOGGER.debug("agent.hooks.libvirt_vm_on_stop.shell_script is " + agentHooksVmOnStopShellScript);

        agentHooksVmOnStopMethod = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.AGENT_HOOKS_LIBVIRT_VM_ON_STOP_METHOD);
        LOGGER.debug("agent.hooks.libvirt_vm_on_stop.method is " + agentHooksVmOnStopMethod);
    }

    public boolean isUefiPropertiesFileLoaded() {
        return !uefiProperties.isEmpty();
    }

    private void loadUefiProperties() throws FileNotFoundException {

        if (isUefiPropertiesFileLoaded()) {
            return;
        }
        final File file = PropertiesUtil.findConfigFile("uefi.properties");
        if (file == null) {
            throw new FileNotFoundException("Unable to find file uefi.properties.");
        }

        LOGGER.info("uefi.properties file found at " + file.getAbsolutePath());
        try {
            PropertiesUtil.loadFromFile(uefiProperties, file);
            LOGGER.info("guest.nvram.template.legacy = " + uefiProperties.getProperty("guest.nvram.template.legacy"));
            LOGGER.info("guest.loader.legacy = " + uefiProperties.getProperty("guest.loader.legacy"));
            LOGGER.info("guest.nvram.template.secure = " + uefiProperties.getProperty("guest.nvram.template.secure"));
            LOGGER.info("guest.loader.secure =" + uefiProperties.getProperty("guest.loader.secure"));
            LOGGER.info("guest.nvram.path = " + uefiProperties.getProperty("guest.nvram.path"));
        } catch (final FileNotFoundException ex) {
            throw new CloudRuntimeException("Cannot find the file: " + file.getAbsolutePath(), ex);
        } catch (final IOException ex) {
            throw new CloudRuntimeException("IOException in reading " + file.getAbsolutePath(), ex);
        }
    }

    protected void configureDiskActivityChecks() {
        diskActivityCheckEnabled = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_DISKACTIVITY_CHECKENABLED);
        if (diskActivityCheckEnabled) {
            final int timeout = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_DISKACTIVITY_CHECKTIMEOUT_S);
            if (timeout > 0) {
                diskActivityCheckTimeoutSeconds = timeout;
            }
            final long inactiveTime = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.VM_DISKACTIVITY_INACTIVETIME_MS);
            if (inactiveTime > 0) {
                diskActivityInactiveThresholdMilliseconds = inactiveTime;
            }
        }
    }

    protected void configureVifDrivers(final Map<String, Object> params) throws ConfigurationException {
        final String LIBVIRT_VIF_DRIVER = "libvirt.vif.driver";

        trafficTypeVifDriverMap = new HashMap<TrafficType, VifDriver>();

        // Load the default vif driver
        String defaultVifDriverName = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.LIBVIRT_VIF_DRIVER);
        if (defaultVifDriverName == null) {
            if (bridgeType == BridgeType.OPENVSWITCH) {
                LOGGER.info("No libvirt.vif.driver specified. Defaults to OvsVifDriver.");
                defaultVifDriverName = DEFAULT_OVS_VIF_DRIVER_CLASS_NAME;
            } else {
                LOGGER.info("No libvirt.vif.driver specified. Defaults to BridgeVifDriver.");
                defaultVifDriverName = DEFAULT_BRIDGE_VIF_DRIVER_CLASS_NAME;
            }
        }
        defaultVifDriver = getVifDriverClass(defaultVifDriverName, params);
        tungstenVifDriver = getVifDriverClass(DEFAULT_TUNGSTEN_VIF_DRIVER_CLASS_NAME, params);

        // Load any per-traffic-type vif drivers
        for (final Map.Entry<String, Object> entry : params.entrySet()) {
            final String k = entry.getKey();
            final String vifDriverPrefix = LIBVIRT_VIF_DRIVER + ".";

            if (k.startsWith(vifDriverPrefix)) {
                // Get trafficType
                final String trafficTypeSuffix = k.substring(vifDriverPrefix.length());

                // Does this suffix match a real traffic type?
                final TrafficType trafficType = TrafficType.getTrafficType(trafficTypeSuffix);
                if (!trafficType.equals(TrafficType.None)) {
                    // Get vif driver class name
                    final String vifDriverClassName = (String)entry.getValue();
                    // if value is null, ignore
                    if (vifDriverClassName != null) {
                        // add traffic type to vif driver mapping to Map
                        trafficTypeVifDriverMap.put(trafficType, getVifDriverClass(vifDriverClassName, params));
                    }
                }
            }
        }
    }

    protected VifDriver getVifDriverClass(final String vifDriverClassName, final Map<String, Object> params) throws ConfigurationException {
        VifDriver vifDriver;

        try {
            final Class<?> clazz = Class.forName(vifDriverClassName);
            vifDriver = (VifDriver)clazz.newInstance();
            vifDriver.configure(params);
        } catch (final ClassNotFoundException e) {
            throw new ConfigurationException("Unable to find class for libvirt.vif.driver " + e);
        } catch (final InstantiationException e) {
            throw new ConfigurationException("Unable to instantiate class for libvirt.vif.driver " + e);
        } catch (final IllegalAccessException e) {
            throw new ConfigurationException("Unable to instantiate class for libvirt.vif.driver " + e);
        }
        return vifDriver;
    }

    public VifDriver getVifDriver(final TrafficType trafficType) {
        VifDriver vifDriver = trafficTypeVifDriverMap.get(trafficType);

        if (vifDriver == null) {
            vifDriver = defaultVifDriver;
        }

        return vifDriver;
    }

    public VifDriver getVifDriver(final TrafficType trafficType, final String bridgeName) {
        VifDriver vifDriver = null;

        for (VifDriver driver : getAllVifDrivers()) {
            if (driver.isExistingBridge(bridgeName)) {
                vifDriver = driver;
                break;
            }
        }

        if (vifDriver == null) {
            vifDriver = getVifDriver(trafficType);
        }

        return vifDriver;
    }

    public List<VifDriver> getAllVifDrivers() {
        final Set<VifDriver> vifDrivers = new HashSet<VifDriver>();

        vifDrivers.add(defaultVifDriver);
        if (isTungstenEnabled) {
            vifDrivers.add(tungstenVifDriver);
        }
        vifDrivers.addAll(trafficTypeVifDriverMap.values());

        final ArrayList<VifDriver> vifDriverList = new ArrayList<VifDriver>(vifDrivers);

        return vifDriverList;
    }

    private void getPifs() {
        final File dir = new File("/sys/devices/virtual/net");
        final File[] netdevs = dir.listFiles();
        final List<String> bridges = new ArrayList<String>();
        for (int i = 0; i < netdevs.length; i++) {
            final File isbridge = new File(netdevs[i].getAbsolutePath() + "/bridge");
            final String netdevName = netdevs[i].getName();
            LOGGER.debug("looking in file " + netdevs[i].getAbsolutePath() + "/bridge");
            if (isbridge.exists()) {
                LOGGER.debug("Found bridge " + netdevName);
                bridges.add(netdevName);
            }
        }

        for (final String bridge : bridges) {
            LOGGER.debug("looking for pif for bridge " + bridge);
            final String pif = getPif(bridge);
            if (isPublicBridge(bridge)) {
                pifs.put("public", pif);
            }
            if (isGuestBridge(bridge)) {
                pifs.put("private", pif);
            }
            pifs.put(bridge, pif);
        }

        // guest(private) creates bridges on a pif, if private bridge not found try pif direct
        // This addresses the unnecessary requirement of someone to create an unused bridge just for traffic label
        if (pifs.get("private") == null) {
            LOGGER.debug("guest(private) traffic label '" + guestBridgeName + "' not found as bridge, looking for physical interface");
            final File dev = new File("/sys/class/net/" + guestBridgeName);
            if (dev.exists()) {
                LOGGER.debug("guest(private) traffic label '" + guestBridgeName + "' found as a physical device");
                pifs.put("private", guestBridgeName);
            }
        }

        // public creates bridges on a pif, if private bridge not found try pif direct
        // This addresses the unnecessary requirement of someone to create an unused bridge just for traffic label
        if (pifs.get("public") == null) {
            LOGGER.debug("public traffic label '" + publicBridgeName + "' not found as bridge, looking for physical interface");
            final File dev = new File("/sys/class/net/" + publicBridgeName);
            if (dev.exists()) {
                LOGGER.debug("public traffic label '" + publicBridgeName + "' found as a physical device");
                pifs.put("public", publicBridgeName);
            }
        }

        LOGGER.debug("done looking for pifs, no more bridges");
    }

    boolean isGuestBridge(String bridge) {
        return guestBridgeName != null && bridge.equals(guestBridgeName);
    }

    private void getOvsPifs() {
        final String cmdout = Script.runSimpleBashScript("ovs-vsctl list-br | sed '{:q;N;s/\\n/%/g;t q}'");
        LOGGER.debug("cmdout was " + cmdout);
        final List<String> bridges = Arrays.asList(cmdout.split("%"));
        for (final String bridge : bridges) {
            LOGGER.debug("looking for pif for bridge " + bridge);
            // String pif = getOvsPif(bridge);
            // Not really interested in the pif name at this point for ovs
            // bridges
            final String pif = bridge;
            if (isPublicBridge(bridge)) {
                pifs.put("public", pif);
            }
            if (isGuestBridge(bridge)) {
                pifs.put("private", pif);
            }
            pifs.put(bridge, pif);
        }
        LOGGER.debug("done looking for pifs, no more bridges");
    }

    public boolean isPublicBridge(String bridge) {
        return publicBridgeName != null && bridge.equals(publicBridgeName);
    }

    private String getPif(final String bridge) {
        String pif = matchPifFileInDirectory(bridge);
        final File vlanfile = new File("/proc/net/vlan/" + pif);

        if (vlanfile.isFile()) {
            pif = Script.runSimpleBashScript("grep ^Device\\: /proc/net/vlan/" + pif + " | awk {'print $2'}");
        }

        return pif;
    }

    private String matchPifFileInDirectory(final String bridgeName) {
        final File brif = new File("/sys/devices/virtual/net/" + bridgeName + "/brif");

        if (!brif.isDirectory()) {
            final File pif = new File("/sys/class/net/" + bridgeName);
            if (pif.isDirectory()) {
                // if bridgeName already refers to a pif, return it as-is
                return bridgeName;
            }
            LOGGER.debug("failing to get physical interface from bridge " + bridgeName + ", does " + brif.getAbsolutePath() + "exist?");
            return "";
        }

        final File[] interfaces = brif.listFiles();

        for (int i = 0; i < interfaces.length; i++) {
            final String fname = interfaces[i].getName();
            LOGGER.debug("matchPifFileInDirectory: file name '" + fname + "'");
            if (isInterface(fname)) {
                return fname;
            }
        }

        LOGGER.debug("failing to get physical interface from bridge " + bridgeName + ", did not find an eth*, bond*, team*, vlan*, em*, p*p*, ens*, eno*, enp*, or enx* in " + brif.getAbsolutePath());
        return "";
    }

    static String [] ifNamePatterns = {
            "^eth",
            "^bond",
            "^vlan",
            "^vx",
            "^em",
            "^ens",
            "^eno",
            "^enp",
            "^team",
            "^enx",
            "^dummy",
            "^lo",
            "^p\\d+p\\d+",
            "^vni",
            "^enc"
    };

    /**
     * @param fname
     * @return
     */
    protected static boolean isInterface(final String fname) {
        StringBuffer commonPattern = new StringBuffer();
        for (final String ifNamePattern : ifNamePatterns) {
            commonPattern.append("|(").append(ifNamePattern).append(".*)");
        }
        if(fname.matches(commonPattern.toString())) {
            return true;
        }
        return false;
    }

    public boolean checkNetwork(final TrafficType trafficType, final String networkName) {
        if (networkName == null) {
            return true;
        }

        if (getVifDriver(trafficType, networkName) instanceof OvsVifDriver) {
            return checkOvsNetwork(networkName);
        } else {
            return checkBridgeNetwork(networkName);
        }
    }

    private boolean checkBridgeNetwork(final String networkName) {
        if (networkName == null) {
            return true;
        }

        final String name = matchPifFileInDirectory(networkName);

        if (name == null || name.isEmpty()) {
            return false;
        } else {
            return true;
        }
    }

    private boolean checkOvsNetwork(final String networkName) {
        LOGGER.debug("Checking if network " + networkName + " exists as openvswitch bridge");
        if (networkName == null) {
            return true;
        }

        final Script command = new Script("/bin/sh", timeout);
        command.add("-c");
        command.add("ovs-vsctl br-exists " + networkName);
        return "0".equals(command.execute(null));
    }

    public boolean passCmdLine(final String vmName, final String cmdLine) throws InternalErrorException {
        final Script command = new Script(patchScriptPath, 300000, LOGGER);
        String result;
        command.add("-n", vmName);
        command.add("-c", cmdLine);
        result = command.execute();
        if (result != null) {
            LOGGER.error("Passing cmdline failed:" + result);
            return false;
        }
        return true;
    }

    public List<VgpuTypesInfo> getGpuDevices() {
        LOGGER.debug("Executing GPU discovery script at: {}", gpuDiscoveryPath);
        final Script command = new Script(gpuDiscoveryPath, Duration.standardSeconds(30), LOGGER);

        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = command.execute(parser);
        if (result == null) {
            LOGGER.debug("GPU discovery command executed successfully");
            result = parser.getLines();
        }

        if (result == null || result.trim().isEmpty()) {
            LOGGER.error("GPU discovery failed: command returned null or empty result. Script path: {}, Exit code: {}",
                    gpuDiscoveryPath, command.getExitValue());
            return Collections.emptyList();
        }

        LOGGER.debug("GPU discovery result: {}", result);

        // This will be used to update the GPU device list when agent on a host is unavailable or the VM is imported.
        return parseGpuDevicesFromResult(result);
    }

    protected List<VgpuTypesInfo> parseGpuDevicesFromResult(String result) {
        List<VgpuTypesInfo> gpuDevices = new ArrayList<>();
        try {
            JsonParser jsonParser = new JsonParser();
            JsonArray jsonArray = jsonParser.parse(result).getAsJsonObject().get("gpus").getAsJsonArray();

            for (JsonElement jsonElement : jsonArray) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                String busAddress = jsonObject.get("pci_address").getAsString();
                String vendorId = jsonObject.get("vendor_id").getAsString();
                String vendorName = jsonObject.get("vendor").getAsString();
                String deviceId = jsonObject.get("device_id").getAsString();
                String deviceName = jsonObject.get("device").getAsString();

                // vgpu instances uses mdev uuid
                // vf instances uses vf_pci_address

                JsonArray vgpuInstances = jsonObject.get("vgpu_instances").getAsJsonArray();
                JsonArray vfInstances = jsonObject.get("vf_instances").getAsJsonArray();

                JsonObject fullPassthrough = jsonObject.get("full_passthrough").getAsJsonObject();
                boolean fullPassthroughEnabled = fullPassthrough.get("enabled").getAsInt() == 1;

                String numaNode = getJsonStringValueOrNull(jsonObject, "numa_node");
                String pciRoot = getJsonStringValueOrNull(jsonObject, "pci_root");

                Long maxInstances = getJsonLongValueOrNull(jsonObject, "max_instances");
                Long videoRam = getJsonLongValueOrNull(jsonObject, "video_ram");
                Long maxHeads = getJsonLongValueOrNull(jsonObject, "max_heads");
                Long maxResolutionX = getJsonLongValueOrNull(jsonObject, "max_resolution_x");
                Long maxResolutionY = getJsonLongValueOrNull(jsonObject, "max_resolution_y");

                VgpuTypesInfo vgpuType = new VgpuTypesInfo(GpuDevice.DeviceType.PCI, vendorName + " " + deviceName,
                        "passthrough", busAddress, vendorId, vendorName, deviceId, deviceName, numaNode, pciRoot);

                vgpuType.setMaxVgpuPerGpu(maxInstances);
                vgpuType.setVideoRam(videoRam);
                vgpuType.setMaxHeads(maxHeads);
                vgpuType.setMaxResolutionX(maxResolutionX);
                vgpuType.setMaxResolutionY(maxResolutionY);

                if (fullPassthroughEnabled) {
                    vgpuType.setPassthroughEnabled(true);
                } else {
                    vgpuType.setPassthroughEnabled(false);
                }
                vgpuType.setVmName(getJsonStringValueOrNull(fullPassthrough, "used_by_vm"));

                gpuDevices.add(vgpuType);

                for (JsonElement vgpuInstance : vgpuInstances) {
                    VgpuTypesInfo vgpu = getGpuDeviceFromVgpuInstance(vgpuInstance, busAddress, vendorId, vendorName,
                            deviceId, deviceName, numaNode, pciRoot);
                    if (vgpu != null) {
                        gpuDevices.add(vgpu);
                    }
                }

                for (JsonElement vfInstance : vfInstances) {
                    VgpuTypesInfo vf = getGpuDeviceFromVfInstance(vfInstance, busAddress, vendorId, vendorName,
                            deviceId, deviceName, numaNode, pciRoot);
                    if (vf != null) {
                        gpuDevices.add(vf);
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("Failed to parse GPU discovery result: {}", e.getMessage(), e);
        }
        return gpuDevices;
    }

    protected VgpuTypesInfo getGpuDeviceFromVgpuInstance(JsonElement vgpuInstance, String busAddress, String vendorId,
            String vendorName, String deviceId, String deviceName, String numaNode, String pciRoot) {
        JsonObject vgpuInstanceJsonObject = vgpuInstance.getAsJsonObject();
        String mdevUuid = getJsonStringValueOrNull(vgpuInstanceJsonObject, "mdev_uuid");
        String profileName = getJsonStringValueOrNull(vgpuInstanceJsonObject, "profile_name");
        if (profileName == null || profileName.isEmpty()) {
            return null; // Skip if profile name is not provided
        }
        Long maxInstances = getJsonLongValueOrNull(vgpuInstanceJsonObject, "max_instances");
        Long videoRam = getJsonLongValueOrNull(vgpuInstanceJsonObject, "video_ram");
        Long maxHeads = getJsonLongValueOrNull(vgpuInstanceJsonObject, "max_heads");
        Long maxResolutionX = getJsonLongValueOrNull(vgpuInstanceJsonObject, "max_resolution_x");
        Long maxResolutionY = getJsonLongValueOrNull(vgpuInstanceJsonObject, "max_resolution_y");
        VgpuTypesInfo device = new VgpuTypesInfo(GpuDevice.DeviceType.MDEV, vendorName + " " + deviceName, profileName, mdevUuid, vendorId, vendorName, deviceId, deviceName, numaNode, pciRoot);
        device.setParentBusAddress(busAddress);
        device.setMaxVgpuPerGpu(maxInstances);
        device.setVideoRam(videoRam);
        device.setMaxHeads(maxHeads);
        device.setMaxResolutionX(maxResolutionX);
        device.setMaxResolutionY(maxResolutionY);
        device.setVmName(getJsonStringValueOrNull(vgpuInstance.getAsJsonObject(), "used_by_vm"));
        return device;
    }

    protected VgpuTypesInfo getGpuDeviceFromVfInstance(JsonElement vfInstance, String busAddress, String vendorId,
            String vendorName, String deviceId, String deviceName, String numaNode, String pciRoot) {
        JsonObject vfInstanceJsonObject = vfInstance.getAsJsonObject();
        String vfPciAddress = vfInstanceJsonObject.get("vf_pci_address").getAsString();
        String vfProfile = vfInstanceJsonObject.get("vf_profile").getAsString();
        if (vfProfile == null || vfProfile.isEmpty()) {
            return null; // Skip if profile name is not provided
        }
        Long maxInstances = getJsonLongValueOrNull(vfInstanceJsonObject, "max_instances");
        Long videoRam = getJsonLongValueOrNull(vfInstanceJsonObject, "video_ram");
        Long maxHeads = getJsonLongValueOrNull(vfInstanceJsonObject, "max_heads");
        Long maxResolutionX = getJsonLongValueOrNull(vfInstanceJsonObject, "max_resolution_x");
        Long maxResolutionY = getJsonLongValueOrNull(vfInstanceJsonObject, "max_resolution_y");
        VgpuTypesInfo device = new VgpuTypesInfo(GpuDevice.DeviceType.PCI, vendorName + " " + deviceName, vfProfile, vfPciAddress, vendorId, vendorName, deviceId, deviceName, numaNode, pciRoot);
        device.setParentBusAddress(busAddress);
        device.setMaxVgpuPerGpu(maxInstances);
        device.setVideoRam(videoRam);
        device.setMaxHeads(maxHeads);
        device.setMaxResolutionX(maxResolutionX);
        device.setMaxResolutionY(maxResolutionY);
        device.setVmName(getJsonStringValueOrNull(vfInstanceJsonObject, "used_by_vm"));
        return device;
    }

    /**
     * Safely extracts a string value from a JSON object, returning null if the field is missing or null.
     *
     * @param jsonObject the JSON object to extract from
     * @param fieldName the name of the field to extract
     * @return the string value of the field, or null if the field is missing or null
     */
    protected String getJsonStringValueOrNull(JsonObject jsonObject, String fieldName) {
        JsonElement element = jsonObject.get(fieldName);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    protected Long getJsonLongValueOrNull(JsonObject jsonObject, String fieldName) {
        JsonElement element = jsonObject.get(fieldName);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsLong();
    }

    boolean isDirectAttachedNetwork(final String type) {
        if ("untagged".equalsIgnoreCase(type)) {
            return true;
        } else {
            try {
                Long.valueOf(type);
            } catch (final NumberFormatException e) {
                return true;
            }
            return false;
        }
    }

    public String startVM(final Connect conn, final String vmName, final String domainXML) throws LibvirtException, InternalErrorException {
        return startVM(conn, vmName, domainXML, 0);
    }

    public String startVM(final Connect conn, final String vmName, final String domainXML, int flags) throws LibvirtException, InternalErrorException {
        try {
            /*
                We create a transient domain here. When this method gets
                called we receive a full XML specification of the guest,
                so no need to define it persistent.

                This also makes sure we never have any old "garbage" defined
                in libvirt which might haunt us.
             */

            // check for existing inactive vm definition and remove it
            // this can sometimes happen during crashes, etc
            Domain dm = null;
            try {
                dm = conn.domainLookupByName(vmName);
                if (dm != null && dm.isPersistent() == 1) {
                    // this is safe because it doesn't stop running VMs
                    dm.undefine();
                }
            } catch (final LibvirtException e) {
                // this is what we want, no domain found
            } finally {
                if (dm != null) {
                    dm.free();
                }
            }

            conn.domainCreateXML(domainXML, flags);
        } catch (final LibvirtException e) {
            throw e;
        }
        return null;
    }

    @Override
    public boolean stop() {
        try {
            final Connect conn = LibvirtConnection.getConnection();
            if (AgentPropertiesFileHandler.getPropertyValue(AgentProperties.LIBVIRT_EVENTS_ENABLED) && libvirtDomainListener != null) {
                LOGGER.debug("Clearing old domain listener");
                conn.removeLifecycleListener(libvirtDomainListener);
            }
            conn.close();
        } catch (final LibvirtException e) {
            LOGGER.trace("Ignoring libvirt error.", e);
        }

        return true;
    }

    /**
     * This finds a command wrapper to handle the command and executes it.
     * If no wrapper is found an {@see UnsupportedAnswer} is sent back.
     * Any other exceptions are to be caught and wrapped in an generic {@see Answer}, marked as failed.
     *
     * @param cmd the instance of a {@see Command} to execute.
     * @return the for the {@see Command} appropriate {@see Answer} or {@see UnsupportedAnswer}
     */
    @Override
    public Answer executeRequest(final Command cmd) {
        if (isReconcileCommandsEnabled) {
            ReconcileCommandUtils.updateLogFileForCommand(COMMANDS_LOG_PATH, cmd, Command.State.STARTED);
        }

        final LibvirtRequestWrapper wrapper = LibvirtRequestWrapper.getInstance();
        try {
            return wrapper.execute(cmd, this);
        } catch (final RequestWrapper.CommandNotSupported cmde) {
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
    }

    public CommandInfo[] getCommandInfosFromLogFiles(boolean update) {
        File commandsLogPath = new File(COMMANDS_LOG_PATH);
        File[] files = commandsLogPath.listFiles();
        if (files != null) {
            CommandInfo[] commandInfos = new CommandInfo[files.length];
            int i = 0;
            for (File file : files) {
                CommandInfo commandInfo = ReconcileCommandUtils.readLogFileForCommand(file.getAbsolutePath());
                if (commandInfo == null) {
                    continue;
                }
                if (update) {
                    if (Command.State.PROCESSING.equals(commandInfo.getState())) {
                        ReconcileCommandUtils.updateLogFileForCommand(file.getAbsolutePath(), Command.State.INTERRUPTED);
                    } else if (Command.State.PROCESSING_IN_BACKEND.equals(commandInfo.getState())) {
                        ReconcileCommandUtils.updateLogFileForCommand(file.getAbsolutePath(), Command.State.DANGLED_IN_BACKEND);
                    }
                }
                logger.debug(String.format("Adding reconcile command with seq: %s, command: %s, answer: %s", commandInfo.getRequestSeq(), commandInfo.getCommandName(), commandInfo.getAnswer()));
                commandInfos[i++] = commandInfo;
            }
            return commandInfos;
        }
        return new CommandInfo[0];
    }

    public void createOrUpdateLogFileForCommand(Command command, Command.State state) {
        if (isReconcileCommandsEnabled) {
            ReconcileCommandUtils.updateLogFileForCommand(COMMANDS_LOG_PATH, command, state);
        }
    }

    public void createOrUpdateLogFileForCommand(Command command, Answer answer) {
        if (isReconcileCommandsEnabled) {
            ReconcileCommandUtils.updateLogFileWithAnswerForCommand(LibvirtComputingResource.COMMANDS_LOG_PATH, command, answer);
        }
    }

    @Override
    public void processPingAnswer(PingAnswer answer) {
        PingCommand pingCommand = answer.getCommand();
        List<String> reconcileCommands = answer.getReconcileCommands();
        CommandInfo[] commandInfos = pingCommand.getCommandInfos();
        for (CommandInfo commandInfo : commandInfos) {
            String commandKey = getCommandKey(commandInfo.getRequestSeq(), commandInfo.getCommandName());
            if (Arrays.asList(Command.State.COMPLETED, Command.State.FAILED, Command.State.INTERRUPTED, Command.State.TIMED_OUT).contains(commandInfo.getState())) {
                logger.debug(String.format("Removing command %s in %s state as it has been received by the management server", commandKey, commandInfo.getState()));
                String fileName = String.format("%s/%s-%s.json", COMMANDS_LOG_PATH, commandInfo.getRequestSeq(), commandInfo.getCommandName());
                ReconcileCommandUtils.deleteLogFile(fileName);
            } else if (!reconcileCommands.contains(commandKey)) {
                logger.debug(String.format("Removing command %s in %s state as it cannot be found by the management server", commandKey, commandInfo.getState()));
                String fileName = String.format("%s/%s-%s.json", COMMANDS_LOG_PATH, commandInfo.getRequestSeq(), commandInfo.getCommandName());
                ReconcileCommandUtils.deleteLogFile(fileName);
            }
        }
    }

    private String getCommandKey(long requestSeq, String commandName) {
        return requestSeq + "-" + commandName;
    }

    public synchronized boolean destroyTunnelNetwork(final String bridge) {
        findOrCreateTunnelNetwork(bridge);

        final Script cmd = new Script(ovsTunnelPath, timeout, LOGGER);
        cmd.add("destroy_ovs_bridge");
        cmd.add("--bridge", bridge);

        final String result = cmd.execute();

        if (result != null) {
            LOGGER.debug("OVS Bridge could not be destroyed due to error ==> " + result);
            return false;
        }
        return true;
    }

    public synchronized boolean findOrCreateTunnelNetwork(final String nwName) {
        try {
            if (checkNetwork(TrafficType.Guest, nwName)) {
                return true;
            }
            // if not found, create a new one
            final Map<String, String> otherConfig = new HashMap<String, String>();
            otherConfig.put("ovs-host-setup", "");
            Script.runSimpleBashScript("ovs-vsctl -- --may-exist add-br "
                    + nwName + " -- set bridge " + nwName
                    + " other_config:ovs-host-setup='-1'");
            LOGGER.debug("### KVM network for tunnels created:" + nwName);
        } catch (final Exception e) {
            LOGGER.warn("createTunnelNetwork failed", e);
            return false;
        }
        return true;
    }

    public synchronized boolean configureTunnelNetwork(final Long networkId,
                                                       final long hostId, final String nwName) {
        try {
            final boolean findResult = findOrCreateTunnelNetwork(nwName);
            if (!findResult) {
                LOGGER.warn("LibvirtComputingResource.findOrCreateTunnelNetwork() failed! Cannot proceed creating the tunnel.");
                return false;
            }
            final String configuredHosts = Script
                    .runSimpleBashScript("ovs-vsctl get bridge " + nwName
                            + " other_config:ovs-host-setup");
            boolean configured = false;
            if (configuredHosts != null) {
                final String hostIdsStr[] = configuredHosts.split(",");
                for (final String hostIdStr : hostIdsStr) {
                    if (hostIdStr.equals(((Long)hostId).toString())) {
                        configured = true;
                        break;
                    }
                }
            }
            if (!configured) {
                final Script cmd = new Script(ovsTunnelPath, timeout, LOGGER);
                cmd.add("setup_ovs_bridge");
                cmd.add("--key", nwName);
                cmd.add("--cs_host_id", ((Long)hostId).toString());
                cmd.add("--bridge", nwName);
                final String result = cmd.execute();
                if (result != null) {
                    throw new CloudRuntimeException(
                            "Unable to pre-configure OVS bridge " + nwName
                                    + " for network ID:" + networkId);
                }
            }
        } catch (final Exception e) {
            LOGGER.warn("createandConfigureTunnelNetwork failed", e);
            return false;
        }
        return true;
    }

    protected Storage.StorageResourceType getStorageResourceType() {
        return Storage.StorageResourceType.STORAGE_POOL;
    }

    // this is much like PrimaryStorageDownloadCommand, but keeping it separate
    public KVMPhysicalDisk templateToPrimaryDownload(final String templateUrl, final KVMStoragePool primaryPool, final String volUuid) {
        final int index = templateUrl.lastIndexOf("/");
        final String mountpoint = templateUrl.substring(0, index);
        String templateName = null;
        if (index < templateUrl.length() - 1) {
            templateName = templateUrl.substring(index + 1);
        }

        KVMPhysicalDisk templateVol = null;
        KVMStoragePool secondaryPool = null;
        try {
            secondaryPool = storagePoolManager.getStoragePoolByURI(mountpoint);
            /* Get template vol */
            if (templateName == null) {
                secondaryPool.refresh();
                final List<KVMPhysicalDisk> disks = secondaryPool.listPhysicalDisks();
                if (disks == null || disks.isEmpty()) {
                    LOGGER.error("Failed to get volumes from pool: " + secondaryPool.getUuid());
                    return null;
                }
                for (final KVMPhysicalDisk disk : disks) {
                    if (disk.getName().endsWith("qcow2")) {
                        templateVol = disk;
                        break;
                    }
                }
                if (templateVol == null) {
                    LOGGER.error("Failed to get template from pool: " + secondaryPool.getUuid());
                    return null;
                }
            } else {
                templateVol = secondaryPool.getPhysicalDisk(templateName);
            }

            /* Copy volume to primary storage */

            final KVMPhysicalDisk primaryVol = storagePoolManager.copyPhysicalDisk(templateVol, volUuid, primaryPool, 0);
            return primaryVol;
        } catch (final CloudRuntimeException e) {
            LOGGER.error("Failed to download template to primary storage", e);
            return null;
        } finally {
            if (secondaryPool != null) {
                storagePoolManager.deleteStoragePool(secondaryPool.getType(), secondaryPool.getUuid());
            }
        }
    }

    public String getResizeScriptType(final KVMStoragePool pool, final KVMPhysicalDisk vol) {
        final StoragePoolType poolType = pool.getType();
        final PhysicalDiskFormat volFormat = vol.getFormat();

        if (pool.getType() == StoragePoolType.CLVM && volFormat == PhysicalDiskFormat.RAW) {
            return "CLVM";
        } else if ((poolType == StoragePoolType.NetworkFilesystem
                || poolType == StoragePoolType.SharedMountPoint
                || poolType == StoragePoolType.Filesystem
                || poolType == StoragePoolType.Gluster)
                && volFormat == PhysicalDiskFormat.QCOW2 ) {
            return "QCOW2";
        } else if (poolType == StoragePoolType.Linstor) {
            return RESIZE_NOTIFY_ONLY;
        }
        throw new CloudRuntimeException("Cannot determine resize type from pool type " + pool.getType());
    }

    private String getBroadcastUriFromBridge(final String brName) {
        final String pif = matchPifFileInDirectory(brName);
        final Pattern pattern = Pattern.compile("(\\D+)(\\d+)(\\D*)(\\d*)(\\D*)(\\d*)");
        final Matcher matcher = pattern.matcher(pif);
        LOGGER.debug("getting broadcast uri for pif " + pif + " and bridge " + brName);
        if(matcher.find()) {
            if (brName.startsWith("brvx")){
                return BroadcastDomainType.Vxlan.toUri(matcher.group(2)).toString();
            }
            else{
                if (!matcher.group(6).isEmpty()) {
                    return BroadcastDomainType.Vlan.toUri(matcher.group(6)).toString();
                } else if (!matcher.group(4).isEmpty()) {
                    return BroadcastDomainType.Vlan.toUri(matcher.group(4)).toString();
                } else {
                    //untagged or not matching (eth|bond|team)#.#
                    LOGGER.debug("failed to get vNet id from bridge " + brName
                            + "attached to physical interface" + pif + ", perhaps untagged interface");
                    return "";
                }
            }
        } else {
            LOGGER.debug("failed to get vNet id from bridge " + brName + "attached to physical interface" + pif);
            return "";
        }
    }

    private void VifHotPlug(final Connect conn, final String vmName, final String broadcastUri, final String macAddr) throws InternalErrorException, LibvirtException {
        final NicTO nicTO = new NicTO();
        nicTO.setMac(macAddr);
        nicTO.setType(TrafficType.Public);
        if (broadcastUri == null) {
            nicTO.setBroadcastType(BroadcastDomainType.Native);
        } else {
            final URI uri = BroadcastDomainType.fromString(broadcastUri);
            nicTO.setBroadcastType(BroadcastDomainType.getSchemeValue(uri));
            nicTO.setBroadcastUri(uri);
        }

        final Domain vm = getDomain(conn, vmName);
        vm.attachDevice(getVifDriver(nicTO.getType()).plug(nicTO, "Other PV", "", null).toString());
    }


    private void vifHotUnPlug (final Connect conn, final String vmName, final String macAddr) throws InternalErrorException, LibvirtException {

        Domain vm = null;
        vm = getDomain(conn, vmName);
        final List<InterfaceDef> pluggedNics = getInterfaces(conn, vmName);
        for (final InterfaceDef pluggedNic : pluggedNics) {
            if (pluggedNic.getMacAddress().equalsIgnoreCase(macAddr)) {
                vm.detachDevice(pluggedNic.toString());
                // We don't know which "traffic type" is associated with
                // each interface at this point, so inform all vif drivers
                for (final VifDriver vifDriver : getAllVifDrivers()) {
                    vifDriver.unplug(pluggedNic, true);
                }
            }
        }
    }

    private ExecutionResult prepareNetworkElementCommand(final SetupGuestNetworkCommand cmd) {
        Connect conn;
        final NicTO nic = cmd.getNic();
        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);

        try {
            conn = LibvirtConnection.getConnectionByVmName(routerName);
            final List<InterfaceDef> pluggedNics = getInterfaces(conn, routerName);
            InterfaceDef routerNic = null;

            for (final InterfaceDef pluggedNic : pluggedNics) {
                if (pluggedNic.getMacAddress().equalsIgnoreCase(nic.getMac())) {
                    routerNic = pluggedNic;
                    break;
                }
            }

            if (routerNic == null) {
                return new ExecutionResult(false, "Can not find nic with mac " + nic.getMac() + " for VM " + routerName);
            }

            return new ExecutionResult(true, null);
        } catch (final LibvirtException e) {
            final String msg = "Creating guest network failed due to " + e.toString();
            LOGGER.warn(msg, e);
            return new ExecutionResult(false, msg);
        }
    }

    protected ExecutionResult prepareNetworkElementCommand(final SetSourceNatCommand cmd) {
        Connect conn;
        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        final IpAddressTO pubIP = cmd.getIpAddress();

        try {
            conn = LibvirtConnection.getConnectionByVmName(routerName);
            Integer devNum = 0;
            final String pubVlan = pubIP.getBroadcastUri();
            final List<InterfaceDef> pluggedNics = getInterfaces(conn, routerName);

            for (final InterfaceDef pluggedNic : pluggedNics) {
                final String pluggedVlanBr = pluggedNic.getBrName();
                final String pluggedVlanId = getBroadcastUriFromBridge(pluggedVlanBr);
                if (pubVlan.equalsIgnoreCase(Vlan.UNTAGGED) && pluggedVlanBr.equalsIgnoreCase(publicBridgeName)) {
                    break;
                } else if (pluggedVlanBr.equalsIgnoreCase(linkLocalBridgeName)) {
                    /*skip over, no physical bridge device exists*/
                } else if (pluggedVlanId == null) {
                    /*this should only be true in the case of link local bridge*/
                    return new ExecutionResult(false, "unable to find the vlan id for bridge " + pluggedVlanBr + " when attempting to set up" + pubVlan +
                            " on router " + routerName);
                } else if (pluggedVlanId.equals(pubVlan)) {
                    break;
                }
                devNum++;
            }

            pubIP.setNicDevId(devNum);

            return new ExecutionResult(true, "success");
        } catch (final LibvirtException e) {
            final String msg = "Ip SNAT failure due to " + e.toString();
            LOGGER.error(msg, e);
            return new ExecutionResult(false, msg);
        }
    }

    protected ExecutionResult prepareNetworkElementCommand(final IpAssocVpcCommand cmd) {
        Connect conn;
        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);

        try {
            conn = getLibvirtUtilitiesHelper().getConnectionByVmName(routerName);
            Pair<Map<String, Integer>, Integer> macAddressToNicNumPair = getMacAddressToNicNumPair(conn, routerName);
            final Map<String, Integer> macAddressToNicNum = macAddressToNicNumPair.first();
            Integer devNum = macAddressToNicNumPair.second();

            final IpAddressTO[] ips = cmd.getIpAddresses();
            for (final IpAddressTO ip : ips) {
                ip.setNicDevId(macAddressToNicNum.get(ip.getVifMacAddress()));
            }

            return new ExecutionResult(true, null);
        } catch (final LibvirtException e) {
            LOGGER.error("Ip Assoc failure on applying one ip due to exception:  ", e);
            return new ExecutionResult(false, e.getMessage());
        }
    }

    public ExecutionResult prepareNetworkElementCommand(final IpAssocCommand cmd) {
        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        final String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        Connect conn;
        try {
            conn = getLibvirtUtilitiesHelper().getConnectionByVmName(routerName);
            Pair<Map<String, Integer>, Integer> macAddressToNicNumPair = getMacAddressToNicNumPair(conn, routerName);
            final Map<String, Integer> macAddressToNicNum = macAddressToNicNumPair.first();
            Integer devNum = macAddressToNicNumPair.second();

            final IpAddressTO[] ips = cmd.getIpAddresses();
            int nicNum = 0;
            for (final IpAddressTO ip : ips) {
                boolean newNic = false;
                if (!macAddressToNicNum.containsKey(ip.getVifMacAddress())) {
                    /* plug a vif into router */
                    VifHotPlug(conn, routerName, ip.getBroadcastUri(), ip.getVifMacAddress());
                    macAddressToNicNum.put(ip.getVifMacAddress(), devNum++);
                    newNic = true;
                }
                nicNum = macAddressToNicNum.get(ip.getVifMacAddress());
                networkUsage(routerIp, "addVif", "eth" + nicNum);

                ip.setNicDevId(nicNum);
                ip.setNewNic(newNic);
            }
            return new ExecutionResult(true, null);
        } catch (final LibvirtException e) {
            LOGGER.error("ipassoccmd failed", e);
            return new ExecutionResult(false, e.getMessage());
        } catch (final InternalErrorException e) {
            LOGGER.error("ipassoccmd failed", e);
            return new ExecutionResult(false, e.getMessage());
        }
    }

    protected ExecutionResult cleanupNetworkElementCommand(final IpAssocCommand cmd) {

        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        final String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        final String lastIp = cmd.getAccessDetail(NetworkElementCommand.NETWORK_PUB_LAST_IP);
        Connect conn;
        try {
            conn = getLibvirtUtilitiesHelper().getConnectionByVmName(routerName);
            Pair<Map<String, Integer>, Integer> macAddressToNicNumPair = getMacAddressToNicNumPair(conn, routerName);
            final Map<String, Integer> macAddressToNicNum = macAddressToNicNumPair.first();
            Integer devNum = macAddressToNicNumPair.second();

            final IpAddressTO[] ips = cmd.getIpAddresses();
            int nicNum = 0;
            for (final IpAddressTO ip : ips) {
                if (!macAddressToNicNum.containsKey(ip.getVifMacAddress())) {
                    /* plug a vif into router */
                    VifHotPlug(conn, routerName, ip.getBroadcastUri(), ip.getVifMacAddress());
                    macAddressToNicNum.put(ip.getVifMacAddress(), devNum++);
                }
                nicNum = macAddressToNicNum.get(ip.getVifMacAddress());

                if (StringUtils.equalsIgnoreCase(lastIp, "true") && !ip.isAdd()) {
                    // in isolated network eth2 is the default public interface. We don't want to delete it.
                    if (nicNum != 2) {
                        vifHotUnPlug(conn, routerName, ip.getVifMacAddress());
                        networkUsage(routerIp, "deleteVif", "eth" + nicNum);
                    }
                }
            }

        } catch (final LibvirtException e) {
            LOGGER.error("ipassoccmd failed", e);
            return new ExecutionResult(false, e.getMessage());
        } catch (final InternalErrorException e) {
            LOGGER.error("ipassoccmd failed", e);
            return new ExecutionResult(false, e.getMessage());
        }

        return new ExecutionResult(true, null);
    }


    private Pair<Map<String, Integer>, Integer> getMacAddressToNicNumPair(Connect conn, String routerName) {
        Integer devNum = 0;
        final List<InterfaceDef> pluggedNics = getInterfaces(conn, routerName);
        final Map<String, Integer> macAddressToNicNum = new HashMap<>(pluggedNics.size());
        for (final InterfaceDef pluggedNic : pluggedNics) {
            final String pluggedVlan = pluggedNic.getBrName();
            macAddressToNicNum.put(pluggedNic.getMacAddress(), devNum);
            devNum++;
        }
        return new Pair<Map<String, Integer>, Integer>(macAddressToNicNum, devNum);
    }

    public PowerState convertToPowerState(final DomainState ps) {
        final PowerState state = POWER_STATES_TABLE.get(ps);
        return state == null ? PowerState.PowerUnknown : state;
    }

    public PowerState getVmState(final Connect conn, final String vmName) {
        int retry = 3;
        Domain vms = null;
        while (retry-- > 0) {
            try {
                vms = conn.domainLookupByName(vmName);
                final PowerState s = convertToPowerState(vms.getInfo().state);
                return s;
            } catch (final LibvirtException e) {
                LOGGER.error("Could not get state for VM [{}] (retry={}) due to:", vmName, retry, e);
            } finally {
                try {
                    if (vms != null) {
                        vms.free();
                    }
                } catch (final LibvirtException l) {
                    LOGGER.trace("Ignoring libvirt error.", l);
                }
            }
        }
        return PowerState.PowerOff;
    }

    public String networkUsage(final String privateIpAddress, final String option, final String vif) {
        return networkUsage(privateIpAddress, option, vif, null);
    }

    public String networkUsage(final String privateIpAddress, final String option, final String vif, String publicIp) {
        final Script getUsage = new Script(routerProxyPath, LOGGER);
        getUsage.add("netusage.sh");
        getUsage.add(privateIpAddress);
        if (option.equals("get")) {
            getUsage.add("-g");
            if (StringUtils.isNotEmpty(publicIp)) {
                getUsage.add("-l", publicIp);
            }
        } else if (option.equals("create")) {
            getUsage.add("-c");
        } else if (option.equals("reset")) {
            getUsage.add("-r");
        } else if (option.equals("addVif")) {
            getUsage.add("-a", vif);
        } else if (option.equals("deleteVif")) {
            getUsage.add("-d", vif);
        }

        final OutputInterpreter.OneLineParser usageParser = new OutputInterpreter.OneLineParser();
        final String result = getUsage.execute(usageParser);
        if (result != null) {
            LOGGER.debug("Failed to execute networkUsage:" + result);
            return null;
        }
        return usageParser.getLine();
    }

    public long[] getNetworkStats(final String privateIP) {
        return getNetworkStats(privateIP, null);
    }

    public long[] getNetworkStats(final String privateIP, String publicIp) {
        final String result = networkUsage(privateIP, "get", null, publicIp);
        final long[] stats = new long[2];
        if (result != null) {
            final String[] splitResult = result.split(":");
            int i = 0;
            while (i < splitResult.length - 1) {
                stats[0] += Long.parseLong(splitResult[i++]);
                stats[1] += Long.parseLong(splitResult[i++]);
            }
        }
        return stats;
    }

    public String getHaproxyStats(final String privateIP, final String publicIp, final Integer port) {
        final Script getHaproxyStatsScript = new Script(routerProxyPath, LOGGER);
        getHaproxyStatsScript.add("get_haproxy_stats.sh");
        getHaproxyStatsScript.add(privateIP);
        getHaproxyStatsScript.add(publicIp);
        getHaproxyStatsScript.add(String.valueOf(port));

        final OutputInterpreter.OneLineParser statsParser = new OutputInterpreter.OneLineParser();
        final String result = getHaproxyStatsScript.execute(statsParser);
        if (result != null) {
            LOGGER.debug("Failed to execute haproxy stats:" + result);
            return null;
        }
        return statsParser.getLine();
    }

    public long[] getNetworkLbStats(final String privateIp, final String publicIp, final Integer port) {
        final String result = getHaproxyStats(privateIp, publicIp, port);
        final long[] stats = new long[1];
        if (result != null) {
            final String[] splitResult = result.split(",");
            stats[0] += Long.parseLong(splitResult[0]);
        }
        return stats;
    }

    public String configureVPCNetworkUsage(final String privateIpAddress, final String publicIp, final String option, final String vpcCIDR) {
        final Script getUsage = new Script(routerProxyPath, LOGGER);
        getUsage.add("vpc_netusage.sh");
        getUsage.add(privateIpAddress);
        getUsage.add("-l", publicIp);

        if (option.equals("get")) {
            getUsage.add("-g");
        } else if (option.equals("create")) {
            getUsage.add("-c");
            getUsage.add("-v", vpcCIDR);
        } else if (option.equals("reset")) {
            getUsage.add("-r");
        } else if (option.equals("vpn")) {
            getUsage.add("-n");
        } else if (option.equals("remove")) {
            getUsage.add("-d");
        }

        final OutputInterpreter.OneLineParser usageParser = new OutputInterpreter.OneLineParser();
        final String result = getUsage.execute(usageParser);
        if (result != null) {
            LOGGER.debug("Failed to execute VPCNetworkUsage:" + result);
            return null;
        }
        return usageParser.getLine();
    }

    public long[] getVPCNetworkStats(final String privateIP, final String publicIp, final String option) {
        final String result = configureVPCNetworkUsage(privateIP, publicIp, option, null);
        final long[] stats = new long[2];
        if (result != null) {
            final String[] splitResult = result.split(":");
            int i = 0;
            while (i < splitResult.length - 1) {
                stats[0] += Long.parseLong(splitResult[i++]);
                stats[1] += Long.parseLong(splitResult[i++]);
            }
        }
        return stats;
    }

    public void handleVmStartFailure(final Connect conn, final String vmName, final LibvirtVMDef vm) {
        if (vm != null && vm.getDevices() != null) {
            cleanupVMNetworks(conn, vm.getDevices().getInterfaces());
        }
    }

    protected String getUuid(String uuid) {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        } else {
            try {
                final UUID uuid2 = UUID.fromString(uuid);
                final String uuid3 = uuid2.toString();
                if (!uuid3.equals(uuid)) {
                    uuid = UUID.randomUUID().toString();
                }
            } catch (final IllegalArgumentException e) {
                uuid = UUID.randomUUID().toString();
            }
        }
        return uuid;
    }

    /**
     * Set quota and period tags on 'ctd' when CPU limit use is set
     */
    protected void setQuotaAndPeriod(VirtualMachineTO vmTO, CpuTuneDef ctd) {
        if (vmTO.isLimitCpuUse() && vmTO.getCpuQuotaPercentage() != null) {
            Double cpuQuotaPercentage = vmTO.getCpuQuotaPercentage();
            int period = CpuTuneDef.DEFAULT_PERIOD;
            int quota = (int) (period * cpuQuotaPercentage);
            if (quota < CpuTuneDef.MIN_QUOTA) {
                LOGGER.info("Calculated quota (" + quota + ") below the minimum (" + CpuTuneDef.MIN_QUOTA + ") for VM domain " + vmTO.getUuid() + ", setting it to minimum " +
                        "and calculating period instead of using the default");
                quota = CpuTuneDef.MIN_QUOTA;
                period = (int) ((double) quota / cpuQuotaPercentage);
                if (period > CpuTuneDef.MAX_PERIOD) {
                    LOGGER.info("Calculated period (" + period + ") exceeds the maximum (" + CpuTuneDef.MAX_PERIOD +
                            "), setting it to the maximum");
                    period = CpuTuneDef.MAX_PERIOD;
                }
            }
            ctd.setQuota(quota);
            ctd.setPeriod(period);
            LOGGER.info("Setting quota=" + quota + ", period=" + period + " to VM domain " + vmTO.getUuid());
        }
    }

    protected void enlightenWindowsVm(VirtualMachineTO vmTO, FeaturesDef features) {
        if (vmTO.getOs().contains("Windows PV")) {
            // If OS is Windows PV, then enable the features. Features supported on Windows 2008 and later
            LibvirtVMDef.HyperVEnlightenmentFeatureDef hyv = new LibvirtVMDef.HyperVEnlightenmentFeatureDef();
            hyv.setFeature("relaxed", true);
            hyv.setFeature("vapic", true);
            hyv.setFeature("spinlocks", true);
            hyv.setRetries(8096);
            features.addHyperVFeature(hyv);
            LOGGER.info("Enabling KVM Enlightment Features to VM domain " + vmTO.getUuid());
        }
    }

    /**
     * Creates VM KVM definitions from virtual machine transfer object specifications.
     */
    public LibvirtVMDef createVMFromSpec(final VirtualMachineTO vmTO) {
        LOGGER.debug(String.format("Creating VM from specifications [%s]", vmTO.toString()));

        LibvirtVMDef vm = new LibvirtVMDef();
        vm.setDomainName(vmTO.getName());
        String uuid = vmTO.getUuid();
        uuid = getUuid(uuid);
        vm.setDomUUID(uuid);
        vm.setDomDescription(vmTO.getOs());
        vm.setPlatformEmulator(vmTO.getPlatformEmulator());

        Map<String, String> customParams = vmTO.getDetails();
        boolean isUefiEnabled = false;
        boolean isSecureBoot = false;
        String bootMode = null;

        if (MapUtils.isNotEmpty(customParams) && customParams.containsKey(GuestDef.BootType.UEFI.toString())) {
            isUefiEnabled = true;
            LOGGER.debug(String.format("Enabled UEFI for VM UUID [%s].", uuid));

            if (isSecureMode(customParams.get(GuestDef.BootType.UEFI.toString()))) {
                LOGGER.debug(String.format("Enabled Secure Boot for VM UUID [%s].", uuid));
                isSecureBoot = true;
            }

            bootMode = customParams.get(GuestDef.BootType.UEFI.toString());
        }

        Map<String, String> extraConfig = vmTO.getExtraConfig();
        if (dpdkSupport && (!extraConfig.containsKey(DpdkHelper.DPDK_NUMA) || !extraConfig.containsKey(DpdkHelper.DPDK_HUGE_PAGES))) {
            LOGGER.info(String.format("DPDK is enabled for VM [%s], but it needs extra configurations for CPU NUMA and Huge Pages for VM deployment.", vmTO.toString()));
        }
        configureVM(vmTO, vm, customParams, isUefiEnabled, isSecureBoot, bootMode, extraConfig, uuid);
        return vm;
    }

    /**
     * Configures created VM from specification, adding the necessary components to VM.
     */
    private void configureVM(VirtualMachineTO vmTO, LibvirtVMDef vm, Map<String, String> customParams, boolean isUefiEnabled, boolean isSecureBoot, String bootMode,
            Map<String, String> extraConfig, String uuid) {
        LOGGER.debug(String.format("Configuring VM with UUID [%s].", uuid));

        GuestDef guest = createGuestFromSpec(vmTO, vm, uuid, customParams);
        if (isUefiEnabled) {
            configureGuestIfUefiEnabled(isSecureBoot, bootMode, guest);
        }

        vm.addComp(guest);
        vm.addComp(createGuestResourceDef(vmTO));

        int vcpus = vmTO.getCpus();
        if (!extraConfig.containsKey(DpdkHelper.DPDK_NUMA)) {
            vm.addComp(createCpuModeDef(vmTO, vcpus));
        }

        if (hypervisorLibvirtVersion >= MIN_LIBVIRT_VERSION_FOR_GUEST_CPU_TUNE) {
            vm.addComp(createCpuTuneDef(vmTO));
        }

        FeaturesDef features = createFeaturesDef(customParams, isUefiEnabled, isSecureBoot);
        enlightenWindowsVm(vmTO, features);
        vm.addComp(features);

        vm.addComp(createTermPolicy());
        vm.addComp(createClockDef(vmTO));
        vm.addComp(createDevicesDef(vmTO, guest, vcpus, isUefiEnabled));

        addExtraConfigsToVM(vmTO, vm, extraConfig);
    }

    /**
     *  Adds extra configuration to User VM Domain XML before starting.
     */
    private void addExtraConfigsToVM(VirtualMachineTO vmTO, LibvirtVMDef vm, Map<String, String> extraConfig) {
        if (MapUtils.isNotEmpty(extraConfig) && VirtualMachine.Type.User.equals(vmTO.getType())) {
            LOGGER.debug(String.format("Appending extra configuration data [%s] to guest VM [%s] domain XML.", extraConfig, vmTO.toString()));
            addExtraConfigComponent(extraConfig, vm);
        }
    }

    /**
     * Adds devices components to VM.
     */
    protected DevicesDef createDevicesDef(VirtualMachineTO vmTO, GuestDef guest, int vcpus, boolean isUefiEnabled) {
        DevicesDef devices = new DevicesDef();
        devices.setEmulatorPath(hypervisorPath);
        devices.setGuestType(guest.getGuestType());
        devices.addDevice(createSerialDef());

        if (rngEnable) {
            devices.addDevice(createRngDef());
        }

        devices.addDevice(createChannelDef(vmTO));
        if (!isGuestS390x()) {
            devices.addDevice(createWatchDogDef());
        }
        devices.addDevice(createVideoDef(vmTO));
        devices.addDevice(createConsoleDef());
        devices.addDevice(createGraphicDef(vmTO));

        if (vmTO.getGpuDevice() != null && CollectionUtils.isNotEmpty(vmTO.getGpuDevice().getGpuDevices())) {
            attachGpuDevices(vmTO, devices);
        }

        if (!isGuestS390x()) {
            devices.addDevice(createTabletInputDef());
        }

        TpmDef tpmDef = createTpmDef(vmTO);
        if (tpmDef != null) {
            devices.addDevice(tpmDef);
        }

        if (isGuestAarch64()) {
            createArm64UsbDef(devices);
        }

        DiskDef.DiskBus busT = getDiskModelFromVMDetail(vmTO);
        if (busT == null) {
            busT = getGuestDiskModel(vmTO.getPlatformEmulator(), isUefiEnabled);
        }

        if (busT == DiskDef.DiskBus.SCSI) {
            Map<String, String> details = vmTO.getDetails();

            boolean isIothreadsEnabled = details != null && details.containsKey(VmDetailConstants.IOTHREADS);
            addSCSIControllers(devices, vcpus, vmTO.getDisks().length, isIothreadsEnabled);
        }
        return devices;
    }

    protected void attachGpuDevices(final VirtualMachineTO vmTO, final DevicesDef devicesDef) {
        // GPU device is not set for the VM
        List<VgpuTypesInfo> gpuDevices = vmTO.getGpuDevice().getGpuDevices();
        for (VgpuTypesInfo gpuDevice : gpuDevices) {
            LibvirtGpuDef gpu = new LibvirtGpuDef();

            gpu.defGpu(gpuDevice);

            devicesDef.addDevice(gpu);
            LOGGER.info("Attached GPU device " + gpuDevice.getDeviceName() + " to VM " + vmTO.getName());
        }
    }

    protected WatchDogDef createWatchDogDef() {
        return new WatchDogDef(watchDogAction, watchDogModel);
    }

    protected void createArm64UsbDef(DevicesDef devices) {
        devices.addDevice(new InputDef(KEYBOARD, USB));
        devices.addDevice(new InputDef(MOUSE, USB));
        devices.addDevice(new LibvirtVMDef.USBDef((short)0, 0, 5, 0, 0));
    }

    protected InputDef createTabletInputDef() {
        return new InputDef(TABLET, USB);
    }

    /**
     * Creates a Libvirt Graphic Definition with the VM's password and VNC address.
     */
    protected GraphicDef createGraphicDef(VirtualMachineTO vmTO) {
        return new GraphicDef(VNC, (short)0, true, vmTO.getVncAddr(), vmTO.getVncPassword(), null);
    }

    /**
     * Adds a Virtio channel for the Qemu Guest Agent tools.
     */
    protected ChannelDef createChannelDef(VirtualMachineTO vmTO) {
        File virtIoChannel = Paths.get(qemuSocketsPath.getPath(), vmTO.getName() + "." + qemuGuestAgentSocketName).toFile();
        return new ChannelDef(qemuGuestAgentSocketName, ChannelDef.ChannelType.UNIX, virtIoChannel);
    }

    /**
     * Creates Virtio SCSI controller. <br>
     * The respective Virtio SCSI XML definition is generated only if the VM's Disk Bus is of ISCSI.
     */
    protected SCSIDef createSCSIDef(short index, int vcpus, boolean isIothreadsEnabled) {
        return new SCSIDef(index, 0, 0, 9 + index, 0, vcpus, isIothreadsEnabled);
    }


    private void addSCSIControllers(DevicesDef devices, int vcpus, int diskCount, boolean isIothreadsEnabled) {
        int controllers = diskCount / 7;
        if (diskCount % 7 != 0) {
            controllers++;
        }
        for (int i = 0; i < controllers; i++) {
            devices.addDevice(createSCSIDef((short)i, vcpus, isIothreadsEnabled));
        }
    }

    protected ConsoleDef createConsoleDef() {
        return new ConsoleDef(PTY, null, null, (short)0);
    }

    protected VideoDef createVideoDef(VirtualMachineTO vmTO) {
        Map<String, String> details = vmTO.getDetails();
        String videoHw = this.videoHw;
        int videoRam = this.videoRam;
        if (details != null) {
            if (details.containsKey(VmDetailConstants.VIDEO_HARDWARE)) {
                videoHw = details.get(VmDetailConstants.VIDEO_HARDWARE);
            }
            if (details.containsKey(VmDetailConstants.VIDEO_RAM)) {
                String value = details.get(VmDetailConstants.VIDEO_RAM);
                videoRam = NumbersUtil.parseInt(value, videoRam);
            }
        }
        return new VideoDef(videoHw, videoRam);
    }

    protected RngDef createRngDef() {
        return new RngDef(rngPath, rngBackendModel, rngRateBytes, rngRatePeriod);
    }

    protected SerialDef createSerialDef() {
        return new SerialDef(PTY, null, (short)0);
    }

    protected ClockDef createClockDef(final VirtualMachineTO vmTO) {
        ClockDef clock = new ClockDef();
        if (StringUtils.startsWith(vmTO.getOs(), WINDOWS)) {
            clock.setClockOffset(ClockDef.ClockOffset.LOCALTIME);
            clock.setTimer(HYPERVCLOCK, null, null);
        } else if ((vmTO.getType() != VirtualMachine.Type.User || isGuestPVEnabled(vmTO.getOs())) && hypervisorLibvirtVersion >= MIN_LIBVIRT_VERSION_FOR_GUEST_CPU_MODE) {
            clock.setTimer(KVMCLOCK, null, null, noKvmClock);
        }
        return clock;
    }

    protected TermPolicy createTermPolicy() {
        TermPolicy term = new TermPolicy();
        term.setCrashPolicy(DESTROY);
        term.setPowerOffPolicy(DESTROY);
        term.setRebootPolicy(RESTART);
        return term;
    }

    protected FeaturesDef createFeaturesDef(Map<String, String> customParams, boolean isUefiEnabled, boolean isSecureBoot) {
        FeaturesDef features = new FeaturesDef();
        features.addFeatures(PAE);
        features.addFeatures(APIC);
        if (!isHostS390x()) {
            features.addFeatures(ACPI);
        }
        if (isUefiEnabled && isSecureBoot) {
            features.addFeatures(SMM);
        }
        return features;
    }

    /**
     * A 4.0.X/4.1.X management server doesn't send the correct JSON
     * command for getMinSpeed, it only sends a 'speed' field.<br>
     * So, to create a cpu tune,  if getMinSpeed() returns null we fall back to getSpeed().<br>
     * This way a >4.1 agent can work communicate a <=4.1 management server.<br>
     * This change is due to the overcommit feature in 4.2.
     */
    protected CpuTuneDef createCpuTuneDef(VirtualMachineTO vmTO) {
        CpuTuneDef ctd = new CpuTuneDef();
        ctd.setShares(calculateCpuShares(vmTO));
        setQuotaAndPeriod(vmTO, ctd);
        return ctd;
    }

    /**
     * Calculates the VM CPU shares considering the cgroup version of the host.
     * <ul>
     *     <li>
     *         If the host utilize cgroup v1, then, the CPU shares is calculated as <b>VM CPU shares = CPU cores * CPU frequency</b>.
     *     </li>
     *     <li>
     *         If the host utilize cgroup v2, the CPU shares calculation considers the cgroup v2 upper limit of <b>10,000</b>, and a linear scale conversion is applied
     *         considering the maximum host CPU shares (i.e. using the number of CPU cores and CPU nominal frequency of the host). Therefore, the VM CPU shares is calculated as
     *         <b>VM CPU shares = (VM requested shares * cgroup upper limit) / host max shares</b>.
     *     </li>
     * </ul>
     */
    public int calculateCpuShares(VirtualMachineTO vmTO) {
        int vCpus = vmTO.getCpus();
        int cpuSpeed = ObjectUtils.defaultIfNull(vmTO.getMinSpeed(), vmTO.getSpeed());
        int requestedCpuShares = vCpus * cpuSpeed;
        int hostCpuMaxCapacity = getHostCpuMaxCapacity();

        // cgroup v2 is in use
        if (hostCpuMaxCapacity > 0) {

            int updatedCpuShares = (int) Math.ceil((requestedCpuShares * LIBVIRT_CGROUPV2_WEIGHT_MAX) / (double) hostCpuMaxCapacity);
            LOGGER.debug("This host utilizes cgroupv2 (as the max shares value is [{}]), thus, the VM requested shares of [{}] will be converted to " +
                    "consider the host limits; the new CPU shares value is [{}].", hostCpuMaxCapacity, requestedCpuShares, updatedCpuShares);

            if (updatedCpuShares < LIBVIRT_CGROUPV2_WEIGHT_MIN) updatedCpuShares = LIBVIRT_CGROUPV2_WEIGHT_MIN;
            if (updatedCpuShares > LIBVIRT_CGROUPV2_WEIGHT_MAX) updatedCpuShares = LIBVIRT_CGROUPV2_WEIGHT_MAX;
            return updatedCpuShares;
        }

        // cgroup v1 is in use
        LOGGER.debug("This host does not have a maximum CPU shares set; therefore, this host utilizes cgroupv1 and the VM requested CPU shares [{}] will not be " +
                "converted.", requestedCpuShares);

        if (requestedCpuShares < LIBVIRT_CGROUP_CPU_SHARES_MIN) requestedCpuShares = LIBVIRT_CGROUP_CPU_SHARES_MIN;
        if (requestedCpuShares > LIBVIRT_CGROUP_CPU_SHARES_MAX) requestedCpuShares = LIBVIRT_CGROUP_CPU_SHARES_MAX;
        return requestedCpuShares;
    }

    private CpuModeDef createCpuModeDef(VirtualMachineTO vmTO, int vcpus) {
        final CpuModeDef cmd = new CpuModeDef();
        Map<String, String> details = vmTO.getDetails();
        String cpuMode = MapUtils.isNotEmpty(details) && details.get(VmDetailConstants.GUEST_CPU_MODE) != null ? details.get(VmDetailConstants.GUEST_CPU_MODE) : guestCpuMode;
        String cpuModel = MapUtils.isNotEmpty(details) && details.get(VmDetailConstants.GUEST_CPU_MODEL) != null ? details.get(VmDetailConstants.GUEST_CPU_MODEL) : guestCpuModel;
        cmd.setMode(cpuMode);
        cmd.setModel(cpuModel);
        if (VirtualMachine.Type.User.equals(vmTO.getType())) {
            cmd.setFeatures(cpuFeatures);
        }
        int vCpusInDef = vmTO.getVcpuMaxLimit() == null ? vcpus : vmTO.getVcpuMaxLimit();
        setCpuTopology(cmd, vCpusInDef, vmTO.getDetails());
        return cmd;
    }

    protected TpmDef createTpmDef(VirtualMachineTO vmTO) {
        Map<String, String> details = vmTO.getDetails();
        if (MapUtils.isEmpty(details)) {
            return null;
        }
        String tpmModel = details.get(VmDetailConstants.VIRTUAL_TPM_MODEL);
        if (tpmModel == null) {
            return null;
        }
        String tpmVersion = details.get(VmDetailConstants.VIRTUAL_TPM_VERSION);
        return new TpmDef(tpmModel, tpmVersion);
    }

    private void configureGuestIfUefiEnabled(boolean isSecureBoot, String bootMode, GuestDef guest) {
        setGuestLoader(bootMode, SECURE, guest, GuestDef.GUEST_LOADER_SECURE);
        setGuestLoader(bootMode, LEGACY, guest, GuestDef.GUEST_LOADER_LEGACY);

        if (isUefiPropertieNotNull(GuestDef.GUEST_NVRAM_PATH)) {
            guest.setNvram(uefiProperties.getProperty(GuestDef.GUEST_NVRAM_PATH));
        }

        if (isSecureBoot && isUefiPropertieNotNull(GuestDef.GUEST_NVRAM_TEMPLATE_SECURE) && SECURE.equalsIgnoreCase(bootMode)) {
            guest.setNvramTemplate(uefiProperties.getProperty(GuestDef.GUEST_NVRAM_TEMPLATE_SECURE));
        } else if (isUefiPropertieNotNull(GuestDef.GUEST_NVRAM_TEMPLATE_LEGACY)) {
            guest.setNvramTemplate(uefiProperties.getProperty(GuestDef.GUEST_NVRAM_TEMPLATE_LEGACY));
        }
    }

    private void setGuestLoader(String bootMode, String mode, GuestDef guest, String propertie) {
        if (isUefiPropertieNotNull(propertie) && mode.equalsIgnoreCase(bootMode)) {
            guest.setLoader(uefiProperties.getProperty(propertie));
        }
    }

    private boolean isUefiPropertieNotNull(String propertie) {
        return uefiProperties.getProperty(propertie) != null;
    }

    public boolean isGuestAarch64() {
        return AARCH64.equals(guestCpuArch);
    }

    private boolean isGuestS390x() {
        return S390X.equals(guestCpuArch);
    }

    /**
     * Creates a guest definition from a VM specification.
     */
    protected GuestDef createGuestFromSpec(VirtualMachineTO vmTO, LibvirtVMDef vm, String uuid, Map<String, String> customParams) {
        GuestDef guest = new GuestDef();

        configureGuestAndVMHypervisorType(vmTO, vm, guest);
        guest.setManufacturer(vmTO.getMetadataManufacturer());
        guest.setProduct(vmTO.getMetadataProductName());
        guest.setGuestArch(guestCpuArch != null ? guestCpuArch : vmTO.getArch());
        guest.setMachineType((isGuestAarch64() || isGuestS390x()) ? VIRT : PC);
        guest.setBootType(GuestDef.BootType.BIOS);
        if (MapUtils.isNotEmpty(customParams)) {
            if (customParams.containsKey(GuestDef.BootType.UEFI.toString())) {
                guest.setBootType(GuestDef.BootType.UEFI);
                guest.setBootMode(GuestDef.BootMode.LEGACY);
                guest.setMachineType(Q35);
                if (SECURE.equalsIgnoreCase(customParams.get(GuestDef.BootType.UEFI.toString()))) {
                    guest.setBootMode(GuestDef.BootMode.SECURE);
                }
            }
            guest.setIothreads(customParams.containsKey(VmDetailConstants.IOTHREADS));
        }
        guest.setUuid(uuid);
        if(!isGuestS390x()) {
            guest.setBootOrder(GuestDef.BootOrder.CDROM);
        }
        guest.setBootOrder(GuestDef.BootOrder.HARDISK);
        return guest;
    }

    protected void configureGuestAndVMHypervisorType(VirtualMachineTO vmTO, LibvirtVMDef vm, GuestDef guest) {
        if (HypervisorType.LXC == hypervisorType && VirtualMachine.Type.User.equals(vmTO.getType())) {
            configureGuestAndUserVMToUseLXC(vm, guest);
        } else {
            configureGuestAndSystemVMToUseKVM(vm, guest);
        }
    }

    /**
     * KVM domain is only valid for system VMs. Use LXC for user VMs.
     */
    private void configureGuestAndSystemVMToUseKVM(LibvirtVMDef vm, GuestDef guest) {
        guest.setGuestType(GuestDef.GuestType.KVM);
        vm.setHvsType(HypervisorType.KVM.toString().toLowerCase());
        vm.setLibvirtVersion(hypervisorLibvirtVersion);
        vm.setQemuVersion(hypervisorQemuVersion);
    }

    /**
     * LXC domain is only valid for user VMs. Use KVM for system VMs.
     */
    private void configureGuestAndUserVMToUseLXC(LibvirtVMDef vm, GuestDef guest) {
        guest.setGuestType(GuestDef.GuestType.LXC);
        vm.setHvsType(HypervisorType.LXC.toString().toLowerCase());
    }

    /**
     * Creates guest resources based in VM specification.
     */
    protected GuestResourceDef createGuestResourceDef(VirtualMachineTO vmTO){
        GuestResourceDef grd = new GuestResourceDef();

        grd.setMemBalloning(!noMemBalloon);

        Long maxRam = ByteScaleUtils.bytesToKibibytes(vmTO.getMaxRam());

        grd.setMemorySize(maxRam);
        grd.setCurrentMem(getCurrentMemAccordingToMemBallooning(vmTO, maxRam));

        int vcpus = vmTO.getCpus();
        Integer maxVcpus = vmTO.getVcpuMaxLimit();

        grd.setVcpuNum(vcpus);
        grd.setMaxVcpuNum(maxVcpus == null ? vcpus : maxVcpus);

        return grd;
    }

    protected long getCurrentMemAccordingToMemBallooning(VirtualMachineTO vmTO, long maxRam) {
        long retVal = maxRam;
        if (noMemBalloon) {
            LOGGER.warn(String.format("Setting VM's [%s] current memory as max memory [%s] due to memory ballooning is disabled. If you are using a custom service offering, verify if memory ballooning really should be disabled.", vmTO.toString(), maxRam));
        } else if (vmTO != null && vmTO.getType() != VirtualMachine.Type.User) {
            LOGGER.warn(String.format("Setting System VM's [%s] current memory as max memory [%s].", vmTO.toString(), maxRam));
        } else {
            long minRam = ByteScaleUtils.bytesToKibibytes(vmTO.getMinRam());
            LOGGER.debug(String.format("Setting VM's [%s] current memory as min memory [%s] due to memory ballooning is enabled.", vmTO.toString(), minRam));
            retVal = minRam;
        }
        return retVal;
    }

    /**
     * Adds extra configurations (if any) as a String component to the domain XML
     */
    protected void addExtraConfigComponent(Map<String, String> extraConfig, LibvirtVMDef vm) {
        if (MapUtils.isNotEmpty(extraConfig)) {
            StringBuilder extraConfigBuilder = new StringBuilder();
            for (String key : extraConfig.keySet()) {
                if (!key.startsWith(DpdkHelper.DPDK_INTERFACE_PREFIX) && !key.equals(DpdkHelper.DPDK_VHOST_USER_MODE)) {
                    extraConfigBuilder.append(extraConfig.get(key));
                }
            }
            String comp = extraConfigBuilder.toString();
            if (StringUtils.isNotBlank(comp)) {
                vm.addComp(comp);
            }
        }
    }

    public void createVifs(final VirtualMachineTO vmSpec, final LibvirtVMDef vm) throws InternalErrorException, LibvirtException {
        final NicTO[] nics = vmSpec.getNics();
        final Map <String, String> params = vmSpec.getDetails();
        String nicAdapter = "";
        if (params != null && params.get("nicAdapter") != null && !params.get("nicAdapter").isEmpty()) {
            nicAdapter = params.get("nicAdapter");
        }
        Map<String, String> extraConfig = vmSpec.getExtraConfig();
        for (int i = 0; i < nics.length; i++) {
            for (final NicTO nic : vmSpec.getNics()) {
                if (nic.getDeviceId() == i) {
                    createVif(vm, vmSpec, nic, nicAdapter, extraConfig);
                }
            }
        }
    }

    public String getVolumePath(final Connect conn, final DiskTO volume) throws LibvirtException, URISyntaxException {
        return getVolumePath(conn, volume, false);
    }

    public String getVolumePath(final Connect conn, final DiskTO volume, boolean diskOnHostCache) throws LibvirtException, URISyntaxException {
        final DataTO data = volume.getData();
        final DataStoreTO store = data.getDataStore();
        final String dataPath = data.getPath();

        if (volume.getType() == Volume.Type.ISO && dataPath != null) {
            if (dataPath.startsWith(ConfigDrive.CONFIGDRIVEDIR) && diskOnHostCache) {
                return getConfigPath() + "/" + data.getPath();
            }

            if (store instanceof NfsTO || store instanceof PrimaryDataStoreTO && data instanceof TemplateObjectTO && !((TemplateObjectTO) data).isDirectDownload()) {
                final String isoPath = store.getUrl().split("\\?")[0] + File.separator + dataPath;
                final int index = isoPath.lastIndexOf("/");
                final String path = isoPath.substring(0, index);
                final String name = isoPath.substring(index + 1);
                final KVMStoragePool secondaryPool = storagePoolManager.getStoragePoolByURI(path);
                final KVMPhysicalDisk isoVol = secondaryPool.getPhysicalDisk(name);
                return isoVol.getPath();
            }
        }

        return dataPath;
    }

    public static boolean useBLOCKDiskType(KVMPhysicalDisk physicalDisk) {
        return physicalDisk != null &&
                physicalDisk.getPool() != null &&
                StoragePoolType.Linstor.equals(physicalDisk.getPool().getType()) &&
                physicalDisk.getFormat() != null &&
                PhysicalDiskFormat.RAW.equals(physicalDisk.getFormat());
    }

    public static DiskDef.DiskType getDiskType(KVMPhysicalDisk physicalDisk) {
        return useBLOCKDiskType(physicalDisk) ? DiskDef.DiskType.BLOCK : DiskDef.DiskType.FILE;
    }

    public void createVbd(final Connect conn, final VirtualMachineTO vmSpec, final String vmName, final LibvirtVMDef vm) throws InternalErrorException, LibvirtException, URISyntaxException {
        final Map<String, String> details = vmSpec.getDetails();
        final List<DiskTO> disks = Arrays.asList(vmSpec.getDisks());
        boolean isSecureBoot = false;
        boolean isWindowsTemplate = false;
        Collections.sort(disks, new Comparator<DiskTO>() {
            @Override
            public int compare(final DiskTO arg0, final DiskTO arg1) {
                return arg0.getDiskSeq() > arg1.getDiskSeq() ? 1 : -1;
            }
        });

        boolean isUefiEnabled = MapUtils.isNotEmpty(details) && details.containsKey(GuestDef.BootType.UEFI.toString());
        if (isUefiEnabled) {
            isSecureBoot = isSecureMode(details.get(GuestDef.BootType.UEFI.toString()));
        }

        if (vmSpec.getOs().toLowerCase().contains("window")) {
            isWindowsTemplate =true;
        }
        for (final DiskTO volume : disks) {
            KVMPhysicalDisk physicalDisk = null;
            KVMStoragePool pool = null;
            final DataTO data = volume.getData();
            if (volume.getType() == Volume.Type.ISO && data.getPath() != null) {
                DataStoreTO dataStore = data.getDataStore();
                String dataStoreUrl = null;
                if (data.getPath().startsWith(ConfigDrive.CONFIGDRIVEDIR) && vmSpec.isConfigDriveOnHostCache() && data instanceof TemplateObjectTO) {
                    String configDrivePath = getConfigPath() + "/" + data.getPath();
                    physicalDisk = new KVMPhysicalDisk(configDrivePath, ((TemplateObjectTO) data).getUuid(), null);
                    physicalDisk.setFormat(PhysicalDiskFormat.FILE);
                } else if (dataStore instanceof NfsTO) {
                    NfsTO nfsStore = (NfsTO)data.getDataStore();
                    dataStoreUrl = nfsStore.getUrl();
                    physicalDisk = getPhysicalDiskFromNfsStore(dataStoreUrl, data);
                } else if (dataStore instanceof PrimaryDataStoreTO) {
                    //In order to support directly downloaded ISOs
                    PrimaryDataStoreTO primaryDataStoreTO = (PrimaryDataStoreTO) dataStore;
                    if (primaryDataStoreTO.getPoolType().equals(StoragePoolType.NetworkFilesystem)) {
                        String psHost = primaryDataStoreTO.getHost();
                        String psPath = primaryDataStoreTO.getPath();
                        dataStoreUrl = "nfs://" + psHost + File.separator + psPath;
                        physicalDisk = getPhysicalDiskFromNfsStore(dataStoreUrl, data);
                    } else if (primaryDataStoreTO.getPoolType().equals(StoragePoolType.SharedMountPoint) ||
                            primaryDataStoreTO.getPoolType().equals(StoragePoolType.Filesystem) ||
                            primaryDataStoreTO.getPoolType().equals(StoragePoolType.StorPool) ||
                            primaryDataStoreTO.getPoolType().equals(StoragePoolType.Linstor)) {
                        physicalDisk = getPhysicalDiskPrimaryStore(primaryDataStoreTO, data);
                    }
                }
            } else if (volume.getType() != Volume.Type.ISO) {
                final PrimaryDataStoreTO store = (PrimaryDataStoreTO)data.getDataStore();
                physicalDisk = getStoragePoolMgr().getPhysicalDisk(store.getPoolType(), store.getUuid(), data.getPath());
                pool = physicalDisk.getPool();
            }

            String volPath = null;
            if (physicalDisk != null) {
                volPath = physicalDisk.getPath();
            }

            if (volume.getType() != Volume.Type.ISO
                    && physicalDisk != null && physicalDisk.getFormat() == PhysicalDiskFormat.QCOW2
                    && (pool.getType() == StoragePoolType.NetworkFilesystem
                    || pool.getType() == StoragePoolType.SharedMountPoint
                    || pool.getType() == StoragePoolType.Filesystem
                    || pool.getType() == StoragePoolType.Gluster
                    || pool.getType() == StoragePoolType.StorPool)) {
                setBackingFileFormat(physicalDisk.getPath());
            }

            // check for disk activity, if detected we should exit because vm is running elsewhere
            if (diskActivityCheckEnabled && physicalDisk != null && physicalDisk.getFormat() == PhysicalDiskFormat.QCOW2) {
                LOGGER.debug("Checking physical disk file at path " + volPath + " for disk activity to ensure vm is not running elsewhere");
                try {
                    HypervisorUtils.checkVolumeFileForActivity(volPath, diskActivityCheckTimeoutSeconds, diskActivityInactiveThresholdMilliseconds, diskActivityCheckFileSizeMin);
                } catch (final IOException ex) {
                    throw new CloudRuntimeException("Unable to check physical disk file for activity", ex);
                }
                LOGGER.debug("Disk activity check cleared");
            }

            // if params contains a rootDiskController key, use its value (this is what other HVs are doing)
            DiskDef.DiskBus diskBusType = getDiskModelFromVMDetail(vmSpec);
            if (diskBusType == null) {
                diskBusType = getGuestDiskModel(vmSpec.getPlatformEmulator(), isUefiEnabled);
            }

            DiskDef.DiskBus diskBusTypeData = getDataDiskModelFromVMDetail(vmSpec);
            if (diskBusTypeData == null) {
                diskBusTypeData = (diskBusType == DiskDef.DiskBus.SCSI) ? diskBusType : DiskDef.DiskBus.VIRTIO;
            }

            final DiskDef disk = new DiskDef();
            int devId = volume.getDiskSeq().intValue();
            if (volume.getType() == Volume.Type.ISO) {
                final DiskDef.DiskType diskType = getDiskType(physicalDisk);
                disk.defISODisk(volPath, devId, isUefiEnabled, diskType);

                if (guestCpuArch != null && (guestCpuArch.equals("aarch64") || guestCpuArch.equals("s390x"))) {
                    disk.setBusType(DiskDef.DiskBus.SCSI);
                }
            } else {
                if (pool == null) {
                    throw new CloudRuntimeException(String.format("Found null pool for volume %s", volume));
                }

                disk.setLogicalBlockIOSize(pool.getSupportedLogicalBlockSize());
                disk.setPhysicalBlockIOSize(pool.getSupportedPhysicalBlockSize());

                if (diskBusType == DiskDef.DiskBus.SCSI || diskBusType == DiskDef.DiskBus.VIRTIOBLK) {
                    disk.setQemuDriver(true);
                    disk.setDiscard(DiscardType.UNMAP);
                }

                boolean iothreadsEnabled = MapUtils.isNotEmpty(details) && details.containsKey(VmDetailConstants.IOTHREADS);
                disk.isIothreadsEnabled(iothreadsEnabled);

                String ioDriver =  null;

                if (MapUtils.isNotEmpty(volume.getDetails()) && volume.getDetails().containsKey(VmDetailConstants.IO_POLICY)) {
                    ioDriver = volume.getDetails().get(VmDetailConstants.IO_POLICY).toUpperCase();
                } else if (iothreadsEnabled) {
                    ioDriver = IoDriverPolicy.THREADS.name();
                }

                setDiskIoDriver(disk, getIoDriverForTheStorage(ioDriver));

                if (pool.getType() == StoragePoolType.RBD) {
                    /*
                            For RBD pools we use the secret mechanism in libvirt.
                            We store the secret under the UUID of the pool, that's why
                            we pass the pool's UUID as the authSecret
                     */
                    disk.defNetworkBasedDisk(physicalDisk.getPath().replace("rbd:", ""), pool.getSourceHost(), pool.getSourcePort(), pool.getAuthUserName(),
                            pool.getUuid(), devId, diskBusType, DiskProtocol.RBD, DiskDef.DiskFmtType.RAW);
                } else if (pool.getType() == StoragePoolType.PowerFlex) {
                    disk.defBlockBasedDisk(physicalDisk.getPath(), devId, diskBusTypeData);
                    if (physicalDisk.getFormat().equals(PhysicalDiskFormat.QCOW2)) {
                        disk.setDiskFormatType(DiskDef.DiskFmtType.QCOW2);
                    }
                } else if (pool.getType() == StoragePoolType.Gluster) {
                    final String mountpoint = pool.getLocalPath();
                    final String path = physicalDisk.getPath();
                    final String glusterVolume = pool.getSourceDir().replace("/", "");
                    disk.defNetworkBasedDisk(glusterVolume + path.replace(mountpoint, ""), pool.getSourceHost(), pool.getSourcePort(), null,
                            null, devId, diskBusType, DiskProtocol.GLUSTER, DiskDef.DiskFmtType.QCOW2);
                } else if (pool.getType() == StoragePoolType.CLVM || physicalDisk.getFormat() == PhysicalDiskFormat.RAW) {
                    if (volume.getType() == Volume.Type.DATADISK && !(isWindowsTemplate && isUefiEnabled)) {
                        disk.defBlockBasedDisk(physicalDisk.getPath(), devId, diskBusTypeData);
                    }
                    else {
                        disk.defBlockBasedDisk(physicalDisk.getPath(), devId, diskBusType);
                    }
                    if (pool.getType() == StoragePoolType.Linstor && isQemuDiscardBugFree(diskBusType)) {
                        disk.setDiscard(DiscardType.UNMAP);
                    }
                } else {
                    if (volume.getType() == Volume.Type.DATADISK && !(isWindowsTemplate && isUefiEnabled)) {
                        disk.defFileBasedDisk(physicalDisk.getPath(), devId, diskBusTypeData, DiskDef.DiskFmtType.QCOW2);
                    } else {
                        if (isSecureBoot) {
                            disk.defFileBasedDisk(physicalDisk.getPath(), devId, DiskDef.DiskFmtType.QCOW2, isWindowsTemplate);
                        } else {
                            disk.defFileBasedDisk(physicalDisk.getPath(), devId, diskBusType, DiskDef.DiskFmtType.QCOW2);
                        }
                    }

                }
                pool.customizeLibvirtDiskDef(disk);
            }

            if (data instanceof VolumeObjectTO) {
                final VolumeObjectTO volumeObjectTO = (VolumeObjectTO)data;
                disk.setSerial(diskUuidToSerial(volumeObjectTO.getUuid()));
                setBurstProperties(volumeObjectTO, disk);

                if (volumeObjectTO.getCacheMode() != null) {
                    disk.setCacheMode(DiskDef.DiskCacheMode.valueOf(volumeObjectTO.getCacheMode().toString().toUpperCase()));
                }

                if (volumeObjectTO.requiresEncryption() &&
                        pool.getType().encryptionSupportMode() == Storage.EncryptionSupport.Hypervisor ) {
                    String secretUuid = createLibvirtVolumeSecret(conn, volumeObjectTO.getPath(), volumeObjectTO.getPassphrase());
                    DiskDef.LibvirtDiskEncryptDetails encryptDetails = new DiskDef.LibvirtDiskEncryptDetails(secretUuid, QemuObject.EncryptFormat.enumValue(volumeObjectTO.getEncryptFormat()));
                    disk.setLibvirtDiskEncryptDetails(encryptDetails);
                }
            }
            if (vm.getDevices() == null) {
                LOGGER.error("There is no devices for" + vm);
                throw new RuntimeException("There is no devices for" + vm);
            }
            vm.getDevices().addDevice(disk);
        }

        if (vmSpec.getType() != VirtualMachine.Type.User) {
            final DiskDef iso = new DiskDef();
            iso.defISODisk(sysvmISOPath, DiskDef.DiskType.FILE);
            if (guestCpuArch != null && (guestCpuArch.equals("aarch64") || guestCpuArch.equals("s390x"))) {
                iso.setBusType(DiskDef.DiskBus.SCSI);
            }
            vm.getDevices().addDevice(iso);
        }

        // For LXC, find and add the root filesystem, rbd data disks
        if (HypervisorType.LXC.toString().toLowerCase().equals(vm.getHvsType())) {
            for (final DiskTO volume : disks) {
                final DataTO data = volume.getData();
                final PrimaryDataStoreTO store = (PrimaryDataStoreTO)data.getDataStore();
                if (volume.getType() == Volume.Type.ROOT) {
                    final KVMPhysicalDisk physicalDisk = storagePoolManager.getPhysicalDisk(store.getPoolType(), store.getUuid(), data.getPath());
                    final FilesystemDef rootFs = new FilesystemDef(physicalDisk.getPath(), "/");
                    vm.getDevices().addDevice(rootFs);
                } else if (volume.getType() == Volume.Type.DATADISK) {
                    final KVMPhysicalDisk physicalDisk = storagePoolManager.getPhysicalDisk(store.getPoolType(), store.getUuid(), data.getPath());
                    final KVMStoragePool pool = physicalDisk.getPool();
                    if(StoragePoolType.RBD.equals(pool.getType())) {
                        final int devId = volume.getDiskSeq().intValue();
                        final String device = mapRbdDevice(physicalDisk);
                        if (device != null) {
                            LOGGER.debug("RBD device on host is: " + device);
                            final DiskDef diskdef = new DiskDef();
                            diskdef.defBlockBasedDisk(device, devId, DiskDef.DiskBus.VIRTIO);
                            diskdef.setQemuDriver(false);
                            vm.getDevices().addDevice(diskdef);
                        } else {
                            throw new InternalErrorException("Error while mapping RBD device on host");
                        }
                    }
                }
            }
        }
    }

    private KVMPhysicalDisk getPhysicalDiskPrimaryStore(PrimaryDataStoreTO primaryDataStoreTO, DataTO data) {
        KVMStoragePool storagePool = storagePoolManager.getStoragePool(primaryDataStoreTO.getPoolType(), primaryDataStoreTO.getUuid());
        return storagePool.getPhysicalDisk(data.getPath());
    }

    /**
     * Check if IO_URING is supported by qemu
     */
    protected boolean isIoUringSupportedByQemu() {
        LOGGER.debug("Checking if iouring is supported");
        String command = getIoUringCheckCommand();
        if (org.apache.commons.lang3.StringUtils.isBlank(command)) {
            LOGGER.debug("Could not check iouring support, disabling it");
            return false;
        }
        int exitValue = executeBashScriptAndRetrieveExitValue(command);
        return exitValue == 0;
    }

    protected String getIoUringCheckCommand() {
        String[] qemuPaths = { "/usr/bin/qemu-system-x86_64", "/usr/libexec/qemu-kvm", "/usr/bin/qemu-kvm" };
        for (String qemuPath : qemuPaths) {
            File file = new File(qemuPath);
            if (file.exists()) {
                String cmd = String.format("ldd %s | grep -Eqe '[[:space:]]liburing\\.so'", qemuPath);
                LOGGER.debug("Using the check command: " + cmd);
                return cmd;
            }
        }
        return null;
    }

    /**
     * Set Disk IO Driver, if supported by the Libvirt/Qemu version.
     * IO Driver works for:
     * (i) Qemu >= 5.0;
     * (ii) Libvirt >= 6.3.0
     */
    public void setDiskIoDriver(DiskDef disk, IoDriverPolicy ioDriver) {
        LOGGER.debug(String.format("Disk IO driver policy [%s]. The host supports the io_uring policy [%s]", ioDriver, enableIoUring));
        if (ioDriver != null) {
            if (IoDriverPolicy.IO_URING != ioDriver) {
                disk.setIoDriver(ioDriver);
            } else if (enableIoUring) {
                disk.setIoDriver(IoDriverPolicy.IO_URING);
            }
        }
    }

    public IoDriverPolicy getIoDriverForTheStorage(String ioDriver) {
        if (ioDriver == null) {
            return null;
        }
        return IoDriverPolicy.valueOf(ioDriver);
    }

    /**
     * IO_URING supported if the property 'enable.io.uring' is set to true OR it is supported by qemu
     */
    private boolean isIoUringEnabled() {
        boolean meetRequirements = getHypervisorLibvirtVersion() >= HYPERVISOR_LIBVIRT_VERSION_SUPPORTS_IO_URING
                && getHypervisorQemuVersion() >= HYPERVISOR_QEMU_VERSION_SUPPORTS_IO_URING;
        if (!meetRequirements) {
            return false;
        }
        return isUbuntuOrDebianHost() || isIoUringSupportedByQemu();
    }

    /**
     * Qemu has a bug with discard enabled on IDE bus devices if qemu version < 7.0.
     * <a href="https://bugzilla.redhat.com/show_bug.cgi?id=2029980">redhat bug entry</a>
     * @param diskBus used for the disk
     * @return true if it is safe to enable discard, otherwise false.
     */
    public boolean isQemuDiscardBugFree(DiskDef.DiskBus diskBus) {
        return diskBus != DiskDef.DiskBus.IDE || getHypervisorQemuVersion() >= HYPERVISOR_QEMU_VERSION_IDE_DISCARD_FIXED;
    }

    public boolean isUbuntuOrDebianHost() {
        Map<String, String> versionString = getVersionStrings();
        String hostKey = "Host.OS";
        if (MapUtils.isEmpty(versionString) || !versionString.containsKey(hostKey) || versionString.get(hostKey) == null) {
            return false;
        }
        return versionString.get(hostKey).equalsIgnoreCase("ubuntu")
                || versionString.get(hostKey).toLowerCase().startsWith("debian");
    }

    private KVMPhysicalDisk getPhysicalDiskFromNfsStore(String dataStoreUrl, DataTO data) {
        final String volPath = dataStoreUrl + File.separator + data.getPath();
        final int index = volPath.lastIndexOf("/");
        final String volDir = volPath.substring(0, index);
        final String volName = volPath.substring(index + 1);
        final KVMStoragePool storage = storagePoolManager.getStoragePoolByURI(volDir);
        return storage.getPhysicalDisk(volName);
    }

    private void setBurstProperties(final VolumeObjectTO volumeObjectTO, final DiskDef disk ) {
        if (volumeObjectTO.getBytesReadRate() != null && volumeObjectTO.getBytesReadRate() > 0) {
            disk.setBytesReadRate(volumeObjectTO.getBytesReadRate());
        }
        if (volumeObjectTO.getBytesReadRateMax() != null && volumeObjectTO.getBytesReadRateMax() > 0) {
            disk.setBytesReadRateMax(volumeObjectTO.getBytesReadRateMax());
        }
        if (volumeObjectTO.getBytesReadRateMaxLength() != null && volumeObjectTO.getBytesReadRateMaxLength() > 0) {
            disk.setBytesReadRateMaxLength(volumeObjectTO.getBytesReadRateMaxLength());
        }
        if (volumeObjectTO.getBytesWriteRate() != null && volumeObjectTO.getBytesWriteRate() > 0) {
            disk.setBytesWriteRate(volumeObjectTO.getBytesWriteRate());
        }
        if (volumeObjectTO.getBytesWriteRateMax() != null && volumeObjectTO.getBytesWriteRateMax() > 0) {
            disk.setBytesWriteRateMax(volumeObjectTO.getBytesWriteRateMax());
        }
        if (volumeObjectTO.getBytesWriteRateMaxLength() != null && volumeObjectTO.getBytesWriteRateMaxLength() > 0) {
            disk.setBytesWriteRateMaxLength(volumeObjectTO.getBytesWriteRateMaxLength());
        }
        if (volumeObjectTO.getIopsReadRate() != null && volumeObjectTO.getIopsReadRate() > 0) {
            disk.setIopsReadRate(volumeObjectTO.getIopsReadRate());
        }
        if (volumeObjectTO.getIopsReadRateMax() != null && volumeObjectTO.getIopsReadRateMax() > 0) {
            disk.setIopsReadRateMax(volumeObjectTO.getIopsReadRateMax());
        }
        if (volumeObjectTO.getIopsReadRateMaxLength() != null && volumeObjectTO.getIopsReadRateMaxLength() > 0) {
            disk.setIopsReadRateMaxLength(volumeObjectTO.getIopsReadRateMaxLength());
        }
        if (volumeObjectTO.getIopsWriteRate() != null && volumeObjectTO.getIopsWriteRate() > 0) {
            disk.setIopsWriteRate(volumeObjectTO.getIopsWriteRate());
        }
        if (volumeObjectTO.getIopsWriteRateMax() != null && volumeObjectTO.getIopsWriteRateMax() > 0) {
            disk.setIopsWriteRateMax(volumeObjectTO.getIopsWriteRateMax());
        }
        if (volumeObjectTO.getIopsWriteRateMaxLength() != null && volumeObjectTO.getIopsWriteRateMaxLength() > 0) {
            disk.setIopsWriteRateMaxLength(volumeObjectTO.getIopsWriteRateMaxLength());
        }
    }

    private void createVif(final LibvirtVMDef vm, final VirtualMachineTO vmSpec, final NicTO nic, final String nicAdapter, Map<String, String> extraConfig) throws InternalErrorException, LibvirtException {
        if (vm.getDevices() == null) {
            LOGGER.error("LibvirtVMDef object get devices with null result");
            throw new InternalErrorException("LibvirtVMDef object get devices with null result");
        }
        final InterfaceDef interfaceDef = getVifDriver(nic.getType(), nic.getName()).plug(nic, vm.getPlatformEmulator(), nicAdapter, extraConfig);
        if (vmSpec.getDetails() != null) {
            setInterfaceDefQueueSettings(vmSpec.getDetails(), vmSpec.getCpus(), interfaceDef);
        }
        vm.getDevices().addDevice(interfaceDef);
    }

    public boolean cleanupDisk(Map<String, String> volumeToDisconnect) {
        return storagePoolManager.disconnectPhysicalDisk(volumeToDisconnect);
    }

    public boolean cleanupDisk(final DiskDef disk) {
        final String path = disk.getDiskPath();

        if (StringUtils.isBlank(path)) {
            LOGGER.debug("Unable to clean up disk with null path (perhaps empty cdrom drive):" + disk);
            return false;
        }

        if (path.endsWith("systemvm.iso")) {
            // don't need to clean up system vm ISO as it's stored in local
            return true;
        }

        return storagePoolManager.disconnectPhysicalDiskByPath(path);
    }

    protected KVMStoragePoolManager getPoolManager() {
        return storagePoolManager;
    }

    public void detachAndAttachConfigDriveISO(final Connect conn, final String vmName) {
        // detach and re-attach configdrive ISO
        List<DiskDef> disks = getDisks(conn, vmName);
        DiskDef configdrive = null;
        for (DiskDef disk : disks) {
            if (disk.getDeviceType() == DiskDef.DeviceType.CDROM && CONFIG_DRIVE_ISO_DISK_LABEL.equals(disk.getDiskLabel())) {
                configdrive = disk;
            }
        }
        if (configdrive != null) {
            try {
                LOGGER.debug(String.format("Detaching ConfigDrive ISO of the VM %s, at path %s", vmName, configdrive.getDiskPath()));
                String result = attachOrDetachISO(conn, vmName, configdrive.getDiskPath(), false, CONFIG_DRIVE_ISO_DEVICE_ID);
                if (result != null) {
                    LOGGER.warn("Detach ConfigDrive ISO of the VM {}, at path {} with result: {}", vmName, configdrive.getDiskPath(), result);
                }
                LOGGER.debug(String.format("Attaching ConfigDrive ISO of the VM %s, at path %s", vmName, configdrive.getDiskPath()));
                result = attachOrDetachISO(conn, vmName, configdrive.getDiskPath(), true, CONFIG_DRIVE_ISO_DEVICE_ID);
                if (result != null) {
                    LOGGER.warn("Attach ConfigDrive ISO of the VM {}, at path {} with result: {}", vmName, configdrive.getDiskPath(), result);
                }
            } catch (final LibvirtException | InternalErrorException | URISyntaxException e) {
                final String msg = "Detach and attach ConfigDrive ISO failed due to " + e.toString();
                LOGGER.warn(msg, e);
            }
        }
    }

    public synchronized String attachOrDetachISO(final Connect conn, final String vmName, String isoPath, final boolean isAttach, final Integer diskSeq) throws LibvirtException, URISyntaxException,
            InternalErrorException {
        final DiskDef iso = new DiskDef();
        if (isAttach && StringUtils.isNotBlank(isoPath) && isoPath.lastIndexOf("/") > 0) {
            if (isoPath.startsWith(getConfigPath() + "/" + ConfigDrive.CONFIGDRIVEDIR) && isoPath.contains(vmName)) {
                iso.defISODisk(isoPath, diskSeq, DiskDef.DiskType.FILE);
            } else {
                final int index = isoPath.lastIndexOf("/");
                final String path = isoPath.substring(0, index);
                final String name = isoPath.substring(index + 1);
                final KVMStoragePool storagePool = storagePoolManager.getStoragePoolByURI(path);
                final KVMPhysicalDisk isoVol = storagePool.getPhysicalDisk(name);
                final DiskDef.DiskType diskType = getDiskType(isoVol);
                isoPath = isoVol.getPath();

                iso.defISODisk(isoPath, diskSeq, diskType);
            }
        } else {
            iso.defISODisk(null, diskSeq, DiskDef.DiskType.FILE);
        }

        final String result = attachOrDetachDevice(conn, true, vmName, iso.toString());
        if (result == null && !isAttach) {
            final List<DiskDef> disks = getDisks(conn, vmName);
            for (final DiskDef disk : disks) {
                if (disk.getDeviceType() == DiskDef.DeviceType.CDROM
                        && (diskSeq == null || disk.getDiskLabel().equals(iso.getDiskLabel()))) {
                    cleanupDisk(disk);
                }
            }

        }
        return result;
    }

    public synchronized String attachOrDetachDisk(final Connect conn,
                                                  final boolean attach, final String vmName, final KVMPhysicalDisk attachingDisk,
                                                  final int devId, final Long bytesReadRate, final Long bytesReadRateMax, final Long bytesReadRateMaxLength, final Long bytesWriteRate, final Long bytesWriteRateMax, final Long bytesWriteRateMaxLength, final Long iopsReadRate, final Long iopsReadRateMax, final Long iopsReadRateMaxLength, final Long iopsWriteRate, final Long iopsWriteRateMax, final Long iopsWriteRateMaxLength, final String cacheMode) throws LibvirtException, InternalErrorException {
        List<DiskDef> disks = null;
        Domain dm = null;
        DiskDef diskdef = null;
        final KVMStoragePool attachingPool = attachingDisk.getPool();
        try {
            dm = conn.domainLookupByName(vmName);
            final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
            final String domXml = dm.getXMLDesc(0);
            parser.parseDomainXML(domXml);
            disks = parser.getDisks();

            if (!attach) {
                for (final DiskDef disk : disks) {
                    final String file = disk.getDiskPath();
                    if (file != null && file.equalsIgnoreCase(attachingDisk.getPath())) {
                        diskdef = disk;
                        break;
                    }
                }
                if (diskdef == null) {
                    throw new InternalErrorException("disk: " + attachingDisk.getPath() + " is not attached before");
                }
            } else {
                DiskDef.DiskBus busT = DiskDef.DiskBus.VIRTIO;
                for (final DiskDef disk : disks) {
                    if (disk.getDeviceType() == DeviceType.DISK) {
                        if (disk.getBusType() == DiskDef.DiskBus.SCSI) {
                            busT = DiskDef.DiskBus.SCSI;
                        }
                        break;
                    }
                }

                diskdef = new DiskDef();
                if (busT == DiskDef.DiskBus.SCSI) {
                    diskdef.setQemuDriver(true);
                    diskdef.setDiscard(DiscardType.UNMAP);
                }
                if (attachingPool.getType() == StoragePoolType.RBD) {
                    diskdef.defNetworkBasedDisk(attachingDisk.getPath(), attachingPool.getSourceHost(), attachingPool.getSourcePort(), attachingPool.getAuthUserName(),
                            attachingPool.getUuid(), devId, busT, DiskProtocol.RBD, DiskDef.DiskFmtType.RAW);
                } else if (attachingPool.getType() == StoragePoolType.PowerFlex) {
                    diskdef.defBlockBasedDisk(attachingDisk.getPath(), devId, busT);
                } else if (attachingPool.getType() == StoragePoolType.Gluster) {
                    diskdef.defNetworkBasedDisk(attachingDisk.getPath(), attachingPool.getSourceHost(), attachingPool.getSourcePort(), null,
                            null, devId, busT, DiskProtocol.GLUSTER, DiskDef.DiskFmtType.QCOW2);
                } else if (attachingDisk.getFormat() == PhysicalDiskFormat.QCOW2) {
                    diskdef.defFileBasedDisk(attachingDisk.getPath(), devId, busT, DiskDef.DiskFmtType.QCOW2);
                } else if (attachingDisk.getFormat() == PhysicalDiskFormat.RAW) {
                    diskdef.defBlockBasedDisk(attachingDisk.getPath(), devId, busT);
                    if (attachingPool.getType() == StoragePoolType.Linstor) {
                        diskdef.setDiscard(DiscardType.UNMAP);
                    }
                }
                if (bytesReadRate != null && bytesReadRate > 0) {
                    diskdef.setBytesReadRate(bytesReadRate);
                }
                if (bytesReadRateMax != null && bytesReadRateMax > 0) {
                    diskdef.setBytesReadRateMax(bytesReadRateMax);
                }
                if (bytesReadRateMaxLength != null && bytesReadRateMaxLength > 0) {
                    diskdef.setBytesReadRateMaxLength(bytesReadRateMaxLength);
                }
                if (bytesWriteRate != null && bytesWriteRate > 0) {
                    diskdef.setBytesWriteRate(bytesWriteRate);
                }
                if (bytesWriteRateMax != null && bytesWriteRateMax > 0) {
                    diskdef.setBytesWriteRateMax(bytesWriteRateMax);
                }
                if (bytesWriteRateMaxLength != null && bytesWriteRateMaxLength > 0) {
                    diskdef.setBytesWriteRateMaxLength(bytesWriteRateMaxLength);
                }
                if (iopsReadRate != null && iopsReadRate > 0) {
                    diskdef.setIopsReadRate(iopsReadRate);
                }
                if (iopsReadRateMax != null && iopsReadRateMax > 0) {
                    diskdef.setIopsReadRateMax(iopsReadRateMax);
                }
                if (iopsReadRateMaxLength != null && iopsReadRateMaxLength > 0) {
                    diskdef.setIopsReadRateMaxLength(iopsReadRateMaxLength);
                }
                if (iopsWriteRate != null && iopsWriteRate > 0) {
                    diskdef.setIopsWriteRate(iopsWriteRate);
                }
                if (iopsWriteRateMax != null && iopsWriteRateMax > 0) {
                    diskdef.setIopsWriteRateMax(iopsWriteRateMax);
                }

                if (cacheMode != null) {
                    diskdef.setCacheMode(DiskDef.DiskCacheMode.valueOf(cacheMode.toUpperCase()));
                }

                diskdef.setPhysicalBlockIOSize(attachingPool.getSupportedPhysicalBlockSize());
                diskdef.setLogicalBlockIOSize(attachingPool.getSupportedLogicalBlockSize());
                attachingPool.customizeLibvirtDiskDef(diskdef);
            }

            final String xml = diskdef.toString();
            return attachOrDetachDevice(conn, attach, vmName, xml);
        } finally {
            if (dm != null) {
                dm.free();
            }
        }
    }

    protected synchronized String attachOrDetachDevice(final Connect conn, final boolean attach, final String vmName, final String xml) throws LibvirtException, InternalErrorException {
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            if (attach) {
                LOGGER.debug("Attaching device: " + xml);
                dm.attachDevice(xml);
            } else {
                LOGGER.debug("Detaching device: " + xml);
                dm.detachDevice(xml);
            }
        } catch (final LibvirtException e) {
            if (attach) {
                LOGGER.warn("Failed to attach device to " + vmName + ": " + e.getMessage());
            } else {
                LOGGER.warn("Failed to detach device from " + vmName + ": " + e.getMessage());
            }
            throw e;
        } finally {
            if (dm != null) {
                try {
                    dm.free();
                } catch (final LibvirtException l) {
                    LOGGER.trace("Ignoring libvirt error.", l);
                }
            }
        }

        return null;
    }

    @Override
    public PingCommand getCurrentStatus(final long id) {
        PingRoutingCommand pingRoutingCommand;
        if (!canBridgeFirewall) {
            pingRoutingCommand = new PingRoutingCommand(com.cloud.host.Host.Type.Routing, id, this.getHostVmStateReport());
        } else {
            final HashMap<String, Pair<Long, Long>> nwGrpStates = syncNetworkGroups(id);
            pingRoutingCommand = new PingRoutingWithNwGroupsCommand(getType(), id, this.getHostVmStateReport(), nwGrpStates);
        }
        HealthCheckResult healthCheckResult = getHostHealthCheckResult();
        if (healthCheckResult != HealthCheckResult.IGNORE) {
            pingRoutingCommand.setHostHealthCheckResult(healthCheckResult == HealthCheckResult.SUCCESS);
        }
        if (isReconcileCommandsEnabled) {
            pingRoutingCommand.setCommandInfos(getCommandInfosFromLogFiles(false));
        }
        return pingRoutingCommand;
    }

    /**
     * The health check result is true, if the script is executed successfully and the exit code is 0
     * The health check result is false, if the script is executed successfully and the exit code is 1
     * The health check result is null, if
     * - Script file is not specified, or
     * - Script file does not exist, or
     * - Script file is not accessible by the user of the cloudstack-agent process, or
     * - Script file is not executable
     * - There are errors when the script is executed (exit codes other than 0 or 1)
     */
    private HealthCheckResult getHostHealthCheckResult() {
        if (StringUtils.isBlank(hostHealthCheckScriptPath)) {
            LOGGER.debug("Host health check script path is not specified");
            return HealthCheckResult.IGNORE;
        }
        File script = new File(hostHealthCheckScriptPath);
        if (!script.exists() || !script.isFile() || !script.canExecute()) {
            LOGGER.warn(String.format("The host health check script file set at: %s cannot be executed, " +
                            "reason: %s", hostHealthCheckScriptPath,
                    !script.exists() ? "file does not exist" : "please check file permissions to execute this file"));
            return HealthCheckResult.IGNORE;
        }
        int exitCode = executeBashScriptAndRetrieveExitValue(hostHealthCheckScriptPath);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("Host health check script exit code: %s", exitCode));
        }
        return retrieveHealthCheckResultFromExitCode(exitCode);
    }

    private HealthCheckResult retrieveHealthCheckResultFromExitCode(int exitCode) {
        if (exitCode != 0 && exitCode != 1) {
            return HealthCheckResult.IGNORE;
        }
        return exitCode == 0 ? HealthCheckResult.SUCCESS : HealthCheckResult.FAILURE;
    }

    @Override
    public Type getType() {
        return Type.Routing;
    }

    private Map<String, String> getVersionStrings() {
        final Script command = new Script(versionStringPath, timeout, LOGGER);
        final KeyValueInterpreter kvi = new KeyValueInterpreter();
        final String result = command.execute(kvi);
        if (result == null) {
            return kvi.getKeyValues();
        } else {
            return new HashMap<String, String>(1);
        }
    }

    @Override
    public StartupCommand[] initialize() {
        final KVMHostInfo info = new KVMHostInfo(dom0MinMem, dom0OvercommitMem, manualCpuSpeed, dom0MinCpuCores);
        calculateHostCpuMaxCapacity(info.getAllocatableCpus(), info.getCpuSpeed());

        String capabilities = String.join(",", info.getCapabilities());
        if (dpdkSupport) {
            capabilities += ",dpdk";
        }
        final StartupRoutingCommand cmd =
                new StartupRoutingCommand(info.getAllocatableCpus(), info.getCpuSpeed(), info.getTotalMemory(), info.getReservedMemory(), capabilities, hypervisorType,
                        RouterPrivateIpStrategy.HostLocal);
        cmd.setCpuSockets(info.getCpuSockets());
        fillNetworkInformation(cmd);
        privateIp = cmd.getPrivateIpAddress();
        cmd.getHostDetails().putAll(getVersionStrings());
        cmd.getHostDetails().put(KeyStoreUtils.SECURED, String.valueOf(isHostSecured()).toLowerCase());
        cmd.setPool(pool);
        cmd.setCluster(clusterId);
        cmd.setGatewayIpAddress(localGateway);
        cmd.setIqn(getIqn());
        cmd.getHostDetails().put(HOST_VOLUME_ENCRYPTION, String.valueOf(hostSupportsVolumeEncryption()));
        cmd.setHostTags(getHostTags());
        boolean instanceConversionSupported = hostSupportsInstanceConversion();
        cmd.getHostDetails().put(HOST_INSTANCE_CONVERSION, String.valueOf(instanceConversionSupported));
        if (instanceConversionSupported) {
            cmd.getHostDetails().put(HOST_VIRTV2V_VERSION, getHostVirtV2vVersion());
        }
        if (hostSupportsOvfExport()) {
            cmd.getHostDetails().put(HOST_OVFTOOL_VERSION, getHostOvfToolVersion());
        }
        HealthCheckResult healthCheckResult = getHostHealthCheckResult();
        if (healthCheckResult != HealthCheckResult.IGNORE) {
            cmd.setHostHealthCheckResult(healthCheckResult == HealthCheckResult.SUCCESS);
        }
        if (StringUtils.isNotBlank(info.getCpuArch())) {
            cmd.setCpuArch(info.getCpuArch());
        }

        if (cmd.getHostDetails().containsKey("Host.OS")) {
            hostDistro = cmd.getHostDetails().get("Host.OS");
        }

        cmd.setGpuDevices(getGpuDevices());

        List<StartupCommand> startupCommands = new ArrayList<>();
        startupCommands.add(cmd);
        for (int i = 0; i < localStoragePaths.size(); i++) {
            String localStoragePath = localStoragePaths.get(i);
            String localStorageUUID = localStorageUUIDs.get(i);
            StartupStorageCommand sscmd = createLocalStoragePool(localStoragePath, localStorageUUID, cmd);
            if (sscmd != null) {
                startupCommands.add(sscmd);
            }
        }
        StartupCommand[] startupCommandsArray = new StartupCommand[startupCommands.size()];
        int i = 0;
        for (StartupCommand startupCommand : startupCommands) {
            startupCommandsArray[i] = startupCommand;
            i++;
        }
        return startupCommandsArray;
    }

    protected List<String> getHostTags() {
        List<String> hostTagsList = new ArrayList<>();
        String hostTags = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.HOST_TAGS);
        if (StringUtils.isNotBlank(hostTags)) {
            for (String hostTag : hostTags.split(",")) {
                if (!hostTagsList.contains(hostTag.trim())) {
                    hostTagsList.add(hostTag.trim());
                }
            }
        }
        return hostTagsList;
    }

    /**
     * Calculates and sets the host CPU max capacity according to the cgroup version of the host.
     * <ul>
     *     <li>
     *         <b>cgroup v1</b>: the max CPU capacity for the host is set to <b>0</b>.
     *     </li>
     *     <li>
     *         <b>cgroup v2</b>: the max CPU capacity for the host is the value of <b>cpuCores * cpuSpeed</b>.
     *     </li>
     * </ul>
     */
    protected void calculateHostCpuMaxCapacity(int cpuCores, Long cpuSpeed) {
        String output = Script.runSimpleBashScript(COMMAND_GET_CGROUP_HOST_VERSION);
        LOGGER.info(String.format("Host uses control group [%s].", output));

        if (!CGROUP_V2.equals(output)) {
            LOGGER.info(String.format("Setting host CPU max capacity to 0, as it uses cgroup v1.", getHostCpuMaxCapacity()));
            setHostCpuMaxCapacity(0);
            return;
        }

        LOGGER.info(String.format("Calculating the max shares of the host."));
        setHostCpuMaxCapacity(cpuCores * cpuSpeed.intValue());
        LOGGER.info(String.format("The max shares of the host is [%d].", getHostCpuMaxCapacity()));
    }

    private StartupStorageCommand createLocalStoragePool(String localStoragePath, String localStorageUUID, StartupRoutingCommand cmd) {
        StartupStorageCommand sscmd = null;
        try {
            final KVMStoragePool localStoragePool = storagePoolManager.createStoragePool(localStorageUUID, "localhost", -1, localStoragePath, "", StoragePoolType.Filesystem);
            final com.cloud.agent.api.StoragePoolInfo pi =
                    new com.cloud.agent.api.StoragePoolInfo(localStoragePool.getUuid(), cmd.getPrivateIpAddress(), localStoragePath, localStoragePath,
                            StoragePoolType.Filesystem, localStoragePool.getCapacity(), localStoragePool.getAvailable());

            sscmd = new StartupStorageCommand();
            sscmd.setPoolInfo(pi);
            sscmd.setGuid(pi.getUuid());
            sscmd.setDataCenter(dcId);
            sscmd.setResourceType(Storage.StorageResourceType.STORAGE_POOL);
        } catch (final CloudRuntimeException e) {
            LOGGER.debug("Unable to initialize local storage pool: " + e);
        }
        return sscmd;
    }

    public String diskUuidToSerial(String uuid) {
        String uuidWithoutHyphen = uuid.replace("-","");
        return uuidWithoutHyphen.substring(0, Math.min(uuidWithoutHyphen.length(), 20));
    }

    private String getIqn() {
        try {
            final String textToFind = "InitiatorName=";

            final Script iScsiAdmCmd = new Script(true, "grep", 0, LOGGER);

            iScsiAdmCmd.add(textToFind);
            iScsiAdmCmd.add("/etc/iscsi/initiatorname.iscsi");

            final OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();

            final String result = iScsiAdmCmd.execute(parser);

            if (result != null) {
                return null;
            }

            final String textFound = parser.getLine().trim();

            return textFound.substring(textToFind.length());
        }
        catch (final Exception ex) {
            return null;
        }
    }

    /**
     * Given a disk path on KVM host, attempts to find source host and path using mount command
     * @param diskPath KVM host path for virtual disk
     * @return Pair with IP of host and path
     */
    public Pair<String, String> getSourceHostPath(String diskPath) {
        String sourceHostIp = null;
        String sourcePath = null;
        try {
            String mountResult = Script.runSimpleBashScript("mount | grep \"" + diskPath + "\"");
            LOGGER.debug("Got mount result for " + diskPath + "\n\n" + mountResult);
            if (StringUtils.isNotEmpty(mountResult)) {
                String[] res = mountResult.strip().split(" ");
                if (res[0].contains(":")) {
                    res = res[0].split(":");
                    sourceHostIp = res[0].strip();
                    sourcePath = res[1].strip();
                } else {
                    // Assume local storage
                    sourceHostIp = getPrivateIp();
                    sourcePath = diskPath;
                }
            }
            if (StringUtils.isNotEmpty(sourceHostIp) && StringUtils.isNotEmpty(sourcePath)) {
                return new Pair<>(sourceHostIp, sourcePath);
            }
        } catch (Exception ex) {
            LOGGER.warn("Failed to list source host and IP for " + diskPath + ex.toString());
        }
        return null;
    }

    public List<String> getAllVmNames(final Connect conn) {
        final ArrayList<String> domainNames = new ArrayList<String>();
        try {
            final String names[] = conn.listDefinedDomains();
            for (int i = 0; i < names.length; i++) {
                domainNames.add(names[i]);
            }
        } catch (final LibvirtException e) {
            LOGGER.warn("Failed to list defined domains", e);
        }

        int[] ids = null;
        try {
            ids = conn.listDomains();
        } catch (final LibvirtException e) {
            LOGGER.warn("Failed to list domains", e);
            return domainNames;
        }

        Domain dm = null;
        for (int i = 0; i < ids.length; i++) {
            try {
                dm = conn.domainLookupByID(ids[i]);
                domainNames.add(dm.getName());
            } catch (final LibvirtException e) {
                LOGGER.warn("Unable to get vms", e);
            } finally {
                try {
                    if (dm != null) {
                        dm.free();
                    }
                } catch (final LibvirtException e) {
                    LOGGER.trace("Ignoring libvirt error.", e);
                }
            }
        }

        return domainNames;
    }

    private HashMap<String, HostVmStateReportEntry> getHostVmStateReport() {
        final HashMap<String, HostVmStateReportEntry> vmStates = new HashMap<String, HostVmStateReportEntry>();
        Connect conn = null;

        if (hypervisorType == HypervisorType.LXC) {
            try {
                conn = LibvirtConnection.getConnectionByType(HypervisorType.LXC.toString());
                vmStates.putAll(getHostVmStateReport(conn));
                conn = LibvirtConnection.getConnectionByType(HypervisorType.KVM.toString());
                vmStates.putAll(getHostVmStateReport(conn));
            } catch (final LibvirtException e) {
                LOGGER.debug("Failed to get connection: " + e.getMessage());
            }
        }

        if (hypervisorType == HypervisorType.KVM) {
            try {
                conn = LibvirtConnection.getConnectionByType(HypervisorType.KVM.toString());
                vmStates.putAll(getHostVmStateReport(conn));
            } catch (final LibvirtException e) {
                LOGGER.debug("Failed to get connection: " + e.getMessage());
            }
        }

        return vmStates;
    }

    private HashMap<String, HostVmStateReportEntry> getHostVmStateReport(final Connect conn) {
        final HashMap<String, HostVmStateReportEntry> vmStates = new HashMap<String, HostVmStateReportEntry>();

        String[] vms = null;
        int[] ids = null;

        try {
            ids = conn.listDomains();
        } catch (final LibvirtException e) {
            LOGGER.warn("Unable to listDomains", e);
            return null;
        }
        try {
            vms = conn.listDefinedDomains();
        } catch (final LibvirtException e) {
            LOGGER.warn("Unable to listDomains", e);
            return null;
        }

        Domain dm = null;
        for (int i = 0; i < ids.length; i++) {
            try {
                dm = conn.domainLookupByID(ids[i]);

                final DomainState ps = dm.getInfo().state;

                final PowerState state = convertToPowerState(ps);

                LOGGER.trace("VM " + dm.getName() + ": powerstate = " + ps + "; vm state=" + state.toString());
                final String vmName = dm.getName();

                // TODO : for XS/KVM (host-based resource), we require to remove
                // VM completely from host, for some reason, KVM seems to still keep
                // Stopped VM around, to work-around that, reporting only powered-on VM
                //
                if (state == PowerState.PowerOn) {
                    vmStates.put(vmName, new HostVmStateReportEntry(state, conn.getHostName()));
                }
            } catch (final LibvirtException e) {
                LOGGER.warn("Unable to get vms", e);
            } finally {
                try {
                    if (dm != null) {
                        dm.free();
                    }
                } catch (final LibvirtException e) {
                    LOGGER.trace("Ignoring libvirt error.", e);
                }
            }
        }

        for (int i = 0; i < vms.length; i++) {
            try {

                dm = conn.domainLookupByName(vms[i]);

                final DomainState ps = dm.getInfo().state;
                final PowerState state = convertToPowerState(ps);
                final String vmName = dm.getName();
                LOGGER.trace("VM " + vmName + ": powerstate = " + ps + "; vm state=" + state.toString());

                // TODO : for XS/KVM (host-based resource), we require to remove
                // VM completely from host, for some reason, KVM seems to still keep
                // Stopped VM around, to work-around that, reporting only powered-on VM
                //
                if (state == PowerState.PowerOn) {
                    vmStates.put(vmName, new HostVmStateReportEntry(state, conn.getHostName()));
                }
            } catch (final LibvirtException e) {
                LOGGER.warn("Unable to get vms", e);
            } finally {
                try {
                    if (dm != null) {
                        dm.free();
                    }
                } catch (final LibvirtException e) {
                    LOGGER.trace("Ignoring libvirt error.", e);
                }
            }
        }

        return vmStates;
    }

    public String rebootVM(final Connect conn, final String vmName) throws LibvirtException{
        Domain dm = null;
        String msg = null;
        try {
            dm = conn.domainLookupByName(vmName);
            // Perform ACPI based reboot
            // https://libvirt.org/html/libvirt-libvirt-domain.html#virDomainReboot
            // https://libvirt.org/html/libvirt-libvirt-domain.html#virDomainRebootFlagValues
            // Send ACPI event to Reboot
            dm.reboot(0x1);
            return null;
        } catch (final LibvirtException e) {
            LOGGER.warn("Failed to create vm", e);
            msg = e.getMessage();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException e) {
                LOGGER.trace("Ignoring libvirt error.", e);
            }
        }

        return msg;
    }

    public String stopVM(final Connect conn, final String vmName, final boolean forceStop) {
        DomainState state = null;
        Domain dm = null;

        // delete the metadata of vm snapshots before stopping
        try {
            dm = conn.domainLookupByName(vmName);
            cleanVMSnapshotMetadata(dm);
        } catch (LibvirtException e) {
            LOGGER.debug("Failed to get vm :" + e.getMessage());
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (LibvirtException l) {
                LOGGER.trace("Ignoring libvirt error.", l);
            }
        }

        LOGGER.debug("Try to stop the vm at first");
        if (forceStop) {
            return stopVMInternal(conn, vmName, true);
        }
        String ret = stopVMInternal(conn, vmName, false);
        if (Script.ERR_TIMEOUT.equals(ret)) {
            ret = stopVMInternal(conn, vmName, true);
        } else if (ret != null) {
            /*
             * There is a race condition between libvirt and qemu: libvirt
             * listens on qemu's monitor fd. If qemu is shutdown, while libvirt
             * is reading on the fd, then libvirt will report an error.
             */
            /* Retry 3 times, to make sure we can get the vm's status */
            for (int i = 0; i < 3; i++) {
                try {
                    dm = conn.domainLookupByName(vmName);
                    state = dm.getInfo().state;
                    break;
                } catch (final LibvirtException e) {
                    LOGGER.debug("Failed to get vm status:" + e.getMessage());
                } finally {
                    try {
                        if (dm != null) {
                            dm.free();
                        }
                    } catch (final LibvirtException l) {
                        LOGGER.trace("Ignoring libvirt error.", l);
                    }
                }
            }

            if (state == null) {
                LOGGER.debug("Can't get vm's status, assume it's dead already");
                return null;
            }

            if (state != DomainState.VIR_DOMAIN_SHUTOFF) {
                LOGGER.debug("Try to destroy the vm");
                ret = stopVMInternal(conn, vmName, true);
                if (ret != null) {
                    return ret;
                }
            }
        }

        return null;
    }

    protected String stopVMInternal(final Connect conn, final String vmName, final boolean force) {
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            final int persist = dm.isPersistent();
            if (force) {
                if (dm.isActive() == 1) {
                    dm.destroy();
                    if (persist == 1) {
                        dm.undefine();
                    }
                }
            } else {
                if (dm.isActive() == 0) {
                    return null;
                }
                dm.shutdown();
                int retry = stopTimeout / 2000;
                /* Wait for the domain gets into shutoff state. When it does
                   the dm object will no longer work, so we need to catch it. */
                try {
                    while (dm.isActive() == 1 && retry >= 0) {
                        Thread.sleep(2000);
                        retry--;
                    }
                } catch (final LibvirtException e) {
                    final String error = e.toString();
                    if (error.contains("Domain not found")) {
                        LOGGER.debug("successfully shut down vm " + vmName);
                    } else {
                        LOGGER.debug("Error in waiting for vm shutdown:" + error);
                    }
                }
                if (retry < 0) {
                    LOGGER.warn("Timed out waiting for domain " + vmName + " to shutdown gracefully");
                    return Script.ERR_TIMEOUT;
                } else {
                    if (persist == 1) {
                        dm.undefine();
                    }
                }
            }
        } catch (final LibvirtException e) {
            if (e.getMessage().contains("Domain not found")) {
                LOGGER.debug("VM " + vmName + " doesn't exist, no need to stop it");
                return null;
            }
            LOGGER.debug("Failed to stop VM :" + vmName + " :", e);
            return e.getMessage();
        } catch (final InterruptedException ie) {
            LOGGER.debug("Interrupted sleep");
            return ie.getMessage();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException e) {
                LOGGER.trace("Ignoring libvirt error.", e);
            }
        }

        return null;
    }

    public Integer getVncPort(final Connect conn, final String vmName) throws LibvirtException {
        final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            final String xmlDesc = dm.getXMLDesc(0);
            parser.parseDomainXML(xmlDesc);
            return parser.getVncPort();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException l) {
                LOGGER.trace("Ignoring libvirt error.", l);
            }
        }
    }

    private boolean IsHVMEnabled(final Connect conn) {
        final LibvirtCapXMLParser parser = new LibvirtCapXMLParser();
        try {
            parser.parseCapabilitiesXML(conn.getCapabilities());
            final ArrayList<String> osTypes = parser.getGuestOsType();
            for (final String o : osTypes) {
                if (o.equalsIgnoreCase("hvm")) {
                    return true;
                }
            }
        } catch (final LibvirtException e) {
            LOGGER.trace("Ignoring libvirt error.", e);
        }
        return false;
    }

    private String getHypervisorPath(final Connect conn) {
        final LibvirtCapXMLParser parser = new LibvirtCapXMLParser();
        try {
            parser.parseCapabilitiesXML(conn.getCapabilities());
        } catch (final LibvirtException e) {
            LOGGER.debug(e.getMessage());
        }
        return parser.getEmulator();
    }

    boolean isGuestPVEnabled(final String guestOSName) {
        DiskDef.DiskBus db = getGuestDiskModel(guestOSName, false);
        return db != DiskDef.DiskBus.IDE;
    }

    public boolean isCentosHost() {
        if (hvVersion <= 9) {
            return true;
        } else {
            return false;
        }
    }

    public DiskDef.DiskBus getDiskModelFromVMDetail(final VirtualMachineTO vmTO) {
        Map<String, String> details = vmTO.getDetails();
        if (details == null) {
            return null;
        }

        String rootDiskController = details.get(VmDetailConstants.ROOT_DISK_CONTROLLER);
        if (StringUtils.isNotBlank(rootDiskController)) {
            LOGGER.debug("Passed custom disk controller for ROOT disk " + rootDiskController);
            for (DiskDef.DiskBus bus : DiskDef.DiskBus.values()) {
                if (bus.toString().equalsIgnoreCase(rootDiskController)) {
                    LOGGER.debug("Found matching enum for disk controller for ROOT disk " + rootDiskController);
                    return bus;
                }
            }
        }
        return null;
    }

    public DiskDef.DiskBus getDataDiskModelFromVMDetail(final VirtualMachineTO vmTO) {
        Map<String, String> details = vmTO.getDetails();
        if (details == null) {
            return null;
        }

        String dataDiskController = details.get(VmDetailConstants.DATA_DISK_CONTROLLER);
        if (StringUtils.isNotBlank(dataDiskController)) {
            LOGGER.debug("Passed custom disk controller for DATA disk " + dataDiskController);
            for (DiskDef.DiskBus bus : DiskDef.DiskBus.values()) {
                if (bus.toString().equalsIgnoreCase(dataDiskController)) {
                    LOGGER.debug("Found matching enum for disk controller for DATA disk " + dataDiskController);
                    return bus;
                }
            }
        }
        return null;
    }

    private DiskDef.DiskBus getGuestDiskModel(final String platformEmulator, boolean isUefiEnabled) {
        if (platformEmulator == null) {
            return DiskDef.DiskBus.IDE;
        } else if (platformEmulator.startsWith("Other PV Virtio-SCSI")) {
            return DiskDef.DiskBus.SCSI;
        } else if (platformEmulator.contains("Ubuntu") ||
                StringUtils.startsWithAny(platformEmulator,
                        "Fedora", "CentOS", "Red Hat Enterprise Linux", "Debian GNU/Linux", "FreeBSD", "Oracle",
                        "Rocky Linux", "AlmaLinux", "Other PV")) {
            return DiskDef.DiskBus.VIRTIO;
        } else if (isUefiEnabled && StringUtils.startsWithAny(platformEmulator, "Windows", "Other")) {
            return DiskDef.DiskBus.SATA;
        } else if (guestCpuArch != null && (guestCpuArch.equals("aarch64") || guestCpuArch.equals("s390x"))) {
            return DiskDef.DiskBus.SCSI;
        } else {
            return DiskDef.DiskBus.IDE;
        }

    }
    private void cleanupVMNetworks(final Connect conn, final List<InterfaceDef> nics) {
        if (nics != null) {
            for (final InterfaceDef nic : nics) {
                for (final VifDriver vifDriver : getAllVifDrivers()) {
                    vifDriver.unplug(nic, true);
                }
            }
        }
    }

    public Domain getDomain(final Connect conn, final String vmName) throws LibvirtException {
        return conn.domainLookupByName(vmName);
    }

    public List<InterfaceDef> getInterfaces(final Connect conn, final String vmName) {
        final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            parser.parseDomainXML(dm.getXMLDesc(0));
            return parser.getInterfaces();

        } catch (final LibvirtException e) {
            LOGGER.debug("Failed to get dom xml: " + e.toString());
            return new ArrayList<InterfaceDef>();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException e) {
                LOGGER.trace("Ignoring libvirt error.", e);
            }
        }
    }

    public List<DiskDef> getDisks(final Connect conn, final String vmName) {
        final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            parser.parseDomainXML(dm.getXMLDesc(0));
            return parser.getDisks();

        } catch (final LibvirtException e) {
            LOGGER.debug("Failed to get dom xml: " + e.toString());
            return new ArrayList<DiskDef>();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException e) {
                LOGGER.trace("Ignoring libvirt error.", e);
            }
        }
    }

    private String executeBashScript(final String script) {
        return createScript(script).execute();
    }

    private Script createScript(final String script) {
        final Script command = new Script("/bin/bash", timeout, LOGGER);
        command.add("-c");
        command.add(script);
        return command;
    }

    private int executeBashScriptAndRetrieveExitValue(final String script) {
        Script command = createScript(script);
        command.execute();
        return command.getExitValue();
    }

    public List<VmNetworkStatsEntry> getVmNetworkStat(Connect conn, String vmName) throws LibvirtException {
        Domain dm = null;
        try {
            dm = getDomain(conn, vmName);

            List<VmNetworkStatsEntry> stats = new ArrayList<VmNetworkStatsEntry>();

            List<InterfaceDef> nics = getInterfaces(conn, vmName);

            for (InterfaceDef nic : nics) {
                DomainInterfaceStats nicStats = dm.interfaceStats(nic.getDevName());
                String macAddress = nic.getMacAddress();
                VmNetworkStatsEntry stat = new VmNetworkStatsEntry(vmName, macAddress, nicStats.tx_bytes, nicStats.rx_bytes);
                stats.add(stat);
            }

            return stats;
        } finally {
            if (dm != null) {
                dm.free();
            }
        }
    }

    public List<VmDiskStatsEntry> getVmDiskStat(final Connect conn, final String vmName) throws LibvirtException {
        Domain dm = null;
        try {
            dm = getDomain(conn, vmName);

            final List<VmDiskStatsEntry> stats = new ArrayList<>();

            final List<DiskDef> disks = getDisks(conn, vmName);

            for (final DiskDef disk : disks) {
                if (disk.getDeviceType() != DeviceType.DISK) {
                    break;
                }
                final DomainBlockStats blockStats = dm.blockStats(disk.getDiskLabel());
                String diskPath = getDiskPathFromDiskDef(disk);
                if (diskPath != null) {
                    final VmDiskStatsEntry stat = new VmDiskStatsEntry(vmName, diskPath, blockStats.wr_req, blockStats.rd_req, blockStats.wr_bytes, blockStats.rd_bytes);
                    final DomainBlockStats oldStats = vmDiskStats.get(String.format("%s-%s", vmName, diskPath));
                    if (oldStats != null) {
                        final long deltaiord = blockStats.rd_req - oldStats.rd_req;
                        if (deltaiord > 0) {
                            stat.setDeltaIoRead(deltaiord);
                        }
                        final long deltaiowr = blockStats.wr_req - oldStats.wr_req;
                        if (deltaiowr > 0) {
                            stat.setDeltaIoWrite(deltaiowr);
                        }
                        final long deltabytesrd = blockStats.rd_bytes - oldStats.rd_bytes;
                        if (deltabytesrd > 0) {
                            stat.setDeltaBytesRead(deltabytesrd);
                        }
                        final long deltabyteswr = blockStats.wr_bytes - oldStats.wr_bytes;
                        if (deltabyteswr > 0) {
                            stat.setDeltaBytesWrite(deltabyteswr);
                        }
                    }
                    stats.add(stat);
                    vmDiskStats.put(String.format("%s-%s", vmName, diskPath), blockStats);
                }
            }

            return stats;
        } finally {
            if (dm != null) {
                dm.free();
            }
        }
    }

    public DiskDef getDiskWithPathOfVolumeObjectTO(List<DiskDef> disks, VolumeObjectTO vol) {
        return disks.stream()
                .filter(diskDef -> diskDef.getDiskPath() != null && diskDef.getDiskPath().contains(vol.getPath()))
                .findFirst()
                .orElseThrow(() -> new CloudRuntimeException(String.format("Unable to find volume [%s].", vol.getUuid())));
    }

    protected String getDiskPathFromDiskDef(DiskDef disk) {
        final String path = disk.getDiskPath();
        if (path != null) {
            final String[] token = path.split("/");
            if (DiskProtocol.RBD.equals(disk.getDiskProtocol())) {
                // for example, path = <RBD pool>/<disk path>
                if (token.length > 1) {
                    return token[1];
                }
            } else if (token.length > 3) {
                // for example, path = /mnt/pool_uuid/disk_path/
                return token[3];
            }
        }
        return null;
    }

    private String vmToString(Domain dm) throws LibvirtException {
        return String.format("{\"name\":\"%s\",\"uuid\":\"%s\"}", dm.getName(), dm.getUUIDString());
    }

    /**
     * Returns metrics for the period since this function was last called for the specified VM.
     * @param conn the Libvirt connection.
     * @param vmName name of the VM.
     * @return metrics for the period since last time this function was called for the VM.
     * @throws LibvirtException
     */
    public VmStatsEntry getVmStat(final Connect conn, final String vmName) throws LibvirtException {
        Domain dm = null;
        try {
            LOGGER.debug("Trying to get VM with name [{}].", vmName);
            dm = getDomain(conn, vmName);
            if (dm == null) {
                LOGGER.warn("Could not get VM with name [{}].", vmName);
                return null;
            }

            LibvirtExtendedVmStatsEntry newStats = getVmCurrentStats(dm);
            LibvirtExtendedVmStatsEntry oldStats = vmStats.get(vmName);

            VmStatsEntry metrics = calculateVmMetrics(dm, oldStats, newStats);
            vmStats.put(vmName, newStats);

            return metrics;
        } finally {
            if (dm != null) {
                dm.free();
            }
        }
    }

    /**
     * Returns a VM's current statistics.
     * @param dm domain of the VM.
     * @return current statistics of the VM.
     * @throws LibvirtException
     */
    protected LibvirtExtendedVmStatsEntry getVmCurrentStats(final Domain dm) throws LibvirtException {
        final LibvirtExtendedVmStatsEntry stats = new LibvirtExtendedVmStatsEntry();

        getVmCurrentCpuStats(dm, stats);
        getVmCurrentNetworkStats(dm, stats);
        getVmCurrentDiskStats(dm, stats);

        LOGGER.debug("Retrieved statistics for VM [{}]: [{}].", vmToString(dm), stats);
        stats.setTimestamp(Calendar.getInstance());
        return stats;
    }

    /**
     * Passes a VM's current CPU statistics into the provided LibvirtExtendedVmStatsEntry.
     * @param dm domain of the VM.
     * @param stats LibvirtExtendedVmStatsEntry that will receive the current CPU statistics.
     * @throws LibvirtException
     */
    protected void getVmCurrentCpuStats(final Domain dm, final LibvirtExtendedVmStatsEntry stats) throws LibvirtException {
        LOGGER.trace("Getting CPU stats for VM [{}].", vmToString(dm));
        stats.setCpuTime(dm.getInfo().cpuTime);
    }

    /**
     * Passes a VM's current network statistics into the provided LibvirtExtendedVmStatsEntry.
     * @param dm domain of the VM.
     * @param stats LibvirtExtendedVmStatsEntry that will receive the current network statistics.
     * @throws LibvirtException
     */
    protected void getVmCurrentNetworkStats(final Domain dm, final LibvirtExtendedVmStatsEntry stats) throws LibvirtException {
        final String vmAsString = vmToString(dm);
        LOGGER.trace("Getting network stats for VM [{}].", vmAsString);
        final List<InterfaceDef> vifs = getInterfaces(dm.getConnect(), dm.getName());
        LOGGER.debug("Found [{}] network interface(s) for VM [{}].", vifs.size(), vmAsString);
        double rx = 0;
        double tx = 0;
        for (final InterfaceDef vif : vifs) {
            final DomainInterfaceStats ifStats = dm.interfaceStats(vif.getDevName());
            rx += ifStats.rx_bytes;
            tx += ifStats.tx_bytes;
        }
        stats.setNetworkReadKBs(rx / 1024);
        stats.setNetworkWriteKBs(tx / 1024);
    }

    /**
     * Passes a VM's current disk statistics into the provided LibvirtExtendedVmStatsEntry.
     * @param dm domain of the VM.
     * @param stats LibvirtExtendedVmStatsEntry that will receive the current disk statistics.
     * @throws LibvirtException
     */
    protected void getVmCurrentDiskStats(final Domain dm, final LibvirtExtendedVmStatsEntry stats) throws LibvirtException {
        final String vmAsString = vmToString(dm);
        LOGGER.trace("Getting disk stats for VM [{}].", vmAsString);
        final List<DiskDef> disks = getDisks(dm.getConnect(), dm.getName());
        LOGGER.debug("Found [{}] disk(s) for VM [{}].", disks.size(), vmAsString);
        long io_rd = 0;
        long io_wr = 0;
        double bytes_rd = 0;
        double bytes_wr = 0;
        for (final DiskDef disk : disks) {
            if (disk.getDeviceType() == DeviceType.CDROM || disk.getDeviceType() == DeviceType.FLOPPY) {
                LOGGER.debug("Ignoring disk [{}] in VM [{}]'s stats since its deviceType is [{}].", disk.toString().replace("\n", ""), vmAsString, disk.getDeviceType());
                continue;
            }
            final DomainBlockStats blockStats = dm.blockStats(disk.getDiskLabel());
            io_rd += blockStats.rd_req;
            io_wr += blockStats.wr_req;
            bytes_rd += blockStats.rd_bytes;
            bytes_wr += blockStats.wr_bytes;
        }
        stats.setDiskReadIOs(io_rd);
        stats.setDiskWriteIOs(io_wr);
        stats.setDiskReadKBs(bytes_rd / 1024);
        stats.setDiskWriteKBs(bytes_wr / 1024);
    }

    /**
     * Calculates a VM's metrics for the period between the two statistics given as parameters.
     * @param dm domain of the VM.
     * @param oldStats old statistics. If null, the CPU, network and disk utilization won't be calculated.
     * @param newStats new statistics.
     * @return metrics for the period between the two statistics.
     * @throws LibvirtException
     */
    protected VmStatsEntry calculateVmMetrics(final Domain dm, final LibvirtExtendedVmStatsEntry oldStats, final LibvirtExtendedVmStatsEntry newStats) throws LibvirtException {
        final VmStatsEntry metrics = new VmStatsEntry();
        final DomainInfo info = dm.getInfo();
        final String vmAsString = vmToString(dm);

        metrics.setEntityType("vm");
        LOGGER.trace("Writing VM [{}]'s CPU and memory information into the metrics.", vmAsString);
        metrics.setNumCPUs(info.nrVirtCpu);
        metrics.setMemoryKBs(info.maxMem);
        metrics.setTargetMemoryKBs(info.memory);
        LOGGER.trace("Trying to get free memory for VM [{}].", vmAsString);
        metrics.setIntFreeMemoryKBs(getMemoryFreeInKBs(dm));

        if (oldStats != null) {
            LOGGER.debug("Old stats exist for VM [{}]; therefore, the utilization will be calculated.", vmAsString);

            LOGGER.trace("Calculating CPU utilization for VM [{}].", vmAsString);
            final Calendar now = Calendar.getInstance();
            long elapsedTime = now.getTimeInMillis() - oldStats.getTimestamp().getTimeInMillis();
            double utilization = (info.cpuTime - oldStats.getCpuTime()) / ((double) elapsedTime * 1000000 * info.nrVirtCpu);
            if (utilization > 0) {
                metrics.setCPUUtilization(utilization * 100);
            }

            LOGGER.trace("Calculating network utilization for VM [{}].", vmAsString);
            final double deltarx = newStats.getNetworkReadKBs() - oldStats.getNetworkReadKBs();
            if (deltarx > 0) {
                metrics.setNetworkReadKBs(deltarx);
            }
            final double deltatx = newStats.getNetworkWriteKBs() - oldStats.getNetworkWriteKBs();
            if (deltatx > 0) {
                metrics.setNetworkWriteKBs(deltatx);
            }

            LOGGER.trace("Calculating disk utilization for VM [{}].", vmAsString);
            final double deltaiord = newStats.getDiskReadIOs() - oldStats.getDiskReadIOs();
            if (deltaiord > 0) {
                metrics.setDiskReadIOs(deltaiord);
            }
            final double deltaiowr = newStats.getDiskWriteIOs() - oldStats.getDiskWriteIOs();
            if (deltaiowr > 0) {
                metrics.setDiskWriteIOs(deltaiowr);
            }
            final double deltabytesrd = newStats.getDiskReadKBs() - oldStats.getDiskReadKBs();
            if (deltabytesrd > 0) {
                metrics.setDiskReadKBs(deltabytesrd);
            }
            final double deltabyteswr = newStats.getDiskWriteKBs() - oldStats.getDiskWriteKBs();
            if (deltabyteswr > 0) {
                metrics.setDiskWriteKBs(deltabyteswr);
            }
        }

        String metricsAsString = new ReflectionToStringBuilder(metrics, ToStringStyle.JSON_STYLE).setExcludeFieldNames("vmId", "vmUuid").toString();
        LOGGER.debug("Calculated metrics for VM [{}]: [{}].", vmAsString, metricsAsString);

        return metrics;
    }

    /**
     * This method retrieves the memory statistics from the domain given as parameters.
     * If no memory statistic is found, it will return {@link NumberUtils#LONG_MINUS_ONE} as the value of free memory in the domain.
     * If it can retrieve the domain memory statistics, it will return the free memory statistic; that means, it returns the value at the first position of the array returned by {@link Domain#memoryStats(int)}.
     *
     * @return the amount of free memory in KBs
     */
    protected long getMemoryFreeInKBs(Domain dm) throws LibvirtException {
        MemoryStatistic[] memoryStats = dm.memoryStats(NUMMEMSTATS);
        LOGGER.trace("Retrieved memory statistics (information about tags can be found on the libvirt documentation): {}.",
                () -> Stream.of(memoryStats).map(stat -> stat.toString().trim().replace("\n", ",")).collect(Collectors.joining("},{", "[{", "}]")));

        long freeMemory = NumberUtils.LONG_MINUS_ONE;

        if (ArrayUtils.isEmpty(memoryStats)){
            return freeMemory;
        }

        for (int i = 0; i < memoryStats.length; i++) {
            if(memoryStats[i].getTag() == UNUSEDMEMORY) {
                freeMemory = memoryStats[i].getValue();
                break;
            }
        }

        if (freeMemory == NumberUtils.LONG_MINUS_ONE){
            LOGGER.warn("Couldn't retrieve free memory, returning -1.");
        }
        return freeMemory;
    }

    public void removeCheckpointsOnVm(String vmName, String volumeUuid, List<String> checkpointPaths) {
        logger.debug("Removing checkpoints with paths [{}] of volume [{}] on VM [{}].", checkpointPaths, volumeUuid, vmName);
        String checkpointName;
        for (String checkpointPath : checkpointPaths) {
            checkpointName = checkpointPath.substring(checkpointPath.lastIndexOf("/") + 1);
            Script.runSimpleBashScript(String.format(CHECKPOINT_DELETE_COMMAND, vmName, checkpointName));
        }
        logger.debug("Removed all checkpoints of volume [{}] on VM [{}].", volumeUuid, vmName);
    }

    public boolean recreateCheckpointsOnVm(List<VolumeObjectTO> volumes, String vmName, Connect conn) {
        logger.debug("Trying to recreate checkpoints on VM [{}] with volumes [{}].", vmName, volumes);
        try {
            validateLibvirtAndQemuVersionForIncrementalSnapshots();
        } catch (CloudRuntimeException e) {
            logger.warn("Will not recreate the checkpoints on VM as {}", e.getMessage(), e);
            return false;
        }
        List<DiskDef> diskDefs = getDisks(conn, vmName);
        Map<VolumeObjectTO, DiskDef> mapDiskToDiskDef = mapVolumeToDiskDef(volumes, diskDefs);

        for (VolumeObjectTO volume : volumes) {
            if (CollectionUtils.isEmpty(volume.getCheckpointPaths())) {
                continue;
            }
            Set<KVMStoragePool> storagePoolSet = connectToAllVolumeSnapshotSecondaryStorages(volume);
            recreateCheckpointsOfDisk(vmName, volume, mapDiskToDiskDef);
            disconnectAllVolumeSnapshotSecondaryStorages(storagePoolSet);
        }
        logger.debug("Successfully recreated all checkpoints on VM [{}].", vmName);
        return true;
    }

    public Set<KVMStoragePool> connectToAllVolumeSnapshotSecondaryStorages(VolumeObjectTO volumeObjectTO) {
        return volumeObjectTO.getCheckpointImageStoreUrls().stream().map(uri -> getStoragePoolMgr().getStoragePoolByURI(uri)).collect(Collectors.toSet());
    }

    public void disconnectAllVolumeSnapshotSecondaryStorages(Set<KVMStoragePool> kvmStoragePools) {
        kvmStoragePools.forEach(storage -> getStoragePoolMgr().deleteStoragePool(storage.getType(), storage.getUuid()));
    }


    protected void recreateCheckpointsOfDisk(String vmName, VolumeObjectTO volume, Map<VolumeObjectTO, DiskDef> mapDiskToDiskDef) {
        for (String path : volume.getCheckpointPaths()) {
            DiskDef diskDef = mapDiskToDiskDef.get(volume);
            if (diskDef != null) {
                try {
                    updateDiskLabelOnXml(path, diskDef.getDiskLabel());
                } catch (ParserConfigurationException | IOException | SAXException | TransformerException | XPathExpressionException e) {
                    logger.error("Exception while parsing checkpoint XML with path [{}].", path, e);
                    throw new CloudRuntimeException(e);
                }
            } else {
                logger.debug("Could not map [{}] to any disk definition. Will try to recreate snapshot without updating disk label.", volume);
            }

            logger.trace("Recreating checkpoint with path [{}] on VM [{}].", path, vmName);
            Script.runSimpleBashScript(String.format(CHECKPOINT_CREATE_COMMAND, vmName, path));
        }
    }

    /**
     * Changes the value of the disk label of the checkpoint XML found in {@code path} to {@code label}. This method assumes that the checkpoint only contains one disk.
     * @param path the path to the checkpoint XML to be updated
     * @param label the new label to be used for the disk
     * */
    private void updateDiskLabelOnXml(String path, String label) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException, TransformerException {
        logger.trace("Updating checkpoint with path [{}] to use disk label [{}].", path, label);

        DocumentBuilderFactory docFactory = ParserUtils.getSaferDocumentBuilderFactory();
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
        Document doc = docBuilder.parse(new File(path));

        XPath xPath = XPathFactory.newInstance().newXPath();
        Node diskNode = (Node) xPath.compile("/domaincheckpoint/disks/disk").evaluate(doc, XPathConstants.NODE);
        diskNode.getAttributes().getNamedItem("name").setNodeValue(label);

        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.INDENT, "yes");
        tf.setOutputProperty(OutputKeys.METHOD, "xml");
        tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        DOMSource domSource = new DOMSource(doc);
        StreamResult sr = new StreamResult(new File(path));
        tf.transform(domSource, sr);
    }

    protected Map<VolumeObjectTO, DiskDef> mapVolumeToDiskDef(List<VolumeObjectTO> volumeTos, List<DiskDef> diskDefs) {
        HashMap<VolumeObjectTO, DiskDef> diskToDiskDefHashMap = new HashMap<>();
        for (VolumeObjectTO volumeTo : volumeTos) {
            for (DiskDef diskDef : diskDefs) {
                if (StringUtils.contains(diskDef.getDiskPath(), volumeTo.getPath())) {
                    diskToDiskDefHashMap.put(volumeTo, diskDef);
                }
            }
        }
        return diskToDiskDefHashMap;
    }

    public void validateLibvirtAndQemuVersionForIncrementalSnapshots() {
        if (getHypervisorLibvirtVersion() < MINIMUM_LIBVIRT_VERSION_FOR_INCREMENTAL_SNAPSHOT || getHypervisorQemuVersion() < MINIMUM_QEMU_VERSION_FOR_INCREMENTAL_SNAPSHOT) {
            throw new CloudRuntimeException(String.format("Hypervisor version is insufficient, should have at least libvirt [%s] and qemu [%s] but we have [%s] and [%s].",
                    MINIMUM_LIBVIRT_VERSION_FOR_INCREMENTAL_SNAPSHOT, MINIMUM_QEMU_VERSION_FOR_INCREMENTAL_SNAPSHOT, getHypervisorLibvirtVersion(), getHypervisorQemuVersion()));
        }
    }

    private boolean canBridgeFirewall(final String prvNic) {
        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("can_bridge_firewall");
        cmd.add("--privnic", prvNic);
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean destroyNetworkRulesForVM(final Connect conn, final String vmName) {
        if (!canBridgeFirewall) {
            return false;
        }
        String vif = null;
        final List<InterfaceDef> intfs = getInterfaces(conn, vmName);
        if (intfs.size() > 0) {
            final InterfaceDef intf = intfs.get(0);
            vif = intf.getDevName();
        }
        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("destroy_network_rules_for_vm");
        cmd.add("--vmname", vmName);
        if (vif != null) {
            cmd.add("--vif", vif);
        }
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    /**
     * Function to destroy the security group rules applied to the nic's
     * @param conn
     * @param vmName
     * @param nic
     * @return
     *      true   : If success
     *      false  : If failure
     */
    public boolean destroyNetworkRulesForNic(final Connect conn, final String vmName, final NicTO nic) {
        if (!canBridgeFirewall) {
            return false;
        }
        final List<String> nicSecIps = nic.getNicSecIps();
        String secIpsStr;
        final StringBuilder sb = new StringBuilder();
        if (nicSecIps != null) {
            for (final String ip : nicSecIps) {
                sb.append(ip).append(SecurityGroupRulesCmd.RULE_COMMAND_SEPARATOR);
            }
            secIpsStr = sb.toString();
        } else {
            secIpsStr = "0" + SecurityGroupRulesCmd.RULE_COMMAND_SEPARATOR;
        }
        final List<InterfaceDef> intfs = getInterfaces(conn, vmName);
        if (intfs.size() == 0 || intfs.size() < nic.getDeviceId()) {
            return false;
        }

        final InterfaceDef intf = intfs.get(nic.getDeviceId());
        final String brname = intf.getBrName();
        final String vif = intf.getDevName();

        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("destroy_network_rules_for_vm");
        cmd.add("--vmname", vmName);
        if (nic.getIp() != null) {
            cmd.add("--vmip", nic.getIp());
        }
        cmd.add("--vmmac", nic.getMac());
        cmd.add("--vif", vif);
        cmd.add("--nicsecips", secIpsStr);

        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    /**
     * Function to apply default network rules for a VM
     * @param conn
     * @param vm
     * @param checkBeforeApply
     * @return
     */
    public boolean applyDefaultNetworkRules(final Connect conn, final VirtualMachineTO vm, final boolean checkBeforeApply) {
        NicTO[] nicTOs = new NicTO[] {};
        if (vm != null && vm.getNics() != null) {
            LOGGER.debug("Checking default network rules for vm " + vm.getName());
            nicTOs = vm.getNics();
        }
        for (NicTO nic : nicTOs) {
            if (vm.getType() != VirtualMachine.Type.User) {
                nic.setPxeDisable(true);
            }
        }
        boolean isFirstNic = true;
        for (final NicTO nic : nicTOs) {
            if (nic.isSecurityGroupEnabled() || nic.getIsolationUri() != null && nic.getIsolationUri().getScheme().equalsIgnoreCase(IsolationType.Ec2.toString())) {
                if (vm.getType() != VirtualMachine.Type.User) {
                    configureDefaultNetworkRulesForSystemVm(conn, vm.getName());
                    break;
                }
                if (!applyDefaultNetworkRulesOnNic(conn, vm.getName(), vm.getId(), nic, isFirstNic, checkBeforeApply)) {
                    LOGGER.error("Unable to apply default network rule for nic " + nic.getName() + " for VM " + vm.getName());
                    return false;
                }
                isFirstNic = false;
            }
        }
        return true;
    }

    /**
     * Function to apply default network rules for a NIC
     * @param conn
     * @param vmName
     * @param vmId
     * @param nic
     * @param isFirstNic
     * @param checkBeforeApply
     * @return
     */
    public boolean applyDefaultNetworkRulesOnNic(final Connect conn, final String vmName, final Long vmId, final NicTO nic, boolean isFirstNic, boolean checkBeforeApply) {
        final List<String> nicSecIps = nic.getNicSecIps();
        String secIpsStr;
        final StringBuilder sb = new StringBuilder();
        if (nicSecIps != null) {
            for (final String ip : nicSecIps) {
                sb.append(ip).append(SecurityGroupRulesCmd.RULE_COMMAND_SEPARATOR);
            }
            secIpsStr = sb.toString();
        } else {
            secIpsStr = "0" + SecurityGroupRulesCmd.RULE_COMMAND_SEPARATOR;
        }
        return defaultNetworkRules(conn, vmName, nic, vmId, secIpsStr, isFirstNic, checkBeforeApply);
    }

    public boolean defaultNetworkRules(final Connect conn, final String vmName, final NicTO nic, final Long vmId, final String secIpStr, final boolean isFirstNic, final boolean checkBeforeApply) {
        if (!canBridgeFirewall) {
            return false;
        }

        final List<InterfaceDef> intfs = getInterfaces(conn, vmName);
        if (intfs.size() == 0 || intfs.size() < nic.getDeviceId()) {
            return false;
        }

        final InterfaceDef intf = intfs.get(nic.getDeviceId());
        final String brname = intf.getBrName();
        final String vif = intf.getDevName();

        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("default_network_rules");
        cmd.add("--vmname", vmName);
        cmd.add("--vmid", vmId.toString());
        if (nic.getIp() != null) {
            cmd.add("--vmip", nic.getIp());
        }
        if (nic.getIp6Address() != null) {
            cmd.add("--vmip6", nic.getIp6Address());
        }
        cmd.add("--vmmac", nic.getMac());
        cmd.add("--vif", vif);
        cmd.add("--brname", brname);
        cmd.add("--nicsecips", secIpStr);
        if (isFirstNic) {
            cmd.add("--isFirstNic");
        }
        if (checkBeforeApply) {
            cmd.add("--check");
        }
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    protected boolean post_default_network_rules(final Connect conn, final String vmName, final NicTO nic, final Long vmId, final InetAddress dhcpServerIp, final String hostIp, final String hostMacAddr) {
        if (!canBridgeFirewall) {
            return false;
        }

        final List<InterfaceDef> intfs = getInterfaces(conn, vmName);
        if (intfs.size() < nic.getDeviceId()) {
            return false;
        }

        final InterfaceDef intf = intfs.get(nic.getDeviceId());
        final String brname = intf.getBrName();
        final String vif = intf.getDevName();

        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("post_default_network_rules");
        cmd.add("--vmname", vmName);
        cmd.add("--vmid", vmId.toString());
        cmd.add("--vmip", nic.getIp());
        cmd.add("--vmmac", nic.getMac());
        cmd.add("--vif", vif);
        cmd.add("--brname", brname);
        if (dhcpServerIp != null) {
            cmd.add("--dhcpSvr", dhcpServerIp.getHostAddress());
        }

        cmd.add("--hostIp", hostIp);
        cmd.add("--hostMacAddr", hostMacAddr);
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean configureDefaultNetworkRulesForSystemVm(final Connect conn, final String vmName) {
        if (!canBridgeFirewall) {
            return false;
        }

        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("default_network_rules_systemvm");
        cmd.add("--vmname", vmName);
        cmd.add("--localbrname", linkLocalBridgeName);
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public Answer listFilesAtPath(ListDataStoreObjectsCommand command) {
        DataStoreTO store = command.getStore();
        KVMStoragePool storagePool = storagePoolManager.getStoragePool(StoragePoolType.NetworkFilesystem, store.getUuid());
        return listFilesAtPath(storagePool.getLocalPath(), command.getPath(), command.getStartIndex(), command.getPageSize());
    }

    public boolean addNetworkRules(final String vmName, final String vmId, final String guestIP, final String guestIP6, final String sig, final String seq, final String mac, final String rules, final String vif, final String brname,
                                   final String secIps) {
        if (!canBridgeFirewall) {
            return false;
        }

        final String newRules = rules.replace(" ", ";");
        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("add_network_rules");
        cmd.add("--vmname", vmName);
        cmd.add("--vmid", vmId);
        cmd.add("--vmip", guestIP);
        if (StringUtils.isNotBlank(guestIP6)) {
            cmd.add("--vmip6", guestIP6);
        }
        cmd.add("--sig", sig);
        cmd.add("--seq", seq);
        cmd.add("--vmmac", mac);
        cmd.add("--vif", vif);
        cmd.add("--brname", brname);
        cmd.add("--nicsecips", secIps);
        if (newRules != null && !newRules.isEmpty()) {
            cmd.add("--rules", newRules);
        }
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean configureNetworkRulesVMSecondaryIP(final Connect conn, final String vmName, final String vmMac, final String secIp, final String action) {

        if (!canBridgeFirewall) {
            return false;
        }

        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("network_rules_vmSecondaryIp");
        cmd.add("--vmname", vmName);
        cmd.add("--vmmac", vmMac);
        cmd.add("--nicsecips", secIp);
        cmd.add("--action=" + action);

        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean setupTungstenVRouter(final String oper, final String inf, final String subnet, final String route,
        final String vrf) {
        final Script cmd = new Script(setupTungstenVrouterPath, timeout, LOGGER);
        cmd.add(oper);
        cmd.add(inf);
        cmd.add(subnet);
        cmd.add(route);
        cmd.add(vrf);

        final String result = cmd.execute();
        return result == null;
    }

    public boolean updateTungstenLoadbalancerStats(final String lbUuid, final String lbStatsPort,
        final String lbStatsUri, final String lbStatsAuth) {
        final Script cmd = new Script(updateTungstenLoadbalancerStatsPath, timeout, LOGGER);
        cmd.add(lbUuid);
        cmd.add(lbStatsPort);
        cmd.add(lbStatsUri);
        cmd.add(lbStatsAuth);

        final String result = cmd.execute();
        return result == null;
    }

    public boolean updateTungstenLoadbalancerSsl(final String lbUuid, final String sslCertName,
        final String certificateKey, final String privateKey, final String privateIp, final String port) {
        final Script cmd = new Script(updateTungstenLoadbalancerSslPath, timeout, LOGGER);
        cmd.add(lbUuid);
        cmd.add(sslCertName);
        cmd.add(certificateKey);
        cmd.add(privateKey);
        cmd.add(privateIp);
        cmd.add(port);

        final String result = cmd.execute();
        return result == null;
    }

    public boolean setupTfRoute(final String privateIpAddress, final String fromNetwork, final String toNetwork) {
        final Script setupTfRouteScript = new Script(routerProxyPath, timeout, LOGGER);
        setupTfRouteScript.add("setup_tf_route.py");
        setupTfRouteScript.add(privateIpAddress);
        setupTfRouteScript.add(fromNetwork);
        setupTfRouteScript.add(toNetwork);

        final OutputInterpreter.OneLineParser setupTfRouteParser = new OutputInterpreter.OneLineParser();
        final String result = setupTfRouteScript.execute(setupTfRouteParser);
        if (result != null) {
            LOGGER.debug("Failed to execute setup TF Route:" + result);
            return false;
        }
        return true;
    }

    public boolean cleanupRules() {
        if (!canBridgeFirewall) {
            return false;
        }
        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("cleanup_rules");
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public String getRuleLogsForVms() {
        final Script cmd = new Script(securityGroupPath, timeout, LOGGER);
        cmd.add("get_rule_logs_for_vms");
        final OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();
        final String result = cmd.execute(parser);
        if (result == null) {
            return parser.getLine();
        }
        return null;
    }

    private HashMap<String, Pair<Long, Long>> syncNetworkGroups(final long id) {
        final HashMap<String, Pair<Long, Long>> states = new HashMap<String, Pair<Long, Long>>();

        final String result = getRuleLogsForVms();
        LOGGER.trace("syncNetworkGroups: id=" + id + " got: " + result);
        final String[] rulelogs = result != null ? result.split(";") : new String[0];
        for (final String rulesforvm : rulelogs) {
            final String[] log = rulesforvm.split(",");
            if (log.length != 6) {
                continue;
            }
            try {
                states.put(log[0], new Pair<Long, Long>(Long.parseLong(log[1]), Long.parseLong(log[5])));
            } catch (final NumberFormatException nfe) {
                states.put(log[0], new Pair<Long, Long>(-1L, -1L));
            }
        }
        return states;
    }

    /* online snapshot supported by enhanced qemu-kvm */
    private boolean isSnapshotSupported() {
        final String result = executeBashScript("qemu-img --help|grep convert");
        if (result != null) {
            return false;
        } else {
            return true;
        }
    }

    public Pair<Double, Double> getNicStats(final String nicName) {
        return new Pair<Double, Double>(readDouble(nicName, "rx_bytes"), readDouble(nicName, "tx_bytes"));
    }

    double readDouble(final String nicName, final String fileName) {
        final String path = "/sys/class/net/" + nicName + "/statistics/" + fileName;
        try {
            return Double.parseDouble(FileUtils.readFileToString(new File(path)));
        } catch (final IOException ioe) {
            LOGGER.warn("Failed to read the " + fileName + " for " + nicName + " from " + path, ioe);
            return 0.0;
        }
    }

    private String prettyVersion(final long version) {
        final long major = version / 1000000;
        final long minor = version % 1000000 / 1000;
        final long release = version % 1000000 % 1000;
        return major + "." + minor + "." + release;
    }

    @Override
    public void setName(final String name) {
        // TODO Auto-generated method stub
    }

    @Override
    public void setConfigParams(final Map<String, Object> params) {
        // TODO Auto-generated method stub
    }

    @Override
    public Map<String, Object> getConfigParams() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getRunLevel() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void setRunLevel(final int level) {
        // TODO Auto-generated method stub
    }

    public HypervisorType getHypervisorType(){
        return hypervisorType;
    }

    public String mapRbdDevice(final KVMPhysicalDisk disk){
        final KVMStoragePool pool = disk.getPool();
        //Check if rbd image is already mapped
        final String[] splitPoolImage = disk.getPath().split("/");
        String device = Script.runSimpleBashScript("rbd showmapped | grep \""+splitPoolImage[0]+"[ ]*"+splitPoolImage[1]+"\" | grep -o \"[^ ]*[ ]*$\"");
        if(device == null) {
            //If not mapped, map and return mapped device
            Script.runSimpleBashScript("rbd map " + disk.getPath() + " --id " + pool.getAuthUserName());
            device = Script.runSimpleBashScript("rbd showmapped | grep \""+splitPoolImage[0]+"[ ]*"+splitPoolImage[1]+"\" | grep -o \"[^ ]*[ ]*$\"");
        }
        return device;
    }

    public List<Ternary<String, Boolean, String>> cleanVMSnapshotMetadata(Domain dm) throws LibvirtException {
        LOGGER.debug("Cleaning the metadata of vm snapshots of vm " + dm.getName());
        List<Ternary<String, Boolean, String>> vmsnapshots = new ArrayList<Ternary<String, Boolean, String>>();
        if (dm.snapshotNum() == 0) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("VM [%s] does not have any snapshots. Skipping cleanup of snapshots for this VM.", dm.getName()));
            }
            return vmsnapshots;
        }
        String currentSnapshotName = null;
        try {
            DomainSnapshot snapshotCurrent = dm.snapshotCurrent();
            String snapshotXML = snapshotCurrent.getXMLDesc();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Current snapshot of VM [%s] has the following XML: [%s].", dm.getName(), snapshotXML));
            }

            snapshotCurrent.free();
            DocumentBuilder builder;
            try {
                builder = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder();

                InputSource is = new InputSource();
                is.setCharacterStream(new StringReader(snapshotXML));
                Document doc = builder.parse(is);
                Element rootElement = doc.getDocumentElement();

                currentSnapshotName = getTagValue("name", rootElement);
            } catch (ParserConfigurationException | SAXException | IOException e) {
                LOGGER.error(String.format("Failed to parse snapshot configuration [%s] of VM [%s] due to: [%s].", snapshotXML, dm.getName(), e.getMessage()), e);
            }
        } catch (LibvirtException e) {
            LOGGER.error(String.format("Failed to get the current snapshot of VM [%s] due to: [%s]. Continuing the migration process.", dm.getName(), e.getMessage()), e);
        }
        int flags = 2; // VIR_DOMAIN_SNAPSHOT_DELETE_METADATA_ONLY = 2
        String[] snapshotNames = dm.snapshotListNames();
        Arrays.sort(snapshotNames);
        LOGGER.debug(String.format("Found [%s] snapshots in VM [%s] to clean.", snapshotNames.length, dm.getName()));
        for (String snapshotName: snapshotNames) {
            DomainSnapshot snapshot = dm.snapshotLookupByName(snapshotName);
            Boolean isCurrent = (currentSnapshotName != null && currentSnapshotName.equals(snapshotName)) ? true: false;
            vmsnapshots.add(new Ternary<String, Boolean, String>(snapshotName, isCurrent, snapshot.getXMLDesc()));
        }
        for (String snapshotName: snapshotNames) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Cleaning snapshot [%s] of VM [%s] metadata.", snapshotNames, dm.getName()));
            }
            DomainSnapshot snapshot = dm.snapshotLookupByName(snapshotName);
            snapshot.delete(flags); // clean metadata of vm snapshot
        }
        return vmsnapshots;
    }

    public String getVlanIdFromBridgeName(String brName) {
        if (StringUtils.isNotBlank(brName)) {
            String[] s = brName.split("-");
            if (s.length > 1) {
                return s[1];
            }
            return null;
        }
        return null;
    }

    public boolean shouldDeleteBridge(Map<String, Boolean> vlanToPersistenceMap, String vlanId) {
        if (MapUtils.isNotEmpty(vlanToPersistenceMap) && vlanId != null && vlanToPersistenceMap.containsKey(vlanId)) {
            return vlanToPersistenceMap.get(vlanId);
        }
        return true;
    }

    private static String getTagValue(String tag, Element eElement) {
        NodeList nlList = eElement.getElementsByTagName(tag).item(0).getChildNodes();
        Node nValue = nlList.item(0);

        return nValue.getNodeValue();
    }

    public void restoreVMSnapshotMetadata(Domain dm, String vmName, List<Ternary<String, Boolean, String>> vmsnapshots) {
        LOGGER.debug("Restoring the metadata of vm snapshots of vm " + vmName);
        for (Ternary<String, Boolean, String> vmsnapshot: vmsnapshots) {
            String snapshotName = vmsnapshot.first();
            Boolean isCurrent = vmsnapshot.second();
            String snapshotXML = vmsnapshot.third();
            LOGGER.debug("Restoring vm snapshot " + snapshotName + " on " + vmName + " with XML:\n " + snapshotXML);
            try {
                int flags = 1; // VIR_DOMAIN_SNAPSHOT_CREATE_REDEFINE = 1
                if (isCurrent) {
                    flags += 2; // VIR_DOMAIN_SNAPSHOT_CREATE_CURRENT = 2
                }
                dm.snapshotCreateXML(snapshotXML, flags);
            } catch (LibvirtException e) {
                LOGGER.debug("Failed to restore vm snapshot " + snapshotName + ", continue");
                continue;
            }
        }
    }

    public String getHostDistro() {
        return hostDistro;
    }

    public boolean isHostSecured() {
        // Test for host certificates
        final File confFile = PropertiesUtil.findConfigFile(KeyStoreUtils.AGENT_PROPSFILE);
        if (confFile == null || !confFile.exists() || !Paths.get(confFile.getParent(), KeyStoreUtils.CERT_FILENAME).toFile().exists()) {
            return false;
        }

        // Test for libvirt TLS configuration
        try {
            new Connect(String.format("qemu+tls://%s/system", privateIp));
        } catch (final LibvirtException ignored) {
            return false;
        }
        return true;
    }

    /**
     * Test host for volume encryption support
     * @return boolean
     */
    public boolean hostSupportsVolumeEncryption() {
        // test qemu-img
        try {
            QemuImg qemu = new QemuImg(0);
            if (!qemu.supportsImageFormat(PhysicalDiskFormat.LUKS)) {
                return false;
            }
        } catch (QemuImgException | LibvirtException ex) {
            LOGGER.info("Host's qemu install doesn't support encryption", ex);
            return false;
        }

        // test cryptsetup
        CryptSetup crypt = new CryptSetup();
        if (!crypt.isSupported()) {
            LOGGER.info("Host can't run cryptsetup");
            return false;
        }

        return true;
    }

    public boolean isSecureMode(String bootMode) {
        if (StringUtils.isNotBlank(bootMode) && "secure".equalsIgnoreCase(bootMode)) {
            return true;
        }

        return false;
    }

    public boolean hostSupportsInstanceConversion() {
        int exitValue = Script.runSimpleBashScriptForExitValue(INSTANCE_CONVERSION_SUPPORTED_CHECK_CMD);
        if (isUbuntuOrDebianHost() && exitValue == 0) {
            exitValue = Script.runSimpleBashScriptForExitValue(UBUNTU_NBDKIT_PKG_CHECK_CMD);
        }
        return exitValue == 0;
    }

    public boolean hostSupportsWindowsGuestConversion() {
        if (isUbuntuOrDebianHost()) {
            int exitValue = Script.runSimpleBashScriptForExitValue(UBUNTU_WINDOWS_GUEST_CONVERSION_SUPPORTED_CHECK_CMD);
            return exitValue == 0;
        }
        int exitValue = Script.runSimpleBashScriptForExitValue(WINDOWS_GUEST_CONVERSION_SUPPORTED_CHECK_CMD);
        return exitValue == 0;
    }

    public boolean hostSupportsOvfExport() {
        int exitValue = Script.runSimpleBashScriptForExitValue(OVF_EXPORT_SUPPORTED_CHECK_CMD);
        return exitValue == 0;
    }

    public String getHostVirtV2vVersion() {
        if (!hostSupportsInstanceConversion()) {
            return "";
        }
        String cmd = String.format("%s | awk '{print $2}'", INSTANCE_CONVERSION_SUPPORTED_CHECK_CMD);
        String version = Script.runSimpleBashScript(cmd);
        return StringUtils.isNotBlank(version) ? version.split(",")[0] : "";
    }

    public String getHostOvfToolVersion() {
        if (!hostSupportsOvfExport()) {
            return "";
        }
        return Script.runSimpleBashScript(OVF_EXPORT_TOOl_GET_VERSION_CMD);
    }

    public boolean ovfExportToolSupportsParallelThreads() {
        String ovfExportToolVersion = getHostOvfToolVersion();
        if (StringUtils.isBlank(ovfExportToolVersion)) {
            return false;
        }
        String[] ovfExportToolVersions = ovfExportToolVersion.trim().split("\\.");
        if (ovfExportToolVersions.length > 1) {
            try {
                int majorVersion = Integer.parseInt(ovfExportToolVersions[0]);
                int minorVersion = Integer.parseInt(ovfExportToolVersions[1]);
                //ovftool version >= 4.4 supports parallel threads
                if (majorVersion > 4 || (majorVersion == 4 && minorVersion >= 4)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    protected void setCpuTopology(CpuModeDef cmd, int vCpusInDef, Map<String, String> details) {
        if (!enableManuallySettingCpuTopologyOnKvmVm) {
            LOGGER.debug(String.format("Skipping manually setting CPU topology on VM's XML due to it is disabled in agent.properties {\"property\": \"%s\", \"value\": %s}.",
              AgentProperties.ENABLE_MANUALLY_SETTING_CPU_TOPOLOGY_ON_KVM_VM.getName(), enableManuallySettingCpuTopologyOnKvmVm));
            return;
        }

        int numCoresPerSocket = 1;
        int numThreadsPerCore = 1;

        if (details != null) {
            numCoresPerSocket = NumbersUtil.parseInt(details.get(VmDetailConstants.CPU_CORE_PER_SOCKET), 1);
            numThreadsPerCore = NumbersUtil.parseInt(details.get(VmDetailConstants.CPU_THREAD_PER_CORE), 1);
        }

        if ((numCoresPerSocket * numThreadsPerCore) > vCpusInDef) {
            LOGGER.warn(String.format("cores per socket (%d) * threads per core (%d) exceeds total VM cores. Ignoring extra topology", numCoresPerSocket, numThreadsPerCore));
            numCoresPerSocket = 1;
            numThreadsPerCore = 1;
        }

        if (vCpusInDef % (numCoresPerSocket * numThreadsPerCore) != 0) {
            LOGGER.warn(String.format("cores per socket(%d) * threads per core(%d) doesn't divide evenly into total VM cores(%d). Ignoring extra topology", numCoresPerSocket, numThreadsPerCore, vCpusInDef));
            numCoresPerSocket = 1;
            numThreadsPerCore = 1;
        }

        // Set default coupling (makes 4 or 6 core sockets for larger core configs)
        int numTotalSockets = 1;
        if (numCoresPerSocket == 1 && numThreadsPerCore == 1) {
            if (vCpusInDef % 6 == 0) {
                numCoresPerSocket = 6;
            } else if (vCpusInDef % 4 == 0) {
                numCoresPerSocket = 4;
            }
            numTotalSockets = vCpusInDef / numCoresPerSocket;
        } else {
            int nTotalCores = vCpusInDef / numThreadsPerCore;
            numTotalSockets = nTotalCores / numCoresPerSocket;
        }

        cmd.setTopology(numCoresPerSocket, numThreadsPerCore, numTotalSockets);
    }

    public void setBackingFileFormat(String volPath) {
        final int timeout = 0;
        QemuImgFile file = new QemuImgFile(volPath);

        try{
            QemuImg qemu = new QemuImg(timeout);
            Map<String, String> info = qemu.info(file);
            String backingFilePath = info.get(QemuImg.BACKING_FILE);
            String backingFileFormat = info.get(QemuImg.BACKING_FILE_FORMAT);
            if (StringUtils.isNotBlank(backingFilePath) && StringUtils.isBlank(backingFileFormat)) {
                // VMs which are created in CloudStack 4.14 and before cannot be started or migrated
                // in latest Linux distributions due to missing backing file format
                // Please refer to https://libvirt.org/kbase/backing_chains.html#vm-refuses-to-start-due-to-misconfigured-backing-store-format
                LOGGER.info("Setting backing file format of " + volPath);
                QemuImgFile backingFile = new QemuImgFile(backingFilePath);
                Map<String, String> backingFileinfo = qemu.info(backingFile);
                String backingFileFmt = backingFileinfo.get(QemuImg.FILE_FORMAT);
                qemu.rebase(file, backingFile, backingFileFmt, false);
            }
        } catch (QemuImgException | LibvirtException e) {
            LOGGER.error("Failed to set backing file format of " + volPath + " due to : " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the memory of the running VM. <br/>
     * The libvirt (see <a href="https://github.com/libvirt/libvirt/blob/master/src/conf/domain_conf.c">https://github.com/libvirt/libvirt/blob/master/src/conf/domain_conf.c</a>, function <b>virDomainDefParseMemory</b>) uses <b>total memory</b> as the tag <b>memory</b>, in VM's XML.
     * @param dm domain of the VM.
     * @return the memory of the VM.
     * @throws org.libvirt.LibvirtException
     **/
    public static long getDomainMemory(Domain dm) throws LibvirtException {
        return dm.getMaxMemory();
    }

    /**
     * Retrieves the quantity of running VCPUs of the running VM. <br/>
     * @param dm domain of the VM.
     * @return the quantity of running VCPUs of the running VM.
     * @throws org.libvirt.LibvirtException
     **/
    public static long countDomainRunningVcpus(Domain dm) throws LibvirtException {
        VcpuInfo vcpus[] = dm.getVcpusInfo();
        return Arrays.stream(vcpus).filter(vcpu -> vcpu.state.equals(VcpuInfo.VcpuState.VIR_VCPU_RUNNING)).count();
    }

    /**
     * Retrieves the cpu_shares (priority) of the running VM <br/>
     * @param dm domain of the VM.
     * @return the value of cpu_shares of the running VM.
     * @throws org.libvirt.LibvirtException
     **/
    public static Integer getCpuShares(Domain dm) throws LibvirtException {
        for (SchedParameter c : dm.getSchedulerParameters()) {
            if (c.field.equals("cpu_shares")) {
                return Integer.parseInt(c.getValueAsString());
            }
        }
        LOGGER.warn(String.format("Could not get cpu_shares of domain: [%s]. Returning default value of 0. ", dm.getName()));
        return 0;
    }

    /**
     * Sets the cpu_shares (priority) of the running VM <br/>
     * @param dm domain of the VM.
     * @param cpuShares new priority of the running VM.
     * @throws org.libvirt.LibvirtException
     **/
    public static void setCpuShares(Domain dm, Integer cpuShares) throws LibvirtException {
        SchedUlongParameter[] params = new SchedUlongParameter[1];
        params[0] = new SchedUlongParameter();
        params[0].field = "cpu_shares";
        params[0].value = cpuShares;

        dm.setSchedulerParameters(params);
    }

    /**
     * Set up a libvirt secret for a volume. If Libvirt says that a secret already exists for this volume path, we use its uuid.
     * The UUID of the secret needs to be prescriptive such that we can register the same UUID on target host during live migration
     *
     * @param conn libvirt connection
     * @param consumer identifier for volume in secret
     * @param data secret contents
     * @return uuid of matching secret for volume
     * @throws LibvirtException
     */
    public String createLibvirtVolumeSecret(Connect conn, String consumer, byte[] data) throws LibvirtException {
        String secretUuid = null;
        LibvirtSecretDef secretDef = new LibvirtSecretDef(LibvirtSecretDef.Usage.VOLUME, generateSecretUUIDFromString(consumer));
        secretDef.setVolumeVolume(consumer);
        secretDef.setPrivate(true);
        secretDef.setEphemeral(true);

        try {
            Secret secret = conn.secretDefineXML(secretDef.toString());
            secret.setValue(data);
            secretUuid = secret.getUUIDString();
            secret.free();
        } catch (LibvirtException ex) {
            if (ex.getMessage().contains("already defined for use")) {
                Match match = new Match();
                if (UuidUtils.getUuidRegex().matches(ex.getMessage(), match)) {
                    secretUuid = match.getCapturedText(0);
                    LOGGER.info(String.format("Reusing previously defined secret '%s' for volume '%s'", secretUuid, consumer));
                } else {
                    throw ex;
                }
            } else {
                throw ex;
            }
        }

        return secretUuid;
    }

    public void removeLibvirtVolumeSecret(Connect conn, String secretUuid) throws LibvirtException {
        try {
            Secret secret = conn.secretLookupByUUIDString(secretUuid);
            secret.undefine();
        } catch (LibvirtException ex) {
            if (ex.getMessage().contains("Secret not found")) {
                LOGGER.debug(String.format("Secret uuid %s doesn't exist", secretUuid));
                return;
            }
            throw ex;
        }
        LOGGER.debug(String.format("Undefined secret %s", secretUuid));
    }

    public void cleanOldSecretsByDiskDef(Connect conn, List<DiskDef> disks) throws LibvirtException {
        for (DiskDef disk : disks) {
            DiskDef.LibvirtDiskEncryptDetails encryptDetails = disk.getLibvirtDiskEncryptDetails();
            if (encryptDetails != null) {
                removeLibvirtVolumeSecret(conn, encryptDetails.getPassphraseUuid());
            }
        }
    }

    /**
     * Retrieves the temporary path of the snapshot.
     * @param diskPath Path of the disk to snapshot;
     * @param snapshotName Snapshot name;
     * @return the path of the disk replacing the disk with the snapshot.
     */
    public String getSnapshotTemporaryPath(String diskPath, String snapshotName) {
        String[] diskPathSplitted = diskPath.split(File.separator);
        diskPathSplitted[diskPathSplitted.length - 1] = snapshotName;
        return String.join(File.separator, diskPathSplitted);
    }

    public static String generateSecretUUIDFromString(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes()).toString();
    }

    /**
     * Merges the snapshot into base file.
     *
     * @param vm           Domain of the VM;
     * @param diskLabel    Disk label to manage snapshot and base file;
     * @param baseFilePath Path of the base file;
     * @param topFilePath Path of the top file, if null, the active image is used;
     * @param active Whether the snapshot being merged is the active image;
     * @param snapshotName Name of the snapshot;
     * @param volume VolumeObjectTO of the corresponding volume;
     * @param conn Libvirt connection;
     * @throws LibvirtException
     */
    public void mergeSnapshotIntoBaseFile(Domain vm, String diskLabel, String baseFilePath, String topFilePath, boolean active, String snapshotName, VolumeObjectTO volume,
            Connect conn) throws LibvirtException {
        if (AgentPropertiesFileHandler.getPropertyValue(AgentProperties.LIBVIRT_EVENTS_ENABLED)) {
            mergeSnapshotIntoBaseFileWithEventsAndConfigurableTimeout(vm, diskLabel, baseFilePath, topFilePath, active, snapshotName, volume, conn);
        } else {
            mergeSnapshotIntoBaseFileWithoutEvents(vm, diskLabel, baseFilePath, topFilePath, active, snapshotName, volume, conn);
        }
    }

    /**
     * This method only works if LIBVIRT_EVENTS_ENABLED is true.
     * */
    protected void mergeSnapshotIntoBaseFileWithEventsAndConfigurableTimeout(Domain vm, String diskLabel, String baseFilePath, String topFilePath, boolean active, String snapshotName, VolumeObjectTO volume,
            Connect conn) throws LibvirtException {
        boolean isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit = LibvirtUtilitiesHelper.isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit(conn);
        String vmName = vm.getName();

        int commitFlags = 0;
        if (isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit) {
            commitFlags |= Domain.BlockCommitFlags.DELETE;
        }
        if (active) {
            commitFlags |= Domain.BlockCommitFlags.ACTIVE;
        }

        Semaphore semaphore = getSemaphoreToWaitForMerge();
        BlockCommitListener blockCommitListener = getBlockCommitListener(semaphore, vmName);
        vm.addBlockJobListener(blockCommitListener);

        logger.info("Starting block commit of snapshot [{}] of VM [{}]. Using parameters: diskLabel [{}]; baseFilePath [{}]; topFilePath [{}]; commitFlags [{}]", snapshotName,
                vmName, diskLabel, baseFilePath, topFilePath, commitFlags);

        vm.blockCommit(diskLabel, baseFilePath, topFilePath, 0, commitFlags);

        Thread checkProgressThread = new Thread(() -> checkBlockCommitProgress(vm, diskLabel, vmName, snapshotName, topFilePath, baseFilePath));
        checkProgressThread.start();

        String errorMessage = String.format("the block commit of top file [%s] into base file [%s] for snapshot [%s] of VM [%s]." +
                " The job will be left running to avoid data corruption, but ACS will return an error and volume [%s] will need to be normalized manually. If the commit" +
                " involved the active image, the pivot will need to be manually done.", topFilePath, baseFilePath, snapshotName, vmName, volume);
        try {
            if (!semaphore.tryAcquire(qcow2DeltaMergeTimeout, TimeUnit.SECONDS)) {
                throw new CloudRuntimeException("Timed out while waiting for " + errorMessage);
            }
        } catch (InterruptedException e) {
            throw new CloudRuntimeException("Interrupted while waiting for " + errorMessage);
        } finally {
            vm.removeBlockJobListener(blockCommitListener);
        }

        String mergeResult = blockCommitListener.getResult();
        try {
            checkProgressThread.join();
        } catch (InterruptedException ex) {
            throw new CloudRuntimeException(String.format("Exception while running wait block commit task of snapshot [%s] and VM [%s].", snapshotName, vmName));
        }

        if (mergeResult != null) {
            String commitError = String.format("Failed %s The failure occurred due to [%s].", errorMessage, mergeResult);
            logger.error(commitError);
            throw new CloudRuntimeException(commitError);
        }

        logger.info("Completed block commit of snapshot [{}] of VM [{}].", snapshotName, vmName);

        manuallyDeleteUnusedSnapshotFile(isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit, topFilePath != null ? topFilePath : getSnapshotTemporaryPath(baseFilePath, snapshotName));
    }

    /**
     * Merges the snapshot into base file to keep volume and VM behavior after stopping - starting.
     * @param vm Domain of the VM;
     * @param diskLabel Disk label to manage snapshot and base file;
     * @param baseFilePath Path of the base file;
     * @param snapshotName Name of the snapshot;
     * @throws LibvirtException
     */
    protected void mergeSnapshotIntoBaseFileWithoutEvents(Domain vm, String diskLabel, String baseFilePath, String topFilePath, boolean active, String snapshotName, VolumeObjectTO volume, Connect conn) throws LibvirtException {
        boolean isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit = LibvirtUtilitiesHelper.isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit(conn);
        String vmName = vm.getName();
        String mergeCommand = buildMergeCommand(vmName, diskLabel, baseFilePath, topFilePath, active, isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit);
        String mergeResult = Script.runSimpleBashScript(mergeCommand);

        if (mergeResult == null) {
            logger.debug("Successfully merged snapshot [{}] into VM [{}] {} base file.", snapshotName, vmName, volume);
            manuallyDeleteUnusedSnapshotFile(isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit, getSnapshotTemporaryPath(baseFilePath, snapshotName));
            return;
        }

        String errorMsg = String.format("Failed to merge snapshot [%s] into VM [%s] %s base file. Command [%s] resulted in [%s]. If the VM is stopped and then started, it"
                + " will start to write in the base file again. All changes made between the snapshot and the VM stop will be in the snapshot. If the VM is stopped, the snapshot must be"
                + " merged into the base file manually.", snapshotName, vmName, volume, mergeCommand, mergeResult);

        logger.warn("%s VM XML: [{}].", errorMsg, vm.getXMLDesc(0));
        throw new CloudRuntimeException(errorMsg);
    }

    protected String buildMergeCommand(String vmName, String diskLabel, String baseFilePath, String topFilePath, boolean active, boolean delete) {
        StringBuilder cmd = new StringBuilder(COMMAND_MERGE_SNAPSHOT);
        if (StringUtils.isNotEmpty(topFilePath)) {
            cmd.append(" --top ");
            cmd.append(topFilePath);
        }
        if (active) {
            cmd.append(" --active --pivot");
        }
        if (delete) {
            cmd.append(" --delete");
        }
        cmd.append(" --wait");
        return String.format(cmd.toString(), vmName, diskLabel, baseFilePath);
    }

    /**
     * This was created to facilitate testing.
     * */
    protected BlockCommitListener getBlockCommitListener(Semaphore semaphore, String vmName) {
        return new BlockCommitListener(semaphore, vmName, ThreadContext.get("logcontextid"));
    }

    /**
     * This was created to facilitate testing.
     * */
    protected Semaphore getSemaphoreToWaitForMerge() {
        return new Semaphore(0);
    }

    protected void checkBlockCommitProgress(Domain vm, String diskLabel, String vmName, String snapshotName, String topFilePath, String baseFilePath) {
        int timeout = qcow2DeltaMergeTimeout;
        DomainBlockJobInfo result;
        long lastCommittedBytes = 0;
        long endBytes = 0;
        String partialLog = String.format("of top file [%s] into base file [%s] for snapshot [%s] of VM [%s]", topFilePath, baseFilePath, snapshotName, vmName);

        while (timeout > 0) {
            timeout -= 1;

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                logger.debug("Thread that was tracking the progress {} was interrupted.", partialLog, ex);
                return;
            }

            try {
                result = vm.getBlockJobInfo(diskLabel, 0);
            } catch (LibvirtException ex) {
                logger.warn("Exception while getting block job info {}: [{}].", partialLog, ex.getMessage(), ex);
                return;
            }

            if (result == null || result.type == 0 && result.end == 0 && result.cur == 0) {
                logger.debug("Block commit job {} has already finished.", partialLog);
                return;
            }

            long currentCommittedBytes = result.cur;
            if (currentCommittedBytes > lastCommittedBytes) {
                logger.debug("The block commit {} is at [{}] of [{}].", partialLog, currentCommittedBytes, result.end);
            }
            lastCommittedBytes = currentCommittedBytes;
            endBytes = result.end;
        }
        logger.warn("Block commit {} has timed out after waiting at least {} seconds. The progress of the operation was [{}] of [{}].", partialLog, qcow2DeltaMergeTimeout, lastCommittedBytes, endBytes);
    }

    /**
     * Manually deletes the unused snapshot file.<br/>
     * This method is necessary due to Libvirt created the tag '--delete' on command 'virsh blockcommit' on version <b>1.2.9</b>, however it was only implemented on version
     *  <b>6.0.0</b>.
     * @param snapshotPath The unused snapshot file to manually delete.
     */
    protected void manuallyDeleteUnusedSnapshotFile(boolean isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit, String snapshotPath) {
        if (isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit) {
            logger.debug("The current Libvirt's version supports the flag '--delete' on command 'virsh blockcommit', we will skip the manually deletion of the"
                    + " unused snapshot file [{}] as it already was automatically deleted.", snapshotPath);
            return;
        }

        logger.debug("The current Libvirt's version does not supports the flag '--delete' on command 'virsh blockcommit', therefore we will manually delete the"
                + " unused snapshot file [{}].", snapshotPath);

        deleteIfExists(snapshotPath);
    }

    protected void deleteIfExists(String snapshotPath) {
        try {
            Files.deleteIfExists(Paths.get(snapshotPath));
            logger.debug("Manually deleted unused snapshot file [{}].", snapshotPath);
        } catch (IOException ex) {
            throw new CloudRuntimeException(String.format("Unable to manually delete unused snapshot file [%s] due to [%s].", snapshotPath, ex.getMessage()));
        }
    }

    public void setInterfaceDefQueueSettings(Map<String, String> details, Integer cpus, InterfaceDef interfaceDef) {
        String nicMultiqueueNumber = details.get(VmDetailConstants.NIC_MULTIQUEUE_NUMBER);
        if (nicMultiqueueNumber != null) {
            try {
                Integer nicMultiqueueNumberInteger = Integer.valueOf(nicMultiqueueNumber);
                if (nicMultiqueueNumberInteger == InterfaceDef.MULTI_QUEUE_NUMBER_MEANS_CPU_CORES) {
                    if (cpus != null) {
                        interfaceDef.setMultiQueueNumber(cpus);
                    }
                } else {
                    interfaceDef.setMultiQueueNumber(nicMultiqueueNumberInteger);
                }
            } catch (NumberFormatException ex) {
                LOGGER.warn(String.format("VM details %s is not a valid integer value %s", VmDetailConstants.NIC_MULTIQUEUE_NUMBER, nicMultiqueueNumber));
            }
        }
        String nicPackedEnabled = details.get(VmDetailConstants.NIC_PACKED_VIRTQUEUES_ENABLED);
        if (nicPackedEnabled != null) {
            try {
                interfaceDef.setPackedVirtQueues(Boolean.valueOf(nicPackedEnabled));
            } catch (NumberFormatException ex) {
                LOGGER.warn(String.format("VM details %s is not a valid Boolean value %s", VmDetailConstants.NIC_PACKED_VIRTQUEUES_ENABLED, nicPackedEnabled));
            }
        }
    }

    /*
    Scp volume from remote host to local directory
     */
    public String copyVolume(String srcIp, String username, String password, String localDir, String remoteFile, String tmpPath, int timeoutInSecs) {
        String outputFile = UUID.randomUUID().toString();
        try {
            StringBuilder command = new StringBuilder("qemu-img convert -O qcow2 ");
            command.append(remoteFile);
            command.append(" " + tmpPath);
            command.append(outputFile);
            LOGGER.debug(String.format("Converting remote disk file: %s, output file: %s%s (timeout: %d secs)", remoteFile, tmpPath, outputFile, timeoutInSecs));
            SshHelper.sshExecute(srcIp, 22, username, null, password, command.toString(), timeoutInSecs * 1000);
            LOGGER.debug("Copying converted remote disk file " + outputFile + " to: " + localDir);
            SshHelper.scpFrom(srcIp, 22, username, null, password, localDir, tmpPath + outputFile);
            LOGGER.debug("Successfully copied converted remote disk file to: " + localDir + "/" + outputFile);
            return outputFile;
        } catch (Exception e) {
            try {
                String deleteRemoteConvertedFileCmd = String.format("rm -f %s%s", tmpPath, outputFile);
                SshHelper.sshExecute(srcIp, 22, username, null, password, deleteRemoteConvertedFileCmd);
            } catch (Exception ignored) {
            }

            try {
                FileUtils.deleteQuietly(new File(localDir + "/" + outputFile));
            } catch (Exception ignored) {
            }

            throw new RuntimeException(e);
        }
    }

    @Override
    public void disconnected() {
        LOGGER.info("Detected agent disconnect event, running through " + _disconnectHooks.size() + " disconnect hooks");
        for (DisconnectHook hook : _disconnectHooks) {
            hook.start();
        }
        long start = System.currentTimeMillis();
        for (DisconnectHook hook : _disconnectHooks) {
            try {
                long elapsed = System.currentTimeMillis() - start;
                long remaining = hook.getTimeoutMs() - elapsed;
                long joinWait = remaining > 0 ? remaining : 1;
                hook.join(joinWait);
                hook.interrupt();
            } catch (InterruptedException ex) {
                LOGGER.warn("Interrupted disconnect hook: " + ex.getMessage());
            }
        }
        _disconnectHooks.clear();
    }

    public void addDisconnectHook(DisconnectHook hook) {
        LOGGER.debug("Adding disconnect hook " + hook);
        _disconnectHooks.add(hook);
    }

    public void removeDisconnectHook(DisconnectHook hook) {
        LOGGER.debug("Removing disconnect hook " + hook);
        if (_disconnectHooks.contains(hook)) {
            LOGGER.debug("Removing disconnect hook " + hook);
            _disconnectHooks.remove(hook);
        } else {
            LOGGER.debug("Requested removal of disconnect hook, but hook not found: " + hook);
        }
    }

    public VolumeOnStorageTO getVolumeOnStorage(PrimaryDataStoreTO primaryStore, String volumePath) {
        try {
            if (primaryStore.isManaged()) {
                if (!storagePoolManager.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volumePath, primaryStore.getDetails())) {
                    logger.warn(String.format("Failed to connect src volume %s, in storage pool %s", volumePath, primaryStore));
                }
            }
            final KVMPhysicalDisk srcVolume = storagePoolManager.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volumePath);
            if (srcVolume == null) {
                logger.debug("Failed to get physical disk for volume: " + volumePath);
                throw new CloudRuntimeException("Failed to get physical disk for volume at path: " + volumePath);
            }
            return new VolumeOnStorageTO(HypervisorType.KVM, srcVolume.getName(), srcVolume.getName(), srcVolume.getPath(),
                    srcVolume.getFormat().toString(), srcVolume.getSize(), srcVolume.getVirtualSize());
        } catch (final CloudRuntimeException e) {
            logger.debug(String.format("Failed to get volume %s on storage %s: %s", volumePath, primaryStore, e));
            return new VolumeOnStorageTO();
        } finally {
            if (primaryStore.isManaged()) {
                storagePoolManager.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volumePath);
            }
        }
    }

    public String getHypervisorPath() {
        return hypervisorPath;
    }
    public String getGuestCpuArch() {
        return guestCpuArch;
    }
}
