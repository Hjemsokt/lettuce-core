package com.lambdaworks.redis.cluster;

import java.util.Queue;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.lambdaworks.redis.ClientOptions;
import com.lambdaworks.redis.RedisChannelWriter;
import com.lambdaworks.redis.RedisException;
import com.lambdaworks.redis.protocol.CommandHandler;
import com.lambdaworks.redis.protocol.ConnectionWatchdog;
import com.lambdaworks.redis.protocol.RedisCommand;

import io.netty.channel.ChannelHandler;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 */
@ChannelHandler.Sharable
class ClusterNodeCommandHandler<K, V> extends CommandHandler<K, V> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ClusterNodeCommandHandler.class);
    private static final Set<LifecycleState> CHANNEL_OPEN_STATES = ImmutableSet.of(LifecycleState.ACTIVATING,
            LifecycleState.ACTIVE, LifecycleState.CONNECTED);

    private final RedisChannelWriter<K, V> clusterChannelWriter;

    /**
     * Initialize a new instance that handles commands from the supplied queue.
     * 
     * @param clientOptions client options for this connection
     * @param queue The command queue
     * @param clusterChannelWriter top-most channel writer.
     */
    public ClusterNodeCommandHandler(ClientOptions clientOptions, Queue<RedisCommand<K, V, ?>> queue,
            RedisChannelWriter<K, V> clusterChannelWriter) {
        super(clientOptions, queue);
        this.clusterChannelWriter = clusterChannelWriter;
    }

    /**
     * Prepare the closing of the channel.
     */
    public void prepareClose() {
        if (channel != null) {
            ConnectionWatchdog connectionWatchdog = channel.pipeline().get(ConnectionWatchdog.class);
            if (connectionWatchdog != null) {
                connectionWatchdog.setReconnectSuspended(true);
            }
        }
    }

    /**
     * Move queued and buffered commands from the inactive connection to the master command writer. This is done only if the
     * current connection is disconnected and auto-reconnect is enabled (command-retries). If the connection would be open, we
     * could get into a race that the commands we're moving are right now in processing. Alive connections can handle redirects
     * and retries on their own.
     */
    @Override
    public void close() {

        logger.debug("{} close()", logPrefix());

        if (clusterChannelWriter != null) {
            if (isAutoReconnect() && !CHANNEL_OPEN_STATES.contains(getState())) {
                for (RedisCommand<K, V, ?> queuedCommand : queue) {
                    try {
                        clusterChannelWriter.write(queuedCommand);
                    } catch (RedisException e) {
                        queuedCommand.setException(e);
                        queuedCommand.complete();
                    }
                }

                queue.clear();
            }

            for (RedisCommand<K, V, ?> queuedCommand : commandBuffer) {
                try {
                    clusterChannelWriter.write(queuedCommand);
                } catch (RedisException e) {
                    queuedCommand.setException(e);
                    queuedCommand.complete();
                }
            }

            commandBuffer.clear();
        }

        super.close();
    }

    public boolean isAutoReconnect() {
        return clientOptions.isAutoReconnect();
    }

    public boolean isQueueEmpty() {
        if (queue.isEmpty() && commandBuffer.isEmpty()) {
            return true;
        }

        return false;
    }

}
