package com.xilinx.rapidwright.rapidpnr;

import static com.xilinx.rapidwright.rapidpnr.NameConvention.getHoriBoundaryName;
import static com.xilinx.rapidwright.rapidpnr.NameConvention.getIslandName;
import static com.xilinx.rapidwright.rapidpnr.NameConvention.getVertBoundaryName;

import java.nio.file.Path;
import java.util.List;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoProject;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.VivadoTclCmd;

public class CompletePnR extends AbstractPhysicalImpl {

    private boolean extractBoundaryCell = true;

    public CompletePnR(
        HierarchicalLogger logger, 
        DirectoryManager dirManager, 
        DesignParams designParams, 
        NetlistDatabase netlistDB,
        boolean extractBoundaryCell
    ) {
        super(logger, dirManager, designParams, netlistDB);
        this.extractBoundaryCell = extractBoundaryCell;
    }

    public void run(AbstractNetlist abstractNetlist, List<Coordinate2D> groupLocs) {
        // load results of previous steps
        loadPreStepsResult(abstractNetlist, groupLocs, extractBoundaryCell);

        logger.info("Start running PnR flow for floorplanned complete design");
        logger.newSubStep();

        Path workDir = dirManager.addSubDir("complete-reconfig");
        // create design
        Design completeDesign = createCompleteDesign();
        // create tcl command file
        TclCmdFile tclCmdFile = createTclCmdFileForCompletePnR();
        // create project
        VivadoProject vivadoProject = new VivadoProject(completeDesign, workDir, tclCmdFile);
        Job vivadoJob = vivadoProject.createVivadoJob();

        // create island designs
        // for (int x = 0; x < gridDim.getX(); x++) {
        //     for (int y = 0; y < gridDim.getY(); y++) {
        //         Design islandDesign = createIslandDesign(Coordinate2D.of(x, y));
        //         Path dcpPath = workDir.resolve(getIslandDcpName(x, y));
        //         islandDesign.writeCheckpoint(dcpPath.toString());
        //     }
        // }

        JobQueue jobQueue = new JobQueue();
        jobQueue.addJob(vivadoJob);

        RuntimeTracker timer = new RuntimeTracker("PnR of complete design", (short) 0);
        timer.start();
        jobQueue.runAllToCompletion();
        timer.stop();

        logger.endSubStep();
        logger.info("Complete running PnR flow for floorplanned complete design");
        logger.info(timer.toString());
    }

    public TclCmdFile createTclCmdFileForCompletePnR() {
        TclCmdFile tclCmdFile = new TclCmdFile();

        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        // read checkpoint
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));

        // set constraints
        //// clock constraints
        for (String clkName : clkName2PeriodMap.keySet()) {
            Double period = clkName2PeriodMap.get(clkName);
            tclCmdFile.addCmd(VivadoTclCmd.createClock(clkName, period));
        }
        if (clkName2PeriodMap.size() > 1) {
            tclCmdFile.addCmd(VivadoTclCmd.setClockGroups(true, clkName2PeriodMap.keySet()));
        }

        //// pblock constraints
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                String cellName = getIslandName(x, y);
                String pblockRange = getPblockRangeOfIsland(Coordinate2D.of(x, y));
                tclCmdFile.addCmds(VivadoTclCmd.addCellPblockConstr(cellName, pblockRange, false, false, true));
                //tclCmdFile.addCmd(VivadoTclCmd.setPropertyDontTouch(true, cellName));
                //tclCmdFile.addCmd(VivadoTclCmd.setPropertyHDReConfig(true, cellName));
            }
        }

        if (hasBoundaryCell) {

            vertBoundaryDim.traverse((Coordinate2D loc) -> {
                if (!isVertBoundaryExist(loc)) return;
                String cellName = getVertBoundaryName(loc);
                String pblockRange = getPblockRangeOfVertBoundary(loc);
                tclCmdFile.addCmds(VivadoTclCmd.addCellPblockConstr(cellName, pblockRange, false, false, true));
            });

            horiBoundaryDim.traverse((Coordinate2D loc) -> {
                if (!isHoriBoundaryExist(loc)) return;
                String cellName = getHoriBoundaryName(loc);
                String pblockRange = getPblockRangeOfHoriBoundary(loc);
                tclCmdFile.addCmds(VivadoTclCmd.addCellPblockConstr(cellName, pblockRange, false, false, true));
            });
        }

        // run PnR flow
        tclCmdFile.addCmd(VivadoTclCmd.placeDesign());
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign());
        tclCmdFile.addCmds(VivadoTclCmd.conditionalPhysOptDesign());

        // save PnR results
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, "timing.rpt"));
        tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));
        tclCmdFile.addCmd(VivadoTclCmd.exitVivado());

        return tclCmdFile;
    }
}

