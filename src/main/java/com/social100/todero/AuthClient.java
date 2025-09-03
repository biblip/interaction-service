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
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AuthClient {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  // clientId -> WebSocketConnection
  private final Map<String, WebSocket> serversForwardMap = new ConcurrentHashMap<>();
  private final Map<WebSocket, String> serversReverseMap = new ConcurrentHashMap<>();

  public boolean validateAndRegister(String token, WebSocket conn) {
    try {
      ValidationResponse validation = validateToken(token);
      if (!validation.isValid()) {
        System.out.println("Rejected client: invalid token");
        conn.close(1008, "Invalid token");
        return false;
      }

      // Build a client ID using the device_id + user_id + nodeId
      String clientId = validation.deviceId() + ":" + validation.userId();

      // Store the connection for later use
      serversForwardMap.put(clientId, conn);
      serversReverseMap.put(conn, clientId);

      System.out.println(Arrays.toString(serversForwardMap.keySet().toArray()));

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
    String clientId = serversReverseMap.remove(conn);
    if (clientId != null) {
      serversForwardMap.remove(clientId);
    }
  }

  public WebSocket getClientById(String clientId) {
    return serversForwardMap.get(clientId);
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
