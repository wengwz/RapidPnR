package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.LinkedList;

import com.xilinx.rapidwright.rapidpnr.partitioner.TritonPartitionWrapper;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierHyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;

public class RecursiveTreePlacer extends AbstractIslandPlacer{

    private class PartitionTreeNode {
        int depth;
        HierHyperGraph hyperGraph;
        PartitionTreeNode parent = null;

        PartitionTreeNode leftChild = null;
        PartitionTreeNode rightChild = null;

        public PartitionTreeNode(int depth, HierHyperGraph hyperGraph) {
            this.depth = depth;
            this.hyperGraph = hyperGraph;
        }

        public PartitionTreeNode createLeftChild(HierHyperGraph hyperGraph) {
            assert leftChild == null;
            leftChild = new PartitionTreeNode(depth + 1, hyperGraph);
            leftChild.parent = this;

            return leftChild;
        }

        public PartitionTreeNode createRightChild(HierHyperGraph hyperGraph) {
            assert rightChild == null;
            rightChild = new PartitionTreeNode(depth + 1, hyperGraph);
            rightChild.parent = this;

            return rightChild;
        }
    };

    private int maxTreeDepth;
    private PartitionTreeNode rootNode;
    private List<List<Integer>> node2PartResults;

    public RecursiveTreePlacer(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams designParams) {
        super(logger, dirManager, designParams);

        Coordinate2D gridDim = designParams.getGridDim();
        assert gridDim.getX() == gridDim.getY(): "grid size should be equal in each dimension";
        maxTreeDepth = gridDim.getX();
    }

    public List<Coordinate2D> run(AbstractNetlist netlist) {

        this.abstractNetlist = netlist;
        // construct root node
        rootNode = new PartitionTreeNode(0, createHyperGraphFromNetlist(netlist));
        // initialize partition results for each node
        node2PartResults = new ArrayList<>();
        for (int nodeId = 0; nodeId < netlist.getNodeNum(); nodeId++) {
            node2PartResults.add(new ArrayList<>(Collections.nCopies(maxTreeDepth, -1)));
        }

        Path placeDir = dirManager.addSubDir("island_place");
        Queue<PartitionTreeNode> nodeSearchQueue = new LinkedList<>();
        nodeSearchQueue.add(rootNode);

        while (!nodeSearchQueue.isEmpty()) {
            PartitionTreeNode curNode = nodeSearchQueue.poll();
            int partitionIter = curNode.depth;
            int partitionDim = getDimFromIter(partitionIter);
            HierHyperGraph curHyperGraph = curNode.hyperGraph;

            logger.info("Partition iteration: " + partitionIter + " Partition dimension: " + partitionDim);
            
            if (partitionIter >= maxTreeDepth) {
                continue;
            }

            // get current location of nodes
            int internalNodeLoc = -1;
            Map<Integer, Integer> extNode2Loc = new HashMap<>();
            for (int nodeId = 0; nodeId < curHyperGraph.getNodeNum(); nodeId++) {
                int rootNodeId = curHyperGraph.getRootParentsOfNode(nodeId).get(0);
                int loc = getLocOfNode(rootNodeId, partitionDim);
                
                if (curHyperGraph.isExtVirtualNode(nodeId)) {
                    extNode2Loc.put(nodeId, loc);
                } else {
                    if (internalNodeLoc == -1) {
                        internalNodeLoc = loc;
                    } else {
                        assert internalNodeLoc == loc: "inconsistent location of internal nodes";
                    }
                }
            }

            // get fixed nodes and scale factor of edge weight
            Map<Integer, Integer> fixedNode2Blk = new HashMap<>();
            Map<Integer, Double> edge2ScaleFac = new HashMap<>();
            for (int nodeId : extNode2Loc.keySet()) {
                int loc = extNode2Loc.get(nodeId);
                if (loc == -1) continue; // skip nodes with unknown location

                internalNodeLoc = internalNodeLoc == -1 ? 0 : internalNodeLoc;
                int dist = loc - internalNodeLoc;

                int blkId = dist > 0 ? 1 : 0;
                fixedNode2Blk.put(nodeId, blkId);

                Double weightFac = Math.pow(2.0, Math.abs(dist));
                for (int edgeId : curHyperGraph.getEdgesOfNode(nodeId)) {
                    if (!edge2ScaleFac.containsKey(edgeId)) {
                        edge2ScaleFac.put(edgeId, weightFac);
                    } else {
                        Double originWeightFac = edge2ScaleFac.get(edgeId);
                        if (weightFac > originWeightFac) {
                            edge2ScaleFac.put(edgeId, weightFac);
                        }
                    }
                }
            }
            // update edge weights
            for (int edgeId : edge2ScaleFac.keySet()) {
                Double scaleFac = edge2ScaleFac.get(edgeId);
                List<Double> weights = curHyperGraph.getWeightsOfEdge(edgeId);
                List<Double> newWeights = new ArrayList<>(weights);
                newWeights.set(1, weights.get(1) * scaleFac);
                curHyperGraph.setEdgeWeights(edgeId, newWeights);
            }

            // partition netlist
            TritonPartitionWrapper.Config config = new TritonPartitionWrapper.Config(placeDir);
            TritonPartitionWrapper partitioner = new TritonPartitionWrapper(logger, config, curHyperGraph);
            if (fixedNode2Blk.size() > 0) {
                partitioner.setFixedNodes(fixedNode2Blk);
            }
            List<Integer> partResults = partitioner.run();

            // update partition results
            List<List<Integer>> leftChildNodes = new ArrayList<>();
            List<List<Integer>> rightChildNodes = new ArrayList<>();
            for (int nodeId = 0; nodeId < curHyperGraph.getNodeNum(); nodeId++) {
                if (curHyperGraph.isExtVirtualNode(nodeId)) {
                    continue; // skip external virtual nodes
                }

                int partResult = partResults.get(nodeId);
                assert partResult == 0 || partResult == 1: "invalid partition result";
                if (partResult == 0) {
                    leftChildNodes.add(new ArrayList<>(Arrays.asList(nodeId)));
                } else {
                    rightChildNodes.add(new ArrayList<>(Arrays.asList(nodeId)));
                }
                
                // update global partition results
                int rootNodeId = curHyperGraph.getRootParentsOfNode(nodeId).get(0);
                node2PartResults.get(rootNodeId).set(partitionIter, partResult);
            }
            logger.info("Num of nodes in left child: " + leftChildNodes.size());
            logger.info("Num of nodes in right child: " + rightChildNodes.size());

            // construct new child nodes
            HierHyperGraph leftChildGraph = curHyperGraph.createClusteredChildGraph(leftChildNodes, false);
            PartitionTreeNode leftChildNode = curNode.createLeftChild(leftChildGraph);
            logger.info("Num of nodes in left child graph: " + leftChildGraph.getNodeNum());

            HierHyperGraph rightChildGraph = curHyperGraph.createClusteredChildGraph(rightChildNodes, false);
            PartitionTreeNode rightChildNode = curNode.createRightChild(rightChildGraph);
            logger.info("Num of nodes in right child graph: " + rightChildGraph.getNodeNum());

            nodeSearchQueue.add(leftChildNode);
            nodeSearchQueue.add(rightChildNode);
        }

        List<Coordinate2D> nodeLocs = new ArrayList<>();
        for (int nodeId = 0; nodeId < netlist.getNodeNum(); nodeId++) {
            nodeLocs.add(getLocOfNode(nodeId));
        }

        return nodeLocs;
    }


    private HierHyperGraph createHyperGraphFromNetlist(AbstractNetlist netlist) {
        //
        // List<String> resourceTypes = Arrays.asList("LUT", "FF", "DSP", "BRAM");
        // List<Double> nodeWeightFac = Collections.nCopies(resourceTypes.size(), 1.0);
        List<Double> nodeWeightFac = Collections.nCopies(1, 1.0);
        List<Double> edgeWeightFac = Collections.nCopies(2, 1.0); // 2-dim edge weights
        HierHyperGraph netlistGraph = new HierHyperGraph(nodeWeightFac, edgeWeightFac);

        for (int nodeId = 0; nodeId < netlist.getNodeNum(); nodeId++) {
            // List<Double> nodeWeights = new ArrayList<>();
            
            // Map<String, Integer> resType2Amount = netlist.getResUtilOfNode(nodeId);
            // for (String resType : resourceTypes) {
            //     if (resType2Amount.containsKey(resType)) {
            //         nodeWeights.add((double) resType2Amount.get(resType));
            //     } else {
            //         nodeWeights.add(0.0);
            //     }
            // }
            // netlistGraph.addNode(nodeWeights);

            netlistGraph.addNode(Arrays.asList((double) netlist.getLeafCellNumOfNode(nodeId)));
        }

        Map<Set<Integer>, List<Integer>> compressedEdges = compressAbstractEdges(abstractNetlist.edge2NodeIds);

        for (Map.Entry<Set<Integer>, List<Integer>> entry : compressedEdges.entrySet()) {
            Set<Integer> groupIds = entry.getKey();
            List<Integer> originEdgeIds = entry.getValue();

            List<Double> weights = Arrays.asList((double) originEdgeIds.size(), 0.0);
            netlistGraph.addEdge(groupIds, weights);
        }

        return netlistGraph;
    }


    // helper functions
    private int getDimFromIter(int iter) {
        return iter % 2;
    }

    private int totalHoriIterNum() {
        return (maxTreeDepth + 1) / 2;
    }

    private int totalVertIterNum() {
        return maxTreeDepth / 2;
    }

    private int getLocOfNode(int nodeId, int dim) {
        assert nodeId < node2PartResults.size();
        assert dim == 0 || dim == 1: "invalid dimension value"; // x-axis: 0, y-axis: 1
        List<Integer> partResults = node2PartResults.get(nodeId);

        int loc = 0;
        for (int iter = 0; iter < maxTreeDepth; iter++) {
            if (getDimFromIter(iter) != dim) continue;

            if (iter < 2 && partResults.get(iter) == -1) {
                loc = -1;
                break;
            }

            loc = loc * 2;
            if (partResults.get(iter) != -1) {
                loc += partResults.get(iter);
            }
        }
        return loc;
    }

    private Coordinate2D getLocOfNode(int nodeId) {
        return new Coordinate2D(getLocOfNode(nodeId, 0), getLocOfNode(nodeId, 1));
    }

    public static void main(String[] args) {
        int testValue = 9;
        int divValue = testValue / 2;

        System.out.println("testValue: " + testValue);
        System.out.println("divValue: " + divValue);
    }
}
