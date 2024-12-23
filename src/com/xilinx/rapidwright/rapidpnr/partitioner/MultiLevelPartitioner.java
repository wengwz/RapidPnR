package com.xilinx.rapidwright.rapidpnr.partitioner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import com.xilinx.rapidwright.rapidpnr.utils.HierHyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.HyperGraph;
public class MultiLevelPartitioner extends AbstractPartitioner{

    public static class Config extends AbstractConfig {
        // coarse configuration
        public Coarser.Config coarserConfig;
        public int coarsenStopNodeNum = 80;

        // fm refiner configuartion
        int maxPassNum = 3;
        double passEarlyExitRatio = 0.25;
        double extremeLargeRatio = 0.1;

        @Override
        public String toString() {
            return super.toString() + "\n" + coarserConfig.toString();
        }

        public Config() {
            coarserConfig = new Coarser.Config(this.randomSeed);
        }

        public Config(Config config) {
            super(config);
            coarserConfig = new Coarser.Config(config.coarserConfig);
            coarsenStopNodeNum = config.coarsenStopNodeNum;
            maxPassNum = config.maxPassNum;
            passEarlyExitRatio = config.passEarlyExitRatio;
            extremeLargeRatio = config.extremeLargeRatio;
        }
    }

    // FM-based partition refinement
    public class FMRefiner extends FMPartitioner {
        public FMRefiner(HierarchicalLogger logger, Config config, HyperGraph hyperGraph) {
            super(logger, config, hyperGraph);
        }

        @Override
        public List<Integer> run(List<Integer> initialPartRes) {
            logger.info("Start partition refinement");
            logger.newSubStep();

            setPartResult(initialPartRes, true);
            double initialCutSize = cutSize;

            setupNode2MoveGain(node2BlockId);

            edgeBasedRefinement();

            vertexBasedRefine();

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

        // coarsening phase
        HierHyperGraph coarsestHierGraph = coarsen();

        // initial partitioning
        List<Integer> initialPartRes = initialPartition(coarsestHierGraph);

        // uncoarsening and refinement
        List<Integer> finalPartRes = uncoarsenAndRefine(coarsestHierGraph, initialPartRes);

        setPartResult(finalPartRes, true);

        printPartitionInfo();

        logger.endSubStep();
        logger.info("Complete multi-level partitioning");
        return Collections.unmodifiableList(node2BlockId);
    }

    public List<Integer> parallelRun(int parallelNum) {
        logger.info("Start parallel multi-level partitioning");
        logger.newSubStep();

        List<PartitionThread> partitionThreads = new ArrayList<>();
        Random random = new Random(config.randomSeed);

        for (int id = 0; id < parallelNum; id++) {
            logger.info("Launch partition thread " + id);
            Config newConfig = new Config(config);
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

                Double cutSize = thread.partitioner.cutSize;
                logger.info(String.format("Partition thread %d complete with cutSize=%.2f", id, cutSize));

                threadComplete.set(id, true);
                if (cutSize < minCutSize) {
                    minCutSize = cutSize;
                    minCutThreadId = id;
                }
                completeThreadNum++;
            }            
        }

        logger.info(String.format("Find best partition from thread %d with cut size %.2f", minCutThreadId, minCutSize));

        List<Integer> bestPartResult = partitionThreads.get(minCutThreadId).partitioner.node2BlockId;
        setPartResult(bestPartResult, true);
        printPartitionInfo();

        logger.endSubStep();
        logger.info("Complete parallel multi-level partitioning");
        return Collections.unmodifiableList(node2BlockId);
    }

    public HierHyperGraph coarsen() {
        logger.info("Start coarsening phase");
        logger.newSubStep();

        int coarseLevel = 0;
        HierHyperGraph curGraph = originHierGraph;
        Coarser.Config coarserConfig = new Coarser.Config(config.coarserConfig);
        logger.info("Original Hypergraph: \n" + curGraph.getHyperGraphInfo(true), true);

        while(curGraph.getNodeNum() > config.coarsenStopNodeNum) {
            logger.info(String.format("The level of coarsening %d", coarseLevel));

            curGraph = Coarser.coarsening(coarserConfig, curGraph, new HashSet<>());
            logger.info("Coarsened Hypergraph: \n" + curGraph.getHyperGraphInfo(false), true);
            
            coarseLevel++;
            coarserConfig.seed ++; // modify random seed for next coarsening
        }

        logger.info("Coarsest Hypergraph:\n" + curGraph.getHyperGraphInfo(true), true);

        logger.endSubStep();
        logger.info(String.format("Complete coarsening phase with %d levels of hypergraphs", coarseLevel + 1));
        return curGraph;
    }

    public List<Integer> initialPartition(HierHyperGraph hyperGraph) {
        logger.info("Start initial partition of coarsest hypergraph");

        logger.newSubStep();
        // FM-based initial partitioning
        FMPartitioner.Config fmPartConfig = new FMPartitioner.Config(config);
        FMPartitioner fmPartitioner = new FMPartitioner(logger, fmPartConfig, hyperGraph);

        List<Integer> partResults = fmPartitioner.run();

        logger.endSubStep();
        logger.info("Complete initial partition of coarsest hypergraph");
        return partResults;
    }

    public List<Integer> uncoarsenAndRefine(HierHyperGraph coarsestGraph, List<Integer> initPart) {
        logger.info("Start uncoarsening and refinement");
        HierHyperGraph curHyperGraph = coarsestGraph;
        List<Integer> curPartResult = initPart;

        logger.newSubStep();
        FMPartitioner.Config refinerConfig = new FMPartitioner.Config(config, config.maxPassNum, config.passEarlyExitRatio, config.extremeLargeRatio);
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
            
            curPartResult = refiner.run(parentPartResult);
            curHyperGraph = parentGraph;

            logger.endSubStep();
            iterCount++;
        }
        logger.endSubStep();

        logger.info("Complete uncoarsening and refinement");

        return curPartResult;
    }

    public static void main(String[] args) {
        Path inputGraphPath = Path.of("workspace/test/nvdla-tpw-cls.hgr").toAbsolutePath();
        // Path inputGraphPath = Path.of("workspace/test/blue-rdma-cls.hgr").toAbsolutePath();
        HierarchicalLogger logger = HierarchicalLogger.createLogger("TestMultiLevelPart", null, true);

        List<Double> weightFac = Arrays.asList(1.0);
        HyperGraph hyperGraph = HyperGraph.readGraphFromHmetisFormat(inputGraphPath, weightFac, weightFac);

        Config config = new Config();
        config.randomSeed = 10000;
        config.coarserConfig.maxNodeSizeRatio = 0.4;
        MultiLevelPartitioner partitioner = new MultiLevelPartitioner(logger, config, hyperGraph);
        partitioner.parallelRun(20);
        //partitioner.run();

        assert partitioner.checkPartitionStates();

        //assert partitioner.checkPartitionStates(): "Partition states are not consistent";
    }

}
