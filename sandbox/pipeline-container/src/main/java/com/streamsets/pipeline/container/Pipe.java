/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.container;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Preconditions;
import com.streamsets.pipeline.api.Module;
import com.streamsets.pipeline.api.Module.Info;
import com.streamsets.pipeline.config.Configuration;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class Pipe implements Module.Context {

  private List<Info> pipelineInfo;
  private MetricRegistry metrics;
  private Module.Info info;
  private Set<String> inputLanes;
  private Set<String> outputLanes;
  private Set<String> producedLanes;
  private Set<String> consumedLanes;

  public Pipe(List<Info> pipelineInfo, MetricRegistry metrics, Info info, Set<String> inputLanes,
      Set<String> outputLanes) {
    Preconditions.checkNotNull(pipelineInfo, "pipelineInfo cannot be null");
    Preconditions.checkNotNull(metrics, "metrics cannot be null");
    Preconditions.checkNotNull(info, "info cannot be null");
    Preconditions.checkNotNull(inputLanes, "inputLanes cannot be null");
    Preconditions.checkNotNull(outputLanes, "outputLanes cannot be null");
    Preconditions.checkArgument(!(inputLanes.isEmpty() && outputLanes.isEmpty()),
                                "both, inputLanes and outputLanes, cannot be empty");
    this.pipelineInfo = pipelineInfo;
    this.metrics = metrics;
    this.info = info;
    this.inputLanes = Collections.unmodifiableSet(inputLanes);
    this.outputLanes = Collections.unmodifiableSet(outputLanes);
    consumedLanes = new HashSet<String>(inputLanes);
    consumedLanes.removeAll(outputLanes);
    consumedLanes = Collections.unmodifiableSet(consumedLanes);
    producedLanes = new HashSet<String>(outputLanes);
    producedLanes.removeAll(inputLanes);
    producedLanes = Collections.unmodifiableSet(producedLanes);
  }

  public abstract void init();

  public abstract void destroy();

  @Override
  public List<Info> getPipelineInfo() {
    return pipelineInfo;
  }

  @Override
  public MetricRegistry getMetrics() {
    return metrics;
  }

  public Module.Info getModuleInfo() {
    return info;
  }

  public Set<String> getInputLanes() {
    return inputLanes;
  }

  public Set<String> getOutputLanes() {
    return outputLanes;
  }

  public Set<String> getProducedLanes() {
    return producedLanes;
  }

  public Set<String> getConsumedLanes() {
    return consumedLanes;
  }

  public void configure(Configuration conf) {
    Preconditions.checkNotNull(conf, "conf cannot be null");
    //TODO
  }

  public void processBatch(PipelineBatch batch) {
    PipeBatch pipeBatch = new PipeBatch(this, batch);
    pipeBatch.extractFromPipelineBatch();
    processBatch(pipeBatch);
    pipeBatch.flushBackToPipelineBatch();
  }

  protected abstract void processBatch(PipeBatch pipeBatch);

}
