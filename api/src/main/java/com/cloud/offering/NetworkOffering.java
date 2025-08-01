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
package com.cloud.offering;

import java.util.Date;

import org.apache.cloudstack.acl.InfrastructureEntity;
import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;

import com.cloud.network.Network.GuestType;
import com.cloud.network.Networks.TrafficType;

/**
 * Describes network offering
 *
 */
public interface NetworkOffering extends InfrastructureEntity, InternalIdentity, Identity {

    public enum Availability {
        Required, Optional
    }

    public enum State {
        Disabled, Enabled
    }

    public enum Detail {
        InternalLbProvider, PublicLbProvider, servicepackageuuid, servicepackagedescription, PromiscuousMode, MacAddressChanges, ForgedTransmits, MacLearning, RelatedNetworkOffering, domainid, zoneid, pvlanType, internetProtocol
    }

    public enum NetworkMode {
        NATTED,
        ROUTED
    }

    enum RoutingMode {
        Static, Dynamic
    }

    public final static String SystemPublicNetwork = "System-Public-Network";
    public final static String SystemControlNetwork = "System-Control-Network";
    public final static String SystemManagementNetwork = "System-Management-Network";
    public final static String SystemStorageNetwork = "System-Storage-Network";
    public final static String SystemPrivateGatewayNetworkOffering = "System-Private-Gateway-Network-Offering";
    public final static String SystemPrivateGatewayNetworkOfferingWithoutVlan = "System-Private-Gateway-Network-Offering-Without-Vlan";

    public final static String DefaultSharedNetworkOfferingWithSGService = "DefaultSharedNetworkOfferingWithSGService";
    public static final String DEFAULT_TUNGSTEN_SHARED_NETWORK_OFFERING_WITH_SGSERVICE = "DefaultTungstenSharedNetworkOfferingWithSGService";
    public static final String DEFAULT_NAT_NSX_OFFERING_FOR_VPC = "DefaultNATNSXNetworkOfferingForVpc";
    public static final String DEFAULT_NAT_NSX_OFFERING_FOR_VPC_WITH_ILB = "DefaultNATNSXNetworkOfferingForVpcWithInternalLB";
    public static final String DEFAULT_ROUTED_NSX_OFFERING_FOR_VPC = "DefaultRoutedNSXNetworkOfferingForVpc";
    public static final String DEFAULT_ROUTED_NETRIS_OFFERING_FOR_VPC = "DefaultRoutedNetrisNetworkOfferingForVpc";
    public static final String DEFAULT_NAT_NETRIS_OFFERING_FOR_VPC = "DefaultNATNetrisNetworkOfferingForVpc";
    public static final String DEFAULT_NAT_NSX_OFFERING = "DefaultNATNSXNetworkOffering";
    public static final String DEFAULT_ROUTED_NSX_OFFERING = "DefaultRoutedNSXNetworkOffering";
    public final static String QuickCloudNoServices = "QuickCloudNoServices";
    public final static String DefaultIsolatedNetworkOfferingWithSourceNatService = "DefaultIsolatedNetworkOfferingWithSourceNatService";
    public final static String OvsIsolatedNetworkOfferingWithSourceNatService = "OvsIsolatedNetworkOfferingWithSourceNatService";
    public final static String DefaultSharedNetworkOffering = "DefaultSharedNetworkOffering";
    public final static String DefaultIsolatedNetworkOffering = "DefaultIsolatedNetworkOffering";
    public final static String DefaultSharedEIPandELBNetworkOffering = "DefaultSharedNetscalerEIPandELBNetworkOffering";
    public final static String DefaultIsolatedNetworkOfferingForVpcNetworks = "DefaultIsolatedNetworkOfferingForVpcNetworks";
    public final static String DefaultIsolatedNetworkOfferingForVpcNetworksNoLB = "DefaultIsolatedNetworkOfferingForVpcNetworksNoLB";
    public final static String DefaultIsolatedNetworkOfferingForVpcNetworksWithInternalLB = "DefaultIsolatedNetworkOfferingForVpcNetworksWithInternalLB";
    public final static String DefaultL2NetworkOffering = "DefaultL2NetworkOffering";
    public final static String DefaultL2NetworkOfferingVlan = "DefaultL2NetworkOfferingVlan";
    public final static String DefaultL2NetworkOfferingConfigDrive = "DefaultL2NetworkOfferingConfigDrive";
    public final static String DefaultL2NetworkOfferingConfigDriveVlan = "DefaultL2NetworkOfferingConfigDriveVlan";

    /**
     * @return name for the network offering.
     */
    String getName();

    /**
     * @return text to display to the end user.
     */
    String getDisplayText();

    /**
     * @return the rate in megabits per sec to which a VM's network interface is throttled to
     */
    Integer getRateMbps();

    /**
     * @return the rate megabits per sec to which a VM's multicast&broadcast traffic is throttled to
     */
    Integer getMulticastRateMbps();

    boolean isForVpc();

    NetworkMode getNetworkMode();

    TrafficType getTrafficType();

    boolean isSpecifyVlan();

    String getTags();

    boolean isDefault();

    boolean isSystemOnly();

    Availability getAvailability();

    String getUniqueName();

    void setState(State state);

    State getState();

    GuestType getGuestType();

    Long getServiceOfferingId();

    boolean isDedicatedLB();

    boolean isSharedSourceNat();

    boolean isRedundantRouter();

    boolean isConserveMode();

    boolean isElasticIp();

    boolean isAssociatePublicIP();

    boolean isElasticLb();

    boolean isSpecifyIpRanges();

    boolean isInline();

    boolean isPersistent();

    boolean isInternalLb();

    boolean isPublicLb();

    boolean isEgressDefaultPolicy();

    Integer getConcurrentConnections();

    boolean isKeepAliveEnabled();

    boolean isSupportingStrechedL2();

    boolean isSupportingPublicAccess();

    boolean isSupportsVmAutoScaling();

    String getServicePackage();

    Date getCreated();

    RoutingMode getRoutingMode();

    Boolean isSpecifyAsNumber();
}
