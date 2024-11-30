package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.VivadoTclCmd;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;
import com.xilinx.rapidwright.util.JobQueue;

import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.design.Design;

import static com.xilinx.rapidwright.rapidpnr.NameConvention.*;


public class IncrementalIslandPnR extends PhysicalImpl{


    public IncrementalIslandPnR(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams designParams, NetlistDatabase netlistDB) {
        super(logger, dirManager, designParams, netlistDB);
    }

    public void run(AbstractNetlist abstractNetlist, List<Coordinate2D> groupLocs) {
        loadPreStepsResult(abstractNetlist, groupLocs, true);

        logger.info("Start running Parallel Iterative PnR flow");
        logger.newSubStep();

        //
        Coordinate2D island0Loc = Coordinate2D.of(0, 0);
        Path island0WorkDir = dirManager.addSubDir(getIslandName(island0Loc));
        
        Design island0 = createIslandDesignWithBoundary(new Coordinate2D(0, 0));
        setConstraintOnParallelDesign(island0, island0Loc);
        TclCmdFile island0TclCmdFile = createTclCmdFileForIslandImpl(island0Loc, null, true);
        VivadoProject island0Project = new VivadoProject(island0, island0WorkDir, island0TclCmdFile);
        Job island0Job = island0Project.createVivadoJob();

        //
        Coordinate2D island3Loc = Coordinate2D.of(1, 1);
        Path island3WorkDir = dirManager.addSubDir(getIslandName(new Coordinate2D(1, 1)));
        
        Design island3 = createIslandDesignWithBoundary(island3Loc);
        setConstraintOnParallelDesign(island3, island3Loc);
        TclCmdFile island3TclCmdFile = createTclCmdFileForIslandImpl(island3Loc, null, true);
        VivadoProject island3Project = new VivadoProject(island3, island3WorkDir, island3TclCmdFile);
        Job island3Job = island3Project.createVivadoJob();

        //
        Coordinate2D island1Loc = Coordinate2D.of(1, 0);
        Path island1WorkDir = dirManager.addSubDir(getIslandName(new Coordinate2D(1, 0)));
        
        Design island1 = createIslandDesignWithContext(island1Loc, Arrays.asList(island0, island3), false);
        setConstraintOnIncrementalDesign(island1, island1Loc);
        Map<Path, Design> island1Context = new HashMap<>();
        island1Context.put(island0WorkDir, island0);
        island1Context.put(island3WorkDir, island3);
        TclCmdFile island1TclCmdFile = createTclCmdFileForIslandImpl(island1Loc, island1Context, true);
        VivadoProject island1Project = new VivadoProject(island1, island1WorkDir, island1TclCmdFile);
        Job island1Job = island1Project.createVivadoJob();
        
        //
        Coordinate2D island2Loc = Coordinate2D.of(0, 1);
        Path island2WorkDir = dirManager.addSubDir(getIslandName(island2Loc));

        Design island2 = createIslandDesignWithContext(island2Loc, Arrays.asList(island1), true);
        setConstraintOnIncrementalDesign(island2, island2Loc);
        Map<Path, Design> island2Context = new HashMap<>();
        island2Context.put(island2WorkDir, island2);
        TclCmdFile island2TclCmdFile = createTclCmdFileForIslandImpl(island2Loc, island2Context, false);
        VivadoProject island2Project = new VivadoProject(island2, island2WorkDir, island2TclCmdFile);
        Job island2Job = island2Project.createVivadoJob();

        JobQueue jobQueue = new JobQueue();
        jobQueue.addJob(island0Job);
        jobQueue.addJob(island3Job);


        RuntimeTrackerTree runTimeTrackerTree = new RuntimeTrackerTree("RapidPnR", false);
        String rootTimerName = runTimeTrackerTree.getRootRuntimeTracker();
        //
        RuntimeTracker subTimer = runTimeTrackerTree.createRuntimeTracker("island0&3", rootTimerName);

        subTimer.start();
        boolean success = jobQueue.runAllToCompletion();
        subTimer.stop();

        assert success: "run Vivado Impls of island0 and island3 failed";
        logger.info("Complete running Vivado Impls of island0 and island3");
        logger.info(subTimer.toString());

        //
        subTimer = runTimeTrackerTree.createRuntimeTracker("island1", rootTimerName);
        jobQueue.addJob(island1Job);
        subTimer.start();
        success = jobQueue.runAllToCompletion();
        subTimer.stop();

        assert success: "run Vivado Impls of island1 failed";
        logger.info("Complete running Vivado Impls of island1");
        logger.info(subTimer.toString());

        //
        subTimer = runTimeTrackerTree.createRuntimeTracker("island2", rootTimerName);
        jobQueue.addJob(island2Job);
        subTimer.start();
        success = jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success: "run Vivado Impls of island2 failed";

        logger.info("Complete running Vivado Impls of island2");
        logger.info(subTimer.toString());


        logger.endSubStep();
        logger.info("Complete running Parallel Iterative PnR flow");
        logger.info(runTimeTrackerTree.toString());
    }

    private Design createIslandDesignWithContext(Coordinate2D loc, List<Design> contextDesigns, boolean isTopCell) {
        logger.info("Start creating design with context for island" + loc.toString());
        logger.newSubStep();

        String islandDesignName = getIslandName(loc) + "_context";
        Design topDesign = new Design(islandDesignName, netlistDB.partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        EDIFCell islandCell = new EDIFCell(workLib, getIslandName(loc));
        copyPartialNetlistToCell(islandCell, netlistDB.originTopCell, getCellInstsOfIsland(loc));
        islandCell.createCellInst(getIslandName(loc), topCell);

        for (Design contextDesign : contextDesigns) {
            EDIFCell contextTopCell = contextDesign.getNetlist().getTopCell();
            EDIFCell newCell = createBlackboxCell(workLib, contextTopCell);
            newCell.createCellInst(newCell.getName(), topCell);
        }

        if (isTopCell) {
            connectCellInstsOfTopCell(topCell, netlistDB.originTopCell);
        } else {
            connectCellInstsOfCustomCell(topCell, netlistDB.originTopCell);
        }

        topDesign.setAutoIOBuffers(false);
        //topDesign.setDesignOutOfContext(false);

        logger.endSubStep();
        logger.info("Complete creating design with context for island" + loc.toString());
        return topDesign;
    }


    private TclCmdFile createTclCmdFileForIslandImpl(Coordinate2D loc, Map<Path, Design> contexts, Boolean lockIsland) {
        logger.info("Start creating file of Tcl commands for merging design");
        TclCmdFile tclCmdFile = new TclCmdFile();

        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));

        // read context designs
        if (contexts!= null) {
            for (Map.Entry<Path, Design> context : contexts.entrySet()) {
                Path contextDcpPath = context.getKey().resolve(VivadoProject.OUTPUT_DCP_NAME);
                String designName = context.getValue().getName();
                tclCmdFile.addCmd(VivadoTclCmd.readCheckpoint(designName, contextDcpPath.toString()));
            }
        }

        tclCmdFile.addCmd(VivadoTclCmd.placeDesign());
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign());
        tclCmdFile.addCmds(VivadoTclCmd.conditionalPhysOptDesign());

        String timingRptPath = addSuffixRpt("timing_summary");
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, timingRptPath));

        if (lockIsland) {
            String lockCellInstName = getIslandName(loc);
            tclCmdFile.addCmd(VivadoTclCmd.lockDesign(false, "routing", lockCellInstName));
        }

        tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));

        logger.info("Complete creating file of Tcl commands for merging design");

        return tclCmdFile;
    }

    private void setConstraintOnParallelDesign(Design design, Coordinate2D islandLoc) {
        EDIFCell topCell = design.getTopEDIFCell();

        String islandPblockRange = getPblockRangeOfIsland(islandLoc);
        // Add pblock constraints on top cell
        VivadoTclCmd.addTopCellPblockConstr(design, islandPblockRange, false, false, true);

        Coordinate2D loc = getUpBoundaryLocOf(islandLoc);
        if (loc != null) {
            EDIFCellInst boundaryCellInst = topCell.getCellInst(getHoriBoundaryName(loc));
            String pblockRange = getPblockRangeOfHoriBoundary(loc);
            VivadoTclCmd.addStrictCellPblockConstr(design, boundaryCellInst, pblockRange);
        }

        loc = getDownBoundaryLocOf(islandLoc);
        if (loc != null) {
            EDIFCellInst boundaryCellInst = topCell.getCellInst(getHoriBoundaryName(loc));
            String pblockRange = getPblockRangeOfHoriBoundary(loc);
            VivadoTclCmd.addStrictCellPblockConstr(design, boundaryCellInst, pblockRange);
        }

        loc = getLeftBoundaryLocOf(islandLoc);
        if (loc != null) {
            EDIFCellInst boundaryCellInst = topCell.getCellInst(getVertBoundaryName(loc));
            String pblockRange = getPblockRangeOfVertBoundary(loc);
            VivadoTclCmd.addStrictCellPblockConstr(design, boundaryCellInst, pblockRange);
        }

        loc = getRightBoundaryLocOf(islandLoc);
        if (loc != null) {
            EDIFCellInst boundaryCellInst = topCell.getCellInst(getVertBoundaryName(loc));
            String pblockRange = getPblockRangeOfVertBoundary(loc);
            VivadoTclCmd.addStrictCellPblockConstr(design, boundaryCellInst, pblockRange);
        }

        VivadoTclCmd.createClocks(design, clkName2PeriodMap);
        VivadoTclCmd.setAsyncClockGroupsForEachClk(design, clkName2PeriodMap.keySet());
    }

    private void setConstraintOnIncrementalDesign(Design design, Coordinate2D islandLoc) {
        EDIFCell topCell = design.getTopEDIFCell();
        EDIFCellInst islandCellInst = topCell.getCellInst(getIslandName(islandLoc));
        String pblockRange = getPblockRangeOfIsland(islandLoc);
        
        VivadoTclCmd.addStrictCellPblockConstr(design, islandCellInst, pblockRange);

        VivadoTclCmd.createClocks(design, clkName2PeriodMap);
        VivadoTclCmd.setAsyncClockGroupsForEachClk(design, clkName2PeriodMap.keySet());
    }

}
