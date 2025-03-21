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

package org.apache.cloudstack.kvm.ha;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckOnHostCommand;
import com.cloud.agent.api.CheckVMActivityOnStoragePoolCommand;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.ha.provider.ActivityCheckerInterface;
import org.apache.cloudstack.ha.provider.HACheckerException;
import org.apache.cloudstack.ha.provider.HealthCheckerInterface;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.commons.lang.ArrayUtils;

import javax.inject.Inject;
import java.util.ArrayList;
import org.joda.time.DateTime;
import java.util.HashMap;
import java.util.List;

public class KVMHostActivityChecker extends AdapterBase implements ActivityCheckerInterface<Host>, HealthCheckerInterface<Host> {

    @Inject
    private ClusterDao clusterDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private PrimaryDataStoreDao storagePool;
    @Inject
    private StorageManager storageManager;
    @Inject
    private ResourceManager resourceManager;

    @Override
    public boolean isActive(Host r, DateTime suspectTime) throws HACheckerException {
        try {
            return isVMActivityOnHost(r, suspectTime);
        } catch (HACheckerException e) {
            //Re-throwing the exception to avoid poluting the 'HACheckerException' already thrown
            throw e;
        } catch (Exception e){
            String message = String.format("Operation timed out, probably the %s is not reachable.", r.toString());
            logger.warn(message, e);
            throw new HACheckerException(message, e);
        }
    }

    @Override
    public boolean isHealthy(Host r) {
        return isAgentActive(r);
    }

    private boolean isAgentActive(Host agent) {
        if (agent.getHypervisorType() != Hypervisor.HypervisorType.KVM && agent.getHypervisorType() != Hypervisor.HypervisorType.LXC) {
            throw new IllegalStateException(String.format("Calling KVM investigator for non KVM Host of type [%s].", agent.getHypervisorType()));
        }
        Status hostStatus = Status.Unknown;
        Status neighbourStatus = Status.Unknown;
        final CheckOnHostCommand cmd = new CheckOnHostCommand(agent, HighAvailabilityManager.KvmHAFenceHostIfHeartbeatFailsOnStorage.value());
        try {
            logger.debug(String.format("Checking %s status...", agent.toString()));
            Answer answer = agentMgr.easySend(agent.getId(), cmd);
            if (answer != null) {
                hostStatus = answer.getResult() ? Status.Down : Status.Up;
                logger.debug(String.format("%s has the status [%s].", agent.toString(), hostStatus));

                if ( hostStatus == Status.Up ){
                    return true;
                }
            }
            else {
                logger.debug(String.format("Setting %s to \"Disconnected\" status.", agent.toString()));
                hostStatus = Status.Disconnected;
            }
        } catch (Exception e) {
            logger.warn(String.format("Failed to send command CheckOnHostCommand to %s.", agent.toString()), e);
        }

        List<HostVO> neighbors = resourceManager.listHostsInClusterByStatus(agent.getClusterId(), Status.Up);
        for (HostVO neighbor : neighbors) {
            if (neighbor.getId() == agent.getId() || (neighbor.getHypervisorType() != Hypervisor.HypervisorType.KVM && neighbor.getHypervisorType() != Hypervisor.HypervisorType.LXC)) {
                continue;
            }

            try {
                logger.debug(String.format("Investigating %s via neighbouring %s.", agent.toString(), neighbor.toString()));

                Answer answer = agentMgr.easySend(neighbor.getId(), cmd);
                if (answer != null) {
                    neighbourStatus = answer.getResult() ? Status.Down : Status.Up;

                    logger.debug(String.format("Neighbouring %s returned status [%s] for the investigated %s.", neighbor.toString(), neighbourStatus, agent.toString()));

                    if (neighbourStatus == Status.Up) {
                        break;
                    }
                } else {
                    logger.debug(String.format("Neighbouring %s is Disconnected.", neighbor.toString()));
                }
            } catch (Exception e) {
                logger.warn(String.format("Failed to send command CheckOnHostCommand to %s.", neighbor.toString()), e);
            }
        }
        if (neighbourStatus == Status.Up && (hostStatus == Status.Disconnected || hostStatus == Status.Down)) {
            hostStatus = Status.Disconnected;
        }
        if (neighbourStatus == Status.Down && (hostStatus == Status.Disconnected || hostStatus == Status.Down)) {
            hostStatus = Status.Down;
        }

        logger.debug(String.format("%s has the status [%s].", agent.toString(), hostStatus));

        return hostStatus == Status.Up;
    }

    private boolean isVMActivityOnHost(Host agent, DateTime suspectTime) throws HACheckerException {
        if (agent.getHypervisorType() != Hypervisor.HypervisorType.KVM && agent.getHypervisorType() != Hypervisor.HypervisorType.LXC) {
            throw new IllegalStateException(String.format("Calling KVM investigator for non KVM Host of type [%s].", agent.getHypervisorType()));
        }
        boolean activityStatus = true;
        HashMap<StoragePool, List<Volume>> poolVolMap = getVolumeUuidOnHost(agent);
        for (StoragePool pool : poolVolMap.keySet()) {
            activityStatus = verifyActivityOfStorageOnHost(poolVolMap, pool, agent, suspectTime, activityStatus);
            if (!activityStatus) {
                logger.warn("It seems that the storage pool [{}] does not have activity on {}.", pool, agent);
                break;
            }
        }

        return activityStatus;
    }

    protected boolean verifyActivityOfStorageOnHost(HashMap<StoragePool, List<Volume>> poolVolMap, StoragePool pool, Host agent, DateTime suspectTime, boolean activityStatus) throws HACheckerException, IllegalStateException {
        List<Volume> volume_list = poolVolMap.get(pool);
        final CheckVMActivityOnStoragePoolCommand cmd = new CheckVMActivityOnStoragePoolCommand(agent, pool, volume_list, suspectTime);

        logger.debug("Checking VM activity for {} on storage pool [{}].", agent.toString(), pool);
        try {
            Answer answer = storageManager.sendToPool(pool, getNeighbors(agent), cmd);

            if (answer != null) {
                activityStatus = !answer.getResult();
                logger.debug("{} {} activity on storage pool [{}]", agent.toString(), activityStatus ? "has" : "does not have", pool);
            } else {
                String message = String.format("Did not get a valid response for VM activity check for %s on storage pool [%s].", agent.toString(), pool);
                logger.debug(message);
                throw new IllegalStateException(message);
            }
        } catch (StorageUnavailableException e){
            String message = String.format("Storage [%s] is unavailable to do the check, probably the %s is not reachable.", pool, agent);
            logger.warn(message, e);
            throw new HACheckerException(message, e);
        }
        return activityStatus;
    }

    private HashMap<StoragePool, List<Volume>> getVolumeUuidOnHost(Host agent) {
        List<VMInstanceVO> vm_list = vmInstanceDao.listByHostId(agent.getId());
        List<VolumeVO> volume_list = new ArrayList<VolumeVO>();
        for (VirtualMachine vm : vm_list) {
            logger.debug("Retrieving volumes of VM [{}]...", vm);
            List<VolumeVO> vm_volume_list = volumeDao.findByInstance(vm.getId());
            volume_list.addAll(vm_volume_list);
        }

        HashMap<StoragePool, List<Volume>> poolVolMap = new HashMap<StoragePool, List<Volume>>();
        for (Volume vol : volume_list) {
            StoragePool sp = storagePool.findById(vol.getPoolId());
            logger.debug("Retrieving storage pool [{}] of volume [{}]...", sp, vol);
            if (!poolVolMap.containsKey(sp)) {
                List<Volume> list = new ArrayList<Volume>();
                list.add(vol);

                poolVolMap.put(sp, list);
            } else {
                poolVolMap.get(sp).add(vol);
            }
        }
        return poolVolMap;
    }

    public long[] getNeighbors(Host agent) {
        List<Long> neighbors = new ArrayList<Long>();
        List<HostVO> cluster_hosts = resourceManager.listHostsInClusterByStatus(agent.getClusterId(), Status.Up);
        logger.debug("Retrieving all \"Up\" hosts from cluster [{}]...", clusterDao.findById(agent.getClusterId()));
        for (HostVO host : cluster_hosts) {
            if (host.getId() == agent.getId() || (host.getHypervisorType() != Hypervisor.HypervisorType.KVM && host.getHypervisorType() != Hypervisor.HypervisorType.LXC)) {
                continue;
            }
            neighbors.add(host.getId());
        }
        return ArrayUtils.toPrimitive(neighbors.toArray(new Long[neighbors.size()]));
    }

}
