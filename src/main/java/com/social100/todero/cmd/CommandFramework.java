package com.social100.todero.cmd;

import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class CommandFramework {

  // === Command message (used for both request and response) ===
  public static class CommandMessage {
    public enum Kind { REQUEST, RESPONSE, ERROR }

    private final String id;
    private final String name;
    private final List<String> params;
    private final Kind kind;

    public CommandMessage(String id, String name, List<String> params, Kind kind) {
      this.id = id;
      this.name = name;
      this.params = params;
      this.kind = kind;
    }

    public String getId()     { return id; }
    public String getName()   { return name; }
    public List<String> getParams() { return params; }
    public Kind getKind()     { return kind; }
  }

  // === Serializer/deserializer ===
  // Format: <kind>:<id>:<name>:<param1>:<param2>:...
  public static class CommandCodec {

    public String serialize(CommandMessage msg) {
      StringBuilder sb = new StringBuilder();
      sb.append(msg.getKind().name()).append(":")
          .append(msg.getId()).append(":")
          .append(msg.getName());
      for (String p : msg.getParams()) {
        sb.append(":").append(escape(p));
      }
      return sb.toString();
    }

    public CommandMessage deserialize(String raw) {
      List<String> parts = splitOnUnescapedColons(raw);
      if (parts.size() < 3) {
        throw new IllegalArgumentException("Invalid message: " + raw);
      }
      CommandMessage.Kind kind = CommandMessage.Kind.valueOf(parts.get(0));
      String id = parts.get(1);
      String name = parts.get(2);
      List<String> params = new ArrayList<>();
      for (int i = 3; i < parts.size(); i++) {
        params.add(unescape(parts.get(i)));
      }
      return new CommandMessage(id, name, params, kind);
    }

    private List<String> splitOnUnescapedColons(String s) {
      List<String> out = new ArrayList<>();
      StringBuilder cur = new StringBuilder();
      boolean escaping = false;
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (escaping) {
          cur.append(c);       // keep the escaped char as-is (we'll unescape later)
          escaping = false;
        } else if (c == '\\') {
          escaping = true;     // next char is escaped
          cur.append(c);       // keep the backslash so unescape() can process it
        } else if (c == ':') {
          out.add(cur.toString());
          cur.setLength(0);
        } else {
          cur.append(c);
        }
      }
      if (escaping) cur.append('\\'); // trailing backslash; keep it
      out.add(cur.toString());
      return out;
    }

    private String escape(String s) {
      return s.replace("\\", "\\\\").replace(":", "\\:");
    }

    private String unescape(String s) {
      StringBuilder out = new StringBuilder();
      boolean escaping = false;
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (escaping) {
          // Only two escapes are defined: '\:' -> ':' and '\\' -> '\'.
          if (c == ':' || c == '\\') out.append(c);
          else { out.append('\\').append(c); } // preserve unknown escapes literally
          escaping = false;
        } else if (c == '\\') {
          escaping = true;
        } else {
          out.append(c);
        }
      }
      if (escaping) out.append('\\'); // trailing backslash literal
      return out.toString();
    }
  }

  // === Registry for concrete command handlers ===
  public interface CommandHandler {
    CommandMessage handle(CommandMessage req);
  }

  public static class CommandRegistry {
    private final Map<String, CommandHandler> handlers = new ConcurrentHashMap<>();
    public void register(String name, CommandHandler handler) {
      handlers.put(name, handler);
    }
    public Optional<CommandHandler> get(String name) {
      return Optional.ofNullable(handlers.get(name));
    }
  }

  // === CommandBus with send/request/response correlation ===
  public static class CommandBus {
    private final CommandRegistry registry;
    private final CommandCodec codec;
    private final Map<String, CompletableFuture<CommandMessage>> pending = new ConcurrentHashMap<>();
    private Consumer<String> outboundWriter;

    private final long    defaultTimeout;
    private final TimeUnit defaultTimeoutUnit;

    public CommandBus(CommandRegistry registry,
                      CommandCodec codec,
                      long defaultTimeout,
                      TimeUnit defaultTimeoutUnit)
    {
      this.registry = registry;
      this.codec = codec;
      this.defaultTimeout = defaultTimeout;
      this.defaultTimeoutUnit = defaultTimeoutUnit;
    }

    public void setOutboundWriter(Consumer<String> outboundWriter) {
      this.outboundWriter = outboundWriter;
    }

    public CommandCodec getCodec() {
      return this.codec;
    }

    // request with custom timeout
    public CompletableFuture<CommandMessage> request(String name,
                                                     long timeout,
                                                     TimeUnit unit,
                                                     String... params)
    {
      String id = UUID.randomUUID().toString();
      CommandMessage msg = new CommandMessage(id, name, List.of(params), CommandMessage.Kind.REQUEST);

      CompletableFuture<CommandMessage> future = new CompletableFuture<>();
      pending.put(id, future);

      future.orTimeout(timeout, unit)
          .exceptionally(ex -> {
            pending.remove(id);
            return null;
          });

      outboundWriter.accept(codec.serialize(msg));
      return future;
    }

    // request with default (global) timeout
    public CompletableFuture<CommandMessage> request(String name, String... params) {
      return request(name, defaultTimeout, defaultTimeoutUnit, params);
    }

    public void receive(String raw) {
      CommandMessage msg = codec.deserialize(raw);

      if (msg.getKind() == CommandMessage.Kind.RESPONSE) {
        CompletableFuture<CommandMessage> future = pending.remove(msg.getId());
        if (future != null) {
          future.complete(msg);
        }
        return;
      }

      registry.get(msg.getName()).ifPresent(handler -> {
        CommandMessage response = handler.handle(msg);
        outboundWriter.accept(codec.serialize(response));
      });
    }
  }
}
