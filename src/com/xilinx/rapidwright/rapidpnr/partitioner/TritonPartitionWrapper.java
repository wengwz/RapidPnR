package com.xilinx.rapidwright.rapidpnr.partitioner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.HyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.TclCmdFile;

public class TritonPartitionWrapper extends AbstractPartitioner {
    public static class Config extends AbstractConfig {
        public Path workDir;
        public String openroadCmd;

        public Config() {
            workDir = Path.of("triton_part");
        }

        public Config(Path workDir) {
            super();
            this.workDir = workDir;
        }

        public Config(AbstractConfig config, Path workDir) {
            super(config);
            this.workDir = workDir;
        }

        public Config(int blockNum, int seed, List<Double> imbFactors, boolean verbose, Path workDir) {
            super(blockNum, seed, imbFactors, verbose);
            this.workDir = workDir;
        }

    }
    public static String OPENROAD_CMD = "openroad";
    public static String TCL_FILE_NAME = "openroad.tcl";
    public static String GRAPH_FILE_NAME = "input.hgr";
    public static String FIXED_FILE_NAME = "input.fixed";

    private Config config;

    public TritonPartitionWrapper(HierarchicalLogger logger, Config config, HyperGraph hyperGraph) {
        super(logger, config, hyperGraph);
        this.config = config;

        assert config.imbFactors.stream().distinct().count() == 1: "TritonPart only supports uniform imbalance factor";
    }

    public List<Integer> run() {
        logger.info("Start running TritonPart in OpenROAD");

        logger.info(String.format("Input HyperGraph Info:\n%s", hyperGraph.getHyperGraphInfo(true)), true);

        // write input files for TritonPart
        hyperGraph.saveGraphInHmetisFormat(config.workDir.resolve(GRAPH_FILE_NAME));
        writeOpenroadTclCmdFile();
        if (fixedNodes.size() > 0) {
            writeFixedFile();
        }
        // TODO: other input files

        // create job
        JobQueue jobQueue = new JobQueue();
        Job partitionJob = JobQueue.createJob();
        partitionJob.setRunDir(config.workDir.toString());
        partitionJob.setCommand(runCommand());
        jobQueue.addJob(partitionJob);

        // execution
        RuntimeTracker timer = new RuntimeTracker("TritonPart", (short) 0);
        timer.start();
        boolean success = jobQueue.runAllToCompletion();
        timer.stop();
        assert success;

        // read partition results
        List<Integer> partResult = readPartitionResults();
        assert checkAndUpdatePartResult(partResult): "Fail to check and update partition results";

        // print statistics about partitio results
        setPartResult(partResult, false);

        printPartitionInfo();

        logger.info(String.format("Complete running TritonPart in OpenROAD(Time Elapsed: %.2f sec)", timer.getTimeInSec()));

        return partResult;
    }

    private void writeFixedFile() {
        Path filePath = config.workDir.resolve(FIXED_FILE_NAME);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()))) {
            for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
                if (fixedNodes.containsKey(nodeId)) {
                    writer.write(fixedNodes.get(nodeId).toString() + "\n");
                } else {
                    writer.write("-1\n");
                }
            }
        } catch (Exception e) {

        }
    }

    private void writeOpenroadTclCmdFile() {
        Path filePath = config.workDir.resolve(TCL_FILE_NAME);
        TclCmdFile tclCmdFile = new TclCmdFile();

        String partitionCmd = "triton_part_hypergraph";
        partitionCmd += String.format(" -num_parts %d", config.blockNum);
        Double balanceConstr = config.imbFactors.get(0) * 100; // convert to percentage for TritonPart
        partitionCmd += String.format(" -balance_constraint %.2f", balanceConstr);
        partitionCmd += String.format(" -hypergraph_file %s", GRAPH_FILE_NAME);
        partitionCmd += String.format(" -seed %d", config.randomSeed);
        partitionCmd += String.format(" -vertex_dimension %d", hyperGraph.getNodeWeightDim());
        partitionCmd += String.format(" -hyperedge_dimension %d", hyperGraph.getEdgeWeightDim());
        if (fixedNodes.size() > 0) {
            partitionCmd += String.format(" -fixed_file %s", FIXED_FILE_NAME);
        }

        if (hyperGraph.getEdgeWeightDim() > 1) {
            String edgeWeightFac = "";
            for (Double factor : hyperGraph.getEdgeWeightsFactor()) {
                if (edgeWeightFac.length() > 0) {
                    edgeWeightFac += " ";
                }
                edgeWeightFac += String.format("%.2f", factor);
            }
            partitionCmd += String.format(" -e_wt_factors { %s }", edgeWeightFac);
        }

        tclCmdFile.addCmd(partitionCmd);
        tclCmdFile.addCmd("exit");

        tclCmdFile.writeToFile(filePath);
    }

    private List<Integer> readPartitionResults() {
        Path filePath = config.workDir.resolve(GRAPH_FILE_NAME + ".part." + config.blockNum);
        assert filePath.toFile().exists();

        List<Integer> blockIds = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                blockIds.add(Integer.parseInt(line.trim()));
            }
        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }

        return blockIds;
    }

    private String dockerRunCommand() {
        String imageName = "crpi-vxps6h1znknsd4n1.cn-hangzhou.personal.cr.aliyuncs.com/wengwz/openroad:dev";
        String dockerCommand = "docker";
        String dockerOptions = "run --rm -v `pwd`:/workspace -w /workspace";
        String dockerOperation = OPENROAD_CMD + " " + TCL_FILE_NAME;
        return dockerCommand + " " + dockerOptions + " " + imageName + " " + dockerOperation;
    }

    private String runCommand() {
        if (config.openroadCmd != null) {
            return config.openroadCmd + " " + TCL_FILE_NAME;
        } else {
            return dockerRunCommand();
        }
    }

    private boolean checkAndUpdatePartResult(List<Integer> partResult) {
        // check fixed nodes constraints and update partition results
        logger.info("Start checking fixed nodes constraints and update results");
        assert partResult.size() == hyperGraph.getNodeNum();

        if (config.blockNum > 2 || fixedNodes.size() == 0) {
            return true; // only support checking 2-way partition with fixed nodes
        }

        Boolean isPartResultFlip = null;
        for (int nodeId : fixedNodes.keySet()) {
            int fixedBlkId = fixedNodes.get(nodeId);
            int actualBlkId = partResult.get(nodeId);
            if (isPartResultFlip == null) {
                isPartResultFlip = fixedBlkId != actualBlkId;
            }

            if (isPartResultFlip != (fixedBlkId != actualBlkId)) {
                return false;
            }
        }

        if (isPartResultFlip) {
            logger.info("Flip partition results due to fixed nodes constraints");
            for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
                int blkId = partResult.get(nodeId);
                partResult.set(nodeId, blkId == 0 ? 1 : 0);
            }
        }
    
        logger.info("Complete checking fixed nodes constraints and update results");
        return true;
    }

    public static void main(String[] args) {
        Path inputGraphPath = Path.of("workspace/test/nvdla-tpw-cls.hgr").toAbsolutePath();
        //Path inputGraphPath = Path.of("workspace/test/blue-rdma-cls.hgr").toAbsolutePath();
        HierarchicalLogger logger = HierarchicalLogger.createLogger("TestTritonPart", null, true);

        List<Double> weightFac = Arrays.asList(1.0);
        HyperGraph hyperGraph = HyperGraph.readGraphFromHmetisFormat(inputGraphPath, weightFac, weightFac);

        Path workDir = Path.of("workspace/test").toAbsolutePath();
        Config config = new Config(2, 999, Arrays.asList(0.01), true, workDir);
        TritonPartitionWrapper partitioner = new TritonPartitionWrapper(logger, config, hyperGraph);
        partitioner.run();
    }
}
