package com.xilinx.rapidwright.rapidpnr.partitioner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.xilinx.rapidwright.rapidpnr.utils.HierHyperGraph;

abstract public class Coarser {

    public static enum Scheme {
        EC, HEC, FC
    }

    public static class Config {
        public Scheme scheme;
        public int seed;
        public double stopRatio = 2.0;
        public double maxNodeRatio = 1.0;

        public Config(Scheme scheme, int seed) {
            this.scheme = scheme;
            this.seed = seed;
        }

        public Config(Scheme scheme, int seed, double stopRatio) {
            this.scheme = scheme;
            this.seed = seed;
            this.stopRatio = stopRatio;
        }

        public Config(Scheme scheme, int seed, double stopRatio, double maxNodeRatio) {
            this.scheme = scheme;
            this.seed = seed;
            this.stopRatio = stopRatio;
            this.maxNodeRatio = maxNodeRatio;
        }
    };

    public static HierHyperGraph coarsening(Config config, HierHyperGraph hyperGraph, Set<Integer> dontTouchNodes) {
        switch (config.scheme) {
            case EC:
                return edgeCoarsening(hyperGraph, dontTouchNodes, config);
            case HEC:
                return hyperEdgeCoarsening(hyperGraph, dontTouchNodes);
            case FC:
                return firstChoiceCoarsening(hyperGraph, dontTouchNodes, config);
            default:
                throw new IllegalArgumentException("Unknown coarsening scheme: " + config.scheme);
        }
    }


    public static HierHyperGraph edgeCoarsening(HierHyperGraph hyperGraph, Set<Integer> dontTouchNodes, Config config) {
        Random random = new Random(config.seed);
        double totalNodeSize = hyperGraph.getNodeWeightsSum(hyperGraph.getTotalNodeWeight());
        double maxClsSize = totalNodeSize * config.maxNodeRatio;

        List<Integer> randomNodeIdxSeq = new ArrayList<>();
        Set<Integer> ignoredNodes = new HashSet<>(dontTouchNodes);

        List<List<Integer>> cluster2Nodes = new ArrayList<>();
        List<Integer> node2Cluster = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), -1));

        for (int i = 0; i < hyperGraph.getNodeNum(); i++) {
            if (dontTouchNodes.contains(i)) continue;

            if (hyperGraph.getNodeWeightsSum(i) > maxClsSize) {
                ignoredNodes.add(i);
                continue;
            }

            randomNodeIdxSeq.add(i);
        }

        Collections.shuffle(randomNodeIdxSeq, random);

        for (int nodeId : randomNodeIdxSeq) {
            if (node2Cluster.get(nodeId) != -1) continue;

            Map<Integer, Double> neighborNode2Weight = new HashMap<>();

            for (int edgeId : hyperGraph.getEdgesOfNode(nodeId)) {
                for (int nNodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                    if (nNodeId == nodeId || node2Cluster.get(nNodeId) != -1) {
                        continue;
                    }
                    if (ignoredNodes.contains(nNodeId)) {
                        continue;
                    }

                    double weight = hyperGraph.getEdgeWeightsSum(edgeId) / (hyperGraph.getDegreeOfEdge(edgeId) - 1);

                    if (neighborNode2Weight.containsKey(nNodeId)) {
                        double originWeight = neighborNode2Weight.get(nNodeId);
                        neighborNode2Weight.replace(nNodeId, originWeight + weight);
                    } else {
                        neighborNode2Weight.put(nNodeId, weight);
                    }
                }
            }

            // find neighbor node with max weight
            int maxWeightNodeId = -1;
            double maxWeight = 0.0;
            for (int nNodeId : neighborNode2Weight.keySet()) {
                if (neighborNode2Weight.get(nNodeId) > maxWeight) {
                    maxWeight = neighborNode2Weight.get(nNodeId);
                    maxWeightNodeId = nNodeId;
                }
            }

            // merge node
            int clusterId = cluster2Nodes.size();
            List<Integer> mergedNodes = new ArrayList<>();

            mergedNodes.add(nodeId);
            node2Cluster.set(nodeId, clusterId);
            
            if (maxWeightNodeId != -1) {
                mergedNodes.add(maxWeightNodeId);
                node2Cluster.set(maxWeightNodeId, clusterId);
            }

            cluster2Nodes.add(mergedNodes);
        }

        for (int nodeId : ignoredNodes) {
            assert node2Cluster.get(nodeId) == -1;
            int clusterId = cluster2Nodes.size();
            List<Integer> mergedNodes = new ArrayList<>(Arrays.asList(nodeId));
            cluster2Nodes.add(mergedNodes);
            node2Cluster.set(nodeId, clusterId);
        }

        // check if all nodes are clustered
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            assert node2Cluster.get(nodeId) != -1;
        }

        return hyperGraph.createClusteredChildGraph(cluster2Nodes, false);
    }

    public static HierHyperGraph hyperEdgeCoarsening(HierHyperGraph hyperGraph, Set<Integer> dontTouchNodes) {

        List<List<Integer>> cluster2Nodes = new ArrayList<>();
        List<Integer> node2Cluster = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), -1));

        List<Integer> sortedEdgeIds = new ArrayList<>();
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            sortedEdgeIds.add(edgeId);
        }
        Comparator<Integer> edgeIdxCmp = (idx1, idx2) -> {
            int edgeCmp = (int) (hyperGraph.getEdgeWeightsSum(idx2) - hyperGraph.getEdgeWeightsSum(idx1));
            if (edgeCmp == 0) {
                edgeCmp = hyperGraph.getDegreeOfEdge(idx1) - hyperGraph.getDegreeOfEdge(idx2);
            }
            return edgeCmp;
        };
        sortedEdgeIds.sort(edgeIdxCmp);

        // for (int edgeId : sortedEdgeIds) {
        //     System.out.println("Edge-" + edgeId + " weight: " + hyperGraph.getEdgeWeightsSum(edgeId) + " degree: " + hyperGraph.getDegreeOfEdge(edgeId));
        // }

        List<Integer> unClusteredEdges = new ArrayList<>();
        for (int edgeId : sortedEdgeIds) {
            List<Integer> nodesOfEdge = hyperGraph.getNodesOfEdge(edgeId);
            boolean hasMatchedNodes = false;
            for (int nodeId : nodesOfEdge) {
                if (dontTouchNodes.contains(nodeId) || node2Cluster.get(nodeId) != -1) {
                    hasMatchedNodes = true;
                    break;
                }
            }

            if (!hasMatchedNodes) {
                int clusterId = cluster2Nodes.size();
                cluster2Nodes.add(nodesOfEdge);
                for (int nodeId : nodesOfEdge) {
                    node2Cluster.set(nodeId, clusterId);
                }
            } else {
                unClusteredEdges.add(edgeId);
            }
        }

        for (int edgeId : unClusteredEdges) {
            List<Integer> nodesOfEdge = hyperGraph.getNodesOfEdge(edgeId);
            List<Integer> mergedNodes = new ArrayList<>();
            for (int nodeId : nodesOfEdge) {
                if (dontTouchNodes.contains(nodeId)) continue;
                if (node2Cluster.get(nodeId) != -1) continue;
                mergedNodes.add(nodeId);
            }

            if (mergedNodes.size() == 0) {
                continue;
            }

            int clusterId = cluster2Nodes.size();
            cluster2Nodes.add(mergedNodes);
            for (int nodeId : mergedNodes) {
                node2Cluster.set(nodeId, clusterId);
            }
        }

        for (int nodeId : dontTouchNodes) {
            assert node2Cluster.get(nodeId) == -1;
            int clusterId = cluster2Nodes.size();
            cluster2Nodes.add(new ArrayList<>(Arrays.asList(nodeId)));
            node2Cluster.set(nodeId, clusterId);
        }

        // check if all nodes are clustered
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            assert node2Cluster.get(nodeId) != -1;
        }

        return hyperGraph.createClusteredChildGraph(cluster2Nodes, false);
    }


    public static HierHyperGraph firstChoiceCoarsening(
        HierHyperGraph hyperGraph, Set<Integer> dontTouchNodes, Config config
    ) {
        Random random = new Random(config.seed);
        double totalNodeSize = hyperGraph.getNodeWeightsSum(hyperGraph.getTotalNodeWeight());
        double maxClsSize = totalNodeSize * config.maxNodeRatio;
        
        List<Integer> randomNodeIdxSeq = new ArrayList<>();
        Set<Integer> overSizeNodes = new HashSet<>();

        List<List<Integer>> cluster2Nodes = new ArrayList<>();
        List<Double> cluster2Size = new ArrayList<>();
        List<Integer> node2Cluster = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), -1));


        for (int i = 0; i < hyperGraph.getNodeNum(); i++) {
            if (dontTouchNodes.contains(i)) continue;
            if (hyperGraph.getNodeWeightsSum(i) > maxClsSize) {
                overSizeNodes.add(i);
                continue;
            }
            randomNodeIdxSeq.add(i);
        }

        Collections.shuffle(randomNodeIdxSeq, random);

        int matchedNodesNum = 0;
        for (int nodeId : randomNodeIdxSeq) {
            if (node2Cluster.get(nodeId) != -1) continue;

            Map<Integer, Double> neighborNode2Weight = new HashMap<>();

            for (int edgeId : hyperGraph.getEdgesOfNode(nodeId)) {
                for (int nNodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                    if (nNodeId == nodeId) continue;
                    if (dontTouchNodes.contains(nNodeId)) continue;
                    if (overSizeNodes.contains(nNodeId)) continue; // skip very large nodes

                    double weight = hyperGraph.getEdgeWeightsSum(edgeId) / (hyperGraph.getDegreeOfEdge(edgeId) - 1);

                    if (neighborNode2Weight.containsKey(nNodeId)) {
                        double originWeight = neighborNode2Weight.get(nNodeId);
                        neighborNode2Weight.replace(nNodeId, originWeight + weight);
                    } else {
                        neighborNode2Weight.put(nNodeId, weight);
                    }
                }
            }

            int curGraphNodeNum = hyperGraph.getNodeNum() - matchedNodesNum + cluster2Nodes.size();
            if (((double) hyperGraph.getNodeNum() / curGraphNodeNum) > config.stopRatio) {
                break;
            }

            // find neighbor node with max weight
            int maxWeightNodeId = -1;
            double maxWeight = 0.0;
            for (int nNodeId : neighborNode2Weight.keySet()) {
                // skip if the neighbor node is already in a very large cluster
                int clusterId = node2Cluster.get(nNodeId);
                if (clusterId != -1 && cluster2Size.get(clusterId) > maxClsSize) continue;

                if (neighborNode2Weight.get(nNodeId) > maxWeight) {
                    maxWeight = neighborNode2Weight.get(nNodeId);
                    maxWeightNodeId = nNodeId;
                }
            }
            //assert maxWeightNodeId != -1;
            if (maxWeightNodeId == -1) {
                continue;
            }

            if (node2Cluster.get(maxWeightNodeId) != -1) {
                int clusterId = node2Cluster.get(maxWeightNodeId);
                double nodeSize = hyperGraph.getNodeWeightsSum(nodeId);

                cluster2Nodes.get(clusterId).add(nodeId);
                cluster2Size.set(clusterId, cluster2Size.get(clusterId) + nodeSize);
                node2Cluster.set(nodeId, clusterId);

                matchedNodesNum += 1;
            } else {
                // merge node
                int clusterId = cluster2Nodes.size();
                List<Integer> mergedNodes = new ArrayList<>(Arrays.asList(nodeId, maxWeightNodeId));
                double nodeWeight = hyperGraph.getNodeWeightsSum(nodeId);
                nodeWeight += hyperGraph.getNodeWeightsSum(maxWeightNodeId);

                node2Cluster.set(nodeId, clusterId);
                node2Cluster.set(maxWeightNodeId, clusterId);
                cluster2Nodes.add(mergedNodes);
                cluster2Size.add(nodeWeight);
                matchedNodesNum += 2;
            }
        }

        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            if (node2Cluster.get(nodeId) != -1) continue;
            int clusterId = cluster2Nodes.size();
            cluster2Nodes.add(new ArrayList<>(Arrays.asList(nodeId)));
            node2Cluster.set(nodeId, clusterId);
        }

        return hyperGraph.createClusteredChildGraph(cluster2Nodes, false);
    }

}
