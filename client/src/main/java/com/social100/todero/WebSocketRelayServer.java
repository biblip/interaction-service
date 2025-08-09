package com.social100.todero;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketRelayServer extends WebSocketServer {
  private final Set<WebSocket> clients = ConcurrentHashMap.newKeySet();
  private InteractionClient udpClient;

  public WebSocketRelayServer(int port) {
    super(new InetSocketAddress("127.0.0.1", port));
    System.out.println("WebSocketRelayServer listening on port " + port);
  }

  public void setUDPClient(InteractionClient client) {
    this.udpClient = client;
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    String resourceDescriptor = handshake.getResourceDescriptor();
    if (!"/ws".equals(resourceDescriptor)) {
      System.out.println("Rejected client with invalid path: " + resourceDescriptor);
      conn.close(1002, "Invalid path"); // 1002 = protocol error
      return;
    }

    clients.add(conn);
    System.out.println("WebSocket client connected: " + conn.getRemoteSocketAddress());
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    clients.remove(conn);
    System.out.println("WebSocket client disconnected");
  }

  @Override
  public void onMessage(WebSocket conn, String message) {
    System.out.println("WebSocket received: " + message);
    try {
      if (udpClient != null) {
        udpClient.send("FromWebSocket:" + message);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void broadcastToAll(String message) {
    for (WebSocket ws : clients) {
      ws.send(message);
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
