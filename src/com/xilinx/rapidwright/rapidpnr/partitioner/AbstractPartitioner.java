package com.xilinx.rapidwright.rapidpnr.partitioner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.HyperGraph;

abstract public class AbstractPartitioner {
    public static class AbstractConfig {
        public int blockNum = 2;
        public int randomSeed = 999;
        public List<Double> imbFactors = Arrays.asList(0.01);
        public boolean verbose = true; // verbose mode

        @Override
        public String toString() {
            return String.format("Partition Config: BlockNum=%d Seed=%d ImbFactors=%s", blockNum, randomSeed, imbFactors);
        }

        public AbstractConfig() {}

        public AbstractConfig(int blockNum, int seed, List<Double> imbFactors, boolean verbose) {
            this.blockNum = blockNum;
            this.randomSeed = seed;
            this.imbFactors = new ArrayList<>(imbFactors);
            this.verbose = verbose;
        }

        public AbstractConfig(AbstractConfig config) {
            this.blockNum = config.blockNum;
            this.randomSeed = config.randomSeed;
            this.imbFactors = new ArrayList<>(config.imbFactors);
            this.verbose = config.verbose;
        }
    }

    protected HierarchicalLogger logger;
    protected AbstractConfig config;
    protected HyperGraph hyperGraph;


    // constraints
    protected List<Double> blockSizeUpperBound;
    protected List<Double> blockSizeLowerBound;
    protected Map<Integer, Integer> fixedNodes;

    // partition states
    protected List<Integer> node2BlockId;
    protected List<List<Double>> blockSizes;
    protected Double cutSize;


    public AbstractPartitioner(HierarchicalLogger logger, AbstractConfig config, HyperGraph hyperGraph) {
        this.logger = logger;
        this.config = config;
        this.hyperGraph = hyperGraph;

        // check config
        assert config.blockNum >= 2;
        assert config.imbFactors.size() == hyperGraph.getNodeWeightDim();

        // setup constraints
        setBlockSizeBound();
        fixedNodes = new HashMap<>();

        // setup partition states
        node2BlockId = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeNum(), -1));
        blockSizes = new ArrayList<>();
        for (int blockId = 0; blockId < config.blockNum; blockId++) {
            blockSizes.add(new ArrayList<>(Collections.nCopies(hyperGraph.getNodeWeightDim(), 0.0)));
        }
        cutSize = 0.0;
    }

    // kernel function
    abstract public List<Integer> run();

    protected void setBlockSizeBound() {
        List<Double> totalNodeWeights = hyperGraph.getTotalNodeWeight();

        blockSizeUpperBound = new ArrayList<>();
        blockSizeLowerBound = new ArrayList<>();
        for (int i = 0; i < totalNodeWeights.size(); i++) {
            Double nodeWeight = totalNodeWeights.get(i);
            Double imbalanceFac = config.imbFactors.get(i);

            Double upperBound = nodeWeight * ((1.0 / config.blockNum) + imbalanceFac);
            Double lowerBound = nodeWeight * ((1.0 / config.blockNum) - imbalanceFac);
            blockSizeLowerBound.add(lowerBound);
            blockSizeUpperBound.add(upperBound);
        }
    }

    protected void moveNode(int nodeId, int toBlockId) {
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

        // update node2BlockId & cut-size
        List<Double> originCutSize = hyperGraph.getCutSizeOfNode(node2BlockId, nodeId);
        node2BlockId.set(nodeId, toBlockId);
        List<Double> newCutSize = hyperGraph.getCutSizeOfNode(node2BlockId, nodeId);
        cutSize += hyperGraph.getEdgeWeightsSum(newCutSize) - hyperGraph.getEdgeWeightsSum(originCutSize);
    }

    // refinement
    protected void edgeBasedRefinement() {
        logger.info("Start edge-based cut size refinement");
        List<Integer> randEdgeIds = new ArrayList<>();
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            randEdgeIds.add(edgeId);
        }
        Collections.shuffle(randEdgeIds, new Random(config.randomSeed));

        for (int edgeId : randEdgeIds) {
            if (!hyperGraph.isCutEdge(edgeId, node2BlockId)) continue;
            List<Double> moveGains = new ArrayList<>(Collections.nCopies(config.blockNum, 0.0));
            List<Integer> nodeIds = hyperGraph.getNodesOfEdge(edgeId);
            
            // trial move
            for (int blkId = 0; blkId < config.blockNum; blkId++) {
                boolean isMoveLegal = isMoveLegal(nodeIds, blkId);

                if (isMoveLegal) {
                    Map<Integer, Integer> movedNode2BlkId = new HashMap<>();
                    for (int nodeId : nodeIds) {
                        movedNode2BlkId.put(nodeId, blkId);
                    }
                    moveGains.set(blkId, getMoveGainOf(movedNode2BlkId));
                } else {
                    moveGains.set(blkId, Double.NEGATIVE_INFINITY);
                }
            }

            int maxGainBlkId = -1;
            Double maxGain = Double.NEGATIVE_INFINITY;
            for (int blkId = 0; blkId < config.blockNum; blkId++) {
                if (moveGains.get(blkId) > maxGain && moveGains.get(blkId) > 0) {
                    maxGain = moveGains.get(blkId);
                    maxGainBlkId = blkId;
                }
            }

            if (maxGainBlkId != -1) {
                logger.info(String.format("Move incident nodes of edge-%d to blk-%d", edgeId, maxGainBlkId));
                for (int nodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                    moveNode(nodeId, maxGainBlkId);
                }
            }
        }

        logger.info("Complete edge-based cut size refinement");
    }

    protected void randomVertexBasedRefinement() {
        logger.info("Start random vertex-based refinement");

        int passId = 0;
        double totalGain = 0;

        while(true) {
            logger.info(String.format("Start pass-%d of vertex-based refinement", passId));
            double passGain = 0;

            List<Integer> randNodeIds = new ArrayList<>();
            for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
                randNodeIds.add(nodeId);
            }
            Collections.shuffle(randNodeIds, new Random(config.randomSeed + passId));

            for (int nodeId : randNodeIds) {
                double maxGain = 0;
                int maxGainBlkId = -1;
    
                for (int blkId = 0; blkId < config.blockNum; blkId++) {
                    if (blkId == node2BlockId.get(nodeId)) continue;
                    double moveGain = getMoveGainOf(Map.of(nodeId, blkId));
                    if (maxGainBlkId == -1 || moveGain > maxGain) {
                        maxGain = moveGain;
                        maxGainBlkId = blkId;
                    }
                }
    
                if (maxGain >= 0 && isMoveLegal(nodeId, maxGainBlkId)) {
                    moveNode(nodeId, maxGainBlkId);
                    passGain += maxGain;
                }
            }
            passId++;
            totalGain += passGain;
            if (passGain == 0) break;
        }

        logger.info(String.format("Complete %d passes random vertex-based refinement with gain=%.2f", passId, totalGain));
    }

    protected Double getMoveGainOf(Map<Integer, Integer> movedNodes) {
        Double moveGain = 0.0;
        Map<Integer, Integer> fromBlkIds = new HashMap<>();

        for (int nodeId : movedNodes.keySet()) {
            int fromBlockId = node2BlockId.get(nodeId);
            int toBlockId = movedNodes.get(nodeId);
            if (toBlockId == fromBlockId) continue;

            fromBlkIds.put(nodeId, fromBlockId);
            List<Double> originCutSize = hyperGraph.getCutSizeOfNode(node2BlockId, nodeId);

            node2BlockId.set(nodeId, toBlockId);
            List<Double> curCutSize = hyperGraph.getCutSizeOfNode(node2BlockId, nodeId);

            Double gain = hyperGraph.getEdgeWeightsSum(originCutSize) - hyperGraph.getEdgeWeightsSum(curCutSize);
            moveGain += gain;
        }

        // recover node2BlockId
        for (int nodeId : fromBlkIds.keySet()) {
            node2BlockId.set(nodeId, fromBlkIds.get(nodeId));
        }

        return moveGain;
    }

    // checkers
    protected boolean checkSizeConstr() {

        // check block size constraints
        for (int blockId = 0; blockId < config.blockNum; blockId++) {
            List<Double> blockSize = blockSizes.get(blockId);
            if (!vecLessEq(blockSize, blockSizeUpperBound)) {
                return false;
            }
        }
        return true;
    }

    protected boolean checkFixedNodesConstr() {
        // check fixed node constraints
        for (int nodeId : fixedNodes.keySet()) {
            int blockId = fixedNodes.get(nodeId);
            if (node2BlockId.get(nodeId) != blockId) {
                logger.info(String.format("Fixed node-%d(%d) is not in block-%d", nodeId, node2BlockId.get(nodeId), blockId));
                return false;
            }
        }
        return true;
    }

    protected boolean checkPartitionStates() {
        List<List<Double>> refBlockSizes = hyperGraph.getBlockSize(node2BlockId);
        Double refCutSize = hyperGraph.getEdgeWeightsSum(hyperGraph.getCutSize(node2BlockId));

        if (!refCutSize.equals(cutSize)) {
            logger.severe(String.format("Cut size mismatch: %f %f", refCutSize, cutSize));
            return false;
        }

        for (int blockId = 0; blockId < config.blockNum; blockId++) {
            if (!vecEq(refBlockSizes.get(blockId), blockSizes.get(blockId))) {
                logger.severe(String.format("Size of block-%d mismatch: %s %s", blockId, refBlockSizes.get(blockId), blockSizes.get(blockId)));
                return false;
            }
        }

        return true;
    }

    protected boolean isMoveLegal(int nodeId, int toBlockId) {
        assert toBlockId < config.blockNum && toBlockId >= 0;
        assert nodeId < hyperGraph.getNodeNum() && nodeId >= 0;

        int fromBlkId = node2BlockId.get(nodeId);
        if (fromBlkId == toBlockId) {
            return true;
        }

        //List<Double> fromBlockSize = vecSub(blockSizes.get(fromBlkId), hyperGraph.getWeightsOfNode(nodeId));
        List<Double> nodeWeights = hyperGraph.getWeightsOfNode(nodeId);
        List<Double> toBlockSize = vecAdd(nodeWeights, blockSizes.get(toBlockId));

        Boolean blockSizeConstr = vecLessEq(toBlockSize, blockSizeUpperBound);
        Boolean fixedNodeConstr = true;
        if (fixedNodes.containsKey(nodeId)) {
            fixedNodeConstr = fixedNodes.get(nodeId) == toBlockId;
        }
        return blockSizeConstr && fixedNodeConstr;
    }

    protected boolean isMoveLegal(List<Integer> nodeIds, int toBlockId) {
        assert isBlkIdLegal(toBlockId);
        List<Double> totalMoveSize = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeWeightDim(), 0.0));

        for (int nodeId : nodeIds) {
            assert nodeId < hyperGraph.getNodeNum() && nodeId >= 0;
            if (node2BlockId.get(nodeId) == toBlockId) continue;

            if (fixedNodes.containsKey(nodeId)) {
                if (fixedNodes.get(nodeId) != toBlockId) {
                    return false;
                }
            }
            vecAccu(totalMoveSize, hyperGraph.getWeightsOfNode(nodeId));
        }

        List<Double> toBlockSize = vecAdd(totalMoveSize, blockSizes.get(toBlockId));
        return vecLessEq(toBlockSize, blockSizeUpperBound);
    }

    protected boolean isBlkIdLegal(int blkId) {
        return blkId >= 0 && blkId < config.blockNum;
    }

    // setters
    protected void setPartResult(List<Integer> partRes, boolean checkConstr) {
        assert partRes.size() == hyperGraph.getNodeNum();

        // clear block sizes
        List<Double> zeroBlockSize = Collections.nCopies(hyperGraph.getNodeWeightDim(), 0.0);
        for (int blockId = 0; blockId < config.blockNum; blockId++) {
            blockSizes.set(blockId, new ArrayList<>(zeroBlockSize));
        }

        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            int blockId = partRes.get(nodeId);
            assert isBlkIdLegal(blockId);

            node2BlockId.set(nodeId, blockId);
            vecAccu(blockSizes.get(blockId), hyperGraph.getWeightsOfNode(nodeId));
        }

        if (checkConstr) {
            assert checkSizeConstr(): "Partition results violates block size constraint";
            assert checkFixedNodesConstr(): "Initial partition violates fixed nodes constraint";
        }

        cutSize = hyperGraph.getEdgeWeightsSum(hyperGraph.getCutSize(node2BlockId));
    }

    public void setFixedNodes(Map<Integer, Integer> fixedNodes) {
        this.fixedNodes = fixedNodes;
    }

    public void setFixedNode(Integer nodeId, Integer blockId) {
        assert nodeId >= 0 && nodeId < hyperGraph.getNodeNum();
        assert blockId >= 0 && blockId < config.blockNum;
        fixedNodes.put(nodeId, blockId);
    }

    public void setBlockNum(int blockNum) {
        assert blockNum >= 2;
        this.config.blockNum = blockNum;
        setBlockSizeBound();
        blockSizes = new ArrayList<>();
        for (int blockId = 0; blockId < config.blockNum; blockId++) {
            blockSizes.add(new ArrayList<>(Collections.nCopies(hyperGraph.getNodeWeightDim(), 0.0)));
        }
    }

    public void setRandomSeed(int randomSeed) {
        this.config.randomSeed = randomSeed;
    }

    public void setImbFactors(List<Double> imbFactors) {
        assert imbFactors.size() == hyperGraph.getNodeWeightDim();
        this.config.imbFactors = imbFactors;
        setBlockSizeBound();
    }


    // getters
    public String getStatesInfo() {
        String info = "Partition States:\n";
        info += String.format("  Size of Blocks:\n");
        for (int blockId = 0; blockId < config.blockNum; blockId++) {
            info += String.format("    Block-%d: %s\n", blockId, blockSizes.get(blockId));
        }
        info += String.format("  Cut Size=%.3f", cutSize);
        return info;
    }

    public String getConstrInfo() {
        String info = "Partition Constraints:\n";
        info += String.format("  Block Size Upper Bound: %s\n", blockSizeUpperBound);
        info += String.format("  Block Size Lower Bound: %s\n", blockSizeLowerBound);
        info += String.format("  Total num of fixed nodes: %d\n", fixedNodes.size());

        List<List<Double>> blockSizeOfFixedNodes = new ArrayList<>();
        List<Double> zeroBlockSize = Collections.nCopies(hyperGraph.getNodeWeightDim(), 0.0);
        for (int blockId = 0; blockId < config.blockNum; blockId++) {
            blockSizeOfFixedNodes.add(new ArrayList<>(zeroBlockSize));
        }

        for (int nodeId : fixedNodes.keySet()) {
            int blockId = fixedNodes.get(nodeId);
            List<Double> nodeWeights = hyperGraph.getWeightsOfNode(nodeId);
            vecAccu(blockSizeOfFixedNodes.get(blockId), nodeWeights);
        }
        info += "  Block Sizes of Fixed Nodes:\n";

        for (int blockId = 0; blockId < config.blockNum; blockId++) {
            info += String.format("    Block-%d: %s", blockId, blockSizeOfFixedNodes.get(blockId));
            if (blockId != config.blockNum - 1) {
                info += "\n";
            }
        }
        return info;
    }

    public String getGraphInfo() {
        String info = "HyperGraph Information:\n";
        info += String.format("  NodeNum=%d Total Weight=%s\n", hyperGraph.getNodeNum(), hyperGraph.getTotalNodeWeight());
        info += String.format("  Edge Num=%d Total Weight=%s", hyperGraph.getEdgeNum(), hyperGraph.getTotalEdgeWeight());
        return info;
    }

    public String getPartitionInfo() {
        String info = "Partition Information:\n";
        info += config.toString() + "\n";
        info += getGraphInfo() + "\n";
        info += getConstrInfo() + "\n";
        info += getStatesInfo();
        return info;
    }

    public void printPartitionStates() {
        logger.info(getStatesInfo(), true);
    }
    
    public void printPartitionInfo() {
        logger.info(getPartitionInfo(), true);
    }


    //  vector operations
    protected static List<Double> vecAdd(List<Double> a, List<Double> b) {
        assert a.size() == b.size(): String.format("Size of operand a and b: %d %d", a.size(), b.size());
        List<Double> res = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            res.add(a.get(i) + b.get(i));
        }
        return res;
    }

    protected static void vecAccu(List<Double> a, List<Double> b) {
        assert a.size() == b.size(): String.format("Size of operand a and b: %d %d", a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            a.set(i, a.get(i) + b.get(i));
        }
    }

    protected static List<Double> vecSub(List<Double> a, List<Double> b) {
        assert a.size() == b.size();
        List<Double> res = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            res.add(a.get(i) - b.get(i));
        }
        return res;
    }

    protected static void vecDec(List<Double> a, List<Double> b) {
        assert a.size() == b.size();
        for (int i = 0; i < a.size(); i++) {
            a.set(i, a.get(i) - b.get(i));
        }
    }

    protected static boolean vecLessEq(List<Double> a, List<Double> b) {
        assert a.size() == b.size();
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) > b.get(i)) {
                return false;
            }
        }
        return true;
    }

    protected static boolean vecGreaterEq(List<Double> a, List<Double> b) {
        assert a.size() == b.size();
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) < b.get(i)) {
                return false;
            }
        }
        return true;
    }

    protected static boolean vecEq(List<Double> a, List<Double> b) {
        assert a.size() == b.size();
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    protected static List<Double> vecMulScalar(List<Double> a, Double b) {
        List<Double> res = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            res.add(a.get(i) * b);
        }
        return res;
    }
}
