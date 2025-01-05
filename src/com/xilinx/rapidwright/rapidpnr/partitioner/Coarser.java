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
import com.xilinx.rapidwright.rapidpnr.utils.VecOps;

abstract public class Coarser {

    public static enum Scheme {
        EC, HEC, FC
    }

    public static class Config {
        public Scheme scheme = Scheme.FC;
        public int seed = 999;
        public double levelShrinkRatio = 2.0;
        public double maxNodeSizeRatio = 1.0;
        public List<Integer> partResult = null;
        public Set<Integer> dontTouchNodes = null;
        public boolean disableHeteroNode = false;

        @Override
        public String toString() {
            return String.format("Coarser Config: Scheme=%s Seed=%d LevelShrinkRatio=%.2f MaxNodeSizeRatio=%.2f", scheme, seed, levelShrinkRatio, maxNodeSizeRatio);
        }

        public Config() {
            this.dontTouchNodes = new HashSet<>();
        }

        public Config(Config config) {
            this.scheme = config.scheme;
            this.seed = config.seed;
            this.levelShrinkRatio = config.levelShrinkRatio;
            this.maxNodeSizeRatio = config.maxNodeSizeRatio;
            this.partResult = config.partResult;
            this.dontTouchNodes = config.dontTouchNodes;
            this.disableHeteroNode = config.disableHeteroNode;
        }

        public Config(int seed) {
            this.seed = seed;
            this.dontTouchNodes = new HashSet<>();
        }

        public Config(Scheme scheme, int seed) {
            this.scheme = scheme;
            this.seed = seed;
            this.dontTouchNodes = new HashSet<>();
        }

        public Config(Scheme scheme, int seed, double stopRatio) {
            this.scheme = scheme;
            this.seed = seed;
            this.levelShrinkRatio = stopRatio;
            this.dontTouchNodes = new HashSet<>();
        }

        public Config(Scheme scheme, int seed, double stopRatio, double maxNodeSizeRatio) {
            this.scheme = scheme;
            this.seed = seed;
            this.levelShrinkRatio = stopRatio;
            this.maxNodeSizeRatio = maxNodeSizeRatio;
            this.dontTouchNodes = new HashSet<>();
        }

        public boolean isInternalNodes(int node1, int node2) {
            if (partResult == null) {
                return true;
            } else {
                return partResult.get(node1).equals(partResult.get(node2));
            }
        }
    };

    public static HierHyperGraph coarsening(Config config, HierHyperGraph hyperGraph) {
        switch (config.scheme) {
            case EC:
                return edgeCoarsening(hyperGraph, config);
            case HEC:
                return hyperEdgeCoarsening(hyperGraph, config);
            case FC:
                return firstChoiceCoarsening(hyperGraph, config);
            default:
                throw new IllegalArgumentException("Unknown coarsening scheme: " + config.scheme);
        }
    }


    public static HierHyperGraph edgeCoarsening(HierHyperGraph hyperGraph, Config config) {
        Random random = new Random(config.seed);
        double totalNodeSize = hyperGraph.getNodeWeightsSum(hyperGraph.getTotalNodeWeight());
        double maxClsSize = totalNodeSize * config.maxNodeSizeRatio;

        List<Integer> randomNodeIdxSeq = new ArrayList<>();
        Set<Integer> ignoredNodes = new HashSet<>(config.dontTouchNodes);

        List<List<Integer>> cluster2Nodes = new ArrayList<>();
        List<Integer> node2Cluster = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), -1));

        for (int i = 0; i < hyperGraph.getNodeNum(); i++) {
            if (config.dontTouchNodes.contains(i)) continue;

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

    public static HierHyperGraph hyperEdgeCoarsening(HierHyperGraph hyperGraph, Config config) {

        List<List<Integer>> cluster2Nodes = new ArrayList<>();
        List<Integer> node2Cluster = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), -1));

        List<Integer> sortedEdgeIds = new ArrayList<>();
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            sortedEdgeIds.add(edgeId);
        }
        Comparator<Integer> edgeIdxCmp = (idx1, idx2) -> {
            int edgeCmp = (int) (hyperGraph.getDegreeOfEdge(idx2) - hyperGraph.getDegreeOfEdge(idx1));
            // if (edgeCmp == 0) {
            //     edgeCmp = hyperGraph.getDegreeOfEdge(idx1) - hyperGraph.getDegreeOfEdge(idx2);
            // }
            return edgeCmp;
        };
        sortedEdgeIds.sort(edgeIdxCmp);

        List<Double> nodeSizeLimit = vecMulScalar(hyperGraph.getTotalNodeWeight(), config.maxNodeSizeRatio);

        List<Integer> unClusteredEdges = new ArrayList<>();
        for (int edgeId : sortedEdgeIds) {
            List<Integer> nodesOfEdge = hyperGraph.getNodesOfEdge(edgeId);
            boolean hasMatchedNodes = false;

            List<Double> clsSize = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeWeightDim(), 0.0));
            for (int nodeId : nodesOfEdge) {
                if (config.dontTouchNodes.contains(nodeId) || node2Cluster.get(nodeId) != -1) {
                    hasMatchedNodes = true;
                    break;
                }
                VecOps.accu(clsSize, hyperGraph.getWeightsOfNode(nodeId));
            }
            boolean isClsSizeLegal = VecOps.lessEq(clsSize, nodeSizeLimit);

            if (!hasMatchedNodes && isClsSizeLegal) {
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
                if (config.dontTouchNodes.contains(nodeId)) continue;
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

        for (int nodeId : config.dontTouchNodes) {
            assert node2Cluster.get(nodeId) == -1;
            int clusterId = cluster2Nodes.size();
            cluster2Nodes.add(new ArrayList<>(Arrays.asList(nodeId)));
            node2Cluster.set(nodeId, clusterId);
        }

        // for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
        //     if (node2Cluster.get(nodeId) != -1) continue;
        //     int clusterId = cluster2Nodes.size();
        //     cluster2Nodes.add(new ArrayList<>(Arrays.asList(nodeId)));
        //     node2Cluster.set(nodeId, clusterId);
        // }

        // check if all nodes are clustered
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            assert node2Cluster.get(nodeId) != -1;
        }

        return hyperGraph.createClusteredChildGraph(cluster2Nodes, false);
    }


    public static HierHyperGraph firstChoiceCoarsening(
        HierHyperGraph hyperGraph, Config config
    ) {
        // check input parameters
        if (config.partResult != null) {
            assert config.partResult.size() == hyperGraph.getNodeNum();
        }

        Random random = new Random(config.seed);
        List<Double> nodeSizeLimit = vecMulScalar(hyperGraph.getTotalNodeWeight(), config.maxNodeSizeRatio);
        
        List<Integer> randomNodeIdxSeq = new ArrayList<>();
        Set<Integer> skipNodes = new HashSet<>();

        List<List<Integer>> cluster2Nodes = new ArrayList<>();
        List<List<Double>> cluster2Size = new ArrayList<>();
        List<Integer> node2Cluster = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), -1));

        for (int i = 0; i < hyperGraph.getNodeNum(); i++) {
            if (config.dontTouchNodes.contains(i)) continue;
            if (allGreaterEq(hyperGraph.getWeightsOfNode(i), nodeSizeLimit)) {
                skipNodes.add(i);
                continue;
            }
            if (config.disableHeteroNode && isHeteroNode(hyperGraph, i)) {
                skipNodes.add(i);
                continue;
            }

            randomNodeIdxSeq.add(i);
        }

        Collections.shuffle(randomNodeIdxSeq, random);

        int matchedNodesNum = 0;
        for (int nodeId : randomNodeIdxSeq) {
            if (node2Cluster.get(nodeId) != -1) continue;

            List<Double> nodeSize = hyperGraph.getWeightsOfNode(nodeId);
            Map<Integer, Double> neighborNode2Weight = new HashMap<>();

            for (int edgeId : hyperGraph.getEdgesOfNode(nodeId)) {
                for (int nNodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                    if (nNodeId == nodeId) continue;
                    if (config.dontTouchNodes.contains(nNodeId)) continue;
                    if (skipNodes.contains(nNodeId)) continue; // skip very large nodes
                    if (!config.isInternalNodes(nodeId, nNodeId)) continue;

                    if (config.disableHeteroNode && !isHomoNode(hyperGraph, nodeId, nNodeId)) continue;


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
            if (((double) hyperGraph.getNodeNum() / curGraphNodeNum) > config.levelShrinkRatio) {
                break;
            }

            // find neighbor node with max weight
            int maxWeightNodeId = -1;
            double maxWeight = 0.0;
            for (int nNodeId : neighborNode2Weight.keySet()) {
                List<Double> size = hyperGraph.getWeightsOfNode(nNodeId);
                int clusterId = node2Cluster.get(nNodeId);
                if (clusterId != -1) {
                    size = cluster2Size.get(clusterId);
                }
                List<Double> accuSize = vecAdd(size, nodeSize);

                // skip if clustering result in oversized node
                if (!allLessEq(accuSize, nodeSizeLimit)) continue;

                if (neighborNode2Weight.get(nNodeId) > maxWeight) {
                    maxWeight = neighborNode2Weight.get(nNodeId);
                    maxWeightNodeId = nNodeId;
                }
            }
            
            if (maxWeightNodeId == -1) {
                continue;
            }

            if (node2Cluster.get(maxWeightNodeId) != -1) {
                int clusterId = node2Cluster.get(maxWeightNodeId);

                cluster2Nodes.get(clusterId).add(nodeId);
                vecAccu(cluster2Size.get(clusterId), nodeSize);
                node2Cluster.set(nodeId, clusterId);

                matchedNodesNum += 1;
            } else {
                // merge node
                int clusterId = cluster2Nodes.size();
                List<Integer> mergedNodes = new ArrayList<>(Arrays.asList(nodeId, maxWeightNodeId));
                List<Double> clusterSize = vecAdd(nodeSize, hyperGraph.getWeightsOfNode(maxWeightNodeId));

                node2Cluster.set(nodeId, clusterId);
                node2Cluster.set(maxWeightNodeId, clusterId);
                cluster2Nodes.add(mergedNodes);
                cluster2Size.add(clusterSize);
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

    public static boolean isHeteroNode(HierHyperGraph graph, int nodeId) {
        int nonZeroCnt = 0;
        for (double weight : graph.getWeightsOfNode(nodeId)) {
            if (weight > 0) {
                nonZeroCnt += 1;
            }
        }
        return nonZeroCnt > 1;
    }

    public static boolean isHomoNode(HierHyperGraph graph, int node1, int node2) {
        List<Double> node1Weights = graph.getWeightsOfNode(node1);
        List<Double> node2Weights = graph.getWeightsOfNode(node2);

        for (int i = 0; i < node1Weights.size(); i++) {
            if (node1Weights.get(i) == 0 && node2Weights.get(i) > 0) {
                return false;
            }

            if (node1Weights.get(i) > 0 && node2Weights.get(i) == 0) {
                return false;
            }
        }

        return true;
    }

    public static boolean allLessEq(List<Double> vec1, List<Double> vec2) {
        assert vec1.size() == vec2.size();
        for (int i = 0; i < vec1.size(); i++) {
            if (vec1.get(i) > vec2.get(i)) {
                return false;
            }
        }
        return true;
    }

    public static boolean allGreaterEq(List<Double> vec1, List<Double> vec2) {
        assert vec1.size() == vec2.size();
        for (int i = 0; i < vec1.size(); i++) {
            if (vec1.get(i) < vec2.get(i)) {
                return false;
            }
        }
        return true;
    }

    public static List<Double> vecMulScalar(List<Double> vec, double scalar) {
        List<Double> result = new ArrayList<>();
        for (double val : vec) {
            result.add(val * scalar);
        }
        return result;
    }

    public static List<Double> vecAdd(List<Double> vec1, List<Double> vec2) {
        assert vec1.size() == vec2.size();
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < vec1.size(); i++) {
            result.add(vec1.get(i) + vec2.get(i));
        }
        return result;
    }

    public static void vecAccu(List<Double> vec1, List<Double> vec2) {
        assert vec1.size() == vec2.size();
        for (int i = 0; i < vec1.size(); i++) {
            vec1.set(i, vec1.get(i) + vec2.get(i));
        }
    }

}
