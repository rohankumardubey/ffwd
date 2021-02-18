/*-
 * -\-\-
 * FastForward Core
 * --
 * Copyright (C) 2016 - 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.ffwd.noop;

import com.spotify.ffwd.model.v2.Batch;
import com.spotify.ffwd.model.v2.Metric;
import com.spotify.ffwd.output.BatchablePluginSink;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoopPluginSink implements BatchablePluginSink {

  private static final Logger log = LoggerFactory.getLogger(NoopPluginSink.class);

  private final AtomicLong date = new AtomicLong();
  private final AtomicLong last = new AtomicLong();
  private final AtomicLong total = new AtomicLong();

  @Override
  public void sendMetric(Metric metric) {
  }

  @Override
  public void sendBatch(final Batch batch) {
  }

  @Override
  public CompletableFuture<Void> sendMetrics(Collection<Metric> metrics) {
    return count(metrics.size());
  }

  @Override
  public CompletableFuture<Void> sendBatches(final Collection<Batch> batches) {
    return count(batches.size());
  }

  private CompletableFuture<Void> count(int size) {
    final long now = System.currentTimeMillis();
    final long then = this.date.getAndSet(now);
    final long total = this.total.addAndGet(size);

    final double rate;

    final long diff = (now - then) / 1000;

    if (then != 0 && diff != 0) {
      final long seen = total - this.last.getAndSet(total);
      rate = Math.round((double) seen / (double) diff);
    } else {
      rate = Double.NaN;
    }

    log.info("{} things/s (total: {})", rate, total);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public boolean isReady() {
    return true;
  }
}
