/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.config;

import com.google.common.base.Preconditions;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class Configuration {
  private Map<String, String> map;

  public Configuration() {
    map = new LinkedHashMap<String, String>();
  }

  public Configuration getSubSetConfiguration(String namePrefix) {
    Preconditions.checkNotNull(namePrefix, "namePrefix cannot be null");
    Configuration conf = new Configuration();
    for (Map.Entry<String, String> entry : map.entrySet()) {
      if (entry.getKey().startsWith(namePrefix)) {
        conf.set(entry.getKey(), entry.getValue());
      }
    }
    return conf;
  }

  public Set<String> getNames() {
    return new HashSet<String>(map.keySet());
  }

  public void set(String name, String value) {
    Preconditions.checkNotNull(name, "name cannot be null");
    Preconditions.checkNotNull(value, "value cannot be null, use unset");
    map.put(name, value);
  }

  public void unset(String name) {
    Preconditions.checkNotNull(name, "name cannot be null");
    map.remove(name);
  }

  public void set(String name, long value) {
    set(name, Long.toString(value));
  }

  public void set(String name, boolean value) {
    set(name, Boolean.toString(value));
  }

  private String get(String name) {
    Preconditions.checkNotNull(name, "name cannot be null");
    return map.get(name);
  }

  public String get(String name, String defaultValue) {
    String value = get(name);
    return (value != null) ? value : defaultValue;
  }

  public long get(String name, long defaultValue) {
    String value = get(name);
    return (value != null) ? Long.parseLong(value) : defaultValue;
  }
  public boolean get(String name, boolean defaultValue) {
    String value = get(name);
    return (value != null) ? Boolean.parseBoolean(value) : defaultValue;
  }

}
