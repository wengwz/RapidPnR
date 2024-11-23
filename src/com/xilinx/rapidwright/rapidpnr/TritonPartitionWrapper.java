package com.xilinx.rapidwright.rapidpnr;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.ConsoleHandler;

import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.RuntimeTracker;

public class TritonPartitionWrapper {
    private final int BLOCK_NUM = 2;
    private final double BALANCE_CONSTR = 1.0;
    private final int RAND_SEED = 10;
    private final String OPENROAD_CMD = "openroad";
    private final String TCL_FILE_NAME = "openroad.tcl";
    private final String GRAPH_FILE_NAME = "input.hgr";

    private HierarchicalLogger logger;
    private HyperGraph hyperGraph;
    private Path workDir;

    private int blockNum;
    private Double balanceConstr;


    public TritonPartitionWrapper(HierarchicalLogger logger, HyperGraph hyperGraph, Path workDir) {
        this.logger = logger;
        this.hyperGraph = hyperGraph;
        this.workDir = workDir;
    }

    public List<Integer> run() {
        return run(BALANCE_CONSTR, BLOCK_NUM);
    }

    public List<Integer> run(int blockNum) {
        return run(BALANCE_CONSTR, blockNum);
    }

    public List<Integer> run(Double balanceConstr, int blockNum) {
        logger.info("Start running TritonPart in OpenROAD");
        this.balanceConstr = balanceConstr;
        this.blockNum = blockNum;

        // write input files for TritonPart
        hyperGraph.saveGraphInHmetisFormat(workDir.resolve(GRAPH_FILE_NAME));
        writeOpenroadTclCmdFile();
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
        logger.info("Complete running TritonPart in OpenROAD");
        logger.info(timer.toString());
        printPartitionResults(partResult);

        return partResult;
    }

    private void writeOpenroadTclCmdFile() {
        Path filePath = workDir.resolve(TCL_FILE_NAME);
        TclCmdFile tclCmdFile = new TclCmdFile();

        String partitionCmd = "triton_part_hypergraph";
        partitionCmd += String.format(" -balance_constraint %.2f", balanceConstr);
        partitionCmd += String.format(" -hypergraph_file %s", GRAPH_FILE_NAME);
        partitionCmd += String.format(" -num_parts %d", blockNum);
        partitionCmd += String.format(" -seed %d", RAND_SEED);
        partitionCmd += String.format(" -vertex_dimension %d", hyperGraph.getNodeWeightDim());
        partitionCmd += String.format(" -hyperedge_dimension %d", hyperGraph.getEdgeWeightDim());

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
        String imageName = "openroad:latest";
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

        HyperGraph hyperGraph = new HyperGraph(1, 1);

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
        List<Integer> partResult = partitioner.run(1.0, 2);


        //TritonPartitionWrapper partitioner = new T
    }
}
