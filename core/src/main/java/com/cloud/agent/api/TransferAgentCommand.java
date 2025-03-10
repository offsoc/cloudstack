//
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
//

package com.cloud.agent.api;

import com.cloud.host.Status.Event;

public class TransferAgentCommand extends Command {
    protected long agentId;
    protected long futureOwner;
    protected long currentOwner;
    protected boolean isConnectionTransfer;
    Event event;

    protected TransferAgentCommand() {
    }

    public TransferAgentCommand(long agentId, long currentOwner, long futureOwner, Event event) {
        this.agentId = agentId;
        this.currentOwner = currentOwner;
        this.futureOwner = futureOwner;
        this.event = event;
    }

    public TransferAgentCommand(long agentId, long currentOwner, long futureOwner, Event event, boolean isConnectionTransfer) {
        this(agentId, currentOwner, futureOwner, event);
        this.isConnectionTransfer = isConnectionTransfer;
    }

    public long getAgentId() {
        return agentId;
    }

    public long getFutureOwner() {
        return futureOwner;
    }

    public Event getEvent() {
        return event;
    }

    public long getCurrentOwner() {
        return currentOwner;
    }

    public boolean isConnectionTransfer() {
        return isConnectionTransfer;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
