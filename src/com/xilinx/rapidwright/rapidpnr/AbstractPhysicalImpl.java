package com.xilinx.rapidwright.rapidpnr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.xilinx.rapidwright.design.Design;
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

import static com.xilinx.rapidwright.rapidpnr.NameConvention.*;

import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.NetlistUtils;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.VivadoTclCmd;

abstract public class AbstractPhysicalImpl {
    protected HierarchicalLogger logger;
    protected DirectoryManager dirManager;

    // origin netlist database
    protected DesignParams designParams;
    protected NetlistDatabase netlistDB;

    // Design Parameters
    ////
    protected String designName;
    protected Map<String, Double> clkName2PeriodMap;
    protected String resetPortName; // TODO: only support single reset design
    //// Layout Informatio
    protected Coordinate2D gridDim;
    protected Coordinate2D vertBoundaryDim;
    protected Coordinate2D horiBoundaryDim;
    protected Map<String, String> pblockName2RangeMap;

    // Results of Netlist Abstraction & Island Placement
    //// Abstract Netlist Info
    AbstractNetlist abstractNetlist;
    //// Island Placement Results
    List<Coordinate2D> groupLocs;
    ////
    protected Set<EDIFCellInst>[][] island2CellInsts;
    protected Map<EDIFCellInst, Coordinate2D> cellInst2IslandLocMap;

    protected Set<EDIFNet>[][] vertBoundary2Nets;
    protected Set<EDIFNet>[][] horiBoundary2Nets;
    protected Map<EDIFNet, Coordinate2D> net2vertBoundaryLocMap;
    protected Map<EDIFNet, Coordinate2D> net2horiBoundaryLocMap;

    boolean hasBoundaryCell;
    protected Set<EDIFCellInst>[][] vertBoundary2CellInsts;
    protected Set<EDIFCellInst>[][] horiBoundary2CellInsts;
    protected Map<EDIFCellInst, Coordinate2D> cellInst2VertBoundaryLocMap;
    protected Map<EDIFCellInst, Coordinate2D> cellInst2HoriBoundaryLocMap;

    class CreateCellInstOfLoc implements Consumer<Coordinate2D> {

        public Design design;

        public Set<EDIFCellInst>[][] loc2CellInsts;
        public Function<Coordinate2D, String> loc2CellName;
        public Function<Coordinate2D, String> loc2PblockRange = null;
        public Function<Coordinate2D, String> loc2PblockName = null;
        public Boolean setDontTouch = false;
            
        public void accept(Coordinate2D loc) {
            EDIFNetlist netlist = design.getNetlist();
            EDIFLibrary workLib = netlist.getWorkLibrary();
            EDIFCell topCell = netlist.getTopCell();

            String cellName = loc2CellName.apply(loc);

            Set<EDIFCellInst> subCellInsts = loc2CellInsts[loc.getX()][loc.getY()];
            if (subCellInsts.isEmpty()) return;

            EDIFCell newCell = new EDIFCell(workLib, cellName);
            copyPartialNetlistToCell(newCell, netlistDB.originTopCell, subCellInsts);

            EDIFCellInst cellInst = newCell.createCellInst(cellName, topCell);
            if (loc2PblockRange != null) {
                String pblockRange = loc2PblockRange.apply(loc);
                if (loc2PblockName != null) {
                    String pblockName = loc2PblockName.apply(loc);
                    VivadoTclCmd.addStrictCellPblockConstr(design, cellInst, pblockName, pblockRange);
                } else {
                    VivadoTclCmd.addStrictCellPblockConstr(design, cellInst, pblockRange);
                }
            }

            if (setDontTouch) {
                VivadoTclCmd.setPropertyDontTouch(design, cellInst);
            }
        }
    }

    public AbstractPhysicalImpl(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams designParams, NetlistDatabase netlistDB) {
        this.logger = logger;
        this.dirManager = dirManager;

        // Parse Design Parameters
        this.designParams = designParams;
        designName = designParams.getDesignName();
        gridDim = designParams.getGridDim();
        vertBoundaryDim = designParams.getVertBoundaryDim();
        horiBoundaryDim = designParams.getHoriBoundaryDim();
        pblockName2RangeMap = designParams.getPblockName2RangeMap();

        clkName2PeriodMap = designParams.getClkPortName2PeriodMap();

        // Netlist Database
        this.netlistDB = netlistDB;
        resetPortName = netlistDB.resetPorts.iterator().next().getName();
    }

    abstract public void run(AbstractNetlist abstractNetlist, List<Coordinate2D> groupLocs);

    protected void loadPreStepsResult(AbstractNetlist abstractNetlist, List<Coordinate2D> groupLocs, boolean hasBoundaryCell) {
        logger.info("Start loading results from previous steps");
        logger.newSubStep();

        this.abstractNetlist = abstractNetlist;
        this.groupLocs = groupLocs;

        // Build cellInsts to island map
        buildCellInst2IslandMap();

        // Build net to boundary map
        buildNet2BoundaryMap();

        // Build cellInst to boundary map
        this.hasBoundaryCell = hasBoundaryCell;
        if (hasBoundaryCell) {
            buildCellInst2BoundaryMap();
        }

        logger.endSubStep();
        logger.info("Complete loading results from previous steps");
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

        for (int i = 0; i < abstractNetlist.getNodeNum(); i++) {
            Coordinate2D islandLoc = groupLocs.get(i);
            Set<EDIFCellInst> cellInstSet = abstractNetlist.getCellInstsOfNode(i);
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
                    //assert NetlistUtils.isRegisterCellInst(srcCellInst);
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
                    // assert NetlistUtils.isRegisterCellInst(srcCellInst);
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

    protected Design createCompleteDesign() {
        String designName = "complete";
        Design topDesign = new Design(designName, netlistDB.partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        // add island cells
        CreateCellInstOfLoc createCellInst = new CreateCellInstOfLoc();
        createCellInst.design = topDesign;

        createCellInst.loc2CellInsts = island2CellInsts;
        createCellInst.loc2CellName = NameConvention::getIslandName;
        gridDim.traverse(createCellInst);

        if (hasBoundaryCell) {

            // add vertical boundary cells
            createCellInst.loc2CellInsts = vertBoundary2CellInsts;
            createCellInst.loc2CellName = NameConvention::getVertBoundaryName;
            vertBoundaryDim.traverse(createCellInst);
    
            // add horizontal boundary cells
            createCellInst.loc2CellInsts = horiBoundary2CellInsts;
            createCellInst.loc2CellName = NameConvention::getHoriBoundaryName;
            horiBoundaryDim.traverse(createCellInst);
        }

        connectCellInstsOfTopCell(topCell, netlistDB.originTopCell);
        topDesign.setAutoIOBuffers(false);
        return topDesign;
    }

    protected Design createIslandDesign(Coordinate2D loc) {
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

    protected Design createIslandDesignWithBoundary(Coordinate2D loc) {
        assert hasBoundaryCell: "boundary cells are not available";

        logger.info("Start creating design with boundary for island" + loc.toString());
        logger.newSubStep();

        String islandDesignName = getIslandName(loc) + "_boundary";

        Design topDesign = new Design(islandDesignName, netlistDB.partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        Set<EDIFCellInst> allCopiedCellInsts = new HashSet<>();

        EDIFCell islandCell = new EDIFCell(workLib, getIslandName(loc));
        copyPartialNetlistToCell(islandCell, netlistDB.originTopCell, getCellInstsOfIsland(loc));
        allCopiedCellInsts.addAll(getCellInstsOfIsland(loc));
        islandCell.createCellInst(getIslandName(loc), topCell);

        //String pblockRange = getPblockRangeOfIsland(loc);
        // if (addPblockOnTop) {
        //     VivadoTclCmd.addCellPblockConstr(topDesign, pblockRange, false, false, true);
        // } else {
        //     VivadoTclCmd.addCellPblockConstr(topDesign, islandCellInst, pblockRange, false, false, true);
        // }
        //VivadoTclCmd.addStrictPblockConstr(topDesign, getPblockRangeOfIsland(loc));

        // add boundary cells
        Coordinate2D upBoundaryLoc = getUpBoundaryLocOf(loc);
        if (upBoundaryLoc != null) {
            EDIFCell newCell = new EDIFCell(workLib, getHoriBoundaryName(upBoundaryLoc));
            copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfHoriBoundary(upBoundaryLoc));
            newCell.createCellInst(getHoriBoundaryName(upBoundaryLoc), topCell);
            //VivadoTclCmd.addStrictCellPblockConstr(topDesign, newCellInst, getPblockRangeOfHoriBoundary(upBoundaryLoc));
            allCopiedCellInsts.addAll(getCellInstsOfHoriBoundary(upBoundaryLoc));
        }

        Coordinate2D downBoundaryLoc = getDownBoundaryLocOf(loc);
        if (downBoundaryLoc != null) {
            EDIFCell newCell = new EDIFCell(workLib, getHoriBoundaryName(downBoundaryLoc));
            copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfHoriBoundary(downBoundaryLoc));
            newCell.createCellInst(getHoriBoundaryName(downBoundaryLoc), topCell);
            //VivadoTclCmd.addStrictCellPblockConstr(topDesign, newCellInst, getPblockRangeOfHoriBoundary(downBoundaryLoc));

            allCopiedCellInsts.addAll(getCellInstsOfHoriBoundary(downBoundaryLoc));
        }

        Coordinate2D leftBoundaryLoc = getLeftBoundaryLocOf(loc);
        if (leftBoundaryLoc != null) {
            EDIFCell newCell = new EDIFCell(workLib, getVertBoundaryName(leftBoundaryLoc));
            copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfVertBoundary(leftBoundaryLoc));
            newCell.createCellInst(getVertBoundaryName(leftBoundaryLoc), topCell);
            // VivadoTclCmd.addStrictCellPblockConstr(topDesign, newCellInst, getPblockRangeOfVertBoundary(leftBoundaryLoc));

            allCopiedCellInsts.addAll(getCellInstsOfVertBoundary(leftBoundaryLoc));
        }

        Coordinate2D rightBoundaryLoc = getRightBoundaryLocOf(loc);
        if (rightBoundaryLoc != null) {
            EDIFCell newCell = new EDIFCell(workLib, getVertBoundaryName(rightBoundaryLoc));
            copyPartialNetlistToCell(newCell, netlistDB.originTopCell, getCellInstsOfVertBoundary(rightBoundaryLoc));
            newCell.createCellInst(getVertBoundaryName(rightBoundaryLoc), topCell);
            // VivadoTclCmd.addStrictCellPblockConstr(topDesign, newCellInst, getPblockRangeOfVertBoundary(rightBoundaryLoc));

            allCopiedCellInsts.addAll(getCellInstsOfVertBoundary(rightBoundaryLoc));
        }

        // connect cellInsts
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

        // create port for partial nets
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
                } else if (!allCopiedCellInsts.contains(originCellInst)) {
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

        // if (setIslandDontTouch) {
        //     VivadoTclCmd.setPropertyDontTouch(topDesign, islandCellInst);
        // }

        // VivadoTclCmd.createClocks(topDesign, clkName2PeriodMap);
        // VivadoTclCmd.setAsyncClockGroupsForEachClk(topDesign, clkName2PeriodMap.keySet());

        topDesign.setAutoIOBuffers(false);

        logger.endSubStep();
        logger.info("Complete creating design with boundary for island" + loc.toString());

        return topDesign;
    }

    protected EDIFCell createBlackboxCell(EDIFLibrary lib, EDIFCell refCell) {
        EDIFCell blackboxCell = new EDIFCell(lib, refCell.getName());

        for (EDIFPort port : refCell.getPorts()) {
            blackboxCell.createPort(port);
        }

        return blackboxCell;
    }

    protected void copyStaticNetToNewCell(EDIFNet originNet, EDIFCell newCell) {
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

    protected EDIFNet copyNetToNewCell(EDIFNet srcNet, EDIFCell newCell) {
        assert !srcNet.isGND() && !srcNet.isVCC();
        assert newCell.getNet(srcNet.getName()) == null;

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

    protected void copyCellInstToNewCell(EDIFCellInst cellInst, EDIFCell newCell) {

        EDIFNetlist newNetlist = newCell.getLibrary().getNetlist();
        //EDIFNetlist newNetlist = newDesign.getNetlist();
        //EDIFCell newTopCell = newNetlist.getTopCell();
        EDIFLibrary newPrimLib = newNetlist.getHDIPrimitivesLibrary();
        EDIFLibrary newWorkLib = newNetlist.getWorkLibrary();

        EDIFCell cellType = cellInst.getCellType();
        assert !cellType.isStaticSource();
        
        EDIFLibrary cellLib = cellType.getLibrary();
        assert cellLib.getNetlist() != newNetlist;

        //EDIFLibrary targetLib = cellLib.isWorkLibrary() ? newWorkLib : newPrimLib;
        // EDIFCell newCellType = targetLib.getCell(cellType.getName());
        // if (newCellType == null) { // copy cellType if it's not found in new netlist
        //     newCellType = new EDIFCell(targetLib, cellType, cellType.getName());
        // }
        EDIFCell newCellType = newNetlist.getCell(cellType.getName());
        if (newCellType == null) {
            newNetlist.copyCellAndSubCells(cellType);
        }
        newCellType = newNetlist.getCell(cellType.getName());

        EDIFCellInst newCellInst = newCell.createChildCellInst(cellInst.getName(), newCellType);
        newCellInst.setPropertiesMap(cellInst.createDuplicatePropertiesMap());
    }

    protected EDIFCell copyPartialNetlistToCell(EDIFCell newCell, EDIFCell originCell, Set<EDIFCellInst> originCellInsts) {
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

    protected void connectCellInstsOfCustomCell(EDIFCell customCell, EDIFCell originTopCell) {
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

    protected void connectCellInstsOfTopCell(EDIFCell newTopCell, EDIFCell originTopCell) {
        for (EDIFPort port : originTopCell.getPorts()) {
            newTopCell.createPort(port);
        }

        for (Map.Entry<String, EDIFNet> entry : originTopCell.getInternalNetMap().entrySet()) {
            String portInstName = entry.getKey();
            EDIFNet internalNet = entry.getValue();
            String internalNetName = internalNet.getName();

            if (internalNet.isGND() || internalNet.isVCC()) {
                NetType netType = internalNet.isGND() ? NetType.GND : NetType.VCC;
                EDIFNet newStaticNet = EDIFTools.getStaticNet(netType, newTopCell, newTopCell.getNetlist());
                newStaticNet.createPortInst(portInstName, newTopCell);
                continue;
            }

            EDIFNet newInternalNet = newTopCell.getNet(internalNetName);
            if (newInternalNet == null) {
                newInternalNet = newTopCell.createNet(internalNetName);
                //logger.info("Create new internal net: " + internalNetName);
            }
            newInternalNet.createPortInst(portInstName, newTopCell);
        }

        for (EDIFCellInst cellInst : newTopCell.getCellInsts()) {
            EDIFCell newCell = cellInst.getCellType();
            if (newCell.isStaticSource()) continue; // Skip static source cells
            
            for (EDIFPort port : newCell.getPorts()) {

                Map<Integer, String> index2PortInstName = new HashMap<>();
                if (port.isBus()) {
                    for (int i = 0; i < port.getWidth(); i++) {
                        String portInstName = port.getPortInstNameFromPort(i);
                        if (newCell.getInternalNet(portInstName) != null) {
                            index2PortInstName.put(i, portInstName);
                        }
                    }
                } else {
                    index2PortInstName.put(0, port.getName());
                }

                for (Map.Entry<Integer, String> entry : index2PortInstName.entrySet()) {
                    String netName = entry.getValue();
                    EDIFNet originNet = originTopCell.getNet(netName);
                    assert originNet != null: String.format("Net correspond to port %s on %s not found in originTopCell", netName, cellInst.getName());
                    
                    if (netlistDB.isGlobalResetNet(originNet)) { // TODO: to be modified
                        netName = resetPortName;
                    }

                    EDIFNet newNet = newTopCell.getNet(netName);
                    if (newNet == null) {
                        newNet = newTopCell.createNet(netName);
                    }

                    if (port.isBus()) {
                        newNet.createPortInst(port, entry.getKey(), cellInst);
                    } else {
                        newNet.createPortInst(port, cellInst);
                    }
                }
                // String portName = port.getName();
                // String netName = portName;
                // EDIFNet originNet = originTopCell.getNet(portName);
                // assert originNet != null: String.format("Net correspond to port %s on %s not found in originTopCell", portName, cellInst.getName());
                // if (netlistDB.isGlobalResetNet(originNet)) { // TODO: to be modified
                //     netName = resetPortName;
                // }
                // EDIFNet newNet = newTopCell.getNet(netName);
                // if (newNet == null) {
                //     newNet = newTopCell.createNet(netName);
                // }
                // newNet.createPortInst(port, cellInst);
            }
        }

        // check Illegal nets
        for (EDIFNet net : newTopCell.getNets()) {
            int netDegree = net.getPortInsts().size();
            assert netDegree > 1: String.format("Net %s has only %d portInst", net.getName(), netDegree);
            assert net.getSourcePortInsts(true).size() == 1;
        }
    }

    // Helper functions

    protected Boolean isBoundaryCell(EDIFCellInst cellInst) {
        return cellInst2HoriBoundaryLocMap.containsKey(cellInst) || cellInst2VertBoundaryLocMap.containsKey(cellInst);
    }

    protected Boolean isNeighborVertBoundary(Coordinate2D island, Coordinate2D boundary) {
        Boolean yMatch = island.getY() == boundary.getY();
        Boolean xMatch = island.getX() == boundary.getX() || island.getX() == boundary.getX() + 1;
        return xMatch && yMatch;
    }

    protected Boolean isNeighborHoriBoundary(Coordinate2D island, Coordinate2D boundary) {
        Boolean xMatch = island.getX() == boundary.getX();
        Boolean yMatch = island.getY() == boundary.getY() || island.getY() == boundary.getY() + 1;
        return xMatch && yMatch;
    }
    
    protected int getIslandIdFromLoc(Coordinate2D loc) {
        return loc.getX() + loc.getY() * gridDim.getX();
    }

    protected Coordinate2D getIslandLocFromId(int id) {
        return new Coordinate2D(id % gridDim.getX(), id / gridDim.getX());
    }

    protected Coordinate2D getLeftBoundaryLocOf(Integer x, Integer y) {
        if (x - 1 >= 0) {
            return new Coordinate2D(x - 1, y);
        } else {
            return null;
        }
    }

    protected Coordinate2D getLeftBoundaryLocOf(Coordinate2D loc) {
        return getLeftBoundaryLocOf(loc.getX(), loc.getY());
    }

    protected Coordinate2D getRightBoundaryLocOf(Integer x, Integer y) {
        if (x < vertBoundaryDim.getX()) {
            return new Coordinate2D(x, y);
        } else {
            return null;
        }
    }

    protected Coordinate2D getRightBoundaryLocOf(Coordinate2D loc) {
        return getRightBoundaryLocOf(loc.getX(), loc.getY());
    }

    protected Coordinate2D getUpBoundaryLocOf(Integer x, Integer y) {
        if (y < horiBoundaryDim.getY()) {
            return new Coordinate2D(x, y);
        } else {
            return null;
        }
    }

    protected Coordinate2D getUpBoundaryLocOf(Coordinate2D loc) {
        return getUpBoundaryLocOf(loc.getX(), loc.getY());
    }

    protected Coordinate2D getDownBoundaryLocOf(Integer x, Integer y) {
        if (y - 1 >= 0) {
            return new Coordinate2D(x, y - 1);
        } else {
            return null;
        }
    }

    protected Coordinate2D getDownBoundaryLocOf(Coordinate2D loc) {
        return getDownBoundaryLocOf(loc.getX(), loc.getY());
    }

    protected Coordinate2D getUpIslandLocOf(Integer x, Integer y) {
        if (y + 1 < gridDim.getY()) {
            return new Coordinate2D(x, y + 1);
        } else {
            return null;
        }
    }

    protected Coordinate2D getDownIslandLocOf(Integer x, Integer y) {
        if (y - 1 >= 0) {
            return new Coordinate2D(x, y - 1);
        } else {
            return null;
        }
    }

    protected Coordinate2D getLeftIslandLocOf(Integer x, Integer y) {
        if (x - 1 >= 0) {
            return new Coordinate2D(x - 1, y);
        } else {
            return null;
        }
    }

    protected Coordinate2D getRightIslandLocOf(Integer x, Integer y) {
        if (x + 1 < gridDim.getX()) {
            return new Coordinate2D(x + 1, y);
        } else {
            return null;
        }
    }

    protected Set<EDIFCellInst> getCellInstsOfIsland(int x, int y) {
        return island2CellInsts[x][y];
    }

    protected Set<EDIFCellInst> getCellInstsOfIsland(Coordinate2D loc) {
        return getCellInstsOfIsland(loc.getX(), loc.getY());
    }

    protected Set<EDIFNet> getNetsOfVertBoundary(int x, int y) {
        return vertBoundary2Nets[x][y];
    }

    protected Set<EDIFNet> getNetsOfVertBoundary(Coordinate2D loc) {
        return getNetsOfVertBoundary(loc.getX(), loc.getY());
    }

    protected Set<EDIFNet> getNetsOfHoriBoundary(int x, int y) {
        return horiBoundary2Nets[x][y];
    }

    protected Set<EDIFNet> getNetsOfHoriBoundary(Coordinate2D loc) {
        return getNetsOfHoriBoundary(loc.getX(), loc.getY());
    }

    protected Set<EDIFCellInst> getCellInstsOfVertBoundary(int x, int y) {
        return vertBoundary2CellInsts[x][y];
    }

    protected Set<EDIFCellInst> getCellInstsOfVertBoundary(Coordinate2D loc) {
        return getCellInstsOfVertBoundary(loc.getX(), loc.getY());
    }

    protected Boolean isVertBoundaryExist(int x, int y) {
        return vertBoundary2Nets[x][y].size() > 0;
    }

    protected Boolean isVertBoundaryExist(Coordinate2D loc) {
        return isVertBoundaryExist(loc.getX(), loc.getY());
    }

    protected Boolean isHoriBoundaryExist(int x, int y) {
        return horiBoundary2Nets[x][y].size() > 0;
    }

    protected Boolean isHoriBoundaryExist(Coordinate2D loc) {
        return isHoriBoundaryExist(loc.getX(), loc.getY());
    }

    protected Set<EDIFCellInst> getCellInstsOfHoriBoundary(int x, int y) {
        return horiBoundary2CellInsts[x][y];
    }

    protected Set<EDIFCellInst> getCellInstsOfHoriBoundary(Coordinate2D loc) {
        return getCellInstsOfHoriBoundary(loc.getX(), loc.getY());
    }

    protected String getPblockRange(String pblockName) {
        return pblockName2RangeMap.get(pblockName);
    }

    protected String getPblockRangeOfHoriBoundary(int x, int y) {
        String pblockName = getHoriBoundaryName(x, y);
        String pblockRange = getPblockRange(pblockName);
        assert pblockRange != null;
        return pblockRange;
    }

    protected String getPblockRangeOfHoriBoundary(Coordinate2D loc) {
        return getPblockRangeOfHoriBoundary(loc.getX(), loc.getY());
    }

    protected String getPblockRangeOfVertBoundary(int x, int y) {
        String pblockName = getVertBoundaryName(x, y);
        String pblockRange = getPblockRange(pblockName);
        assert pblockRange != null;
        return pblockRange;
    }

    protected String getPblockRangeOfVertBoundary(Coordinate2D loc) {
        return getPblockRangeOfVertBoundary(loc.getX(), loc.getY());
    }

    protected String getPblockRangeOfIsland(int x, int y) {
        String pblockName = getIslandName(x, y);
        String pblockRange = getPblockRange(pblockName);
        assert pblockRange != null;
        return pblockRange;
    }

    protected String getPblockRangeOfIsland(Coordinate2D loc) {
        return getPblockRangeOfIsland(loc.getX(), loc.getY());
    }
}
