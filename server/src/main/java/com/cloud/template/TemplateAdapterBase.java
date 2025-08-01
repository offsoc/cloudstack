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
package com.cloud.template;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.iso.DeleteIsoCmd;
import org.apache.cloudstack.api.command.user.iso.GetUploadParamsForIsoCmd;
import org.apache.cloudstack.api.command.user.iso.RegisterIsoCmd;
import org.apache.cloudstack.api.command.user.template.DeleteTemplateCmd;
import org.apache.cloudstack.api.command.user.template.ExtractTemplateCmd;
import org.apache.cloudstack.api.command.user.template.GetUploadParamsForTemplateCmd;
import org.apache.cloudstack.api.command.user.template.RegisterTemplateCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.secstorage.heuristics.HeuristicType;
import org.apache.cloudstack.storage.command.TemplateOrVolumePostUploadCommand;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.heuristics.HeuristicRuleHelper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.api.ApiDBUtils;
import com.cloud.configuration.Config;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.cpu.CPU;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deployasis.DeployAsIsConstants;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Grouping;
import com.cloud.projects.ProjectManager;
import com.cloud.server.ConfigurationServer;
import com.cloud.server.StatsCollector;
import com.cloud.storage.GuestOS;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.TemplateType;
import com.cloud.storage.TemplateProfile;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.GuestOSHypervisorDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.upload.params.IsoUploadParams;
import com.cloud.storage.upload.params.TemplateUploadParams;
import com.cloud.storage.upload.params.UploadParams;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.EnumUtils;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.UserVmDao;

public abstract class TemplateAdapterBase extends AdapterBase implements TemplateAdapter {
    protected @Inject
    DomainDao _domainDao;
    protected @Inject
    AccountDao _accountDao;
    protected @Inject
    ConfigurationDao _configDao;
    protected @Inject
    UserDao _userDao;
    protected @Inject
    AccountManager _accountMgr;
    protected @Inject
    DataCenterDao _dcDao;
    protected @Inject
    VMTemplateDao _tmpltDao;
    protected @Inject
    TemplateDataStoreDao _tmpltStoreDao;
    protected @Inject
    VMTemplateZoneDao _tmpltZoneDao;
    protected @Inject
    UsageEventDao _usageEventDao;
    protected @Inject
    HostDao _hostDao;
    protected @Inject
    UserVmDao _userVmDao;
    protected @Inject
    GuestOSHypervisorDao _osHyperDao;
    protected @Inject
    ResourceLimitService _resourceLimitMgr;
    protected @Inject
    ImageStoreDao _imgStoreDao;
    @Inject
    protected TemplateManager templateMgr;
    @Inject
    ConfigurationServer _configServer;
    @Inject
    ProjectManager _projectMgr;
    @Inject
    private TemplateDataStoreDao templateDataStoreDao;
    @Inject
    DataStoreManager storeMgr;
    @Inject
    HeuristicRuleHelper heuristicRuleHelper;
    @Inject
    TemplateDataFactory imageFactory;
    @Inject
    EndPointSelector _epSelector;
    @Inject
    StatsCollector _statsCollector;

    protected List<DataStore> getImageStoresThrowsExceptionIfNotFound(long zoneId, TemplateProfile profile) {
        List<DataStore> imageStores = storeMgr.getImageStoresByZoneIds(zoneId);
        if (CollectionUtils.isEmpty(imageStores)) {
            throw new CloudRuntimeException(String.format("Unable to find image store to download the template [%s].", profile.getTemplate()));
        }
        return imageStores;
    }

    protected DataStore verifyHeuristicRulesForZone(VMTemplateVO template, Long zoneId) {
        HeuristicType heuristicType;
        if (ImageFormat.ISO.equals(template.getFormat())) {
            heuristicType = HeuristicType.ISO;
        } else {
            heuristicType = HeuristicType.TEMPLATE;
        }
        return heuristicRuleHelper.getImageStoreIfThereIsHeuristicRule(zoneId, heuristicType, template);
    }

    protected boolean isZoneAndImageStoreAvailable(DataStore imageStore, Long zoneId, Set<Long> zoneSet, boolean isTemplatePrivate) {
        if (zoneId == null) {
            logger.warn(String.format("Zone ID is null, cannot allocate ISO/template in image store [%s].", imageStore));
            return false;
        }

        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone == null) {
            logger.warn("Unable to find zone by id [{}], so skip downloading template to its image store [{}].", zoneId, imageStore);
            return false;
        }

        if (Grouping.AllocationState.Disabled == zone.getAllocationState()) {
            logger.info("Zone [{}] is disabled. Skip downloading template to its image store [{}].", zone, imageStore);
            return false;
        }

        if (!_statsCollector.imageStoreHasEnoughCapacity(imageStore)) {
            logger.info("Image store doesn't have enough capacity. Skip downloading template to this image store [{}].", imageStore);
            return false;
        }

        if (zoneSet == null) {
            logger.info(String.format("Zone set is null; therefore, the ISO/template should be allocated in every secondary storage of zone [%s].", zone));
            return true;
        }

        if (isTemplatePrivate && zoneSet.contains(zoneId)) {
            logger.info(String.format("The template is private and it is already allocated in a secondary storage in zone [%s]; therefore, image store [%s] will be skipped.",
                    zone, imageStore));
            return false;
        }

        logger.info(String.format("Private template will be allocated in image store [%s] in zone [%s].", imageStore, zone));
        zoneSet.add(zoneId);
        return true;
    }

    /**
     * If the template/ISO is marked as private, then it is allocated to a random secondary storage; otherwise, allocates to every storage pool in every zone given by the
     * {@link TemplateProfile#getZoneIdList()}.
     */
    protected void postUploadAllocation(List<DataStore> imageStores, VMTemplateVO template, List<TemplateOrVolumePostUploadCommand> payloads) {
        Set<Long> zoneSet = new HashSet<>();
        Collections.shuffle(imageStores);
        for (DataStore imageStore : imageStores) {
            Long zoneId_is = imageStore.getScope().getScopeId();

            if (!isZoneAndImageStoreAvailable(imageStore, zoneId_is, zoneSet, isPrivateTemplate(template))) {
                continue;
            }

            TemplateInfo tmpl = imageFactory.getTemplate(template.getId(), imageStore);

            // persist template_store_ref entry
            DataObject templateOnStore = imageStore.create(tmpl);

            // update template_store_ref and template state
            EndPoint ep = _epSelector.select(templateOnStore);
            if (ep == null) {
                String errMsg = String.format("There is no secondary storage VM for downloading template to image store %s", imageStore);
                logger.warn(errMsg);
                throw new CloudRuntimeException(errMsg);
            }

            TemplateOrVolumePostUploadCommand payload = new TemplateOrVolumePostUploadCommand(template.getId(), template.getUuid(), tmpl.getInstallPath(), tmpl
                    .getChecksum(), tmpl.getType().toString(), template.getUniqueName(), template.getFormat().toString(), templateOnStore.getDataStore().getUri(),
                    templateOnStore.getDataStore().getRole().toString());
            //using the existing max template size configuration
            payload.setMaxUploadSize(_configDao.getValue(Config.MaxTemplateAndIsoSize.key()));

            Long accountId = template.getAccountId();
            Account account = _accountDao.findById(accountId);
            Domain domain = _domainDao.findById(account.getDomainId());

            payload.setDefaultMaxSecondaryStorageInGB(_resourceLimitMgr.findCorrectResourceLimitForAccountAndDomain(account, domain, ResourceType.secondary_storage, null));
            payload.setAccountId(accountId);
            payload.setRemoteEndPoint(ep.getPublicAddr());
            payload.setRequiresHvm(template.requiresHvm());
            payload.setDescription(template.getDisplayText());
            payloads.add(payload);
        }
    }

    protected boolean isPrivateTemplate(VMTemplateVO template){
        // if public OR featured OR system template
        if (template.isPublicTemplate() || template.isFeatured() || template.getTemplateType() == TemplateType.SYSTEM) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public TemplateProfile prepare(boolean isIso, Long userId, String name, String displayText, CPU.CPUArch arch, Integer bits, Boolean passwordEnabled, Boolean requiresHVM, String url,
                                   Boolean isPublic, Boolean featured, Boolean isExtractable, String format, Long guestOSId, List<Long> zoneId, HypervisorType hypervisorType, String accountName,
                                   Long domainId, String chksum, Boolean bootable, Map details, boolean directDownload, boolean deployAsIs, Long extensionId) throws ResourceAllocationException {
        return prepare(isIso, userId, name, displayText, arch, bits, passwordEnabled, requiresHVM, url, isPublic, featured, isExtractable, format, guestOSId, zoneId,
            hypervisorType, chksum, bootable, null, null, details, false, null, false, TemplateType.USER, directDownload, deployAsIs, false, extensionId);
    }

    @Override
    public TemplateProfile prepare(boolean isIso, long userId, String name, String displayText, CPU.CPUArch arch, Integer bits, Boolean passwordEnabled, Boolean requiresHVM, String url,
                                   Boolean isPublic, Boolean featured, Boolean isExtractable, String format, Long guestOSId, List<Long> zoneIdList, HypervisorType hypervisorType, String chksum,
                                   Boolean bootable, String templateTag, Account templateOwner, Map details, Boolean sshkeyEnabled, String imageStoreUuid, Boolean isDynamicallyScalable,
                                   TemplateType templateType, boolean directDownload, boolean deployAsIs, boolean forCks, Long extensionId) throws ResourceAllocationException {
        //Long accountId = null;
        // parameters verification

        if (isPublic == null) {
            isPublic = Boolean.FALSE;
        }

        if (isIso) {
            if (bootable == null) {
                bootable = Boolean.TRUE;
            }
            GuestOS noneGuestOs = ApiDBUtils.findGuestOSByDisplayName(ApiConstants.ISO_GUEST_OS_NONE);
            if ((guestOSId == null || guestOSId == noneGuestOs.getId()) && bootable == true) {
                throw new InvalidParameterValueException("Please pass a valid GuestOS Id");
            }
            if (bootable == false) {
                guestOSId = noneGuestOs.getId(); //Guest os id of None.
            }
        } else {
            if (bits == null) {
                bits = Integer.valueOf(64);
            }
            if (passwordEnabled == null) {
                passwordEnabled = false;
            }
            if (requiresHVM == null) {
                requiresHVM = true;
            }
            if (deployAsIs) {
                logger.info("Setting default guest OS for deploy-as-is template while the template registration is not completed");
                guestOSId = getDefaultDeployAsIsGuestOsId();
            }
        }

        if (isExtractable == null) {
            isExtractable = Boolean.FALSE;
        }
        if (sshkeyEnabled == null) {
            sshkeyEnabled = Boolean.FALSE;
        }

        boolean isAdmin = _accountMgr.isRootAdmin(templateOwner.getId());
        boolean isRegionStore = false;
        List<ImageStoreVO> stores = _imgStoreDao.findRegionImageStores();
        if (stores != null && stores.size() > 0) {
            isRegionStore = true;
        }

        if (!isAdmin && zoneIdList == null && !isRegionStore ) {
            // domain admin and user should also be able to register template on a region store
            throw new InvalidParameterValueException("Template registered for 'All zones' can only be owned a Root Admin account. " +
                    "Please select specific zone(s).");
        }

        // check for the url format only when url is not null. url can be null incase of form based upload
        if (url != null && url.toLowerCase().contains("file://")) {
            throw new InvalidParameterValueException("File:// type urls are currently unsupported");
        }

        // check whether owner can create public templates
        boolean allowPublicUserTemplates = TemplateManager.AllowPublicUserTemplates.valueIn(templateOwner.getId());
        if (!isAdmin && !allowPublicUserTemplates && isPublic) {
            throw new InvalidParameterValueException("Only private templates/ISO can be created.");
        }

        if (!isAdmin || featured == null) {
            featured = Boolean.FALSE;
        }

        ImageFormat imgfmt;
        try {
            imgfmt = ImageFormat.valueOf(format.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.debug("ImageFormat IllegalArgumentException: " + e.getMessage());
            throw new IllegalArgumentException("Image format: " + format + " is incorrect. Supported formats are " + EnumUtils.listValues(ImageFormat.values()));
        }

        // Check that the resource limit for templates/ISOs won't be exceeded
        UserVO user = _userDao.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("Unable to find user with id " + userId);
        }

        _resourceLimitMgr.checkResourceLimit(templateOwner, ResourceType.template);

        // If a zoneId is specified, make sure it is valid
        if (zoneIdList != null) {
            for (Long zoneId :zoneIdList) {
                DataCenterVO zone = _dcDao.findById(zoneId);
                if (zone == null) {
                    throw new IllegalArgumentException("Please specify a valid zone.");
                }
                Account caller = CallContext.current().getCallingAccount();
                if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getId())) {
                    throw new PermissionDeniedException(String.format("Cannot perform this operation, Zone %s is currently disabled", zone));
                }
            }
        }

        List<VMTemplateVO> systemvmTmplts = _tmpltDao.listAllSystemVMTemplates();
        for (VMTemplateVO template : systemvmTmplts) {
            if ((template.getName().equalsIgnoreCase(name) ||
                    template.getDisplayText().equalsIgnoreCase(displayText)) &&
                    Objects.equals(template.getArch(), arch)) {
                logger.error("{} for same arch {} is having same name or description", template,
                        template.getArch());
                throw new IllegalArgumentException("Cannot use reserved names for templates");
            }
        }

        if (hypervisorType.equals(Hypervisor.HypervisorType.XenServer)) {
            if (details == null || !details.containsKey("hypervisortoolsversion") || details.get("hypervisortoolsversion") == null ||
                ((String)details.get("hypervisortoolsversion")).equalsIgnoreCase("none")) {
                String hpvs = _configDao.getValue(Config.XenServerPVdriverVersion.key());
                if (hpvs != null) {
                    if (details == null) {
                        details = new HashMap<String, String>();
                    }
                    details.put("hypervisortoolsversion", hpvs);
                }
            }
        }

        Long id = _tmpltDao.getNextInSequence(Long.class, "id");
        CallContext.current().setEventDetails("Id: " + id + " name: " + name);
        TemplateProfile profile = new TemplateProfile(id, userId, name, displayText, arch, bits, passwordEnabled, requiresHVM, url, isPublic, featured, isExtractable, imgfmt, guestOSId, zoneIdList,
            hypervisorType, templateOwner.getAccountName(), templateOwner.getDomainId(), templateOwner.getAccountId(), chksum, bootable, templateTag, details,
            sshkeyEnabled, null, isDynamicallyScalable, templateType, directDownload, deployAsIs, extensionId);
        profile.setForCks(forCks);
        return profile;

    }

    @Override
    public TemplateProfile prepare(RegisterTemplateCmd cmd) throws ResourceAllocationException {
        //check if the caller can operate with the template owner
        Account caller = CallContext.current().getCallingAccount();
        Account owner = _accountMgr.getAccount(cmd.getEntityOwnerId());
        _accountMgr.checkAccess(caller, null, true, owner);

        HypervisorType hypervisorType = HypervisorType.getType(cmd.getHypervisor());
        if(hypervisorType == HypervisorType.None) {
            throw new InvalidParameterValueException(String.format(
                    "Hypervisor Type: %s is invalid. Supported Hypervisor types are: %s",
                    cmd.getHypervisor(),
                    StringUtils.join(Arrays.stream(HypervisorType.values()).filter(h -> h != HypervisorType.None).map(HypervisorType::name).toArray(), ", ")));
        }

        TemplateType templateType = templateMgr.validateTemplateType(cmd, _accountMgr.isAdmin(caller.getAccountId()),
                CollectionUtils.isEmpty(cmd.getZoneIds()), hypervisorType);

        List<Long> zoneId = cmd.getZoneIds();
        // ignore passed zoneId if we are using region wide image store
        List<ImageStoreVO> stores = _imgStoreDao.findRegionImageStores();
        if (stores != null && stores.size() > 0) {
            zoneId = null;
        }

        Map details = cmd.getDetails();
        if (cmd.isDeployAsIs()) {
            if (MapUtils.isNotEmpty(details)) {
                if (details.containsKey(VmDetailConstants.ROOT_DISK_CONTROLLER)) {
                    logger.info("Ignoring the rootDiskController detail provided, as we honour what is defined in the template");
                    details.remove(VmDetailConstants.ROOT_DISK_CONTROLLER);
                }
                if (details.containsKey(VmDetailConstants.NIC_ADAPTER)) {
                    logger.info("Ignoring the nicAdapter detail provided, as we honour what is defined in the template");
                    details.remove(VmDetailConstants.NIC_ADAPTER);
                }
            }
        }

        if (HypervisorType.External.equals(hypervisorType) && MapUtils.isNotEmpty(cmd.getExternalDetails())) {
            if (details != null) {
                details.putAll(cmd.getExternalDetails());
            } else {
                details = cmd.getExternalDetails();
            }
        }

        return prepare(false, CallContext.current().getCallingUserId(), cmd.getTemplateName(), cmd.getDisplayText(), cmd.getArch(), cmd.getBits(), cmd.isPasswordEnabled(), cmd.getRequiresHvm(),
                cmd.getUrl(), cmd.isPublic(), cmd.isFeatured(), cmd.isExtractable(), cmd.getFormat(), cmd.getOsTypeId(), zoneId, hypervisorType, cmd.getChecksum(), true,
                cmd.getTemplateTag(), owner, details, cmd.isSshKeyEnabled(), null, cmd.isDynamicallyScalable(), templateType,
                cmd.isDirectDownload(), cmd.isDeployAsIs(), cmd.isForCks(), cmd.getExtensionId());

    }

    /**
     * Prepare upload parameters internal method for templates and ISOs local upload
     */
    private TemplateProfile prepareUploadParamsInternal(UploadParams params) throws ResourceAllocationException {
        //check if the caller can operate with the template owner
        Account caller = CallContext.current().getCallingAccount();
        Account owner = _accountMgr.getAccount(params.getTemplateOwnerId());
        _accountMgr.checkAccess(caller, null, true, owner);

        List<Long> zoneList = null;
        // ignore passed zoneId if we are using region wide image store
        List<ImageStoreVO> stores = _imgStoreDao.findRegionImageStores();
        if (!(stores != null && stores.size() > 0)) {
            zoneList = new ArrayList<>();
            zoneList.add(params.getZoneId());
        }

        if(!params.isIso() && params.getHypervisorType() == HypervisorType.None) {
            throw new InvalidParameterValueException(String.format(
                    "Hypervisor Type: %s is invalid. Supported Hypervisor types are: %s",
                    params.getHypervisorType(),
                    StringUtils.join(Arrays.stream(HypervisorType.values()).filter(h -> h != HypervisorType.None).map(HypervisorType::name).toArray(), ", ")));
        }

        return prepare(params.isIso(), params.getUserId(), params.getName(), params.getDisplayText(), params.getArch(), params.getBits(),
                params.isPasswordEnabled(), params.requiresHVM(), params.getUrl(), params.isPublic(), params.isFeatured(),
                params.isExtractable(), params.getFormat(), params.getGuestOSId(), zoneList,
                params.getHypervisorType(), params.getChecksum(), params.isBootable(), params.getTemplateTag(), owner,
                params.getDetails(), params.isSshKeyEnabled(), params.getImageStoreUuid(),
                params.isDynamicallyScalable(), params.isRoutingType() ? TemplateType.ROUTING : TemplateType.USER, params.isDirectDownload(), params.isDeployAsIs(), false, null);
    }

    private Long getDefaultDeployAsIsGuestOsId() {
        GuestOS deployAsIsGuestOs = ApiDBUtils.findGuestOSByDisplayName(DeployAsIsConstants.DEFAULT_GUEST_OS_DEPLOY_AS_IS);
        return deployAsIsGuestOs.getId();
    }

    @Override
    public TemplateProfile prepare(GetUploadParamsForTemplateCmd cmd) throws ResourceAllocationException {
        Long osTypeId = cmd.getOsTypeId();
        if (osTypeId == null) {
            logger.info("Setting the default guest OS for deploy-as-is templates while the template upload is not completed");
            osTypeId = getDefaultDeployAsIsGuestOsId();
        }
        UploadParams params = new TemplateUploadParams(CallContext.current().getCallingUserId(), cmd.getName(),
                cmd.getDisplayText(), cmd.getArch(), cmd.getBits(), BooleanUtils.toBoolean(cmd.isPasswordEnabled()),
                BooleanUtils.toBoolean(cmd.getRequiresHvm()), BooleanUtils.toBoolean(cmd.isPublic()),
                BooleanUtils.toBoolean(cmd.isFeatured()), BooleanUtils.toBoolean(cmd.isExtractable()), cmd.getFormat(), osTypeId,
                cmd.getZoneId(), HypervisorType.getType(cmd.getHypervisor()), cmd.getChecksum(),
                cmd.getTemplateTag(), cmd.getEntityOwnerId(), cmd.getDetails(), BooleanUtils.toBoolean(cmd.isSshKeyEnabled()),
                BooleanUtils.toBoolean(cmd.isDynamicallyScalable()), BooleanUtils.toBoolean(cmd.isRoutingType()), cmd.isDeployAsIs(), cmd.isForCks());
        return prepareUploadParamsInternal(params);
    }

    @Override
    public TemplateProfile prepare(GetUploadParamsForIsoCmd cmd) throws ResourceAllocationException {
        UploadParams params = new IsoUploadParams(CallContext.current().getCallingUserId(), cmd.getName(),
                cmd.getDisplayText(), BooleanUtils.toBoolean(cmd.isPublic()), BooleanUtils.toBoolean(cmd.isFeatured()),
                BooleanUtils.toBoolean(cmd.isExtractable()), cmd.getOsTypeId(),
                cmd.getZoneId(), BooleanUtils.toBoolean(cmd.isBootable()), cmd.getEntityOwnerId());
        return prepareUploadParamsInternal(params);
    }

    @Override
    public TemplateProfile prepare(RegisterIsoCmd cmd) throws ResourceAllocationException {
        //check if the caller can operate with the template owner
        Account caller = CallContext.current().getCallingAccount();
        Account owner = _accountMgr.getAccount(cmd.getEntityOwnerId());
        _accountMgr.checkAccess(caller, null, true, owner);

        List<Long> zoneList = null;
        Long zoneId = cmd.getZoneId();
        // ignore passed zoneId if we are using region wide image store
        List<ImageStoreVO> stores = _imgStoreDao.findRegionImageStores();
        if (CollectionUtils.isEmpty(stores) && zoneId != null && zoneId > 0L) {
            zoneList = new ArrayList<>();
            zoneList.add(zoneId);
        }

        return prepare(true, CallContext.current().getCallingUserId(), cmd.getIsoName(), cmd.getDisplayText(), cmd.getArch(), 64, cmd.isPasswordEnabled(), true, cmd.getUrl(), cmd.isPublic(),
            cmd.isFeatured(), cmd.isExtractable(), ImageFormat.ISO.toString(), cmd.getOsTypeId(), zoneList, HypervisorType.None, cmd.getChecksum(), cmd.isBootable(), null,
            owner, null, false, cmd.getImageStoreUuid(), cmd.isDynamicallyScalable(), TemplateType.USER, cmd.isDirectDownload(), false, false, null);
    }

    protected VMTemplateVO persistTemplate(TemplateProfile profile, VirtualMachineTemplate.State initialState) {
        List<Long> zoneIdList = profile.getZoneIdList();
        VMTemplateVO template =
            new VMTemplateVO(profile.getTemplateId(), profile.getName(), profile.getFormat(), profile.isPublic(), profile.isFeatured(), profile.isExtractable(),
                profile.getTemplateType(), profile.getUrl(), profile.isRequiresHVM(), profile.getBits(), profile.getAccountId(), profile.getCheckSum(),
                profile.getDisplayText(), profile.isPasswordEnabled(), profile.getGuestOsId(), profile.isBootable(), profile.getHypervisorType(),
                profile.getTemplateTag(), profile.getDetails(), profile.isSshKeyEnabled(), profile.IsDynamicallyScalable(), profile.isDirectDownload(), profile.isDeployAsIs(), profile.getArch(), profile.getExtensionId());
        template.setState(initialState);
        template.setForCks(profile.isForCks());

        if (profile.isDirectDownload()) {
            template.setSize(profile.getSize());
        }

        if (zoneIdList == null) {
            List<DataCenterVO> dcs = _dcDao.listAll();

            if (dcs.isEmpty()) {
                throw new CloudRuntimeException("No zones are present in the system, can't add template");
            }

            template.setCrossZones(true);
            for (DataCenterVO dc : dcs) {
                _tmpltDao.addTemplateToZone(template, dc.getId());
            }

        } else {
            for (Long zoneId: zoneIdList) {
                _tmpltDao.addTemplateToZone(template, zoneId);
            }
        }
        return _tmpltDao.findById(template.getId());
    }

    private Long accountAndUserValidation(Account account, long userId, UserVmVO vmInstanceCheck, VMTemplateVO template, String msg) throws PermissionDeniedException {

        if (account != null) {
            if (!_accountMgr.isAdmin(account.getId())) {
                if ((vmInstanceCheck != null) && (account.getId() != vmInstanceCheck.getAccountId())) {
                    throw new PermissionDeniedException(msg + ". Permission denied.");
                }

                if ((template != null) &&
                    (!template.isPublicTemplate() && (account.getId() != template.getAccountId()) && (template.getTemplateType() != TemplateType.PERHOST))) {
                    //special handling for the project case
                    Account owner = _accountMgr.getAccount(template.getAccountId());
                    if (owner.getType() == Account.Type.PROJECT) {
                        if (!_projectMgr.canAccessProjectAccount(account, owner.getId())) {
                            throw new PermissionDeniedException(msg + ". Permission denied. The caller can't access project's template");
                        }
                    } else {
                        throw new PermissionDeniedException(msg + ". Permission denied.");
                    }
                }
            } else {
                if ((vmInstanceCheck != null) && !_domainDao.isChildDomain(account.getDomainId(), vmInstanceCheck.getDomainId())) {
                    throw new PermissionDeniedException(msg + ". Permission denied.");
                }
                // FIXME: if template/ISO owner is null we probably need to
                // throw some kind of exception

                if (template != null) {
                    Account templateOwner = _accountDao.findById(template.getAccountId());
                    if ((templateOwner != null) && !_domainDao.isChildDomain(account.getDomainId(), templateOwner.getDomainId())) {
                        throw new PermissionDeniedException(msg + ". Permission denied.");
                    }
                }
            }
        }

        return userId;
    }

    @Override
    public TemplateProfile prepareDelete(DeleteTemplateCmd cmd) {
        Long templateId = cmd.getId();
        Long userId = CallContext.current().getCallingUserId();
        Account account = CallContext.current().getCallingAccount();
        Long zoneId = cmd.getZoneId();

        VMTemplateVO template = _tmpltDao.findById(templateId);
        if (template == null) {
            throw new InvalidParameterValueException("unable to find template with id " + templateId);
        }

        userId = accountAndUserValidation(account, userId, null, template, "Unable to delete template ");

        UserVO user = _userDao.findById(userId);
        if (user == null) {
            throw new InvalidParameterValueException("Please specify a valid user.");
        }

        if (template.getFormat() == ImageFormat.ISO) {
            throw new InvalidParameterValueException("Please specify a valid template.");
        }

        if (template.getState() == VirtualMachineTemplate.State.NotUploaded || template.getState() == VirtualMachineTemplate.State.UploadInProgress) {
            throw new InvalidParameterValueException("The template is either getting uploaded or it may be initiated shortly, please wait for it to be completed");
        }

        return new TemplateProfile(userId, template, zoneId);
    }

    @Override
    public TemplateProfile prepareExtractTemplate(ExtractTemplateCmd cmd) {
        Long templateId = cmd.getId();
        Long userId = CallContext.current().getCallingUserId();
        Long zoneId = cmd.getZoneId();

        VMTemplateVO template = _tmpltDao.findById(templateId);
        if (template == null) {
            throw new InvalidParameterValueException("unable to find template with id " + templateId);
        }
        return new TemplateProfile(userId, template, zoneId);
    }

    @Override
    public TemplateProfile prepareDelete(DeleteIsoCmd cmd) {
        Long templateId = cmd.getId();
        Long userId = CallContext.current().getCallingUserId();
        Account account = CallContext.current().getCallingAccount();
        Long zoneId = cmd.getZoneId();

        VMTemplateVO template = _tmpltDao.findById(templateId);
        if (template == null) {
            throw new InvalidParameterValueException("unable to find iso with id " + templateId);
        }

        userId = accountAndUserValidation(account, userId, null, template, "Unable to delete iso ");

        UserVO user = _userDao.findById(userId);
        if (user == null) {
            throw new InvalidParameterValueException("Please specify a valid user.");
        }

        if (template.getFormat() != ImageFormat.ISO) {
            throw new InvalidParameterValueException("Please specify a valid iso.");
        }

        if (template.getState() == VirtualMachineTemplate.State.NotUploaded || template.getState() == VirtualMachineTemplate.State.UploadInProgress) {
            throw new InvalidParameterValueException("The iso is either getting uploaded or it may be initiated shortly, please wait for it to be completed");
        }

        return new TemplateProfile(userId, template, zoneId);
    }

    @Override
    abstract public VMTemplateVO create(TemplateProfile profile);

    @Override
    abstract public boolean delete(TemplateProfile profile);
}
