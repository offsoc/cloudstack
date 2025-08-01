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
package com.cloud.resource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.cloud.utils.fsm.StateMachine;

public enum ResourceState {
    Creating,
    Enabled,
    Disabled,
    ErrorInPrepareForMaintenance,
    PrepareForMaintenance,
    ErrorInMaintenance,
    Maintenance,
    Error,
    Degraded;

    public enum Event {
        InternalCreated("Resource is created"),
        Enable("Admin enables"),
        Disable("Admin disables"),
        AdminAskMaintenance("Admin asks to enter maintenance"),
        AdminCancelMaintenance("Admin asks to cancel maintenance"),
        InternalEnterMaintenance("Resource enters maintenance"),
        UpdatePassword("Admin updates password of host"),
        UnableToMigrate("Migration of VM failed, such as from scheduled HAWork"),
        UnableToMaintain("Management server has exhausted all legal operations and attempts to put into maintenance has failed"),
        ErrorsCorrected("Errors were corrected on a resource attempting to enter maintenance but encountered errors"),
        Error("An internal error happened"),
        DeleteHost("Admin delete a host"),
        DeclareHostDegraded("Admin declares host as Degraded"),
        EnableDegradedHost("Admin puts Degraded host into Enabled"),

        /*
         * Below events don't cause resource state to change, they are merely
         * for ClusterManager propagating event from one mgmt server to another
         */
        Unmanaged("Umanage a cluster");

        private final String comment;

        private Event(String comment) {
            this.comment = comment;
        }

        public String getDescription() {
            return this.comment;
        }

        public static Event toEvent(String e) {
            if (Enable.toString().equals(e)) {
                return Enable;
            } else if (Disable.toString().equals(e)) {
                return Disable;
            }

            return null;
        }
    }

    public static List<ResourceState> s_maintenanceStates = List.of(ResourceState.Maintenance,
            ResourceState.ErrorInMaintenance, ResourceState.PrepareForMaintenance,
            ResourceState.ErrorInPrepareForMaintenance);

    public ResourceState getNextState(Event a) {
        return s_fsm.getNextState(this, a);
    }

    public ResourceState[] getFromStates(Event a) {
        List<ResourceState> from = s_fsm.getFromStates(this, a);
        return from.toArray(new ResourceState[from.size()]);
    }

    public Set<Event> getPossibleEvents() {
        return s_fsm.getPossibleEvents(this);
    }

    public static String[] toString(ResourceState... states) {
        String[] strs = new String[states.length];
        for (int i = 0; i < states.length; i++) {
            strs[i] = states[i].toString();
        }
        return strs;
    }

    public static boolean isMaintenanceState(ResourceState state) {
        return s_maintenanceStates.contains(state);
    }

    public static boolean canAttemptMaintenance(ResourceState state) {
        return !Arrays.asList(ResourceState.Maintenance, ResourceState.PrepareForMaintenance,
                ResourceState.ErrorInPrepareForMaintenance).contains(state);
    }

    protected static final StateMachine<ResourceState, Event> s_fsm = new StateMachine<ResourceState, Event>();
    static {
        s_fsm.addTransition(null, Event.InternalCreated, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.Creating, Event.InternalCreated, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.Creating, Event.Error, ResourceState.Error);
        s_fsm.addTransition(ResourceState.Enabled, Event.Enable, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.Enabled, Event.InternalCreated, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.Enabled, Event.Disable, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.Enabled, Event.AdminAskMaintenance, ResourceState.PrepareForMaintenance);
        s_fsm.addTransition(ResourceState.Enabled, Event.DeclareHostDegraded, ResourceState.Degraded);
        s_fsm.addTransition(ResourceState.Enabled, Event.InternalEnterMaintenance, ResourceState.Maintenance);
        s_fsm.addTransition(ResourceState.Enabled, Event.DeleteHost, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.Disabled, Event.Enable, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.Disabled, Event.Disable, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.Disabled, Event.InternalCreated, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.Disabled, Event.DeclareHostDegraded, ResourceState.Degraded);
        s_fsm.addTransition(ResourceState.PrepareForMaintenance, Event.InternalEnterMaintenance, ResourceState.Maintenance);
        s_fsm.addTransition(ResourceState.PrepareForMaintenance, Event.AdminCancelMaintenance, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.PrepareForMaintenance, Event.UnableToMigrate, ResourceState.ErrorInPrepareForMaintenance);
        s_fsm.addTransition(ResourceState.PrepareForMaintenance, Event.UnableToMaintain, ResourceState.ErrorInMaintenance);
        s_fsm.addTransition(ResourceState.PrepareForMaintenance, Event.InternalCreated, ResourceState.PrepareForMaintenance);
        s_fsm.addTransition(ResourceState.Maintenance, Event.AdminCancelMaintenance, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.Maintenance, Event.InternalCreated, ResourceState.Maintenance);
        s_fsm.addTransition(ResourceState.Maintenance, Event.DeleteHost, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.Maintenance, Event.DeclareHostDegraded, ResourceState.Degraded);
        s_fsm.addTransition(ResourceState.ErrorInPrepareForMaintenance, Event.InternalCreated, ResourceState.ErrorInPrepareForMaintenance);
        s_fsm.addTransition(ResourceState.ErrorInPrepareForMaintenance, Event.Disable, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.ErrorInPrepareForMaintenance, Event.DeleteHost, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.ErrorInPrepareForMaintenance, Event.InternalEnterMaintenance, ResourceState.Maintenance);
        s_fsm.addTransition(ResourceState.ErrorInPrepareForMaintenance, Event.AdminCancelMaintenance, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.ErrorInPrepareForMaintenance, Event.UnableToMigrate, ResourceState.ErrorInPrepareForMaintenance);
        s_fsm.addTransition(ResourceState.ErrorInPrepareForMaintenance, Event.UnableToMaintain, ResourceState.ErrorInMaintenance);
        s_fsm.addTransition(ResourceState.ErrorInPrepareForMaintenance, Event.ErrorsCorrected, ResourceState.PrepareForMaintenance);
        s_fsm.addTransition(ResourceState.ErrorInMaintenance, Event.InternalCreated, ResourceState.ErrorInMaintenance);
        s_fsm.addTransition(ResourceState.ErrorInMaintenance, Event.AdminAskMaintenance, ResourceState.PrepareForMaintenance);
        s_fsm.addTransition(ResourceState.ErrorInMaintenance, Event.Disable, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.ErrorInMaintenance, Event.DeleteHost, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.ErrorInMaintenance, Event.AdminCancelMaintenance, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.Error, Event.InternalCreated, ResourceState.Error);
        s_fsm.addTransition(ResourceState.Disabled, Event.DeleteHost, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.Degraded, Event.DeleteHost, ResourceState.Disabled);
        s_fsm.addTransition(ResourceState.Degraded, Event.EnableDegradedHost, ResourceState.Enabled);
        s_fsm.addTransition(ResourceState.Degraded, Event.AdminAskMaintenance, ResourceState.Maintenance);
    }
}
