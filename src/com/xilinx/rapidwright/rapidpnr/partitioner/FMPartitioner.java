package com.xilinx.rapidwright.rapidpnr.partitioner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.HyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.SortedList;

public class FMPartitioner extends AbstractPartitioner {

    public static class Config extends AbstractConfig {
        public int maxPassNum = Integer.MAX_VALUE; // maximum number of passes
        public double passEarlyExitRatio = 1.0; // number of non-gain moves before early exit
        public double extremeLargeRatio = 0.15; // extremely large nodes needs to be initialized first;

        public Config() {
            super();
        }

        public Config(AbstractConfig config) {
            super(config);
        }

        public Config(AbstractConfig config, int maxPassNum, double passEarlyExitRatio, double extremeLargeRatio) {
            super(config);

            this.maxPassNum = maxPassNum;
            this.passEarlyExitRatio = passEarlyExitRatio;
            this.extremeLargeRatio = extremeLargeRatio;
        }
    }

    private Config config;
    // partition states
    // private SortedList<Double> node2MoveGain;
    
    public FMPartitioner(HierarchicalLogger logger, Config config, HyperGraph hyperGraph) {
        super(logger, config, hyperGraph);

        this.config = config;

        assert config.blockNum == 2: "FMPartitioner only supports 2-way partition currently";
    }

    public List<Integer> run(List<Integer> initialPartRes) {
        logger.info("Start running FM Partition");
        logger.newSubStep();

        if (initialPartRes != null) {
            setPartResult(initialPartRes, true);
        } else {
            randomInitialPart();
        }

        vertexBasedRefine();

        printPartitionInfo();

        logger.endSubStep();
        logger.info("Complete FM Partition");

        return Collections.unmodifiableList(node2BlockId);
    }

    public List<Integer> run() {
        return run(null);
    }

    public void setMaxPassNum(int maxPassNum) {
        assert maxPassNum > 0;
        this.config.maxPassNum = maxPassNum;
    }

    public void setPassEarlyExitRatio(double ratio) {
        assert ratio > 0.0 && ratio < 1.0;
        this.config.passEarlyExitRatio = ratio;
    }

    protected void randomInitialPart() {
        logger.info("Start random shuffling and greedy initial partition");

        // random shuffling nodes
        List<Double> largeNodeWeight = vecMulScalar(hyperGraph.getTotalNodeWeight(), config.extremeLargeRatio);
        List<Integer> randomNodeSeq = new ArrayList<>();
        List<Integer> shuffleNodes = new ArrayList<>();
        for (int nodeId : getWeightSortedNodeIds()) {
            if (!vecLessEq(hyperGraph.getWeightsOfNode(nodeId), largeNodeWeight)) {
                randomNodeSeq.add(nodeId);
            } else {
                shuffleNodes.add(nodeId);
            }
        }
        Collections.shuffle(shuffleNodes, new Random(config.randomSeed));
        randomNodeSeq.addAll(shuffleNodes);

        int assignedNodeNum = 0;
        // assgin fixed nodes
        for (int nodeId : fixedNodes.keySet()) {
            int blockId = fixedNodes.get(nodeId);

            node2BlockId.set(nodeId, blockId);
            List<Double> nodeWeights = hyperGraph.getWeightsOfNode(nodeId);
            vecAccu(blockSizes.get(blockId), nodeWeights);
            assignedNodeNum++;
        }
        assert checkSizeConstr(): "Fixed nodes constraints violate block size constraint";

        // greedy-based initial partition
        for (int nodeId : randomNodeSeq) {
            if (node2BlockId.get(nodeId) != -1) {
                continue;
            }

            List<Double> blkId2CutSizeIncr = new ArrayList<>(Collections.nCopies(config.blockNum, 0.0));
            List<Boolean> blkId2Legality = new ArrayList<>(Collections.nCopies(config.blockNum, false));

            Set<Integer> alreadyCutEdge = new HashSet<>();
            for (int edgeId : hyperGraph.getEdgesOfNode(nodeId)) {
                if (hyperGraph.isCutEdge(edgeId, node2BlockId)) {
                    alreadyCutEdge.add(edgeId);
                }
            }

            for (int blockId = 0; blockId < config.blockNum; blockId++) {
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

            if (!(blkId2Legality.get(0) || blkId2Legality.get(1))) {
                logger.info(blockSizes.toString());
            }
            
            
            assert blkId2Legality.get(0) || blkId2Legality.get(1): 
            String.format("No legal move found for node-%d (node size=%s already assigned=%d)", nodeId, hyperGraph.getWeightsOfNode(nodeId), assignedNodeNum);
            
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

            assignedNodeNum++;
        }

        // update other states
        cutSize = hyperGraph.getEdgeWeightsSum(hyperGraph.getCutSize(node2BlockId));

        printPartitionStates();

        logger.info("Complete random shuffling and greedy initial partition");
    }

    protected void vertexBasedRefine() {
        logger.info("Start vertex-based cut size refinement");
        int iterIdx = 0;
        SortedList<Double> node2MoveGain = setupNode2MoveGain(node2BlockId);
        int maxNoGainMoveNum = (int) (config.passEarlyExitRatio * hyperGraph.getNodeNum());
        //logger.info("Maximum no gain move before early exit: " + maxNoGainMoveNum);

        logger.newSubStep();
        while (iterIdx < config.maxPassNum) {
            logger.info("Start refinement pass " + iterIdx);
            Double passGain = 0.0;

            Double maxPassGain = 0.0;
            int maxPassGainId = -1;
            int noGainMoveNum = 0;

            List<Boolean> isNodeMoved = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), false));
            List<Integer> trialMoveNodesSeq = new ArrayList<>();

            while (trialMoveNodesSeq.size() < hyperGraph.getNodeNum()) {
                List<Integer> sortedNodes = node2MoveGain.getSortedIndices();

                boolean hasLegalMove = false;
                for (int nodeId : sortedNodes) {
                    if (isNodeMoved.get(nodeId)) {
                        continue;
                    }

                    int fromBlockId = node2BlockId.get(nodeId);
                    int toBlockId = getOppositeBlkId(fromBlockId);

                    if (isMoveLegal(nodeId, toBlockId)) {
                        Double nodeGain = node2MoveGain.getValueOf(nodeId);
                        if (config.verbose) {
                            logger.info(String.format("Trial move node-%d from blk-%d to blk-%d: gain=%.3f pass_gain=%.3f", 
                            nodeId, fromBlockId, toBlockId, nodeGain, passGain));
                        }

                        passGain += nodeGain;
                        if (passGain > maxPassGain) {
                            maxPassGain = passGain;
                            maxPassGainId = trialMoveNodesSeq.size();
                        }
                        moveNode(nodeId, toBlockId, node2MoveGain);
                        isNodeMoved.set(nodeId, true);
                        trialMoveNodesSeq.add(nodeId);
                        hasLegalMove = true;
                        if (nodeGain <= 0) noGainMoveNum++;

                        break;
                    }
                }

                if (!hasLegalMove || noGainMoveNum > maxNoGainMoveNum) {
                    break;
                }
            }

            // withdraw node moves after maxPassGainId
            if (config.verbose) {
                logger.info("Withdraw node moves after maxPassGainId=" + maxPassGainId);
            }
            for (int i = trialMoveNodesSeq.size() - 1; i > maxPassGainId; i--) {
                int nodeId = trialMoveNodesSeq.get(i);
                int fromBlockId = node2BlockId.get(nodeId);
                int toBlockId = getOppositeBlkId(fromBlockId);
                moveNode(nodeId, toBlockId, node2MoveGain);
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


    protected void moveNode(int nodeId, int toBlockId, SortedList<Double> node2MoveGain) {
        int fromBlkId = node2BlockId.get(nodeId);
        if (fromBlkId == toBlockId) {
            return;
        }

        // update block sizes
        List<Double> nodeWeight = hyperGraph.getWeightsOfNode(nodeId);
        if (fromBlkId != -1) {
            vecDec(blockSizes.get(fromBlkId), nodeWeight);
        }
        vecAccu(blockSizes.get(toBlockId), nodeWeight);

        // update cut size
        cutSize -= node2MoveGain.getValueOf(nodeId);

        // update node2GainMap

        updateNodeGain(nodeId, toBlockId, node2MoveGain);

        // update node2BlockId
        node2BlockId.set(nodeId, toBlockId);
    }

    protected void updateNodeGain(int nodeId, int toBlockId, SortedList<Double> node2MoveGain) {
        int fromBlockId = node2BlockId.get(nodeId);
        if (fromBlockId == toBlockId) {
            return;
        }

        for (int edgeId : hyperGraph.getEdgesOfNode(nodeId)) {
            Double edgeWeight = hyperGraph.getEdgeWeightsSum(edgeId);

            List<List<Integer>> block2Nodes = new ArrayList<>();
            for (int blockId = 0; blockId < config.blockNum; blockId++) {
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
                    Double originGain = node2MoveGain.getValueOf(nNodeId);
                    node2MoveGain.update(nNodeId, originGain + edgeWeight);
                }

                Double originGain = node2MoveGain.getValueOf(nodeId);
                node2MoveGain.update(nodeId, originGain + 2 * edgeWeight);
            }

            if (block2Nodes.get(toBlockId).size() == 1) {
                int nNodeId = block2Nodes.get(toBlockId).get(0);
                Double originGain = node2MoveGain.getValueOf(nNodeId);
                node2MoveGain.update(nNodeId, originGain - edgeWeight);
            }

            if (block2Nodes.get(fromBlockId).size() == 0) {
                for (int nNodeId : block2Nodes.get(toBlockId)) {
                    Double originGain = node2MoveGain.getValueOf(nNodeId);
                    node2MoveGain.update(nNodeId, originGain - edgeWeight);
                }

                Double originGain = node2MoveGain.getValueOf(nodeId);
                node2MoveGain.update(nodeId, originGain - 2 * edgeWeight);
            }

            if (block2Nodes.get(fromBlockId).size() == 1) {
                int nNodeId = block2Nodes.get(fromBlockId).get(0);
                Double originGain = node2MoveGain.getValueOf(nNodeId);
                node2MoveGain.update(nNodeId, originGain + edgeWeight);
            }
        }
    }

    protected static int getOppositeBlkId(Integer blkId) {
        return blkId == 0 ? 1 : 0;
    }

    protected SortedList<Double> setupNode2MoveGain(List<Integer> partResults) {
        List<Double> node2Gain = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), 0.0));

        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            Double edgeWeight = hyperGraph.getEdgeWeightsSum(edgeId);
            List<List<Integer>> block2Nodes = new ArrayList<>();
            for (int blockId = 0; blockId < config.blockNum; blockId++) {
                block2Nodes.add(new ArrayList<>());
            }
            
            for (int nodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                int blkId = partResults.get(nodeId);
                assert isBlkIdLegal(blkId);
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

        return new SortedList<Double>(node2Gain, true);
    }

    protected List<Integer> getWeightSortedNodeIds() {
        List<Double> nodeWeights = new ArrayList<>();
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            nodeWeights.add(hyperGraph.getNodeWeightsSum(nodeId));
        }
        SortedList<Double> sortedNodeWeights = new SortedList<>(nodeWeights, true);
        return sortedNodeWeights.getSortedIndices();
    }

    public static void main(String[] args) {
        Path inputGraphPath = Path.of("workspace/test/nvdla-tpw-cls.hgr").toAbsolutePath();
        HierarchicalLogger logger = HierarchicalLogger.createLogger("TestFMPart", null, true);

        List<Double> weightFac = Arrays.asList(1.0);
        HyperGraph hyperGraph = HyperGraph.readGraphFromHmetisFormat(inputGraphPath, weightFac, weightFac);

        Config config = new Config();
        FMPartitioner partitioner = new FMPartitioner(logger, config, hyperGraph);
        partitioner.run();

        assert partitioner.checkPartitionStates(): "Partition states are not consistent";
    }

}
