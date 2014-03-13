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
package org.terasology.blockNetwork;

import org.terasology.math.Side;
import org.terasology.math.Vector3i;

public class LocationNetworkNode implements NetworkNode {
    public final Vector3i location;

    public LocationNetworkNode(Vector3i location) {
        this.location = location;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !this.getClass().isAssignableFrom(o.getClass())) return false;

        LocationNetworkNode that = (LocationNetworkNode) o;
        if (location != null ? !location.equals(that.location) : that.location != null) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public int hashCode() {
        int result = location != null ? location.hashCode() : 0;
        return result;
    }

    @Override
    public String toString() {
        return location.toString();
    }

    @Override
    public boolean isConnectedTo(NetworkNode networkNode) {
        if (networkNode == null || !(networkNode instanceof LocationNetworkNode)) return false;

        LocationNetworkNode locationNetworkNode = (LocationNetworkNode) networkNode;

        // allow for blocks to have multiple network connections
        if( locationNetworkNode.location.equals(location)) return true;

        for(Side side : Side.values()) {
            if( locationNetworkNode.location.equals(side.getAdjacentPos(location))) {
                return true;
            }
        }

        return false;
    }
}
