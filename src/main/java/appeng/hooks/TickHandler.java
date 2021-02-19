/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.hooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.chunk.ChunkManager;

import appeng.api.networking.IGridNode;
import appeng.api.util.AEColor;
import appeng.core.AEConfig;
import appeng.core.AELog;
import appeng.core.sync.packets.PaintedEntityPacket;
import appeng.crafting.CraftingJob;
import appeng.me.Grid;
import appeng.tile.AEBaseBlockEntity;
import appeng.util.IWorldCallable;
import appeng.util.Platform;

public class TickHandler {

    private static TickHandler INSTANCE;
    private final Queue<IWorldCallable<?>> serverQueue = new ArrayDeque<>();
    private final Multimap<World, CraftingJob> craftingJobs = LinkedListMultimap.create();
    private final Map<WorldAccess, Queue<IWorldCallable<?>>> callQueue = new WeakHashMap<>();
    private final HandlerRep server = new HandlerRep();
    private final HandlerRep client = new HandlerRep();
    private final HashMap<Integer, PlayerColor> srvPlayerColors = new HashMap<>();

    public TickHandler() {
        if (INSTANCE != null) {
            throw new IllegalStateException("There can only be a single tick handler.");
        }
        INSTANCE = this;

        // Register for all the tick events we care about
        ServerTickEvents.END_SERVER_TICK.register(this::onAfterServerTick);
        ServerTickEvents.START_WORLD_TICK.register(this::onBeforeWorldTick);
        ServerTickEvents.END_WORLD_TICK.register(this::onAfterWorldTick);
        ServerWorldEvents.UNLOAD.register(this::onUnloadWorld);
    }

    public static TickHandler instance() {
        return INSTANCE;
    }

    public Map<Integer, PlayerColor> getPlayerColors() {
        return this.srvPlayerColors;
    }

    public void addCallable(final WorldAccess w, final IWorldCallable<?> c) {
        if (w == null) {
            this.serverQueue.add(c);
        } else {
            Queue<IWorldCallable<?>> queue = this.callQueue.get(w);

            if (queue == null) {
                queue = new ArrayDeque<>();
                this.callQueue.put(w, queue);
            }

            queue.add(c);
        }
    }

    public void addInit(final AEBaseBlockEntity tile) {
        // for no there is no reason to care about this on the client...
        if (Platform.isServer()) {
            this.getRepo().tiles.add(tile);
        }
    }

    private HandlerRep getRepo() {
        if (Platform.isServer()) {
            return this.server;
        }
        return this.client;
    }

    public void addNetwork(final Grid grid) {
        // for no there is no reason to care about this on the client...
        if (Platform.isServer()) {
            this.getRepo().addNetwork(grid);
        }
    }

    public void removeNetwork(final Grid grid) {
        // for no there is no reason to care about this on the client...
        if (Platform.isServer()) {
            this.getRepo().removeNetwork(grid);
        }
    }

    public Iterable<Grid> getGridList() {
        return this.getRepo().networks;
    }

    public void shutdown() {
        this.getRepo().clear();
    }

    public void onUnloadWorld(MinecraftServer server, ServerWorld world) {
        final List<IGridNode> toDestroy = new ArrayList<>();

        this.getRepo().updateNetworks();
        for (final Grid g : this.getRepo().networks) {
            for (final IGridNode n : g.getNodes()) {
                if (n.getWorld() == world) {
                    toDestroy.add(n);
                }
            }
        }

        for (final IGridNode n : toDestroy) {
            n.destroy();
        }
    }

    private void onBeforeWorldTick(ServerWorld world) {
        final Queue<IWorldCallable<?>> queue = this.callQueue.get(world);
        this.processQueue(queue, world);
    }

    private void onAfterWorldTick(ServerWorld world) {
        synchronized (this.craftingJobs) {
            final Collection<CraftingJob> jobSet = this.craftingJobs.get(world);
            if (!jobSet.isEmpty()) {
                final int jobSize = jobSet.size();
                final int microSecondsPerTick = AEConfig.instance().getCraftingCalculationTimePerTick() * 1000;
                final int simTime = Math.max(1, microSecondsPerTick / jobSize);
                final Iterator<CraftingJob> i = jobSet.iterator();
                while (i.hasNext()) {
                    final CraftingJob cj = i.next();
                    if (!cj.simulateFor(simTime)) {
                        i.remove();
                    }
                }
            }
        }
    }

    private void onAfterServerTick(MinecraftServer server) {
        this.tickColors(this.srvPlayerColors);
        // ready tiles.
        List<AEBaseBlockEntity> delayQueue = null;
        final HandlerRep repo = this.getRepo();
        while (!repo.tiles.isEmpty()) {
            final AEBaseBlockEntity bt = repo.tiles.poll();
            if (!bt.isRemoved()) {
                // If the tile entity is in a chunk that is in the progress of being loaded,
                // re-queue the tile-entity until the chunk is ready for ticking tile-entities
                // Vanilla also checks "shouldTickBlock" before ticking tile-entities in chunks
                ChunkManager chunkProvider = bt.getWorld().getChunkManager();
                if (chunkProvider.shouldTickBlock(bt.getPos())) {
                    bt.onReady();
                } else {
                    // Be defensive about the chunk being unloaded already and don't re-queue
                    // the tile entity if the chunk no longer exists to avoid endlessly re-queueing TEs
                    ChunkPos chunkPos = new ChunkPos(bt.getPos());
                    if (chunkProvider.isChunkLoaded(chunkPos.x, chunkPos.z)) {
                        if (delayQueue == null) {
                            delayQueue = new ArrayList<>();
                        }
                        delayQueue.add(bt);
                    } else {
                        AELog.warn("Skipping onReady for Tile-Entity in unloaded chunk %s", chunkPos);
                    }
                }
            }
        }

        // Re-insert tiles that have to wait
        if (delayQueue != null) {
            AELog.debug("Delaying onReady for %s tile-entities because their chunks are not fully loaded",
                    delayQueue.size());
            repo.tiles.addAll(delayQueue);
        }

        // tick networks.
        this.getRepo().updateNetworks();
        for (final Grid g : this.getRepo().networks) {
            g.update();
        }

        // cross world queue.
        this.processQueue(this.serverQueue, null);
    }

    public void registerCraftingSimulation(final World world, final CraftingJob craftingJob) {
        synchronized (this.craftingJobs) {
            this.craftingJobs.put(world, craftingJob);
        }
    }

    protected void tickColors(final Map<Integer, PlayerColor> playerSet) {
        final Iterator<PlayerColor> i = playerSet.values().iterator();

        while (i.hasNext()) {
            final PlayerColor pc = i.next();
            if (pc.ticksLeft <= 0) {
                i.remove();
            }
            pc.ticksLeft--;
        }
    }

    private void processQueue(final Queue<IWorldCallable<?>> queue, final World world) {
        if (queue == null) {
            return;
        }

        final Stopwatch sw = Stopwatch.createStarted();

        IWorldCallable<?> c = null;
        while ((c = queue.poll()) != null) {
            try {
                c.call(world);

                if (sw.elapsed(TimeUnit.MILLISECONDS) > 50) {
                    break;
                }
            } catch (final Exception e) {
                AELog.debug(e);
            }
        }
    }

    private static class HandlerRep {

        private Queue<AEBaseBlockEntity> tiles = new ArrayDeque<>();
        private Set<Grid> networks = new HashSet<>();
        private Set<Grid> toAdd = new HashSet<>();
        private Set<Grid> toRemove = new HashSet<>();

        private void clear() {
            this.tiles = new ArrayDeque<>();
            this.networks = new HashSet<>();
            this.toAdd = new HashSet<>();
            this.toRemove = new HashSet<>();
        }

        private synchronized void addNetwork(Grid g) {
            this.toAdd.add(g);
            this.toRemove.remove(g);
        }

        private synchronized void removeNetwork(Grid g) {
            this.toRemove.add(g);
            this.toAdd.remove(g);
        }

        private synchronized void updateNetworks() {
            this.networks.removeAll(this.toRemove);
            this.toRemove.clear();

            this.networks.addAll(this.toAdd);
            this.toAdd.clear();
        }
    }

    public static class PlayerColor {

        public final AEColor myColor;
        private final int myEntity;
        private int ticksLeft;

        public PlayerColor(final int id, final AEColor col, final int ticks) {
            this.myEntity = id;
            this.myColor = col;
            this.ticksLeft = ticks;
        }

        public PaintedEntityPacket getPacket() {
            return new PaintedEntityPacket(this.myEntity, this.myColor, this.ticksLeft);
        }
    }
}
