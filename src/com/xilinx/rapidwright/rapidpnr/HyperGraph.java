package com.xilinx.rapidwright.rapidpnr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;

public class HyperGraph {
    protected int nodeNum;
    protected int edgeNum;

    protected int nodeWeightDim;
    protected int edgeWeightDim;

    protected List<Double> nodeWeightFactor;
    protected List<Double> edgeWeightFactor;

    protected List<List<Integer>> edge2Nodes;
    protected List<List<Double>> edge2Weights;

    protected List<List<Integer>> node2Edges;
    protected List<List<Double>> node2Weights;

    public HyperGraph(List<Double> nodeWeightFactor, List<Double> edgeWeightFactor) {
        this.nodeNum = 0;
        this.edgeNum = 0;

        this.edgeWeightDim = edgeWeightFactor.size();
        this.nodeWeightDim = nodeWeightFactor.size();

        this.nodeWeightFactor = new ArrayList<>(nodeWeightFactor);
        this.edgeWeightFactor = new ArrayList<>(edgeWeightFactor); 

        edge2Nodes = new ArrayList<>();
        edge2Weights = new ArrayList<>();

        node2Edges = new ArrayList<>();
        node2Weights = new ArrayList<>();
    }

    public void setEdgeWeights(int edgeId, List<Double> weights) {
        assert weights.size() == edgeWeightDim;
        assert edgeId < edgeNum;
        edge2Weights.set(edgeId, new ArrayList<>(weights));
    }

    public void setNodeWeights(int nodeId, List<Double> weights) {
        assert weights.size() == nodeWeightDim;
        assert nodeId < nodeNum;
        node2Weights.set(nodeId, new ArrayList<>(weights));
    }

    public int addNode(List<Double> weights) {
        assert weights.size() == nodeWeightDim;

        node2Weights.add(new ArrayList<>(weights));
        node2Edges.add(new ArrayList<>());
        
        int nodeId = nodeNum;
        nodeNum++;
        return nodeId;
    }

    public int addEdge(Set<Integer> nodeIds, List<Double> weights) {
        assert weights.size() == edgeWeightDim;

        edge2Nodes.add(new ArrayList<>(nodeIds));
        edge2Weights.add(new ArrayList<>(weights));
        int edgeId = edgeNum;

        for (int nodeId : nodeIds) {
            assert nodeId < nodeNum;
            node2Edges.get(nodeId).add(edgeId);
        }
        edgeNum++;
        return edgeId;
    }

    public void setNodeWeightsFactor(List<Double> weightFactor) {
        assert weightFactor.size() == nodeWeightDim;
        nodeWeightFactor = new ArrayList<>(weightFactor);
    }

    public void setEdgeWeightsFactor(List<Double> weightFactor) {
        assert weightFactor.size() == edgeWeightDim;
        edgeWeightFactor = new ArrayList<>(weightFactor);
    }

    public List<Double> getNodeWeightsFactor() {
        return Collections.unmodifiableList(nodeWeightFactor);
    }

    public List<Double> getEdgeWeightsFactor() {
        return Collections.unmodifiableList(edgeWeightFactor);
    }

    public Double getEdgeWeightsSum(List<Double> edgeWeights) {
        return getWeightsSum(edgeWeights, edgeWeightFactor);
    }

    public Double getNodeWeightsSum(List<Double> nodeWeights) {
        return getWeightsSum(nodeWeights, nodeWeightFactor);
    }

    public Double getNodeWeightsSum(int nodeId) {
        return getNodeWeightsSum(getWeightsOfNode(nodeId));
    }

    public Double getEdgeWeightsSum(int edgeId) {
        return getEdgeWeightsSum(getWeightsOfEdge(edgeId));
    }

    public List<Double> getCutSize(List<Integer> partResult) {
        assert partResult.size() == nodeNum;
        List<Double> cutSizes = new ArrayList<Double>(Collections.nCopies(edgeWeightDim, 0.0));

        for (int edgeId = 0; edgeId < edgeNum; edgeId++) {
            if (isCutEdge(edgeId, partResult)) {
                accuWeights(cutSizes, edge2Weights.get(edgeId));
            }
        }

        return cutSizes;
    }

    public List<Double> getCutSizeOfNode(List<Integer> partResult, Integer nodeId) {
        
        List<Double> cutSize = new ArrayList<>(Collections.nCopies(edgeWeightDim, 0.0));

        for (int edgeId : node2Edges.get(nodeId)) {

            if (isCutEdge(edgeId, partResult)) {
                accuWeights(cutSize, edge2Weights.get(edgeId));
            }
        }

        return cutSize;
    }

    public List<List<Double>> getBlockSize(List<Integer> partResult) {
        assert partResult.size() == nodeNum;

        Integer maxBlockId = Collections.max(partResult);
        List<List<Double>> blockSizes = new ArrayList<>();

        for (int i = 0; i <= maxBlockId; i++) {
            blockSizes.add(new ArrayList<Double>(Collections.nCopies(nodeWeightDim, 0.0)));
        }

        for (int nodeId = 0; nodeId < nodeNum; nodeId++) {
            int blockId = partResult.get(nodeId);
            assert blockId != -1;
            //if (blockId == -1) continue;
            accuWeights(blockSizes.get(blockId), node2Weights.get(nodeId));
        }

        return blockSizes;
    }

    public boolean isCutEdge(int edgeId, List<Integer> partResult) {
        assert partResult.size() == nodeNum;

        Set<Integer> edge2Blocks = new HashSet<>();

        for (int nodeId : edge2Nodes.get(edgeId)) {
            int blockId = partResult.get(nodeId);
            if (blockId == -1) continue;

            edge2Blocks.add(blockId);
        }

        return edge2Blocks.size() > 1;
    }

    public void saveGraphInHmetisFormat(Path outputFilePath) {
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath.toFile()))) {
            writer.write(String.format("%d %d 11", edgeNum, nodeNum));
            writer.newLine();

            // save edges
            for (int edgeId = 0; edgeId < edgeNum; edgeId++) {
                String edgeLineString = "";

                for (double weight : edge2Weights.get(edgeId)) {
                    if (edgeLineString.length() != 0) {
                        edgeLineString += " ";
                    }
                    edgeLineString += String.format("%.2f", weight);
                }

                for (int nodeId : edge2Nodes.get(edgeId)) {
                    nodeId += 1;
                    edgeLineString += String.format(" %d", nodeId);
                }

                writer.write(edgeLineString);
                writer.newLine();
            }

            // save nodes
            for (int nodeId = 0; nodeId < nodeNum; nodeId++) {
                String nodeLineString = "";

                for (double weight : node2Weights.get(nodeId)) {
                    if (nodeLineString.length() != 0) {
                        nodeLineString += " ";
                    }
                    nodeLineString += String.format("%.2f", weight);
                }
                writer.write(nodeLineString);
                writer.newLine();
            }

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    public HierHyperGraph createClusteredChildGraph(List<List<Integer>> cluster2Nodes) {
        return new HierHyperGraph(this, cluster2Nodes);
        // HyperGraph clsHyperGraph = new HyperGraph(nodeWeightFactor, edgeWeightFactor);
        // List<Integer> node2Cluster = new ArrayList<>(Collections.nCopies(nodeNum, -1));

        // for (int clusterId = 0; clusterId < cluster2Nodes.size(); clusterId++) {
        //     for (int nodeId : cluster2Nodes.get(clusterId)) {
        //         assert node2Cluster.get(nodeId) == -1: String.format("Node %d is already included in a cluster", nodeId);
        //         node2Cluster.set(nodeId, clusterId);
        //     }
        // }

        // // check all nodes included in the cluster
        // for (int nodeId = 0; nodeId < nodeNum; nodeId++) {
        //     assert node2Cluster.get(nodeId) != -1;
        // }

        // // add nodes to clsHyperGraph
        // for (List<Integer> nodes : cluster2Nodes) {
        //     List<Double> weights = new ArrayList<>(Collections.nCopies(nodeWeightDim, 0.0));
        //     for (int nodeId : nodes) {
        //         accuWeights(weights, node2Weights.get(nodeId));
        //     }
        //     clsHyperGraph.addNode(weights);
        // }

        // // add edges to clsHyperGraph
        // for (int edgeId = 0; edgeId < edgeNum; edgeId++) {
        //     Set<Integer> clusterIds = new HashSet<>();
        //     for (int nodeId : edge2Nodes.get(edgeId)) {
        //         clusterIds.add(node2Cluster.get(nodeId));
        //     }

        //     if (clusterIds.size() > 1) {
        //         List<Double> weights = new ArrayList<>(edge2Weights.get(edgeId));
        //         clsHyperGraph.addEdge(clusterIds, weights);
        //     }
        // }

        // return clsHyperGraph;
    }

    public int getEdgeNum() {
        return edgeNum;
    }

    public int getNodeNum() {
        return nodeNum;
    }

    public int getEdgeWeightDim() {
        return edgeWeightDim;
    }

    public int getNodeWeightDim() {
        return nodeWeightDim;
    }

    public List<Integer> getNodesOfEdge(int edgeId) {
        return Collections.unmodifiableList(edge2Nodes.get(edgeId));
    }

    public List<Integer> getEdgesOfNode(int nodeId) {
        return Collections.unmodifiableList(node2Edges.get(nodeId));
    }

    public List<Double> getWeightsOfNode(int nodeId) {
        return Collections.unmodifiableList(node2Weights.get(nodeId));
    }

    public List<Double> getWeightsOfEdge(int edgeId) {
        return Collections.unmodifiableList(edge2Weights.get(edgeId));
    }

    public List<Double> getTotalNodeWeightsOfEdge(int edgeId) {
        List<Double> weights = new ArrayList<>(Collections.nCopies(nodeWeightDim, 0.0));
        for (int nodeId : edge2Nodes.get(edgeId)) {
            accuWeights(weights, node2Weights.get(nodeId));
        }
        return weights;
    }

    public List<Integer> getNeighborsOfNode(int nodeId) {
        Set<Integer> neighbors = new HashSet<>();
        for (int edgeId : node2Edges.get(nodeId)) {
            for (int neighborNodeId : edge2Nodes.get(edgeId)) {
                if (neighborNodeId != nodeId) {
                    neighbors.add(neighborNodeId);
                }
            }
        }
        return new ArrayList<>(neighbors);
    }

    public List<Double> getTotalEdgeWeight() {
        List<Double> totalEdgeWeights = new ArrayList<>(Collections.nCopies(edgeWeightDim, 0.0));
        for (int edgeId = 0; edgeId < edgeNum; edgeId++) {
            accuWeights(totalEdgeWeights, edge2Weights.get(edgeId));
        }
        return totalEdgeWeights;
    }

    public List<Double> getTotalNodeWeight() {
        List<Double> totalNodeWeights = new ArrayList<>(Collections.nCopies(nodeWeightDim, 0.0));
        for (int nodeId = 0; nodeId < nodeNum; nodeId++) {
            accuWeights(totalNodeWeights, node2Weights.get(nodeId));
        }
        return totalNodeWeights;
    }

    public List<List<Integer>> getDistance2Nodes(Integer nodeId, Integer maxDistance) {
        assert nodeId < getNodeNum();

        List<List<Integer>> dist2Nodes = new ArrayList<>();
        List<Integer> node2Dist = new ArrayList<>(Collections.nCopies(getNodeNum(), -1));

        Queue<Integer> expandQueue = new LinkedList<>();
        dist2Nodes.add(Arrays.asList(nodeId));
        node2Dist.set(nodeId, 0);

        expandQueue.add(nodeId);

        while(!expandQueue.isEmpty()) {
            int curNodeId = expandQueue.poll();

            for (int nNodeId : getNeighborsOfNode(curNodeId)) {
                if (node2Dist.get(nNodeId) == -1) {
                    int dist = node2Dist.get(curNodeId) + 1;

                    if (dist <= maxDistance) {
                        if (dist2Nodes.size() <= dist) {
                            dist2Nodes.add(new ArrayList<>());
                        }
                        dist2Nodes.get(dist).add(nNodeId);
                        node2Dist.set(nNodeId, dist);
                        expandQueue.add(nNodeId);
                    }
                }
            }

        }
        
        return dist2Nodes;
    }

    public static void accuWeights(List<Double> target, List<Double> source) {
        assert target.size() == source.size();
        for (int i = 0; i < target.size(); i++) {

            target.set(i, target.get(i) + source.get(i));
        }
    }

    public static void decWeights(List<Double> target, List<Double> source) {
        assert target.size() == source.size();
        for (int i = 0; i < target.size(); i++) {
            target.set(i, target.get(i) - source.get(i));
        }
    }

    public static List<Double> getWeightsDiff(List<Double> weights1, List<Double> weights2) {
        assert weights1.size() == weights2.size();
        List<Double> diffWeights = new ArrayList<>(Collections.nCopies(weights1.size(), 0.0));
        for (int i = 0; i < weights1.size(); i++) {
            diffWeights.set(i, weights1.get(i) - weights2.get(i));
        }
        return diffWeights;
    }

    public static Double getWeightsSum(List<Double> weights, List<Double> factors) {
        assert weights.size() == factors.size();
        Double sum = 0.0;
        for (int i = 0; i < weights.size(); i++) {
            sum += weights.get(i) * factors.get(i);
        }
        return sum;
    }
}
