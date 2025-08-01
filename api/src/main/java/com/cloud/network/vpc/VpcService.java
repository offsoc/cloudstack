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
package com.cloud.network.vpc;

import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.user.vpc.CreatePrivateGatewayCmd;
import org.apache.cloudstack.api.command.user.vpc.CreateVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.ListPrivateGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpc.ListStaticRoutesCmd;
import org.apache.cloudstack.api.command.user.vpc.ListVPCsCmd;
import org.apache.cloudstack.api.command.user.vpc.RestartVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.UpdateVPCCmd;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.user.User;
import com.cloud.utils.Pair;

public interface VpcService {

    /**
     * Persists VPC record in the database
     *
     * @param zoneId
     * @param vpcOffId
     * @param vpcOwnerId
     * @param vpcName
     * @param displayText
     * @param cidr
     * @param networkDomain   TODO
     * @param ip4Dns1
     * @param ip4Dns2
     * @param displayVpc      TODO
     * @param useVrIpResolver
     * @return
     * @throws ResourceAllocationException TODO
     */
    Vpc createVpc(long zoneId, long vpcOffId, long vpcOwnerId, String vpcName, String displayText, String cidr, String networkDomain,
                  String ip4Dns1, String ip4Dns2, String ip6Dns1, String ip6Dns2, Boolean displayVpc, Integer publicMtu, Integer cidrSize,
                  Long asNumber, List<Long> bgpPeerIds, Boolean useVrIpResolver) throws ResourceAllocationException;

    /**
     * Persists VPC record in the database
     *
     * @param cmd the command with specification data for the new vpc
     * @return a data object describing the new vpc
     * @throws ResourceAllocationException the resources for this VPC cannot be allocated
     */
    Vpc createVpc(CreateVPCCmd cmd) throws ResourceAllocationException;

    /**
     * Deletes a VPC
     *
     * @param vpcId
     * @return
     * @throws InsufficientCapacityException
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     */
    boolean deleteVpc(long vpcId) throws ConcurrentOperationException, ResourceUnavailableException;

    /**
     * Persists VPC record in the database
     *
     * @param cmd the command with specification data for updating the vpc
     * @return a data object describing the new vpc state
     * @throws ResourceUnavailableException if during restart some resources may not be available
     * @throws InsufficientCapacityException if for instance no address space, compute or storage is sufficiently available
     */
    Vpc updateVpc(UpdateVPCCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Updates VPC with new name/displayText
     *
     * @param vpcId the ID of the Vpc to update
     * @param vpcName The new name to give the vpc
     * @param displayText the new display text to use for describing the VPC
     * @param customId A new custom (external) ID to associate this VPC with
     * @param displayVpc should this VPC be displayed on public lists
     * @param mtu what maximal transfer unit to us in this VPCs networks
     * @param sourceNatIp the source NAT address to use for this VPC (must already be associated with the VPC)
     * @return an object describing the current state of the VPC
     * @throws ResourceUnavailableException if during restart some resources may not be available
     * @throws InsufficientCapacityException if for instance no address space, compute or storage is sufficiently available
     */
    Vpc updateVpc(long vpcId, String vpcName, String displayText, String customId, Boolean displayVpc, Integer mtu, String sourceNatIp) throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Lists VPC(s) based on the parameters passed to the API call
     *
     * @param cmd object containing the search specs
     * @return the List of VPCs
     */
    Pair<List<? extends Vpc>, Integer> listVpcs(ListVPCsCmd cmd);

    /**
     * Lists VPC(s) based on the parameters passed to the method call
     */
    Pair<List<? extends Vpc>, Integer> listVpcs(Long id, String vpcName, String displayText, List<String> supportedServicesStr, String cidr, Long vpcOffId, String state,
                                                String accountName, Long domainId, String keyword, Long startIndex, Long pageSizeVal, Long zoneId, Boolean isRecursive, Boolean listAll, Boolean restartRequired,
                                                Map<String, String> tags, Long projectId, Boolean display);

    /**
     * Starts VPC which includes starting VPC provider and applying all the networking rules on the backend
     *
     * @param vpcId
     * @param destroyOnFailure TODO
     * @return
     * @throws InsufficientCapacityException
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     */
    boolean startVpc(long vpcId, boolean destroyOnFailure) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException;

    void startVpc(CreateVPCCmd cmd) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Shuts down the VPC which includes shutting down all VPC provider and rules cleanup on the backend
     *
     * @param vpcId
     * @return
     * @throws ConcurrentOperationException
     * @throws ResourceUnavailableException
     */
    boolean shutdownVpc(long vpcId) throws ConcurrentOperationException, ResourceUnavailableException;

    boolean restartVpc(RestartVPCCmd cmd) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Restarts the VPC. VPC gets shutdown and started as a part of it
     *
     * @param networkId the network to restart
     * @param cleanup throw away the existing VR and rebuild a new one?
     * @param makeRedundant create two VRs for this network
     * @return success or not
     * @throws InsufficientCapacityException when there is no suitable deployment plan possible
     */
    boolean restartVpc(Long networkId, boolean cleanup, boolean makeRedundant, boolean livePatch, User user) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Returns a Private gateway found in the VPC by id
     *
     * @param id
     * @return
     */
    PrivateGateway getVpcPrivateGateway(long id);

    /**
     * Persists VPC private gateway in the Database.
     *
     * @return data object describing the private gateway
     * @throws InsufficientCapacityException
     * @throws ConcurrentOperationException
     * @throws ResourceAllocationException
     */
    PrivateGateway createVpcPrivateGateway(CreatePrivateGatewayCmd command) throws ResourceAllocationException, ConcurrentOperationException, InsufficientCapacityException;

    /**
     * Applies VPC private gateway on the backend, so it becomes functional
     *
     * @param gatewayId
     * @param destroyOnFailure TODO
     * @return
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     */
    PrivateGateway applyVpcPrivateGateway(long gatewayId, boolean destroyOnFailure) throws ConcurrentOperationException, ResourceUnavailableException;

    /**
     * Deletes VPC private gateway
     *
     * @param gatewayId
     * @return
     * @throws ResourceUnavailableException
     * @throws ConcurrentOperationException
     */
    boolean deleteVpcPrivateGateway(long gatewayId) throws ConcurrentOperationException, ResourceUnavailableException;

    /**
     * Returns the list of Private gateways existing in the VPC
     *
     * @param listPrivateGatewaysCmd
     * @return
     */
    Pair<List<PrivateGateway>, Integer> listPrivateGateway(ListPrivateGatewaysCmd listPrivateGatewaysCmd);

    /**
     * Returns Static Route found by Id
     *
     * @param routeId
     * @return
     */
    StaticRoute getStaticRoute(long routeId);

    /**
     * Applies existing Static Routes to the VPC elements
     *
     * @param vpcId
     * @return
     * @throws ResourceUnavailableException
     */
    boolean applyStaticRoutesForVpc(long vpcId) throws ResourceUnavailableException;

    /**
     * Deletes static route from the backend and the database
     *
     * @param routeId
     * @return TODO
     * @throws ResourceUnavailableException
     */
    boolean revokeStaticRoute(long routeId) throws ResourceUnavailableException;

    /**
     * Persists static route entry in the Database
     *
     * @param gatewayId
     * @param cidr
     * @return
     */
    StaticRoute createStaticRoute(Long gatewayId, Long vpcId, String nextHop, String cidr) throws NetworkRuleConflictException;

    /**
     * Lists static routes based on parameters passed to the call
     *
     * @param cmd Command object with parameters for { @see ListStaticRoutesCmd }
     * @return
     */
    Pair<List<? extends StaticRoute>, Integer> listStaticRoutes(ListStaticRoutesCmd cmd);

    /**
     * Associates IP address from the Public network, to the VPC
     *
     * @param ipId
     * @param vpcId
     * @return
     * @throws ResourceAllocationException
     * @throws ResourceUnavailableException
     * @throws InsufficientAddressCapacityException
     * @throws ConcurrentOperationException
     */
    IpAddress associateIPToVpc(long ipId, long vpcId) throws ResourceAllocationException, ResourceUnavailableException, InsufficientAddressCapacityException,
    ConcurrentOperationException;

    /**
     * @param routeId
     * @return
     */
    boolean applyStaticRoute(long routeId) throws ResourceUnavailableException;
}
