package com.social100.todero;

import com.social100.todero.cmd.CommandFramework;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class WebSocketServerCommands {
  CommandFramework.CommandRegistry registry = new CommandFramework.CommandRegistry();
  CommandFramework.CommandCodec codec = new CommandFramework.CommandCodec();
  CommandFramework.CommandBus bus = new CommandFramework.CommandBus(registry, codec, 30, TimeUnit.SECONDS);

  public WebSocketServerCommands() {
    // Register a "REGISTER" command handler
    registry.register("REGISTER", req -> {
      System.out.println("REGISTER" + "  " + req.getParams());
      return new CommandFramework.CommandMessage(
          req.getId(),
          "PONG",
          List.of("Hi there, "+req.getParams().get(0)),
          CommandFramework.CommandMessage.Kind.RESPONSE);
    });
  }
}
