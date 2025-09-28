package com.social100.todero;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class AuthClient {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  WebSocketRegistry webSocketRegistry = new WebSocketRegistry();
  RegistryMonitor monitor = new RegistryMonitor(webSocketRegistry, 10);

  public boolean validateAndRegister(String token, WebSocket conn) {
    try {
      ValidationResponse validation = validateToken(token);
      if (!validation.isValid()) {
        System.out.println("Rejected client: invalid token");
        conn.close(1008, "Invalid token");
        return false;
      }

      // Build a client ID using the user_id + device_id
      String clientId = validation.userId() + ":" + validation.deviceId();

      // Store the connection for later use
      webSocketRegistry.addByClientId(clientId, conn);

      monitor.triggerNow("Added ClientId : " + clientId);

      System.out.println("Client registered with id = " + clientId);
      return true;

    } catch (IOException e) {
      System.out.println("IOException : " + e.getMessage());
      conn.close(1008, "IOException : " + e.getMessage());
      return false;
    }
  }

  private ValidationResponse validateToken(String token) throws IOException {
    String baseUrl = "https://auth.shellaia.com";

    URL url = new URL(baseUrl + "/auth/api-tokens/validate");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();

    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Authorization", "Bearer " + token);
    conn.setRequestProperty("X-Custom-Header", "volunteer_548456");
    conn.setRequestProperty("Content-Type", "application/json");

    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    try (OutputStream os = conn.getOutputStream()) {
      os.write(body);
    }

    int statusCode = conn.getResponseCode();

    String responseBody;
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(
            statusCode >= 200 && statusCode < 300
                ? conn.getInputStream()
                : conn.getErrorStream(),
            StandardCharsets.UTF_8))) {
      responseBody = reader.lines().collect(Collectors.joining("\n"));
    }

    System.out.println("Response body: " + responseBody);

    boolean valid = (statusCode == HttpURLConnection.HTTP_OK);

    // Parse JSON fields if valid
    if (valid) {
      JsonNode node = objectMapper.readTree(responseBody);
      String deviceId = node.path("device_id").asText("");
      String userId   = node.path("user_id").asText("");
      String role     = node.path("role").asText("");
      String iss      = node.path("iss").asText("");

      return new ValidationResponse(true, deviceId, userId, role, iss);
    }

    return new ValidationResponse(false, null, null, null, null);
  }

  public void unregister(WebSocket conn) {
    webSocketRegistry.removeByConnection(conn);
    monitor.triggerNow("unregister");
  }

  // 🧱 Small record that holds the validation + extracted fields
  private record ValidationResponse(
      boolean valid,
      String deviceId,
      String userId,
      String role,
      String iss
  ) {
    public boolean isValid() { return valid; }
  }
}
