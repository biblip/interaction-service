package com.social100.todero.cmd;

import java.util.*;
import java.util.function.Predicate;

public final class ParamParser {

    public static final class KeySpec {
        final String name; // canonical name (normalized)
        final boolean required;
        final boolean allowEmpty;
        final boolean multi; // allow duplicates -> collect into list
        final Set<String> aliases; // normalized
        final Predicate<String> validator; // optional

        private KeySpec(Builder b) {
            this.name = b.name;
            this.required = b.required;
            this.allowEmpty = b.allowEmpty;
            this.multi = b.multi;
            this.aliases = Collections.unmodifiableSet(b.aliases);
            this.validator = b.validator;
        }

        public static Builder builder(String name) { return new Builder(name); }

        public static final class Builder {
            private final String name;
            private boolean required = false;
            private boolean allowEmpty = false;
            private boolean multi = false;
            private final Set<String> aliases = new LinkedHashSet<>();
            private Predicate<String> validator = null;

            private Builder(String name) {
                this.name = name;
            }
            public Builder required(boolean v) { this.required = v; return this; }
            public Builder allowEmpty(boolean v) { this.allowEmpty = v; return this; }
            public Builder multi(boolean v) { this.multi = v; return this; }
            public Builder alias(String a) { if (a != null) this.aliases.add(a); return this; }
            public Builder validator(Predicate<String> v) { this.validator = v; return this; }

            private KeySpec build() { return new KeySpec(this); }
        }
    }

    public static final class ParamSpec {
        final Map<String, KeySpec> byKey;       // normalized key -> spec
        final Map<String, String> defaults;     // normalized key -> default value
        final boolean allowUnknownKeys;
        final char separator;
        final boolean caseInsensitiveKeys;

        private ParamSpec(Builder b) {
            this.byKey = Collections.unmodifiableMap(b.byKey);
            this.defaults = Collections.unmodifiableMap(b.defaults);
            this.allowUnknownKeys = b.allowUnknownKeys;
            this.separator = b.separator;
            this.caseInsensitiveKeys = b.caseInsensitiveKeys;
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private final Map<String, KeySpec> byKey = new LinkedHashMap<>();
            private final Map<String, String> defaults = new LinkedHashMap<>();
            private boolean allowUnknownKeys = false;
            private char separator = ':'; // split KEY:VALUE
            private boolean caseInsensitiveKeys = true;

            private String norm(String s) {
                return caseInsensitiveKeys ? s.toUpperCase(Locale.ROOT) : s;
            }

            public Builder allowUnknownKeys(boolean v) { this.allowUnknownKeys = v; return this; }
            public Builder separator(char c) { this.separator = c; return this; }
            public Builder caseInsensitiveKeys(boolean v) { this.caseInsensitiveKeys = v; return this; }

            public Builder addKey(KeySpec.Builder kb) {
                KeySpec ks = kb.build();
                String canonical = norm(ks.name);
                put(ks, canonical);
                for (String alias : ks.aliases) {
                    put(ks, norm(alias));
                }
                return this;
            }

            public Builder defaultValue(String key, String value) {
                defaults.put(norm(key), value);
                return this;
            }

            private void put(KeySpec ks, String k) {
                if (byKey.containsKey(k)) {
                    throw new IllegalArgumentException("Duplicate key/alias in spec: " + k);
                }
                byKey.put(k, ks);
            }

            public ParamSpec build() { return new ParamSpec(this); }
        }
    }

    public static final class ParsedParams {
        private final Map<String, List<String>> values; // canonical key -> list of values
        private final ParamSpec spec;

        ParsedParams(ParamSpec spec, Map<String, List<String>> values) {
            this.spec = spec;
            this.values = values;
        }

        private String norm(String k) {
            return spec.caseInsensitiveKeys ? k.toUpperCase(Locale.ROOT) : k;
        }

        public boolean has(String key) {
            return values.containsKey(norm(key));
        }

        public String get(String key) {
            List<String> vs = values.get(norm(key));
            return (vs == null || vs.isEmpty()) ? null : vs.get(vs.size() - 1);
        }

        public List<String> getAll(String key) {
            List<String> vs = values.get(norm(key));
            return (vs == null) ? List.of() : List.copyOf(vs);
        }

        public String getOrDefault(String key, String defVal) {
            String v = get(key);
            return (v != null) ? v : defVal;
        }

        public String require(String key) {
            String v = get(key);
            if (v == null) throw new IllegalArgumentException("Missing required key: " + key);
            return v;
        }

        @Override public String toString() {
            return "ParsedParams" + values;
        }
    }

    // ---------- Parser ----------
    public static ParsedParams parse(List<String> params, ParamSpec spec) {
        if (params == null) params = List.of();

        // Build reverse lookup from normalized alias->canonical KeySpec
        Map<String, KeySpec> lookup = spec.byKey;
        Map<String, List<String>> out = new LinkedHashMap<>();

        for (String raw : params) {
            if (raw == null) continue;
            int idx = raw.indexOf(spec.separator);
            if (idx <= 0) {
                throw new IllegalArgumentException("Malformed parameter: '" + raw + "'. Expected KEY" + spec.separator + "VALUE");
            }

            String keyRaw = raw.substring(0, idx).trim();
            String val = raw.substring(idx + 1); // do not trim internally; preserve user content
            String normKey = spec.caseInsensitiveKeys ? keyRaw.toUpperCase(Locale.ROOT) : keyRaw;

            KeySpec ks = lookup.get(normKey);
            if (ks == null) {
                if (!spec.allowUnknownKeys) {
                    throw new IllegalArgumentException("Unknown parameter key: '" + keyRaw + "'");
                } else {
                    // Treat unknown as single-value unless you want to collect them separately.
                    out.computeIfAbsent(normKey, k -> new ArrayList<>()).add(val);
                    continue;
                }
            }

            if (!ks.allowEmpty && (val == null || val.isEmpty())) {
                throw new IllegalArgumentException("Empty value not allowed for key: " + ks.name);
            }

            if (ks.validator != null && val != null && !ks.validator.test(val)) {
                throw new IllegalArgumentException("Invalid value for key '" + ks.name + "': " + val);
            }

            List<String> list = out.computeIfAbsent(ks.name.toUpperCase(Locale.ROOT), k -> new ArrayList<>());
            if (!ks.multi && !list.isEmpty()) {
                throw new IllegalArgumentException("Duplicate key not allowed: " + ks.name);
            }
            list.add(val);
        }

        // Apply defaults & required checks
        for (KeySpec ks : new LinkedHashSet<>(lookup.values())) {
            String canon = ks.name.toUpperCase(Locale.ROOT);
            if (!out.containsKey(canon)) {
                if (ks.required) {
                    // default present?
                    String defVal = spec.defaults.get(canon);
                    if (defVal != null) {
                        out.put(canon, new ArrayList<>(List.of(defVal)));
                    } else {
                        throw new IllegalArgumentException("Missing required key: " + ks.name);
                    }
                } else {
                    // optional with default
                    String defVal = spec.defaults.get(canon);
                    if (defVal != null) {
                        out.put(canon, new ArrayList<>(List.of(defVal)));
                    }
                }
            }
        }

        return new ParsedParams(spec, out);
    }
}
