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
package org.apache.cloudstack.query;

import java.util.Arrays;
import java.util.List;

import org.apache.cloudstack.affinity.AffinityGroupResponse;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostTagsCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostsCmd;
import org.apache.cloudstack.api.command.admin.internallb.ListInternalLBVMsCmd;
import org.apache.cloudstack.api.command.admin.management.ListMgmtsCmd;
import org.apache.cloudstack.api.command.admin.resource.icon.ListResourceIconCmd;
import org.apache.cloudstack.api.command.admin.router.GetRouterHealthCheckResultsCmd;
import org.apache.cloudstack.api.command.admin.router.ListRoutersCmd;
import org.apache.cloudstack.api.command.admin.storage.ListImageStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListObjectStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListSecondaryStagingStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageAccessGroupsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageTagsCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.ListSecondaryStorageSelectorsCmd;
import org.apache.cloudstack.api.command.admin.user.ListUsersCmd;
import org.apache.cloudstack.api.command.user.account.ListAccountsCmd;
import org.apache.cloudstack.api.command.user.account.ListProjectAccountsCmd;
import org.apache.cloudstack.api.command.user.address.ListQuarantinedIpsCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.ListAffinityGroupsCmd;
import org.apache.cloudstack.api.command.user.bucket.ListBucketsCmd;
import org.apache.cloudstack.api.command.user.event.ListEventsCmd;
import org.apache.cloudstack.api.command.user.iso.ListIsosCmd;
import org.apache.cloudstack.api.command.user.job.ListAsyncJobsCmd;
import org.apache.cloudstack.api.command.user.offering.ListDiskOfferingsCmd;
import org.apache.cloudstack.api.command.user.offering.ListServiceOfferingsCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectInvitationsCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectsCmd;
import org.apache.cloudstack.api.command.user.resource.ListDetailOptionsCmd;
import org.apache.cloudstack.api.command.user.securitygroup.ListSecurityGroupsCmd;
import org.apache.cloudstack.api.command.user.snapshot.CopySnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.ListSnapshotsCmd;
import org.apache.cloudstack.api.command.user.tag.ListTagsCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatesCmd;
import org.apache.cloudstack.api.command.admin.vm.ListAffectedVmsForStorageScopeChangeCmd;
import org.apache.cloudstack.api.command.user.vm.ListVMsCmd;
import org.apache.cloudstack.api.command.user.vmgroup.ListVMGroupsCmd;
import org.apache.cloudstack.api.command.user.volume.ListResourceDetailsCmd;
import org.apache.cloudstack.api.command.user.volume.ListVolumesCmd;
import org.apache.cloudstack.api.command.user.zone.ListZonesCmd;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.AsyncJobResponse;
import org.apache.cloudstack.api.response.BucketResponse;
import org.apache.cloudstack.api.response.DetailOptionsResponse;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.DomainRouterResponse;
import org.apache.cloudstack.api.response.EventResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.HostTagResponse;
import org.apache.cloudstack.api.response.ImageStoreResponse;
import org.apache.cloudstack.api.response.InstanceGroupResponse;
import org.apache.cloudstack.api.response.IpQuarantineResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ManagementServerResponse;
import org.apache.cloudstack.api.response.ObjectStoreResponse;
import org.apache.cloudstack.api.response.ProjectAccountResponse;
import org.apache.cloudstack.api.response.ProjectInvitationResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.ResourceDetailResponse;
import org.apache.cloudstack.api.response.ResourceIconResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.RouterHealthCheckResultResponse;
import org.apache.cloudstack.api.response.SecondaryStorageHeuristicsResponse;
import org.apache.cloudstack.api.response.SecurityGroupResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.StorageAccessGroupResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.StorageTagResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.UserResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VirtualMachineResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.vm.VmDetailConstants;

/**
 * Service used for list api query.
 *
 */
public interface QueryService {

    List<String> RootAdminOnlyVmSettings = Arrays.asList(VmDetailConstants.GUEST_CPU_MODE, VmDetailConstants.GUEST_CPU_MODEL);

    // Config keys
    ConfigKey<Boolean> AllowUserViewDestroyedVM = new ConfigKey<>("Advanced", Boolean.class, "allow.user.view.destroyed.vm", "false",
            "Determines whether users can view their destroyed or expunging vm ", true, ConfigKey.Scope.Account);

    ConfigKey<String> UserVMDeniedDetails = new ConfigKey<>(String.class,
    "user.vm.denied.details", "Advanced", "rootdisksize, cpuOvercommitRatio, memoryOvercommitRatio, Message.ReservedCapacityFreed.Flag",
            "Determines whether users can view certain VM settings. When set to empty, default value used is: rootdisksize, cpuOvercommitRatio, memoryOvercommitRatio, Message.ReservedCapacityFreed.Flag.", true, ConfigKey.Scope.Global, null, null, null, null, null, ConfigKey.Kind.CSV, null);

    ConfigKey<String> UserVMReadOnlyDetails = new ConfigKey<>(String.class,
    "user.vm.readonly.details", "Advanced", "dataDiskController, rootDiskController",
            "List of read-only VM settings/details as comma separated string", true, ConfigKey.Scope.Global, null, null, null, null, null, ConfigKey.Kind.CSV, null);

    ConfigKey<Boolean> SortKeyAscending = new ConfigKey<>("Advanced", Boolean.class, "sortkey.algorithm", "true",
            "Sort algorithm - ascending or descending - to use. For entities that use sort key(template, disk offering, service offering, " +
                    "network offering, zones), we use the flag to determine if the entities should be sorted ascending (when flag is true) " +
                    "or descending (when flag is false). Within the scope of the config all users see the same result.", true, ConfigKey.Scope.Global);

    ConfigKey<Boolean> AllowUserViewAllDomainAccounts = new ConfigKey<>("Advanced", Boolean.class,
            "allow.user.view.all.domain.accounts", "false",
            "Determines whether users can view all user accounts within the same domain", true, ConfigKey.Scope.Domain);

    ConfigKey<Boolean> AllowUserViewAllDataCenters = new ConfigKey<>("Advanced", Boolean.class, "allow.user.view.all.zones", "true",
            "Determines whether for instance a Resource Admin can view zones that are not dedicated to them.", true, ConfigKey.Scope.Domain);

    ConfigKey<Boolean> SharePublicTemplatesWithOtherDomains = new ConfigKey<>("Advanced", Boolean.class, "share.public.templates.with.other.domains", "true",
            "If false, templates of this domain will not show up in the list templates of other domains.", true, ConfigKey.Scope.Domain);

    ConfigKey<Boolean> ReturnVmStatsOnVmList = new ConfigKey<>("Advanced", Boolean.class, "list.vm.default.details.stats", "true",
            "Determines whether VM stats should be returned when details are not explicitly specified in listVirtualMachines API request. When false, details default to [group, nics, secgrp, tmpl, servoff, diskoff, backoff, iso, volume, min, affgrp]. When true, all details are returned including 'stats'.", true, ConfigKey.Scope.Global);


    ListResponse<UserResponse> searchForUsers(ResponseObject.ResponseView responseView, ListUsersCmd cmd) throws PermissionDeniedException;

    ListResponse<UserResponse> searchForUsers(Long domainId, boolean recursive) throws PermissionDeniedException;

    ListResponse<EventResponse> searchForEvents(ListEventsCmd cmd);

    ListResponse<ResourceTagResponse> listTags(ListTagsCmd cmd);

    ListResponse<InstanceGroupResponse> searchForVmGroups(ListVMGroupsCmd cmd);

    ListResponse<UserVmResponse> searchForUserVMs(ListVMsCmd cmd);

    ListResponse<VirtualMachineResponse> listAffectedVmsForStorageScopeChange(ListAffectedVmsForStorageScopeChangeCmd cmd);

    ListResponse<SecurityGroupResponse> searchForSecurityGroups(ListSecurityGroupsCmd cmd);

    ListResponse<DomainRouterResponse> searchForRouters(ListRoutersCmd cmd);

    ListResponse<ProjectInvitationResponse> listProjectInvitations(ListProjectInvitationsCmd cmd);

    ListResponse<ProjectResponse> listProjects(ListProjectsCmd cmd);

    ListResponse<ProjectAccountResponse> listProjectAccounts(ListProjectAccountsCmd cmd);

    ListResponse<HostResponse> searchForServers(ListHostsCmd cmd);

    ListResponse<VolumeResponse> searchForVolumes(ListVolumesCmd cmd);

    ListResponse<StoragePoolResponse> searchForStoragePools(ListStoragePoolsCmd cmd);

    ListResponse<ImageStoreResponse> searchForImageStores(ListImageStoresCmd cmd);

    ListResponse<ImageStoreResponse> searchForSecondaryStagingStores(ListSecondaryStagingStoresCmd cmd);

    ListResponse<DomainResponse> searchForDomains(ListDomainsCmd cmd);

    ListResponse<AccountResponse> searchForAccounts(ListAccountsCmd cmd);

    ListResponse<AsyncJobResponse>  searchForAsyncJobs(ListAsyncJobsCmd cmd);

    ListResponse<DiskOfferingResponse>  searchForDiskOfferings(ListDiskOfferingsCmd cmd);

    ListResponse<ServiceOfferingResponse>  searchForServiceOfferings(ListServiceOfferingsCmd cmd);

    ListResponse<ZoneResponse>  listDataCenters(ListZonesCmd cmd);

    ListResponse<TemplateResponse> listTemplates(ListTemplatesCmd cmd);

    ListResponse<TemplateResponse> listIsos(ListIsosCmd cmd);

    DetailOptionsResponse listDetailOptions(ListDetailOptionsCmd cmd);

    ListResponse<ResourceIconResponse> listResourceIcons(ListResourceIconCmd cmd);

    ListResponse<AffinityGroupResponse> searchForAffinityGroups(ListAffinityGroupsCmd cmd);

    List<ResourceDetailResponse> listResourceDetails(ListResourceDetailsCmd cmd);

    ListResponse<DomainRouterResponse> searchForInternalLbVms(ListInternalLBVMsCmd cmd);

    ListResponse<StorageTagResponse> searchForStorageTags(ListStorageTagsCmd cmd);

    ListResponse<StorageAccessGroupResponse> searchForStorageAccessGroups(ListStorageAccessGroupsCmd cmd);

    ListResponse<HostTagResponse> searchForHostTags(ListHostTagsCmd cmd);

    ListResponse<ManagementServerResponse> listManagementServers(ListMgmtsCmd cmd);

    List<RouterHealthCheckResultResponse> listRouterHealthChecks(GetRouterHealthCheckResultsCmd cmd);

    ListResponse<SecondaryStorageHeuristicsResponse> listSecondaryStorageSelectors(ListSecondaryStorageSelectorsCmd cmd);

    ListResponse<IpQuarantineResponse> listQuarantinedIps(ListQuarantinedIpsCmd cmd);

    ListResponse<SnapshotResponse> listSnapshots(ListSnapshotsCmd cmd);

    SnapshotResponse listSnapshot(CopySnapshotCmd cmd);

    ListResponse<ObjectStoreResponse> searchForObjectStores(ListObjectStoragePoolsCmd listObjectStoragePoolsCmd);

    ListResponse<BucketResponse> searchForBuckets(ListBucketsCmd listBucketsCmd);
}
