/*
 * Copyright 2014 MovingBlocks
 *
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
 */
package org.terasology.world.propagation.light;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3i;
import org.terasology.registry.CoreRegistry;
import org.terasology.scheduling.TaskManager;
import org.terasology.world.chunks.Chunk;
import org.terasology.world.chunks.LitChunk;
import org.terasology.world.chunks.internal.GeneratingChunkProvider;
import org.terasology.world.propagation.BatchPropagator;
import org.terasology.world.propagation.LocalChunkView;
import org.terasology.world.propagation.PropagationRules;
import org.terasology.world.propagation.PropagatorWorldView;
import org.terasology.world.propagation.StandardBatchPropagator;
import org.terasology.world.propagation.SunlightRegenBatchPropagator;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * @author Immortius
 */
public class LightMerger<T> {
    private static final int CENTER_INDEX = 13;

    private static Logger logger = LoggerFactory.getLogger(LightMerger.class);

    private final TaskManager taskManager = CoreRegistry.get(TaskManager.class);
    private Future<T> resultFuture;

    private GeneratingChunkProvider chunkProvider;
    private LightPropagationRules lightRules = new LightPropagationRules();
    private SunlightRegenPropagationRules sunlightRegenRules = new SunlightRegenPropagationRules();

    private boolean running = true;

    public LightMerger(GeneratingChunkProvider chunkProvider) {
        this.chunkProvider = chunkProvider;
    }

    public void beginMerge(final Chunk chunk, final T data) {
        if (resultFuture != null) {
            resultFuture.cancel(false);
        }
        resultFuture = taskManager.getThreadPool().submit(new Callable<T>() {
            @Override
            public T call() throws Exception {
                merge(chunk);
                return data;
            }
        });
    }

    public T completeMerge() {
        if (resultFuture != null) {
            try {
                T result = resultFuture.get();
                resultFuture = null;
                return result;
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Error completing lighting merge");
            }
        }
        return null;
    }

    private void merge(Chunk chunk) {
        Chunk[] localChunks = assembleLocalChunks(chunk);
        localChunks[CENTER_INDEX] = chunk;
        for (Chunk localChunk : localChunks) {
            if (localChunk != null) {
                localChunk.lock();
            }
        }
        try {

            List<BatchPropagator> propagators = Lists.newArrayList();
            propagators.add(new StandardBatchPropagator(new LightPropagationRules(), new LocalChunkView(localChunks, lightRules)));
            PropagatorWorldView regenWorldView = new LocalChunkView(localChunks, sunlightRegenRules);
            PropagationRules sunlightRules = new SunlightPropagationRules(regenWorldView);
            PropagatorWorldView sunlightWorldView = new LocalChunkView(localChunks, sunlightRules);
            BatchPropagator sunlightPropagator = new StandardBatchPropagator(sunlightRules, sunlightWorldView);
            propagators.add(new SunlightRegenBatchPropagator(sunlightRegenRules, regenWorldView, sunlightPropagator, sunlightWorldView));
            propagators.add(sunlightPropagator);

            for (BatchPropagator propagator : propagators) {
                // Propagate Inwards
                for (Side side : Side.values()) {
                    Vector3i adjChunkPos = side.getAdjacentPos(chunk.getPosition());
                    LitChunk adjChunk = chunkProvider.getChunkUnready(adjChunkPos);
                    if (adjChunk != null) {
                        propagator.propagateBetween(adjChunk, chunk, side.reverse(), false);
                    }
                }

                // Propagate Outwards
                for (Side side : Side.values()) {
                    Vector3i adjChunkPos = side.getAdjacentPos(chunk.getPosition());
                    LitChunk adjChunk = chunkProvider.getChunk(adjChunkPos);
                    if (adjChunk != null) {
                        propagator.propagateBetween(chunk, adjChunk, side, true);
                    }
                }
            }
            for (BatchPropagator propagator : propagators) {
                propagator.process();
            }
            chunk.deflateSunlight();
        } finally {
            for (Chunk localChunk : localChunks) {
                if (localChunk != null) {
                    localChunk.unlock();
                }
            }
        }
    }

    private Chunk[] assembleLocalChunks(Chunk chunk) {
        Chunk[] localChunks = new Chunk[27];
        int index = 0;
        for (int z = -1; z < 2; ++z) {
            for (int y = -1; y < 2; ++y) {
                for (int x = -1; x < 2; ++x) {
                    Chunk localChunk = chunkProvider.getChunk(chunk.getPosition().x + x, chunk.getPosition().y + y, chunk.getPosition().z + z);
                    if (localChunk != null) {
                        localChunks[index] = localChunk;
                    }
                    index++;
                }
            }
        }
        return localChunks;
    }

    public void shutdown() {
        if (resultFuture != null) {
            resultFuture.cancel(false);
            try {
                resultFuture.get(10L, TimeUnit.SECONDS);
            } catch (CancellationException cancelEx) {
                /* NOP */
            } catch (InterruptedException | TimeoutException ex) {
                logger.error("Failed to shutdown light merge task in a timely manner");
            } catch (ExecutionException err) {
                logger.error("Error in lighting merge during task shutdown", err);
            }
            resultFuture = null;
        }
        running = false;
    }

    public void restart() {
        if (!running) {
            running = true;
        }
    }
}
