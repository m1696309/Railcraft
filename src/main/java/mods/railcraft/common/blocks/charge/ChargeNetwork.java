/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2016
 http://railcraft.info

 This code is the property of CovertJaguar
 and may only be used with explicit written
 permission unless otherwise specified on the
 license page at http://railcraft.info/wiki/info:license.
 -----------------------------------------------------------------------------*/

package mods.railcraft.common.blocks.charge;

import com.google.common.collect.ForwardingSet;
import com.google.common.collect.MapMaker;
import mods.railcraft.common.plugins.forge.WorldPlugin;
import mods.railcraft.common.util.misc.Game;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Consumer;

/**
 * Created by CovertJaguar on 7/23/2016 for Railcraft.
 *
 * @author CovertJaguar <http://www.railcraft.info>
 */
public class ChargeNetwork {
    private static final ChargeGraph NULL_GRAPH = new NullGraph();
    public final Map<BlockPos, ChargeNode> chargeNodes = new HashMap<>();
    public final Map<BlockPos, ChargeNode> chargeQueue = new HashMap<>();
    public final Set<ChargeGraph> chargeGraphs = Collections.newSetFromMap(new WeakHashMap<>());
    private final WeakReference<World> world;

    public ChargeNetwork(World world) {
        this.world = new WeakReference<World>(world);
    }

    public void tick() {
        Set<ChargeNode> added = new HashSet<>();
        Iterator<Map.Entry<BlockPos, ChargeNode>> iterator = chargeQueue.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, ChargeNode> action = iterator.next();
            if (action.getValue() == null) {
                deleteNode(action.getKey());
                iterator.remove();
            } else {
                insertNode(action.getKey(), action.getValue());
                added.add(action.getValue());
                iterator.remove();
            }
        }
        chargeQueue.clear();

        for (ChargeNode chargeNode : added) {
            if (chargeNode.isGraphNull())
                chargeNode.constructGraph();
        }

        chargeGraphs.removeIf(g -> g.invalid);
        chargeGraphs.forEach(ChargeGraph::tick);
    }

    private void insertNode(BlockPos pos, ChargeNode node) {
        ChargeNode oldNode = chargeNodes.put(pos, node);
        if (oldNode != null) {
            oldNode.invalid = true;
            if (oldNode.chargeGraph.isActive()) {
                node.chargeGraph = oldNode.chargeGraph;
                node.chargeGraph.add(node);
            }
            oldNode.chargeGraph = NULL_GRAPH;
        }
    }

    private void deleteNode(BlockPos pos) {
        ChargeNode chargeNode = chargeNodes.remove(pos);
        chargeNode.invalid = true;
        chargeNode.chargeGraph.destroy();
    }

    public boolean registerChargeNode(World world, BlockPos pos, IChargeBlock.ChargeDef chargeDef) {
        if (!nodeMatches(pos, chargeDef)) {
//            Game.log(Level.INFO, "Registering Node: {0}->{1}", pos, chargeDef);
            chargeQueue.put(pos, new ChargeNode(pos, chargeDef, chargeDef.makeBattery(world, pos)));
            return true;
        }
        return false;
    }

    public void deregisterChargeNode(BlockPos pos) {
        chargeQueue.put(pos, null);
    }

    public boolean isUndefined(BlockPos pos) {
        ChargeNode chargeNode = chargeNodes.get(pos);
        return chargeNode == null || chargeNode.isGraphNull();
    }

    public ChargeGraph getGraph(BlockPos pos) {
        ChargeNode chargeNode = chargeNodes.get(pos);
        if (chargeNode == null) {
            World worldObj = world.get();
            if (worldObj != null) {
                IBlockState state = WorldPlugin.getBlockState(worldObj, pos);
                if (state.getBlock() instanceof IChargeBlock) {
                    IChargeBlock.ChargeDef chargeDef = ((IChargeBlock) state.getBlock()).getChargeDef(state, worldObj, pos);
                    if (chargeDef != null) {
                        chargeNode = new ChargeNode(pos, chargeDef, chargeDef.makeBattery(worldObj, pos));
                        insertNode(pos, chargeNode);
                        if (chargeNode.isGraphNull())
                            chargeNode.constructGraph();
                    }
                }
            }
        }
        if (chargeNode == null)
            return NULL_GRAPH;
        return chargeNode.getChargeGraph();
    }

    @Nullable
    public ChargeNode getNode(BlockPos pos) {
        ChargeNode node = chargeNodes.get(pos);
        if (node != null && node.invalid) {
            deleteNode(pos);
            return null;
        }
        return node;
    }

    public boolean nodeMatches(BlockPos pos, IChargeBlock.ChargeDef chargeDef) {
        ChargeNode node = getNode(pos);
        return node != null && !node.invalid && node.chargeDef == chargeDef;
    }

    public static class ChargeGraph extends ForwardingSet<ChargeNode> {
        private final Set<ChargeNode> chargeNodes = new HashSet<>();
        private final Map<ChargeNode, IChargeBlock.ChargeBattery> chargeBatteries = new MapMaker().weakKeys().weakValues().makeMap();
        private boolean invalid;

        @Override
        protected Set<ChargeNode> delegate() {
            return chargeNodes;
        }

        @Override
        public boolean add(ChargeNode chargeNode) {
            boolean added = super.add(chargeNode);
            if (added) {
                chargeNode.chargeGraph = this;
                if (chargeNode.chargeBattery != null)
                    chargeBatteries.put(chargeNode, chargeNode.chargeBattery);
                else
                    chargeBatteries.remove(chargeNode);
            }
            return added;
        }

        @Override
        public boolean addAll(Collection<? extends ChargeNode> collection) {
            return standardAddAll(collection);
        }

        private void destroy() {
            if (isActive()) {
                Game.log(Level.INFO, "Destroying graph: {0}", this);
                invalid = true;
                forEach(n -> n.chargeGraph = NULL_GRAPH);
                clear();
            }
        }

        @Override
        public void clear() {
            chargeBatteries.clear();
            super.clear();
        }

        private void tick() {
            //TODO: apply maintenance cost
            double averageCharge = chargeBatteries.values().stream().mapToDouble(IChargeBlock.ChargeBattery::getCharge).average().orElse(0.0);
            chargeBatteries.values().forEach(b -> b.setCharge(averageCharge));
        }

        public double getCharge() {
            return chargeBatteries.values().stream().mapToDouble(IChargeBlock.ChargeBattery::getCharge).sum();
        }

        public boolean isActive() {
            return !isNull();
        }

        public boolean isNull() {
            return false;
        }

        /**
         * Remove the requested amount of charge if possible and
         * return whether sufficient charge was available to perform the operation.
         *
         * @return true if charge could be removed in full
         */
        public boolean useCharge(double amount) {
            double searchAmount = 0;
            for (IChargeBlock.ChargeBattery battery : chargeBatteries.values()) {
                searchAmount += battery.getCharge();
                if (searchAmount >= amount)
                    break;
            }
            if (searchAmount >= amount) {
                for (IChargeBlock.ChargeBattery battery : chargeBatteries.values()) {
                    amount -= battery.removeCharge(amount);
                    if (amount <= 0.0)
                        break;
                }
            }
            return amount <= 0.0;
        }

        /**
         * Remove up to the requested amount of charge and returns the amount
         * removed.
         *
         * @return charge removed
         */
        public double removeCharge(double amount) {
            double amountNeeded = amount;
            for (IChargeBlock.ChargeBattery battery : chargeBatteries.values()) {
                amountNeeded -= battery.removeCharge(amountNeeded);
                if (amountNeeded <= 0.0)
                    break;
            }
            return amount - amountNeeded;
        }

        @Override
        public String toString() {
            return String.format("ChargeGraph{s=%d,b=%d}", size(), chargeBatteries.size());
        }
    }

    private static class NullGraph extends ChargeGraph {
        @Override
        protected Set<ChargeNode> delegate() {
            return Collections.emptySet();
        }

        @Override
        public boolean isNull() {
            return true;
        }

        @Override
        public String toString() {
            return "ChargeGraph{NullGraph}";
        }
    }

    public final class ChargeNode {
        @Nullable
        protected final IChargeBlock.ChargeBattery chargeBattery;
        private final BlockPos pos;
        private final IChargeBlock.ChargeDef chargeDef;
        private ChargeGraph chargeGraph = NULL_GRAPH;
        private boolean invalid;

        private ChargeNode(BlockPos pos, IChargeBlock.ChargeDef chargeDef, @Nullable IChargeBlock.ChargeBattery chargeBattery) {
            this.pos = pos;
            this.chargeDef = chargeDef;
            this.chargeBattery = chargeBattery;
        }

        public IChargeBlock.ChargeDef getChargeDef() {
            return chargeDef;
        }

        private void forConnections(Consumer<ChargeNode> action) {
            Map<BlockPos, EnumSet<IChargeBlock.ConnectType>> possibleConnections = chargeDef.getConnectType().getPossibleConnectionLocations(pos);
            for (Map.Entry<BlockPos, EnumSet<IChargeBlock.ConnectType>> connection : possibleConnections.entrySet()) {
                ChargeNode other = chargeNodes.get(connection.getKey());
                if (other != null && connection.getValue().contains(other.chargeDef.getConnectType())
                        && other.chargeDef.getConnectType().getPossibleConnectionLocations(connection.getKey()).get(pos).contains(chargeDef.getConnectType())) {
                    action.accept(other);
                }
            }
        }

        private ChargeGraph getChargeGraph() {
            if (chargeGraph.isActive())
                return chargeGraph;
            constructGraph();
            return chargeGraph;
        }

        public boolean isGraphNull() {
            return chargeGraph.isNull();
        }

        private void constructGraph() {
            Set<ChargeNode> visitedNodes = new HashSet<>();
            visitedNodes.add(this);
            Set<ChargeNode> nullNodes = new HashSet<>();
            nullNodes.add(this);
            Set<ChargeNode> newNodes = new HashSet<>();
            newNodes.add(this);
            TreeSet<ChargeGraph> graphs = new TreeSet<>((o1, o2) -> Integer.compare(o1.size(), o2.size()));
            graphs.add(chargeGraph);
            while (!newNodes.isEmpty()) {
                Set<ChargeNode> currentNodes = new HashSet<>(newNodes);
                newNodes.clear();
                for (ChargeNode current : currentNodes) {
                    current.forConnections(n -> {
                        if (!visitedNodes.contains(n) && (n.isGraphNull() || !graphs.contains(n.chargeGraph))) {
                            if (n.isGraphNull())
                                nullNodes.add(n);
                            graphs.add(n.chargeGraph);
                            visitedNodes.add(n);
                            newNodes.add(n);
                        }
                    });
                }
            }
            chargeGraph = graphs.pollLast();
            if (chargeGraph.isNull() && nullNodes.size() > 1)
                chargeGraph = new ChargeGraph();
            if (chargeGraph.isActive()) {
                chargeGraph.addAll(nullNodes);
                for (ChargeGraph graph : graphs) {
                    chargeGraph.addAll(graph);
                }
                graphs.forEach(ChargeGraph::clear);
                Game.log(Level.INFO, "Constructing Graph: {0}->{1}", pos, chargeGraph);
            }
        }

//        private void mergeGraph(ChargeNode otherNode) {
//            if (chargeGraph != otherNode.chargeGraph) {
//                if (chargeGraph.isActive()) {
//                    if (otherNode.chargeGraph.isActive()) {
//                        boolean larger = chargeGraph.size() >= otherNode.chargeGraph.size();
//                        ChargeGraph smallerGraph = !larger ? chargeGraph : otherNode.chargeGraph;
//                        ChargeGraph largerGraph = larger ? chargeGraph : otherNode.chargeGraph;
//                        largerGraph.addAll(smallerGraph);
//                        smallerGraph.clear();
//                        chargeGraph = largerGraph;
//                    } else {
//                        otherNode.chargeGraph = chargeGraph;
//                    }
//                } else {
//                    if (otherNode.chargeGraph.isActive()) {
//                        chargeGraph = otherNode.chargeGraph;
//                    } else {
//                        chargeGraph = new ChargeGraph();
//                        otherNode.chargeGraph = chargeGraph;
//                    }
//                }
//                chargeGraph.add(this);
//                chargeGraph.add(otherNode);
//            }
//        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ChargeNode that = (ChargeNode) o;

            return pos.equals(that.pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }

        @Nullable
        public IChargeBlock.ChargeBattery getBattery() {
            return chargeBattery;
        }

        @Override
        public String toString() {
            return String.format("ChargeNode{%s|%s}", pos, chargeDef);
        }
    }
}