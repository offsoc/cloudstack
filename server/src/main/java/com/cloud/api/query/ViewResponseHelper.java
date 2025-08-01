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
package com.cloud.api.query;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.affinity.AffinityGroupResponse;
import org.apache.cloudstack.api.ApiConstants.DomainDetails;
import org.apache.cloudstack.api.ApiConstants.HostDetails;
import org.apache.cloudstack.api.ApiConstants.VMDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.AsyncJobResponse;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.DomainRouterResponse;
import org.apache.cloudstack.api.response.EventResponse;
import org.apache.cloudstack.api.response.HostForMigrationResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.HostTagResponse;
import org.apache.cloudstack.api.response.ImageStoreResponse;
import org.apache.cloudstack.api.response.InstanceGroupResponse;
import org.apache.cloudstack.api.response.ObjectStoreResponse;
import org.apache.cloudstack.api.response.ProjectAccountResponse;
import org.apache.cloudstack.api.response.ProjectInvitationResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.SecurityGroupResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.StorageTagResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.UserResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.vo.AccountJoinVO;
import com.cloud.api.query.vo.AffinityGroupJoinVO;
import com.cloud.api.query.vo.AsyncJobJoinVO;
import com.cloud.api.query.vo.DataCenterJoinVO;
import com.cloud.api.query.vo.DiskOfferingJoinVO;
import com.cloud.api.query.vo.DomainJoinVO;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.api.query.vo.EventJoinVO;
import com.cloud.api.query.vo.HostJoinVO;
import com.cloud.api.query.vo.ImageStoreJoinVO;
import com.cloud.api.query.vo.InstanceGroupJoinVO;
import com.cloud.api.query.vo.ProjectAccountJoinVO;
import com.cloud.api.query.vo.ProjectInvitationJoinVO;
import com.cloud.api.query.vo.ProjectJoinVO;
import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.api.query.vo.SecurityGroupJoinVO;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.api.query.vo.SnapshotJoinVO;
import com.cloud.api.query.vo.StoragePoolJoinVO;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.api.query.vo.UserAccountJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.api.query.vo.VolumeJoinVO;
import com.cloud.configuration.Resource;
import com.cloud.domain.Domain;
import com.cloud.host.HostTagVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StoragePoolTagVO;
import com.cloud.storage.VolumeStats;
import com.cloud.user.Account;

/**
 * Helper class to generate response from DB view VO objects.
 *
 */
public class ViewResponseHelper {

    protected Logger logger = LogManager.getLogger(getClass());

    public static List<UserResponse> createUserResponse(UserAccountJoinVO... users) {
        return createUserResponse(ResponseView.Restricted, null, users);
    }

    public static List<UserResponse> createUserResponse(ResponseView responseView, Long domainId, UserAccountJoinVO... users) {
        List<UserResponse> respList = new ArrayList<UserResponse>();
        for (UserAccountJoinVO vt : users) {
            respList.add(ApiDBUtils.newUserResponse(responseView, domainId, vt));
        }
        return respList;
    }

    public static List<EventResponse> createEventResponse(EventJoinVO... events) {
        List<EventResponse> respList = new ArrayList<EventResponse>();
        for (EventJoinVO vt : events) {
            respList.add(ApiDBUtils.newEventResponse(vt));
        }
        return respList;
    }

    public static List<ResourceTagResponse> createResourceTagResponse(boolean keyValueOnly, ResourceTagJoinVO... tags) {
        List<ResourceTagResponse> respList = new ArrayList<ResourceTagResponse>();
        for (ResourceTagJoinVO vt : tags) {
            respList.add(ApiDBUtils.newResourceTagResponse(vt, keyValueOnly));
        }
        return respList;
    }

    public static List<InstanceGroupResponse> createInstanceGroupResponse(InstanceGroupJoinVO... groups) {
        List<InstanceGroupResponse> respList = new ArrayList<InstanceGroupResponse>();
        for (InstanceGroupJoinVO vt : groups) {
            respList.add(ApiDBUtils.newInstanceGroupResponse(vt));
        }
        return respList;
    }

    public static List<UserVmResponse> createUserVmResponse(ResponseView view, String objectName, UserVmJoinVO... userVms) {
        return createUserVmResponse(view, objectName, EnumSet.of(VMDetails.all), null, null, userVms);
    }

    public static List<UserVmResponse> createUserVmResponse(ResponseView view, String objectName, Set<VMDetails> details, UserVmJoinVO... userVms) {
        return createUserVmResponse(view, objectName, details, null, null, userVms);
    }

    public static List<UserVmResponse> createUserVmResponse(ResponseView view, String objectName, Set<VMDetails> details, Boolean accumulateStats, UserVmJoinVO... userVms) {
        return createUserVmResponse(view, objectName, details, accumulateStats, null, userVms);
    }

    public static List<UserVmResponse> createUserVmResponse(ResponseView view, String objectName, Set<VMDetails> details, Boolean accumulateStats, Boolean showUserData,
            UserVmJoinVO... userVms) {
        Account caller = CallContext.current().getCallingAccount();
        LinkedHashMap<Long, UserVmResponse> vmDataList = new LinkedHashMap<>();
        // Initialise the vmdatalist with the input data

        for (UserVmJoinVO userVm : userVms) {
            UserVmResponse userVmData = vmDataList.get(userVm.getId());
            if (userVmData == null) {
                // first time encountering this vm
                userVmData = ApiDBUtils.newUserVmResponse(view, objectName, userVm, details, accumulateStats, showUserData, caller);
            } else{
                // update nics, securitygroups, tags, affinitygroups for 1 to many mapping fields
                userVmData = ApiDBUtils.fillVmDetails(view, userVmData, userVm);
            }
            userVmData.setIpAddress(userVmData.getNics());
            vmDataList.put(userVm.getId(), userVmData);
        }
        return new ArrayList<UserVmResponse>(vmDataList.values());
    }

    public static List<DomainRouterResponse> createDomainRouterResponse(DomainRouterJoinVO... routers) {
        Account caller = CallContext.current().getCallingAccount();
        LinkedHashMap<Long, DomainRouterResponse> vrDataList = new LinkedHashMap<>();
        // Initialise the vrdatalist with the input data
        for (DomainRouterJoinVO vr : routers) {
            DomainRouterResponse vrData = vrDataList.get(vr.getId());
            if (vrData == null) {
                // first time encountering this vm
                vrData = ApiDBUtils.newDomainRouterResponse(vr, caller);
            } else {
                // update nics for 1 to many mapping fields
                vrData = ApiDBUtils.fillRouterDetails(vrData, vr);
            }
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<DomainRouterResponse>(vrDataList.values());
    }

    public static List<SecurityGroupResponse> createSecurityGroupResponses(List<SecurityGroupJoinVO> securityGroups) {
        Account caller = CallContext.current().getCallingAccount();
        LinkedHashMap<Long, SecurityGroupResponse> vrDataList = new LinkedHashMap<>();
        // Initialise the vrdatalist with the input data
        for (SecurityGroupJoinVO vr : securityGroups) {
            SecurityGroupResponse vrData = vrDataList.get(vr.getId());
            if (vrData == null) {
                // first time encountering this sg
                vrData = ApiDBUtils.newSecurityGroupResponse(vr, caller);

            } else {
                // update rules for 1 to many mapping fields
                vrData = ApiDBUtils.fillSecurityGroupDetails(vrData, vr);
            }
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<SecurityGroupResponse>(vrDataList.values());
    }

    public static List<ProjectResponse> createProjectResponse(EnumSet<DomainDetails> details, ProjectJoinVO... projects) {
        LinkedHashMap<Long, ProjectResponse> prjDataList = new LinkedHashMap<>();
        // Initialise the prjdatalist with the input data
        for (ProjectJoinVO p : projects) {
            ProjectResponse pData = prjDataList.get(p.getId());
            if (pData == null) {
                // first time encountering this vm
                pData = ApiDBUtils.newProjectResponse(details, p);
                prjDataList.put(p.getId(), pData);
            }
        }
        return new ArrayList<ProjectResponse>(prjDataList.values());
    }

    public static List<ProjectAccountResponse> createProjectAccountResponse(ProjectAccountJoinVO... projectAccounts) {
        List<ProjectAccountResponse> responseList = new ArrayList<ProjectAccountResponse>();
        for (ProjectAccountJoinVO proj : projectAccounts) {
            ProjectAccountResponse resp = ApiDBUtils.newProjectAccountResponse(proj);
            // update user list
            Account caller = CallContext.current().getCallingAccount();
            if (ApiDBUtils.isAdmin(caller)) {
                List<UserAccountJoinVO> users = null;
                if (proj.getUserUuid() != null) {
                    users = Collections.singletonList(ApiDBUtils.findUserAccountById(proj.getUserId()));
                } else {
                    users = ApiDBUtils.findUserViewByAccountId(proj.getAccountId());
                }
                resp.setUsers(ViewResponseHelper.createUserResponse(users.toArray(new UserAccountJoinVO[users.size()])));
            }
            responseList.add(resp);
        }
        return responseList;
    }

    public static List<ProjectInvitationResponse> createProjectInvitationResponse(ProjectInvitationJoinVO... invites) {
        List<ProjectInvitationResponse> respList = new ArrayList<ProjectInvitationResponse>();
        for (ProjectInvitationJoinVO v : invites) {
            respList.add(ApiDBUtils.newProjectInvitationResponse(v));
        }
        return respList;
    }

    public static List<HostResponse> createHostResponse(EnumSet<HostDetails> details, HostJoinVO... hosts) {
        LinkedHashMap<Long, HostResponse> vrDataList = new LinkedHashMap<>();
        // Initialise the vrdatalist with the input data
        for (HostJoinVO vr : hosts) {
            HostResponse vrData = ApiDBUtils.newHostResponse(vr, details);
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<HostResponse>(vrDataList.values());
    }

    public static List<HostResponse> createMinimalHostResponse(HostJoinVO... hosts) {
        LinkedHashMap<Long, HostResponse> vrDataList = new LinkedHashMap<>();
        for (HostJoinVO vr : hosts) {
            HostResponse vrData = ApiDBUtils.newMinimalHostResponse(vr);
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<HostResponse>(vrDataList.values());
    }

    public static List<HostForMigrationResponse> createHostForMigrationResponse(EnumSet<HostDetails> details, HostJoinVO... hosts) {
        LinkedHashMap<Long, HostForMigrationResponse> vrDataList = new LinkedHashMap<>();
        // Initialise the vrdatalist with the input data
        for (HostJoinVO vr : hosts) {
            HostForMigrationResponse vrData = ApiDBUtils.newHostForMigrationResponse(vr, details);
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<HostForMigrationResponse>(vrDataList.values());
    }

    public static List<VolumeResponse> createVolumeResponse(ResponseView view, VolumeJoinVO... volumes) {
        LinkedHashMap<Long, VolumeResponse> vrDataList = new LinkedHashMap<>();
        DecimalFormat df = new DecimalFormat("0.0%");
        for (VolumeJoinVO vr : volumes) {
            VolumeResponse vrData = vrDataList.get(vr.getId());
            if (vrData == null) {
                // first time encountering this volume
                vrData = ApiDBUtils.newVolumeResponse(view, vr);
            }
            else{
                // update tags
                vrData = ApiDBUtils.fillVolumeDetails(view, vrData, vr);
            }
            vrDataList.put(vr.getId(), vrData);

            VolumeStats vs = null;
            if (vr.getFormat() == ImageFormat.VHD || vr.getFormat() == ImageFormat.QCOW2 || vr.getFormat() == ImageFormat.RAW) {
                if (vrData.getPath() != null) {
                    vs = ApiDBUtils.getVolumeStatistics(vrData.getPath());
                }
            } else if (vr.getFormat() == ImageFormat.OVA) {
                if (vrData.getChainInfo() != null) {
                    vs = ApiDBUtils.getVolumeStatistics(vrData.getChainInfo());
                }
            }

            if (vs != null) {
                long vsz = vs.getVirtualSize();
                long psz = vs.getPhysicalSize() ;
                double util = (double)psz/vsz;
                vrData.setUtilization(df.format(util));

                if (view == ResponseView.Full) {
                    vrData.setVirtualsize(vsz);
                    vrData.setPhysicalsize(psz);
                }
            }
        }
        return new ArrayList<VolumeResponse>(vrDataList.values());
    }

    public static List<StoragePoolResponse> createStoragePoolResponse(boolean customStats, StoragePoolJoinVO... pools) {
        LinkedHashMap<Long, StoragePoolResponse> vrDataList = new LinkedHashMap<>();
        // Initialise the vrdatalist with the input data
        for (StoragePoolJoinVO vr : pools) {
            StoragePoolResponse vrData = vrDataList.get(vr.getId());
            if (vrData == null) {
                // first time encountering this vm
                vrData = ApiDBUtils.newStoragePoolResponse(vr, customStats);
            } else {
                // update tags
                vrData = ApiDBUtils.fillStoragePoolDetails(vrData, vr);
            }
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<StoragePoolResponse>(vrDataList.values());
    }

    public static List<StoragePoolResponse> createMinimalStoragePoolResponse(StoragePoolJoinVO... pools) {
        LinkedHashMap<Long, StoragePoolResponse> vrDataList = new LinkedHashMap<>();
        for (StoragePoolJoinVO vr : pools) {
            StoragePoolResponse vrData = vrDataList.get(vr.getId());
            if (vrData == null) {
                vrData = ApiDBUtils.newMinimalStoragePoolResponse(vr);
            }
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<StoragePoolResponse>(vrDataList.values());
    }

    public static List<StorageTagResponse> createStorageTagResponse(StoragePoolTagVO... storageTags) {
        ArrayList<StorageTagResponse> list = new ArrayList<StorageTagResponse>();

        for (StoragePoolTagVO vr : storageTags) {
            list.add(ApiDBUtils.newStorageTagResponse(vr));
        }

        return list;
    }

    public static List<HostTagResponse> createHostTagResponse(HostTagVO... hostTags) {
        ArrayList<HostTagResponse> list = new ArrayList<HostTagResponse>();

        for (HostTagVO vr : hostTags) {
            list.add(ApiDBUtils.newHostTagResponse(vr));
        }

        return list;
    }

    public static List<ImageStoreResponse> createImageStoreResponse(ImageStoreJoinVO... stores) {
        LinkedHashMap<Long, ImageStoreResponse> vrDataList = new LinkedHashMap<>();
        // Initialise the vrdatalist with the input data
        for (ImageStoreJoinVO vr : stores) {
            ImageStoreResponse vrData = vrDataList.get(vr.getId());
            if (vrData == null) {
                // first time encountering this vm
                vrData = ApiDBUtils.newImageStoreResponse(vr);
            } else {
                // update tags
                vrData = ApiDBUtils.fillImageStoreDetails(vrData, vr);
            }
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<ImageStoreResponse>(vrDataList.values());
    }

    public static List<StoragePoolResponse> createStoragePoolForMigrationResponse(StoragePoolJoinVO... pools) {
        LinkedHashMap<Long, StoragePoolResponse> vrDataList = new LinkedHashMap<>();
        // Initialise the vrdatalist with the input data
        for (StoragePoolJoinVO vr : pools) {
            StoragePoolResponse vrData = vrDataList.get(vr.getId());
            if (vrData == null) {
                // first time encountering this vm
                vrData = ApiDBUtils.newStoragePoolForMigrationResponse(vr);
            } else {
                // update tags
                vrData = ApiDBUtils.fillStoragePoolForMigrationDetails(vrData, vr);
            }
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<StoragePoolResponse>(vrDataList.values());
    }

    public static List<DomainResponse> createDomainResponse(ResponseView view, EnumSet<DomainDetails> details, List<DomainJoinVO> domains) {
        List<DomainResponse> respList = new ArrayList<DomainResponse>();
        //-- Coping the list to keep original order
        List<DomainJoinVO> domainsCopy = new ArrayList<>(domains);
        Collections.sort(domainsCopy, DomainJoinVO.domainIdComparator);
        for (DomainJoinVO domainJoinVO : domains){
            //-- Set parent information
            DomainJoinVO parentDomainJoinVO = searchParentDomainUsingBinary(domainsCopy, domainJoinVO);
            if(parentDomainJoinVO == null && domainJoinVO.getParent() != null) {
                //-- fetch the parent from the database
                parentDomainJoinVO = ApiDBUtils.findDomainJoinVOById(domainJoinVO.getParent());
                if(parentDomainJoinVO != null) {
                    //-- Add parent domain to the domain copy for future use
                    domainsCopy.add(parentDomainJoinVO);
                    Collections.sort(domainsCopy, DomainJoinVO.domainIdComparator);
                }
            }
            if(parentDomainJoinVO != null) {
                domainJoinVO.setParentName(parentDomainJoinVO.getName());
                domainJoinVO.setParentUuid(parentDomainJoinVO.getUuid());
            }
            //-- Set correct resource limits
            if(domainJoinVO.getParent() != null && domainJoinVO.getParent() != Domain.ROOT_DOMAIN) {
                Map<Resource.ResourceType, Long> resourceLimitMap = new HashMap<>();
                copyResourceLimitsIntoMap(resourceLimitMap, domainJoinVO);
                //-- Fetching the parent domain resource limit if absent in current domain
                setParentResourceLimitIfNeeded(resourceLimitMap, domainJoinVO, domainsCopy);
                //-- copy the final correct resource limit
                copyResourceLimitsFromMap(resourceLimitMap, domainJoinVO);
            }
            respList.add(ApiDBUtils.newDomainResponse(view, details, domainJoinVO));
        }
        return respList;
    }

    private static DomainJoinVO searchParentDomainUsingBinary(List<DomainJoinVO> domainsCopy, DomainJoinVO domainJoinVO){
        Long parentId = domainJoinVO.getParent() == null ? 0 : domainJoinVO.getParent();
        int totalDomains = domainsCopy.size();
        int left = 0;
        int right = totalDomains -1;
        while(left <= right){
            int middle = (left + right) /2;
            DomainJoinVO middleObject = domainsCopy.get(middle);
            if(middleObject.getId() == parentId){
                return middleObject;
            }
            if(middleObject.getId() > parentId){
                right = middle - 1 ;
            }
            else{
                left = middle + 1;
            }
        }
        return null;
    }

    private static void copyResourceLimitsIntoMap(Map<Resource.ResourceType, Long> resourceLimitMap, DomainJoinVO domainJoinVO){
        resourceLimitMap.put(Resource.ResourceType.user_vm, domainJoinVO.getVmLimit());
        resourceLimitMap.put(Resource.ResourceType.public_ip, domainJoinVO.getIpLimit());
        resourceLimitMap.put(Resource.ResourceType.volume, domainJoinVO.getVolumeLimit());
        resourceLimitMap.put(Resource.ResourceType.snapshot, domainJoinVO.getSnapshotLimit());
        resourceLimitMap.put(Resource.ResourceType.template, domainJoinVO.getTemplateLimit());
        resourceLimitMap.put(Resource.ResourceType.network, domainJoinVO.getNetworkLimit());
        resourceLimitMap.put(Resource.ResourceType.vpc, domainJoinVO.getVpcLimit());
        resourceLimitMap.put(Resource.ResourceType.cpu, domainJoinVO.getCpuLimit());
        resourceLimitMap.put(Resource.ResourceType.memory, domainJoinVO.getMemoryLimit());
        resourceLimitMap.put(Resource.ResourceType.gpu, domainJoinVO.getGpuLimit());
        resourceLimitMap.put(Resource.ResourceType.primary_storage, domainJoinVO.getPrimaryStorageLimit());
        resourceLimitMap.put(Resource.ResourceType.secondary_storage, domainJoinVO.getSecondaryStorageLimit());
        resourceLimitMap.put(Resource.ResourceType.project, domainJoinVO.getProjectLimit());
        resourceLimitMap.put(Resource.ResourceType.backup, domainJoinVO.getBackupLimit());
        resourceLimitMap.put(Resource.ResourceType.backup_storage, domainJoinVO.getBackupStorageLimit());
        resourceLimitMap.put(Resource.ResourceType.bucket, domainJoinVO.getBucketLimit());
        resourceLimitMap.put(Resource.ResourceType.object_storage, domainJoinVO.getObjectStorageLimit());
    }

    private static void copyResourceLimitsFromMap(Map<Resource.ResourceType, Long> resourceLimitMap, DomainJoinVO domainJoinVO){
        domainJoinVO.setVmLimit(resourceLimitMap.get(Resource.ResourceType.user_vm));
        domainJoinVO.setIpLimit(resourceLimitMap.get(Resource.ResourceType.public_ip));
        domainJoinVO.setVolumeLimit(resourceLimitMap.get(Resource.ResourceType.volume));
        domainJoinVO.setSnapshotLimit(resourceLimitMap.get(Resource.ResourceType.snapshot));
        domainJoinVO.setTemplateLimit(resourceLimitMap.get(Resource.ResourceType.template));
        domainJoinVO.setNetworkLimit(resourceLimitMap.get(Resource.ResourceType.network));
        domainJoinVO.setVpcLimit(resourceLimitMap.get(Resource.ResourceType.vpc));
        domainJoinVO.setCpuLimit(resourceLimitMap.get(Resource.ResourceType.cpu));
        domainJoinVO.setMemoryLimit(resourceLimitMap.get(Resource.ResourceType.memory));
        domainJoinVO.setGpuLimit(resourceLimitMap.get(Resource.ResourceType.gpu));
        domainJoinVO.setPrimaryStorageLimit(resourceLimitMap.get(Resource.ResourceType.primary_storage));
        domainJoinVO.setSecondaryStorageLimit(resourceLimitMap.get(Resource.ResourceType.secondary_storage));
        domainJoinVO.setProjectLimit(resourceLimitMap.get(Resource.ResourceType.project));
        domainJoinVO.setBackupLimit(resourceLimitMap.get(Resource.ResourceType.backup));
        domainJoinVO.setBackupStorageLimit(resourceLimitMap.get(Resource.ResourceType.backup_storage));
        domainJoinVO.setBucketLimit(resourceLimitMap.get(Resource.ResourceType.bucket));
        domainJoinVO.setObjectStorageLimit(resourceLimitMap.get(Resource.ResourceType.object_storage));
    }

    private static void setParentResourceLimitIfNeeded(Map<Resource.ResourceType, Long> resourceLimitMap, DomainJoinVO domainJoinVO, List<DomainJoinVO> domainsCopy) {
        DomainJoinVO parentDomainJoinVO = searchParentDomainUsingBinary(domainsCopy, domainJoinVO);

        if(parentDomainJoinVO != null) {
            Long vmLimit = resourceLimitMap.get(Resource.ResourceType.user_vm);
            Long ipLimit = resourceLimitMap.get(Resource.ResourceType.public_ip);
            Long volumeLimit = resourceLimitMap.get(Resource.ResourceType.volume);
            Long snapshotLimit = resourceLimitMap.get(Resource.ResourceType.snapshot);
            Long templateLimit = resourceLimitMap.get(Resource.ResourceType.template);
            Long networkLimit = resourceLimitMap.get(Resource.ResourceType.network);
            Long vpcLimit = resourceLimitMap.get(Resource.ResourceType.vpc);
            Long cpuLimit = resourceLimitMap.get(Resource.ResourceType.cpu);
            Long memoryLimit = resourceLimitMap.get(Resource.ResourceType.memory);
            Long gpuLimit = resourceLimitMap.get(Resource.ResourceType.gpu);
            Long primaryStorageLimit = resourceLimitMap.get(Resource.ResourceType.primary_storage);
            Long secondaryStorageLimit = resourceLimitMap.get(Resource.ResourceType.secondary_storage);
            Long projectLimit = resourceLimitMap.get(Resource.ResourceType.project);
            Long backupLimit = resourceLimitMap.get(Resource.ResourceType.backup);
            Long backupStorageLimit = resourceLimitMap.get(Resource.ResourceType.backup_storage);
            Long bucketLimit = resourceLimitMap.get(Resource.ResourceType.bucket);
            Long objectStorageLimit = resourceLimitMap.get(Resource.ResourceType.object_storage);

            if (vmLimit == null) {
                vmLimit = parentDomainJoinVO.getVmLimit();
                resourceLimitMap.put(Resource.ResourceType.user_vm, vmLimit);
            }
            if (ipLimit == null) {
                ipLimit = parentDomainJoinVO.getIpLimit();
                resourceLimitMap.put(Resource.ResourceType.public_ip, ipLimit);
            }
            if (volumeLimit == null) {
                volumeLimit = parentDomainJoinVO.getVolumeLimit();
                resourceLimitMap.put(Resource.ResourceType.volume, volumeLimit);
            }
            if (snapshotLimit == null) {
                snapshotLimit = parentDomainJoinVO.getSnapshotLimit();
                resourceLimitMap.put(Resource.ResourceType.snapshot, snapshotLimit);
            }
            if (templateLimit == null) {
                templateLimit = parentDomainJoinVO.getTemplateLimit();
                resourceLimitMap.put(Resource.ResourceType.template, templateLimit);
            }
            if (networkLimit == null) {
                networkLimit = parentDomainJoinVO.getNetworkLimit();
                resourceLimitMap.put(Resource.ResourceType.network, networkLimit);
            }
            if (vpcLimit == null) {
                vpcLimit = parentDomainJoinVO.getVpcLimit();
                resourceLimitMap.put(Resource.ResourceType.vpc, vpcLimit);
            }
            if (cpuLimit == null) {
                cpuLimit = parentDomainJoinVO.getCpuLimit();
                resourceLimitMap.put(Resource.ResourceType.cpu, cpuLimit);
            }
            if (memoryLimit == null) {
                memoryLimit = parentDomainJoinVO.getMemoryLimit();
                resourceLimitMap.put(Resource.ResourceType.memory, memoryLimit);
            }
            if (gpuLimit == null) {
                gpuLimit = parentDomainJoinVO.getGpuLimit();
                resourceLimitMap.put(Resource.ResourceType.gpu, gpuLimit);
            }
            if (primaryStorageLimit == null) {
                primaryStorageLimit = parentDomainJoinVO.getPrimaryStorageLimit();
                resourceLimitMap.put(Resource.ResourceType.primary_storage, primaryStorageLimit);
            }
            if (secondaryStorageLimit == null) {
                secondaryStorageLimit = parentDomainJoinVO.getSecondaryStorageLimit();
                resourceLimitMap.put(Resource.ResourceType.secondary_storage, secondaryStorageLimit);
            }
            if (projectLimit == null) {
                projectLimit = parentDomainJoinVO.getProjectLimit();
                resourceLimitMap.put(Resource.ResourceType.project, projectLimit);
            }
            if (backupLimit == null) {
                backupLimit = parentDomainJoinVO.getBackupLimit();
                resourceLimitMap.put(Resource.ResourceType.backup, backupLimit);
            }
            if (backupStorageLimit == null) {
                backupStorageLimit = parentDomainJoinVO.getBackupStorageLimit();
                resourceLimitMap.put(Resource.ResourceType.backup_storage, backupStorageLimit);
            }
            if (bucketLimit == null) {
                bucketLimit = parentDomainJoinVO.getBucketLimit();
                resourceLimitMap.put(Resource.ResourceType.bucket, bucketLimit);
            }
            if (objectStorageLimit == null) {
                objectStorageLimit = parentDomainJoinVO.getObjectStorageLimit();
                resourceLimitMap.put(Resource.ResourceType.object_storage, objectStorageLimit);
            }
            //-- try till parent present
            if (parentDomainJoinVO.getParent() != null && parentDomainJoinVO.getParent() != Domain.ROOT_DOMAIN) {
                setParentResourceLimitIfNeeded(resourceLimitMap, parentDomainJoinVO, domainsCopy);
            }
        }
    }

    public static List<AccountResponse> createAccountResponse(ResponseView view, EnumSet<DomainDetails> details, AccountJoinVO... accounts) {
        return ApiDBUtils.newAccountResponses(view, details, accounts);
    }

    public static List<AsyncJobResponse> createAsyncJobResponse(AsyncJobJoinVO... jobs) {
        List<AsyncJobResponse> respList = new ArrayList<AsyncJobResponse>();
        for (AsyncJobJoinVO vt : jobs) {
            respList.add(ApiDBUtils.newAsyncJobResponse(vt));
        }
        return respList;
    }

    public static List<DiskOfferingResponse> createDiskOfferingResponses(Long vmId, List<DiskOfferingJoinVO> offerings) {
        return ApiDBUtils.newDiskOfferingResponses(vmId, offerings);
    }

    public static List<ServiceOfferingResponse> createServiceOfferingResponse(ServiceOfferingJoinVO... offerings) {
        List<ServiceOfferingResponse> respList = new ArrayList<ServiceOfferingResponse>();
        for (ServiceOfferingJoinVO vt : offerings) {
            respList.add(ApiDBUtils.newServiceOfferingResponse(vt));
        }
        return respList;
    }

    public static List<ZoneResponse> createDataCenterResponse(ResponseView view, Boolean showCapacities, Boolean showResourceImage, DataCenterJoinVO... dcs) {
        List<ZoneResponse> respList = new ArrayList<ZoneResponse>();
        for (DataCenterJoinVO vt : dcs) {
            respList.add(ApiDBUtils.newDataCenterResponse(view, vt, showCapacities, showResourceImage));
        }
        return respList;
    }

    public static List<ZoneResponse> createMinimalDataCenterResponse(ResponseView view, DataCenterJoinVO... dcs) {
        List<ZoneResponse> respList = new ArrayList<ZoneResponse>();
        for (DataCenterJoinVO vt : dcs) {
            respList.add(ApiDBUtils.newMinimalDataCenterResponse(view, vt));
        }
        return respList;
    }

    public static List<TemplateResponse> createTemplateResponse(EnumSet<ApiConstants.DomainDetails> detailsView, ResponseView view, TemplateJoinVO... templates) {
        LinkedHashMap<String, TemplateResponse> vrDataList = new LinkedHashMap<>();
        for (TemplateJoinVO vr : templates) {
            TemplateResponse vrData = vrDataList.get(vr.getTempZonePair());
            if (vrData == null) {
                // first time encountering this volume
                vrData = ApiDBUtils.newTemplateResponse(detailsView, view, vr);
            }
            else{
                // update tags
                vrData = ApiDBUtils.fillTemplateDetails(detailsView, view, vrData, vr);
            }
            vrDataList.put(vr.getTempZonePair(), vrData);
        }
        return new ArrayList<TemplateResponse>(vrDataList.values());
    }

    public static List<SnapshotResponse> createSnapshotResponse(ResponseView view, boolean isShowUnique, SnapshotJoinVO... snapshots) {
        LinkedHashMap<String, SnapshotResponse> vrDataList = new LinkedHashMap<>();
        for (SnapshotJoinVO vr : snapshots) {
            SnapshotResponse vrData = vrDataList.get(vr.getSnapshotStorePair());
            if (vrData == null) {
                // first time encountering this snapshot
                vrData = ApiDBUtils.newSnapshotResponse(view, isShowUnique, vr);
            }
            else{
                // update tags
                vrData = ApiDBUtils.fillSnapshotDetails(vrData, vr);
            }
            vrDataList.put(vr.getSnapshotStorePair(), vrData);
        }
        return new ArrayList<SnapshotResponse>(vrDataList.values());
    }

    public static List<TemplateResponse> createTemplateUpdateResponse(ResponseView view, TemplateJoinVO... templates) {
        LinkedHashMap<Long, TemplateResponse> vrDataList = new LinkedHashMap<>();
        for (TemplateJoinVO vr : templates) {
            TemplateResponse vrData = vrDataList.get(vr.getId());
            if (vrData == null) {
                // first time encountering this volume
                vrData = ApiDBUtils.newTemplateUpdateResponse(vr);
            } else {
                // update tags
                vrData = ApiDBUtils.fillTemplateDetails(EnumSet.of(DomainDetails.all), view, vrData, vr);
            }
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<TemplateResponse>(vrDataList.values());
    }

    public static List<TemplateResponse> createIsoResponse(ResponseView view, TemplateJoinVO... templates) {
        LinkedHashMap<String, TemplateResponse> vrDataList = new LinkedHashMap<>();
        for (TemplateJoinVO vr : templates) {
            TemplateResponse vrData = vrDataList.get(vr.getTempZonePair());
            if (vrData == null) {
                // first time encountering this volume
                vrData = ApiDBUtils.newIsoResponse(vr, view);
            } else {
                // update tags
                vrData = ApiDBUtils.fillTemplateDetails(EnumSet.of(DomainDetails.all), view, vrData, vr);
            }
            vrDataList.put(vr.getTempZonePair(), vrData);
        }
        return new ArrayList<TemplateResponse>(vrDataList.values());
    }

    public static List<AffinityGroupResponse> createAffinityGroupResponses(List<AffinityGroupJoinVO> groups) {
        LinkedHashMap<Long, AffinityGroupResponse> vrDataList = new LinkedHashMap<>();
        for (AffinityGroupJoinVO vr : groups) {
            AffinityGroupResponse vrData = vrDataList.get(vr.getId());
            if (vrData == null) {
                // first time encountering this AffinityGroup
                vrData = ApiDBUtils.newAffinityGroupResponse(vr);
            } else {
                // update vms
                vrData = ApiDBUtils.fillAffinityGroupDetails(vrData, vr);
            }
            vrDataList.put(vr.getId(), vrData);
        }
        return new ArrayList<AffinityGroupResponse>(vrDataList.values());
    }

    public static List<ObjectStoreResponse> createObjectStoreResponse(ObjectStoreVO[] stores) {
        Hashtable<Long, ObjectStoreResponse> storeList = new Hashtable<Long, ObjectStoreResponse>();
        // Initialise the storeList with the input data
        for (ObjectStoreVO store : stores) {
            ObjectStoreResponse storeData = storeList.get(store.getId());
            if (storeData == null) {
                // first time encountering this store
                storeData = ApiDBUtils.newObjectStoreResponse(store);
            } else {
                // update tags
                storeData = ApiDBUtils.fillObjectStoreDetails(storeData, store);
            }
            storeList.put(store.getId(), storeData);
        }
        return new ArrayList<>(storeList.values());
    }
}
