package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.VivadoTclCmd;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;
import com.xilinx.rapidwright.design.DesignTools;

import static com.xilinx.rapidwright.rapidpnr.NameConvention.*;

public class ParallelIslandPnR extends PhysicalImpl{

    public ParallelIslandPnR(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams designParams, NetlistDatabase netlistDB) {
        super(logger, dirManager, designParams, netlistDB);
    }


    public void run(AbstractNetlist abstractNetlist, List<Coordinate2D> groupLocs) {
        // load results of previous steps
        loadPreStepsResult(abstractNetlist, groupLocs, true);

        JobQueue jobQueue = new JobQueue();
        RuntimeTrackerTree runtimeTrackerTree = new RuntimeTrackerTree("ParallelIslandPnR", false);
        String rootTimerName = runtimeTrackerTree.getRootRuntimeTracker();
        Map<Coordinate2D, Design> loc2DesignWithBoundary = new HashMap<>();

        logger.info("Start running ParallelIslandPnR");
        // create vivado jobs
        //// create vivado jobs for each island
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                Coordinate2D loc = new Coordinate2D(x, y);
                int id = getIslandIdFromLoc(loc);
                Path workDir = dirManager.addSubDir(getIslandName(loc));
                Design islandDesign;
                TclCmdFile tclCmdFile;

                if (id == 0 || id == 3) {
                    islandDesign = createIslandDesignWithBoundary(loc);
                    setConstraintOnIslandDesign(islandDesign, loc, false, false);
                    tclCmdFile = createTclCmdFileForIslandImpl(loc, false, false, true);
                    loc2DesignWithBoundary.put(loc, islandDesign);
                } else {
                    islandDesign = createIslandDesignWithBoundary(loc);
                    setConstraintOnIslandDesign(islandDesign, loc, false, true);
                    tclCmdFile = createTclCmdFileForIslandImpl(loc, false, false, false);
                }

                VivadoProject islandProject = new VivadoProject(islandDesign, workDir, tclCmdFile);
                Job islandJob = islandProject.createVivadoJob();
                jobQueue.addJob(islandJob);
            }
        }

        //// create vivado job for merging all islands
        Design mergeDesign = createDesignForIslandMerge(loc2DesignWithBoundary);
        Path mergeDir = dirManager.addSubDir("merge");
        TclCmdFile mergeTclFile = createTclCmdFileForIslandMerge(loc2DesignWithBoundary.keySet());
        VivadoProject mergeProject = new VivadoProject(mergeDesign, mergeDir, mergeTclFile);
        Job mergeJob = mergeProject.createVivadoJob();

        // parallel impl of all islands
        RuntimeTracker subTimer = runtimeTrackerTree.createRuntimeTracker("parallel", rootTimerName);
        subTimer.start();
        boolean success = jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success : "parallel PnR failed";
        logger.info("Complete running parallel PnR of all islands");
        logger.info(subTimer.toString());

        // remove boundary
        // for (int x = 0; x < gridDim.getX(); x++) {
        //     for (int y = 0; y < gridDim.getY(); y++) {
        //         Coordinate2D loc = new Coordinate2D(x, y);
        //         int id = getIslandIdFromLoc(loc);
        //         if (id == 1 || id == 2) {
        //             removeBoundaryCellForIsland(loc);
        //         }
        //     }
        // }

        // merge all islands
        jobQueue.addJob(mergeJob);
        subTimer = runtimeTrackerTree.createRuntimeTracker("merge", rootTimerName);
        subTimer.start();
        jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success : "merge PnR failed";
        logger.info("Complete running merge PnR of all islands");
        logger.info(subTimer.toString());

        logger.info("Complete running ParallelIslandPnR");
        logger.info(runtimeTrackerTree.toString());
    }

    public void run3x2() {
        JobQueue jobQueue = new JobQueue();
        RuntimeTrackerTree runtimeTrackerTree = new RuntimeTrackerTree("ParallelIslandPnR", false);
        String rootTimerName = runtimeTrackerTree.getRootRuntimeTracker();

        Map<Coordinate2D, Design> loc2DesignWithBoundary = new HashMap<>();
        

        logger.info("Start running ParallelIslandPnR flow");
        logger.newSubStep();
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                Coordinate2D loc = new Coordinate2D(x, y);
                Path workDir = dirManager.addSubDir(getIslandName(loc));
                Design islandDesign;
                TclCmdFile tclCmdFile;
                if (getIslandIdFromLoc(loc) % 2 == 0) {
                    islandDesign = createIslandDesignWithBoundary(loc);
                    setConstraintOnIslandDesign(islandDesign, loc, false, false);
                    tclCmdFile = createTclCmdFileForIslandImpl(loc, true, false, true);
                    loc2DesignWithBoundary.put(loc, islandDesign);
                } else {
                    islandDesign = createIslandDesignWithBoundary(loc);
                    setConstraintOnIslandDesign(islandDesign, loc, false, true);
                    tclCmdFile = createTclCmdFileForIslandImpl(loc, false, false, false);
                }

                VivadoProject islandProject = new VivadoProject(islandDesign, workDir, tclCmdFile);
                Job islandJob = islandProject.createVivadoJob();
                jobQueue.addJob(islandJob);

                logger.info("Start running PnR of " + getIslandName(loc));
                RuntimeTracker subTimer = runtimeTrackerTree.createRuntimeTracker(getIslandName(loc), rootTimerName);
                subTimer.start();
                jobQueue.runAllToCompletion();
                subTimer.stop();
                logger.info("Complete running PnR of " + getIslandName(loc));
                logger.info(subTimer.toString());
            }
        }

        Design mergeDesign = createDesignForIslandMerge(loc2DesignWithBoundary);
        Path mergeDir = dirManager.addSubDir("merge");
        TclCmdFile mergeTclFile = createTclCmdFileForIslandMerge(loc2DesignWithBoundary.keySet());
        VivadoProject mergeProject = new VivadoProject(mergeDesign, mergeDir, mergeTclFile);
        Job mergeJob = mergeProject.createVivadoJob();

        // parallel impl of all islands
        // RuntimeTracker subTimer = runtimeTrackerTree.createRuntimeTracker("parallel", rootTimerName);
        // subTimer.start();
        // boolean success = jobQueue.runAllToCompletion();
        // subTimer.stop();
        // assert success : "parallel PnR failed";
        // logger.info("Complete running parallel PnR of all islands");
        // logger.info(subTimer.toString());


        // merge all islands
        jobQueue.addJob(mergeJob);
        RuntimeTracker subTimer = runtimeTrackerTree.createRuntimeTracker("merge", rootTimerName);
        subTimer.start();
        jobQueue.runAllToCompletion();
        subTimer.stop();
        logger.info("Complete running merge PnR of all islands");
        logger.info(subTimer.toString());

        logger.endSubStep();
        logger.info("Complete running ParallelIslandPnR flow");
        logger.info(runtimeTrackerTree.toString());
    }


    private TclCmdFile createTclCmdFileForIslandImpl(Coordinate2D loc, boolean lockDesign, boolean writeEDIF, boolean writeComplete) {

        TclCmdFile tclCmdFile = new TclCmdFile();

        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));
        tclCmdFile.addCmd(VivadoTclCmd.placeDesign());
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign(null));
        tclCmdFile.addCmds(VivadoTclCmd.conditionalPhysOptDesign());

        String timingRptPath = addSuffixRpt("timing_summary");
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, timingRptPath));

        if (lockDesign) {
            tclCmdFile.addCmd(VivadoTclCmd.lockDesign(false, "routing", getIslandName(loc)));
        }

        if (writeComplete) {
            tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));
        } else {
            tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, getIslandName(loc), VivadoProject.OUTPUT_DCP_NAME));
        }

        if (writeEDIF) {
            tclCmdFile.addCmd(VivadoTclCmd.writeEDIF(true, null, VivadoProject.OUTPUT_EDIF_NAME));
        }

        return tclCmdFile;
    }

    private void setConstraintOnIslandDesign(Design design, Coordinate2D islandLoc, boolean addPblockOnTop, boolean setIslandDontTouch) {
        EDIFCell topCell = design.getNetlist().getTopCell();
        String islandName = getIslandName(islandLoc);
        EDIFCellInst islandCellInst = topCell.getCellInst(islandName);

        String islandPblockRange = getPblockRangeOfIsland(islandLoc);
        if (addPblockOnTop) {
            VivadoTclCmd.addTopCellPblockConstr(design, islandPblockRange, false, false, true);
        } else {
            VivadoTclCmd.addStrictCellPblockConstr(design, islandCellInst, islandPblockRange);
        }

        Coordinate2D boundaryLoc = getUpBoundaryLocOf(islandLoc);
        if (boundaryLoc != null) {
            EDIFCellInst cellInst = topCell.getCellInst(getHoriBoundaryName(boundaryLoc));
            String pblockRange = getPblockRangeOfHoriBoundary(boundaryLoc);
            VivadoTclCmd.addStrictCellPblockConstr(design, cellInst, pblockRange);
        }

        boundaryLoc = getDownBoundaryLocOf(islandLoc);
        if (boundaryLoc != null) {
            EDIFCellInst cellInst = topCell.getCellInst(getHoriBoundaryName(boundaryLoc));
            String pblockRange = getPblockRangeOfHoriBoundary(boundaryLoc);
            VivadoTclCmd.addStrictCellPblockConstr(design, cellInst, pblockRange);
        }

        boundaryLoc = getLeftBoundaryLocOf(islandLoc);
        if (boundaryLoc != null) {
            EDIFCellInst cellInst = topCell.getCellInst(getVertBoundaryName(boundaryLoc));
            String pblockRange = getPblockRangeOfVertBoundary(boundaryLoc);
            VivadoTclCmd.addStrictCellPblockConstr(design, cellInst, pblockRange);
        }

        boundaryLoc = getRightBoundaryLocOf(islandLoc);
        if (boundaryLoc != null) {
            EDIFCellInst cellInst = topCell.getCellInst(getVertBoundaryName(boundaryLoc));
            String pblockRange = getPblockRangeOfVertBoundary(boundaryLoc);
            VivadoTclCmd.addStrictCellPblockConstr(design, cellInst, pblockRange);
        }

        if (setIslandDontTouch) {
            VivadoTclCmd.setPropertyDontTouch(design, islandCellInst);
        }
        VivadoTclCmd.createClocks(design, clkName2PeriodMap);
        VivadoTclCmd.setAsyncClockGroupsForEachClk(design, clkName2PeriodMap.keySet());
    }

    private Design createDesignForIslandMerge(Map<Coordinate2D, Design> loc2DesignWithBoundary) {
        logger.info("Start creating design for merging island");

        Design topDesign = new Design("complete", netlistDB.partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                Coordinate2D loc = new Coordinate2D(x, y);
                Design islandDesign = loc2DesignWithBoundary.get(loc);
                if (islandDesign == null) {
                    islandDesign = createIslandDesign(loc);
                }

                EDIFCell islandCell = islandDesign.getNetlist().getTopCell();
                EDIFCell blackboxIslandCell = createBlackboxCell(workLib, islandCell);
                blackboxIslandCell.createCellInst(islandCell.getName(), topCell);
            }
        }

        connectCellInstsOfTopCell(topCell, netlistDB.originTopCell);

        // set clock constraint
        VivadoTclCmd.createClocks(topDesign, clkName2PeriodMap);
        VivadoTclCmd.setAsyncClockGroupsForEachClk(topDesign, clkName2PeriodMap.keySet());
        topDesign.setAutoIOBuffers(false);

        logger.info("Complete creating design for merging island");
        return topDesign;
    }

    private TclCmdFile createTclCmdFileForIslandMerge(Collection<Coordinate2D> islandLocsWithBoundary) {
        TclCmdFile tclCmdFile = new TclCmdFile();

        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));

        Map<String, Path> cellInst2DcpMap = new HashMap<>();
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                Coordinate2D loc = new Coordinate2D(x, y);
                Path dcpPath = dirManager.getSubDir(getIslandName(loc)).resolve(VivadoProject.OUTPUT_DCP_NAME);
                String cellName = getIslandName(loc);

                if (islandLocsWithBoundary.contains(loc)) {
                    cellName = getIslandName(loc) + "_boundary";
                }
                cellInst2DcpMap.put(cellName, dcpPath);
            }
        }

        tclCmdFile.addCmd(VivadoTclCmd.readCheckpoint(cellInst2DcpMap));
        //tclCmdFile.addCmd(VivadoTclCmd.placeDesign(null));
        //tclCmdFile.addCmd(VivadoTclCmd.physOptDesign());
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign(null));
        //tclCmdFile.addCmds(VivadoTclCmd.conditionalPhysOptDesign());
        String timingRptPath = addSuffixRpt("timing_summary");
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, timingRptPath));

        tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));

        return tclCmdFile;
    }

    private void removeBoundaryCellForIsland(Coordinate2D loc) {
        Path dcpPath = dirManager.getSubDir(getIslandName(loc)).resolve(VivadoProject.OUTPUT_DCP_NAME);
        
        Design originDesign = Design.readCheckpoint(dcpPath.toString());
        EDIFHierCellInst topCellInst = originDesign.getNetlist().getTopHierCellInst();
        EDIFHierCellInst islandCellInst = topCellInst.getChild(getIslandName(loc));
        String islandCellInstHierName = islandCellInst.getFullHierarchicalInstName();

        EDIFNetlist islandNetlist = EDIFTools.createNewNetlist(islandCellInst.getInst());
        EDIFTools.ensureCorrectPartInEDIF(islandNetlist, originDesign.getPartName());

        Design newDesign = new Design(islandNetlist);
        DesignTools.copyImplementation(originDesign, newDesign, false, true, false, false, Map.of(islandCellInstHierName,""));
        newDesign.setAutoIOBuffers(false);

        newDesign.writeCheckpoint(dcpPath.toString());
    }

    
}
