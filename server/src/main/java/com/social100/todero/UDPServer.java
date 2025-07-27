package com.social100.todero;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import redis.clients.jedis.*;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

public class UDPServer {

  // Nested class to hold client state
  static class ClientInfo {
    String clientId;
    SocketAddress address;
    long connectionTime;
    long lastActivity;
    int nextMsgId = 0;
    // Map of pending outbound messages awaiting ACK (msgId -> message data)
    Map<Integer, String> pendingMessages = new ConcurrentHashMap<>();
  }

  // Map to store clients by their socket address
  private final Map<SocketAddress, ClientInfo> clients = new ConcurrentHashMap<>();
  private final DatagramSocket socket;
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private Jedis jedis;

  public UDPServer(int port, String redisHost, int redisPort) throws Exception {
    socket = new DatagramSocket(port);
    System.out.println("UDP Server listening on port " + port);

    // Start a thread to listen for incoming packets
    Thread receiverThread = new Thread(this::receiveLoop, "UDPReceiver");
    receiverThread.start();

    // Schedule periodic tasks: e.g., resend unacknowledged messages
    scheduler.scheduleAtFixedRate(this::resendPending, 1, 1, TimeUnit.SECONDS);

    // Connect to Redis
    jedis = new Jedis(redisHost, redisPort);
    String groupName = "mygroup";
    String streamName = "client-messages";
    startRedisListener(streamName, groupName);

    // (Also could schedule a task to remove idle clients, not shown for brevity)
  }

  // Start a thread to listen to Redis for incoming events
  private void startRedisListener(String streamName, String groupName) {
    Thread redisListenerThread = new Thread(() -> {
      try (Jedis jedis = new Jedis("10.0.0.143",6379)) {
        //jedis.xgroupCreate(streamName, groupName, StreamEntryID.LAST_ENTRY, false); // Create the group if not exists
        while (true) {
          // Read messages from the Redis stream
          XReadGroupParams params = XReadGroupParams.xReadGroupParams()
              .count(1)
              .block(0); // 0 = block indefinitely

          Map<String, StreamEntryID> streams = java.util.Collections.singletonMap(
              streamName, StreamEntryID.UNRECEIVED_ENTRY // ">"
          );

          List<Map.Entry<String, List<StreamEntry>>> entries =
              jedis.xreadGroup(groupName, "server-1", params, streams);

          System.out.println("receiving...");

          for (Map.Entry<String, List<StreamEntry>> stream : entries) {
            for (StreamEntry entry : stream.getValue()) {

              System.out.println(entry.getFields().get("client_id") + " --> " + entry.getFields().get("data"));

              String clientId = entry.getFields().get("client_id");
              String data = entry.getFields().get("data");

              // Send message to the corresponding UDP client
              sendMessageToClient(clientId, data);

              // Acknowledge the message in Redis
              jedis.xack(streamName, groupName, entry.getID());
            }
          }
        }
      } catch (Exception e) {
        if (!e.getMessage().contains("BUSYGROUP")) {
          throw e;  // rethrow if it's not the expected "group already exists" error
        } else {
          System.out.println("Consumer group already exists, continuing...");
        }
      }
    });
    redisListenerThread.start();
  }

  // Method to send a message reliably to a client
  private void sendMessageToClient(String clientId, String message) {
    for (ClientInfo client : clients.values()) {
      if (clientId.equals(clientId)) {
        try {
          sendMessage(client, message);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }

  // Main loop for receiving packets
  private void receiveLoop() {
    byte[] buf = new byte[1024];
    DatagramPacket packet = new DatagramPacket(buf, buf.length);
    try {
      while (true) {
        socket.receive(packet);
        SocketAddress clientAddr = packet.getSocketAddress();
        int length = packet.getLength();
        String data = new String(packet.getData(), 0, length, StandardCharsets.UTF_8);

        // Update or add client in the map
        ClientInfo client = clients.get(clientAddr);
        if (client == null) {
          // New client registration
          client = new ClientInfo();
          client.clientId = "";
          client.address = clientAddr;
          client.connectionTime = System.currentTimeMillis();
          client.pendingMessages = new ConcurrentHashMap<>();
          clients.put(clientAddr, client);
          System.out.println("New client registered: " + clientAddr);
        }
        // Update last activity timestamp
        client.lastActivity = System.currentTimeMillis();

        // Process the incoming message
        if (data.startsWith("ACK:")) {
          // Acknowledgment from client
          int ackId;
          try {
            ackId = Integer.parseInt(data.substring(4).trim());
          } catch (NumberFormatException e) {
            continue; // invalid ACK format
          }
          // Remove message from pending since it's acknowledged
          client.pendingMessages.remove(ackId);
        } else if (data.equals("REGISTER")) {
          // Registration message from client
          String welcome = "WELCOME";
          sendMessage(client, welcome);  // send with reliability
        } else if (data.equals("PING")) {
          // Heartbeat from client, no reply needed (lastActivity already updated)
          System.out.println("Received heartbeat from " + clientAddr);
        } else {
          // Received a data message from client (if any)
          System.out.println("Received from " + clientAddr + ": " + data);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
      socket.close();
    }
  }

  // Method to send a message reliably to a client
  private void sendMessage(ClientInfo client, String message) throws Exception {
    int msgId = client.nextMsgId++;
    String payload = msgId + ":" + message;
    byte[] data = payload.getBytes(StandardCharsets.UTF_8);
    DatagramPacket packet = new DatagramPacket(data, data.length,
        ((InetSocketAddress) client.address).getAddress(),
        ((InetSocketAddress) client.address).getPort());
    socket.send(packet);
    // Store in pending for ack tracking
    client.pendingMessages.put(msgId, message);
    System.out.println("Sent to " + client.address + " -> [" + msgId + ":" + message + "]");
  }

  // Periodic task to resend any pending (un-ACKed) messages
  private void resendPending() {
    long now = System.currentTimeMillis();
    for (ClientInfo client : clients.values()) {
      for (Map.Entry<Integer, String> entry : client.pendingMessages.entrySet()) {
        int msgId = entry.getKey();
        String msg = entry.getValue();
        // For simplicity, resend all pending messages (could track last sent time to add delay)
        try {
          String payload = msgId + ":" + msg;
          byte[] data = payload.getBytes(StandardCharsets.UTF_8);
          DatagramPacket packet = new DatagramPacket(data, data.length,
              ((InetSocketAddress) client.address).getAddress(),
              ((InetSocketAddress) client.address).getPort());
          socket.send(packet);
          System.out.println("Resent message " + msgId + " to " + client.address);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }

  // Simple test: broadcast a push notification to all clients
  public void broadcast(String notification) throws Exception {
    for (ClientInfo client : clients.values()) {
      sendMessage(client, notification);
    }
  }

  public static void main(String[] args) throws Exception {
    UDPServer server = new UDPServer(4242, "10.0.0.143", 6379);
    // Example usage: after some time, broadcast a test notification

    /*
    for (int x = 0 ; x < 10 ; x++) {
      Thread.sleep(10000);
      System.out.println("Broadcasting notification to all clients...");
      server.broadcast("HelloClients");
    }*/
  }
}
