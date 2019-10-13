/*
 * Copyright 2018 Qunar, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qunar.tc.qmq.processor;

import com.google.common.base.Strings;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qunar.tc.qmq.concurrent.ActorSystem;
import qunar.tc.qmq.consumer.ConsumerSequenceManager;
import qunar.tc.qmq.consumer.SubscriberStatusChecker;
import qunar.tc.qmq.monitor.QMon;
import qunar.tc.qmq.protocol.CommandCode;
import qunar.tc.qmq.protocol.Datagram;
import qunar.tc.qmq.protocol.RemotingCommand;
import qunar.tc.qmq.protocol.RemotingHeader;
import qunar.tc.qmq.protocol.consumer.AckRequest;
import qunar.tc.qmq.stats.BrokerStats;
import qunar.tc.qmq.util.RemotingBuilder;
import qunar.tc.qmq.utils.PayloadHolderUtils;

import java.util.concurrent.CompletableFuture;

/**
 * @author yunfeng.yang
 * @since 2017/7/27
 */
public class AckMessageProcessor extends AbstractRequestProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AckMessageProcessor.class);

    private final AckMessageWorker ackMessageWorker;
    private final SubscriberStatusChecker subscriberStatusChecker;

    public AckMessageProcessor(final ActorSystem actorSystem, final ConsumerSequenceManager consumerSequenceManager, final SubscriberStatusChecker subscriberStatusChecker) {
        this.ackMessageWorker = new AckMessageWorker(actorSystem, consumerSequenceManager);
        this.subscriberStatusChecker = subscriberStatusChecker;
    }

    @Override
    public CompletableFuture<Datagram> processRequest(ChannelHandlerContext ctx, RemotingCommand command) {
        final AckRequest ackRequest = deserializeAckRequest(command);

        BrokerStats.getInstance().getLastMinuteAckRequestCount().add(1);
        if (isInvalidRequest(ackRequest)) {
            final Datagram datagram = RemotingBuilder.buildEmptyResponseDatagram(CommandCode.BROKER_ERROR, command.getHeader());
            return CompletableFuture.completedFuture(datagram);
        }

        QMon.ackRequestCountInc(ackRequest.getPartitionName(), ackRequest.getConsumerGroup());
        subscriberStatusChecker.heartbeat(ackRequest.getPartitionName(), ackRequest.getConsumerGroup(), ackRequest.getConsumerId());

        if (isHeartbeatAck(ackRequest)) {
            final Datagram datagram = RemotingBuilder.buildEmptyResponseDatagram(CommandCode.SUCCESS, command.getHeader());
            return CompletableFuture.completedFuture(datagram);
        }

        monitorAckSize(ackRequest);
        ackMessageWorker.ack(new AckEntry(ackRequest, ctx, command.getHeader()));
        return null;
    }

    private AckRequest deserializeAckRequest(RemotingCommand command) {
        ByteBuf input = command.getBody();
        String partitionName = PayloadHolderUtils.readString(input);
        String consumerGroup = PayloadHolderUtils.readString(input);
        String consumerId = PayloadHolderUtils.readString(input);
        long pullStartOffset = input.readLong();
        long pullEndOffset = input.readLong();
        byte isExcludeConsume = AckRequest.UNSET;
        if (RemotingHeader.supportTags(command.getHeader().getVersion())) {
            isExcludeConsume = input.readByte();
        }

        return new AckRequest(
                partitionName,
                consumerGroup,
                consumerId,
                pullStartOffset,
                pullEndOffset,
                isExcludeConsume
        );
    }

    private boolean isInvalidRequest(AckRequest ackRequest) {
        if (Strings.isNullOrEmpty(ackRequest.getPartitionName())
                || Strings.isNullOrEmpty(ackRequest.getConsumerGroup())
                || Strings.isNullOrEmpty(ackRequest.getConsumerId())) {
            LOGGER.warn("receive error param ack request: {}", ackRequest);
            return true;
        }

        return false;
    }

    private boolean isHeartbeatAck(AckRequest ackRequest) {
        return ackRequest.getPullOffsetBegin() < 0;
    }

    private void monitorAckSize(AckRequest ackRequest) {
        final int ackSize = (int) (ackRequest.getPullOffsetLast() - ackRequest.getPullOffsetBegin() + 1);
        QMon.consumerAckCountInc(ackRequest.getPartitionName(), ackRequest.getConsumerGroup(), ackSize);
    }

    public static class AckEntry {
        private final String partitionName;
        private final String consumerGroup;
        private final String consumerId;
        private final long firstPullLogOffset;
        private final long lastPullLogOffset;
        private final long ackBegin;
        private final byte isExclusiveConsume;

        private final ChannelHandlerContext ctx;
        private final RemotingHeader requestHeader;

        AckEntry(AckRequest ackRequest, ChannelHandlerContext ctx, RemotingHeader requestHeader) {
            this.partitionName = ackRequest.getPartitionName();
            this.consumerGroup = ackRequest.getConsumerGroup();
            this.consumerId = ackRequest.getConsumerId();
            this.firstPullLogOffset = ackRequest.getPullOffsetBegin();
            this.lastPullLogOffset = ackRequest.getPullOffsetLast();
            this.ackBegin = System.currentTimeMillis();
            this.isExclusiveConsume = ackRequest.getIsExclusiveConsume();

            this.ctx = ctx;
            this.requestHeader = requestHeader;
        }

        public long getFirstPullLogOffset() {
            return firstPullLogOffset;
        }

        public long getLastPullLogOffset() {
            return lastPullLogOffset;
        }

        public String getPartitionName() {
            return partitionName;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public String getConsumerId() {
            return consumerId;
        }

        ChannelHandlerContext getCtx() {
            return ctx;
        }

        RemotingHeader getRequestHeader() {
            return requestHeader;
        }

        long getAckBegin() {
            return ackBegin;
        }

        public boolean isExclusiveConsume() {
            return isExclusiveConsume == 1;
        }

        @Override
        public String toString() {
            return "AckEntry{" +
                    "partitionName='" + partitionName + '\'' +
                    ", consumerGroup='" + consumerGroup + '\'' +
                    ", consumerId='" + consumerId + '\'' +
                    ", firstPullLogOffset=" + firstPullLogOffset +
                    ", lastPullLogOffset=" + lastPullLogOffset +
                    ", channel=" + ctx.channel() +
                    ", opaque=" + requestHeader.getOpaque() +
                    '}';
        }
    }
}
