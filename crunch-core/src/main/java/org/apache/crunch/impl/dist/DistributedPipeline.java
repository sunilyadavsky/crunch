/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.crunch.impl.dist;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.crunch.CrunchRuntimeException;
import org.apache.crunch.MapFn;
import org.apache.crunch.PCollection;
import org.apache.crunch.PTable;
import org.apache.crunch.ParallelDoOptions;
import org.apache.crunch.Pipeline;
import org.apache.crunch.PipelineResult;
import org.apache.crunch.PipelineCallable;
import org.apache.crunch.Source;
import org.apache.crunch.SourceTarget;
import org.apache.crunch.TableSource;
import org.apache.crunch.Target;
import org.apache.crunch.fn.IdentityFn;
import org.apache.crunch.impl.dist.collect.BaseInputCollection;
import org.apache.crunch.impl.dist.collect.BaseInputTable;
import org.apache.crunch.impl.dist.collect.BaseGroupedTable;
import org.apache.crunch.impl.dist.collect.BaseUnionCollection;
import org.apache.crunch.impl.dist.collect.BaseUnionTable;
import org.apache.crunch.impl.dist.collect.EmptyPCollection;
import org.apache.crunch.impl.dist.collect.EmptyPTable;
import org.apache.crunch.impl.dist.collect.PCollectionImpl;
import org.apache.crunch.impl.dist.collect.PCollectionFactory;
import org.apache.crunch.io.From;
import org.apache.crunch.io.ReadableSource;
import org.apache.crunch.io.ReadableSourceTarget;
import org.apache.crunch.io.To;
import org.apache.crunch.materialize.MaterializableIterable;
import org.apache.crunch.types.PTableType;
import org.apache.crunch.types.PType;
import org.apache.crunch.types.writable.Writables;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public abstract class DistributedPipeline implements Pipeline {
  private static final Logger LOG = LoggerFactory.getLogger(DistributedPipeline.class);

  private static final Random RANDOM = new Random();

  private final String name;
  protected final PCollectionFactory factory;
  protected final Map<PCollectionImpl<?>, Set<Target>> outputTargets;
  protected final Map<PCollectionImpl<?>, MaterializableIterable<?>> outputTargetsToMaterialize;
  protected final Map<PipelineCallable<?>, Set<Target>> allPipelineCallables;
  protected final Set<Target> appendedTargets;
  private Path tempDirectory;
  private int tempFileIndex;
  private int nextAnonymousStageId;

  private Configuration conf;
  private PipelineCallable currentPipelineCallable;

  /**
   * Instantiate with a custom name and configuration.
   *
   * @param name Display name of the pipeline
   * @param conf Configuration to be used within all MapReduce jobs run in the pipeline
   */
  public DistributedPipeline(String name, Configuration conf, PCollectionFactory factory) {
    this.name = name;
    this.factory = factory;
    this.outputTargets = Maps.newHashMap();
    this.outputTargetsToMaterialize = Maps.newHashMap();
    this.allPipelineCallables = Maps.newHashMap();
    this.appendedTargets = Sets.newHashSet();
    this.conf = conf;
    this.tempDirectory = createTempDirectory(conf);
    this.tempFileIndex = 0;
    this.nextAnonymousStageId = 0;
  }

  public PCollectionFactory getFactory() {
    return factory;
  }

  @Override
  public Configuration getConfiguration() {
    return conf;
  }

  @Override
  public void setConfiguration(Configuration conf) {
    this.conf = conf;
    this.tempDirectory = createTempDirectory(conf);
  }

  @Override
  public PipelineResult done() {
    PipelineResult res = PipelineResult.DONE;
    if (!outputTargets.isEmpty()) {
      res = run();
    }
    cleanup();
    return res;
  }

  @Override
  public <Output> Output sequentialDo(PipelineCallable<Output> pipelineCallable) {
    allPipelineCallables.put(pipelineCallable, getDependencies(pipelineCallable));
    PipelineCallable last = currentPipelineCallable;
    currentPipelineCallable = pipelineCallable;
    Output out = pipelineCallable.generateOutput(this);
    currentPipelineCallable = last;
    return out;
  }

  public <S> PCollection<S> read(Source<S> source) {
    return factory.createInputCollection(source, this, getCurrentPDoOptions());
  }

  public <K, V> PTable<K, V> read(TableSource<K, V> source) {
    return factory.createInputTable(source, this, getCurrentPDoOptions());
  }

  private ParallelDoOptions getCurrentPDoOptions() {
    ParallelDoOptions.Builder pdb = ParallelDoOptions.builder();
    if (currentPipelineCallable != null) {
      pdb.targets(allPipelineCallables.get(currentPipelineCallable));
    }
    return pdb.build();
  }

  private Set<Target> getDependencies(PipelineCallable<?> callable) {
    Set<Target> deps = Sets.newHashSet(callable.getAllTargets().values());
    for (PCollection pc : callable.getAllPCollections().values()) {
      PCollectionImpl pcImpl = (PCollectionImpl) pc;
      deps.addAll(pcImpl.getTargetDependencies());
      MaterializableIterable iter = (MaterializableIterable) pc.materialize();
      Source pcSrc = iter.getSource();
      if (pcSrc instanceof Target) {
        deps.add((Target) pcSrc);
      }
    }
    return deps;
  }

  public PCollection<String> readTextFile(String pathName) {
    return read(From.textFile(pathName));
  }

  public void write(PCollection<?> pcollection, Target target) {
    write(pcollection, target, Target.WriteMode.DEFAULT);
  }

  @SuppressWarnings("unchecked")
  public void write(PCollection<?> pcollection, Target target,
                    Target.WriteMode writeMode) {
    if (pcollection instanceof BaseGroupedTable) {
      pcollection = ((BaseGroupedTable<?, ?>) pcollection).ungroup();
    } else if (pcollection instanceof BaseUnionCollection || pcollection instanceof BaseUnionTable) {
      pcollection = pcollection.parallelDo("UnionCollectionWrapper",
          (MapFn) IdentityFn.<Object> getInstance(), pcollection.getPType());
    }
    boolean exists = target.handleExisting(writeMode, ((PCollectionImpl) pcollection).getLastModifiedAt(),
        getConfiguration());
    if (exists && writeMode == Target.WriteMode.CHECKPOINT) {
      SourceTarget<?> st = target.asSourceTarget(pcollection.getPType());
      if (st == null) {
        throw new CrunchRuntimeException("Target " + target + " does not support checkpointing");
      } else {
        ((PCollectionImpl) pcollection).materializeAt(st);
      }
      return;
    } else if (writeMode != Target.WriteMode.APPEND && targetInCurrentRun(target)) {
      throw new CrunchRuntimeException("Target " + target + " is already written in current run." +
          " Use WriteMode.APPEND in order to write additional data to it.");
    }

    // Need special handling for append targets in the case of materialization
    if (writeMode == Target.WriteMode.APPEND) {
      appendedTargets.add(target);
    }

    addOutput((PCollectionImpl<?>) pcollection, target);
  }

  private boolean targetInCurrentRun(Target target) {
    for (Set<Target> targets : outputTargets.values()) {
      if (targets.contains(target)) {
        return true;
      }
    }
    return false;
  }

  private void addOutput(PCollectionImpl<?> impl, Target target) {
    if (!outputTargets.containsKey(impl)) {
      outputTargets.put(impl, Sets.<Target> newHashSet());
    }
    outputTargets.get(impl).add(target);
  }

  @Override
  public <S> PCollection<S> emptyPCollection(PType<S> ptype) {
    return new EmptyPCollection<S>(this, ptype);
  }

  @Override
  public <K, V> PTable<K, V> emptyPTable(PTableType<K, V> ptype) {
    return new EmptyPTable<K, V>(this, ptype);
  }

  /**
   * Retrieve a ReadableSourceTarget that provides access to the contents of a {@link PCollection}.
   * This is primarily intended as a helper method to {@link #materialize(PCollection)}. The
   * underlying data of the ReadableSourceTarget may not be actually present until the pipeline is
   * run.
   *
   * @param pcollection The collection for which the ReadableSourceTarget is to be retrieved
   * @return The ReadableSourceTarget
   * @throws IllegalArgumentException If no ReadableSourceTarget can be retrieved for the given
   *           PCollection
   */
  public <T> ReadableSource<T> getMaterializeSourceTarget(PCollection<T> pcollection) {
    PCollectionImpl<T> impl = toPCollectionImpl(pcollection);

    // First, check to see if this is a readable input collection.
    if (impl instanceof BaseInputCollection) {
      BaseInputCollection<T> ic = (BaseInputCollection<T>) impl;
      if (ic.getSource() instanceof ReadableSource) {
        return (ReadableSource) ic.getSource();
      } else {
        throw new IllegalArgumentException(
            "Cannot materialize non-readable input collection: " + ic);
      }
    } else if (impl instanceof BaseInputTable) {
      BaseInputTable it = (BaseInputTable) impl;
      if (it.getSource() instanceof ReadableSource) {
        return (ReadableSource) it.getSource();
      } else {
        throw new IllegalArgumentException(
            "Cannot materialize non-readable input table: " + it);
      }
    }

    // Next, check to see if this pcollection has already been materialized.
    SourceTarget<?> matTarget = impl.getMaterializedAt();
    if (matTarget != null && matTarget instanceof ReadableSourceTarget) {
      return (ReadableSourceTarget<T>) matTarget;
    }

    // Check to see if we plan on materializing this collection on the
    // next run.
    ReadableSourceTarget<T> srcTarget = null;
    if (outputTargets.containsKey(pcollection)) {
      for (Target target : outputTargets.get(impl)) {
        if (target instanceof ReadableSourceTarget && !appendedTargets.contains(target)) {
          return (ReadableSourceTarget<T>) target;
        }
      }
    }

    // If we're not planning on materializing it already, create a temporary
    // output to hold the materialized records and return that.
    SourceTarget<T> st = createIntermediateOutput(pcollection.getPType());
    if (!(st instanceof ReadableSourceTarget)) {
      throw new IllegalArgumentException("The PType for the given PCollection is not readable"
          + " and cannot be materialized");
    } else {
      srcTarget = (ReadableSourceTarget<T>) st;
      addOutput(impl, srcTarget);
      return srcTarget;
    }
  }

  /**
   * Safely cast a PCollection into a PCollectionImpl, including handling the case of
   * UnionCollections.
   *
   * @param pcollection The PCollection to be cast/transformed
   * @return The PCollectionImpl representation
   */
  private <T> PCollectionImpl<T> toPCollectionImpl(PCollection<T> pcollection) {
    PCollectionImpl<T> pcollectionImpl = null;
    if (pcollection instanceof BaseUnionCollection || pcollection instanceof BaseUnionTable) {
      pcollectionImpl = (PCollectionImpl<T>) pcollection.parallelDo("UnionCollectionWrapper",
          (MapFn) IdentityFn.<Object> getInstance(), pcollection.getPType());
    } else {
      pcollectionImpl = (PCollectionImpl<T>) pcollection;
    }
    return pcollectionImpl;
  }

  public <T> SourceTarget<T> createIntermediateOutput(PType<T> ptype) {
    return ptype.getDefaultFileSource(createTempPath());
  }

  public Path createTempPath() {
    tempFileIndex++;
    return new Path(tempDirectory, "p" + tempFileIndex);
  }

  private static Path createTempDirectory(Configuration conf) {
    Path dir = createTemporaryPath(conf);
    try {
      dir.getFileSystem(conf).mkdirs(dir);
    } catch (IOException e) {
      throw new RuntimeException("Cannot create job output directory " + dir, e);
    }
    return dir;
  }

  private static Path createTemporaryPath(Configuration conf) {
    //TODO: allow configurable
    String baseDir = conf.get("crunch.tmp.dir", "/tmp");
    return new Path(baseDir, "crunch-" + (RANDOM.nextInt() & Integer.MAX_VALUE));
  }

  @Override
  public <T> void writeTextFile(PCollection<T> pcollection, String pathName) {
    pcollection.parallelDo("asText", new StringifyFn<T>(), Writables.strings())
        .write(To.textFile(pathName));
  }

  private static class StringifyFn<T> extends MapFn<T, String> {
    @Override
    public String map(T input) {
      return input.toString();
    }
  }

  @Override
  public void cleanup(boolean force) {
    if (force || outputTargets.isEmpty()) {
      try {
        FileSystem fs = tempDirectory.getFileSystem(conf);
        if (fs.exists(tempDirectory)) {
          fs.delete(tempDirectory, true);
        }
      } catch (IOException e) {
        LOG.info("Exception during cleanup", e);
      }
    } else {
      LOG.warn("Not running cleanup while output targets remain.");
    }
  }

  private void cleanup() {
    cleanup(false);
  }

  public int getNextAnonymousStageId() {
    return nextAnonymousStageId++;
  }

  @Override
  public void enableDebug() {
    // Turn on Crunch runtime error catching.
    //TODO: allow configurable
    getConfiguration().setBoolean("crunch.debug", true);
  }

  @Override
  public String getName() {
    return name;
  }
}
