package com.social100.todero;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    webSocketServerCommands.bus.setOutboundWriter(conn::send);
    webSocketServerCommands.bus.receive(raw);
  }

  /**
   * Send a message to a previously registered client by id
   */
  public void sendToClientId(String clientId, String message) {
    WebSocket target = authClient.getClientById(clientId);
    if (target != null) {
      System.out.println("Sending to " + clientId + " -> " + message);
      target.send(message);
    } else {
      System.out.println("No client with id = " + clientId);
    }
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
