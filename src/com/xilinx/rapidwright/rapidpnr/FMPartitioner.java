package com.xilinx.rapidwright.rapidpnr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FMPartitioner {
    private final int blockNum = 2;


    private HierarchicalLogger logger;
    private HyperGraph hyperGraph;
    private Double imbalanceFac;

    private List<Double> blockSizeUpperBound;
    private List<Double> blockSizeLowerBound;

    Map<Integer, Integer> oppositeNodesMap; 

    // Partition states
    private List<Integer> node2BlockId;
    private Map<Integer, Double> node2GainMap;
    private List<List<Double>> blockSizes;
    private Double cutSize;
    
    public FMPartitioner(HierarchicalLogger logger, HyperGraph hyperGraph, Double imbalanceFac) {

        this.logger = logger;
        this.hyperGraph = hyperGraph;
        this.imbalanceFac = imbalanceFac;
        this.oppositeNodesMap = new HashMap<>();

        List<Double> totalNodeWeights = hyperGraph.getTotalNodeWeight();

        blockSizeUpperBound = new ArrayList<>();
        blockSizeLowerBound = new ArrayList<>();
        for (Double nodeWeight : totalNodeWeights) {
            Double upperBound = nodeWeight * ((1.0 / blockNum) + imbalanceFac);
            Double lowerBound = nodeWeight * ((1.0 / blockNum) - imbalanceFac);
            blockSizeLowerBound.add(lowerBound);
            blockSizeUpperBound.add(upperBound);
        }
    }

    public void setConflictNodes(int nodeId1, int nodeId2) {
        assert nodeId1 < hyperGraph.getNodeNum() && nodeId1 >= 0;
        assert nodeId2 < hyperGraph.getNodeNum() && nodeId2 >= 0;

        oppositeNodesMap.put(nodeId1, nodeId2);
        oppositeNodesMap.put(nodeId2, nodeId1);
    }

    public List<Integer> run() {
        logger.info("Start running FM Partitioner");
        logger.newSubStep();

        randomInitialPart();

        vertexBasedRefine();

        printPartResult();

        logger.endSubStep();
        logger.info("Complete FM Partitioner");

        return node2BlockId;
    }

    public void printPartResult() {
        logger.info("Partition Info:");
        logger.info(String.format("Node Num=%d Total Node Weight=%s", hyperGraph.getNodeNum(), hyperGraph.getTotalNodeWeight()));
        logger.info(String.format("Edge Num=%d Total Edge Weight=%s", hyperGraph.getEdgeNum(), hyperGraph.getTotalEdgeWeight()));
        logger.info(String.format("Imbalance Factor=%.3f UpperBound=%s LowerBound=%s", imbalanceFac, blockSizeUpperBound, blockSizeLowerBound));
        logger.info(String.format("Block Sizes=%s", blockSizes));
        logger.info(String.format("Cut Size=%.3f", cutSize));
    }


    private void randomInitialPart() {
        logger.info("Start random shuffling and greedy initial partition");

        // random shuffling
        List<Integer> randomNodeSeq = new ArrayList<>();
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            randomNodeSeq.add(nodeId);
        }
        Collections.shuffle(randomNodeSeq);

        node2BlockId = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), -1));
        blockSizes = new ArrayList<>();
        for (int blockId = 0; blockId < blockNum; blockId++) {
            blockSizes.add(new ArrayList<>(Collections.nCopies(hyperGraph.getNodeWeightDim(), 0.0)));
        }

        // assgin opposite nodes to different blocks
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            if (oppositeNodesMap.containsKey(nodeId)) {
                int opNodeId = oppositeNodesMap.get(nodeId);

                int blockId = randomNodeSeq.indexOf(nodeId) % blockNum;
                if (node2BlockId.get(opNodeId) != -1) {
                    blockId = getOppositeBlkId(node2BlockId.get(opNodeId));
                }

                node2BlockId.set(nodeId, blockId);
                HyperGraph.accuWeights(blockSizes.get(blockId), hyperGraph.getWeightsOfNode(nodeId));
            }
        }

        // greedy-based initial partition
        for (int nodeId : randomNodeSeq) {
            if (node2BlockId.get(nodeId) != -1) {
                continue;
            }

            List<Double> blkId2CutSizeIncr = new ArrayList<>(Collections.nCopies(blockNum, 0.0));
            List<Boolean> blkId2Legality = new ArrayList<>(Collections.nCopies(blockNum, false));

            Set<Integer> alreadyCutEdge = new HashSet<>();
            for (int edgeId : hyperGraph.getEdgesOfNode(nodeId)) {
                if (hyperGraph.isCutEdge(edgeId, node2BlockId)) {
                    alreadyCutEdge.add(edgeId);
                }
            }

            for (int blockId = 0; blockId < blockNum; blockId++) {
                blkId2Legality.set(blockId, isMoveLegal(nodeId, blockId));

                node2BlockId.set(nodeId, blockId);
                for (int edgeId : hyperGraph.getEdgesOfNode(nodeId)) {
                    if (alreadyCutEdge.contains(edgeId)) {
                        continue;
                    }

                    if (hyperGraph.isCutEdge(edgeId, node2BlockId)) {
                        Double originCutSizeIncr = blkId2CutSizeIncr.get(blockId);
                        blkId2CutSizeIncr.set(blockId, originCutSizeIncr + hyperGraph.getEdgeWeightsSum(edgeId));
                    }
                }
                node2BlockId.set(nodeId, -1);
            }

            assert blkId2Legality.get(0) || blkId2Legality.get(1);
            if (!isMoveLegal(nodeId, 0)) {
                node2BlockId.set(nodeId, 1);
                HyperGraph.accuWeights(blockSizes.get(1), hyperGraph.getWeightsOfNode(nodeId));

            } else if (!isMoveLegal(nodeId, 1)) {
                node2BlockId.set(nodeId, 0);
                HyperGraph.accuWeights(blockSizes.get(0), hyperGraph.getWeightsOfNode(nodeId));
            } else {
                if (blkId2CutSizeIncr.get(0) <= blkId2CutSizeIncr.get(1)) {
                    node2BlockId.set(nodeId, 0);
                    HyperGraph.accuWeights(blockSizes.get(0), hyperGraph.getWeightsOfNode(nodeId));
                } else {
                    node2BlockId.set(nodeId, 1);
                    HyperGraph.accuWeights(blockSizes.get(1), hyperGraph.getWeightsOfNode(nodeId));
                }
            }
        }

        // update other states
        node2GainMap = getNode2MoveGainMap(node2BlockId);
        cutSize = hyperGraph.getEdgeWeightsSum(hyperGraph.getCutSize(node2BlockId));

        printPartResult();

        logger.info("Complete random shuffling and greedy initial partition");
    }

    private void ILPInitialPart() {
        assert false; // TODO:
    }

    

    private void vertexBasedRefine() {
        logger.info("Start vertex-based cut size refinement");
        int iterIdx = 0;

        logger.newSubStep();
        while (true) {
            logger.info("Start Pass " + iterIdx);
            Double passGain = 0.0;

            Double maxPassGain = 0.0;
            int maxPassGainId = -1;

            List<Boolean> isNodeMoved = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), false));
            List<Integer> trialMoveNodesSeq = new ArrayList<>();

            while (trialMoveNodesSeq.size() < hyperGraph.getNodeNum()) {
                List<Integer> sortedNodes = getSortedNodes();

                boolean hasLegalMove = false;
                for (int nodeId : sortedNodes) {
                    if (isNodeMoved.get(nodeId)) {
                        continue;
                    }

                    int fromBlockId = node2BlockId.get(nodeId);
                    int toBlockId = getOppositeBlkId(fromBlockId);

                    if (isMoveLegal(nodeId, toBlockId)) {
                        logger.info(String.format("Trial move node-%d from blk-%d to blk-%d: gain=%.3f pass_gain=%.3f", 
                        nodeId, fromBlockId, toBlockId, node2GainMap.get(nodeId), passGain));

                        passGain += node2GainMap.get(nodeId);
                        if (passGain > maxPassGain) {
                            maxPassGain = passGain;
                            maxPassGainId = trialMoveNodesSeq.size();
                        }
                        moveNode(nodeId, toBlockId);
                        isNodeMoved.set(nodeId, true);
                        trialMoveNodesSeq.add(nodeId);
                        hasLegalMove = true;

                        break;
                    }
                }

                if (!hasLegalMove) {
                    break;
                }
            }

            // withdraw node moves after maxPassGainId
            logger.info("Withdraw node moves after maxPassGainId=" + maxPassGainId);
            for (int i = trialMoveNodesSeq.size() - 1; i > maxPassGainId; i--) {
                int nodeId = trialMoveNodesSeq.get(i);
                int fromBlockId = node2BlockId.get(nodeId);
                int toBlockId = getOppositeBlkId(fromBlockId);
                moveNode(nodeId, toBlockId);
            }

            if (maxPassGain <= 0) {
                logger.info("No move gain found in this pass and the refinement is done");
                break;
            }

            logger.info(String.format("Complete pass-%d with %d nodes moved and cut size decreased by %.3f", iterIdx, maxPassGainId + 1, maxPassGain));

            iterIdx++;
        }

        logger.endSubStep();

        logger.info("Complete vertex-based cut size refinement");
    }


    private void moveNode(int nodeId, int toBlockId) {
        int fromBlkId = node2BlockId.get(nodeId);
        if (fromBlkId == toBlockId) {
            return;
        }

        // update block sizes
        List<Double> nodeWeight = hyperGraph.getWeightsOfNode(nodeId);
        if (fromBlkId != -1) {
            blockSizes.set(fromBlkId, vecSub(blockSizes.get(fromBlkId), nodeWeight));
        }
        blockSizes.set(toBlockId, vecAdd(blockSizes.get(toBlockId), nodeWeight));

        // update cut size
        cutSize -= node2GainMap.get(nodeId);

        // update node2GainMap
        updateNodeGain(nodeId, toBlockId);

        // update node2BlockId
        node2BlockId.set(nodeId, toBlockId);
    }

    private void updateNodeGain(int nodeId, int toBlockId) {
        int fromBlockId = node2BlockId.get(nodeId);
        if (fromBlockId == toBlockId) {
            return;
        }

        for (int edgeId : hyperGraph.getEdgesOfNode(nodeId)) {
            Double edgeWeight = hyperGraph.getEdgeWeightsSum(edgeId);

            List<List<Integer>> block2Nodes = new ArrayList<>();
            for (int blockId = 0; blockId < blockNum; blockId++) {
                block2Nodes.add(new ArrayList<>());
            }

            for (int nNodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                if (nNodeId == nodeId) {
                    continue;
                }

                int nBlkId = node2BlockId.get(nNodeId);
                block2Nodes.get(nBlkId).add(nNodeId);
            }

            if (block2Nodes.get(toBlockId).size() == 0) {
                for (int nNodeId : block2Nodes.get(fromBlockId)) {
                    Double originGain = node2GainMap.get(nNodeId);
                    node2GainMap.replace(nNodeId, originGain + edgeWeight);
                }

                Double originGain = node2GainMap.get(nodeId);
                node2GainMap.replace(nodeId, originGain + 2 * edgeWeight);
            }

            if (block2Nodes.get(toBlockId).size() == 1) {
                int nNodeId = block2Nodes.get(toBlockId).get(0);
                Double originGain = node2GainMap.get(nNodeId);
                node2GainMap.replace(nNodeId, originGain - edgeWeight);
            }

            if (block2Nodes.get(fromBlockId).size() == 0) {
                for (int nNodeId : block2Nodes.get(toBlockId)) {
                    Double originGain = node2GainMap.get(nNodeId);
                    node2GainMap.replace(nNodeId, originGain - edgeWeight);
                }

                Double originGain = node2GainMap.get(nodeId);
                node2GainMap.replace(nodeId, originGain - 2 * edgeWeight);
            }

            if (block2Nodes.get(fromBlockId).size() == 1) {
                int nNodeId = block2Nodes.get(fromBlockId).get(0);
                Double originGain = node2GainMap.get(nNodeId);
                node2GainMap.replace(nNodeId, originGain + edgeWeight);
            }
        }
    }

    private Map<Integer, Double> getNode2MoveGainMap(List<Integer> partResults) {
        List<Double> node2Gain = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), 0.0));

        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            Double edgeWeight = hyperGraph.getEdgeWeightsSum(edgeId);
            List<List<Integer>> block2Nodes = new ArrayList<>();
            for (int blockId = 0; blockId < blockNum; blockId++) {
                block2Nodes.add(new ArrayList<>());
            }
            
            for (int nodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                int blkId = partResults.get(nodeId);
                assert blkId < blockNum && blkId >= 0;
                block2Nodes.get(blkId).add(nodeId);
            }

            if (block2Nodes.get(0).size() == 1 || block2Nodes.get(1).size() == 0) {
                for (int nodeId : block2Nodes.get(0)) {
                    if (block2Nodes.get(0).size() == 1) {
                        node2Gain.set(nodeId, node2Gain.get(nodeId) + edgeWeight);
                    } else {
                        node2Gain.set(nodeId, node2Gain.get(nodeId) - edgeWeight);
                    }
                }
            }
    
            if (block2Nodes.get(1).size() == 1 || block2Nodes.get(0).size() == 0) {
                for (int nodeId : block2Nodes.get(1)) {
                    if (block2Nodes.get(1).size() == 1) {
                        node2Gain.set(nodeId, node2Gain.get(nodeId) + edgeWeight);
                    } else {
                        node2Gain.set(nodeId, node2Gain.get(nodeId) - edgeWeight);
                    }
                }
            }
        }

        Map<Integer, Double> node2MoveGain = new HashMap<>();
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            node2MoveGain.put(nodeId, node2Gain.get(nodeId));
        }

        return node2MoveGain;
    }

    private List<Integer> getSortedNodes() {
        return node2GainMap.entrySet()
                           .stream()
                           .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                           .map(Map.Entry::getKey)
                           .collect(Collectors.toList());
    }

    private boolean isMoveLegal(int nodeId, int toBlockId) {
        assert toBlockId < blockNum && toBlockId >= 0;
        assert nodeId < hyperGraph.getNodeNum() && nodeId >= 0;

        int fromBlkId = node2BlockId.get(nodeId);
        if (fromBlkId == toBlockId) {
            return true;
        }

        //List<Double> fromBlockSize = vecSub(blockSizes.get(fromBlkId), hyperGraph.getWeightsOfNode(nodeId));
        List<Double> toBlockSize = vecAdd(hyperGraph.getWeightsOfNode(nodeId), blockSizes.get(toBlockId));

        Boolean blockSizeConstr = vecLessEq(toBlockSize, blockSizeUpperBound);
        Boolean oppositeNodeConstr = !oppositeNodesMap.containsKey(nodeId);
        // if (oppositeNodesMap.containsKey(nodeId)) {
        //     int oppositeNodeId = oppositeNodesMap.get(nodeId);
        //     oppositeNodeConstr = node2BlockId.get(oppositeNodeId) != toBlockId;
        // }
        //return vecLessEq(toBlockSize, blockSizeUpperBound) && vecGreaterEq(fromBlockSize, blockSizeLowerBound);
        return blockSizeConstr && oppositeNodeConstr;
    }

    // helper functions
    public void checkPartitionResult() {
        logger.info("Start checking partition result");

        List<List<Double>> refBlockSizes = hyperGraph.getBlockSize(node2BlockId);
        logger.info("Reference Block Sizes: " + refBlockSizes);
        logger.info("Current Block Sizes: " + blockSizes);

        Double refCutSize = hyperGraph.getEdgeWeightsSum(hyperGraph.getCutSize(node2BlockId));
        logger.info("Reference Cut Size: " + refCutSize);
        logger.info("Current Cut Size: " + cutSize);

        // check cut size and node2GainMap
        Map<Integer, Double> refNode2Gain = getNode2MoveGainMap(node2BlockId);

        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            assert refNode2Gain.get(nodeId).equals(node2GainMap.get(nodeId));
            if (refNode2Gain.get(nodeId) != node2GainMap.get(nodeId)) {
                logger.info(String.format("Node-%d: ref_gain=%.3f current_gain=%.3f", nodeId, refNode2Gain.get(nodeId), node2GainMap.get(nodeId)));
            }
        }

        logger.info("Complete checking partition result");
    }

    private static int getOppositeBlkId(Integer blkId) {
        return blkId == 0 ? 1 : 0;
    }

    private static List<Double> vecAdd(List<Double> a, List<Double> b) {
        assert a.size() == b.size(): String.format("Size of operand a and b: %d %d", a.size(), b.size());
        List<Double> res = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            res.add(a.get(i) + b.get(i));
        }
        return res;
    }

    List<Double> vecSub(List<Double> a, List<Double> b) {
        assert a.size() == b.size();
        List<Double> res = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            res.add(a.get(i) - b.get(i));
        }
        return res;
    }

    boolean vecLessEq(List<Double> a, List<Double> b) {
        assert a.size() == b.size();
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) > b.get(i)) {
                return false;
            }
        }
        return true;
    }

    boolean vecGreaterEq(List<Double> a, List<Double> b) {
        assert a.size() == b.size();
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) < b.get(i)) {
                return false;
            }
        }
        return true;
    }

    boolean vecEq(List<Double> a, List<Double> b) {
        assert a.size() == b.size();
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

}
