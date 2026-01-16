package com.social100.todero;

import com.social100.todero.cmd.CommandFramework;
import com.social100.todero.cmd.ParamParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class WebSocketServerCommands {
  CommandFramework.CommandRegistry registry = new CommandFramework.CommandRegistry();
  CommandFramework.CommandCodec codec = new CommandFramework.CommandCodec();
  CommandFramework.CommandBus bus = new CommandFramework.CommandBus(registry, codec, 30, TimeUnit.SECONDS);

  private final RedisPublisher publisher = new RedisPublisher("10.0.0.143", 6379, "client-messages");

  public WebSocketServerCommands() {

    final ParamParser.ParamSpec SEND_MESSAGE_SPEC = ParamParser.ParamSpec.builder()
        .addKey(
            ParamParser.KeySpec.builder("FROM")
                .required(true)
                .allowEmpty(false)
                .multi(false)
                .validator(v -> !v.trim().isEmpty())
        )
        .addKey(
            ParamParser.KeySpec.builder("TO")
                .required(true)
                .allowEmpty(false)
                .multi(false)
                .validator(v -> !v.trim().isEmpty())
        )
        .addKey(
            ParamParser.KeySpec.builder("MESSAGE")
                .required(true)
                .allowEmpty(true)
                .multi(false)
        )
        .allowUnknownKeys(false)
        .build();

    // REGISTER
//    registry.register("REGISTER", req -> {
//      System.out.println("REGISTER" + "  " + req.getParams());
//      return new CommandFramework.CommandMessage(
//          req.getId(),
//          "PONG",
//          List.of("Hi there, "+req.getParams().get(0)),
//          CommandFramework.CommandMessage.Kind.RESPONSE);
//    });

    // SEND_MESSAGE
    registry.register("SEND_MESSAGE", req -> {
      try {
        ParamParser.ParsedParams parsed = ParamParser.parse(req.getParams(), SEND_MESSAGE_SPEC);

        String fromClientId = parsed.require("FROM");
        String clientId = parsed.require("TO");
        String message  = parsed.require("MESSAGE");

        fromClientId = fromClientId.trim();
        clientId = clientId.trim();
        message  = message.trim();

        if (fromClientId.isEmpty() || clientId.isEmpty()) {
          throw new IllegalArgumentException("TO must be a non-empty client id");
        }

        String xaddId = publisher.publish(fromClientId, clientId, message);

        return new CommandFramework.CommandMessage(
            req.getId(),
            "ACK",
            List.of("xadd_id=" + xaddId, "to=" + clientId),
            CommandFramework.CommandMessage.Kind.RESPONSE
        );

      } catch (IllegalArgumentException iae) {
        return new CommandFramework.CommandMessage(
            req.getId(),
            "ERROR",
            List.of("BadRequest: " + iae.getMessage()),
            CommandFramework.CommandMessage.Kind.ERROR
        );
      } catch (Exception e) {
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
