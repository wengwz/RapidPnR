package com.xilinx.rapidwright.examples;

import java.util.ArrayList;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.FileReader;
import java.io.IOException;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.xilinx.rapidwright.examples.PartitionResultsJson;
import com.xilinx.rapidwright.examples.IslandPlaceResultJson;

enum NetSourceType {
    INSIDE_ISLAND,
    OUTSIDE_ISLAND,
    NEARBY_ANCHOR,
    TOP_PORT,
    UNKNOWN
}

public class ParallelIslandPnR {
    private Logger logger;
    
    private Design originDesign;
    private EDIFNetlist originTopNetlist;
    private EDIFCell originTopCell;

    private String resetPortName;
    private String clockPortName;

    private Set<EDIFNet> clockNets;
    private Set<EDIFNet> resetNets;

    private List<List<EDIFCellInst>> partitionGroup2Cell;
    private List<List<EDIFCellInst>> partitionEdge2Cell;
    private List<List<Integer>> partitionEdge2GroupIdxMap;
    private List<EDIFCellInst> resetTreeCellInsts;

    private List<List<Integer>> partitionGroupLocs;
    private List<Integer> gridDimension;

    
    private List<EDIFCellInst>[][] island2CellMap;
    private Map<EDIFCellInst, List<Integer>> cell2IslandMap;

    private List<EDIFCellInst>[][] horiAnchorRegion2CellMap;
    private List<EDIFCellInst>[][] vertAnchorRegion2CellMap;
    private Map<EDIFCellInst, List<Integer>> cell2horiAnchorRegionMap;
    private Map<EDIFCellInst, List<Integer>> cell2vertAnchorRegionMap;

    private List<EDIFCellInst>[][] horiAnchorIncidentCellMap;
    private List<EDIFCellInst>[][] vertAnchorIncidentCellMap;

    private static String[][] islandPBlockRanges;
    private static String[][] horiAnchorPBlockRanges;
    private static String[][] vertAnchorPBlockRanges;

    static {
        islandPBlockRanges = new String[][] {
            {
                "SLICE_X0Y360:SLICE_X28Y417 DSP48E2_X0Y144:DSP48E2_X3Y165 RAMB18_X0Y144:RAMB18_X2Y165 RAMB36_X0Y72:RAMB36_X2Y82",
                "SLICE_X0Y422:SLICE_X28Y479 DSP48E2_X0Y170:DSP48E2_X3Y191 RAMB18_X0Y170:RAMB18_X2Y191 RAMB36_X0Y85:RAMB36_X2Y95"
            },
            {
                "SLICE_X33Y360:SLICE_X54Y417 DSP48E2_X5Y144:DSP48E2_X7Y165 RAMB18_X3Y144:RAMB18_X3Y165 RAMB36_X3Y72:RAMB36_X3Y82 URAM288_X0Y96:URAM288_X0Y107",
                "SLICE_X33Y422:SLICE_X54Y479 DSP48E2_X5Y170:DSP48E2_X7Y191 RAMB18_X3Y170:RAMB18_X3Y191 RAMB36_X3Y85:RAMB36_X3Y95 URAM288_X0Y116:URAM288_X0Y127"
            }
        };

        vertAnchorPBlockRanges = new String[][] {
            {
                "SLICE_X29Y360:SLICE_X32Y419 DSP48E2_X4Y144:DSP48E2_X4Y167",
                "SLICE_X29Y420:SLICE_X32Y479 DSP48E2_X4Y168:DSP48E2_X4Y191"
            }
        };

        horiAnchorPBlockRanges = new String[][] {
            {"SLICE_X0Y418:SLICE_X28Y421"},
            {"SLICE_X33Y418:SLICE_X54Y421"}
        };
    }

    public ParallelIslandPnR(String designFile, String partitionJsonFile, String placeJsonFile) {
        String logFilePath = "./pr_result/parallel_island_pnr.log";
        logger = Logger.getLogger(ParallelIslandPnR.class.getName());
        logger.setUseParentHandlers(false);
        try {
            FileHandler fileHandler = new FileHandler(logFilePath, false);
            fileHandler.setFormatter(new CustomFormatter());
            logger.addHandler(fileHandler);

            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new CustomFormatter());
            logger.addHandler(consoleHandler);
        } catch (Exception e) {
            System.out.println("Fail to open log file: " + logFilePath);
        }

        originDesign = Design.readCheckpoint(designFile);
        assert originDesign != null : "Invalid path of design checkpoint: " + designFile;
        originTopNetlist = originDesign.getNetlist();
        originTopCell = originTopNetlist.getTopCell();

        readPartitionResultsJson(partitionJsonFile);
        readPlaceResultsJson(placeJsonFile);
        buildCellToRegionMap();

        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                logger.info("Island (" + i + " " + j + ")");
                logger.info("Number of cells: " + island2CellMap[i][j].size());
            }
        }

        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1) - 1; j++) {
                logger.info("Horizontal Anchor Region (" + i + " " + j + ")");
                logger.info("Number of cells: " + horiAnchorRegion2CellMap[i][j].size());
            }
        }

        for (int i = 0; i < gridDimension.get(0) - 1; i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                logger.info("Vertical Anchor Region (" + i + " " + j + ")");
                logger.info("Number of cells: " + vertAnchorRegion2CellMap[i][j].size());
            }
        }

        // check correctness
        Integer islandCellNum = cell2IslandMap.size();
        Integer anchorCellNum = cell2horiAnchorRegionMap.size() + cell2vertAnchorRegionMap.size();
        logger.info(String.format("Number of cells in island and anchor: %d", islandCellNum + anchorCellNum));
        logger.info("Number of cells in reset tree: " + resetTreeCellInsts.size());
        logger.info("Number of cells in top netlist: " + originTopNetlist.getTopCell().getCellInsts().size());
        assert islandCellNum + anchorCellNum + resetTreeCellInsts.size() == originTopNetlist.getTopCell().getCellInsts().size() - 2;
    }

    private void readPartitionResultsJson(String jsonFilePath) {
        // Read partition result from json file
        Gson gson = new GsonBuilder().create();
        try (FileReader reader = new FileReader(jsonFilePath)) {
            PartitionResultsJson partitionResults = gson.fromJson(reader, PartitionResultsJson.class);
            partitionGroup2Cell = new ArrayList<>();
            partitionEdge2Cell = new ArrayList<>();
            partitionEdge2GroupIdxMap = new ArrayList<>();
            resetTreeCellInsts = new ArrayList<>();
            
            for (PartitionGroupJson groupJson : partitionResults.partitionGroups) {
                List<EDIFCellInst> groupCells = new ArrayList<>();
                for (String cellName : groupJson.groupCellNames) {
                    EDIFCellInst cellInst = originTopCell.getCellInst(cellName);
                    assert cellInst != null: "Invalid CellInst Name: " + cellName;
                    groupCells.add(cellInst);
                }
                partitionGroup2Cell.add(groupCells);
            }

            for (PartitionEdgeJson edgeJson : partitionResults.partitionEdges) {
                List<EDIFCellInst> edgeCells = new ArrayList<>();
                for (String cellName : edgeJson.edgeCellNames) {
                    EDIFCellInst cellInst = originTopCell.getCellInst(cellName);
                    assert cellInst != null: "Invalid Edge CellInst Name: " + cellName;
                    edgeCells.add(cellInst);
                }
                partitionEdge2Cell.add(edgeCells);
                partitionEdge2GroupIdxMap.add(edgeJson.incidentGroupIds);
            }

            for (String cellName : partitionResults.resetTreeCellNames) {
                EDIFCellInst cellInst = originTopCell.getCellInst(cellName);
                assert cellInst != null: "Invalid Reset Tree CellInst Name: " + cellName;
                resetTreeCellInsts.add(cellInst);
            }
            clockPortName = partitionResults.clkPortName;
            resetPortName = partitionResults.rstPortName;
            clockNets = new HashSet<>();
            clockNets.add(originTopCell.getNet(clockPortName));

            resetNets = new HashSet<>();
            for (String resetNetName : partitionResults.resetNetNames) {
                EDIFNet resetNet = originTopCell.getNet(resetNetName);
                assert resetNet != null: "Invalid Reset Net Name: " + resetNetName;
                resetNets.add(resetNet);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        logger.info("Complete reading partition results from " + jsonFilePath);
    }

    private void readPlaceResultsJson(String filePath) {
        Gson gson = new GsonBuilder().create();
        try (FileReader reader = new FileReader(filePath)) {
            IslandPlaceResultJson placeJson = gson.fromJson(reader, IslandPlaceResultJson.class);
            gridDimension = placeJson.gridDimension;
            partitionGroupLocs = placeJson.partitionGroupLocs;
            assert partitionGroupLocs.size() == partitionGroup2Cell.size();
        } catch (IOException e) {
            e.printStackTrace();
        }
        logger.info("Complete reading island place results from " + filePath);
    }

    private void buildCellToRegionMap() {
        // Setup 2-D array for cell mapping
        island2CellMap = new ArrayList[gridDimension.get(0)][gridDimension.get(1)];
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                island2CellMap[i][j] = new ArrayList<>();
            }
        }
        horiAnchorRegion2CellMap = new ArrayList[gridDimension.get(0)][gridDimension.get(1) - 1];
        horiAnchorIncidentCellMap = new ArrayList[gridDimension.get(0)][gridDimension.get(1) - 1];
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1) - 1; j++) {
                horiAnchorRegion2CellMap[i][j] = new ArrayList<>();
                horiAnchorIncidentCellMap[i][j] = new ArrayList<>();
            }
        }
        vertAnchorRegion2CellMap = new ArrayList[gridDimension.get(0) - 1][gridDimension.get(1)];
        vertAnchorIncidentCellMap = new ArrayList[gridDimension.get(0) - 1][gridDimension.get(1)];
        for (int i = 0; i < gridDimension.get(0) - 1; i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                vertAnchorRegion2CellMap[i][j] = new ArrayList<>();
                vertAnchorIncidentCellMap[i][j] = new ArrayList<>();
            }
        }
        cell2IslandMap = new HashMap<>();
        cell2horiAnchorRegionMap = new HashMap<>();
        cell2vertAnchorRegionMap = new HashMap<>();

        // Map cells of partitionGroups to island
        for (int i = 0; i < partitionGroup2Cell.size(); i++) {
            List<Integer> loc = partitionGroupLocs.get(i);
            
            for (EDIFCellInst cellInst : partitionGroup2Cell.get(i)) {
                island2CellMap[loc.get(0)][loc.get(1)].add(cellInst);
                assert !cell2IslandMap.containsKey(cellInst);
                cell2IslandMap.put(cellInst, loc);
            }
        }

        // Map cells of partitionEdges to island / anchor region
        for (int i = 0; i < partitionEdge2Cell.size(); i++) {
            List<Integer> incidentGroupIds = partitionEdge2GroupIdxMap.get(i);
            Set<List<Integer>> incidentGroupLocs = new HashSet<>();
            for (int groupId : incidentGroupIds) {
                incidentGroupLocs.add(partitionGroupLocs.get(groupId));
            }

            assert incidentGroupLocs.size() == 1 || incidentGroupLocs.size() == 2: String.format("Incident Group Size: %d", incidentGroupLocs.size());

            if (incidentGroupLocs.size() == 1) {
                List<Integer> loc = incidentGroupLocs.iterator().next();
                for (EDIFCellInst cellInst : partitionEdge2Cell.get(i)) {
                    island2CellMap[loc.get(0)][loc.get(1)].add(cellInst);
                    assert !cell2IslandMap.containsKey(cellInst);
                    cell2IslandMap.put(cellInst, loc);
                }
            } else { // Map cell of cross-island partitionEdge to anchor region
                List<List<Integer>> incidentGroupLocsList = new ArrayList<>(incidentGroupLocs);
                List<Integer> loc1 = incidentGroupLocsList.get(0);
                List<Integer> loc2 = incidentGroupLocsList.get(1);
                if (loc1.get(0) != loc2.get(0)) { // partitionEdge cross vertical anchor region
                    assert loc1.get(1) == loc2.get(1);
                    assert Math.abs(loc1.get(0) - loc2.get(0)) == 1;
                    
                    int xOffset = Math.min(loc1.get(0), loc2.get(0));
                    int yOffset = loc1.get(1);

                    for (EDIFCellInst cellInst : partitionEdge2Cell.get(i)) {
                        vertAnchorRegion2CellMap[xOffset][yOffset].add(cellInst);
                        assert !cell2vertAnchorRegionMap.containsKey(cellInst);
                        cell2vertAnchorRegionMap.put(cellInst, Arrays.asList(xOffset, yOffset));
                    }
                } else { // partitionEdge cross horizontal anchor region
                    assert loc1.get(1) != loc2.get(1);
                    assert Math.abs(loc1.get(1) - loc2.get(1)) == 1;

                    int xOffset = loc1.get(0);
                    int yOffset = Math.min(loc1.get(1), loc2.get(1));

                    for (EDIFCellInst cellInst : partitionEdge2Cell.get(i)) {
                        horiAnchorRegion2CellMap[xOffset][yOffset].add(cellInst);
                        assert !cell2horiAnchorRegionMap.containsKey(cellInst);
                        cell2horiAnchorRegionMap.put(cellInst, Arrays.asList(xOffset, yOffset));
                    }
                }
            }
        }

        // Collect island cells incident to horizontal anchor cells
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1) - 1; j++) {
                if (horiAnchorRegion2CellMap[i][j].size() == 0) continue;
                for (EDIFCellInst cellInst : horiAnchorRegion2CellMap[i][j]) {
                    for (EDIFPortInst portInst : cellInst.getPortInsts()) {
                        EDIFNet net = portInst.getNet();
                        if (net.isGND() || net.isVCC() || resetNets.contains(net) || clockNets.contains(net)) continue;
                        for (EDIFPortInst expandPortInst : net.getPortInsts()) {
                            EDIFCellInst expandCellInst = expandPortInst.getCellInst();
                            if (expandCellInst == null) continue;
                            if (isAnchorRegionCell(expandCellInst)) continue;
                            assert !expandCellInst.getCellType().isStaticSource();
                            assert island2CellMap[i][j].contains(expandCellInst) || island2CellMap[i][j + 1].contains(expandCellInst);
                            horiAnchorIncidentCellMap[i][j].add(expandCellInst);
                        }
                    }
                }
            }
        }

        // Collect island cells incident to vertical anchor cells
        for (int i = 0; i < gridDimension.get(0) - 1; i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                if (vertAnchorRegion2CellMap[i][j].size() == 0) continue;
                for (EDIFCellInst cellInst : vertAnchorRegion2CellMap[i][j]) {
                    for (EDIFPortInst portInst : cellInst.getPortInsts()) {
                        EDIFNet net = portInst.getNet();
                        if (net.isGND() || net.isVCC() || resetNets.contains(net) || clockNets.contains(net)) continue;
                        for (EDIFPortInst expandPortInst : net.getPortInsts()) {
                            EDIFCellInst expandCellInst = expandPortInst.getCellInst();
                            if (expandCellInst == null) continue;
                            if (isAnchorRegionCell(expandCellInst)) continue;
                            assert !expandCellInst.getCellType().isStaticSource();
                            assert island2CellMap[i][j].contains(expandCellInst) || island2CellMap[i + 1][j].contains(expandCellInst);
                            vertAnchorIncidentCellMap[i][j].add(expandCellInst);
                        }
                    }
                }
            }
        }
        logger.info("Complete building cellInst to region map");
    }

    private void generateIslandDCP(Integer x, Integer y) {
        logger.info("Start generating design checkpoint of island (" + x + " " + y + ")");
        String designName = String.format("island_%d_%d", x, y);
        String partName = originDesign.getPartName();
        //logger.info("Part Name: " + partName);
        //Part part = PartNameTools.getPart(partName);
        //originDesign.getNetlist().collapseMacroUnisims(part.getSeries());

        Design islandDesign = new Design(designName, partName);
        EDIFNetlist islandNetlist = islandDesign.getNetlist();
        EDIFCell islandTopCell = islandNetlist.getTopCell();
        
        EDIFLibrary islandPrimLib = islandNetlist.getHDIPrimitivesLibrary();
        EDIFLibrary islandWorkLib = islandNetlist.getWorkLibrary();

        // Copy netlist consisting of cells in island and nearby anchor regions
        //// Copy cells to design checkpoint of  island
        List<EDIFCellInst> originCellInsts = new ArrayList<>();
        originCellInsts.addAll(island2CellMap[x][y]);

        if (x - 1 >= 0) {
            originCellInsts.addAll(vertAnchorRegion2CellMap[x - 1][y]);
            logger.info("Number of cells in left anchor region: " + vertAnchorRegion2CellMap[x - 1][y].size());
        }
        if (x  < gridDimension.get(0) - 1) {
            originCellInsts.addAll(vertAnchorRegion2CellMap[x][y]);
            logger.info("Number of cells in right anchor region: " + vertAnchorRegion2CellMap[x][y].size());
        }
        if (y - 1 >= 0) {
            originCellInsts.addAll(horiAnchorRegion2CellMap[x][y - 1]);
            logger.info("Number of cells in lower anchor region: " + horiAnchorRegion2CellMap[x][y - 1].size());
        }
        if (y < gridDimension.get(1) - 1) {
            originCellInsts.addAll(horiAnchorRegion2CellMap[x][y]);
            logger.info("Number of cells in upper anchor region: " + horiAnchorRegion2CellMap[x][y].size());
        }
        logger.info(" Total number of cellInsts within the island: " + originCellInsts.size());

        for (EDIFCellInst cellInst: originCellInsts) {
            EDIFCell cellType = cellInst.getCellType();
            assert !cellType.isStaticSource();

            EDIFLibrary lib = cellType.getLibrary().isWorkLibrary() ? islandWorkLib : islandPrimLib;
            EDIFCell islandCellType = lib.getCell(cellType.getName());
            if (islandCellType == null) {
                islandCellType = new EDIFCell(lib, cellType, cellType.getName());
            }

            EDIFCellInst islandCellInst = islandTopCell.createChildCellInst(cellInst.getName(), islandCellType);
            islandCellInst.setPropertiesMap(cellInst.createDuplicatePropertiesMap());
        }

        //// Copy nets and connect them to island cells
        for (EDIFNet net : originTopCell.getNets()) {
            String netName = net.getName();

            if(net.isGND() || net.isVCC()) { // Copy VCC&GND nets
                NetType staticNetType = net.isGND() ? NetType.GND : NetType.VCC;
                EDIFNet islandNet = EDIFTools.getStaticNet(staticNetType, islandTopCell, islandNetlist);
                
                for (EDIFPortInst portInst : net.getPortInsts()) {
                    EDIFCellInst cellInst = portInst.getCellInst();
                    if (cellInst == null) continue; // ignore top-level ports
                    if (cellInst.getCellType().isStaticSource()) continue;
                    
                    EDIFCellInst islandCellInst = islandTopCell.getCellInst(cellInst.getName());
                    if (islandCellInst != null) {
                        islandNet.createPortInst(portInst.getName(), islandCellInst);
                    }
                }
                continue;
            }

            // Copy clock nets
            if (clockNets.contains(net)) {
                EDIFNet islandNet = islandTopCell.createNet(netName);
                for (EDIFPortInst portInst : net.getPortInsts()) {
                    EDIFCellInst cellInst = portInst.getCellInst();
                    if (cellInst == null) continue; // ignore top-level ports
                    if (cellInst.getCellType().isStaticSource()) continue;
                    
                    EDIFCellInst islandCellInst = islandTopCell.getCellInst(cellInst.getName());
                    if (islandCellInst != null) {
                        islandNet.createPortInst(portInst.getName(), islandCellInst);
                    }
                }
                continue;
            }

            if (resetNets.contains(net)) {
                // TODO:
                continue;
            }
            
            // Copy other nets
            List<EDIFPortInst> netIncidentPorts = new ArrayList<>();
            NetSourceType netSourceType = NetSourceType.UNKNOWN;
            Boolean hasOutOfIslandPort = false;
            
            for (EDIFPortInst portInst : net.getPortInsts()) {
                EDIFCellInst cellInst = portInst.getCellInst();
                if (cellInst == null) {
                    netSourceType = NetSourceType.TOP_PORT; // ignore top-level ports
                    continue;
                }

                String cellInstName = cellInst.getName();
                EDIFCellInst islandCellInst = islandTopCell.getCellInst(cellInstName);

                if (portInst.isOutput()) { // Check net source
                    if (islandCellInst == null) {
                        netSourceType = NetSourceType.OUTSIDE_ISLAND;
                    } else {
                        netSourceType = isAnchorRegionCell(cellInst) ? NetSourceType.NEARBY_ANCHOR : NetSourceType.INSIDE_ISLAND;
                    }
                }

                if (islandCellInst == null) {
                    hasOutOfIslandPort = true;
                } else {
                    netIncidentPorts.add(portInst);
                }
            }

            assert netSourceType != NetSourceType.UNKNOWN;
            if (netSourceType == NetSourceType.OUTSIDE_ISLAND) {
                for (EDIFPortInst portInst : netIncidentPorts) {
                    EDIFCellInst cellInst = portInst.getCellInst();
                    assert isAnchorRegionCell(cellInst): "Net " + netName + " Port " + portInst.getName() + "Cell " + cellInst.getName() + " is not in anchor region";
                }
            } else {
                if (netSourceType == NetSourceType.INSIDE_ISLAND){
                    assert !hasOutOfIslandPort: "Net " + netName + " has out-of-island port";
                }
                if (netSourceType == NetSourceType.NEARBY_ANCHOR) {
                    if (netIncidentPorts.size() <= 1) continue;
                }

                EDIFNet islandNet = islandTopCell.createNet(netName);
                for (EDIFPortInst portInst : netIncidentPorts) {
                    EDIFCellInst cellInst = portInst.getCellInst();
                    EDIFCellInst islandCellInst = islandTopCell.getCellInst(cellInst.getName());
                    islandNet.createPortInst(portInst.getName(), islandCellInst);
                }
            }
        }

        //// Copy toplevel ports
        for (EDIFPort port : originTopNetlist.getTopCell().getPorts()) {
            EDIFPort islandPort = islandTopCell.createPort(port);
            int[] indicies = port.getBitBlastedIndicies();
            if (port.isBus()) {
                List<EDIFNet> internalNets = port.getInternalNets();
                for (int i = 0; i < internalNets.size(); i++) {
                    EDIFNet internalNet = internalNets.get(i);
                    if (internalNet == null) continue;
                    EDIFNet islandNet = islandTopCell.getNet(internalNet.getName());
                    if (islandNet != null) {
                        islandNet.createPortInst(islandPort, indicies[i]);
                    }
                }
            } else {
                EDIFNet internalNet = port.getInternalNet();
                if (internalNet == null) continue;
                EDIFNet islandNet = islandTopCell.getNet(internalNet.getName());
                if (islandNet != null) {
                    islandNet.createPortInst(port);
                }
            }
        }
        
        // Add context constraints(Pblock & Partition Pin & Clock Position)
        
        String centerPblockName = String.format("island_%d_%d", x, y);
        String pblockRange = islandPBlockRanges[x][y];
        addPBlockConstraint(islandDesign, centerPblockName, pblockRange, island2CellMap[x][y]);

        if (x - 1 >= 0) {
            String leftPblockName = String.format("island_%d_%d_left", x, y);
            String leftPblockRange = vertAnchorPBlockRanges[x - 1][y];
            addPBlockConstraint(islandDesign, leftPblockName, leftPblockRange, vertAnchorRegion2CellMap[x - 1][y]);
        }

        if (x < gridDimension.get(0) - 1) {
            String rightPblockName = String.format("island_%d_%d_right", x, y);
            String rightPblockRange = vertAnchorPBlockRanges[x][y];
            addPBlockConstraint(islandDesign, rightPblockName, rightPblockRange, vertAnchorRegion2CellMap[x][y]);
        }

        if (y - 1 >= 0) {
            String downPblockName = String.format("island_%d_%d_down", x, y);
            String downPblockRange = horiAnchorPBlockRanges[x][y - 1];
            addPBlockConstraint(islandDesign, downPblockName, downPblockRange, horiAnchorRegion2CellMap[x][y - 1]);
        }
        
        if (y < gridDimension.get(1) - 1) {
            String upperPblockName = String.format("island_%d_%d_up", x, y);
            String upperPblockRange = horiAnchorPBlockRanges[x][y];
            addPBlockConstraint(islandDesign, upperPblockName, upperPblockRange, horiAnchorRegion2CellMap[x][y]);
        }
        islandDesign.addXDCConstraint("create_clock -period 2 -name udp_clk [get_ports udp_clk]");

        // Write design checkpoint
        //originDesign.getNetlist().expandMacroUnisims(part.getSeries());
        //islandNetlist.expandMacroUnisims(part.getSeries());

        islandDesign.setAutoIOBuffers(false);
        islandDesign.setDesignOutOfContext(true);
        islandDesign.writeCheckpoint("./pr_result/" + designName + ".dcp");
    }

    // Helper Function
    private Boolean isAnchorRegionCell(EDIFCellInst cellInst) {
        return cell2horiAnchorRegionMap.containsKey(cellInst) || cell2vertAnchorRegionMap.containsKey(cellInst);
    }

    private void addPBlockConstraint(Design islandDesign, String pblockName, String pblockRange, List<EDIFCellInst> cellInsts) {
        islandDesign.addXDCConstraint(String.format("create_pblock %s", pblockName));
        islandDesign.addXDCConstraint(String.format("resize_pblock %s -add { %s }", pblockName, pblockRange));
        for (EDIFCellInst cellInst : cellInsts) {
            islandDesign.addXDCConstraint(String.format("add_cells_to_pblock %s %s", pblockName, cellInst.getName()));
        }
        islandDesign.addXDCConstraint(String.format("set_property IS_SOFT FALSE [get_pblocks %s]", pblockName));
        islandDesign.addXDCConstraint(String.format("set_property EXCLUDE_PLACEMENT true [get_pblocks %s]", pblockName));
    }

    private EDIFNet copyEDIFNetToNewCell(EDIFNet srcNet, EDIFCell dstCell) {
        EDIFNet dstNet = null;
        List<EDIFPortInst> sourcePortInsts = srcNet.getSourcePortInsts(true);
        assert sourcePortInsts.size() == 1;

        // Check source cellInst or source port exists in dstCell to avoid undriven net
        if (sourcePortInsts.get(0).getCellInst() == null) {
            EDIFPort srcPort = sourcePortInsts.get(0).getPort();
            if (dstCell.getPort(srcPort.getName()) == null) {
                return dstNet;
            }
        } else {
            EDIFCellInst srcCellInst = sourcePortInsts.get(0).getCellInst();
            EDIFCellInst dstCellInst = dstCell.getCellInst(srcCellInst.getName());
            if (dstCellInst == null) {
                return dstNet;
            }
        }

        for (EDIFPortInst portInst : srcNet.getPortInsts()) {
            EDIFCellInst cellInst = portInst.getCellInst();
            if (cellInst == null) { // Top-level port
                EDIFPort srcPort = portInst.getPort();
                EDIFPort dstPort = dstCell.getPort(srcPort.getName());

                if (dstPort == null) continue;
                if (dstNet == null) {
                    dstNet = dstCell.createNet(srcNet.getName());
                }
                if (srcPort.isBus()) {
                    dstNet.createPortInst(dstPort, EDIFTools.getPortIndexFromName(portInst.getName()));
                } else {
                    dstNet.createPortInst(dstPort);
                }
            } else {
                EDIFCellInst dstCellInst = dstCell.getCellInst(cellInst.getName());
                if (dstCellInst == null) continue;
                if (dstNet == null) {
                    dstNet = dstCell.createNet(srcNet.getName());
                }
                dstNet.createPortInst(portInst.getName(), dstCellInst);
            }
        }

        return dstNet;
    }

    private void generateAnchorDCP(String designName, List<EDIFCellInst> anchorCellInsts, List<EDIFCellInst> islandCellInsts, String pblockRange) {
        String partName = originDesign.getPartName();

        Design anchorDesign = new Design(designName, partName);
        EDIFNetlist anchorNetlist = anchorDesign.getNetlist();
        EDIFCell anchorTopCell = anchorNetlist.getTopCell();

        EDIFLibrary anchorPrimLib = anchorNetlist.getHDIPrimitivesLibrary();
        EDIFLibrary anchorWorkLib = anchorNetlist.getWorkLibrary();

        // Copy nets and connected cellInsts in nearby island regions
        for (EDIFCellInst cellInst : anchorCellInsts) {
            for (EDIFPortInst portInst : cellInst.getPortInsts()) {
                EDIFNet net = portInst.getNet();
                if (net.isGND() || net.isVCC() ) continue;
                if (resetNets.contains(net) || clockNets.contains(net)) continue;
                if (anchorTopCell.getNet(net.getName()) != null) continue;

                EDIFNet anchorNet = anchorTopCell.createNet(net.getName());
                
                for (EDIFPortInst expandPortInst : net.getPortInsts()) {
                    EDIFCellInst expandCellInst = expandPortInst.getCellInst();
                    if (expandCellInst == null) continue;
                    assert anchorCellInsts.contains(expandCellInst) || islandCellInsts.contains(expandCellInst);
                    String expandCellName = expandCellInst.getName();

                    EDIFCellInst anchorExpCellInst = anchorTopCell.getCellInst(expandCellName);
                    // Create cellInst if not exist
                    if (anchorExpCellInst == null) {
                        EDIFCell expandCellType = expandCellInst.getCellType();
                        assert !expandCellType.isStaticSource();
                        EDIFLibrary lib = expandCellType.getLibrary().isWorkLibrary() ? anchorWorkLib : anchorPrimLib;
                        EDIFCell anchorExpandCellType = lib.getCell(expandCellType.getName());
                        if (anchorExpandCellType == null) {
                            anchorExpandCellType = new EDIFCell(lib, expandCellType, expandCellType.getName());
                        }
                        anchorExpCellInst = anchorTopCell.createChildCellInst(expandCellName, anchorExpandCellType);
                    }
                    anchorNet.createPortInst(expandPortInst.getName(), anchorExpCellInst);
                }
            }
        }

        // Copy Vcc&GND Nets
        for (NetType netType : new NetType[]{NetType.GND, NetType.VCC}) {
            EDIFNet originStaticNet = EDIFTools.getStaticNet(netType, originTopCell, originTopNetlist);
            EDIFNet anchorStaticNet = EDIFTools.getStaticNet(netType, anchorTopCell, anchorNetlist);

            for (EDIFPortInst originPortInst : originStaticNet.getPortInsts()) {
                EDIFCellInst originCellInst = originPortInst.getCellInst();
                if (originCellInst == null) continue;
                EDIFCell originCellType = originCellInst.getCellType();
                if (originCellType.isStaticSource()) continue;
                
                EDIFCellInst anchorCellInst = anchorTopCell.getCellInst(originCellInst.getName());
                if (anchorCellInst != null) {
                    anchorStaticNet.createPortInst(originPortInst.getName(), anchorCellInst);
                }
            }
        }

        // Connect floating input ports
        for (EDIFCellInst originCellInst : islandCellInsts) {
            String cellName = originCellInst.getName();
            
            EDIFCellInst anchorCellInst = anchorTopCell.getCellInst(cellName);
            EDIFNet anchorGNDNet = EDIFTools.getStaticNet(NetType.GND, anchorTopCell, anchorNetlist);
            assert anchorCellInst != null;
            
            for (EDIFPortInst portInst : originCellInst.getPortInsts()) {
                if (portInst.isOutput()) continue;
                EDIFNet net = portInst.getNet();
                if (net.isGND() || net.isVCC()) continue;
                if (resetNets.contains(net) || clockNets.contains(net)) continue;
                
                EDIFNet anchorNet = anchorTopCell.getNet(net.getName());
                if (anchorNet == null) {
                    anchorNet = copyEDIFNetToNewCell(net, anchorTopCell);
                    if (anchorNet == null) {
                        anchorGNDNet.createPortInst(portInst.getName(), anchorCellInst);
                    }
                }
            }
        }

        // Copy Clock Net
        for (EDIFNet clockNet : clockNets) {
            EDIFPort originClkPort= originTopCell.getPort(clockNet.getName());
            EDIFPort anchorClkPort = anchorTopCell.getPort(clockNet.getName());

            if (anchorClkPort == null) {
                anchorClkPort = anchorTopCell.createPort(originClkPort);
            }

            EDIFNet anchorClkNet = anchorTopCell.createNet(clockNet.getName());
            anchorClkNet.createPortInst(anchorClkPort);

            for (EDIFPortInst portInst : clockNet.getPortInsts()) {
                EDIFCellInst cellInst = portInst.getCellInst();
                if (cellInst == null) continue;

                EDIFCellInst anchorCellInst = anchorTopCell.getCellInst(cellInst.getName());
                if (anchorCellInst != null) {
                    anchorClkNet.createPortInst(portInst.getName(), anchorCellInst);
                }
            }
        }

        // Add Context Constraints
        addPBlockConstraint(anchorDesign, designName, pblockRange, anchorCellInsts);
        anchorDesign.addXDCConstraint(String.format("create_clock -period 2 -name udp_clk [get_ports udp_clk]"));

        // Write Design Checkpoint
        anchorDesign.setAutoIOBuffers(false);
        anchorDesign.setDesignOutOfContext(true);
        anchorDesign.writeCheckpoint("./pr_result/" + designName + ".dcp");
    }

    public void separateDesignAndCreateDCPs() {
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(0); j++) {
                generateIslandDCP(i, j);
            }
        }

        // Generate dcp of horizontal anchor regions
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1) - 1; j++) {
                if (horiAnchorRegion2CellMap[i][j].size() == 0) continue;

                String designName = String.format("hori_anchor_%d_%d", i, j);
                generateAnchorDCP(designName, horiAnchorRegion2CellMap[i][j], horiAnchorIncidentCellMap[i][j], horiAnchorPBlockRanges[i][j]);
            }
        }

        // Generate dcp of vertical anchor regions
        for (int i = 0; i < gridDimension.get(0) - 1; i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                if (vertAnchorRegion2CellMap[i][j].size() == 0) continue;
                String designName = String.format("vert_anchor_%d_%d", i, j);
                generateAnchorDCP(designName, vertAnchorRegion2CellMap[i][j], vertAnchorIncidentCellMap[i][j], vertAnchorPBlockRanges[i][j]);
            }
        }
    }

    private Design syncAnchorCellToIslandDCP(Design islandDesign, Design anchorDesign, List<EDIFCellInst> anchorCells) {
        for (EDIFCellInst anchorCellInst : anchorCells) {
            String cellName = anchorCellInst.getName();
            Cell islandAnchorCell = islandDesign.getCell(cellName);
            assert islandAnchorCell != null: "CellInst not founded in island Design: " + cellName;

            Cell anchorCell = anchorDesign.getCell(cellName);
            assert anchorCell != null: "CellInst not founded in anchor Design: " + cellName;

            islandDesign.placeCell(islandAnchorCell, anchorCell.getSite(), anchorCell.getBEL());
            islandAnchorCell.setBELFixed(true);
        }
        return islandDesign;
    }

    public void syncAnchorCellToIslandDCPs() {
        Design[][] horiAnchorDesigns = new Design[gridDimension.get(0)][gridDimension.get(1)-1];
        Design[][] vertAnchorDesigns = new Design[gridDimension.get(0)-1][gridDimension.get(1)];

        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1) - 1; j++) {
                if (horiAnchorRegion2CellMap[i][j].size() == 0) continue;
                String anchorDesignName = "hori_anchor_placed_" + i + "_" + j;
                horiAnchorDesigns[i][j] = Design.readCheckpoint("./pr_result/" + anchorDesignName + ".dcp");
            }
        }

        for (int i = 0; i < gridDimension.get(0) - 1; i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                if (vertAnchorRegion2CellMap[i][j].size() == 0) continue;
                String anchorDesignName = "vert_anchor_placed_" + i + "_" + j;
                vertAnchorDesigns[i][j] = Design.readCheckpoint("./pr_result/" + anchorDesignName + ".dcp");
            }
        }

        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                String islandDesignName = String.format("island_placed_%d_%d", x, y);
                Design islandDesign = Design.readCheckpoint("./pr_result/" + islandDesignName + ".dcp");

                if (x - 1 >= 0) {
                    List<EDIFCellInst> leftAnchorCells = vertAnchorRegion2CellMap[x - 1][y];
                    if (leftAnchorCells.size() != 0) {
                        syncAnchorCellToIslandDCP(islandDesign, vertAnchorDesigns[x-1][y], leftAnchorCells);
                    }
                }

                if (x < gridDimension.get(0) - 1) {
                    List<EDIFCellInst> rightAnchorCells = vertAnchorRegion2CellMap[x][y];
                    if (rightAnchorCells.size() != 0) {
                        syncAnchorCellToIslandDCP(islandDesign, vertAnchorDesigns[x][y], rightAnchorCells);
                    }
                }

                if (y - 1 >= 0) {
                    List<EDIFCellInst> downAnchorCells = horiAnchorRegion2CellMap[x][y - 1];
                    if (downAnchorCells.size() != 0) {
                        syncAnchorCellToIslandDCP(islandDesign, horiAnchorDesigns[x][y-1], downAnchorCells);
                    }
                }

                if (y < gridDimension.get(1) - 1) {
                    List<EDIFCellInst> upAnchorCells = horiAnchorRegion2CellMap[x][y];
                    if (upAnchorCells.size() != 0) {
                        syncAnchorCellToIslandDCP(islandDesign, horiAnchorDesigns[x][y], upAnchorCells);
                    }
                }
                islandDesign.writeCheckpoint("./pr_result/" + islandDesignName + ".dcp");
            }
        }
    }

    private Design syncIslandCellToAnchorDCP(Design anchorDesign, Design[] islandDesigns, List<EDIFCellInst> anchorIncidentIslandCells) {
        // Update island cell for horizontal anchor regions
        for (EDIFCellInst edifCellInst : anchorIncidentIslandCells) {
            if (edifCellInst.getCellType().isPrimitive()) {
                String cellName = edifCellInst.getName();
                Cell islandCell = null;
                for (Design islandDesign : islandDesigns) {
                    if (islandCell == null) {
                       islandCell = islandDesign.getCell(cellName); 
                    }
                }
                assert islandCell != null: "CellInst not founded in island Design: " + cellName;
    
                EDIFCellInst anchorEDIFCell = anchorDesign.getNetlist().getTopCell().getCellInst(cellName);
                assert anchorEDIFCell != null;
                Cell anchorCell = anchorDesign.createCell(cellName, anchorEDIFCell);
                anchorDesign.placeCell(anchorCell, islandCell.getSite(), islandCell.getBEL());
                anchorCell.setBELFixed(true);
            } else {
                EDIFHierCellInst edifHierCellInst = anchorDesign.getNetlist().getTopHierCellInst().getChild(edifCellInst);
                for (EDIFHierCellInst subCellInst : edifCellInst.getCellType().getAllLeafDescendants(edifHierCellInst)) {
                    String subCellName = subCellInst.getFullHierarchicalInstName();
                    Cell islandCell = null;
                    for (Design islandDesign : islandDesigns) {
                        if (islandCell == null) {
                            islandCell = islandDesign.getCell(subCellName);
                        }
                    }
                    assert islandCell != null: "CellInst not founded in island Design: " + subCellName;
                    Cell anchorCell = anchorDesign.createCell(subCellName, subCellInst.getInst());
                    anchorDesign.placeCell(anchorCell, islandCell.getSite(), islandCell.getBEL());
                    anchorDesign.addCell(anchorCell);
                    anchorCell.setBELFixed(true);
                }
            }
        }
        return anchorDesign;
    }

    public void syncIslandCellToAnchorDCPs() {
        Design[][] islandDesigns = new Design[gridDimension.get(0)][gridDimension.get(1)];
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                String designName = String.format("island_placed_%d_%d", i, j);
                islandDesigns[i][j] = Design.readCheckpoint("./pr_result/" + designName + ".dcp");
            }
        }

        // sync horizontal anchor regions
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1) - 1; j++) {
                if (horiAnchorRegion2CellMap[i][j].size() == 0) continue;
                String anchorDesignName = "hori_anchor_" + i + "_" + j;
                Design anchorDesign = Design.readCheckpoint("./pr_result/" + anchorDesignName + ".dcp");
                Design[] nearbyIslandDesigns = new Design[]{islandDesigns[i][j], islandDesigns[i][j + 1]};
                anchorDesign = syncIslandCellToAnchorDCP(anchorDesign, nearbyIslandDesigns, horiAnchorIncidentCellMap[i][j]);
                anchorDesign.writeCheckpoint("./pr_result/" + anchorDesignName + ".dcp");
            }
        }

        // sync vertical anchor regions
        for (int i = 0; i < gridDimension.get(0) - 1; i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                if (vertAnchorRegion2CellMap[i][j].size() == 0) continue;
                String anchorDesignName = "vert_anchor_" + i + "_" + j;
                Design anchorDesign = Design.readCheckpoint("./pr_result/" + anchorDesignName + ".dcp");
                Design[] nearbyIslandDesigns = new Design[]{islandDesigns[i][j], islandDesigns[i + 1][j]};
                
                anchorDesign = syncIslandCellToAnchorDCP(anchorDesign, nearbyIslandDesigns, vertAnchorIncidentCellMap[i][j]);
                anchorDesign.writeCheckpoint("./pr_result/" + anchorDesignName + ".dcp");
            }
        }
    }
     
}