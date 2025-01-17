/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.impl.protocol.task.management;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.MCPromoteToCPMemberCodec;
import com.hazelcast.client.impl.protocol.codec.MCPromoteToCPMemberCodec.RequestParameters;
import com.hazelcast.client.impl.protocol.task.AbstractAsyncMessageTask;
import com.hazelcast.cp.CPSubsystemManagementService;
import com.hazelcast.instance.impl.Node;
import com.hazelcast.internal.management.ManagementCenterService;
import com.hazelcast.internal.nio.Connection;

import java.security.Permission;
import java.util.concurrent.CompletableFuture;

public class ResetCPSubsystemMessageTask extends AbstractAsyncMessageTask<RequestParameters, Void> {

    public ResetCPSubsystemMessageTask(ClientMessage clientMessage, Node node, Connection connection) {
        super(clientMessage, node, connection);
    }

    @Override
    protected CompletableFuture<Void> processInternal() {
        CPSubsystemManagementService cpService =
                nodeEngine.getHazelcastInstance().getCPSubsystem().getCPSubsystemManagementService();
        return cpService.reset().toCompletableFuture();
    }

    @Override
    protected RequestParameters decodeClientMessage(ClientMessage clientMessage) {
        return MCPromoteToCPMemberCodec.decodeRequest(clientMessage);
    }

    @Override
    protected ClientMessage encodeResponse(Object response) {
        return MCPromoteToCPMemberCodec.encodeResponse();
    }

    @Override
    public String getServiceName() {
        return ManagementCenterService.SERVICE_NAME;
    }

    @Override
    public Permission getRequiredPermission() {
        return null;
    }

    @Override
    public String getDistributedObjectName() {
        return null;
    }

    @Override
    public String getMethodName() {
        return "resetCPSubsystem";
    }

    @Override
    public Object[] getParameters() {
        return new Object[0];
    }
}
