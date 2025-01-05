package com.xilinx.rapidwright.rapidpnr;

import static com.xilinx.rapidwright.rapidpnr.NameConvention.addSuffixRpt;
import static com.xilinx.rapidwright.rapidpnr.NameConvention.getHoriBoundaryName;
import static com.xilinx.rapidwright.rapidpnr.NameConvention.getIslandName;
import static com.xilinx.rapidwright.rapidpnr.NameConvention.getVertBoundaryName;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;


import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;
import com.xilinx.rapidwright.rapidpnr.timing.SimpleTimingPredictor;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.NetlistUtils;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoProject;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.VivadoTclCmd;

public class FastParallelIslandPnR extends AbstractPhysicalImpl{

    Design completeDesign;
    SimpleTimingPredictor timingPredictor;

    public FastParallelIslandPnR(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams designParams, NetlistDatabase netlistDB) {
        super(logger, dirManager, designParams, netlistDB);
    }

    private void debugTimingPred() {
        String cellInstName = "inst_61383";
        EDIFCellInst cellInst = netlistDB.originTopCell.getCellInst(cellInstName);
        assert cellInst != null;

        for (EDIFPortInst portInst : cellInst.getPortInsts()) {
            Double recvDelay = timingPredictor.getRecvDelayOf(portInst);
            Double driveDelay = timingPredictor.getDriveDelayOf(portInst);
            Integer recvFanout = timingPredictor.getRecvFanoutOf(portInst);
            Integer driveFanout = timingPredictor.getDriveFanoutOf(portInst);
            Integer recvLogicLevel = timingPredictor.getRecvLogicLevelOf(portInst);
            Integer driveLogicLevel = timingPredictor.getDriveLogicLevelOf(portInst);

            logger.info(String.format("PortInst %s: recvDelay=%.2f, driveDelay=%.2f, recvFanout=%d, driveFanout=%d, recvLogicLevel=%d, driveLogicLevel=%d",
                portInst.getName(), recvDelay, driveDelay, recvFanout, driveFanout, recvLogicLevel, driveLogicLevel));
        }
    }

    public void run(AbstractNetlist abstractNetlist, List<Coordinate2D> abstractNodeLocs) {
        // load results of previous stages
        loadPreStepsResult(abstractNetlist, abstractNodeLocs, true);

        JobQueue jobQueue = new JobQueue();
        RuntimeTrackerTree runtimeTrackerTree = new RuntimeTrackerTree("FastParallelIslandPnR", false);
        String rootTimerName = runtimeTrackerTree.getRootRuntimeTracker();
        RuntimeTracker subTimer;
        boolean success;

        logger.info("Start running FastParallelIslandPnR");
        logger.newSubStep();

        logger.info("Start building simple timing predictor");
        subTimer = runtimeTrackerTree.createRuntimeTracker("delay predictor", rootTimerName);
        subTimer.start();
        timingPredictor = new SimpleTimingPredictor(logger, netlistDB);
        subTimer.stop();
        logger.info(String.format("Complete building simple timing predictor in %.2f sec", subTimer.getTimeInSec()));

        logger.info("Start placement of boundary cells");
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
        assert success: "Boundary placement failed";
        logger.info("Complete placement of boundary cells in " + subTimer.getTimeInSec() + " sec");


        logger.info("Start parallel PnR of islands");
        Design completeDesign = createCompleteDesign();
        gridDim.traverse((Coordinate2D loc) -> {
            Path islandPath = dirManager.addSubDir(getIslandName(loc));
            Design islandDesign = createIslandBlackboxBoundaryDesign(completeDesign, loc);
            setConstraintOnIsland(islandDesign, loc, true);
            TclCmdFile islandTclFile = createTclFileForIsland(islandDesign, loc);
            VivadoProject islandProject = new VivadoProject(islandDesign, islandPath, islandTclFile);
            Job islandJob = islandProject.createVivadoJob();
            jobQueue.addJob(islandJob);
        });

        subTimer = runtimeTrackerTree.createRuntimeTracker("Parallel Island PnR", rootTimerName);
        subTimer.start();
        success = jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success: "Parallel Island PnR failed";
        logger.info("Complete parallel PnR of islands in " + subTimer.getTimeInSec() + " sec");

        logger.info("Start merging islands and boundaries");
        Path mergePath = dirManager.addSubDir("merged");
        Design mergeDesign = readAndCreateMergedDesign();
        TclCmdFile mergeTclFile = createTclFileForMergeDesign();
        VivadoProject mergeProject = new VivadoProject(mergeDesign, mergePath, mergeTclFile);
        Job mergeJob = mergeProject.createVivadoJob();
        jobQueue.addJob(mergeJob);

        subTimer = runtimeTrackerTree.createRuntimeTracker("Merge Islands and Boundaries", rootTimerName);
        subTimer.start();
        success = jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success: "Merge Islands and Boundaries failed";
        logger.info("Complete merging islands and boundaries in " + subTimer.getTimeInSec() + " sec");

        logger.endSubStep();
        logger.info("Complete running FastParallelIslandPnR");
        logger.info(runtimeTrackerTree.toString());
    }

    private Set<EDIFCellInst>[][] buildPartialIslands() {
        Set<EDIFCellInst>[][] partialIslands = new HashSet[gridDim.getX()][gridDim.getY()];
        Integer[][] partialIslandSizes = new Integer[gridDim.getX()][gridDim.getY()];

        gridDim.traverse((Coordinate2D loc) -> {
            partialIslands[loc.getX()][loc.getY()] = new HashSet<>();
            partialIslandSizes[loc.getX()][loc.getY()] = 0;
        });

        Set<EDIFCellInst> boundaryCellInsts = new HashSet<>();
        horiBoundaryDim.traverse((Coordinate2D loc) -> {
            boundaryCellInsts.addAll(getCellInstsOfHoriBoundary(loc));
        });
        vertBoundaryDim.traverse((Coordinate2D loc) -> {
            boundaryCellInsts.addAll(getCellInstsOfVertBoundary(loc));
        });

        Set<EDIFNet> ignoredNets = new HashSet<>();
        ignoredNets.addAll(netlistDB.globalClockNets);
        ignoredNets.addAll(netlistDB.globalResetNets);
        ignoredNets.addAll(netlistDB.ignoreNets);
        ignoredNets.addAll(netlistDB.illegalNets);

        class BreadthFirstExpansion implements Consumer<Coordinate2D> {
            Set<EDIFCellInst>[][] loc2CellInsts;

            public void accept(Coordinate2D boundaryLoc) {
                Set<EDIFCellInst> visitedCellInsts = new HashSet<>();
                Map<EDIFCellInst, Integer> cellInst2DistMap = new HashMap<>();
                Set<EDIFNet> visitedNets = new HashSet<>();
                Queue<EDIFCellInst> searchCellInstQ = new LinkedList<>();
                Integer neighborSize;

                visitedCellInsts.addAll(boundaryCellInsts);
                for (EDIFCellInst cellInst : boundaryCellInsts) {
                    cellInst2DistMap.put(cellInst, 0);
                }
                visitedNets.addAll(ignoredNets);
                searchCellInstQ.addAll(loc2CellInsts[boundaryLoc.getX()][boundaryLoc.getY()]);
                neighborSize = searchCellInstQ.size();

                while (!searchCellInstQ.isEmpty()) {
                    EDIFCellInst searchCellInst = searchCellInstQ.poll();
        
                    for (EDIFPortInst searchPortInst : searchCellInst.getPortInsts()) {
                        EDIFNet expandNet = searchPortInst.getNet();
        
                        if (visitedNets.contains(expandNet)) continue;
                        if (expandNet.isGND() || expandNet.isVCC()) continue;
        
                        visitedNets.add(expandNet);
        
                        for (EDIFPortInst expandPortInst : expandNet.getPortInsts()) {
                            EDIFCellInst expandCellInst = expandPortInst.getCellInst();
        
                            if (expandCellInst == null) continue;
                            if (visitedCellInsts.contains(expandCellInst)) continue;
        
                            visitedCellInsts.add(expandCellInst);
                            cellInst2DistMap.put(expandCellInst, cellInst2DistMap.get(searchCellInst) + 1);
                            Coordinate2D loc = cellInst2IslandLocMap.get(expandCellInst);
                            assert loc != null;
        
                            //if (neighborSize < designParams.getBoundaryNeighborSize()) {
                            if (cellInst2DistMap.get(expandCellInst) <= 1) {
                                partialIslands[loc.getX()][loc.getY()].add(expandCellInst);
        
                                Integer primCellNum = NetlistUtils.getLeafCellNum(expandCellInst.getCellType());
                                neighborSize += primCellNum;
        
                                searchCellInstQ.add(expandCellInst);
                            }

                            // if (!NetlistUtils.isRegisterCellInst(expandCellInst)) {
                            //     partialIslands[loc.getX()][loc.getY()].add(expandCellInst);
                            //     searchCellInstQ.add(expandCellInst);
                            // } else {
                            //     partialIslands[loc.getX()][loc.getY()].add(expandCellInst);
                            // }
                        }
                    }
                }
            }
        }

        BreadthFirstExpansion expandBoundary = new BreadthFirstExpansion();
        expandBoundary.loc2CellInsts = horiBoundary2CellInsts;
        horiBoundaryDim.traverse(expandBoundary);

        expandBoundary.loc2CellInsts = vertBoundary2CellInsts;
        vertBoundaryDim.traverse(expandBoundary);

        return partialIslands;
    }

    private Design createBoundaryDesign() {
        Design design = new Design("boundary", netlistDB.partName);
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        Set<EDIFCellInst>[][] partialIslands;
        //Integer[][] partialIslandSizes = new Integer[gridDim.getX()][gridDim.getY()];
        // Queue<EDIFCellInst> searchCellInstQ = new LinkedList<>();
        // Set<EDIFCellInst> visitedCellInsts = new HashSet<>();
        // Set<EDIFNet> visitedNets = new HashSet<>();

        // // initialize partialIslands
        // gridDim.traverse((Coordinate2D loc) -> {
        //     partialIslands[loc.getX()][loc.getY()] = new HashSet<>();
        //     partialIslandSizes[loc.getX()][loc.getY()] = 0;
        // });

        // horiBoundaryDim.traverse(
        //     (Coordinate2D loc) -> {
        //         searchCellInstQ.addAll(getCellInstsOfHoriBoundary(loc));
        //         visitedCellInsts.addAll(getCellInstsOfHoriBoundary(loc));
        //     }
        // );

        // vertBoundaryDim.traverse(
        //     (Coordinate2D loc) -> {
        //         searchCellInstQ.addAll(getCellInstsOfVertBoundary(loc));
        //         visitedCellInsts.addAll(getCellInstsOfVertBoundary(loc));
        //     }
        // );

        // visitedNets.addAll(netlistDB.globalClockNets);
        // visitedNets.addAll(netlistDB.globalResetNets);
        // visitedNets.addAll(netlistDB.ignoreNets);
        // visitedNets.addAll(netlistDB.illegalNets);

        // while (!searchCellInstQ.isEmpty()) {
        //     EDIFCellInst searchCellInst = searchCellInstQ.poll();

        //     for (EDIFPortInst searchPortInst : searchCellInst.getPortInsts()) {
        //         EDIFNet expandNet = searchPortInst.getNet();

        //         if (visitedNets.contains(expandNet)) continue;
        //         if (expandNet.isGND() || expandNet.isVCC()) continue;

        //         visitedNets.add(expandNet);

        //         for (EDIFPortInst expandPortInst : expandNet.getPortInsts()) {
        //             EDIFCellInst expandCellInst = expandPortInst.getCellInst();

        //             if (expandCellInst == null) continue;
        //             if (visitedCellInsts.contains(expandCellInst)) continue;

        //             visitedCellInsts.add(expandCellInst);
        //             Coordinate2D loc = cellInst2IslandLocMap.get(expandCellInst);
        //             assert loc != null;

        //             Set<EDIFCellInst> partialIsland = partialIslands[loc.getX()][loc.getY()];
        //             if (partialIslandSizes[loc.getX()][loc.getY()] < designParams.getBoundaryNeighborSize()) {
        //                 partialIsland.add(expandCellInst);

        //                 Integer primCellNum = NetlistUtils.getLeafCellNum(expandCellInst.getCellType());
        //                 partialIslandSizes[loc.getX()][loc.getY()] += primCellNum;

        //                 // searchCellInstQ.add(expandCellInst);
        //             }
        //             // island2PartialCellInsts[loc.getX()][loc.getY()].add(expandCellInst);
        //             // Boolean isBoundaryCell = cellInst2VertBoundaryLocMap.containsKey(expandCellInst) ||
        //             //                            cellInst2HoriBoundaryLocMap.containsKey(expandCellInst);
        //             // Boolean isRegCell = NetlistUtils.isRegisterCellInst(expandCellInst);
        //             // if (isBoundaryCell || !isRegCell) {
        //             //     searchCellInstQ.add(expandCellInst);
        //             // }
        //             //searchCellInstQ.add(expandCellInst);
        //             searchCellInstQ.add(expandCellInst);
        //         }
        //     }
        // }

        // class CreateCellInst implements Consumer<Coordinate2D> {

        //     public Set<EDIFCellInst>[][] loc2CellInsts;
        //     public Function<Coordinate2D, String> loc2CellName;
        //     public Function<Coordinate2D, String> loc2PblockRange = null;
        //     public Boolean setDontTouch = false;
            
        //     public void accept(Coordinate2D loc) {
        //         String cellName = loc2CellName.apply(loc);

        //         Set<EDIFCellInst> subCellInsts = loc2CellInsts[loc.getX()][loc.getY()];
        //         if (subCellInsts.isEmpty()) return;

        //         EDIFCell newCell = new EDIFCell(workLib, cellName);
        //         copyPartialNetlistToCell(newCell, netlistDB.originTopCell, subCellInsts);

        //         EDIFCellInst cellInst = newCell.createCellInst(cellName, topCell);
        //         if (loc2PblockRange != null) {
        //             String pblockRange = loc2PblockRange.apply(loc);
        //             VivadoTclCmd.addStrictCellPblockConstr(design, cellInst, pblockRange);    
        //         }

        //         if (setDontTouch) {
        //             VivadoTclCmd.setPropertyDontTouch(design, cellInst);
        //         }
        //     }
        // }

        partialIslands = buildPartialIslands();

        CreateCellInstOfLoc createCellInst = new CreateCellInstOfLoc();
        createCellInst.design = design;

        createCellInst.loc2CellInsts = partialIslands;
        createCellInst.loc2CellName = NameConvention::getIslandName;
        createCellInst.loc2PblockRange = this::getPblockRangeOfIsland;
        gridDim.traverse(createCellInst);

        createCellInst.loc2CellInsts = horiBoundary2CellInsts;
        createCellInst.loc2CellName = NameConvention::getHoriBoundaryName;
        createCellInst.loc2PblockRange = this::getPblockRangeOfHoriBoundary;
        createCellInst.setDontTouch = true;
        horiBoundaryDim.traverse(createCellInst);

        createCellInst.loc2CellInsts = vertBoundary2CellInsts;
        createCellInst.loc2CellName = NameConvention::getVertBoundaryName;
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

        //tclFile.addCmd(VivadoTclCmd.placeDesign(VivadoTclCmd.PlacerDirective.SpreadLogicHigh, false));
        //tclFile.addCmd(VivadoTclCmd.placeDesign(VivadoTclCmd.PlacerDirective.SpreadLogicMedium, false));
        //tclFile.addCmd(VivadoTclCmd.placeDesign(VivadoTclCmd.PlacerDirective.Quick, false));
        //tclFile.addCmd(VivadoTclCmd.placeDesign(VivadoTclCmd.PlacerDirective.RuntimeOpt, false));
        tclFile.addCmd(VivadoTclCmd.placeDesign());

        tclFile.addCmd(VivadoTclCmd.deletePblock());

        horiBoundaryDim.traverse((Coordinate2D loc) -> {
                String cellName = getHoriBoundaryName(loc);
                if (isHoriBoundaryExist(loc)) {
                    tclFile.addCmd(VivadoTclCmd.writeCheckpoint(true, cellName, cellName + ".dcp"));
                }
            }
        );

        vertBoundaryDim.traverse((Coordinate2D loc) -> {
                String cellName = getVertBoundaryName(loc);
                if (isVertBoundaryExist(loc)) {
                    tclFile.addCmd(VivadoTclCmd.writeCheckpoint(true, cellName, cellName + ".dcp"));
                }
            }
        );

        tclFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));
        tclFile.addCmd(VivadoTclCmd.writeEDIF(true, null, VivadoProject.OUTPUT_EDIF_NAME));
        tclFile.addCmd(VivadoTclCmd.exitVivado());
        return tclFile;
    }

    private Design createIslandBlackboxBoundaryDesign(Design completeDesign, Coordinate2D islandLoc) {
        logger.info("Start creating design with blackbox boundary for island" + islandLoc.toString());
        logger.newSubStep();

        String designName = getIslandName(islandLoc) + "_boundary";

        Design design = new Design(designName, netlistDB.partName);
        EDIFNetlist netlist = design.getNetlist();
        EDIFLibrary workLib = netlist.getWorkLibrary();
        EDIFCell topCell = netlist.getTopCell();

        EDIFCell islandCell = completeDesign.getNetlist().getCell(getIslandName(islandLoc));
        netlist.copyCellAndSubCells(islandCell);
        EDIFCell newIslandCell = netlist.getCell(getIslandName(islandLoc));
        newIslandCell.createCellInst(getIslandName(islandLoc), topCell);

        horiBoundaryDim.traverse((Coordinate2D loc) -> {
            if (!isHoriBoundaryExist(loc)) return; // skip if no anchor cells in this boundary

            if (isNeighborHoriBoundary(islandLoc, loc)) {
                String cellName = getHoriBoundaryName(loc);
                EDIFCell boundaryCell = completeDesign.getNetlist().getCell(cellName);
                EDIFCell newBoundaryCell = createBlackboxCell(workLib, boundaryCell);
                newBoundaryCell.createCellInst(cellName, topCell);
            }
        });

        vertBoundaryDim.traverse((Coordinate2D loc) -> {
            if (!isVertBoundaryExist(loc)) return; // skip if no anchor cells in this boundary

            if (isNeighborVertBoundary(islandLoc, loc)) {
                String cellName = getVertBoundaryName(loc);
                EDIFCell boundaryCell = completeDesign.getNetlist().getCell(cellName);
                EDIFCell newBoundaryCell = createBlackboxCell(workLib, boundaryCell);
                newBoundaryCell.createCellInst(cellName, topCell);
            }
        });
        
        connectCellInstsOfCustomCell(topCell, netlistDB.originTopCell);

        // Design design = createIslandDesignWithBoundary(islandLoc);

        // horiBoundaryDim.traverse((Coordinate2D loc) -> {
        //     String cellName = getHoriBoundaryName(loc);
        //     EDIFCellInst cellInst = design.getNetlist().getTopCell().getCellInst(cellName);
        //     if (cellInst != null) {
        //         DesignTools.makeBlackBox(design, cellName);
        //     }
        // });

        // vertBoundaryDim.traverse((Coordinate2D loc) -> {
        //     String cellName = getVertBoundaryName(loc);
        //     EDIFCellInst cellInst = design.getNetlist().getTopCell().getCellInst(cellName);
        //     if (cellInst != null) {
        //         DesignTools.makeBlackBox(design, cellName);
        //     }
        // });

        logger.endSubStep();
        logger.info("Complete creating design with blackbox boundary for island" + islandLoc.toString());

        design.setAutoIOBuffers(false);
        return design;
    }

    private void setConstraintOnIsland(Design design, Coordinate2D islandLoc, boolean islandDontTouch) {
        EDIFCell topCell = design.getNetlist().getTopCell();
        String islandName = getIslandName(islandLoc);
        EDIFCellInst islandCellInst = topCell.getCellInst(islandName);
        assert islandCellInst != null;

        VivadoTclCmd.addStrictCellPblockConstr(design, islandCellInst, getPblockRangeOfIsland(islandLoc));
        if (islandDontTouch) {
            VivadoTclCmd.setPropertyDontTouch(design, islandCellInst);
        }

        horiBoundaryDim.traverse((Coordinate2D loc) -> {
            EDIFCellInst cellInst = topCell.getCellInst(getHoriBoundaryName(loc));
            String pblockRange = getPblockRangeOfHoriBoundary(loc);
            if (cellInst != null) {
                VivadoTclCmd.addStrictCellPblockConstr(design, cellInst, pblockRange);
            }
        });

        vertBoundaryDim.traverse((Coordinate2D loc) -> {
            EDIFCellInst cellInst = topCell.getCellInst(getVertBoundaryName(loc));
            String pblockRange = getPblockRangeOfVertBoundary(loc);
            if (cellInst != null) {
                VivadoTclCmd.addStrictCellPblockConstr(design, cellInst, pblockRange);
            }
        });


        Map<String, Double> newClkName2PeriodMap = new HashMap<>(clkName2PeriodMap);
        for (String clkName : newClkName2PeriodMap.keySet()) {
            Double period = newClkName2PeriodMap.get(clkName) - designParams.getIslandPeriodDecrement();
            newClkName2PeriodMap.replace(clkName, period);
        }
        VivadoTclCmd.createClocks(design, newClkName2PeriodMap);
        VivadoTclCmd.setAsyncClockGroupsForEachClk(design, clkName2PeriodMap.keySet());
    }

    private TclCmdFile createTclFileForIsland(Design islandDesign, Coordinate2D islandLoc) {

        TclCmdFile tclCmdFile = new TclCmdFile();

        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));

        Path boundaryDir = dirManager.getSubDir("boundary");
        EDIFCell islandTopCell = islandDesign.getNetlist().getTopCell();
        Map<String, Path> cellInst2DcpFilePathMap = new HashMap<>();

        horiBoundaryDim.traverse((Coordinate2D loc) -> {
            String cellName = getHoriBoundaryName(loc);
            EDIFCellInst cellInst = islandTopCell.getCellInst(cellName);
            if (cellInst != null) {
                Path dcpPath = boundaryDir.resolve(cellName + ".dcp");
                cellInst2DcpFilePathMap.put(cellName, dcpPath);
            }
        });

        vertBoundaryDim.traverse((Coordinate2D loc) -> {
            String cellName = getVertBoundaryName(loc);
            EDIFCellInst cellInst = islandTopCell.getCellInst(cellName);
            if (cellInst != null) {
                Path dcpPath = boundaryDir.resolve(cellName + ".dcp");
                cellInst2DcpFilePathMap.put(cellName, dcpPath);
            }
        });

        tclCmdFile.addCmd(VivadoTclCmd.readCheckpoint(cellInst2DcpFilePathMap));

        // set input/output delay constraints
        tclCmdFile.addCmds(getIODelayConstraints(islandDesign, islandLoc));

        for (String cellName : cellInst2DcpFilePathMap.keySet()) {
            // tclCmdFile.addCmd(VivadoTclCmd.lockDesign(false, VivadoTclCmd.LockDesignLevel.Placement, cellName));
            String target = VivadoTclCmd.getCells(cellName + "/*");
            tclCmdFile.addCmd(VivadoTclCmd.setProperty("IS_LOC_FIXED", "true", target));
        }

        tclCmdFile.addCmd(VivadoTclCmd.placeDesign());
        //tclCmdFile.addCmd(VivadoTclCmd.placeDesign(VivadoTclCmd.PlacerDirective.SpreadLogicMedium, false));
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign());
        //tclCmdFile.addCmds(VivadoTclCmd.conditionalPhysOptDesign());

        String timingRptPath = addSuffixRpt("timing_summary");
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, timingRptPath));
        tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));
        tclCmdFile.addCmd(VivadoTclCmd.writeEDIF(true, null, VivadoProject.OUTPUT_EDIF_NAME));

        return tclCmdFile;
    }

    private List<String> getIODelayConstraints(Design islandDesign, Coordinate2D islandLoc) {
        List<String> ioDelayConstraints = new ArrayList<>();
        EDIFCell islandTopCell = islandDesign.getNetlist().getTopCell();

        Double mainClockPeriod = designParams.getClkPeriod(designParams.getMainClkName());
        mainClockPeriod -= designParams.getIslandPeriodDecrement();

        Set<EDIFCellInst> boundaryCellInsts = new HashSet<>();
        horiBoundaryDim.traverse((Coordinate2D loc) -> {
            if (isNeighborHoriBoundary(islandLoc, loc)) {
                boundaryCellInsts.addAll(getCellInstsOfHoriBoundary(loc));
            }
        });
        vertBoundaryDim.traverse((Coordinate2D loc) -> {
            if (isNeighborVertBoundary(islandLoc, loc)) {
                boundaryCellInsts.addAll(getCellInstsOfVertBoundary(loc));
            }
        });

        for (EDIFPort port : islandTopCell.getPorts()) { // check all ports
            EDIFNet originNet = netlistDB.originTopCell.getNet(port.getName());
            assert originNet != null;
            if (netlistDB.isGlobalClockNet(originNet)) continue;
            if (netlistDB.isGlobalResetNet(originNet)) continue;
            if (netlistDB.isIgnoreNet(originNet)) continue;

            if (port.isOutput()) {
                EDIFCellInst originSrcCell = NetlistUtils.getSourceCellInstOfNet(originNet);
                assert originSrcCell != null;
                if (!boundaryCellInsts.contains(originSrcCell)) continue;
                if (NetlistUtils.isRegisterCellInst(originSrcCell)) continue;
                assert NetlistUtils.isLutCellInst(originSrcCell);

                EDIFPortInst originSrcPortInst = NetlistUtils.getOutPortInstsOf(originSrcCell).get(0);
                Double estimatedDelay = timingPredictor.predictOutputDelayOf(originSrcPortInst, mainClockPeriod);
                ioDelayConstraints.add(VivadoTclCmd.addIODelayConstraint(port, designParams.getMainClkName(), estimatedDelay));
            } else {
                Double estimatedDelay = -1.0;

                for (EDIFPortInst portInst : originNet.getPortInsts()) {
                    EDIFCellInst originSinkCell = portInst.getCellInst();
                    if (originSinkCell == null) continue;

                    if (!boundaryCellInsts.contains(originSinkCell)) continue;
                    if (NetlistUtils.isRegisterCellInst(originSinkCell)) continue;
                    assert NetlistUtils.isLutCellInst(originSinkCell);
                    assert portInst.isInput();

                    EDIFPortInst originSinkPortInst = originSinkCell.getPortInst(portInst.getName());
                    Double delay = timingPredictor.predictInputDelayOf(originSinkPortInst, mainClockPeriod);
                    if (estimatedDelay == -1.0) {
                        estimatedDelay = delay;
                    } else {
                        estimatedDelay = Math.min(estimatedDelay, delay);
                    }
                }

                if (estimatedDelay != -1.0) {
                    ioDelayConstraints.add(VivadoTclCmd.addIODelayConstraint(port, designParams.getMainClkName(), estimatedDelay));
                }
            }
        }
        return ioDelayConstraints;
    }

    private Design readAndCreateMergedDesign() {
        Design design = new Design("complete", netlistDB.partName);
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        Map<String, String> boundaryCellNamesMap = new HashMap<>();
        // copy netlist

        //// copy boundary cell netlist
        Path boundaryDir = dirManager.getSubDir("boundary");
        Design boundaryDesign = Design.readCheckpoint(boundaryDir.resolve(VivadoProject.OUTPUT_DCP_NAME).toString());
        horiBoundaryDim.traverse((Coordinate2D loc) -> {
            String cellName = getHoriBoundaryName(loc);
            EDIFCell cellType = boundaryDesign.getNetlist().getCell(cellName);
            if (cellType == null) return; // skip if boundary cell doesn't exist

            netlist.copyCellAndSubCells(cellType);
            EDIFCell newCellType = netlist.getCell(cellName);
            newCellType.createCellInst(cellName, topCell);
            boundaryCellNamesMap.put(cellName, cellName);
        });

        vertBoundaryDim.traverse((Coordinate2D loc) -> {
            String cellName = getVertBoundaryName(loc);
            EDIFCell cellType = boundaryDesign.getNetlist().getCell(cellName);
            if (cellType == null) return; // skip if boundary cell doesn't exist

            netlist.copyCellAndSubCells(cellType);
            EDIFCell newCellType = netlist.getCell(cellName);
            newCellType.createCellInst(cellName, topCell);
            boundaryCellNamesMap.put(cellName, cellName);
        });

        //// copy netlist of island designs
        Design[][] islandDesigns = new Design[gridDim.getX()][gridDim.getY()];
        gridDim.traverse((Coordinate2D loc) -> {
            String islandName = getIslandName(loc);
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
        DesignTools.copyImplementation(boundaryDesign, design, false, true, false, false, boundaryCellNamesMap);

        gridDim.traverse((Coordinate2D loc) -> {
            String islandName = getIslandName(loc);
            Design islandDesign = islandDesigns[loc.getX()][loc.getY()];
            DesignTools.copyImplementation(islandDesign, design, false, true, false, false, Map.of(islandName, islandName));        
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
