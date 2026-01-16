package com.social100.todero;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class RedisPublisher implements AutoCloseable {
  private final String streamName;
  private final JedisPool pool;

  // timeouts: connect=1s, read/write=3s — match your bridge defaults
  private static final int CONNECT_TIMEOUT_MS = 1000;
  private static final int SO_TIMEOUT_MS = 3000;
  private static final String CLIENT_NAME = "ws-command-publisher";

  public RedisPublisher(String host, int port, String streamName) {
    this.streamName = streamName;

    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(8);
    poolConfig.setMaxIdle(8);
    poolConfig.setMinIdle(0);
    poolConfig.setTestOnBorrow(true);
    poolConfig.setTestWhileIdle(true);
    poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
    poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(30));
    poolConfig.setMaxWait(Duration.ofSeconds(2));

    this.pool = new JedisPool(
        poolConfig, host, port,
        CONNECT_TIMEOUT_MS, SO_TIMEOUT_MS,
        null, 0, CLIENT_NAME
    );
  }

  /**
   * Publish to the stream with the fields your bridge expects.
   * Optionally include extra fields if you pass them.
   */
  public String publish(String fromClientId, String clientId, String data, Map<String, String> extraFields) {
    try (Jedis jedis = pool.getResource()) {
      // optional immediate connectivity check; cheap + helps fail fast
      jedis.ping();

      Map<String, String> fields = new HashMap<>();
      if (fromClientId != null) fields.put("from", fromClientId);
      if (clientId != null) fields.put("client_id", clientId);
      if (data != null)     fields.put("data", data);
      if (extraFields != null) fields.putAll(extraFields);

      // keep stream from growing unbounded (approximate trim)
      XAddParams params = new XAddParams().approximateTrimming().maxLen(10_000);
      StreamEntryID id = jedis.xadd(streamName, fields, params);
      return id.toString();
    }
  }

  public String publish(String fromClientId, String clientId, String data) {
    return publish(fromClientId, clientId, data, null);
  }

  @Override public void close() {
    try { pool.close(); } catch (Exception ignored) {}
  }
}