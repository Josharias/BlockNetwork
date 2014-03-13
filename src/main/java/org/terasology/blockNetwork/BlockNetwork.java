package org.terasology.blockNetwork;

import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * @author Marcin Sciesinski <marcins78@gmail.com>
 */
public class BlockNetwork {
    private static final Logger logger = LoggerFactory.getLogger(BlockNetwork.class);

    private Supplier<Network> networkFactory;

    private Map<Network, Set<NetworkNode>> allNetworks = Maps.newHashMap();
    // an adjacency list of nodes connecting to each other
    private Map<NetworkNode, Set<NetworkNode>> allNetworkNodes = Maps.newHashMap();

    private Set<NetworkTopologyListener> listeners = Sets.newLinkedHashSet();

    private boolean mutating = false;

    public BlockNetwork() {
        networkFactory  = new BasicNetworkFactory();
    }

    public BlockNetwork(Supplier<Network> networkFactory) {
        this.networkFactory = networkFactory;
    }

    public void addTopologyListener(NetworkTopologyListener listener) {
        listeners.add(listener);
    }

    public void removeTopologyListener(NetworkTopologyListener listener) {
        listeners.remove(listener);
    }

    private void validateNotMutating() {
        if (mutating) {
            throw new IllegalStateException("Can't modify block network while modification is in progress");
        }
    }

    public void addNetworkingBlock(NetworkNode networkNode) {
        validateNotMutating();
        mutating = true;
        try {
            if (!allNetworkNodes.containsKey(networkNode)) {
                allNetworkNodes.put(networkNode, Sets.<NetworkNode>newHashSet());

                // loop through all the nodes and find connections
                for (NetworkNode existingNode : allNetworkNodes.keySet()) {
                    if (networkNode != existingNode && networkNode.isConnectedTo(existingNode)) {
                        allNetworkNodes.get(existingNode).add(networkNode);
                        allNetworkNodes.get(networkNode).add(existingNode);
                    }
                }

                addToNetwork(networkNode);
            }

        } finally {
            mutating = false;
        }
    }

    public void addNetworkingBlocks(Collection<NetworkNode> networkNodes) {
        // No major optimization possible here
        for (NetworkNode networkNode : networkNodes) {
            addNetworkingBlock(networkNode);
        }
    }

    private void addToNetwork(NetworkNode networkNode) {
        Set<NetworkNode> connectedNodes = allNetworkNodes.get(networkNode);

        Network network = null;
        for (NetworkNode connectedNode : connectedNodes) {
            Network foundNetwork = getNetwork(connectedNode);
            if (foundNetwork == null) {
                // abort, this should not happen
                return;
            } else if (network == null) {
                // this is the first network we found
                network = foundNetwork;
            } else if (foundNetwork != network) {
                // connect networks that have now become connected because of this new node
                mergeNetworks(network, foundNetwork);
            }
        }

        if (network == null) {
            network = networkFactory.get();
            allNetworks.put(network, Sets.<NetworkNode>newHashSet());
            notifyNetworkAdded(network);
        }

        allNetworks.get(network).add(networkNode);

        notifyNetworkingNodeAdded(network, networkNode);

    }

    public Network getNetwork(NetworkNode networkNode) {
        for (Network network : allNetworks.keySet()) {
            Set<NetworkNode> nodes = allNetworks.get(network);

            for (NetworkNode node : nodes) {
                if (node.equals(networkNode)) {
                    return network;
                }
            }
        }

        return null;
    }

    private void mergeNetworks(Network target, Network source) {
        Set<NetworkNode> nodesInSource = allNetworks.get(source);
        for (NetworkNode node : nodesInSource) {
            notifyNetworkingNodeRemoved(source, node);
        }
        allNetworks.remove(source);
        notifyNetworkRemoved(source);

        allNetworks.get(target).addAll(nodesInSource);
        for (NetworkNode node : nodesInSource) {
            notifyNetworkingNodeAdded(target, node);
        }

        source.mergeTo(target);
    }

    public void updateNetworkingBlock(NetworkNode oldNode, NetworkNode newNode) {
        logger.info("Replacing networking node: " + oldNode.toString() + " with: " + newNode.toString());
        removeNetworkingBlock(oldNode);
        addNetworkingBlock(newNode);
    }

    public void removeNetworkingBlock(NetworkNode networkNode) {
        validateNotMutating();
        mutating = true;
        try {
            Set<NetworkNode> connectedNodes = allNetworkNodes.get(networkNode);

            Network originalNetwork = getNetwork(networkNode);
            allNetworks.get(originalNetwork).remove(networkNode);
            notifyNetworkingNodeRemoved(originalNetwork, networkNode);

            allNetworkNodes.remove(networkNode);
            // remove all adjacent links
            for (NetworkNode connectedNode : connectedNodes) {
                allNetworkNodes.get(connectedNode).remove(networkNode);
            }

            Queue<NetworkNode> needsNewNetwork = Queues.newArrayDeque();
            needsNewNetwork.addAll(connectedNodes);

            // ensure that the network is still intact, if not,  split it up
            // touch all nodes in the starting from each of the connected nodes to the removed node
            // if a nodes is found in a previously touched list, it connects to that network
            Map<NetworkNode, NetworkNode> visitedNodes = Maps.newHashMap(); // where Key = a node in the network, Value = the connected node it originated from
            for (NetworkNode connectedNode : connectedNodes) {
                visitedNodes.put(connectedNode, connectedNode);
            }

            Queue<NetworkNode> currentNodes = Queues.newArrayDeque();
            for (NetworkNode connectedNode : connectedNodes) {
                if (needsNewNetwork.size() == 0) {
                    // we have already verified that nothing needs a new network
                    return;
                }

                currentNodes.add(connectedNode);

                // search through the nodes connecting to this one
                while (currentNodes.size() > 0) {
                    NetworkNode currentNode = currentNodes.poll();
                    if (visitedNodes.containsKey(currentNode) && currentNode != connectedNode) {
                        // we have visited this node already
                        needsNewNetwork.remove(currentNode);
                    } else {
                        for (NetworkNode node : allNetworkNodes.get(currentNode)) {
                            // add all these adjacent nodes to the list of nodes to visit
                            currentNodes.add(node);
                        }
                    }
                    visitedNodes.put(currentNode, connectedNode);
                }
            }

            // reuse the existing network
            needsNewNetwork.poll();
            // create new networks
            for (NetworkNode node : needsNewNetwork) {
                Network newNetwork = networkFactory.get();
                Set<NetworkNode> newNetworkNodes = Sets.newHashSet();
                allNetworks.put(newNetwork, newNetworkNodes);
                notifyNetworkAdded(newNetwork);
                Set<NetworkNode> originalNetworkNodes = allNetworks.get(originalNetwork);
                for (Map.Entry<NetworkNode, NetworkNode> item : visitedNodes.entrySet()) {
                    if (item.getValue() == node) {
                        originalNetworkNodes.remove(item.getKey());
                        notifyNetworkingNodeRemoved(originalNetwork, item.getKey());
                        newNetworkNodes.add(item.getKey());
                        notifyNetworkingNodeAdded(newNetwork, item.getKey());

                    }
                }

            }

            if (allNetworks.get(originalNetwork).size() == 0) {
                // this network is empty
                allNetworks.remove(originalNetwork);
                notifyNetworkRemoved(originalNetwork);
            }

        } finally {
            mutating = false;
        }
    }

    public void removeNetworkingBlocks(Collection<NetworkNode> networkNodes) {
        for (NetworkNode node : networkNodes) {
            removeNetworkingBlock(node);
        }
    }

    public Collection<Network> getNetworks() {
        return Collections.unmodifiableCollection(allNetworks.keySet());
    }

    public boolean isNetworkActive(Network network) {
        return allNetworks.containsKey(network);
    }


    private void notifyNetworkAdded(Network network) {
        for (NetworkTopologyListener listener : listeners) {
            listener.networkAdded(network);
        }
    }

    private void notifyNetworkRemoved(Network network) {
        for (NetworkTopologyListener listener : listeners) {
            listener.networkRemoved(network);
        }
    }

    private void notifyNetworkingNodeAdded(Network network, NetworkNode networkingNode) {
        for (NetworkTopologyListener listener : listeners) {
            listener.networkingNodeAdded(network, networkingNode);
        }
    }

    private void notifyNetworkingNodeRemoved(Network network, NetworkNode networkingNode) {
        for (NetworkTopologyListener listener : listeners) {
            listener.networkingNodeRemoved(network, networkingNode);
        }
    }

    public Iterable<NetworkNode> getNetworkNodes(Network network) {
        return allNetworks.get(network);
    }

    private class BasicNetwork implements Network {
        @Override
        public void mergeTo(Network network) {
        }
    }

    private class BasicNetworkFactory implements Supplier<Network> {
        @Override
        public Network get() {
            return new BasicNetwork();
        }
    }

    public boolean hasNetworkingNode(Network network, NetworkNode networkNode) {
        return allNetworks.get(network).contains(networkNode);
    }

    public int getNetworkSize() {
        return allNetworkNodes.size();
    }

    public Iterable<NetworkNode> getAdjacentNodes(NetworkNode node) {
        return allNetworkNodes.get(node);

    }

    public int getDistance(NetworkNode from, NetworkNode to) {
        return getDistance(from, to, null);
    }
    public int getDistance(NetworkNode from, NetworkNode to, Predicate<TwoNetworkNodes> edgeFilter) {
            NetworkPath path = getPath(from, to, edgeFilter);
        return path.getDistance();
    }

    public boolean isInDistance(int distance, NetworkNode from, NetworkNode to) {
        return isInDistance(distance, from, to, null);
    }

    public boolean isInDistance(int distance, NetworkNode from, NetworkNode to, Predicate<TwoNetworkNodes> edgeFilter) {
        return getDistance(from, to, edgeFilter) <= distance;
    }

    public NetworkPath getPath(NetworkNode start, NetworkNode end) {
        return getPath(start, end, null);
    }

    /*
     * Further optimizations: there is no heuristic part of this to avoid searching through all nodes
     */
    public NetworkPath getPath(NetworkNode start, NetworkNode end, Predicate<TwoNetworkNodes> edgeFilter) {
        if (start.equals(end)) {
            // we win already
            return new NetworkPath(0, Sets.<NetworkNode>newLinkedHashSet());
        }

        Queue<NetworkNode> currentNodes = Queues.newArrayDeque();
        Map<NetworkNode, NetworkNode> cameFrom = Maps.newHashMap();
        Map<NetworkNode, Integer> distances = Maps.newHashMap();
        Set<NetworkNode> visitedNodes = Sets.newHashSet();

        currentNodes.add(start);
        distances.put(start, 0);
        visitedNodes.add(start);
        while (currentNodes.size() > 0) {
            NetworkNode currentNode = currentNodes.poll();

            int currentConnectedDistance = distances.get(currentNode) + 1;
            for (NetworkNode connectedNode : allNetworkNodes.get(currentNode)) {
                // filter out any undesired edges
                if (edgeFilter != null) {
                    TwoNetworkNodes twoNetworkNodes = new TwoNetworkNodes(currentNode, connectedNode);
                    if (!edgeFilter.apply(twoNetworkNodes)) {
                        continue;
                    }
                }

                // update the distance and cameFrom
                if (!distances.containsKey(connectedNode) || distances.get(connectedNode) > currentConnectedDistance) {
                    distances.put(connectedNode, currentConnectedDistance);
                    cameFrom.put(connectedNode, currentNode);
                }
                if (!visitedNodes.contains(connectedNode)) {
                    visitedNodes.add(connectedNode);
                    currentNodes.add(connectedNode);
                }
            }
        }


        List<NetworkNode> path = Lists.newArrayList();
        NetworkNode currentNode = cameFrom.get(end);
        while (currentNode != start) {
            path.add(currentNode);
            currentNode = cameFrom.get(currentNode);
        }
        Collections.reverse(path);

        return new NetworkPath(distances.get(end), path);
    }

}
