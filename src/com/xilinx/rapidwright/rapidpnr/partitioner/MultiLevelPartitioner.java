package com.xilinx.rapidwright.rapidpnr.partitioner;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import com.xilinx.rapidwright.rapidpnr.utils.HierHyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.HyperGraph;

public class MultiLevelPartitioner extends AbstractPartitioner{

    // configuration parameters
    int coarsenStopNodeNum = 100;
    Coarser.Config coarserConfig = new Coarser.Config(Coarser.Scheme.FC, config.randomSeed, 2);

    HierHyperGraph originHierGraph;

    public class PartitionRefiner extends FMPartitioner {
        public PartitionRefiner(HierarchicalLogger logger, Config config, HyperGraph hyperGraph) {
            super(logger, config, hyperGraph);
            setEarlyExitMoveNum(0.2);
            setMaxPassNum(2);
        }

        @Override
        public List<Integer> run(List<Integer> initialPartRes) {
            logger.info("Start partition refinement");
            logger.newSubStep();

            setPartResult(initialPartRes);

            setupNode2MoveGain(node2BlockId);

            edgeBasedRefinement();

            vertexBasedRefine();

            logger.endSubStep();
            logger.info("Complete partition refinement");

            return node2BlockId;
        }

        @Override 
        public List<Integer> run() {
            assert false: "Partition refinement should start with initial partition";
            return node2BlockId;
        }
    }

    
    public MultiLevelPartitioner(HierarchicalLogger logger, Config config, HyperGraph hyperGraph) {
        super(logger, config, hyperGraph);
    }

    public List<Integer> run() {
        logger.info("Start multi-level partitioning");
        logger.newSubStep();

        originHierGraph = HierHyperGraph.convertToHierHyperGraph(hyperGraph);

        // coarsening phase
        HierHyperGraph coarsestHierGraph = coarsen(coarsenStopNodeNum);

        // initial partitioning
        List<Integer> initialPartRes = initialPartition(coarsestHierGraph);

        // uncoarsening and refinement
        List<Integer> finalPartRes = uncoarsenAndRefine(coarsestHierGraph, initialPartRes);

        setPartResult(finalPartRes);

        printPartitionInfo();

        logger.endSubStep();
        logger.info("Complete multi-level partitioning");
        return node2BlockId;
    }

    public HierHyperGraph coarsen(int stopNodeNum) {
        logger.info("Start coarsening phase");
        logger.newSubStep();

        HierHyperGraph curGraph = originHierGraph;

        int coarseLevel = 0;
        logger.info("Original Hypergraph: \n" + curGraph.getHyperGraphInfo(true), true);

        while(curGraph.getNodeNum() > stopNodeNum) {
            logger.info(String.format("The level of coarsening %d", coarseLevel));
            logger.info("Build coarsened hypergraph");
            coarserConfig.seed = config.randomSeed + coarseLevel;
            curGraph = Coarser.coarsening(coarserConfig, curGraph, new HashSet<>());
            logger.info("Coarsened Hypergraph: \n" + curGraph.getHyperGraphInfo(false), true);
            coarseLevel++;
        }

        logger.info("Coarsest Hypergraph:\n" + curGraph.getHyperGraphInfo(true), true);

        logger.endSubStep();
        logger.info(String.format("Complete coarsening phase with %d levels of hypergraphs", coarseLevel + 1));
        return curGraph;
    }

    public List<Integer> initialPartition(HierHyperGraph hyperGraph) {
        logger.info("Start initial partition of coarsest hypergraph");
        // FM-based initial partitioning
        FMPartitioner fmPartitioner = new FMPartitioner(logger, config, hyperGraph);
        List<Integer> partResults = fmPartitioner.run();

        logger.info("Complete initial partition of coarsest hypergraph");
        return partResults;
    }

    public List<Integer> uncoarsenAndRefine(HierHyperGraph coarsestGraph, List<Integer> initPart) {
        logger.info("Start uncoarsening and refinement");
        HierHyperGraph curHyperGraph = coarsestGraph;
        List<Integer> curPartResult = initPart;
        logger.newSubStep();

        int iterCount = 0;
        while (!curHyperGraph.isRootGraph()) {
            logger.info(String.format("Start iter-%d of uncoarsening and refinement", iterCount));

            // get initial partition result of parent graph
            List<Integer> parentPartResult = curHyperGraph.getPartResultOfParent(curPartResult);

            // get parent graph
            HierHyperGraph parentGraph = curHyperGraph.getParentGraph();

            // refine partition
            PartitionRefiner refiner = new PartitionRefiner(logger, config, parentGraph);
            
            curPartResult = refiner.run(parentPartResult);
            curHyperGraph = parentGraph;

            iterCount++;
        }

        logger.endSubStep();
        logger.info("Complete uncoarsening and refinement");

        return curPartResult;
    }

    public static void main(String[] args) {
        Path inputGraphPath = Path.of("workspace/test/nvdla-tpw-cls.hgr").toAbsolutePath();
        HierarchicalLogger logger = HierarchicalLogger.createLogger("TestMultiLevelPart", null, true);

        List<Double> weightFac = Arrays.asList(1.0);
        HyperGraph hyperGraph = HyperGraph.readGraphFromHmetisFormat(inputGraphPath, weightFac, weightFac);

        Path workDir = Path.of("workspace/test").toAbsolutePath();
        Config config = new Config(2, 99999, Arrays.asList(0.01), workDir);
        MultiLevelPartitioner partitioner = new MultiLevelPartitioner(logger, config, hyperGraph);
        partitioner.run();

        assert partitioner.checkPartitionStates();

        //assert partitioner.checkPartitionStates(): "Partition states are not consistent";
    }


}
