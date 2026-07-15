package com.delta.utilitymod;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Grid A* pathfinder over standable feet positions.
 *
 * Supports walking, diagonal walking, single-block step-ups, drops of up to
 * three blocks, and (optionally) tunneling through mineable blocks. Tunneling
 * through blocks inside the current selection is cheap because those blocks
 * have to be mined anyway; tunneling elsewhere is heavily penalized so the
 * path prefers going around terrain instead of eating through it.
 */
final class DeltaPathfinder {

    /** One waypoint: where the feet should end up, plus blocks that must be broken to get there. */
    static final class Step {
        final BlockPos feet;
        final List<BlockPos> toClear;

        Step(BlockPos feet, List<BlockPos> toClear) {
            this.feet = feet;
            this.toClear = toClear;
        }
    }

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double EYE_HEIGHT = 1.62D;

    private static final double COST_WALK = 10.0D;
    private static final double COST_DIAGONAL = 14.14D;
    private static final double COST_STEP_UP = 15.0D;
    private static final double COST_DROP_PER_BLOCK = 5.0D;
    private static final double COST_MINE_IN_SELECTION = 12.0D;
    private static final double COST_MINE_OUTSIDE = 34.0D;
    private static final double COST_DIG_DOWN = 26.0D;

    private static final int MAX_HORIZONTAL_RADIUS_SQ = 96 * 96;
    private static final int MAX_VERTICAL_RADIUS = 40;

    private DeltaPathfinder() {
    }

    private static final class Node {
        final BlockPos feet;
        final double g;
        final double f;
        final Node parent;
        final List<BlockPos> toClear;

        Node(BlockPos feet, double g, double f, Node parent, List<BlockPos> toClear) {
            this.feet = feet;
            this.g = g;
            this.f = f;
            this.parent = parent;
            this.toClear = toClear;
        }
    }

    /**
     * Finds a path from the player's current feet position to any standable spot
     * whose eye position is within {@code reach} of {@code goal}.
     *
     * @param cheapMine positions whose mining cost should be cheap (inside the selection)
     * @param canMine   positions the bot is allowed to break at all
     * @return waypoint list (empty = already in reach), or null if no path was found
     */
    static List<Step> find(Minecraft client, BlockPos startFeet, Vec3 goal, double reach,
                           boolean allowMining, Predicate<BlockPos> cheapMine, Predicate<BlockPos> canMine,
                           int maxExpansions) {
        if (client == null || client.level == null || startFeet == null || goal == null) {
            return null;
        }
        if (inReach(startFeet, goal, reach)) {
            return List.of();
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));
        Map<Long, Double> bestCost = new HashMap<>();

        Node start = new Node(startFeet.immutable(), 0.0D, heuristic(startFeet, goal), null, List.of());
        open.add(start);
        bestCost.put(startFeet.asLong(), 0.0D);

        int expansions = 0;
        while (!open.isEmpty() && expansions < maxExpansions) {
            Node node = open.poll();
            Double known = bestCost.get(node.feet.asLong());
            if (known != null && node.g > known + 0.01D) {
                continue;
            }
            expansions++;

            if (inReach(node.feet, goal, reach)) {
                return reconstruct(node);
            }

            expand(client, node, startFeet, goal, allowMining, cheapMine, canMine, open, bestCost);
        }
        return null;
    }

    private static void expand(Minecraft client, Node node, BlockPos origin, Vec3 goal,
                               boolean allowMining, Predicate<BlockPos> cheapMine, Predicate<BlockPos> canMine,
                               PriorityQueue<Node> open, Map<Long, Double> bestCost) {
        BlockPos p = node.feet;

        for (int[] dir : CARDINALS) {
            BlockPos flat = p.offset(dir[0], 0, dir[1]);

            // Plain walk on the same level.
            if (standable(client, flat)) {
                offer(open, bestCost, node, origin, goal, flat, COST_WALK, List.of());
            } else if (passable(client, flat) && passable(client, flat.above())) {
                // Feet and head clear but no floor: try dropping up to three blocks.
                for (int drop = 1; drop <= 3; drop++) {
                    BlockPos landing = flat.below(drop);
                    if (!passable(client, landing)) {
                        break;
                    }
                    if (standable(client, landing)) {
                        offer(open, bestCost, node, origin, goal, landing,
                                COST_WALK + drop * COST_DROP_PER_BLOCK, List.of());
                        break;
                    }
                }
            } else if (allowMining) {
                // Walk-through: clear feet/head blocks at the same level.
                List<BlockPos> blockers = blockers(client, flat, canMine);
                if (blockers != null && solid(client, flat.below())) {
                    offer(open, bestCost, node, origin, goal, flat,
                            COST_WALK + mineCost(blockers, cheapMine), blockers);
                }
            }

            // Step up one block (with optional mining of the step-up column and headroom).
            BlockPos up = flat.above();
            boolean headroomClear = passable(client, p.above(2));
            if (standable(client, up) && headroomClear) {
                offer(open, bestCost, node, origin, goal, up, COST_STEP_UP, List.of());
            } else if (allowMining && solid(client, flat)) {
                List<BlockPos> blockers = blockers(client, up, canMine);
                if (blockers != null) {
                    List<BlockPos> all = new ArrayList<>(blockers);
                    if (!headroomClear) {
                        BlockPos headroom = p.above(2);
                        if (!canMine.test(headroom)) {
                            all = null;
                        } else {
                            all.add(headroom);
                        }
                    }
                    if (all != null) {
                        offer(open, bestCost, node, origin, goal, up,
                                COST_STEP_UP + mineCost(all, cheapMine), all);
                    }
                }
            }
        }

        // Diagonal walking, only when both orthogonal columns are open (no corner clipping).
        for (int[] dir : DIAGONALS) {
            BlockPos target = p.offset(dir[0], 0, dir[1]);
            if (!standable(client, target)) {
                continue;
            }
            BlockPos sideA = p.offset(dir[0], 0, 0);
            BlockPos sideB = p.offset(0, 0, dir[1]);
            if (passable(client, sideA) && passable(client, sideA.above())
                    && passable(client, sideB) && passable(client, sideB.above())) {
                offer(open, bestCost, node, origin, goal, target, COST_DIAGONAL, List.of());
            }
        }

        // Dig straight down, but only inside the selection (those blocks must go anyway).
        if (allowMining) {
            BlockPos below = p.below();
            if (cheapMine.test(below) && canMine.test(below) && solid(client, below.below())) {
                offer(open, bestCost, node, origin, goal, below, COST_DIG_DOWN, List.of(below));
            }
        }
    }

    /** Returns the mineable blockers in the feet+head column, or null if the column can't be opened. */
    private static List<BlockPos> blockers(Minecraft client, BlockPos feet, Predicate<BlockPos> canMine) {
        List<BlockPos> result = new ArrayList<>(2);
        BlockPos head = feet.above();

        if (!passable(client, feet)) {
            if (!canMine.test(feet)) {
                return null;
            }
            result.add(feet);
        }
        if (!passable(client, head)) {
            if (!canMine.test(head)) {
                return null;
            }
            result.add(head);
        }
        return result.isEmpty() ? null : result;
    }

    private static double mineCost(List<BlockPos> blockers, Predicate<BlockPos> cheapMine) {
        double cost = 0.0D;
        for (BlockPos pos : blockers) {
            cost += cheapMine.test(pos) ? COST_MINE_IN_SELECTION : COST_MINE_OUTSIDE;
        }
        return cost;
    }

    private static void offer(PriorityQueue<Node> open, Map<Long, Double> bestCost, Node from,
                              BlockPos origin, Vec3 goal, BlockPos feet, double moveCost, List<BlockPos> toClear) {
        int dx = feet.getX() - origin.getX();
        int dz = feet.getZ() - origin.getZ();
        if (dx * dx + dz * dz > MAX_HORIZONTAL_RADIUS_SQ || Math.abs(feet.getY() - origin.getY()) > MAX_VERTICAL_RADIUS) {
            return;
        }

        double g = from.g + moveCost;
        long key = feet.asLong();
        Double known = bestCost.get(key);
        if (known != null && known <= g) {
            return;
        }
        bestCost.put(key, g);
        open.add(new Node(feet.immutable(), g, g + heuristic(feet, goal), from, toClear));
    }

    private static double heuristic(BlockPos feet, Vec3 goal) {
        double dx = feet.getX() + 0.5D - goal.x;
        double dy = feet.getY() + EYE_HEIGHT - goal.y;
        double dz = feet.getZ() + 0.5D - goal.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) * COST_WALK;
    }

    private static boolean inReach(BlockPos feet, Vec3 goal, double reach) {
        double dx = feet.getX() + 0.5D - goal.x;
        double dy = feet.getY() + EYE_HEIGHT - goal.y;
        double dz = feet.getZ() + 0.5D - goal.z;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private static List<Step> reconstruct(Node goalNode) {
        List<Step> steps = new ArrayList<>();
        Node node = goalNode;
        while (node != null && node.parent != null) {
            steps.add(new Step(node.feet, node.toClear));
            node = node.parent;
        }
        java.util.Collections.reverse(steps);
        return steps;
    }

    /** Feet/head space the player can occupy: no collision and no lava. */
    static boolean passable(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        if (!state.getCollisionShape(client.level, pos).isEmpty()) {
            return false;
        }
        return !state.getFluidState().is(FluidTags.LAVA);
    }

    static boolean solid(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return !state.getCollisionShape(client.level, pos).isEmpty();
    }

    /** A position the player can stand in: feet and head clear, solid ground below. */
    static boolean standable(Minecraft client, BlockPos feet) {
        return passable(client, feet) && passable(client, feet.above()) && solid(client, feet.below());
    }
}
