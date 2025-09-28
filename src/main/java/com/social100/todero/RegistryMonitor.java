package com.social100.todero;

import org.java_websocket.WebSocket;
import org.java_websocket.enums.ReadyState;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public class RegistryMonitor {

  private final WebSocketRegistry registry;
  private final ScheduledExecutorService scheduler;

  public RegistryMonitor(WebSocketRegistry registry, long intervalSeconds) {
    this.registry = registry;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "RegistryMonitor");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleAtFixedRate(this::printStatus, 0, intervalSeconds, TimeUnit.SECONDS);
  }

  public void triggerNow() {
    scheduler.execute(this::printStatus);
  }

  /** Manually queue a status print ASAP, with a reason tag. */
  public void triggerNow(String reason) {
    scheduler.execute(() -> printStatus(reason));
  }

  private void printStatus() {
    printStatus(null);
  }

  private void printStatus(String reason) {
    String header = "=== Registry status @ " + Instant.now() +
        (reason == null ? "" : " — " + reason) + " ===";
    System.out.println(header);

    Map<String, WebSocket> forward = registry.snapshotForward();
    Set<String> allClientIds = registry.getAllKnownClientIds();

    if (allClientIds.isEmpty()) {
      System.out.println("(no clients known)");
      return;
    }

    long active = 0;
    for (String clientId : allClientIds) {
      WebSocket conn = forward.get(clientId);

      String stateStr;
      String extra;
      boolean isActive;

      if (conn == null) {
        stateStr = "!INACTIVE!";
        extra = "no connection bound";
        isActive = false;
      } else {
        ReadyState rs = safeReadyState(conn);
        isActive = (rs == ReadyState.OPEN);
        stateStr = isActive ? "ACTIVE" : rs.name();

        SocketAddress remote = null;
        try { remote = conn.getRemoteSocketAddress(); } catch (Throwable ignore) {}
        extra = "remote=" + (remote != null ? remote : "unknown");
      }

      if (isActive) active++;
      System.out.println(" - " + clientId + " -> " + stateStr + "  [" + extra + "]");
    }

    long total = allClientIds.size();
    System.out.println("Totals: active=" + active + ", inactive=" + (total - active));
  }

  private ReadyState safeReadyState(WebSocket conn) {
    try {
      ReadyState rs = conn.getReadyState();
      return (rs != null) ? rs : (conn.isOpen() ? ReadyState.OPEN : ReadyState.CLOSED);
    } catch (Throwable t) {
      return ReadyState.CLOSED;
    }
  }

  public void shutdown() {
    scheduler.shutdownNow();
  }
}