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
package com.cloud.network.vpn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.user.vpn.CreateVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateVpnCustomerGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateVpnGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteVpnCustomerGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteVpnGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnConnectionsCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnCustomerGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpn.ResetVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.UpdateVpnCustomerGatewayCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;

import com.cloud.configuration.Config;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Site2SiteCustomerGateway;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.Site2SiteVpnConnection.State;
import com.cloud.network.Site2SiteVpnGateway;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.Site2SiteCustomerGatewayDao;
import com.cloud.network.dao.Site2SiteCustomerGatewayVO;
import com.cloud.network.dao.Site2SiteVpnConnectionDao;
import com.cloud.network.dao.Site2SiteVpnConnectionVO;
import com.cloud.network.dao.Site2SiteVpnGatewayDao;
import com.cloud.network.dao.Site2SiteVpnGatewayVO;
import com.cloud.network.element.Site2SiteVpnServiceProvider;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.VpcOfferingServiceMapVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.dao.DomainRouterDao;

@Component
public class Site2SiteVpnManagerImpl extends ManagerBase implements Site2SiteVpnManager {

    List<Site2SiteVpnServiceProvider> _s2sProviders;
    @Inject
    Site2SiteCustomerGatewayDao _customerGatewayDao;
    @Inject
    Site2SiteVpnGatewayDao _vpnGatewayDao;
    @Inject
    Site2SiteVpnConnectionDao _vpnConnectionDao;
    @Inject
    VpcDao _vpcDao;
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    AccountDao _accountDao;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    AccountManager _accountMgr;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    VpcOfferingServiceMapDao vpcOfferingServiceMapDao;
    @Inject
    private DomainRouterDao domainRouterDao;
    @Inject
    private IpAddressManager ipAddressManager;
    @Inject
    private VpcManager vpcManager;

    int _connLimit;
    int _subnetsLimit;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        Map<String, String> configs = _configDao.getConfiguration(params);
        _connLimit = NumbersUtil.parseInt(configs.get(Config.Site2SiteVpnConnectionPerVpnGatewayLimit.key()), 4);
        _subnetsLimit = NumbersUtil.parseInt(configs.get(Config.Site2SiteVpnSubnetsPerCustomerGatewayLimit.key()), 10);
        assert (_s2sProviders.iterator().hasNext()) : "Did not get injected with a list of S2S providers!";
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_GATEWAY_CREATE, eventDescription = "creating s2s vpn gateway", async = true)
    public Site2SiteVpnGateway createVpnGateway(CreateVpnGatewayCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Account owner = _accountMgr.getAccount(cmd.getEntityOwnerId());

        //Verify that caller can perform actions in behalf of vpc owner
        _accountMgr.checkAccess(caller, null, false, owner);

        Long vpcId = cmd.getVpcId();
        VpcVO vpc = _vpcDao.findById(vpcId);
        if (vpc == null) {
            throw new InvalidParameterValueException("Invalid VPC " + vpcId + " for site to site vpn gateway creation!");
        }
        Site2SiteVpnGatewayVO gws = _vpnGatewayDao.findByVpcId(vpcId);
        if (gws != null) {
            throw new InvalidParameterValueException(String.format("The VPN gateway of VPC %s already existed!", vpc));
        }

        IPAddressVO requestedIp = _ipAddressDao.findById(cmd.getIpAddressId());
        IPAddressVO ipAddress = getIpAddressIdForVpn(vpcId, vpc.getVpcOfferingId(), requestedIp);
        Site2SiteVpnGatewayVO gw = new Site2SiteVpnGatewayVO(owner.getAccountId(), owner.getDomainId(), ipAddress.getId(), vpcId);

        if (cmd.getDisplay() != null) {
            gw.setDisplay(cmd.getDisplay());
        }

        _vpnGatewayDao.persist(gw);
        return gw;
    }

    private IPAddressVO getIpAddressIdForVpn(Long vpcId, Long vpcOferingId, IPAddressVO requestedIp) {
        VpcOfferingServiceMapVO mapForSourceNat = vpcOfferingServiceMapDao.findByServiceProviderAndOfferingId(Network.Service.SourceNat.getName(), Network.Provider.VPCVirtualRouter.getName(), vpcOferingId);
        VpcOfferingServiceMapVO mapForVpn = vpcOfferingServiceMapDao.findByServiceProviderAndOfferingId(Network.Service.Vpn.getName(), Network.Provider.VPCVirtualRouter.getName(), vpcOferingId);
        if (mapForSourceNat == null && mapForVpn != null) {
            // Use Static NAT IP of VPC VR
            logger.debug(String.format("The VPC VR provides %s Service, however it does not provide %s service, trying to configure using IP of VPC VR", Network.Service.Vpn.getName(), Network.Service.SourceNat.getName()));

            Vpc vpc = _vpcDao.findById(vpcId);
            IPAddressVO ipAddressForVpcVR = vpcManager.getIpAddressForVpcVr(vpc, requestedIp, true);
            if (!vpcManager.configStaticNatForVpcVr(vpc, ipAddressForVpcVR)) {
                throw new CloudRuntimeException("Failed to enable static nat for VPC VR as part of vpn gateway");
            }
            return ipAddressForVpcVR;
        } else {
            //Use source NAT ip for VPC
            List<IPAddressVO> ips = _ipAddressDao.listByAssociatedVpc(vpcId, true);
            if (ips.size() != 1) {
                throw new CloudRuntimeException("Cannot found source nat ip of vpc " + vpcId);
            }
            if (requestedIp != null && requestedIp.getId() != ips.get(0).getId()) {
                throw new CloudRuntimeException(String.format("Cannot use requested IP %s as it is not the Source NAT IP", requestedIp.getAddress().addr()));
            }
            return ips.get(0);
        }
    }

    protected void checkCustomerGatewayCidrList(String guestCidrList) {
        String[] cidrList = guestCidrList.split(",");
        if (cidrList.length > _subnetsLimit) {
            throw new InvalidParameterValueException("Too many subnets of customer gateway! The limit is " + _subnetsLimit);
        }
        // Remote sub nets cannot overlap themselves
        for (int i = 0; i < cidrList.length - 1; i++) {
            for (int j = i + 1; j < cidrList.length; j++) {
                if (NetUtils.isNetworksOverlap(cidrList[i], cidrList[j])) {
                    throw new InvalidParameterValueException("The subnet of customer gateway " + cidrList[i] + " is overlapped with another subnet " + cidrList[j] +
                        " of customer gateway!");
                }
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_CUSTOMER_GATEWAY_CREATE, eventDescription = "creating s2s customer gateway", create = true)
    public Site2SiteCustomerGateway createCustomerGateway(CreateVpnCustomerGatewayCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Account owner = _accountMgr.getAccount(cmd.getEntityOwnerId());

        //Verify that caller can perform actions in behalf of vpc owner
        _accountMgr.checkAccess(caller, null, false, owner);

        String name = cmd.getName();
        String gatewayIp = cmd.getGatewayIp();

        if (!NetUtils.isValidIp4(gatewayIp) && !NetUtils.verifyDomainName(gatewayIp)) {
            throw new InvalidParameterValueException("The customer gateway ip/Domain " + gatewayIp + " is invalid!");
        }
        if (name == null) {
            name = "VPN-" + gatewayIp;
        }
        String peerCidrList = cmd.getGuestCidrList();
        if (!NetUtils.isValidCidrList(peerCidrList)) {
            throw new InvalidParameterValueException("The customer gateway peer cidr list " + peerCidrList + " contains an invalid cidr!");
        }
        peerCidrList = NetUtils.getCleanIp4CidrList(peerCidrList);
        String ipsecPsk = cmd.getIpsecPsk();
        String ikePolicy = cmd.getIkePolicy();
        String espPolicy = cmd.getEspPolicy();
        if (!NetUtils.isValidS2SVpnPolicy("ike", ikePolicy)) {
            throw new InvalidParameterValueException("The customer gateway IKE policy " + ikePolicy + " is invalid!  Verify the required Diffie Hellman (DH) group is specified.");
        }
        if (!NetUtils.isValidS2SVpnPolicy("esp", espPolicy)) {
            throw new InvalidParameterValueException("The customer gateway ESP policy " + espPolicy + " is invalid!");
        }
        Long ikeLifetime = cmd.getIkeLifetime();
        if (ikeLifetime == null) {
            // Default value of lifetime is 1 day
            ikeLifetime = (long)86400;
        }
        if (ikeLifetime > 86400) {
            throw new InvalidParameterValueException("The IKE lifetime " + ikeLifetime + " of vpn connection is invalid!");
        }
        Long espLifetime = cmd.getEspLifetime();
        if (espLifetime == null) {
            // Default value of lifetime is 1 hour
            espLifetime = (long)3600;
        }
        if (espLifetime > 86400) {
            throw new InvalidParameterValueException("The ESP lifetime " + espLifetime + " of vpn connection is invalid!");
        }

        Boolean dpd = cmd.getDpd();
        if (dpd == null) {
            dpd = false;
        }

        Boolean encap = cmd.getEncap();
        if (encap == null) {
            encap = false;
        }

        long accountId = owner.getAccountId();
        if (_customerGatewayDao.findByNameAndAccountId(name, accountId) != null) {
            throw new InvalidParameterValueException("The customer gateway with name " + name + " already existed!");
        }

        Boolean splitConnections = cmd.getSplitConnections();
        if (splitConnections == null) {
            splitConnections = false;
        }

        String ikeVersion = cmd.getIkeVersion();
        if (ikeVersion == null) {
            ikeVersion = "ike";
        }

        checkCustomerGatewayCidrList(peerCidrList);

        Site2SiteCustomerGatewayVO gw =
            new Site2SiteCustomerGatewayVO(name, accountId, owner.getDomainId(), gatewayIp, peerCidrList, ipsecPsk, ikePolicy, espPolicy, ikeLifetime, espLifetime, dpd, encap, splitConnections, ikeVersion);
        gw = _customerGatewayDao.persist(gw);
        CallContext.current().putContextParameter(Site2SiteCustomerGateway.class, gw.getUuid());
        return gw;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_CONNECTION_CREATE, eventDescription = "creating s2s vpn connection", create = true)
    public Site2SiteVpnConnection createVpnConnection(CreateVpnConnectionCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Account owner = _accountMgr.getAccount(cmd.getEntityOwnerId());

        //Verify that caller can perform actions in behalf of vpc owner
        _accountMgr.checkAccess(caller, null, false, owner);

        Long customerGatewayId = cmd.getCustomerGatewayId();
        Site2SiteCustomerGateway customerGateway = getAndValidateSite2SiteCustomerGateway(customerGatewayId, caller);

        Long vpnGatewayId = cmd.getVpnGatewayId();
        Site2SiteVpnGateway vpnGateway = getAndValidateSite2SiteVpnGateway(vpnGatewayId, caller);

        validateVpnConnectionOfTheRightAccount(customerGateway, vpnGateway);
        validateVpnConnectionDoesntExist(customerGateway, vpnGateway);
        validatePrerequisiteVpnGateway(vpnGateway);

        String[] cidrList = customerGateway.getGuestCidrList().split(",");

        // Remote sub nets cannot overlap VPC's sub net
        Vpc vpc = _vpcDao.findById(vpnGateway.getVpcId());
        String vpcCidr = vpc.getCidr();
        for (String cidr : cidrList) {
            if (NetUtils.isNetworksOverlap(vpcCidr, cidr)) {
                throw new InvalidParameterValueException(String.format("The subnets of customer gateway %s subnet %s is overlapped with VPC cidr %s!", customerGateway, cidr, vpcCidr));
            }
        }

        // We also need to check if the new connection's remote CIDR is overlapped with existed connections
        List<Site2SiteVpnConnectionVO> conns = _vpnConnectionDao.listByVpnGatewayId(vpnGatewayId);
        if (conns.size() >= _connLimit) {
            throw new InvalidParameterValueException("There are too many VPN connections with current VPN gateway! The limit is " + _connLimit);
        }
        for (Site2SiteVpnConnectionVO vc : conns) {
            if (vc == null) {
                continue;
            }
            Site2SiteCustomerGatewayVO gw = _customerGatewayDao.findById(vc.getCustomerGatewayId());
            String[] oldCidrList = gw.getGuestCidrList().split(",");
            for (String oldCidr : oldCidrList) {
                for (String cidr : cidrList) {
                    if (NetUtils.isNetworksOverlap(cidr, oldCidr)) {
                        throw new InvalidParameterValueException("The new connection's remote subnet " + cidr +
                            " is overlapped with existed VPN connection to customer gateway " + gw.getName() + "'s subnet " + oldCidr);
                    }
                }
            }
        }

        Site2SiteVpnConnectionVO conn = new Site2SiteVpnConnectionVO(owner.getAccountId(), owner.getDomainId(), vpnGatewayId, customerGatewayId, cmd.isPassive());
        conn.setState(State.Pending);
        if (cmd.getDisplay() != null) {
            conn.setDisplay(cmd.getDisplay());
        }

        _vpnConnectionDao.persist(conn);

        return conn;
    }

    private Site2SiteCustomerGateway getAndValidateSite2SiteCustomerGateway(Long customerGatewayId, Account caller) {
        Site2SiteCustomerGateway customerGateway = _customerGatewayDao.findById(customerGatewayId);
        if (customerGateway == null) {
            throw new InvalidParameterValueException(String.format("Unable to find specified Site to Site VPN customer gateway %s !", customerGatewayId));
        }
        _accountMgr.checkAccess(caller, null, false, customerGateway);
        return customerGateway;
    }

    private Site2SiteVpnGateway getAndValidateSite2SiteVpnGateway(Long vpnGatewayId, Account caller) {
        Site2SiteVpnGateway vpnGateway = _vpnGatewayDao.findById(vpnGatewayId);
        if (vpnGateway == null) {
            throw new InvalidParameterValueException(String.format("Unable to find specified Site to Site VPN gateway %s !", vpnGatewayId));
        }
        _accountMgr.checkAccess(caller, null, false, vpnGateway);
        return vpnGateway;
    }

    private void validateVpnConnectionOfTheRightAccount(Site2SiteCustomerGateway customerGateway, Site2SiteVpnGateway vpnGateway) {
        if (customerGateway.getAccountId() != vpnGateway.getAccountId() || customerGateway.getDomainId() != vpnGateway.getDomainId()) {
            throw new InvalidParameterValueException("VPN connection can only be established between same account's VPN gateway and customer gateway!");
        }
    }

    private void validateVpnConnectionDoesntExist(Site2SiteCustomerGateway customerGateway, Site2SiteVpnGateway vpnGateway) {
        if (_vpnConnectionDao.findByVpnGatewayIdAndCustomerGatewayId(vpnGateway.getId(), customerGateway.getId()) != null) {
            throw new InvalidParameterValueException(String.format("The vpn connection with customer gateway %s and vpn gateway %s already existed!", customerGateway, vpnGateway));
        }
    }

    private void validatePrerequisiteVpnGateway(Site2SiteVpnGateway vpnGateway) {
        // check if gateway has been defined on the VPC
        if (_vpnGatewayDao.findByVpcId(vpnGateway.getVpcId()) == null) {
            throw new InvalidParameterValueException("We can not create a VPN connection for a VPC that does not have a VPN gateway defined");
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_CONNECTION_CREATE, eventDescription = "starting s2s vpn connection", async = true)
    public Site2SiteVpnConnection startVpnConnection(long id) throws ResourceUnavailableException {
        Site2SiteVpnConnectionVO conn = _vpnConnectionDao.acquireInLockTable(id);
        if (conn == null) {
            throw new CloudRuntimeException("Unable to acquire lock for starting of VPN connection with ID " + id);
        }
        try {
            if (conn.getState() != State.Pending && conn.getState() != State.Disconnected) {
                throw new InvalidParameterValueException(
                    "Site to site VPN connection with specified connectionId not in correct state(pending or disconnected) to process!");
            }

            conn.setState(State.Pending);
            _vpnConnectionDao.persist(conn);

            final Site2SiteVpnGateway vpnGateway = _vpnGatewayDao.findById(conn.getVpnGatewayId());
            try {
                vpcManager.applyStaticRouteForVpcVpnIfNeeded(vpnGateway.getVpcId(), false);
            } catch (ResourceUnavailableException | CloudRuntimeException e) {
                logger.error("Unable to apply static routes for vpc " + vpnGateway.getVpcId() + "as part of start of VPN connection, due to " + e.getMessage());
            }

            boolean result = true;
            for (Site2SiteVpnServiceProvider element : _s2sProviders) {
                result = result & element.startSite2SiteVpn(conn);
            }

            if (result) {
                if (conn.isPassive()) {
                    conn.setState(State.Disconnected);
                } else {
                    conn.setState(State.Connecting);
                }
                _vpnConnectionDao.persist(conn);
                return conn;
            }
            conn.setState(State.Error);
            _vpnConnectionDao.persist(conn);
            throw new ResourceUnavailableException("Failed to apply site-to-site VPN", Site2SiteVpnConnection.class, id);
        } finally {
            _vpnConnectionDao.releaseFromLockTable(conn.getId());
        }
    }

    @Override
    public Site2SiteVpnGateway getVpnGateway(Long vpnGatewayId) {
        return _vpnGatewayDao.findById(vpnGatewayId);
    }

    @Override
    public Site2SiteCustomerGateway getCustomerGateway(Long customerGatewayId) {
        return _customerGatewayDao.findById(customerGatewayId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_CUSTOMER_GATEWAY_DELETE, eventDescription = "deleting s2s vpn customer gateway", create = true)
    public boolean deleteCustomerGateway(DeleteVpnCustomerGatewayCmd cmd) {
        CallContext.current().setEventDetails(" Id: " + cmd.getId());
        Account caller = CallContext.current().getCallingAccount();

        Long id = cmd.getId();
        Site2SiteCustomerGateway customerGateway = getAndValidateSite2SiteCustomerGateway(id, caller);

        return doDeleteCustomerGateway(customerGateway);
    }

    protected boolean doDeleteCustomerGateway(Site2SiteCustomerGateway gw) {
        long id = gw.getId();
        List<Site2SiteVpnConnectionVO> vpnConnections = _vpnConnectionDao.listByCustomerGatewayId(id);
        if (!CollectionUtils.isEmpty(vpnConnections)) {
            throw new InvalidParameterValueException(String.format("Unable to delete VPN customer gateway %s because there is still related VPN connections!", gw));
        }
        annotationDao.removeByEntityType(AnnotationService.EntityType.VPN_CUSTOMER_GATEWAY.name(), gw.getUuid());
        _customerGatewayDao.remove(id);
        return true;
    }

    protected void doDeleteVpnGateway(Site2SiteVpnGateway gw) {
        List<Site2SiteVpnConnectionVO> conns = _vpnConnectionDao.listByVpnGatewayId(gw.getId());
        if (!CollectionUtils.isEmpty(conns)) {
            throw new InvalidParameterValueException(String.format("Unable to delete VPN gateway %s because there is still related VPN connections!", gw));
        }
        _vpnGatewayDao.remove(gw.getId());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_GATEWAY_DELETE, eventDescription = "deleting s2s vpn gateway", async = true)
    public boolean deleteVpnGateway(DeleteVpnGatewayCmd cmd) {
        CallContext.current().setEventDetails(" Id: " + cmd.getId());
        Account caller = CallContext.current().getCallingAccount();

        Long id = cmd.getId();
        Site2SiteVpnGateway vpnGateway = getAndValidateSite2SiteVpnGateway(id, caller);

        doDeleteVpnGateway(vpnGateway);
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_CUSTOMER_GATEWAY_UPDATE, eventDescription = "update s2s vpn customer gateway", create = true)
    public Site2SiteCustomerGateway updateCustomerGateway(UpdateVpnCustomerGatewayCmd cmd) {
        CallContext.current().setEventDetails(" Id: " + cmd.getId());
        Account caller = CallContext.current().getCallingAccount();

        Long id = cmd.getId();
        Site2SiteCustomerGatewayVO gw = _customerGatewayDao.findById(id);
        if (gw == null) {
            throw new InvalidParameterValueException("Find to find customer gateway with id " + id);
        }
        _accountMgr.checkAccess(caller, null, false, gw);

        String name = cmd.getName();
        String gatewayIp = cmd.getGatewayIp();

        if (!NetUtils.isValidIp4(gatewayIp) && !NetUtils.verifyDomainName(gatewayIp)) {
            throw new InvalidParameterValueException("The customer gateway ip/Domain " + gatewayIp + " is invalid!");
        }
        if (name == null) {
            name = "VPN-" + gatewayIp;
        }
        String guestCidrList = cmd.getGuestCidrList();
        if (!NetUtils.isValidCidrList(guestCidrList)) {
            throw new InvalidParameterValueException("The customer gateway peer cidr list " + guestCidrList + " contains an invalid cidr!");
        }
        guestCidrList = NetUtils.getCleanIp4CidrList(guestCidrList);
        String ipsecPsk = cmd.getIpsecPsk();
        String ikePolicy = cmd.getIkePolicy();
        String espPolicy = cmd.getEspPolicy();
        if (!NetUtils.isValidS2SVpnPolicy("ike", ikePolicy)) {
            throw new InvalidParameterValueException("The customer gateway IKE policy" + ikePolicy + " is invalid!  Verify the required Diffie Hellman (DH) group is specified.");
        }
        if (!NetUtils.isValidS2SVpnPolicy("esp", espPolicy)) {
            throw new InvalidParameterValueException("The customer gateway ESP policy" + espPolicy + " is invalid!");
        }
        Long ikeLifetime = cmd.getIkeLifetime();
        if (ikeLifetime == null) {
            // Default value of lifetime is 1 day
            ikeLifetime = (long)86400;
        }
        if (ikeLifetime > 86400) {
            throw new InvalidParameterValueException("The IKE lifetime " + ikeLifetime + " of vpn connection is invalid!");
        }
        Long espLifetime = cmd.getEspLifetime();
        if (espLifetime == null) {
            // Default value of lifetime is 1 hour
            espLifetime = (long)3600;
        }
        if (espLifetime > 86400) {
            throw new InvalidParameterValueException("The ESP lifetime " + espLifetime + " of vpn connection is invalid!");
        }

        Boolean dpd = cmd.getDpd();
        if (dpd == null) {
            dpd = false;
        }

        Boolean encap = cmd.getEncap();
        if (encap == null) {
            encap = false;
        }

        Boolean splitConnections = cmd.getSplitConnections();

        String ikeVersion = cmd.getIkeVersion();

        checkCustomerGatewayCidrList(guestCidrList);

        long accountId = gw.getAccountId();
        Site2SiteCustomerGatewayVO existedGw = _customerGatewayDao.findByNameAndAccountId(name, accountId);
        if (existedGw != null && existedGw.getId() != gw.getId()) {
            throw new InvalidParameterValueException("The customer gateway with name " + name + " already existed!");
        }

        gw.setName(name);
        gw.setGatewayIp(gatewayIp);
        gw.setGuestCidrList(guestCidrList);
        gw.setIkePolicy(ikePolicy);
        gw.setEspPolicy(espPolicy);
        gw.setIpsecPsk(ipsecPsk);
        gw.setIkeLifetime(ikeLifetime);
        gw.setEspLifetime(espLifetime);
        gw.setDpd(dpd);
        gw.setEncap(encap);
        gw.setSplitConnections(splitConnections);
        if (ikeVersion != null) {
            gw.setIkeVersion(ikeVersion);
        }
        _customerGatewayDao.persist(gw);

        setupVpnConnection(caller, id);

        return gw;
    }

    private void setupVpnConnection(Account caller, Long vpnCustomerGwIp) {
        List<Site2SiteVpnConnectionVO> conns = _vpnConnectionDao.listByCustomerGatewayId(vpnCustomerGwIp);
        if (conns != null) {
            for (Site2SiteVpnConnection conn : conns) {
                try {
                    _accountMgr.checkAccess(caller, null, false, conn);
                } catch (PermissionDeniedException e) {
                    // Just don't restart this connection, as the user has no rights to it
                    // Maybe should issue a notification to the system?
                    logger.info("Site2SiteVpnManager:updateCustomerGateway() Not resetting VPN connection {} as user lacks permission", conn);
                    continue;
                }

                if (conn.getState() == State.Pending) {
                    // Vpn connection cannot be reset when the state is Pending
                    continue;
                }
                try {
                    if (conn.getState() == State.Connected || conn.getState() == State.Connecting || conn.getState() == State.Error) {
                        stopVpnConnection(conn.getId());
                    }
                    startVpnConnection(conn.getId());
                } catch (ResourceUnavailableException e) {
                    // Should never get here, as we are looping on the actual connections, but we must handle it regardless
                    logger.warn("Failed to update VPN connection");
                }
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_CONNECTION_DELETE, eventDescription = "deleting s2s vpn connection", create = true)
    public boolean deleteVpnConnection(DeleteVpnConnectionCmd cmd) throws ResourceUnavailableException {
        CallContext.current().setEventDetails(" Id: " + cmd.getId());
        Account caller = CallContext.current().getCallingAccount();

        Long id = cmd.getId();
        Site2SiteVpnConnectionVO conn = _vpnConnectionDao.findById(id);
        if (conn == null) {
            throw new InvalidParameterValueException("Fail to find site to site VPN connection " + id + " to delete!");
        }

        _accountMgr.checkAccess(caller, null, false, conn);

        if (conn.getState() != State.Pending) {
            stopVpnConnection(id);
        }

        conn.setState(State.Removed);
        _vpnConnectionDao.update(id, conn);

        final Site2SiteVpnGateway vpnGateway = _vpnGatewayDao.findById(conn.getVpnGatewayId());
        try {
            vpcManager.applyStaticRouteForVpcVpnIfNeeded(vpnGateway.getVpcId(), false);
        } catch (ResourceUnavailableException | CloudRuntimeException e) {
            logger.error("Unable to apply static routes for vpc " + vpnGateway.getVpcId() + "as part of deletion of VPN connection, due to " + e.getMessage());
        }

        _vpnConnectionDao.remove(id);

        return true;
    }

    @DB
    private void stopVpnConnection(Long id) throws ResourceUnavailableException {
        Site2SiteVpnConnectionVO conn = _vpnConnectionDao.acquireInLockTable(id);
        if (conn == null) {
            throw new CloudRuntimeException("Unable to acquire lock for stopping VPN connection with ID " + id);
        }
        try {
            if (conn.getState() == State.Pending) {
                throw new InvalidParameterValueException("Site to site VPN connection with specified id is currently Pending, unable to Disconnect!");
            }

            conn.setState(State.Disconnected);
            _vpnConnectionDao.persist(conn);

            boolean result = true;
            for (Site2SiteVpnServiceProvider element : _s2sProviders) {
                result = result & element.stopSite2SiteVpn(conn);
            }

            if (!result) {
                conn.setState(State.Error);
                _vpnConnectionDao.persist(conn);
                throw new ResourceUnavailableException("Failed to apply site-to-site VPN", Site2SiteVpnConnection.class, id);
            }
        } finally {
            _vpnConnectionDao.releaseFromLockTable(conn.getId());
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_CONNECTION_RESET, eventDescription = "reseting s2s vpn connection", create = true)
    public Site2SiteVpnConnection resetVpnConnection(ResetVpnConnectionCmd cmd) throws ResourceUnavailableException {
        CallContext.current().setEventDetails(" Id: " + cmd.getId());
        Account caller = CallContext.current().getCallingAccount();

        Long id = cmd.getId();
        Site2SiteVpnConnectionVO conn = _vpnConnectionDao.findById(id);
        if (conn == null) {
            throw new InvalidParameterValueException("Fail to find site to site VPN connection " + id + " to reset!");
        }
        _accountMgr.checkAccess(caller, null, false, conn);

        // Set vpn state to disconnected
        conn.setState(State.Disconnected);
        _vpnConnectionDao.persist(conn);

        // Stop and start the connection again
        stopVpnConnection(id);
        startVpnConnection(id);
        conn = _vpnConnectionDao.findById(id);
        return conn;
    }

    @Override
    public Pair<List<? extends Site2SiteCustomerGateway>, Integer> searchForCustomerGateways(ListVpnCustomerGatewaysCmd cmd) {
        Long id = cmd.getId();
        Long domainId = cmd.getDomainId();
        boolean isRecursive = cmd.isRecursive();
        String accountName = cmd.getAccountName();
        boolean listAll = cmd.listAll();
        long startIndex = cmd.getStartIndex();
        long pageSizeVal = cmd.getPageSizeVal();
        String keyword = cmd.getKeyword();

        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, isRecursive, null);
        _accountMgr.buildACLSearchParameters(caller, id, accountName, cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, listAll, false);
        domainId = domainIdRecursiveListProject.first();
        isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(Site2SiteCustomerGatewayVO.class, "id", false, startIndex, pageSizeVal);

        SearchBuilder<Site2SiteCustomerGatewayVO> sb = _customerGatewayDao.createSearchBuilder();
        _accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.LIKE);

        SearchCriteria<Site2SiteCustomerGatewayVO> sc = sb.create();
        _accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (id != null) {
            sc.setParameters("id", id);
        }
        if(keyword != null && !keyword.isEmpty())
        {
            sc.setParameters("name", "%" + keyword + "%");
        }

        Pair<List<Site2SiteCustomerGatewayVO>, Integer> result = _customerGatewayDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends Site2SiteVpnGateway>, Integer> searchForVpnGateways(ListVpnGatewaysCmd cmd) {
        Long id = cmd.getId();
        Long vpcId = cmd.getVpcId();
        Boolean display = cmd.getDisplay();

        Long domainId = cmd.getDomainId();
        boolean isRecursive = cmd.isRecursive();
        String accountName = cmd.getAccountName();
        boolean listAll = cmd.listAll();
        long startIndex = cmd.getStartIndex();
        long pageSizeVal = cmd.getPageSizeVal();

        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, isRecursive, null);
        _accountMgr.buildACLSearchParameters(caller, id, accountName, cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, listAll, false);
        domainId = domainIdRecursiveListProject.first();
        isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(Site2SiteVpnGatewayVO.class, "id", false, startIndex, pageSizeVal);

        SearchBuilder<Site2SiteVpnGatewayVO> sb = _vpnGatewayDao.createSearchBuilder();
        _accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("vpcId", sb.entity().getVpcId(), SearchCriteria.Op.EQ);
        sb.and("display", sb.entity().isDisplay(), SearchCriteria.Op.EQ);

        SearchCriteria<Site2SiteVpnGatewayVO> sc = sb.create();
        _accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (display != null) {
            sc.setParameters("display", display);
        }

        if (vpcId != null) {
            sc.addAnd("vpcId", SearchCriteria.Op.EQ, vpcId);
        }

        Pair<List<Site2SiteVpnGatewayVO>, Integer> result = _vpnGatewayDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends Site2SiteVpnConnection>, Integer> searchForVpnConnections(ListVpnConnectionsCmd cmd) {
        Long id = cmd.getId();
        Long vpcId = cmd.getVpcId();
        Boolean display = cmd.getDisplay();

        Long domainId = cmd.getDomainId();
        boolean isRecursive = cmd.isRecursive();
        String accountName = cmd.getAccountName();
        boolean listAll = cmd.listAll();
        long startIndex = cmd.getStartIndex();
        long pageSizeVal = cmd.getPageSizeVal();

        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, isRecursive, null);
        _accountMgr.buildACLSearchParameters(caller, id, accountName, cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, listAll, false);
        domainId = domainIdRecursiveListProject.first();
        isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(Site2SiteVpnConnectionVO.class, "id", false, startIndex, pageSizeVal);

        SearchBuilder<Site2SiteVpnConnectionVO> sb = _vpnConnectionDao.createSearchBuilder();
        _accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("display", sb.entity().isDisplay(), SearchCriteria.Op.EQ);

        if (vpcId != null) {
            SearchBuilder<Site2SiteVpnGatewayVO> gwSearch = _vpnGatewayDao.createSearchBuilder();
            gwSearch.and("vpcId", gwSearch.entity().getVpcId(), SearchCriteria.Op.EQ);
            sb.join("gwSearch", gwSearch, sb.entity().getVpnGatewayId(), gwSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        SearchCriteria<Site2SiteVpnConnectionVO> sc = sb.create();
        _accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (display != null) {
            sc.setParameters("display", display);
        }
        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (vpcId != null) {
            sc.setJoinParameters("gwSearch", "vpcId", vpcId);
        }

        Pair<List<Site2SiteVpnConnectionVO>, Integer> result = _vpnConnectionDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public boolean cleanupVpnConnectionByVpc(long vpcId) {
        List<Site2SiteVpnConnectionVO> conns = _vpnConnectionDao.listByVpcId(vpcId);
        for (Site2SiteVpnConnection conn : conns) {
            _vpnConnectionDao.remove(conn.getId());
        }
        return true;
    }

    @Override
    public boolean cleanupVpnGatewayByVpc(long vpcId) {
        Site2SiteVpnGatewayVO gw = _vpnGatewayDao.findByVpcId(vpcId);
        if (gw == null) {
            return true;
        }
        doDeleteVpnGateway(gw);
        return true;
    }

    @Override
    @DB
    public void markDisconnectVpnConnByVpc(long vpcId) {
        List<Site2SiteVpnConnectionVO> conns = _vpnConnectionDao.listByVpcId(vpcId);
        for (Site2SiteVpnConnectionVO conn : conns) {
            if (conn == null) {
                continue;
            }
            Site2SiteVpnConnectionVO lock = _vpnConnectionDao.acquireInLockTable(conn.getId());
            if (lock == null) {
                throw new CloudRuntimeException(String.format("Unable to acquire lock on vpn connection %s", conn));
            }
            try {
                if (conn.getState() == Site2SiteVpnConnection.State.Connected || conn.getState() == Site2SiteVpnConnection.State.Connecting) {
                    conn.setState(Site2SiteVpnConnection.State.Disconnected);
                    _vpnConnectionDao.persist(conn);
                }
            } finally {
                _vpnConnectionDao.releaseFromLockTable(lock.getId());
            }
        }
    }

    @Override
    public List<Site2SiteVpnConnectionVO> getConnectionsForRouter(DomainRouterVO router) {
        List<Site2SiteVpnConnectionVO> conns = new ArrayList<>();
        // One router for one VPC
        Long vpcId = router.getVpcId();
        if (router.getVpcId() == null) {
            return conns;
        }
        conns.addAll(_vpnConnectionDao.listByVpcId(vpcId));
        return conns;
    }

    @Override
    public boolean deleteCustomerGatewayByAccount(long accountId) {
        boolean result = true;
        List<Site2SiteCustomerGatewayVO> gws = _customerGatewayDao.listByAccountId(accountId);
        for (Site2SiteCustomerGatewayVO gw : gws) {
            result = result & doDeleteCustomerGateway(gw);
        }
        return result;
    }

    @Override
    public void reconnectDisconnectedVpnByVpc(Long vpcId) {
        List<Site2SiteVpnConnectionVO> conns = _vpnConnectionDao.listByVpcId(vpcId);
        for (Site2SiteVpnConnectionVO conn : conns) {
            if (conn == null) {
                continue;
            }
            if (conn.getState() == Site2SiteVpnConnection.State.Disconnected) {
                try {
                    startVpnConnection(conn.getId());
                } catch (ResourceUnavailableException e) {
                    Site2SiteCustomerGatewayVO gw = _customerGatewayDao.findById(conn.getCustomerGatewayId());
                    logger.warn("Site2SiteVpnManager: Fail to re-initiate VPN connection {} which connect to {}", conn, gw);
                }
            }
        }
    }

    public List<Site2SiteVpnServiceProvider> getS2sProviders() {
        return _s2sProviders;
    }

    @Inject
    public void setS2sProviders(List<Site2SiteVpnServiceProvider> s2sProviders) {
        _s2sProviders = s2sProviders;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_CONNECTION_UPDATE, eventDescription = "creating s2s vpn gateway", async = true)
    public Site2SiteVpnConnection updateVpnConnection(long id, String customId, Boolean forDisplay) {
        Account caller = CallContext.current().getCallingAccount();
        Site2SiteVpnConnectionVO conn = _vpnConnectionDao.findById(id);
        if (conn == null) {
            throw new InvalidParameterValueException("Fail to find site to site VPN connection " + id);
        }

        _accountMgr.checkAccess(caller, null, false, conn);
        if (customId != null) {
            conn.setUuid(customId);
        }

        if (forDisplay != null) {
            conn.setDisplay(forDisplay);
        }

        _vpnConnectionDao.update(id, conn);
        return _vpnConnectionDao.findById(id);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_S2S_VPN_GATEWAY_UPDATE, eventDescription = "updating s2s vpn gateway", async = true)
    public Site2SiteVpnGateway updateVpnGateway(Long id, String customId, Boolean forDisplay) {
        Account caller = CallContext.current().getCallingAccount();

        Site2SiteVpnGatewayVO vpnGateway = _vpnGatewayDao.findById(id);
        if (vpnGateway == null) {
            throw new InvalidParameterValueException("Fail to find vpn gateway with " + id);
        }

        _accountMgr.checkAccess(caller, null, false, vpnGateway);
        if (customId != null) {
            vpnGateway.setUuid(customId);
        }

        if (forDisplay != null) {
            vpnGateway.setDisplay(forDisplay);
        }

        _vpnGatewayDao.update(id, vpnGateway);
        return _vpnGatewayDao.findById(id);

    }
}
