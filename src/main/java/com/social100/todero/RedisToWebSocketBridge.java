package com.social100.todero;

import com.social100.todero.cmd.CmdArgs;
import redis.clients.jedis.*;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class RedisToWebSocketBridge {

    private final String redisHost;
    private final int redisPort;
    private final String streamName;
    private final String groupName;
    private final String consumerName;
    private final WebSocketRelayServer wsServer;

    private Thread listenerThread;
    private volatile boolean running = false;

    // timeouts: connect=1s, read/write (soTimeout)=3s
    final int CONNECT_TIMEOUT_MS = 1000;
    final int SO_TIMEOUT_MS = 3000;
    final int BLOCK_MS = 2000; // <= SO_TIMEOUT_MS; leave a cushion

    // Optional: set a client name to help debugging on the Redis side
    final String CLIENT_NAME = "redis-ws-bridge";

    JedisPool jedisPool;

    public RedisToWebSocketBridge(
            String redisHost,
            int redisPort,
            String streamName,
            String groupName,
            String consumerName,
            WebSocketRelayServer wsServer
    ) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.streamName = streamName;
        this.groupName = groupName;
        this.consumerName = consumerName;
        this.wsServer = wsServer;
    }

    public void start() {
        if (running) return;

        // Configure once (e.g., in constructor/init)
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        // reasonable pool limits; tweak as you like
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(0);

        // Harden the pool against stale sockets
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(30));
        poolConfig.setMaxWait(Duration.ofSeconds(2)); // don't wait forever for a resource

        jedisPool = new JedisPool(
                poolConfig,
                redisHost,
                redisPort,
                CONNECT_TIMEOUT_MS,
                SO_TIMEOUT_MS,
                null,          // password
                0,             // database
                CLIENT_NAME    // clientName
        );

        running = true;
        listenerThread = new Thread(this::listenLoop, "RedisToWebSocketBridge");
        listenerThread.start();
    }

    public void stop() {
        running = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
            try { listenerThread.join(1000); } catch (InterruptedException ignored) {}
        }
        if (jedisPool != null) {
            try { jedisPool.close(); } catch (Exception ignored) {}
        }
    }

    private void ensureGroupExists(Jedis jedis) {
        try {
            // Create group at the end of the stream if it doesn't exist.
            // Using LAST_ENTRY keeps new consumers from reprocessing backlog unless desired.
            jedis.xgroupCreate(streamName, groupName, StreamEntryID.LAST_ENTRY, true);
            System.out.println("[RedisToWebSocketBridge] Created consumer group '" + groupName + "' for stream '" + streamName + "'.");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) {
                // Group already exists—expected on restarts.
                System.out.println("[RedisToWebSocketBridge] Consumer group already exists, continuing.");
            } else {
                throw e;
            }
        }
    }

    private void listenLoop() {
        XReadGroupParams params = XReadGroupParams.xReadGroupParams()
                .count(16)
                .block(BLOCK_MS);

        Map<String, StreamEntryID> streams = java.util.Collections.singletonMap(
                streamName, StreamEntryID.UNRECEIVED_ENTRY
        );

        long backoffMs = 500;                 // start small
        final long MAX_BACKOFF_MS = 10_000;

        while (running) {
            try (Jedis jedis = jedisPool.getResource()) {
                // Force a real connection attempt now (bounded by CONNECT_TIMEOUT_MS)
                try {
                    jedis.ping();
                } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
                    System.err.println("[Bridge] PING failed (connect issue): " + e.getMessage());
                    throw e; // go to outer catch -> backoff -> retry
                }

                // Only ensure the group after we know the connection is healthy
                ensureGroupExists(jedis);

                System.out.println("[Bridge] Listening on " + streamName +
                        " as " + groupName + "/" + consumerName + " ...");

                // Reset backoff after a successful connect
                backoffMs = 500;

                while (running) {
                    List<Map.Entry<String, List<StreamEntry>>> entries;

                    try {
                        // This call will:
                        // - block up to BLOCK_MS for new entries
                        // - OR throw JedisConnectionException if socket read timeout elapses
                        entries = jedis.xreadGroup(groupName, consumerName, params, streams);

                    } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
                        // Broken connection (RST/FIN, DNS issues, read timeout, etc.) -> reconnect
                        if (isTimeout(e)) {
                            System.err.println("[Bridge] Redis read/connect timed out; will reconnect");
                        } else {
                            System.err.println("[Bridge] Redis connection error: " + e.getMessage());
                        }
                        break; // break inner loop -> close Jedis -> backoff & retry

                    } catch (redis.clients.jedis.exceptions.JedisDataException e) {
                        // e.g., group/stream issues (BUSYGROUP should be handled above)
                        System.err.println("[Bridge] Redis data error: " + e.getMessage());
                        break;

                    } catch (Exception e) {
                        // Any other runtime issue; log and decide policy
                        System.err.println("[Bridge] Unexpected error in xreadGroup: " + e);
                        break;
                    }

                    if (entries == null || entries.isEmpty()) {
                        // No messages within BLOCK_MS -> loop again, checks `running` promptly
                        continue;
                    }

                    // Process & ACK
                    for (Map.Entry<String, List<StreamEntry>> stream : entries) {
                        for (StreamEntry entry : stream.getValue()) {
                            String fromClientId = entry.getFields().get("from");
                            String clientId = entry.getFields().get("client_id");
                            String data = entry.getFields().get("data");

                            if (data == null) {
                                System.out.println("[Bridge] Skipping entry without 'data': " + entry.getID());
                                jedis.xack(streamName, groupName, entry.getID());
                                continue;
                            }

                            /*
                            if (fromClientId == null || fromClientId.isEmpty() || clientId == null || clientId.isEmpty()) {
                              System.err.println("[Bridge] No source/target clientId provided for message : '" + data + "'");
                              continue;
                            }*/

                            try {
                                String[] args = CmdArgs.sendMessageArgs(fromClientId, clientId, data);
                                wsServer.sendToClientId(clientId, args);
                                System.out.println("[Bridge] WS broadcast -> " + data);
                                jedis.xack(streamName, groupName, entry.getID());
                            } catch (Exception ex) {
                                System.err.println("[Bridge] WS send failed: " + ex.getMessage());
                                // POLICY: ACK or not?
                                // - If you need at-least-once to WS, DO NOT ACK here and add retry/DLQ.
                                // - If you prefer to avoid retries/PEL growth, ACK here.
                                jedis.xack(streamName, groupName, entry.getID());
                            }
                        }
                    }
                }

            } catch (Exception e) {
                if (!running) break;
                System.err.println("[Bridge] Error (outside read loop): " + e.getMessage());
            }

            // Exponential backoff with jitter before reconnecting
            if (!running) break;
            try {
                long jitter = ThreadLocalRandom.current().nextLong(100, 300);
                long sleepMs = Math.min(backoffMs, MAX_BACKOFF_MS) + jitter;
                System.err.println("[Bridge] Reconnecting in ~" + sleepMs + " ms");
                Thread.sleep(sleepMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }

        System.out.println("[Bridge] Stopped.");
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.SocketTimeoutException) return true;
            String name = c.getClass().getName();
            if (name.contains("ConnectTimeout") || name.contains("ReadTimeout")) return true;
        }
        return false;
    }

    // Optional: simple demo main
    public static void main(String[] args) {
        WebSocketRelayServer wsServer = new WebSocketRelayServer(4242);
        wsServer.start();

        RedisToWebSocketBridge bridge = new RedisToWebSocketBridge(
                "10.0.0.143",
                6379,
                "client-messages",
                "mygroup",
                "ws-consumer-1",
                wsServer
        );
        bridge.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bridge.stop();
            try { wsServer.stop(); } catch (Exception ignored) {}
        }));
    }
}