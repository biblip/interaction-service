package com.social100.todero;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;

public class UDPClient {
  private DatagramSocket socket;
  private InetAddress serverAddress;
  private int serverPort;

  private OnUDPMessageListener listener;

  public interface OnUDPMessageListener {
    void onMessageReceived(String message);
  }

  public UDPClient(String serverHost, int serverPort) throws Exception {
    this.serverAddress = InetAddress.getByName(serverHost);
    this.serverPort = serverPort;
    // Use an ephemeral port (0 lets the OS pick an available port)
    this.socket = new DatagramSocket();
    System.out.println("Client started on port " + socket.getLocalPort());
  }

  public void setOnMessageListener(OnUDPMessageListener listener) {
    this.listener = listener;
  }

  public void startReceiving() throws Exception {
    // Send a REGISTER message to initiate connection
    send("REGISTER");
    System.out.println("Sent registration to server...");

    // Schedule periodic heartbeats (PING) every 30 seconds
    Timer timer = new Timer(true);
    timer.scheduleAtFixedRate(new TimerTask() {
      public void run() {
        try {
          send("PING");
          // (No immediate response expected for PING; it's just to keep alive)
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }, 30000, 30000);

    // Listen for incoming messages from server
    byte[] buf = new byte[1024];
    DatagramPacket packet = new DatagramPacket(buf, buf.length);
    while (true) {
      socket.receive(packet);
      String data = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
      if (data.contains(":")) {
        // Assuming format "id:message"
        int colonIndex = data.indexOf(':');
        String idStr = data.substring(0, colonIndex);
        String message = data.substring(colonIndex + 1);
        try {
          int msgId = Integer.parseInt(idStr);
          System.out.println("Received push from server: " + message.trim());
          // Send ACK
          String ack = "ACK:" + msgId;
          send(ack);
          System.out.println("Sent ACK for message " + msgId);
          if (listener != null) {
            listener.onMessageReceived(message);
          }
        } catch (NumberFormatException e) {
          // If parsing fails, print the raw message
          System.out.println("Received (no-id) from server: " + data);
        }
      } else {
        // If the server sends some other message without colon (e.g., a plain text)
        System.out.println("Received from server: " + data);
      }
    }
  }

  // Utility to send a string to the server
  protected void send(String s) throws Exception {
    byte[] data = s.getBytes(StandardCharsets.UTF_8);
    DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, serverPort);
    socket.send(packet);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.out.println("Usage: UDPClient <server_host> <server_port>");
      return;
    }
    String serverHost = args[0];
    int serverPort = Integer.parseInt(args[1]);
    UDPClient client = new UDPClient(serverHost, serverPort);
    client.startReceiving();
  }
}
