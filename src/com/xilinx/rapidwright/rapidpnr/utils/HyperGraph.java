package com.xilinx.rapidwright.rapidpnr.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
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

    public HyperGraph(int nodeWeightDim, int edgeWeightDim) {
        setupGraph(Collections.nCopies(nodeWeightDim, 1.0), Collections.nCopies(edgeWeightDim, 1.0));
    }

    public HyperGraph(List<Double> nodeWeightFactor, List<Double> edgeWeightFactor) {
        setupGraph(nodeWeightFactor, edgeWeightFactor);
    }

    private void setupGraph(List<Double> nodeWeightFactor, List<Double> edgeWeightFactor) {
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
        assert nodeIds.size() > 1;

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
        assert weightFactor.size() > 0;
        nodeWeightFactor = new ArrayList<>(weightFactor);
        nodeWeightDim = weightFactor.size();

        for (int nodeId = 0; nodeId < getNodeNum(); nodeId++) {
            List<Double> weights = node2Weights.get(nodeId);
            int originWeightDim = weights.size();
            if (originWeightDim < nodeWeightDim) {
                weights.addAll(Collections.nCopies(nodeWeightDim - originWeightDim, 1.0));
            } else if (originWeightDim > nodeWeightDim) {
                weights = weights.subList(0, nodeWeightDim);
            }
            
        }
    }

    public void setEdgeWeightsFactor(List<Double> weightFactor) {
        assert weightFactor.size() > 0;
        edgeWeightFactor = new ArrayList<>(weightFactor);
        edgeWeightDim = weightFactor.size();

        for (int edgeId = 0; edgeId < getEdgeNum(); edgeId++) {
            List<Double> weights = edge2Weights.get(edgeId);
            int originWeightDim = weights.size();
            if (originWeightDim < edgeWeightDim) {
                weights.addAll(Collections.nCopies(edgeWeightDim - originWeightDim, 1.0));
            } else if (originWeightDim > edgeWeightDim) {
                weights = weights.subList(0, edgeWeightDim);
            }
        }
    }

    // getters
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

    public List<List<Double>> getBlockCutSize(List<Integer> partResult) {
        assert partResult.size() == nodeNum;

        Integer maxBlockId = Collections.max(partResult);
        List<List<Double>> blockCutSizes = new ArrayList<>();
        for (int i = 0; i <= maxBlockId; i++) {
            blockCutSizes.add(new ArrayList<Double>(Collections.nCopies(edgeWeightDim, 0.0)));
        }

        for (int edgeId = 0; edgeId < edgeNum; edgeId++) {
            Set<Integer> blockIds = new HashSet<>();
            for (int nodeId : edge2Nodes.get(edgeId)) {
                int blockId = partResult.get(nodeId);
                if (blockId != -1) {
                    blockIds.add(blockId);
                }
            }

            if (blockIds.size() > 1) {
                for (int blockId : blockIds) {
                    accuWeights(blockCutSizes.get(blockId), edge2Weights.get(edgeId));
                }
            }
        }

        return blockCutSizes;
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

    public int getDegreeOfEdge(int edgeId) {
        return edge2Nodes.get(edgeId).size();
    }

    public int getMaxEdgeDegree() {
        List<Integer> edgeDegrees = edge2Nodes.stream().map(nodes -> nodes.size()).collect(Collectors.toList());
        return Collections.max(edgeDegrees);
    }

    public int getMaxNodeWeight(int dim) {
        assert dim < nodeWeightDim;
        List<Double> nodeWeights = node2Weights.stream().map(weights -> weights.get(dim)).collect(Collectors.toList());
        return Collections.max(nodeWeights).intValue();
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

    public boolean hasConnection(int node1, int node2) {
        for (int edgeId : node2Edges.get(node1)) {
            if (edge2Nodes.get(edgeId).contains(node2)) {
                return true;
            }
        }
        return false;
    }

    public List<Double> getMaxNodeWeight() {
        List<Double> maxNodeWeights = new ArrayList<>(Collections.nCopies(nodeWeightDim, 0.0));
        for (int nodeId = 0; nodeId < nodeNum; nodeId++) {
            List<Double> nodeWeights = node2Weights.get(nodeId);
            maxNodeWeights = VecOps.getMax(maxNodeWeights, nodeWeights);
        }
        return maxNodeWeights;
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

    public List<List<Integer>> getDistance2Nodes(List<Integer> nodeIds, Integer maxDistance) {
        List<List<Integer>> dist2Nodes = new ArrayList<>();
        List<Integer> node2Dist = new ArrayList<>(Collections.nCopies(getNodeNum(), -1));
        Queue<Integer> expandQueue = new LinkedList<>();

        dist2Nodes.add(nodeIds);
        for (int nodeId : nodeIds) {
            assert nodeId >= 0 && nodeId < getNodeNum();
            node2Dist.set(nodeId, 0);
            expandQueue.add(nodeId);
        }

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

    public HyperGraph getCompressedGraph() {
        Map<Set<Integer>, List<Double>> compressedEdges = new HashMap<>();

        for (int edgeId = 0; edgeId < getEdgeNum(); edgeId++) {
            Set<Integer> nodeIds = new HashSet<>(getNodesOfEdge(edgeId));
            List<Double> edgeWeights = getWeightsOfEdge(edgeId);

            if (compressedEdges.containsKey(nodeIds)) {
                List<Double> weights = compressedEdges.get(nodeIds);
                accuWeights(weights, edgeWeights);
            } else {
                compressedEdges.put(nodeIds, new ArrayList<>(edgeWeights));
            }
        }

        HyperGraph compressedGraph = new HyperGraph(nodeWeightFactor, edgeWeightFactor);
        for (int nodeId = 0; nodeId < getNodeNum(); nodeId++) {
            compressedGraph.addNode(getWeightsOfNode(nodeId));
        }

        for (Set<Integer> nodeIds : compressedEdges.keySet()) {
            compressedGraph.addEdge(nodeIds, compressedEdges.get(nodeIds));
        }

        return compressedGraph;
    }

    public String getHyperGraphInfo(boolean verbose) {
        String graphInfo = "";
        graphInfo += String.format("Total number of nodes: %d\n", nodeNum);
        graphInfo += String.format("Total number of edges: %d\n", edgeNum);
        graphInfo += String.format("Total node weights: %s\n", getTotalNodeWeight());
        List<Double> totalEdgeWeights = getTotalEdgeWeight();
        graphInfo += String.format("Total edge weights: %.2f (%s)\n", getEdgeWeightsSum(totalEdgeWeights), totalEdgeWeights);

        String distInfo;
        // Node weight distribution
        for (Integer dim = 0; dim < nodeWeightDim; dim++) {
            List<Double> nodeWeights = new ArrayList<>();
            for (int nodeId = 0; nodeId < getNodeNum(); nodeId++) {
                nodeWeights.add(getWeightsOfNode(nodeId).get(dim));
            }

            if (verbose) {
                distInfo = StatisticsUtils.getValueDistInfo(nodeWeights, 6);
                distInfo = HierarchicalLogger.insertAtHeadOfEachLine("  ", distInfo);
                graphInfo += String.format("Node weight distribution in dim-%d:\n%s\n", dim, distInfo);
            } else {
                distInfo = StatisticsUtils.getBasicValueDistInfo(nodeWeights);
                graphInfo += String.format("Node weight in dim-%d: %s\n", dim, distInfo);
            }
        }

        List<Double> edgeWeights = edge2Weights.stream().map(weights -> getEdgeWeightsSum(weights)).collect(Collectors.toList());
        if (verbose) {
            distInfo = StatisticsUtils.getValueDistInfo(edgeWeights, 6);
            distInfo = HierarchicalLogger.insertAtHeadOfEachLine("  ", distInfo);
            graphInfo += String.format("Edge weight distribution:\n%s\n", distInfo);
        } else {
            distInfo = StatisticsUtils.getBasicValueDistInfo(edgeWeights);
            graphInfo += String.format("Edge weight: %s\n", distInfo);
        }

        List<Double> edgeDegrees = edge2Nodes.stream().map(nodes -> (double) nodes.size()).collect(Collectors.toList());
        
        if (verbose) {
            distInfo = StatisticsUtils.getValueDistInfo(edgeDegrees, 6);
            distInfo = HierarchicalLogger.insertAtHeadOfEachLine("  ", distInfo);
            graphInfo += String.format("Edge degree distribution:\n%s", distInfo);
        } else {
            distInfo = StatisticsUtils.getBasicValueDistInfo(edgeDegrees);
            graphInfo += String.format("Edge degree: %s", distInfo);
        }

        return graphInfo;
    }

    // IO
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

    public static HyperGraph readGraphFromHmetisFormat(Path inputFilePath, List<Double> nodeWeightFactor, List<Double> edgeWeightFactor) {
        HyperGraph hyperGraph = new HyperGraph(nodeWeightFactor, edgeWeightFactor);
        int edgeWeightDim = hyperGraph.getEdgeWeightDim();
        int nodeWeightDim = hyperGraph.getNodeWeightDim();

        List<List<Double>> edgeWeights = new ArrayList<>();
        List<Set<Integer>> edge2NodeIds = new ArrayList<>();
        List<List<Double>> nodeWeights = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(inputFilePath.toFile()))) {
            String line;

            // read header
            line = reader.readLine();
            String[] tokens = line.split(" ");
            int edgeNum = Integer.parseInt(tokens[0]);
            int nodeNum = Integer.parseInt(tokens[1]);
            boolean hasEdgeWeights = false;
            boolean hasNodeWeights = false;
            if (tokens.length > 2) {
                assert tokens[2].length() == 2;
                hasEdgeWeights = tokens[2].charAt(0) == '1';
                hasNodeWeights = tokens[2].charAt(1) == '1';
            }

            for (int i = 0; i < edgeNum; i++) {
                line = reader.readLine();
                assert line != null;
                tokens = line.split(" ");

                List<Double> weights = new ArrayList<>(Collections.nCopies(edgeWeightDim, 1.0));
                if (hasEdgeWeights) {
                    assert tokens.length > edgeWeightDim;
                    for (int j = 0; j < edgeWeightDim; j++) {
                        weights.set(j, Double.parseDouble(tokens[j]));
                    }
                }

                int nodeIdsOffset = hasEdgeWeights ? edgeWeightDim : 0;
                Set<Integer> nodeIds = new HashSet<>();
                for (int j = nodeIdsOffset; j < tokens.length; j++) {
                    nodeIds.add(Integer.parseInt(tokens[j]) - 1); // hmetis node index starts from 1
                }

                edgeWeights.add(weights);
                edge2NodeIds.add(nodeIds);
            }

            for (int i = 0; i < nodeNum; i++) {
                List<Double> weights = new ArrayList<>(Collections.nCopies(nodeWeightDim, 1.0));
                if (hasNodeWeights) {
                    line = reader.readLine();
                    tokens = line.split(" ");
                    assert tokens.length == nodeWeightDim;
                    for (int j = 0; j < nodeWeightDim; j++) {
                        weights.set(j, Double.parseDouble(tokens[j]));
                    }
                }
                nodeWeights.add(weights);
            }

            for (int i = 0; i < nodeNum; i++) {
                hyperGraph.addNode(nodeWeights.get(i));
            }

            for (int i = 0; i < edgeNum; i++) {
                hyperGraph.addEdge(edge2NodeIds.get(i), edgeWeights.get(i));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return hyperGraph;
    }

    // 
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
