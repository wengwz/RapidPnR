package com.xilinx.rapidwright.rapidpnr.partitioner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.xilinx.rapidwright.rapidpnr.utils.HierHyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.HyperGraph;
public class MultiLevelPartitioner extends AbstractPartitioner{

    public static class Config extends AbstractConfig {
        // coarse configuration
        public Coarser.Config coarserConfig;
        public int coarsenStopNodeNum = 100;

        // refiner configuartion
        public int fmMaxPassNum = 3;
        public double fmPassEarlyExitRatio = 0.25;
        public double fmExtremeLargeRatio = 0.2;
        public int randRefineNodeNum = 10000;
        public int refineEdgeNum = 100000;

        //
        public int parallelRunNum = 1;
        public boolean vCycleRefine = false;
        public double vCycleUncoarseLevelRatio = 0.5;

        @Override
        public String toString() {
            return super.toString() + "\n" + coarserConfig.toString();
        }

        public Config() {
            coarserConfig = new Coarser.Config();
        }

        public Config(Config config) {
            super(config);
            coarserConfig = new Coarser.Config(config.coarserConfig);
            coarsenStopNodeNum = config.coarsenStopNodeNum;
            fmMaxPassNum = config.fmMaxPassNum;
            fmPassEarlyExitRatio = config.fmPassEarlyExitRatio;
            fmExtremeLargeRatio = config.fmExtremeLargeRatio;
            parallelRunNum = config.parallelRunNum;
            vCycleRefine = config.vCycleRefine;
            vCycleUncoarseLevelRatio = config.vCycleUncoarseLevelRatio;
        }
    }

    // FM-based partition refinement
    public class FMRefiner extends FMPartitioner {
        public FMRefiner(HierarchicalLogger logger, Config config, HyperGraph hyperGraph) {
            super(logger, config, hyperGraph);
        }

        public List<Integer> run(List<Integer> initialPartRes, int refineEdgeNum, int randRefineNodeNum) {
            logger.info("Start partition refinement");
            logger.newSubStep();

            setPartResult(initialPartRes, true);
            double initialCutSize = cutSize;

            if (hyperGraph.getEdgeNum() < refineEdgeNum) {
                edgeBasedRefinement();
            }

            if (hyperGraph.getNodeNum() < randRefineNodeNum) {
                vertexBasedRefine();
            } else {
                randomVertexBasedRefinement();
            }

            printPartitionInfo();

            double finalCutSize = cutSize;
            double cutSizeReduction = initialCutSize - finalCutSize;

            logger.endSubStep();
            logger.info("Complete partition refinement with cut size reduction of " + cutSizeReduction);

            return node2BlockId;
        }

        @Override 
        public List<Integer> run() {
            assert false: "Partition refinement should start with initial partition";
            return node2BlockId;
        }
    }

    public static class PartitionThread extends Thread {
        public MultiLevelPartitioner partitioner;

        public PartitionThread(Config config, HyperGraph hyperGraph) {
            HierarchicalLogger dummyLogger = HierarchicalLogger.createPseduoLogger("partition");
            partitioner = new MultiLevelPartitioner(dummyLogger, config, hyperGraph);
        }

        @Override
        public void run() {
            partitioner.run();
        }
    }

    // configuration parameters
    Config config;
    HierHyperGraph originHierGraph;

    
    public MultiLevelPartitioner(HierarchicalLogger logger, Config config, HyperGraph hyperGraph) {
        super(logger, config, hyperGraph);
        this.config = config;
        this.originHierGraph = HierHyperGraph.convertToHierHyperGraph(hyperGraph);
    }

    public List<Integer> run() {
        logger.info("Start multi-level partitioning");
        logger.newSubStep();

        originHierGraph.setFixedNodes(this.fixedNodes);

        List<Integer> partResult;
        if (config.parallelRunNum > 1) {
            partResult = parallelRun(config.parallelRunNum);
        } else {
            partResult = singleRun();
        }

        setPartResult(partResult, true);
        printPartitionInfo();

        if (config.vCycleRefine) {
            partResult = multiPhaseRefinement(originHierGraph, partResult);
            setPartResult(partResult, true);
            printPartitionInfo();
        }

        logger.endSubStep();
        logger.info("Complete multi-level partitioning");
        return Collections.unmodifiableList(node2BlockId);
    }

    protected List<Integer> singleRun() {
        logger.info("Start initial multi-level partitioning");
        logger.newSubStep();

        // coarsening phase
        HierHyperGraph coarsestHierGraph = coarsen(originHierGraph);

        // initial partitioning
        List<Integer> initialPartRes = initialPartition(coarsestHierGraph);

        // uncoarsening and refinement
        List<Integer> finalPartRes = uncoarsenAndRefine(coarsestHierGraph, initialPartRes);

        logger.endSubStep();
        logger.info("Complete initial multi-level partitioning");
        return finalPartRes;
    }

    protected List<Integer> parallelRun(int parallelNum) {
        logger.info("Start parallel initial multi-level partitioning");
        logger.newSubStep();

        List<PartitionThread> partitionThreads = new ArrayList<>();
        Random random = new Random(config.randomSeed);

        for (int id = 0; id < parallelNum; id++) {
            logger.info("Launch partition thread " + id);
            Config newConfig = new Config(config);

            // disable v-cycle refinement for parallel runs
            newConfig.vCycleRefine = false;
            // set single run
            newConfig.parallelRunNum = 1;
            // reset random seed for each thread
            newConfig.randomSeed = random.nextInt();
            newConfig.coarserConfig.seed = newConfig.randomSeed;

            PartitionThread thread = new PartitionThread(newConfig, hyperGraph);

            thread.start();
            partitionThreads.add(thread);
        }

        List<Boolean> threadComplete = new ArrayList<>(Collections.nCopies(parallelNum, false));
        int completeThreadNum = 0;
        Double minCutSize = Double.MAX_VALUE;
        int minCutThreadId = -1;

        while (completeThreadNum < parallelNum) {
            for (int id = 0; id < parallelNum; id++) {
                if (threadComplete.get(id)) continue;
                PartitionThread thread = partitionThreads.get(id);
                if (thread.isAlive()) continue;

                threadComplete.set(id, true);
                completeThreadNum++;

                Boolean isFail = thread.partitioner.node2BlockId.stream().anyMatch(blockId -> blockId < 0);
                if (isFail) {
                    logger.info(String.format("Partition thread %d failed", id));
                    continue;
                }

                Double cutSize = thread.partitioner.cutSize;
                int seed = thread.partitioner.config.randomSeed;
                logger.info(String.format("Partition thread %d completes successfully with cutSize=%.2f seed=%d", id, cutSize, seed));
                if (cutSize < minCutSize) {
                    minCutSize = cutSize;
                    minCutThreadId = id;
                }
            }            
        }

        logger.info(String.format("Find best partition from thread %d with cut size %.2f", minCutThreadId, minCutSize));

        List<Integer> bestPartResult = partitionThreads.get(minCutThreadId).partitioner.node2BlockId;
        // setPartResult(bestPartResult, true);
        // printPartitionInfo();

        logger.endSubStep();
        logger.info("Complete parallel initial multi-level partitioning");
        return bestPartResult;
    }

    protected HierHyperGraph coarsen(HierHyperGraph initGraph) {
        logger.info("Start coarsening phase");
        logger.newSubStep();

        int coarseLevel = 0;
        HierHyperGraph curGraph = initGraph;
        Coarser.Config coarserConfig = new Coarser.Config(config.coarserConfig);
        coarserConfig.seed = config.randomSeed;

        logger.info("Original Hypergraph: \n" + curGraph.getHyperGraphInfo(true), true);

        while(curGraph.getNodeNum() > config.coarsenStopNodeNum) {
            logger.info(String.format("The level of coarsening %d", coarseLevel));

            int originNodeNum = curGraph.getNodeNum();
            coarserConfig.dontTouchNodes.addAll(curGraph.getFixedNodes().keySet());
            curGraph = Coarser.coarsening(coarserConfig, curGraph);
            int newNodeNum = curGraph.getNodeNum();

            logger.info("Coarsened Hypergraph: \n" + curGraph.getHyperGraphInfo(false), true);
            
            coarseLevel++;
            coarserConfig.seed++; // modify random seed for next coarsening

            if (originNodeNum == newNodeNum) {
                logger.info("Coarsening aborta due to no reduction in node amount");
                break;
            }
        }

        logger.info("Coarsest Hypergraph:\n" + curGraph.getHyperGraphInfo(true), true);

        logger.endSubStep();
        logger.info(String.format("Complete coarsening phase with %d levels of hypergraphs", coarseLevel + 1));
        return curGraph;
    }

    protected List<Integer> initialPartition(HierHyperGraph hyperGraph) {
        logger.info("Start initial partition of coarsest hypergraph");

        logger.newSubStep();
        // FM-based initial partitioning
        FMPartitioner.Config fmPartConfig = new FMPartitioner.Config(config);
        FMPartitioner fmPartitioner = new FMPartitioner(logger, fmPartConfig, hyperGraph);
        fmPartitioner.setFixedNodes(hyperGraph.getFixedNodes());

        List<Integer> partResults = fmPartitioner.run();

        logger.endSubStep();
        logger.info("Complete initial partition of coarsest hypergraph");
        return partResults;
    }

    protected List<Integer> uncoarsenAndRefine(HierHyperGraph coarsestGraph, List<Integer> initPart) {
        logger.info("Start uncoarsening and refinement");
        HierHyperGraph curHyperGraph = coarsestGraph;
        List<Integer> curPartResult = initPart;

        logger.newSubStep();
        FMPartitioner.Config refinerConfig = new FMPartitioner.Config(config, config.fmMaxPassNum, config.fmPassEarlyExitRatio, config.fmExtremeLargeRatio);
        refinerConfig.verbose = false;
        int iterCount = 0;
        while (!curHyperGraph.isRootGraph()) {
            logger.info(String.format("Start iter-%d of uncoarsening and refinement", iterCount));

            logger.newSubStep();

            // get initial partition result of parent graph
            List<Integer> parentPartResult = curHyperGraph.getPartResultOfParent(curPartResult);

            // get parent graph
            HierHyperGraph parentGraph = curHyperGraph.getParentGraph();
            logger.info("Current Graph Info:");
            logger.info(parentGraph.getHyperGraphInfo(false), true);

            // refine partition
            FMRefiner refiner = new FMRefiner(logger, refinerConfig, parentGraph);
            refiner.setFixedNodes(parentGraph.getFixedNodes());
            
            curPartResult = refiner.run(parentPartResult, config.refineEdgeNum, config.randRefineNodeNum);
            curHyperGraph = parentGraph;

            logger.endSubStep();
            iterCount++;
            refinerConfig.randomSeed++;
        }
        logger.endSubStep();

        logger.info("Complete uncoarsening and refinement");

        return curPartResult;
    }

    protected List<Integer> multiPhaseRefinement(HierHyperGraph initGraph, List<Integer> initPartRes) { // V-Cycle Refinement
        logger.info("Start multi-phase (V-Cycle) refinement");
        logger.newSubStep();

        class VCycleIter {
            public HierHyperGraph curGraph;
            public List<Integer> curPartRes;

            public VCycleIter(HierHyperGraph initGraph, List<Integer> initPartRes) {
                curGraph = initGraph;
                curPartRes = initPartRes;
            }

            public void restrictedCoarsen(int seed) {
                logger.info("Start restricted coarsening");
                logger.newSubStep();

                Coarser.Config coarseConfig = new Coarser.Config(config.coarserConfig);
                coarseConfig.seed = seed;
                coarseConfig.partResult = curPartRes;
                int coarseLevel = 0;

                while (curGraph.getNodeNum() > config.coarsenStopNodeNum) {
                    logger.info(String.format("Coarse Level %d: ", coarseLevel));
                    curGraph = Coarser.coarsening(coarseConfig, curGraph);
                    curPartRes = curGraph.getPartResultFromParent(curPartRes);
                    logger.info("Coarse HyperGraph Info: \n" + curGraph.getHyperGraphInfo(false), true);
                    
                    coarseLevel++;
                    coarseConfig.seed++;
                    coarseConfig.partResult = curPartRes;
                }

                logger.endSubStep();
                logger.info("Complete restricted coarsening");
            }

            public double uncoarsenAndRefine(int stopLevel, int seed) { // return cut size gain
                logger.info("Start uncoarsening and refinement");
                logger.newSubStep();
                FMPartitioner.Config refinerConfig = new FMPartitioner.Config(config, config.fmMaxPassNum, config.fmPassEarlyExitRatio, config.fmExtremeLargeRatio);
                refinerConfig.verbose = false;
                refinerConfig.randomSeed = seed;

                double initialCutSize = curGraph.getEdgeWeightsSum(curGraph.getCutSize(curPartRes));

                while (curGraph.getHierarchicalLevel() > stopLevel) {
                    logger.info("Start refining coarse graph of level " + curGraph.getHierarchicalLevel());
                    curPartRes = curGraph.getPartResultOfParent(curPartRes);
                    curGraph = curGraph.getParentGraph();

                    FMRefiner refiner = new FMRefiner(logger, refinerConfig, curGraph);
                    curPartRes = refiner.run(curPartRes);
                }

                double finalCutSize = curGraph.getEdgeWeightsSum(curGraph.getCutSize(curPartRes));
                logger.endSubStep();
                logger.info("Complete uncoarsening and refinement");
                return initialCutSize - finalCutSize;
            }
        }

        Random random = new Random(config.randomSeed);
        VCycleIter vCycleIter = new VCycleIter(initGraph, initPartRes);

        logger.info("Start initial restricted coarsening:");
        vCycleIter.restrictedCoarsen(random.nextInt());

        int coarsestLevel = vCycleIter.curGraph.getHierarchicalLevel();
        int vCycleUncoarseStopLevel = (int) (coarsestLevel * (1.0 - config.vCycleUncoarseLevelRatio));

        double totalVCycleGain = 0;
        int vCycleCount = 0;
        while (true) {
            logger.info("Start V-Cycle Iteration " + vCycleCount);
            double cycleGain = vCycleIter.uncoarsenAndRefine(vCycleUncoarseStopLevel, random.nextInt());
            totalVCycleGain += cycleGain;

            if (cycleGain <= 0) {
                break;
            }

            vCycleIter.restrictedCoarsen(random.nextInt());
            logger.info("Complete V-Cycle Iteration " + vCycleCount);
            vCycleCount++;
        }

        logger.info("Start final uncoarsening and refinement:");
        vCycleIter.uncoarsenAndRefine(0, random.nextInt());


        logger.endSubStep();
        logger.info("Complete multi-phase (V-Cycle) refinement with Gain=" + totalVCycleGain);
        return vCycleIter.curPartRes;
    }

    public static void main(String[] args) {
        Path inputGraphPath = Path.of("workspace/test/nvdla-tpw-cls.hgr").toAbsolutePath();
        // Path inputGraphPath = Path.of("workspace/test/blue-rdma-cls.hgr").toAbsolutePath();
        HierarchicalLogger logger = HierarchicalLogger.createLogger("TestMultiLevelPart", null, true);

        List<Double> weightFac = Arrays.asList(1.0);
        HyperGraph hyperGraph = HyperGraph.readGraphFromHmetisFormat(inputGraphPath, weightFac, weightFac);

        Config config = new Config();
        config.randomSeed = 999;
        config.parallelRunNum = 20;
        config.vCycleRefine = false;
        config.vCycleUncoarseLevelRatio = 0.5;
        config.coarserConfig.maxNodeSizeRatio = 0.4;

        MultiLevelPartitioner partitioner = new MultiLevelPartitioner(logger, config, hyperGraph);
        partitioner.run();

        assert partitioner.checkPartitionStates();

        //assert partitioner.checkPartitionStates(): "Partition states are not consistent";
    }

}
