package org.terasology.blockNetwork;

import com.google.common.base.Predicate;
import org.terasology.math.Side;
import org.terasology.math.SideBitFlag;
import org.terasology.math.Vector3i;

/**
 * @author Marcin Sciesinski <marcins78@gmail.com>
 */
public class SidedLocationNetworkNode extends LocationNetworkNode {
    public final byte connectionSides;

    public SidedLocationNetworkNode(Vector3i location, byte connectionSides) {
        super(location);
        this.connectionSides = connectionSides;
    }

    public SidedLocationNetworkNode(Vector3i location, Side... sides) {
        this(location, SideBitFlag.getSides(sides));
    }

    @Override
    public boolean equals(Object o) {
        if( !super.equals(o)) return false;

        if (this == o) return true;
        if (o == null || !this.getClass().isAssignableFrom(o.getClass())) return false;

        SidedLocationNetworkNode that = (SidedLocationNetworkNode) o;
        if (connectionSides != that.connectionSides) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (int) connectionSides;
        return result;
    }

    @Override
    public String toString() {
        return super.toString()+ " "+connectionSides;
    }

    @Override
    public boolean isConnectedTo(NetworkNode networkNode) {
        if( super.isConnectedTo(networkNode)) {
            SidedLocationNetworkNode locationNetworkNode = (SidedLocationNetworkNode) networkNode;
            return areConnected(location, connectionSides, locationNetworkNode.location, locationNetworkNode.connectionSides);
        }

        return false;
    }

    private static boolean areConnected(Vector3i lhsLocation, byte lhsSide, Vector3i rhsLocation, byte rhsSide) {
        Vector3i sideVector = rhsLocation.clone();
        sideVector.sub(lhsLocation);
        Side side = Side.inDirection(sideVector.toVector3f());
        byte sideBit = SideBitFlag.getSide(side);

        return (sideBit & lhsSide) == sideBit && (SideBitFlag.getReverse(sideBit) & rhsSide) == SideBitFlag.getReverse(sideBit);

    }

    public Side connectionSide(SidedLocationNetworkNode node) {
        Vector3i sideVector = node.location.clone();
        sideVector.sub(location);
        Side side = Side.inDirection(sideVector.toVector3f());
        return side;
    }

    public static Predicate<TwoNetworkNodes> createSideConnectivityFilter(Side targetSide, Vector3i targetLocation) {
        return new SideConnectivityFilter(targetSide, targetLocation);
    }

    private static class SideConnectivityFilter implements Predicate<TwoNetworkNodes> {
        final Side targetSide;
        final Vector3i targetLocation;
        public SideConnectivityFilter(Side targetSide, Vector3i targetLocation) {
            this.targetSide = targetSide;
            this.targetLocation = targetLocation;
        }

        @Override
        public boolean apply(TwoNetworkNodes input) {
            if(!( input.node1 instanceof SidedLocationNetworkNode && input.node2 instanceof SidedLocationNetworkNode)) {
                return false;
            }

            SidedLocationNetworkNode source = (SidedLocationNetworkNode) input.node1;
            SidedLocationNetworkNode target = (SidedLocationNetworkNode) input.node2;

            if( target.location.equals(targetLocation)) {
                byte targetSideBitFlag = SideBitFlag.getSide(targetSide);
                if( ( targetSideBitFlag & target.connectionSides) == targetSideBitFlag ) {
                    return areConnected( source.location, source.connectionSides, target.location, targetSideBitFlag);
                }else { return false; }

            }else {
                return true;
            }
        }
    }
}
