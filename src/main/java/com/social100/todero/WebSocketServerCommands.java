package com.social100.todero;

import com.social100.todero.cmd.CommandFramework;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class WebSocketServerCommands {
  CommandFramework.CommandRegistry registry = new CommandFramework.CommandRegistry();
  CommandFramework.CommandCodec codec = new CommandFramework.CommandCodec();
  CommandFramework.CommandBus bus = new CommandFramework.CommandBus(registry, codec, 30, TimeUnit.SECONDS);

  private final RedisPublisher publisher = new RedisPublisher("10.0.0.143", 6379, "client-messages");

  public WebSocketServerCommands() {
    // REGISTER
    registry.register("REGISTER", req -> {
      System.out.println("REGISTER" + "  " + req.getParams());
      return new CommandFramework.CommandMessage(
          req.getId(),
          "PONG",
          List.of("Hi there, "+req.getParams().get(0)),
          CommandFramework.CommandMessage.Kind.RESPONSE);
    });

    // SEND_MESSAGE
    registry.register("SEND_MESSAGE", req -> {
      List<String> p = req.getParams();

      // Minimal contract: [0]=clientId, [1]=data
      String clientId = p.size() > 0 ? p.get(0) : null;
      String data     = p.size() > 1 ? p.get(1) : null;

      try {
        String id = publisher.publish(clientId, data);
        // If your framework expects a response, return an ACK; otherwise return null.
        return new CommandFramework.CommandMessage(
            req.getId(),
            "ACK",
            List.of("xadd_id=" + id),
            CommandFramework.CommandMessage.Kind.RESPONSE
        );
      } catch (Exception e) {
        // Surface an error response so callers know it failed
        return new CommandFramework.CommandMessage(
            req.getId(),
            "ERROR",
            List.of(e.getClass().getSimpleName() + ": " + e.getMessage()),
            CommandFramework.CommandMessage.Kind.ERROR
        );
      }
    });
  }
}
