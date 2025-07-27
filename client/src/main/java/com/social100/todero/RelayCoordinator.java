package com.social100.todero;

public class RelayCoordinator {
  public static void main(String[] args) throws Exception {
    int wsPort = 5353;
    String udpHost = "io.shellaia.com";
    int udpPort = 4242;

    // Create both components
    UDPClient udpClient = new UDPClient(udpHost, udpPort);
    WebSocketRelayServer wsServer = new WebSocketRelayServer(wsPort);

    // Connect them
    wsServer.setUDPClient(udpClient);
    udpClient.setOnMessageListener(message -> {
      System.out.println("UDP → WebSocket: " + message);
      wsServer.broadcastToAll(message);
    });

    // Start both
    wsServer.start();
    udpClient.startReceiving();
  }
}
