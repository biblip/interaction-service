package com.social100.todero.cmd;

import java.util.LinkedHashMap;
import java.util.Map;

public class CmdArgs {

  public static String[] of(Map<String, String> pairs) {
    return pairs.entrySet().stream()
        .filter(entry -> entry.getValue() != null)
        .map(entry -> entry.getKey() + ":" + entry.getValue())
        .toArray(String[]::new);
  }

  public static String[] sendMessageArgs(String fromClientId, String clientId, String message) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("FROM", fromClientId);
    map.put("TO", clientId);
    map.put("MESSAGE", message);
    return of(map);
  }
}