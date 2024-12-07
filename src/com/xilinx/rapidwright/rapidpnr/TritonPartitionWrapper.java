package com.xilinx.rapidwright.rapidpnr;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.logging.ConsoleHandler;

import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.RuntimeTracker;

public class TritonPartitionWrapper {
    public static String OPENROAD_CMD = "openroad";
    public static String TCL_FILE_NAME = "openroad.tcl";
    public static String GRAPH_FILE_NAME = "input.hgr";
    public static String FIXED_FILE_NAME = "input.fixed";

    private HierarchicalLogger logger;
    private HyperGraph hyperGraph;
    private Path workDir;

    private int blockNum = 2;
    private int randomSeed = 999;
    private double balanceConstr = 1.0;
    private Map<Integer, Integer> fixedNodes = null;


    public TritonPartitionWrapper(HierarchicalLogger logger, HyperGraph hyperGraph, Path workDir) {
        this.logger = logger;
        this.hyperGraph = hyperGraph;
        this.workDir = workDir;
    }

    public void setFixedNodes(Map<Integer, Integer> fixedNodes) {
        this.fixedNodes = fixedNodes;
    }

    public void setBlockNum(int blockNum) {
        assert blockNum >= 2;
        this.blockNum = blockNum;
    }

    public void setRandomSeed(int randomSeed) {
        this.randomSeed = randomSeed;
    }

    public void setBalanceConstr(double balanceConstr) {
        assert balanceConstr >= 0.0 && balanceConstr <= 1.0;
        this.balanceConstr = balanceConstr * 100;
    }

    public List<Integer> run() {
        logger.info("Start running TritonPart in OpenROAD");

        // write input files for TritonPart
        hyperGraph.saveGraphInHmetisFormat(workDir.resolve(GRAPH_FILE_NAME));
        writeOpenroadTclCmdFile();
        if (fixedNodes != null) {
            writeFixedFile();
        }
        // TODO: other input files

        // create job
        JobQueue jobQueue = new JobQueue();
        Job partitionJob = JobQueue.createJob();
        partitionJob.setRunDir(workDir.toString());
        partitionJob.setCommand(dockerRunCommand());
        jobQueue.addJob(partitionJob);

        // execution
        RuntimeTracker timer = new RuntimeTracker("TritonPart", (short) 0);
        timer.start();
        boolean success = jobQueue.runAllToCompletion();
        timer.stop();
        assert success;

        // read partition results
        List<Integer> partResult = readPartitionResults();

        // print statistics about tpartitio results
        logger.info(String.format("Complete running TritonPart in OpenROAD(Time Elapsed: %.2f sec)", timer.getTimeInSec()));
        printPartitionResults(partResult);

        return partResult;
    }

    private void writeFixedFile() {
        Path filePath = workDir.resolve(FIXED_FILE_NAME);
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
        Path filePath = workDir.resolve(TCL_FILE_NAME);
        TclCmdFile tclCmdFile = new TclCmdFile();

        String partitionCmd = "triton_part_hypergraph";
        partitionCmd += String.format(" -num_parts %d", blockNum);
        partitionCmd += String.format(" -balance_constraint %.2f", balanceConstr);
        partitionCmd += String.format(" -hypergraph_file %s", GRAPH_FILE_NAME);
        partitionCmd += String.format(" -seed %d", randomSeed);
        partitionCmd += String.format(" -vertex_dimension %d", hyperGraph.getNodeWeightDim());
        partitionCmd += String.format(" -hyperedge_dimension %d", hyperGraph.getEdgeWeightDim());
        if (fixedNodes != null) {
            partitionCmd += String.format(" -fixed_file %s", FIXED_FILE_NAME);
        }

        tclCmdFile.addCmd(partitionCmd);
        tclCmdFile.addCmd("exit");

        tclCmdFile.writeToFile(filePath);
    }

    private List<Integer> readPartitionResults() {
        Path filePath = workDir.resolve(GRAPH_FILE_NAME + ".part." + blockNum);
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

    private void printPartitionResults(List<Integer> partResult) {
        List<Double> cutSize = hyperGraph.getCutSize(partResult);
        List<List<Double>> blockSizes = hyperGraph.getBlockSize(partResult);

        logger.info("Cut size: " + cutSize.toString());
        for (int i = 0; i < blockSizes.size(); i++) {
            logger.info("Size of block-" + i + ": " + blockSizes.get(i).toString());
        }
    }

    public static void main(String[] args) {
        HierarchicalLogger logger = new HierarchicalLogger("TestTritonPart");
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new CustomFormatter());
        logger.addHandler(consoleHandler);

        HyperGraph hyperGraph = new HyperGraph(Arrays.asList(1.0), Arrays.asList(1.0));

        hyperGraph.addNode(Arrays.asList(1.0));
        hyperGraph.addNode(Arrays.asList(1.0));
        hyperGraph.addNode(Arrays.asList(1.0));
        hyperGraph.addNode(Arrays.asList(1.0));
        hyperGraph.addNode(Arrays.asList(1.0));

        hyperGraph.addEdge(Set.of(2, 4), Arrays.asList(1.0));
        hyperGraph.addEdge(Set.of(0, 1), Arrays.asList(1.0));
        hyperGraph.addEdge(Set.of(1, 2, 4), Arrays.asList(1.0));
        hyperGraph.addEdge(Set.of(3, 4), Arrays.asList(1.0));

        Path workDir = Path.of("workspace/tmp").toAbsolutePath();
        TritonPartitionWrapper partitioner = new TritonPartitionWrapper(logger, hyperGraph, workDir);
        partitioner.run();

        //TritonPartitionWrapper partitioner = new T
    }
}
