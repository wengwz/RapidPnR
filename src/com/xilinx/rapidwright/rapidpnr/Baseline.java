package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.VivadoTclCmd;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.TclCmdFile;

public class Baseline {

    public static void main(String[] args) {
        String jsonFilePath = "workspace/json/nvdla-small-256-full.json";

        Path paramsPath = Path.of(jsonFilePath).toAbsolutePath();
        DesignParams designParams = new DesignParams(paramsPath);
        DirectoryManager dirManager = new DirectoryManager(designParams.getWorkDir());
        Path workDir = dirManager.addSubDir("baseline");

        HierarchicalLogger logger = new HierarchicalLogger("baseline");
        logger.setUseParentHandlers(false);
        Path logFilePath = workDir.resolve("baseline.log");
        // setup logger
        try {
            FileHandler fileHandler = new FileHandler(logFilePath.toString(), false);
            fileHandler.setFormatter(new CustomFormatter());
            logger.addHandler(fileHandler);

            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new CustomFormatter());
            logger.addHandler(consoleHandler);
        } catch (Exception e) {
            System.out.println("Fail to open log file: " + logFilePath.toString());
        }
        logger.setLevel(Level.INFO);

        // prepare input design
        logger.info("Prepare input design");
        Design inputDesign = Design.readCheckpoint(designParams.getInputDcpPath().toString());
        //// set pblock constraints
        String pblockRange = designParams.getPblockRange("complete");
        assert pblockRange != null;
        VivadoTclCmd.addStrictCellPblockConstr(inputDesign, pblockRange);
        //// set clock constraints
        VivadoTclCmd.createClocks(inputDesign, designParams.getClkPortName2PeriodMap());
        VivadoTclCmd.setAsyncClockGroupsForEachClk(inputDesign, designParams.getClkPortNames());


        // prepare tcl command file
        logger.info("Prepare tcl command file");
        TclCmdFile tclCmdFile = new TclCmdFile();
        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));
        tclCmdFile.addCmd(VivadoTclCmd.placeDesign());
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign(null));
        tclCmdFile.addCmds(VivadoTclCmd.conditionalPhysOptDesign());
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, "timing.rpt"));
        tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));

        // create Vivado project
        logger.info("Create Vivado project");
        VivadoProject vivadoProject = new VivadoProject(inputDesign, workDir, tclCmdFile);
        Job vivadoJob = vivadoProject.createVivadoJob();

        logger.info("Launch baseline flow");
        JobQueue jobQueue = new JobQueue();
        jobQueue.addJob(vivadoJob);

        RuntimeTracker timer = new RuntimeTracker("baseline", (short) 0);
        timer.start();
        boolean success = jobQueue.runAllToCompletion();
        timer.stop();

        if (success) {
            logger.info("Baseline flow completed successfully");
            logger.info(timer.toString());
        } else {
            logger.severe("Baseline flow failed");
        }
    }
}
