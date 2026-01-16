package com.social100.todero;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Arrays;

public class WebSocketRelayServer extends WebSocketServer {

  private final WebSocketServerCommands webSocketServerCommands = new WebSocketServerCommands();
  private final AuthClient authClient = new AuthClient();

  public WebSocketRelayServer(int port) {
    super(new InetSocketAddress("0.0.0.0", port));
    System.out.println("WebSocketRelayServer listening on port " + port);
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    String resourceDescriptor = handshake.getResourceDescriptor();
    if (!"/ws".equals(resourceDescriptor)) {
      System.out.println("Rejected client with invalid path: " + resourceDescriptor);
      conn.close(1002, "Invalid path");
      return;
    }

    String authHeader = handshake.getFieldValue("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      System.out.println("Rejected client: missing or invalid Authorization header");
      conn.close(1008, "Missing/invalid Authorization header");
      return;
    }

    // 🟢 Delegate full validation/registration to AuthClient
    String token = authHeader.substring("Bearer ".length());
    boolean ok = authClient.validateAndRegister(token, conn);

    if (!ok) {
      // validateAndRegister already handled the close
      return;
    }

    System.out.println("WebSocket client connected: " + conn.getRemoteSocketAddress());
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    // also remove from registered client map in AuthClient
    authClient.unregister(conn);
    System.out.println("WebSocket client disconnected");
  }

  @Override
  public void onMessage(WebSocket conn, String raw) {
    System.out.println("WebSocket received: " + raw);
    synchronized (webSocketServerCommands) {
      webSocketServerCommands.bus.setOutboundWriter(conn::send);
      webSocketServerCommands.bus.receive(raw);
    }
  }

  /**
   * Send a message to a previously registered client by id
   */
  public void sendToClientId(String clientId, String ...params) {
    WebSocket target = authClient.webSocketRegistry.getConnection(extractGlobalClientIdOnly(clientId));
    if (target != null) {
      System.out.println("Sending to " + clientId + " -> " + Arrays.toString(params));
      synchronized (webSocketServerCommands) {
        webSocketServerCommands.bus.setOutboundWriter(target::send);
        webSocketServerCommands.bus.request("SEND_MESSAGE", params);
      }
    } else {
      System.out.println("No client with id = " + clientId);
    }
  }

  public static String extractGlobalClientIdOnly(String clientId) {
    if (clientId == null) return null;

    int first = clientId.indexOf(':');
    if (first < 0) return clientId; // no ":" at all

    int second = clientId.indexOf(':', first + 1);
    if (second < 0) return clientId; // only one ":" -> return whole string (or clientId.substring(0, first) if you prefer)

    return clientId.substring(0, second);
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    System.err.println("WebSocket error: " + ex.getMessage());
  }

  @Override
  public void onStart() {
    System.out.println("WebSocket server started at " + getAddress());
  }
}
