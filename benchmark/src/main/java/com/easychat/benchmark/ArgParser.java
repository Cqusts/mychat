package com.easychat.benchmark;

import java.util.HashMap;
import java.util.Map;

public class ArgParser {
    private final Map<String, String> args = new HashMap<>();

    public ArgParser(String[] rawArgs) {
        for (int i = 0; i < rawArgs.length; i++) {
            if (rawArgs[i].startsWith("--") && i + 1 < rawArgs.length && !rawArgs[i + 1].startsWith("--")) {
                args.put(rawArgs[i].substring(2), rawArgs[i + 1]);
                i++;
            }
        }
    }

    public String get(String key, String defaultValue) {
        return args.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String val = args.get(key);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

    public long getLong(String key, long defaultValue) {
        String val = args.get(key);
        return val != null ? Long.parseLong(val) : defaultValue;
    }
}
