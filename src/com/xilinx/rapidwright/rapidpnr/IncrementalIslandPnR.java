package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;

import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.VivadoTclCmd;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;
import com.xilinx.rapidwright.util.JobQueue;

import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.design.Design;

import static com.xilinx.rapidwright.rapidpnr.NameConvention.*;


public class IncrementalIslandPnR {
    private HierarchicalLogger logger;
    private DirectoryManager dirManager;
    
    // origin netlist database
    private NetlistDatabase netlistDB;

    // Abstract Netlist Info
    AbstractNetlist abstractNetlist;

    // Island Placement Results
    List<Coordinate2D> groupLocs;

    
    private Set<EDIFCellInst>[][] island2CellInsts;
    private Map<EDIFCellInst, Coordinate2D> cellInst2IslandLocMap;

    private Set<EDIFNet>[][] vertBoundary2Nets;
    private Set<EDIFNet>[][] horiBoundary2Nets;
    private Map<EDIFNet, Coordinate2D> net2vertBoundaryLocMap;
    private Map<EDIFNet, Coordinate2D> net2horiBoundaryLocMap;

    private Set<EDIFCellInst>[][] vertBoundary2CellInsts;
    private Set<EDIFCellInst>[][] horiBoundary2CellInsts;
    private Map<EDIFCellInst, Coordinate2D> cellInst2VertBoundaryLocMap;
    private Map<EDIFCellInst, Coordinate2D> cellInst2HoriBoundaryLocMap;

    // Design Parameters
    ////
    String clkPortName; // TODO: only support single clock design
    String resetPortName; // TODO: only support single reset design
    Double clkPeriod;

    String designName;

    //// Layout Informatio
    private Coordinate2D gridDim;
    private Coordinate2D vertBoundaryDim;
    private Coordinate2D horiBoundaryDim;
    private Map<String, String> pblockName2RangeMap;


    public IncrementalIslandPnR(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams designParams, NetlistDatabase netlistDB) {
        this.logger = logger;
        this.dirManager = dirManager;

        // Parse Design Parameters

        designName = designParams.getDesignName();

        gridDim = designParams.getGridDim();
        vertBoundaryDim = designParams.getVertBoundaryDim();
        horiBoundaryDim = designParams.getHoriBoundaryDim();
        pblockName2RangeMap = designParams.getPblockName2RangeMap();

        // Netlist Database
        this.netlistDB = netlistDB;
        clkPortName = netlistDB.clkPorts.iterator().next().getName();
        resetPortName = netlistDB.resetPorts.iterator().next().getName();
        clkPeriod = designParams.getClkPeriod(clkPortName);
    }

    public void run() {
        logger.info("Start running Parallel Iterative PnR flow");
        logger.newSubStep();

        //
        Coordinate2D island0Loc = Coordinate2D.of(0, 0);
        Path island0WorkDir = dirManager.addSubDir(getIslandName(island0Loc));
        
        Design island0 = createIslandDesignWithBoundary(new Coordinate2D(0, 0));
        TclCmdFile island0TclCmdFile = createTclCmdFileForIslandImpl(island0Loc, null, true);
        VivadoProject island0Project = new VivadoProject(island0, island0WorkDir, island0TclCmdFile);
        Job island0Job = island0Project.createVivadoJob();

        //
        Coordinate2D island3Loc = Coordinate2D.of(1, 1);
        Path island3WorkDir = dirManager.addSubDir(getIslandName(new Coordinate2D(1, 1)));
        
        Design island3 = createIslandDesignWithBoundary(island3Loc);
        TclCmdFile island3TclCmdFile = createTclCmdFileForIslandImpl(island3Loc, null, true);
        VivadoProject island3Project = new VivadoProject(island3, island3WorkDir, island3TclCmdFile);
        Job island3Job = island3Project.createVivadoJob();

        //
        Coordinate2D island1Loc = Coordinate2D.of(1, 0);
        Path island1WorkDir = dirManager.addSubDir(getIslandName(new Coordinate2D(1, 0)));
        
        Design island1 = createIslandDesignWithContext(island1Loc, Arrays.asList(island0, island3), false);
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

    public void runParallelAndComplete() {

        logger.info("Start running Parallel and then Complete PnR flow");
        logger.newSubStep();

        Map<Path, Design> path2DesignMap = new HashMap<>();
        JobQueue jobQueue = new JobQueue();
        RuntimeTrackerTree runTimeTrackerTree = new RuntimeTrackerTree("Parallel&Complete Flow", false);
        String rootTimerName = runTimeTrackerTree.getRootRuntimeTracker();

        // island_0_0
        Coordinate2D island0Loc = Coordinate2D.of(0, 0);
        Path island0WorkDir = dirManager.addSubDir(getIslandName(island0Loc));
        Design island0 = createIslandDesignWithBoundary(new Coordinate2D(0, 0));
        path2DesignMap.put(island0WorkDir, island0);
        TclCmdFile island0TclCmdFile = createTclCmdFileForIslandImpl(island0Loc, null, true);
        VivadoProject island0Project = new VivadoProject(island0, island0WorkDir, island0TclCmdFile);
        Job island0Job = island0Project.createVivadoJob();

        // island_1_1
        Coordinate2D island3Loc = Coordinate2D.of(1, 1);
        Path island3WorkDir = dirManager.addSubDir(getIslandName(new Coordinate2D(1, 1)));
        Design island3 = createIslandDesignWithBoundary(island3Loc);
        path2DesignMap.put(island3WorkDir, island3);
        TclCmdFile island3TclCmdFile = createTclCmdFileForIslandImpl(island3Loc, null, true);
        VivadoProject island3Project = new VivadoProject(island3, island3WorkDir, island3TclCmdFile);
        Job island3Job = island3Project.createVivadoJob();

        // complete design
        List<Coordinate2D> islandLocs = Arrays.asList(Coordinate2D.of(0, 1), Coordinate2D.of(1, 0));
        Path completeWorkDir = dirManager.addSubDir("complete");
        Design completeDesign = createCompleteDesignWithContext(islandLocs, path2DesignMap.values());
        TclCmdFile completeTclCmdFile = createTclCmdFileForIslandImpl(null, path2DesignMap, false);
        VivadoProject completeProject = new VivadoProject(completeDesign, completeWorkDir, completeTclCmdFile);

        
        RuntimeTracker subTimer = runTimeTrackerTree.createRuntimeTracker("island0&3", rootTimerName);
        jobQueue.addJob(island0Job);
        jobQueue.addJob(island3Job);

        subTimer.start();
        boolean success = jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success: "run parallel Vivado impls of island0 and island3 failed";
        logger.info("Complete running parallel Vivado impls of island0 and island3");
        logger.info(subTimer.toString());

        subTimer = runTimeTrackerTree.createRuntimeTracker("complete", rootTimerName);
        jobQueue.addJob(completeProject.createVivadoJob());
        subTimer.start();
        success = jobQueue.runAllToCompletion();
        subTimer.stop();
        assert success: "run Vivado impls of complete design failed";
        logger.info("Complete running Vivado impls of complete design");
        logger.info(subTimer.toString());


        logger.endSubStep();

        logger.info("Complete running Parallel and then Complete PnR flow");
        logger.info(runTimeTrackerTree.toString());
    }

    public void runCompleteDesign() {
        logger.info("Start running PnR flow for floorplanned complete design");
        logger.newSubStep();

        Path workDir = dirManager.addSubDir("complete");
        Design completeDesign = createCompleteDesign();

        // create tcl comand file
        TclCmdFile tclCmdFile = new TclCmdFile();
        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));
        tclCmdFile.addCmd(VivadoTclCmd.placeDesign(null));
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign(null));
        tclCmdFile.addCmds(VivadoTclCmd.conditionalPhysOptDesign());
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, "timing.rpt"));
        tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(false, null, VivadoProject.OUTPUT_DCP_NAME));

        VivadoProject vivadoProject = new VivadoProject(completeDesign, workDir, tclCmdFile);
        Job vivadoJob = vivadoProject.createVivadoJob();

        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                Design islanDesign = createIslandDesign(Coordinate2D.of(x, y));
                Path dcpPath = workDir.resolve(getIslandName(Coordinate2D.of(x, y)) + ".dcp");
                islanDesign.writeCheckpoint(dcpPath.toString());
            }
        }

        JobQueue jobQueue = new JobQueue();
        jobQueue.addJob(vivadoJob);
        jobQueue.runAllToCompletion();

        logger.endSubStep();
        logger.info("Complete running PnR flow for floorplanned complete design");
    }

    public void loadPreStepsResult(AbstractNetlist abstractNetlist, List<Coordinate2D> groupLocs) {
        this.abstractNetlist = abstractNetlist;
        this.groupLocs = groupLocs;

        // Build cellInsts to island map
        buildCellInst2IslandMap();

        // Build net to boundary map
        buildNet2BoundaryMap();

        // Build cellInst to boundary map
        buildCellInst2BoundaryMap();
    }

    private void buildCellInst2IslandMap() {
        logger.info("Start building cellInst to island map");
        logger.newSubStep();
        // Setup 2-D array for cell mapping
        island2CellInsts = new HashSet[gridDim.getX()][gridDim.getY()];
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                island2CellInsts[x][y] = new HashSet<>();
            }
        }
        cellInst2IslandLocMap = new HashMap<>();

        for (int i = 0; i < abstractNetlist.getGroupNum(); i++) {
            Coordinate2D islandLoc = groupLocs.get(i);
            Set<EDIFCellInst> cellInstSet = abstractNetlist.getCellInstsOfGroup(i);
            for (EDIFCellInst cellInst : cellInstSet) {
                assert getCellInstsOfIsland(islandLoc).contains(cellInst) == false;
                island2CellInsts[islandLoc.getX()][islandLoc.getY()].add(cellInst);
                cellInst2IslandLocMap.put(cellInst, islandLoc);
            }
        }

        // Print cell distribution
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                logger.info(String.format("The number of cellInsts in island(%d, %d): %d", x, y, island2CellInsts[x][y].size()));
            }
        }

        logger.endSubStep();
        logger.info("Complete building cellInst to region map");
    }

    private void buildNet2BoundaryMap() {
        logger.info("Start building net to boundary map");
        logger.newSubStep();

        vertBoundary2Nets = new HashSet[vertBoundaryDim.getX()][vertBoundaryDim.getY()];
        horiBoundary2Nets = new HashSet[horiBoundaryDim.getX()][horiBoundaryDim.getY()];

        for (int x = 0; x < vertBoundaryDim.getX(); x++) {
            for (int y = 0; y < vertBoundaryDim.getY(); y++) {
                vertBoundary2Nets[x][y] = new HashSet<>();
            }
        }

        for (int x = 0; x < horiBoundaryDim.getX(); x++) {
            for (int y = 0; y < horiBoundaryDim.getY(); y++) {
                horiBoundary2Nets[x][y] = new HashSet<>();
            }
        }

        net2horiBoundaryLocMap = new HashMap<>();
        net2vertBoundaryLocMap = new HashMap<>();

        for (EDIFNet net : netlistDB.originTopCell.getNets()) {
            Set<Coordinate2D> netIncidentPortLocs = new HashSet<>();
            if (net.isGND() || net.isVCC()) continue;
            if (netlistDB.isGlobalClockNet(net) || netlistDB.isGlobalResetNet(net)) continue;
            if (netlistDB.isIgnoreNet(net)) continue;

            for (EDIFPortInst portInst : net.getPortInsts()) {
                EDIFCellInst cellInst = portInst.getCellInst();
                if (cellInst == null) continue; // Skip top-level ports

                assert cellInst2IslandLocMap.containsKey(cellInst);
                netIncidentPortLocs.add(cellInst2IslandLocMap.get(cellInst));
            }

            assert netIncidentPortLocs.size() <= 2;
            if (netIncidentPortLocs.size() == 2) {
                Iterator<Coordinate2D> iter = netIncidentPortLocs.iterator();
                Coordinate2D loc0 = iter.next();
                Coordinate2D loc1 = iter.next();
                Integer xDist = loc0.getDistX(loc1);
                Integer yDist = loc0.getDistY(loc1);
                assert xDist + yDist == 1;
                if (xDist == 1) {
                    Integer boundaryX = Math.min(loc0.getX(), loc1.getX());
                    Integer boundaryY = loc0.getY();
                    vertBoundary2Nets[boundaryX][boundaryY].add(net);
                    net2vertBoundaryLocMap.put(net, new Coordinate2D(xDist, yDist));
                } else {
                    Integer boundaryX = loc0.getX();
                    Integer boundaryY = Math.min(loc0.getY(), loc1.getY());
                    horiBoundary2Nets[boundaryX][boundaryY].add(net);
                    net2horiBoundaryLocMap.put(net, new Coordinate2D(xDist, yDist));
                }
            }
        }

        int totalNumOfBoundaryNet = 0;
        logger.info("Vertical Boundary Net Count:");
        logger.newSubStep();
        for (int x = 0; x < vertBoundaryDim.getX(); x++) {
            for (int y = 0; y < vertBoundaryDim.getY(); y++) {
                logger.info(String.format("The number of nets on vert boundary(%d, %d): %d", x, y, vertBoundary2Nets[x][y].size()));
                totalNumOfBoundaryNet += vertBoundary2Nets[x][y].size();
            }
        }
        logger.endSubStep();

        logger.info("Horizontal Boundary Net Count:");
        logger.newSubStep();
        for (int x = 0; x < horiBoundaryDim.getX(); x++) {
            for (int y = 0; y < horiBoundaryDim.getY(); y++) {
                logger.info(String.format("The number of nets on hori boundary(%d, %d): %d", x, y, horiBoundary2Nets[x][y].size()));
                totalNumOfBoundaryNet += horiBoundary2Nets[x][y].size();
            }
        }
        logger.endSubStep();

        logger.info("Total number of boundary nets: " + totalNumOfBoundaryNet);

        logger.endSubStep();
        logger.info("Complete building net to boundary map");
    }

    private void buildCellInst2BoundaryMap() {
        logger.info("Start building cellInst to boundary map");
        vertBoundary2CellInsts = new HashSet[vertBoundaryDim.getX()][vertBoundaryDim.getY()];
        horiBoundary2CellInsts = new HashSet[horiBoundaryDim.getX()][horiBoundaryDim.getY()];

        for (int x = 0; x < vertBoundaryDim.getX(); x++) {
            for (int y = 0; y < vertBoundaryDim.getY(); y++) {
                vertBoundary2CellInsts[x][y] = new HashSet<>();
            }
        }

        for (int x = 0; x < horiBoundaryDim.getX(); x++) {
            for (int y = 0; y < horiBoundaryDim.getY(); y++) {
                horiBoundary2CellInsts[x][y] = new HashSet<>();
            }
        }

        cellInst2HoriBoundaryLocMap = new HashMap<>();
        cellInst2VertBoundaryLocMap = new HashMap<>();

        // build cellInst to vertical boundary map
        for (int x = 0; x < vertBoundaryDim.getX(); x++) {
            for (int y = 0; y < vertBoundaryDim.getY(); y++) {
                for (EDIFNet net : vertBoundary2Nets[x][y]) {
                    EDIFCellInst srcCellInst = NetlistUtils.getSourceCellInstOfNet(net);
                    assert srcCellInst != null;
                    assert NetlistUtils.isRegisterCellInst(srcCellInst);
                    // remove cellInst from island and then insert it into boundary
                    Coordinate2D loc = cellInst2IslandLocMap.get(srcCellInst);
                    island2CellInsts[loc.getX()][loc.getY()].remove(srcCellInst);
                    cellInst2IslandLocMap.remove(srcCellInst);

                    vertBoundary2CellInsts[x][y].add(srcCellInst);
                    cellInst2VertBoundaryLocMap.put(srcCellInst, new Coordinate2D(x, y));
                }
            }
        }

        // build cellInst to horizontal boundary map
        for (int x = 0; x < horiBoundaryDim.getX(); x++) {
            for (int y = 0; y < horiBoundaryDim.getY(); y++) {
                for (EDIFNet net : horiBoundary2Nets[x][y]) {
                    EDIFCellInst srcCellInst = NetlistUtils.getSourceCellInstOfNet(net);
                    assert srcCellInst != null;
                    assert NetlistUtils.isRegisterCellInst(srcCellInst);
                    // remove cellInst from island and then insert it into boundary
                    Coordinate2D loc = cellInst2IslandLocMap.get(srcCellInst);
                    island2CellInsts[loc.getX()][loc.getY()].remove(srcCellInst);
                    cellInst2IslandLocMap.remove(srcCellInst);

                    horiBoundary2CellInsts[x][y].add(srcCellInst);
                    cellInst2HoriBoundaryLocMap.put(srcCellInst, new Coordinate2D(x, y));
                }
            }
        }
        logger.info("Complete building cellInst to boundary map");
    }

    private Design createIslandDesign(Coordinate2D loc) {
        logger.info("Start creating design for island" + loc.toString());
        logger.newSubStep();

        String islandDesignName = getIslandName(loc);

        Design topDesign = new Design(islandDesignName, netlistDB.partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFCell topCell = topNetlist.getTopCell();

        copyPartialNetlistToCell(topCell, netlistDB.originTopCell, getCellInstsOfIsland(loc));

        topDesign.setAutoIOBuffers(false);

        logger.endSubStep();
        logger.info("Complete creating design for island" + loc.toString());
        return topDesign;
    }

    private Design createIslandDesignWithBoundary(Coordinate2D loc) {
        logger.info("Start creating design with boundary for island" + loc.toString());
        logger.newSubStep();

        String islandDesignName = getIslandName(loc) + "_boundary";

        Design topDesign = new Design(islandDesignName, netlistDB.partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        Set<EDIFCellInst> allNewCellInsts = new HashSet<>();

        EDIFCell islandCell = new EDIFCell(workLib, getIslandName(loc));
        copyPartialNetlistToCell(islandCell, netlistDB.originTopCell, getCellInstsOfIsland(loc));
        allNewCellInsts.addAll(getCellInstsOfIsland(loc));
        islandCell.createCellInst(getIslandName(loc), topCell);

        VivadoTclCmd.drawPblock(topDesign, islandDesignName, getPBlockRangeOfIsland(loc));
        VivadoTclCmd.setPblockProperties(topDesign, islandDesignName, false, false, true);
        VivadoTclCmd.addCellToPBlock(topDesign, islandDesignName);
        //VivadoTclCmd.addStrictPblockConstr(topDesign, getPBlockRangeOfIsland(loc));

        // add boundary cells
        Coordinate2D upBoundaryLoc = getUpBoundaryLocOf(loc);
        if (upBoundaryLoc != null) {
            EDIFCell newCell = new EDIFCell(workLib, getHoriBoundaryName(upBoundaryLoc));
            copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfHoriBoundary(upBoundaryLoc));
            EDIFCellInst newCellInst = newCell.createCellInst(getHoriBoundaryName(upBoundaryLoc), topCell);
            VivadoTclCmd.addStrictPblockConstr(topDesign, newCellInst, getPBlockRangeOfHoriBoundary(upBoundaryLoc));

            allNewCellInsts.addAll(getCellInstsOfHoriBoundary(upBoundaryLoc));
        }

        Coordinate2D downBoundaryLoc = getDownBoundaryLocOf(loc);
        if (downBoundaryLoc != null) {
            EDIFCell newCell = new EDIFCell(workLib, getHoriBoundaryName(downBoundaryLoc));
            copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfHoriBoundary(downBoundaryLoc));
            EDIFCellInst newCellInst = newCell.createCellInst(getHoriBoundaryName(downBoundaryLoc), topCell);
            VivadoTclCmd.addStrictPblockConstr(topDesign, newCellInst, getPBlockRangeOfHoriBoundary(downBoundaryLoc));

            allNewCellInsts.addAll(getCellInstsOfHoriBoundary(downBoundaryLoc));
        }

        Coordinate2D leftBoundaryLoc = getLeftBoundaryLocOf(loc);
        if (leftBoundaryLoc != null) {
            EDIFCell newCell = new EDIFCell(workLib, getVertBoundaryName(leftBoundaryLoc));
            copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfVertBoundary(leftBoundaryLoc));
            EDIFCellInst newCellInst = newCell.createCellInst(getVertBoundaryName(leftBoundaryLoc), topCell);
            VivadoTclCmd.addStrictPblockConstr(topDesign, newCellInst, getPBlockRangeOfVertBoundary(leftBoundaryLoc));

            allNewCellInsts.addAll(getCellInstsOfVertBoundary(leftBoundaryLoc));
        }

        Coordinate2D rightBoundaryLoc = getRightBoundaryLocOf(loc);
        if (rightBoundaryLoc != null) {
            EDIFCell newCell = new EDIFCell(workLib, getVertBoundaryName(rightBoundaryLoc));
            copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfVertBoundary(rightBoundaryLoc));
            EDIFCellInst newCellInst = newCell.createCellInst(getVertBoundaryName(rightBoundaryLoc), topCell);
            VivadoTclCmd.addStrictPblockConstr(topDesign, newCellInst, getPBlockRangeOfVertBoundary(rightBoundaryLoc));

            allNewCellInsts.addAll(getCellInstsOfVertBoundary(rightBoundaryLoc));
        }


        for (EDIFCellInst cellInst : topCell.getCellInsts()) {
            EDIFCell cellType = cellInst.getCellType();
            assert !cellType.isStaticSource();

            for (EDIFPort port : cellType.getPorts()) {
                String portName = port.getName();
                EDIFNet net = topCell.getNet(portName);
                if (net == null) {
                    net = topCell.createNet(portName);
                }
                net.createPortInst(port, cellInst);
            }
        }

        for (EDIFNet newNet : topCell.getNets()) {
            int portInstNum = newNet.getPortInsts().size();
            int srcPortInstNum = newNet.getSourcePortInsts(true).size();

            assert portInstNum >= 1;

            // check if original net has ports or cellInsts out of current design
            EDIFNet originNet = netlistDB.originTopCell.getNet(newNet.getName());
            assert originNet != null;
            Boolean hasPortOutOfDesign = false;
            for (EDIFPortInst originPortInst : originNet.getPortInsts()) {
                EDIFCellInst originCellInst = originPortInst.getCellInst();
                if (originCellInst == null) {
                    hasPortOutOfDesign = true;
                } else if (!allNewCellInsts.contains(originCellInst)) {
                    hasPortOutOfDesign = true;
                }
            }

            if (portInstNum == 1 || srcPortInstNum == 0 || hasPortOutOfDesign) {
                EDIFDirection portDir = srcPortInstNum == 0 ? EDIFDirection.INPUT : EDIFDirection.OUTPUT;
                EDIFPort topPort = topCell.createPort(newNet.getName(), portDir, 1);
                newNet.createPortInst(topPort);
                //logger.info("Create partition pin: " + newNet.getName() + " on cell: " + topCell.getName());
            }
        }

        VivadoTclCmd.addClockConstraint(topDesign, clkPortName, clkPeriod);
        //VivadoTclCmd.setPropertyHDPartition(topDesign);
        topDesign.setAutoIOBuffers(false);
        //topDesign.setDesignOutOfContext(true);

        logger.endSubStep();
        logger.info("Complete creating design with boundary for island" + loc.toString());

        return topDesign;
    }

    private Design createIslandDesignWithContext(Coordinate2D loc, List<Design> contextDesigns, boolean copyOriginPorts) {
        logger.info("Start creating design with context for island" + loc.toString());
        logger.newSubStep();

        String islandDesignName = getIslandName(loc) + "_context";
        Design topDesign = new Design(islandDesignName, netlistDB.partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        EDIFCell islandCell = new EDIFCell(workLib, getIslandName(loc));
        copyPartialNetlistToCell(islandCell, netlistDB.originTopCell, getCellInstsOfIsland(loc));
        EDIFCellInst islandCellInst = islandCell.createCellInst(getIslandName(loc), topCell);

        for (Design contextDesign : contextDesigns) {
            EDIFCell contextTopCell = contextDesign.getNetlist().getTopCell();
            EDIFCell newCell = createBlackboxCell(workLib, contextTopCell);
            newCell.createCellInst(newCell.getName(), topCell);
        }

        if (copyOriginPorts) {
            connectTopCellInstAndPorts(topCell, netlistDB.originTopCell);
        } else {
            connectCellInstsOfCustomCell(topCell, netlistDB.originTopCell);
        }

        //
        VivadoTclCmd.addStrictPblockConstr(topDesign, islandCellInst, getPBlockRangeOfIsland(loc));
        VivadoTclCmd.addClockConstraint(topDesign, clkPortName, clkPeriod);
        //VivadoTclCmd.setPropertyHDPartition(topDesign);
        topDesign.setAutoIOBuffers(false);
        //topDesign.setDesignOutOfContext(false);

        logger.endSubStep();
        logger.info("Complete creating design with context for island" + loc.toString());
        return topDesign;
    }

    private Design createCompleteDesignWithContext(List<Coordinate2D> locs, Collection<Design> contextDesigns) {
        logger.info("Start creating design with context for islands");
        logger.newSubStep();

        String islandDesignName = "complete";
        Design topDesign = new Design(islandDesignName, netlistDB.partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        for (Coordinate2D loc : locs) {
            EDIFCell islandCell = new EDIFCell(workLib, getIslandName(loc));
            copyPartialNetlistToCell(islandCell, netlistDB.originTopCell, getCellInstsOfIsland(loc));
            EDIFCellInst islandCellInst = islandCell.createCellInst(getIslandName(loc), topCell);
            VivadoTclCmd.addStrictPblockConstr(topDesign, islandCellInst, getPBlockRangeOfIsland(loc));
        }

        for (Design contextDesign : contextDesigns) {
            EDIFCell contextTopCell = contextDesign.getNetlist().getTopCell();
            EDIFCell newCell = createBlackboxCell(workLib, contextTopCell);
            newCell.createCellInst(newCell.getName(), topCell);
        }
    
        connectTopCellInstAndPorts(topCell, netlistDB.originTopCell);

        //
        VivadoTclCmd.addClockConstraint(topDesign, clkPortName, clkPeriod);
        topDesign.setAutoIOBuffers(false);

        logger.endSubStep();
        logger.info("Complete creating design with context for islands");
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
                tclCmdFile.addCmd(VivadoTclCmd.readCheckPoint(designName, contextDcpPath.toString()));
            }
        }

        tclCmdFile.addCmd(VivadoTclCmd.placeDesign(null));
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign(null));
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

    private Design createCompleteDesign() {
        String designName = "complete";
        Design topDesign = new Design(designName, netlistDB.partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        // add island cells
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                Coordinate2D loc = Coordinate2D.of(x, y);
                EDIFCell islandCell = new EDIFCell(workLib, getIslandName(loc));
                copyPartialNetlistToCell(islandCell, netlistDB.originTopCell, getCellInstsOfIsland(loc));
                EDIFCellInst islandCellInst = islandCell.createCellInst(getIslandName(loc), topCell);
                String pblockRange = getPBlockRangeOfIsland(loc);
                VivadoTclCmd.addPblockConstr(topDesign, islandCellInst, pblockRange, false, false, true);
                VivadoTclCmd.setPropertyDontTouch(topDesign, islandCellInst.getName());
            }
        }

        // add vertical boundary cells
        for (int x = 0; x < vertBoundaryDim.getX(); x++) {
            for (int y = 0; y < vertBoundaryDim.getY(); y++) {
                Coordinate2D loc = Coordinate2D.of(x, y);
                EDIFCell newCell = new EDIFCell(workLib, getVertBoundaryName(loc));
                copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfVertBoundary(loc));
                EDIFCellInst newCellInst = newCell.createCellInst(getVertBoundaryName(loc), topCell);

                String pblockRange = getPBlockRangeOfVertBoundary(loc);
                VivadoTclCmd.addPblockConstr(topDesign, newCellInst, pblockRange, false, false, true);
            }
        }

        // add horizontal boundary cells
        for (int x = 0; x < horiBoundaryDim.getX(); x++) {
            for (int y = 0; y < horiBoundaryDim.getY(); y++) {
                Coordinate2D loc = Coordinate2D.of(x, y);
                EDIFCell newCell = new EDIFCell(workLib, getHoriBoundaryName(loc));
                copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfHoriBoundary(loc));
                EDIFCellInst newCellInst = newCell.createCellInst(getHoriBoundaryName(loc), topCell);

                String pblockRange = getPBlockRangeOfHoriBoundary(loc);
                VivadoTclCmd.addPblockConstr(topDesign, newCellInst, pblockRange, false, false, true);
            }
        }

        connectTopCellInstAndPorts(topCell, netlistDB.originTopCell);

        // set clock constraint
        VivadoTclCmd.addClockConstraint(topDesign, clkPortName, clkPeriod);
        topDesign.setAutoIOBuffers(false);

        return topDesign;
    }

    private EDIFCell createBlackboxCell(EDIFLibrary lib, EDIFCell refCell) {
        EDIFCell blackboxCell = new EDIFCell(lib, refCell.getName());

        for (EDIFPort port : refCell.getPorts()) {
            blackboxCell.createPort(port);
        }

        return blackboxCell;
    }

    private EDIFCell copyPartialNetlistToCell(EDIFCell newCell, EDIFCell originCell, Set<EDIFCellInst> originCellInsts) {
        // Copy partial netlist including originCellInsts to newCell
        logger.info("Copy partial netlist to cell: " + newCell.getName());
        logger.newSubStep();

        //// Copy partial netlist
        // Copy CellInsts
        for (EDIFCellInst cellInst : originCellInsts) {
            copyCellInstToNewCell(cellInst, newCell);
        }

        //// Copy Nets
        int netNum = 0;
        int partPinNum = 0;
        for (EDIFNet net : originCell.getNets()) {
            if (net.isGND() || net.isVCC()) {
                copyStaticNetToNewCell(net, newCell);
                continue;
            }

            EDIFNet newNet = copyNetToNewCell(net, newCell);
            if (newNet != null) {
                netNum += 1;
                // check if the net has out of island portInsts
                Boolean hasOutOfIslandPortInst = false;
                Boolean isSrcPortOutOfIsland = false;
                Boolean isSinkPortOutOfIsland = false;
                
                for (EDIFPortInst portInst : net.getPortInsts()) {
                    EDIFCellInst cellInst = portInst.getCellInst();
                    if (cellInst == null) { // Skip top-level port
                        hasOutOfIslandPortInst = true;
                        if (portInst.isInput()) {
                            isSrcPortOutOfIsland = true;
                        } else {
                            isSinkPortOutOfIsland = true;
                        }
                        continue;
                    }

                    if (newCell.getCellInst(cellInst.getName()) == null) {
                        hasOutOfIslandPortInst = true;
                        if (portInst.isOutput()) {
                            isSrcPortOutOfIsland = true;
                        } else {
                            isSinkPortOutOfIsland = true;
                        }
                    }
                }

                //assert isSrcPortOutOfIsland ^ isSinkPortOutOfIsland: String.format("%d", );

                if (hasOutOfIslandPortInst) {
                    partPinNum += 1;
                    EDIFDirection dir = isSrcPortOutOfIsland ? EDIFDirection.INPUT : EDIFDirection.OUTPUT;
                    String partPinName = newNet.getName();
                    EDIFPort newPort = newCell.createPort(partPinName, dir, 1);
                    newNet.createPortInst(newPort);
                    //logger.info("Create partition pin: " + partPinName + " on cell: " + newCell.getName());
                }
            }
        }

        logger.info("Number of cellInsts in the partial netlist: " + originCellInsts.size());
        logger.info("Number of nets in the partial netlist: " + netNum);
        logger.info("Number of partition pins in the partial netlist: " + partPinNum);

        logger.endSubStep();
        logger.info("Complete copying partial netlist to cell: " + newCell.getName());
        return newCell;
    }

    private void connectCellInstsOfCustomCell(EDIFCell customCell, EDIFCell originTopCell) {
        for (EDIFCellInst cellInst : customCell.getCellInsts()) {
            EDIFCell cellType = cellInst.getCellType();
            assert !cellType.isStaticSource();

            for (EDIFPort port : cellType.getPorts()) {
                String portName = port.getName();
                EDIFNet net = customCell.getNet(portName);
                if (net == null) {
                    net = customCell.createNet(portName);
                }
                net.createPortInst(port, cellInst);
            }
        }

        for (EDIFNet newNet : customCell.getNets()) {
            int portInstNum = newNet.getPortInsts().size();
            int srcPortInstNum = newNet.getSourcePortInsts(true).size();

            assert portInstNum >= 1;

            // check if original net connected with top-level ports
            EDIFNet originNet = originTopCell.getNet(newNet.getName());
            assert originNet != null;
            Boolean netHasTopPort = false;
            for (EDIFPortInst portInst : originNet.getPortInsts()) {
                EDIFCellInst cellInst = portInst.getCellInst();
                if (cellInst == null) {
                    netHasTopPort = true;
                }
            }

            if (portInstNum == 1 || srcPortInstNum == 0 || netHasTopPort) {
                EDIFDirection portDir = srcPortInstNum == 0 ? EDIFDirection.INPUT : EDIFDirection.OUTPUT;
                EDIFPort topPort = customCell.createPort(newNet.getName(), portDir, 1);
                newNet.createPortInst(topPort);
                //logger.info("Create partition pin: " + newNet.getName() + " on cell: " + customCell.getName());
            }
        }
    
    }

    private void connectTopCellInstAndPorts(EDIFCell topCell, EDIFCell originTopCell) {
        for (EDIFPort port : originTopCell.getPorts()) {
            topCell.createPort(port);
        }

        for (Map.Entry<String, EDIFNet> entry : originTopCell.getInternalNetMap().entrySet()) {
            String portInstName = entry.getKey();
            EDIFNet internalNet = entry.getValue();
            String internalNetName = internalNet.getName();

            if (internalNet.isGND() || internalNet.isVCC()) {
                NetType netType = internalNet.isGND() ? NetType.GND : NetType.VCC;
                EDIFNet newStaticNet = EDIFTools.getStaticNet(netType, topCell, topCell.getNetlist());
                newStaticNet.createPortInst(portInstName, topCell);
                continue;
            }

            EDIFNet newInternalNet = topCell.getNet(internalNetName);
            if (newInternalNet == null) {
                newInternalNet = topCell.createNet(internalNetName);
                //logger.info("Create new internal net: " + internalNetName);
            }
            newInternalNet.createPortInst(portInstName, topCell);
        }

        for (EDIFCellInst cellInst : topCell.getCellInsts()) {
            EDIFCell newCell = cellInst.getCellType();
            if (newCell.isStaticSource()) continue; // Skip static source cells
            for (EDIFPort port : newCell.getPorts()) {
                String portName = port.getName();
                String netName = portName;
                EDIFNet originNet = originTopCell.getNet(portName);
                assert originNet != null: String.format("Net correspond to port %s on %s not found in originTopCell", portName, cellInst.getName());
                if (netlistDB.isGlobalResetNet(originNet)) { // TODO: to be modified
                    netName = resetPortName;
                }
                EDIFNet newNet = topCell.getNet(netName);
                if (newNet == null) {
                    newNet = topCell.createNet(netName);
                }
                newNet.createPortInst(port, cellInst);
            }
        }

        // check Illegal nets
        for (EDIFNet net : topCell.getNets()) {
            int netDegree = net.getPortInsts().size();
            assert netDegree > 1: String.format("Net %s has only %d portInst", net.getName(), netDegree);
            assert net.getSourcePortInsts(true).size() == 1;
        }
    }

    private void copyCellInstToNewCell(EDIFCellInst cellInst, EDIFCell newCell) {

        EDIFNetlist newNetlist = newCell.getLibrary().getNetlist();
        //EDIFNetlist newNetlist = newDesign.getNetlist();
        //EDIFCell newTopCell = newNetlist.getTopCell();
        EDIFLibrary newPrimLib = newNetlist.getHDIPrimitivesLibrary();
        EDIFLibrary newWorkLib = newNetlist.getWorkLibrary();

        EDIFCell cellType = cellInst.getCellType();
        assert !cellType.isStaticSource();
        
        EDIFLibrary cellLib = cellType.getLibrary();
        assert cellLib.getNetlist() != newNetlist;

        EDIFLibrary targetLib = cellLib.isWorkLibrary() ? newWorkLib : newPrimLib;
        EDIFCell newCellType = targetLib.getCell(cellType.getName());
        if (newCellType == null) { // copy cellType if it's not found in new netlist
            newCellType = new EDIFCell(targetLib, cellType, cellType.getName());
        }

        EDIFCellInst newCellInst = newCell.createChildCellInst(cellInst.getName(), newCellType);
        newCellInst.setPropertiesMap(cellInst.createDuplicatePropertiesMap());
    }

    private void copyStaticNetToNewCell(EDIFNet originNet, EDIFCell newCell) {
        assert originNet.isGND() || originNet.isVCC();
        assert originNet.getParentCell() != newCell;

        NetType netType = originNet.isGND() ? NetType.GND : NetType.VCC;
        EDIFNet newNet = EDIFTools.getStaticNet(netType, newCell, newCell.getNetlist());

        for (EDIFPortInst portInst : originNet.getPortInsts()) {
            EDIFCellInst cellInst = portInst.getCellInst();
            if (cellInst == null) continue; // Skip top-level port
            if (cellInst.getCellType().isStaticSource()) continue;
            String cellName = cellInst.getName();
            EDIFCellInst newCellInst = newCell.getCellInst(cellName);
            if (newCellInst != null) {
                newNet.createPortInst(portInst.getName(), newCellInst);
            }
        }
    }

    private EDIFNet copyNetToNewCell(EDIFNet srcNet, EDIFCell newCell) {
        assert !srcNet.isGND() && !srcNet.isVCC();

        EDIFNet newNet = null;
        
        // copy ports for 
        for (EDIFPortInst portInst : srcNet.getPortInsts()) {
            EDIFCellInst cellInst = portInst.getCellInst();
            if (cellInst == null) { // Top-level port
                continue;
            }

            EDIFCellInst newCellInst = newCell.getCellInst(cellInst.getName());
            if (newCellInst == null) {
                continue;
            }
            
            if (newNet == null) {
                newNet = newCell.createNet(srcNet.getName());
            }
            newNet.createPortInst(portInst.getName(), newCellInst);
        }

        return newNet;
    }

    // Helper functions
    private Coordinate2D getLeftBoundaryLocOf(Integer x, Integer y) {
        if (x - 1 >= 0) {
            return new Coordinate2D(x - 1, y);
        } else {
            return null;
        }
    }

    private Coordinate2D getLeftBoundaryLocOf(Coordinate2D loc) {
        return getLeftBoundaryLocOf(loc.getX(), loc.getY());
    }

    private Coordinate2D getRightBoundaryLocOf(Integer x, Integer y) {
        if (x < vertBoundaryDim.getX()) {
            return new Coordinate2D(x, y);
        } else {
            return null;
        }
    }

    private Coordinate2D getRightBoundaryLocOf(Coordinate2D loc) {
        return getRightBoundaryLocOf(loc.getX(), loc.getY());
    }

    private Coordinate2D getUpBoundaryLocOf(Integer x, Integer y) {
        if (y < horiBoundaryDim.getY()) {
            return new Coordinate2D(x, y);
        } else {
            return null;
        }
    }

    private Coordinate2D getUpBoundaryLocOf(Coordinate2D loc) {
        return getUpBoundaryLocOf(loc.getX(), loc.getY());
    }

    private Coordinate2D getDownBoundaryLocOf(Integer x, Integer y) {
        if (y - 1 >= 0) {
            return new Coordinate2D(x, y - 1);
        } else {
            return null;
        }
    }

    private Coordinate2D getDownBoundaryLocOf(Coordinate2D loc) {
        return getDownBoundaryLocOf(loc.getX(), loc.getY());
    }

    private Coordinate2D getUpIslandLocOf(Integer x, Integer y) {
        if (y + 1 < gridDim.getY()) {
            return new Coordinate2D(x, y + 1);
        } else {
            return null;
        }
    }

    private Coordinate2D getDownIslandLocOf(Integer x, Integer y) {
        if (y - 1 >= 0) {
            return new Coordinate2D(x, y - 1);
        } else {
            return null;
        }
    }

    private Coordinate2D getLeftIslandLocOf(Integer x, Integer y) {
        if (x - 1 >= 0) {
            return new Coordinate2D(x - 1, y);
        } else {
            return null;
        }
    }

    private Coordinate2D getRightIslandLocOf(Integer x, Integer y) {
        if (x + 1 < gridDim.getX()) {
            return new Coordinate2D(x + 1, y);
        } else {
            return null;
        }
    }

    private Set<EDIFCellInst> getCellInstsOfIsland(int x, int y) {
        return island2CellInsts[x][y];
    }

    private Set<EDIFCellInst> getCellInstsOfIsland(Coordinate2D loc) {
        return getCellInstsOfIsland(loc.getX(), loc.getY());
    }

    private Set<EDIFNet> getNetsOfVertBoundary(int x, int y) {
        return vertBoundary2Nets[x][y];
    }

    private Set<EDIFNet> getNetsOfVertBoundary(Coordinate2D loc) {
        return getNetsOfVertBoundary(loc.getX(), loc.getY());
    }

    private Set<EDIFNet> getNetsOfHoriBoundary(int x, int y) {
        return horiBoundary2Nets[x][y];
    }

    private Set<EDIFNet> getNetsOfHoriBoundary(Coordinate2D loc) {
        return getNetsOfHoriBoundary(loc.getX(), loc.getY());
    }

    private Set<EDIFCellInst> getCellInstsOfVertBoundary(int x, int y) {
        return vertBoundary2CellInsts[x][y];
    }

    private Set<EDIFCellInst> getCellInstsOfVertBoundary(Coordinate2D loc) {
        return getCellInstsOfVertBoundary(loc.getX(), loc.getY());
    }

    private Set<EDIFCellInst> getCellInstsOfHoriBoundary(int x, int y) {
        return horiBoundary2CellInsts[x][y];
    }

    private Set<EDIFCellInst> getCellInstsOfHoriBoundary(Coordinate2D loc) {
        return getCellInstsOfHoriBoundary(loc.getX(), loc.getY());
    }

    private String getPblockRange(String pblockName) {
        return pblockName2RangeMap.get(pblockName);
    }

    protected String getPBlockRangeOfHoriBoundary(Coordinate2D loc) {
        String pblockName = getHoriBoundaryName(loc);
        String pblockRange = getPblockRange(pblockName);
        assert pblockRange != null;
        return pblockRange;
    }

    protected String getPBlockRangeOfVertBoundary(Coordinate2D loc) {
        String pblockName = getVertBoundaryName(loc);
        String pblockRange = getPblockRange(pblockName);
        assert pblockRange != null;
        return pblockRange;
    }

    protected String getPBlockRangeOfIsland(Coordinate2D loc) {
        String pblockName = getIslandName(loc);
        String pblockRange = getPblockRange(pblockName);
        assert pblockRange != null;
        return pblockRange;
    }
}
