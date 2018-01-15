/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.strimzi.controller.topic;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.System.getenv;

public class Config {

    private static abstract class Type<T> {
        final String name;
        final String doc;
        Type(String name, String doc) {
            this.name = name;
            this.doc = doc;
        }
        abstract T parse(String s);
    }

    private static Type<? extends String> STRING = new Type<String>("string", "A Java string.") {
        @Override
        public String parse(String s) {
            return s;
        }
    };

    private static Type<? extends Long> LONG = new Type<Long>("long", "A Java long.") {
        @Override
        public Long parse(String s) {
            return Long.parseLong(s);
        }
    };

    private static Type<? extends Long> DURATION = new Type<Long>("duration",
            "A time duration composed of a non-negative integer quantity and time unit taken from " + Arrays.toString(TimeUnit.values()) + ". For example '5 seconds'.") {

        private final Pattern pattern = Pattern.compile("([0-9]+) *([a-z]+)", Pattern.CASE_INSENSITIVE);

        @Override
        public Long parse(String s) {
            Matcher m = pattern.matcher(s);
            if (m.matches()) {
                final TimeUnit unit = TimeUnit.valueOf(m.group(2).toUpperCase(Locale.ENGLISH));
                final String quantity = m.group(1);
                return TimeUnit.MILLISECONDS.convert(Long.parseLong(quantity), unit);
            } else {
                throw new IllegalArgumentException("Invalid duration: Expected an integer followed by one of " + TimeUnit.values() + " e.g. '5 MINUTES'");
            }

        }
    };

    static class Value<T> {
        public final String key;
        public final String defaultValue;
        public final boolean required;
        public final String doc;
        private final Type<? extends T> type;
        private Value(String key, Type<? extends T> type, String defaultValue, String doc) {
            this.key = key;
            this.type = type;
            if (defaultValue != null) {
                type.parse(defaultValue);
            }
            this.defaultValue = defaultValue;
            this.required = false;
            this.doc = doc;
        }
        private Value(String key, Type type, boolean required, String doc) {
            this.key = key;
            this.type = type;
            this.defaultValue = null;
            this.required = required;
            this.doc = doc;
        }
    }

    private static final Map<String, Value> CONFIG_VALUES = new HashMap<>();
    private static final Set<Type> TYPES = new HashSet<>();

    public static final Value<String> KUBERNETES_MASTER_URL = new Value("kubernetesMasterUrl", STRING, Main.DEFAULT_MASTER_URL,
            "The URL of the kubernetes master apiserver.");
    public static final Value<String> KAFKA_BOOTSTRAP_SERVERS = new Value("kafkaBootstrapServers", STRING,getenv("KAFKA_SERVICE_HOST") + ":" + getenv("KAFKA_SERVICE_PORT"),
            "A comma-separated list of kafka bootstrap servers.");
    public static final Value<String> ZOOKEEPER_CONNECT = new Value("zookeeperConnect", STRING, getenv("KAFKA_ZOOKEEPER_SERVICE_HOST") + ":" + getenv("KAFKA_ZOOKEEPER_SERVICE_PORT"),
            "The zookeeper connection string.");
    public static final Value<Long> ZOOKEEPER_SESSION_TIMEOUT_MS = new Value("zookeeperSessionTimeout", DURATION, "2 seconds",
            "The ZooKeeper session timeout.");
    public static final Value<Long> FULL_RECONCILIATION_INTERVAL_MS = new Value("fullReconciliationInterval", DURATION, "15 minutes",
            "The period between full reconciliations.");
    public static final Value<Long> REASSIGN_THROTTLE = new Value("reassignThrottle", LONG, Long.toString(Long.MAX_VALUE),
            "The interbroker throttled rate to use when a topic change requires partition reassignment.");
    public static final Value<Long> REASSIGN_VERIFY_INTERVAL_MS = new Value("reassignVerifyInterval", DURATION, "2 minutes",
            "The interval between verification executions (as in kafka-reassign-partitions.sh --verify ...) when a topic change requires partition reassignment.");

    static {
        Map<String, Value> configValues = CONFIG_VALUES;
        addConfigValue(configValues, KUBERNETES_MASTER_URL);
        addConfigValue(configValues, KAFKA_BOOTSTRAP_SERVERS);
        addConfigValue(configValues, ZOOKEEPER_CONNECT);
        addConfigValue(configValues, ZOOKEEPER_SESSION_TIMEOUT_MS);
        addConfigValue(configValues, FULL_RECONCILIATION_INTERVAL_MS);
        addConfigValue(configValues, REASSIGN_THROTTLE);
        addConfigValue(configValues, REASSIGN_VERIFY_INTERVAL_MS);
    }

    static void addConfigValue(Map<String, Value> configValues, Value cv) {
        if (configValues.put(cv.key, cv) != null) {
            throw new RuntimeException();
        }
        TYPES.add(cv.type);
    }

    private final Map<String, Object> map;

    public Config(Map<String, String> map) {
        this.map = new HashMap<>(map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            final Value configValue = CONFIG_VALUES.get(entry.getKey());
            if (configValue == null) {
                throw new IllegalArgumentException("Unknown config key " + entry.getKey());
            }
            this.map.put(configValue.key, get(map, configValue));
        }
        // now add all those config (with default value) that weren't in the given map
        Map<String, Value> x = new HashMap(CONFIG_VALUES);
        x.keySet().removeAll(map.keySet());
        for (Value value : x.values()) {
            this.map.put(value.key, get(map, value));
        }
    }

    public Iterable<Value> keys() {
        return Collections.unmodifiableCollection(CONFIG_VALUES.values());
    }

    private <T> T get(Map<String, String> map, Value<T> value) {
        final String s = map.getOrDefault(value.key, value.defaultValue);
        if (s != null) {
            return (T) value.type.parse(s);
        } else {
            if (value.required) {
                throw new IllegalArgumentException();
            }
            return null;
        }
    }

    public <T> T get(Value<T> value, T defaultValue) {
        return (T)this.map.getOrDefault(value.key, defaultValue);
    }

    public <T> T get(Value<T> value) {
        return (T)this.map.get(value.key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Config config = (Config) o;
        return Objects.equals(map, config.map);
    }

    @Override
    public int hashCode() {
        return Objects.hash(map);
    }
}
