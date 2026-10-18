package com.blib.api.common.pathfinding.v1.node;

import java.util.Objects;

import com.blib.api.common.pathfinding.v1.terrain.TerrainType;

/**
 * A single position in pathfinding space with terrain classification and A* costs. Position and terrain type are fixed
 * at creation. Costs and parent are mutable for A* use.
 */
public final class PathNode implements Comparable<PathNode> {

    private final int x;

    private final int y;

    private final int z;

    private final TerrainType terrainType;

    private final PathPosture posture;

    private float gCost;

    private float hCost;

    private float costMalus;

    private float pendingCostMalus;

    private PathBlockBreakPlan blockBreakPlan = PathBlockBreakPlan.EMPTY;

    private PathBlockBreakPlan pendingBlockBreakPlan = PathBlockBreakPlan.EMPTY;

    private PathNode parent;

    private boolean hasDropEntryWaypoint;

    private double dropEntryX;

    private double dropEntryY;

    private double dropEntryZ;

    private boolean hasPendingDropEntryWaypoint;

    private double pendingDropEntryX;

    private double pendingDropEntryY;

    private double pendingDropEntryZ;

    /** Oct 18: this step is LEAPT to across a gap, from the take-off point below. See PathLeapConfig. */
    private boolean leap;

    private double leapTakeoffX;

    private double leapTakeoffY;

    private double leapTakeoffZ;

    private boolean hasPendingLeap;

    private double pendingLeapTakeoffX;

    private double pendingLeapTakeoffY;

    private double pendingLeapTakeoffZ;

    private boolean closed;

    private boolean hasStableGround;

    private int stableGroundX;

    private int stableGroundY;

    private int stableGroundZ;

    private int stableGroundXSize;

    private int stableGroundZSize;

    public PathNode(int x, int y, int z, TerrainType terrainType) {
        this(x, y, z, terrainType, PathPosture.STANDING);
    }

    public PathNode(int x, int y, int z, TerrainType terrainType, PathPosture posture) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.terrainType = terrainType;
        this.posture = posture;
    }

    public float totalCost() {
        return gCost + hCost;
    }

    public float distanceTo(PathNode other) {
        var dx = (float) (other.x - x);
        var dy = (float) (other.y - y);
        var dz = (float) (other.z - z);

        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public float distanceSquaredTo(PathNode other) {
        var dx = (float) (other.x - x);
        var dy = (float) (other.y - y);
        var dz = (float) (other.z - z);

        return dx * dx + dy * dy + dz * dz;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public TerrainType getTerrainType() {
        return terrainType;
    }

    public PathPosture getPosture() {
        return posture;
    }

    public boolean requiresCrawling() {
        return posture.isCrawling();
    }

    public boolean requiresSwimming() {
        return posture.isSwimming();
    }

    public float getGCost() {
        return gCost;
    }

    public void setGCost(float gCost) {
        this.gCost = gCost;
    }

    public float getHCost() {
        return hCost;
    }

    public void setHCost(float hCost) {
        this.hCost = hCost;
    }

    public float getCostMalus() {
        return costMalus;
    }

    public void setCostMalus(float costMalus) {
        this.costMalus = costMalus;
    }

    public float getPendingCostMalus() {
        return pendingCostMalus;
    }

    public void setPendingTraversal(float costMalus) {
        setPendingTraversal(costMalus, PathBlockBreakPlan.EMPTY);
    }

    public void setPendingTraversal(float costMalus, PathBlockBreakPlan blockBreakPlan) {
        this.pendingCostMalus = costMalus;
        this.pendingBlockBreakPlan = blockBreakPlan != null ? blockBreakPlan : PathBlockBreakPlan.EMPTY;
        this.hasPendingDropEntryWaypoint = false;
        // ⚠ A fresh traversal is never a leap unless the leap move sets it again after this - the same node is reached
        // by other edges, and one of them must not inherit another's leap.
        this.hasPendingLeap = false;
    }

    public void commitPendingTraversal() {
        this.costMalus = pendingCostMalus;
        this.blockBreakPlan = pendingBlockBreakPlan;

        if (hasPendingDropEntryWaypoint) {
            this.hasDropEntryWaypoint = true;
            this.dropEntryX = pendingDropEntryX;
            this.dropEntryY = pendingDropEntryY;
            this.dropEntryZ = pendingDropEntryZ;
        } else {
            this.hasDropEntryWaypoint = false;
        }

        this.hasPendingDropEntryWaypoint = false;
        this.pendingBlockBreakPlan = PathBlockBreakPlan.EMPTY;

        this.leap = hasPendingLeap;

        if (hasPendingLeap) {
            this.leapTakeoffX = pendingLeapTakeoffX;
            this.leapTakeoffY = pendingLeapTakeoffY;
            this.leapTakeoffZ = pendingLeapTakeoffZ;
        }

        this.hasPendingLeap = false;
    }

    public PathNode getParent() {
        return parent;
    }

    public void setParent(PathNode parent) {
        this.parent = parent;
    }

    public boolean hasDropEntryWaypoint() {
        return hasDropEntryWaypoint;
    }

    public double getDropEntryX() {
        return dropEntryX;
    }

    public double getDropEntryY() {
        return dropEntryY;
    }

    public double getDropEntryZ() {
        return dropEntryZ;
    }

    /** {@return whether this step is reached by leaping across a gap} */
    public boolean isLeap() {
        return leap;
    }

    /** {@return the take-off point of the leap that reaches this step: the centre of the cell it leaps from} */
    public double getLeapTakeoffX() {
        return leapTakeoffX;
    }

    public double getLeapTakeoffY() {
        return leapTakeoffY;
    }

    public double getLeapTakeoffZ() {
        return leapTakeoffZ;
    }

    /** Marks the edge being considered as a leap from this take-off point; kept only if the edge wins. */
    public void setPendingLeap(double x, double y, double z) {
        this.hasPendingLeap = true;
        this.pendingLeapTakeoffX = x;
        this.pendingLeapTakeoffY = y;
        this.pendingLeapTakeoffZ = z;
    }

    public void setPendingDropEntryWaypoint(double x, double y, double z) {
        this.hasPendingDropEntryWaypoint = true;
        this.pendingDropEntryX = x;
        this.pendingDropEntryY = y;
        this.pendingDropEntryZ = z;
    }

    public PathBlockBreakPlan getBlockBreakPlan() {
        return blockBreakPlan;
    }

    public boolean requiresBlockBreaking() {
        return !blockBreakPlan.isEmpty();
    }

    public PathBlockBreakPlan getPendingBlockBreakPlan() {
        return pendingBlockBreakPlan;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public boolean hasStableGround() {
        return hasStableGround;
    }

    public int getStableGroundX() {
        return stableGroundX;
    }

    public int getStableGroundY() {
        return stableGroundY;
    }

    public int getStableGroundZ() {
        return stableGroundZ;
    }

    public int getStableGroundXSize() {
        return stableGroundXSize;
    }

    public int getStableGroundZSize() {
        return stableGroundZSize;
    }

    public void setStableGround(int x, int y, int z) {
        setStableGround(x, y, z, 1, 1);
    }

    public void setStableGround(int x, int y, int z, int xSize, int zSize) {
        this.hasStableGround = true;
        this.stableGroundX = x;
        this.stableGroundY = y;
        this.stableGroundZ = z;
        this.stableGroundXSize = Math.max(1, xSize);
        this.stableGroundZSize = Math.max(1, zSize);
    }

    public void copyStableGroundFrom(PathNode node) {
        if (!node.hasStableGround()) {
            this.hasStableGround = false;
            this.stableGroundXSize = 0;
            this.stableGroundZSize = 0;
            return;
        }

        setStableGround(
            node.getStableGroundX(),
            node.getStableGroundY(),
            node.getStableGroundZ(),
            node.getStableGroundXSize(),
            node.getStableGroundZSize()
        );
    }

    @Override
    public int compareTo(PathNode other) {
        return Float.compare(totalCost(), other.totalCost());
    }

    public void reset() {
        this.gCost = 0;
        this.hCost = 0;
        this.costMalus = 0;
        this.pendingCostMalus = 0;
        this.blockBreakPlan = PathBlockBreakPlan.EMPTY;
        this.pendingBlockBreakPlan = PathBlockBreakPlan.EMPTY;
        this.parent = null;
        this.hasDropEntryWaypoint = false;
        this.hasPendingDropEntryWaypoint = false;
        this.leap = false;
        this.hasPendingLeap = false;
        this.closed = false;
        this.hasStableGround = false;
        this.stableGroundXSize = 0;
        this.stableGroundZSize = 0;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof PathNode other)) {
            return false;
        }

        return x == other.x
            && y == other.y
            && z == other.z
            && terrainType == other.terrainType
            && posture == other.posture;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z, terrainType, posture);
    }

    @Override
    public String toString() {
        return "PathNode[x=" + x + ", y=" + y + ", z=" + z + ", terrain=" + terrainType + ", posture=" + posture + "]";
    }
}
