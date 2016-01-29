/*
 * Copyright 2016 Quantiply Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.quantiply.elasticsearch;

import com.quantiply.rico.elasticsearch.Action;
import com.quantiply.rico.elasticsearch.ActionRequestKey;
import com.quantiply.samza.task.ESPushTaskConfig;
import io.searchbox.action.BulkableAction;
import io.searchbox.client.JestClient;
import io.searchbox.core.Bulk;
import io.searchbox.core.BulkResult;
import io.searchbox.core.DocumentResult;
import io.searchbox.core.Index;
import io.searchbox.params.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class HTTPBulkLoader {

  public static class Config {
    public final int flushMaxActions;
    public final Optional<Integer> flushMaxMs;

    public Config(int flushMaxActions, Optional<Integer>  flushMaxMs) {
      this.flushMaxActions = flushMaxActions;
      this.flushMaxMs = flushMaxMs;
    }
  }

  public enum TriggerType { MAX_ACTIONS, MAX_INTERVALS, MANUAL }

  public static class BulkReport {
    public final BulkResult bulkResult;
    public final TriggerType triggerType;
    public final List<ActionRequest> requests;

    public BulkReport(BulkResult bulkResult, TriggerType triggerType, List<ActionRequest> requests) {
      this.bulkResult = bulkResult;
      this.triggerType = triggerType;
      this.requests = requests;
    }
  }

  public static class ActionRequest {
    public final ActionRequestKey key;
    public final ESPushTaskConfig.ESIndexSpec spec;
    public final Object source;
    public final long timeMs;

    public ActionRequest(ActionRequestKey key, ESPushTaskConfig.ESIndexSpec spec, Object source, long timeMs) {
      this.key = key;
      this.spec = spec;
      this.source = source;
      this.timeMs = timeMs;
    }
  }

  protected final Config config;
  protected final JestClient client;
  protected final List<BulkableAction<DocumentResult>> actions;
  protected final List<ActionRequest> requests;
  protected int windowsSinceFlush = 0;
  protected Logger logger = LoggerFactory.getLogger(new Object(){}.getClass().getEnclosingClass());

  public HTTPBulkLoader(Config config, JestClient client) {
    this.config = config;
    this.client = client;
    actions = new ArrayList<>();
    requests = new ArrayList<>();
  }

  public void addAction(ActionRequest req) {
    BulkableAction<DocumentResult> action = null;
    if (req.key.getAction().equals(Action.INDEX)) {
      Index.Builder builder = new Index.Builder(req.source)
          .id(req.key.getId().toString())
          .index(getIndex(req.spec, req.key))
          .type(req.spec.docType);
      if (req.key.getVersionType() != null) {
        builder.setParameter(Parameters.VERSION_TYPE, req.key.getVersionType().toString());
      }
      if (req.key.getVersion() != null) {
        builder.setParameter(Parameters.VERSION, req.key.getVersion());
      }
      action = builder.build();
    }
    else {
      throw new RuntimeException("Not implemented");
    }
    actions.add(action);
    requests.add(req);
//    checkFlush();
  }

  public void window() throws IOException {
    windowsSinceFlush += 1;
    checkFlush();
  }

  public BulkReport flush() throws IOException {
    return flush(TriggerType.MANUAL);
  }

  protected BulkReport flush(TriggerType triggerType) throws IOException {
    Bulk bulkRequest = new Bulk.Builder().addAction(actions).build();
    BulkReport report = null;
    try {
      BulkResult bulkResult = client.execute(bulkRequest);
      report = new BulkReport(bulkResult, triggerType, requests);
    }
    catch (Exception e) {
      logger.error("Error writing to Elasticsearch", e);
      throw e;
    }
    finally {
      windowsSinceFlush = 0;
      actions.clear();
      requests.clear();
    }
    return report;
  }

  public void close() {
    client.shutdownClient();
  }

  protected TriggerType getTrigger() {
    if (actions.size() >= config.flushMaxActions) {
      return TriggerType.MAX_ACTIONS;
    }
//    if (windowsSinceFlush >= config.flushMaxWindowIntervals) {
//      return TriggerType.MAX_INTERVALS;
//    }
    return null;
  }

  protected void checkFlush() throws IOException {
    TriggerType triggerType = getTrigger();
    if (triggerType != null) {
      flush(triggerType);
    }
  }

  protected String getIndex(ESPushTaskConfig.ESIndexSpec spec, ActionRequestKey requestKey) {
    if (spec.indexNameDateFormat.isPresent()) {
      ZonedDateTime dateTime = Instant.ofEpochMilli(requestKey.getPartitionTsUnixMs()).atZone(spec.indexNameDateZone);
      //ES index names must be lowercase
      String dateStr = dateTime.format(DateTimeFormatter.ofPattern(spec.indexNameDateFormat.get())).toLowerCase();
      return spec.indexNamePrefix + dateStr;
    }
    return spec.indexNamePrefix;
  }

}
