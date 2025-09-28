package com.social100.todero;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;

public class WebSocketRegistry {

  // clientId = "userId:deviceId"
  private final Map<String, WebSocket> serversForwardMap = new ConcurrentHashMap<>(); // clientId -> conn
  private final Map<WebSocket, String> serversReverseMap = new ConcurrentHashMap<>(); // conn -> clientId
  private final Map<String, Set<String>> devicesIdsForUserId = new ConcurrentHashMap<>(); // userId -> {deviceId,...}

  private final Object lock = new Object();

  public void addByClientId(String clientId, WebSocket conn) {
    ClientId parts = parseClientId(clientId);

    synchronized (lock) {
      // If this conn was previously registered, unlink the old clientId and device mapping
      String oldClientIdForConn = serversReverseMap.put(conn, clientId);
      if (oldClientIdForConn != null && !oldClientIdForConn.equals(clientId)) {
        serversForwardMap.remove(oldClientIdForConn, conn);
        ClientId old = parseClientId(oldClientIdForConn);
        removeDeviceUnsafe(old.userId(), old.deviceId()); // only if present
      }

      // If this clientId was bound to a different conn, unlink that reverse mapping
      WebSocket oldConnForClient = serversForwardMap.put(clientId, conn);
      if (oldConnForClient != null && oldConnForClient != conn) {
        serversReverseMap.remove(oldConnForClient, clientId);
      }

      // Add device to the user's device set
      devicesIdsForUserId
          .computeIfAbsent(parts.userId(), k -> ConcurrentHashMap.newKeySet())
          .add(parts.deviceId());
    }
  }

  public void removeByConnection(WebSocket conn) {
    synchronized (lock) {
      String clientId = serversReverseMap.remove(conn);
      if (clientId == null) return;

      serversForwardMap.remove(clientId, conn);

      ClientId parts = parseClientId(clientId);
      removeDeviceUnsafe(parts.userId(), parts.deviceId());
    }
  }

  // ------- Optional helpers -------

  public WebSocket getConnection(String clientId) {
    return serversForwardMap.get(clientId);
  }

  public String getClientId(WebSocket conn) {
    return serversReverseMap.get(conn);
  }

  /** Returns an immutable snapshot of deviceIds for this user (empty if none). */
  public Set<String> getDeviceIdsForUser(String userId) {
    Set<String> set = devicesIdsForUserId.get(userId);
    return set == null ? Set.of() : Collections.unmodifiableSet(set);
  }

  /** Remove a specific device for a user (by explicit userId/deviceId). */
  public void removeDevice(String userId, String deviceId) {
    synchronized (lock) {
      removeDeviceUnsafe(userId, deviceId);
    }
  }

  /** Remove all devices for a userId (and any forward/reverse connections tied to them). */
  public void removeAllDevicesForUser(String userId) {
    synchronized (lock) {
      Set<String> set = devicesIdsForUserId.remove(userId);
      if (set == null || set.isEmpty()) return;

      // Clean forward/reverse for each (userId:deviceId)
      for (String deviceId : set) {
        String clientId = userId + ":" + deviceId;
        WebSocket conn = serversForwardMap.remove(clientId);
        if (conn != null) {
          serversReverseMap.remove(conn, clientId);
        }
      }
    }
  }

  public Map<String, org.java_websocket.WebSocket> snapshotForward() {
    // clientId -> conn
    return java.util.Map.copyOf(serversForwardMap);
  }

  public java.util.Set<String> getAllKnownClientIds() {
    // Union of user->devices and forward keys, in case either side is ahead
    java.util.Set<String> ids = new java.util.HashSet<>(serversForwardMap.keySet());
    devicesIdsForUserId.forEach((user, devs) -> devs.forEach(d -> ids.add(user + ":" + d)));
    return java.util.Set.copyOf(ids);
  }

  // ------- Internals -------

  private void removeDeviceUnsafe(String userId, String deviceId) {
    Set<String> set = devicesIdsForUserId.get(userId);
    if (set == null) return;
    set.remove(deviceId);
    if (set.isEmpty()) {
      devicesIdsForUserId.remove(userId, Set.of()); // fast path won't work; remove by key if now empty
      // Re-check emptiness safely
      Set<String> current = devicesIdsForUserId.get(userId);
      if (current != null && current.isEmpty()) {
        devicesIdsForUserId.remove(userId, current);
      }
    }
  }

  private ClientId parseClientId(String clientId) {
    if (clientId == null || !clientId.contains(":")) {
      throw new IllegalArgumentException("Invalid clientId format: " + clientId);
    }
    String[] parts = clientId.split(":", 2);
    return new ClientId(parts[0], parts[1]);
  }

  private record ClientId(String userId, String deviceId) {}
}