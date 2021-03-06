/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.wso2.carbon.transport.http.netty.sender;

import com.lmax.disruptor.RingBuffer;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonCallback;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.MessageProcessorException;
import org.wso2.carbon.messaging.TransportSender;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.common.HttpRoute;
import org.wso2.carbon.transport.http.netty.common.Util;
import org.wso2.carbon.transport.http.netty.common.disruptor.config.DisruptorConfig;
import org.wso2.carbon.transport.http.netty.common.disruptor.config.DisruptorFactory;
import org.wso2.carbon.transport.http.netty.common.ssl.SSLConfig;
import org.wso2.carbon.transport.http.netty.config.Parameter;
import org.wso2.carbon.transport.http.netty.config.SenderConfiguration;
import org.wso2.carbon.transport.http.netty.listener.SourceHandler;
import org.wso2.carbon.transport.http.netty.sender.channel.BootstrapConfiguration;
import org.wso2.carbon.transport.http.netty.sender.channel.ChannelUtils;
import org.wso2.carbon.transport.http.netty.sender.channel.TargetChannel;
import org.wso2.carbon.transport.http.netty.sender.channel.pool.ConnectionManager;

import java.util.HashMap;
import java.util.Map;

/**
 * A class creates connections with BE and send messages.
 */
public class HTTPSender implements TransportSender {

    private static final Logger log = LoggerFactory.getLogger(HTTPSender.class);
    private String id;
    private ConnectionManager connectionManager;
    private SenderConfiguration senderConfiguration;

    public HTTPSender(SenderConfiguration senderConfiguration) {
        this.id = senderConfiguration.getId();
        this.senderConfiguration = senderConfiguration;
        Map<String, String> paramMap = new HashMap();
        if (senderConfiguration.getParameters() != null && !senderConfiguration.getParameters().isEmpty()) {
            for (Parameter parameter : senderConfiguration.getParameters()) {
                paramMap.put(parameter.getName(), parameter.getValue());
            }

        }
        DisruptorConfig disruptorConfig = new DisruptorConfig(
                paramMap.get(org.wso2.carbon.transport.http.netty.common.Constants.DISRUPTOR_BUFFER_SIZE),
                paramMap.get(org.wso2.carbon.transport.http.netty.common.Constants.DISRUPTOR_COUNT),
                paramMap.get(org.wso2.carbon.transport.http.netty.common.Constants.DISRUPTOR_EVENT_HANDLER_COUNT),
                paramMap.get(org.wso2.carbon.transport.http.netty.common.Constants.WAIT_STRATEGY), Boolean.parseBoolean(
                org.wso2.carbon.transport.http.netty.common.Constants.SHARE_DISRUPTOR_WITH_OUTBOUND),
                paramMap.get(org.wso2.carbon.transport.http.netty.common.Constants.
                        DISRUPTOR_CONSUMER_EXTERNAL_WORKER_POOL));
        // TODO: Need to have a proper service
        DisruptorFactory.createDisruptors(DisruptorFactory.DisruptorType.OUTBOUND, disruptorConfig);
        BootstrapConfiguration.createBootStrapConfiguration(paramMap);
        this.connectionManager = ConnectionManager.getInstance(paramMap);
    }

    @Override
    public boolean send(CarbonMessage msg, CarbonCallback callback) throws MessageProcessorException {

        final HttpRequest httpRequest = Util.createHttpRequest(msg);

        if (msg.getProperty(Constants.HOST) == null) {
            log.debug("Cannot find property HOST hence using default as " + "localhost"
                    + " Please specify remote host as 'HOST' in carbon message property ");
            msg.setProperty(Constants.HOST, "localhost");
        }
        if (msg.getProperty(Constants.PORT) == null) {
            SSLConfig sslConfig = senderConfiguration.getSslConfig();
            int port = 80;
            if (sslConfig != null) {
                port = 443;
            }
            log.debug("Cannot find property PORT hence using default as " + port
                    + " Please specify remote host as 'PORT' in carbon message property ");
            msg.setProperty(Constants.PORT, port);
        }

        final HttpRoute route = new HttpRoute((String) msg.getProperty(Constants.HOST),
                (Integer) msg.getProperty(Constants.PORT));

        SourceHandler srcHandler = (SourceHandler) msg.getProperty(Constants.SRC_HNDLR);
        if (srcHandler == null) {
            log.debug("Cannot find property SRC_HNDLR hence Sender uses as standalone.If you need to use sender with"
                    + "listener side please copy property SRC_HNDLR from incoming message");
        }

        RingBuffer ringBuffer = (RingBuffer) msg.getProperty(Constants.DISRUPTOR);

        Boolean enableDisruptor = false;

        if (msg.getProperty(org.wso2.carbon.transport.http.netty.common.Constants.
                IS_DISRUPTOR_ENABLE) != null) {
            enableDisruptor = (Boolean) msg.getProperty(org.wso2.carbon.transport.http.netty.common.Constants.
                    IS_DISRUPTOR_ENABLE);
        } else {
            log.debug("Cannot find property 'enable.disruptor   hence using worker pool as thread model "
                    + "for client side if you neeed to sender side compatible with Listener side please copy values"
                    + "SRC_HANDLER property , enable.disruptor properties from ");
        }

        if (ringBuffer == null && enableDisruptor) {
            DisruptorConfig disruptorConfig = DisruptorFactory.
                    getDisruptorConfig(DisruptorFactory.DisruptorType.OUTBOUND);
            ringBuffer = disruptorConfig.getDisruptor();
        } else if (!enableDisruptor) {
            senderConfiguration.setDisruptorOn(false);
        }

        Channel outboundChannel = null;
        try {
            TargetChannel targetChannel = connectionManager
                    .getTargetChannel(route, srcHandler, senderConfiguration, httpRequest, msg, callback, ringBuffer);
            if (targetChannel != null) {
                outboundChannel = targetChannel.getChannel();
                targetChannel.getTargetHandler().setCallback(callback);
                targetChannel.getTargetHandler().setIncomingMsg(msg);
                targetChannel.getTargetHandler().setRingBuffer(ringBuffer);
                targetChannel.getTargetHandler().setTargetChannel(targetChannel);
                targetChannel.getTargetHandler().setConnectionManager(connectionManager);
                boolean written = ChannelUtils.writeContent(outboundChannel, httpRequest, msg);
                if (written) {
                    targetChannel.setRequestWritten(true);
                }
            }
        } catch (Exception failedCause) {
            throw new MessageProcessorException(failedCause.getMessage(), failedCause);
        }

        return false;
    }

    @Override
    public String getId() {
        return id;
    }

}
