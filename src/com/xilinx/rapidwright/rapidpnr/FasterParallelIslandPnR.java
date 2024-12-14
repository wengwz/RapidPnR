package com.xilinx.rapidwright.rapidpnr;

import static com.xilinx.rapidwright.rapidpnr.NameConvention.addSuffixRpt;
import static com.xilinx.rapidwright.rapidpnr.NameConvention.getHoriBoundaryName;
import static com.xilinx.rapidwright.rapidpnr.NameConvention.getIslandName;
import static com.xilinx.rapidwright.rapidpnr.NameConvention.getVertBoundaryName;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoProject;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.VivadoTclCmd;

public class FasterParallelIslandPnR extends AbstractPhysicalImpl{

    protected Set<EDIFCellInst>[][] periIsland2CellInsts;

    public FasterParallelIslandPnR(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams designParams, NetlistDatabase netlistDB) {
        super(logger, dirManager, designParams, netlistDB);
    }

    public void run(AbstractNetlist abstractNetlist, List<Coordinate2D> groupLocs) {
        // load results of previous stages
        loadPreStepsResult(abstractNetlist, groupLocs, true);
        
        JobQueue jobQueue = new JobQueue();
        RuntimeTrackerTree runtimeTrackerTree = new RuntimeTrackerTree("FastParallelIslandPnR", false);
        String rootTimerName = runtimeTrackerTree.getRootRuntimeTracker();
        RuntimeTracker subTimer;
        boolean success;

        logger.info("Start running FastParallelIslandPnR");
        logger.newSubStep();

        buildCellInst2PeriIslandMap(1);

        logger.info("Start PnR of boundary");
        Path boundaryPath = dirManager.addSubDir("boundary");
        Design boundaryDesign = createBoundaryDesign();
        TclCmdFile boundaryTclFile = createTclFileForBoundaryDesign();
        VivadoProject boundaryProject = new VivadoProject(boundaryDesign, boundaryPath, boundaryTclFile);
        Job boundaryJob = boundaryProject.createVivadoJob();
        jobQueue.addJob(boundaryJob);

        subTimer = runtimeTrackerTree.createRuntimeTracker("boundary", rootTimerName);
        subTimer.start();
        success = jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success: "PnR of boundary failed";
        logger.info("Complete PnR of boundary in " + subTimer.getTimeInSec() + " seconds");


        logger.info("Start parallel PnR of islands");
        gridDim.traverse((Coordinate2D loc) -> {
            Path islandPath = dirManager.addSubDir(getIslandName(loc));
            Design islandDesign = createIslandBlackboxBoundaryDesign(boundaryDesign, loc);
            TclCmdFile islandTclFile = createTclFileForIsland(loc);
            VivadoProject islandProject = new VivadoProject(islandDesign, islandPath, islandTclFile);
            Job islandJob = islandProject.createVivadoJob();
            jobQueue.addJob(islandJob);
        });

        subTimer = runtimeTrackerTree.createRuntimeTracker("Parallel Island PnR", rootTimerName);
        subTimer.start();
        success = jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success: "Parallel Island PnR failed";
        logger.info("Complete parallel PnR of islands in " + subTimer.getTimeInSec() + " seconds");

        logger.info("Start merging islands and boundaries");
        Path mergePath = dirManager.addSubDir("merged");
        Design mergeDesign = readAndCreateMergedDesign();
        TclCmdFile mergeTclFile = createTclFileForMergeDesign();
        VivadoProject mergeProject = new VivadoProject(mergeDesign, mergePath, mergeTclFile);
        Job mergeJob = mergeProject.createVivadoJob();
        //jobQueue.addJob(mergeJob);

        subTimer = runtimeTrackerTree.createRuntimeTracker("Merge Islands and Boundaries", rootTimerName);
        subTimer.start();
        success = jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success: "Merge Islands and Boundaries failed";
        logger.info("Complete merging islands and boundaries in " + subTimer.getTimeInSec() + " seconds");


        logger.endSubStep();
        logger.info("Complete running FastParallelIslandPnR");
        logger.info(runtimeTrackerTree.toString());
    }

    private void buildCellInst2PeriIslandMap(int maxDepth) {
        logger.info("Start building cellInsts to peripheral islands map");

        Set<String> expandCellNames = new HashSet<>(Arrays.asList("CARRY8", "MUXF7", "MUXF8"));

        periIsland2CellInsts = new HashSet[gridDim.getX()][gridDim.getY()];
        //Set<EDIFCellInst>[][] partialIslands = new HashSet[gridDim.getX()][gridDim.getY()];
        
        gridDim.traverse((Coordinate2D loc) -> {
            periIsland2CellInsts[loc.getX()][loc.getY()] = new HashSet<>();
        });

        Queue<EDIFCellInst> searchCellInstQ = new LinkedList<>();
        Map<EDIFCellInst, Integer> cellInst2DepthMap = new HashMap<>();
        horiBoundaryDim.traverse((Coordinate2D loc) -> {
            searchCellInstQ.addAll(getCellInstsOfHoriBoundary(loc));

            for (EDIFCellInst cellInst : getCellInstsOfHoriBoundary(loc)) {
                cellInst2DepthMap.put(cellInst, 0);
            }
        });

        vertBoundaryDim.traverse((Coordinate2D loc) -> {
            searchCellInstQ.addAll(getCellInstsOfVertBoundary(loc));

            for (EDIFCellInst cellInst : getCellInstsOfVertBoundary(loc)) {
                cellInst2DepthMap.put(cellInst, 0);
            }
        });

        Set<EDIFNet> visitedNets = new HashSet<>();
        visitedNets.addAll(netlistDB.globalClockNets);
        visitedNets.addAll(netlistDB.globalResetNets);
        visitedNets.addAll(netlistDB.ignoreNets);
        visitedNets.addAll(netlistDB.illegalNets);

        while (!searchCellInstQ.isEmpty()) {
            EDIFCellInst searchCellInst = searchCellInstQ.poll();

            Integer depth = cellInst2DepthMap.get(searchCellInst);

            if (depth >= maxDepth) continue;

            for (EDIFPortInst searchPortInst : searchCellInst.getPortInsts()) {
                EDIFNet expandNet = searchPortInst.getNet();

                if (visitedNets.contains(expandNet)) continue;
                if (expandNet.isGND() || expandNet.isVCC()) continue;

                visitedNets.add(expandNet);

                for (EDIFPortInst expandPortInst : expandNet.getPortInsts()) {
                    EDIFCellInst expandCellInst = expandPortInst.getCellInst();

                    if (expandCellInst == null) continue;
                    if (cellInst2DepthMap.containsKey(expandCellInst)) continue;

                    Coordinate2D loc = cellInst2IslandLocMap.get(expandCellInst);
                    assert loc != null;

                    cellInst2DepthMap.put(expandCellInst, depth + 1);
                    periIsland2CellInsts[loc.getX()][loc.getY()].add(expandCellInst);
                    island2CellInsts[loc.getX()][loc.getY()].remove(expandCellInst);
                    searchCellInstQ.add(expandCellInst);
                }
            }
        }

        logger.info("Complete building cellInsts to peripheral islands map");
    }

    private Design createBoundaryDesign() {
        Design design = new Design("boundary", netlistDB.partName);
        EDIFNetlist netlist = design.getNetlist();

        EDIFCell topCell = netlist.getTopCell();


        CreateCellInstOfLoc createCellInst = new CreateCellInstOfLoc();
        createCellInst.design = design;

        createCellInst.loc2CellInsts = periIsland2CellInsts;
        createCellInst.loc2CellName = NameConvention::getPeriIslandName;
        createCellInst.loc2PblockName = NameConvention::getIslandName;
        createCellInst.loc2PblockRange = this::getPblockRangeOfIsland;
        gridDim.traverse(createCellInst);

        createCellInst.loc2CellInsts = horiBoundary2CellInsts;
        createCellInst.loc2CellName = NameConvention::getHoriBoundaryName;
        createCellInst.loc2PblockName = NameConvention::getHoriBoundaryName;
        createCellInst.loc2PblockRange = this::getPblockRangeOfHoriBoundary;
        createCellInst.setDontTouch = true;
        horiBoundaryDim.traverse(createCellInst);

        createCellInst.loc2CellInsts = vertBoundary2CellInsts;
        createCellInst.loc2CellName = NameConvention::getVertBoundaryName;
        createCellInst.loc2PblockName = NameConvention::getVertBoundaryName;
        createCellInst.loc2PblockRange = this::getPblockRangeOfVertBoundary;
        createCellInst.setDontTouch = true;
        vertBoundaryDim.traverse(createCellInst);

        connectCellInstsOfCustomCell(topCell, netlistDB.originTopCell);

        VivadoTclCmd.createClocks(design, clkName2PeriodMap);
        VivadoTclCmd.setAsyncClockGroupsForEachClk(design, clkName2PeriodMap.keySet());

        design.setAutoIOBuffers(false);
        return design;
    }

    TclCmdFile createTclFileForBoundaryDesign() {
        TclCmdFile tclFile = new TclCmdFile();

        tclFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        tclFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));

        tclFile.addCmd(VivadoTclCmd.placeDesign());
        //tclFile.addCmd(VivadoTclCmd.placeDesign(VivadoTclCmd.PlacerDirective.SpreadLogicMedium, false));
        //tclFile.addCmd(VivadoTclCmd.placeDesign(VivadoTclCmd.PlacerDirective.Quick, false));
        //tclFile.addCmd(VivadoTclCmd.placeDesign(VivadoTclCmd.PlacerDirective.RuntimeOpt, false));
        tclFile.addCmd(VivadoTclCmd.routeDesign());

        horiBoundaryDim.traverse(
            (Coordinate2D loc) -> {
                String cellName = getHoriBoundaryName(loc);
                tclFile.addCmd(VivadoTclCmd.lockDesign(false, VivadoTclCmd.LockDesignLevel.Routing, cellName));
            }
        );
        vertBoundaryDim.traverse(
            (Coordinate2D loc) -> {
                String cellName = getVertBoundaryName(loc);
                tclFile.addCmd(VivadoTclCmd.lockDesign(false, VivadoTclCmd.LockDesignLevel.Routing, cellName));
            }
        );

        tclFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));
        tclFile.addCmd(VivadoTclCmd.writeEDIF(true, null, VivadoProject.OUTPUT_EDIF_NAME));
        tclFile.addCmd(VivadoTclCmd.exitVivado());
        return tclFile;
    }

    private Design createIslandBlackboxBoundaryDesign(Design boundaryDesign, Coordinate2D islandLoc) {
        logger.info("Start creating design with blackbox boundary for island" + islandLoc.toString());


        String designName = getIslandName(islandLoc) + "_boundary";

        Design design = new Design(designName, netlistDB.partName);
        EDIFNetlist netlist = design.getNetlist();
        EDIFLibrary workLib = netlist.getWorkLibrary();
        EDIFCell topCell = netlist.getTopCell();

        EDIFCell boundaryCell = createBlackboxCell(workLib, boundaryDesign.getNetlist().getTopCell());
        boundaryCell.createCellInst("boundary", topCell);

        Set<EDIFCellInst> islandSubCellInsts = island2CellInsts[islandLoc.getX()][islandLoc.getY()];
        EDIFCell islandCell = new EDIFCell(workLib, getIslandName(islandLoc));
        copyPartialNetlistToCell(islandCell, netlistDB.originTopCell, islandSubCellInsts);
        EDIFCellInst islandCellInst = islandCell.createCellInst(getIslandName(islandLoc), topCell);
        
        connectCellInstsOfCustomCell(topCell, netlistDB.originTopCell);
        VivadoTclCmd.setPropertyDontTouch(design, islandCellInst);

        VivadoTclCmd.createClocks(design, clkName2PeriodMap);
        VivadoTclCmd.setAsyncClockGroupsForEachClk(design, clkName2PeriodMap.keySet());

        logger.info("Complete creating design with blackbox boundary for island" + islandLoc.toString());

        design.setAutoIOBuffers(false);
        return design;
    }

    private TclCmdFile createTclFileForIsland(Coordinate2D islandLoc) {
        TclCmdFile tclCmdFile = new TclCmdFile();

        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));

        Path boundaryDir = dirManager.getSubDir("boundary");
        Path boundaryDcpPath = boundaryDir.resolve(VivadoProject.OUTPUT_DCP_NAME);

        tclCmdFile.addCmd(VivadoTclCmd.readCheckpoint("boundary", boundaryDcpPath.toString()));

        String pblockName = "boundary_" + getIslandName(islandLoc); // pblock imported from boundary design
        tclCmdFile.addCmd(VivadoTclCmd.addCellToPblock(pblockName, getIslandName(islandLoc)));

        tclCmdFile.addCmd(VivadoTclCmd.placeDesign());
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign());
        tclCmdFile.addCmds(VivadoTclCmd.conditionalPhysOptDesign());

        String timingRptPath = addSuffixRpt("timing_summary");
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, timingRptPath));
        tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));
        tclCmdFile.addCmd(VivadoTclCmd.writeEDIF(true, null, VivadoProject.OUTPUT_EDIF_NAME));

        return tclCmdFile;
    }

    private Design readAndCreateMergedDesign() {
        Design design = new Design("complete", netlistDB.partName);
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        //// copy boundary cell
        Path boundaryDir = dirManager.getSubDir("boundary");
        Design boundaryDesign = Design.readCheckpoint(boundaryDir.resolve(VivadoProject.OUTPUT_DCP_NAME).toString());
        
        EDIFCell boundaryCell = boundaryDesign.getNetlist().getTopCell();
        netlist.copyCellAndSubCells(boundaryCell);
        EDIFCell newBoundaryCell = netlist.getCell(boundaryCell.getName());
        newBoundaryCell.createCellInst(boundaryCell.getName(), topCell);

        //// copy netlist of island designs
        Design[][] islandDesigns = new Design[gridDim.getX()][gridDim.getY()];
        gridDim.traverse((Coordinate2D loc) -> {
            String islandName = getIslandName(loc);
            logger.info("Reading island design of " + islandName);
            Path dcpPath = dirManager.addSubDir(islandName).resolve(VivadoProject.OUTPUT_DCP_NAME);
            Design islandDesign = Design.readCheckpoint(dcpPath.toString());
            islandDesigns[loc.getX()][loc.getY()] = islandDesign;

            EDIFCell islandCell = islandDesign.getNetlist().getCell(islandName);
            netlist.copyCellAndSubCells(islandCell);
            EDIFCell newIslandCell = netlist.getCell(islandName);
            newIslandCell.createCellInst(islandName, topCell);
        });

        connectCellInstsOfTopCell(topCell, netlistDB.originTopCell);

        // copy implementation
        DesignTools.copyImplementation(boundaryDesign, design, false, true, false, false, Map.of("boundary", "boundary"));

        gridDim.traverse((Coordinate2D loc) -> {
            String islandName = getIslandName(loc);
            Design islandDesign = islandDesigns[loc.getX()][loc.getY()];
            DesignTools.copyImplementation(islandDesign, design, false, false, false, false, Map.of(islandName, islandName));        
        });

        VivadoTclCmd.createClocks(design, clkName2PeriodMap);
        VivadoTclCmd.setAsyncClockGroupsForEachClk(design, clkName2PeriodMap.keySet());
        design.setAutoIOBuffers(false);
        return design;
    }

    private TclCmdFile createTclFileForMergeDesign() {
        TclCmdFile tclCmdFile = new TclCmdFile();
        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));

        if (designParams.isFullRouteMerge()) {
            tclCmdFile.addCmd(VivadoTclCmd.routeDesign(null, false));
        } else {
            tclCmdFile.addCmds(VivadoTclCmd.routeUnroutedNetsWithMinDelay());
        }

        String timingRptPath = addSuffixRpt("timing_summary");
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, timingRptPath));

        tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));

        return tclCmdFile;
    }


    public static void main(String[] args) {
        // String dcpFilePath = "./workspace/nvdla-small-new/merged/.dcp";
        // Design inputDesign = Design.readCheckpoint(dcpFilePath);

        // for (EDIFLibrary lib : inputDesign.getNetlist().getLibraries()) {
        //     System.out.println("Library:" + lib.getName());
        //     for (EDIFCell cell : lib.getCells()) {
        //         System.out.println("Cell:" + cell.getName());
        //     }
        // }

        Map<String, Double> clkName2PeriodMap = new HashMap<>();
        clkName2PeriodMap.put("clk1", 10.0);
        clkName2PeriodMap.put("clk2", 20.0);

        Map<String, Double> newClkName2PeriodMap = new HashMap<>(clkName2PeriodMap);
        newClkName2PeriodMap.replace("clk1", 2.0);
        newClkName2PeriodMap.replace("clk2", 30.0);

        System.out.println(clkName2PeriodMap);
        System.out.println(newClkName2PeriodMap);


        for (String clk : newClkName2PeriodMap.keySet()) {
            newClkName2PeriodMap.replace(clk, newClkName2PeriodMap.get(clk) - 1.0);            
        }

        System.out.println(clkName2PeriodMap);
        System.out.println(newClkName2PeriodMap);
    }


}
