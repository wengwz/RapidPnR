package com.xilinx.rapidwright.examples;

import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.logging.Logger;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.merge.MergeDesigns;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.examples.CustomDesignMerger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ParallelIterativePnR {
    private Logger logger;
    
    private Design originDesign;
    private EDIFNetlist originTopNetlist;
    private EDIFCell originTopCell;

    private EDIFNetlist flatTopNetlist;
    private EDIFCell flatTopCell;

    private Device targetDevice;

    // Abstracted Netlist Info
    private String resetPortName;
    private String clockPortName;
    private Set<EDIFNet> clockNets;
    private Set<EDIFNet> resetNets;
    private Set<EDIFNet> ignoreNets;
    private Set<EDIFCellInst> resetTreeCellInsts;

    private List<Set<EDIFCellInst>> group2CellInstsMap;
    private Map<EDIFCellInst, Integer> cellInst2GroupMap;
    private List<Set<Integer>> edge2GroupIdxMap;

    // Island Placement Results
    private List<Integer> gridDimension;
    private List<List<Integer>> partitionGroupLocs;

    
    private Set<EDIFCellInst>[][] island2CellInstMap;
    private Map<EDIFCellInst, List<Integer>> cellInst2IslandMap;

    private Set<EDIFNet>[][] vertBoundary2NetMap;
    private Set<EDIFNet>[][] horiBoundary2NetMap;
    private Map<EDIFNet, List<Integer>> net2vertBoundaryLocMap;
    private Map<EDIFNet, List<Integer>> net2horiBoundaryLocMap;

    private static String[][] islandPBlockRanges;
    static {
        islandPBlockRanges = new String[][]{
            // {
            //     "SLICE_X55Y120:SLICE_X72Y149 DSP48E2_X8Y48:DSP48E2_X8Y59 RAMB18_X4Y48:RAMB18_X4Y59 RAMB36_X4Y24:RAMB36_X4Y29 URAM288_X1Y32:URAM288_X1Y39",
            //     "SLICE_X55Y150:SLICE_X72Y179 DSP48E2_X8Y60:DSP48E2_X8Y71 RAMB18_X4Y60:RAMB18_X4Y71 RAMB36_X4Y30:RAMB36_X4Y35 URAM288_X1Y40:URAM288_X1Y47"
            // },
            // {
            //     "SLICE_X73Y120:SLICE_X89Y149 DSP48E2_X9Y48:DSP48E2_X10Y59 RAMB18_X5Y48:RAMB18_X6Y59 RAMB36_X5Y24:RAMB36_X6Y29",
            //     "SLICE_X73Y150:SLICE_X89Y179 DSP48E2_X9Y60:DSP48E2_X10Y71 RAMB18_X5Y60:RAMB18_X6Y71 RAMB36_X5Y30:RAMB36_X6Y35"
            // }
            {
                "URAM288_X1Y36:URAM288_X1Y39 DSP48E2_X8Y54:DSP48E2_X8Y59 SLICE_X58Y131:SLICE_X71Y149",
                "URAM288_X1Y40:URAM288_X1Y43 DSP48E2_X8Y60:DSP48E2_X8Y65 SLICE_X58Y150:SLICE_X71Y168"
            },
            {
                "RAMB36_X5Y27:RAMB36_X6Y29 RAMB18_X5Y54:RAMB18_X6Y59 DSP48E2_X9Y54:DSP48E2_X9Y59 SLICE_X72Y131:SLICE_X86Y149",
                "RAMB36_X5Y30:RAMB36_X6Y32 RAMB18_X5Y60:RAMB18_X6Y65 DSP48E2_X9Y60:DSP48E2_X9Y65 SLICE_X72Y150:SLICE_X86Y168"
            }
        };
    }

    // Island boundary to INT Tile Map
    private static int[] vertBoundaryX2IntTileXMap = {46};
    private static int[] horiBoundaryY2IntTileYMap = {149};
    private static int[][] vertBoundaryY2IntTileYRangeMap = {
        {131, 149}, {150, 168}
    };
    private static int[][] horiBoundaryX2IntTileXMap = {
        {39, 46}, {48, 55}
    };

    public ParallelIterativePnR(String designFile, String netlistJsonFile, String placeJsonFile, Logger logger) {
        this.logger = logger;

        originDesign = Design.readCheckpoint(designFile);
        originTopNetlist = originDesign.getNetlist();
        originTopCell = originTopNetlist.getTopCell();
        targetDevice = Device.getDevice(originDesign.getPartName());

        flatTopNetlist = EDIFTools.createFlatNetlist(originTopNetlist, originDesign.getPartName());
        flatTopCell = flatTopNetlist.getTopCell();

        // Read abstracted netlist info in JSON format
        readAbstractNetlistJson(netlistJsonFile);
        readIslandPlaceJson(placeJsonFile);

        // Build cellInsts to island map
        buildCellInst2IslandMap();

        // Build net to boundary map
        buildNet2BoundaryMap();
    }

    private void readAbstractNetlistJson(String jsonFilePath) {
        logger.info("Start reading abstracted netlist from " + jsonFilePath);
        // Read partition result from json file
        Gson gson = new GsonBuilder().create();
        try (FileReader reader = new FileReader(jsonFilePath)) {
            PartitionResultsJson partitionResults = gson.fromJson(reader, PartitionResultsJson.class);
            clockPortName = partitionResults.clkPortName;
            resetPortName = partitionResults.rstPortName;

            clockNets = new HashSet<>();
            for (String clockNetName : partitionResults.clkNetNames) {
                EDIFNet clockNet = flatTopCell.getNet(clockNetName);
                assert clockNet != null: "Invalid name of clock net in JSON file: " + clockNetName;
                assert !clockNets.contains(clockNet): "Duplicated clock net in JSON file: " + clockNetName;
                clockNets.add(clockNet);
            }

            resetNets = new HashSet<>();
            for (String resetNetName : partitionResults.resetNetNames) {
                EDIFNet resetNet = flatTopCell.getNet(resetNetName);
                assert resetNet != null: "Invalid name of reset net in JSON file: " + resetNetName;
                assert !resetNets.contains(resetNet): "Duplicated reset net in JSON file: " + resetNetName;
                resetNets.add(resetNet);
            }

            ignoreNets = new HashSet<>();
            for (String ignoreNetName : partitionResults.ignoreNetNames) {
                EDIFNet ignoreNet = flatTopCell.getNet(ignoreNetName);
                assert ignoreNet != null: "Invalid name of ignore net in JSON file: " + ignoreNetName;
                assert !ignoreNets.contains(ignoreNet): "Duplicated ignore net in JSON file: " + ignoreNetName;
                ignoreNets.add(ignoreNet);
            }

            resetTreeCellInsts = new HashSet<>();
            for (String cellName : partitionResults.resetTreeCellNames) {
                EDIFCellInst cellInst = flatTopCell.getCellInst(cellName);
                assert cellInst != null: "Invalid name of reset tree cell in JSON file: " + cellName;
                assert !resetTreeCellInsts.contains(cellInst): "Duplicated reset tree cell in JSON file: " + cellName;
                resetTreeCellInsts.add(cellInst);
            }

            // Read abstracted groups info
            group2CellInstsMap = new ArrayList<>();
            cellInst2GroupMap = new HashMap<>();
            for (PartitionGroupJson groupJson : partitionResults.partitionGroups) {
                Set<EDIFCellInst> cellInstSet = new HashSet<>();
                Integer groupId = group2CellInstsMap.size();
                for (String cellName : groupJson.groupCellNames) {
                    EDIFCellInst cellInst = flatTopCell.getCellInst(cellName);
                    assert cellInst != null: "Invalid CellInst Name: " + cellName;
                    assert !cellInstSet.contains(cellInst) && !cellInst2GroupMap.containsKey(cellInst);
                    cellInst2GroupMap.put(cellInst, groupId);
                    cellInstSet.add(cellInst);
                }
                group2CellInstsMap.add(cellInstSet);
            }

            // Read abstracted edges info
            edge2GroupIdxMap = new ArrayList<>();
            for (PartitionEdgeJson edgeJson : partitionResults.partitionEdges) {
                Set<Integer> groupIdxSet = new HashSet<>();
                groupIdxSet.addAll(edgeJson.incidentGroupIds);
                assert groupIdxSet.size() == edgeJson.incidentGroupIds.size();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        logger.info("Complete reading abstracted netlist from " + jsonFilePath);
    }

    private void readIslandPlaceJson(String jsonFilePath) {
        logger.info("Start reading placement results from " + jsonFilePath);
        Gson gson = new GsonBuilder().create();
        try (FileReader reader = new FileReader(jsonFilePath)) {
            IslandPlaceResultJson placeResults = gson.fromJson(reader, IslandPlaceResultJson.class);
            gridDimension = placeResults.gridDimension;
            partitionGroupLocs = placeResults.partitionGroupLocs;
        } catch (IOException e) {
            e.printStackTrace();
        }
        logger.info("Complete reading placement results from " + jsonFilePath);
    }

    private void buildCellInst2IslandMap() {
        // Setup 2-D array for cell mapping
        island2CellInstMap = new HashSet[gridDimension.get(0)][gridDimension.get(1)];
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                island2CellInstMap[i][j] = new HashSet<>();
            }
        }
        cellInst2IslandMap = new HashMap<>();

        for (int i = 0; i < group2CellInstsMap.size(); i++) {
            List<Integer> groupLoc = partitionGroupLocs.get(i);
            int x = groupLoc.get(0);
            int y = groupLoc.get(1);
            Set<EDIFCellInst> cellInstSet = group2CellInstsMap.get(i);
            for (EDIFCellInst cellInst : cellInstSet) {
                island2CellInstMap[x][y].add(cellInst);
                cellInst2IslandMap.put(cellInst, Arrays.asList(x, y));
            }
        }
        // Print cell distribution
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                logger.info(String.format("The number of cells in island (%d, %d):%d", i, j, island2CellInstMap[i][j].size()));
            }
        }
        logger.info("Complete building cellInst to region map");
    }

    private void buildNet2BoundaryMap() {
        logger.info("# Start building net to boundary map");
        vertBoundary2NetMap = new HashSet[gridDimension.get(0) - 1][gridDimension.get(1)];
        horiBoundary2NetMap = new HashSet[gridDimension.get(0)][gridDimension.get(1) - 1];
        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                if (x < gridDimension.get(0) - 1) {
                    vertBoundary2NetMap[x][y] = new HashSet<>();
                }
                if (y < gridDimension.get(1) - 1) {
                    horiBoundary2NetMap[x][y] = new HashSet<>();
                }
            }
        }
        net2horiBoundaryLocMap = new HashMap<>();
        net2vertBoundaryLocMap = new HashMap<>();

        for (EDIFNet net : flatTopCell.getNets()) {
            Set<List<Integer>> netIncidentPortLocs = new HashSet<>();
            if (net.isGND() || net.isVCC()) continue;
            if (clockNets.contains(net) || resetNets.contains(net)) continue;
            if (ignoreNets.contains(net)) continue;

            for (EDIFPortInst portInst : net.getPortInsts()) {
                EDIFCellInst cellInst = portInst.getCellInst();
                if (cellInst == null) continue; // Skip top-level ports

                assert cellInst2IslandMap.containsKey(cellInst);
                netIncidentPortLocs.add(cellInst2IslandMap.get(cellInst));
            }
            assert netIncidentPortLocs.size() <= 2;
            if (netIncidentPortLocs.size() == 2) {
                List<List<Integer>> locList = new ArrayList<>(netIncidentPortLocs);
                List<Integer> loc0 = locList.get(0);
                List<Integer> loc1 = locList.get(1);
                Integer xDistance = Math.abs(loc0.get(0) - loc1.get(0));
                Integer yDistance = Math.abs(loc0.get(1) - loc1.get(1));
                assert xDistance + yDistance <= 1;
                if (xDistance == 1) {
                    Integer boundaryX = Math.min(loc0.get(0), loc1.get(0));
                    Integer boundaryY = loc0.get(1);
                    vertBoundary2NetMap[boundaryX][boundaryY].add(net);
                    net2vertBoundaryLocMap.put(net, Arrays.asList(xDistance, yDistance));
                } else {
                    Integer boundaryX = loc0.get(0);
                    Integer boundaryY = Math.min(loc0.get(1), loc1.get(1));
                    horiBoundary2NetMap[boundaryX][boundaryY].add(net);
                    net2horiBoundaryLocMap.put(net, Arrays.asList(xDistance, yDistance));
                }
            }
        }
        logger.info("# Vertical Boundary Net Count:");
        for (int x = 0; x < gridDimension.get(0) - 1; x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                logger.info(String.format("## The number of nets on vert boundary(%d, %d): %d", x, y, vertBoundary2NetMap[x][y].size()));
            }
        }
        logger.info("# Horizontal Boundary Net Count:");
        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1) - 1; y++) {
                logger.info(String.format("## The number of nets on hori boundary(%d, %d): %d", x, y, horiBoundary2NetMap[x][y].size()));
            }
        }
        logger.info("# Complete building net to boundary map");
    }

    private Design generateIslandDCP(Integer x, Integer y, String outputDir) {
        logger.info(String.format("# Start generating DCP of island (%d, %d)", x, y));
        String designName = String.format("island_%d_%d", x, y);
        String partName = originDesign.getPartName();

        Design islandDesign = new Design(designName, partName);
        EDIFNetlist islandNetlist = islandDesign.getNetlist();
        EDIFCell islandTopCell = islandNetlist.getTopCell();

        List<EDIFPort> islandLeftPortInsts = new ArrayList<>();
        List<EDIFPort> islandRightPortInsts = new ArrayList<>();
        List<EDIFPort> islandUpPortInsts = new ArrayList<>();
        List<EDIFPort> islandDownPortInsts = new ArrayList<>();

        // Copy Netlist
        //// Copy CellInsts
        logger.info("## Island Basic Info:");
        logger.info("## The number of cellInsts: " + island2CellInstMap[x][y].size());
        for (EDIFCellInst cellInst : island2CellInstMap[x][y]) {
            copyCellInstToNewDesign(cellInst, islandDesign);
        }
        
        //// Copy Nets
        for (EDIFNet net : flatTopCell.getNets()) {
            if (resetNets.contains(net)) { // TODO:
                EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, islandTopCell, islandNetlist);
                for (EDIFPortInst portInst : net.getPortInsts()) {
                    EDIFCellInst cellInst = portInst.getCellInst();
                    if (cellInst == null) continue; // Skip top-level port
                    EDIFCellInst newCellInst = islandTopCell.getCellInst(cellInst.getName());
                    if (newCellInst != null && NetlistUtils.isLutCellInst(cellInst)) {
                        gndNet.createPortInst(portInst.getName(), newCellInst);
                    }
                }
                continue;
            }

            if (ignoreNets.contains(net)) { // Connect portInst on ignored nets to VCC
                EDIFNet vccNet = EDIFTools.getStaticNet(NetType.VCC, islandTopCell, islandNetlist);
                for (EDIFPortInst portInst : net.getPortInsts()) {
                    EDIFCellInst cellInst = portInst.getCellInst();
                    if (cellInst == null) continue; // Skip top-level port
                    EDIFCellInst newCellInst = islandTopCell.getCellInst(cellInst.getName());
                    if (newCellInst != null) {
                        vccNet.createPortInst(portInst.getName(), newCellInst);
                    }
                }
                continue;
            }

            if (net.isGND() || net.isVCC()) {
                copyStaticNetToNewCell(net, islandTopCell);
                continue;
            }

            EDIFNet newNet = copyNetToNewCell(net, islandTopCell);
            if (newNet != null && !clockNets.contains(net)) {
                // check if the net has out of island portInsts
                Set<List<Integer>> outOfIslandPortInstLoc = new HashSet<>();
                Boolean isNetSrcPortOutOfIsland = false;
                
                for (EDIFPortInst portInst : net.getPortInsts()) {
                    EDIFCellInst cellInst = portInst.getCellInst();
                    if (cellInst == null) continue; // Skip top-level port

                    if (islandTopCell.getCellInst(cellInst.getName()) == null) {
                        List<Integer> loc = cellInst2IslandMap.get(cellInst);
                        outOfIslandPortInstLoc.add(loc);

                        if (portInst.isOutput()) {
                            isNetSrcPortOutOfIsland = true;
                        }
                    }
                }

                if (!outOfIslandPortInstLoc.isEmpty()) {
                    assert outOfIslandPortInstLoc.size() == 1;
                    List<Integer> loc = outOfIslandPortInstLoc.iterator().next();
                    Integer xDistance = x - loc.get(0);
                    Integer yDistance = y - loc.get(1);
                    assert Math.abs(xDistance) + Math.abs(yDistance) == 1;

                    EDIFDirection dir = isNetSrcPortOutOfIsland ? EDIFDirection.INPUT : EDIFDirection.OUTPUT;
                    String partPinName = getPartitionPinNameFromNet(newNet);
                    EDIFPort newPort = islandTopCell.createPort(partPinName, dir, 1);
                    newNet.createPortInst(newPort);

                    if (xDistance == 1) {
                        islandLeftPortInsts.add(newPort);
                    } else if (xDistance == -1) {
                        islandRightPortInsts.add(newPort);
                    }

                    if (yDistance == 1) {
                        islandDownPortInsts.add(newPort);
                    } else if (yDistance == -1) {
                        islandUpPortInsts.add(newPort);
                    }
                }
            }
        }

        //// Print Island Port Info
        logger.info(String.format("The number of left partition pin: %d", islandLeftPortInsts.size()));
        logger.info(String.format("The number of right partition pin: %d", islandRightPortInsts.size()));
        logger.info(String.format("The number of up partition pin: %d", islandUpPortInsts.size()));
        logger.info(String.format("The number of down partition pin: %d", islandDownPortInsts.size()));

        // Add Context Constraints
        //// Add PBlock Constraints
        logger.info("## Add PBlock Constraints: ");
        String pblockName = String.format("island_%d_%d", x, y);
        String pblockRange = islandPBlockRanges[x][y];
        addPBlockConstraint(islandDesign, pblockName, pblockRange);

        //// Add Timing Constraints
        logger.info("## Add Timing Constraints: ");
        Double clockPeriod = 2.0;
        addClockConstraint(islandDesign, clockPortName, clockPeriod);
        List<EDIFPort> allPortInsts = new ArrayList<>();
        allPortInsts.addAll(islandUpPortInsts);
        allPortInsts.addAll(islandDownPortInsts);
        allPortInsts.addAll(islandLeftPortInsts);
        allPortInsts.addAll(islandRightPortInsts);
        for (EDIFPort portInst : allPortInsts) {
            addIODelayConstraint(islandDesign, portInst, clockPortName, clockPeriod / 2);
        }
        
        //// Add Initial IO Constraints
        // logger.info("## Add Initial IO Constraints: ");
        // List<Integer> upBoundaryLoc = getUpBoundaryLocOfIsland(x, y);
        // if (upBoundaryLoc != null && !islandUpPortInsts.isEmpty()) {
        //     logger.info("### Add Up Boundary Constraints: ");
        //     List<List<Integer>> upBoundaryIntLocs = getIntTilesLocOfHoriBoundary(upBoundaryLoc);
        //     // List<Integer> midIntLoc = upBoundaryIntLocs.get(upBoundaryIntLocs.size() / 2);
        //     // for (EDIFPort port : islandUpPortInsts) {
        //     //     addPartitionPinLocConstraint(islandDesign, port, midIntLoc);
        //     // }
        //     Map<EDIFPort, List<Integer>> port2LocMap = randomPartPinAssignment(islandUpPortInsts, upBoundaryIntLocs, 15);
        //     for (EDIFPort port : port2LocMap.keySet()) {
        //         addPartitionPinLocConstraint(islandDesign, port, port2LocMap.get(port));
        //     }

        // }

        // List<Integer> downBoundaryLoc = getDownBoundaryLocOfIsland(x, y);
        // if (downBoundaryLoc != null && !islandDownPortInsts.isEmpty()) {
        //     logger.info("### Add Down Boundary Constraints: ");
        //     List<List<Integer>> downBoundaryIntLocs = getIntTilesLocOfHoriBoundary(downBoundaryLoc);
        //     // List<Integer> midIntLoc = downBoundaryIntLocs.get(downBoundaryIntLocs.size() / 2);
        //     // for (EDIFPort port : islandDownPortInsts) {
        //     //     addPartitionPinLocConstraint(islandDesign, port, midIntLoc);
        //     // }
        //     Map<EDIFPort, List<Integer>> port2LocMap = randomPartPinAssignment(islandDownPortInsts, downBoundaryIntLocs, 15);
        //     for (EDIFPort port : port2LocMap.keySet()) {
        //         addPartitionPinLocConstraint(islandDesign, port, port2LocMap.get(port));
        //     }
        // }

        // List<Integer> leftBoundaryLoc = getLeftBoundaryLocOfIsland(x, y);
        // if (leftBoundaryLoc != null && !islandLeftPortInsts.isEmpty()) {
        //     logger.info("### Add Left Boundary Constraints: ");
        //     List<List<Integer>> leftBoundaryIntLocs = getIntTilesLocOfVertBoundary(leftBoundaryLoc);
        //     // List<Integer> midIntLoc = leftBoundaryIntLocs.get(leftBoundaryIntLocs.size() / 2);
        //     // for (EDIFPort port : islandLeftPortInsts) {
        //     //     addPartitionPinLocConstraint(islandDesign, port, midIntLoc);
        //     // }
        //     Map<EDIFPort, List<Integer>> port2LocMap = randomPartPinAssignment(islandLeftPortInsts, leftBoundaryIntLocs, 15);
        //     for (EDIFPort port : port2LocMap.keySet()) {
        //         addPartitionPinLocConstraint(islandDesign, port, port2LocMap.get(port));
        //     }
        // }

        // List<Integer> rightBoundaryLoc = getRightBoundaryLocOfIsland(x, y);
        // if (rightBoundaryLoc != null && !islandRightPortInsts.isEmpty()) {
        //     logger.info("### Add Right Boundary Constraints: ");
        //     List<List<Integer>> rightBoundaryIntLocs = getIntTilesLocOfVertBoundary(rightBoundaryLoc);
        //     // List<Integer> midIntLoc = rightBoundaryIntLocs.get(rightBoundaryIntLocs.size() / 2);
        //     // for (EDIFPort port : islandRightPortInsts) {
        //     //     addPartitionPinLocConstraint(islandDesign, port, midIntLoc);
        //     // }
        //     Map<EDIFPort, List<Integer>> port2LocMap = randomPartPinAssignment(islandRightPortInsts, rightBoundaryIntLocs, 15);
        //     for (EDIFPort port : port2LocMap.keySet()) {
        //         addPartitionPinLocConstraint(islandDesign, port, port2LocMap.get(port));
        //     }
        // }

        // Write design checkpoint
        // islandDesign.setAutoIOBuffers(false);
        // islandDesign.setDesignOutOfContext(true);
        // islandDesign.writeCheckpoint(String.format("%s/island_%d_%d.dcp", outputDir, x, y));
        logger.info(String.format("# Complete generating DCP of island (%d, %d)", x, y));
        return islandDesign;
    }

    public void generateIslandDCPs(String outputDir) {
        logger.info("# Start generating DCPs of all islands");
        Design[][] islandDesigns = new Design[gridDimension.get(0)][gridDimension.get(1)];
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
               islandDesigns[i][j] = generateIslandDCP(i, j, outputDir);
            }
        }

        // Add Partition Pin Position Constraints
        logger.info("# Start adding partition pin position constraints");
        //// Add Vertical Partition Pin Position Constraints
        for (int x = 0; x < gridDimension.get(0) - 1; x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                List<List<Integer>> intTileLocs = getIntTilesLocOfVertBoundary(Arrays.asList(x, y));
                List<EDIFNet> boundaryNets = new ArrayList(vertBoundary2NetMap[x][y]);

                List<Integer> leftIslandLoc = getLeftIslandLocOfVertBoundary(Arrays.asList(x, y));
                List<Integer> rightIslandLoc = getRightIslandLocOfVertBoundary(Arrays.asList(x, y));
                Design leftIslandDesign = islandDesigns[leftIslandLoc.get(0)][leftIslandLoc.get(1)];
                Design rightIslandDesign = islandDesigns[rightIslandLoc.get(0)][rightIslandLoc.get(1)];
                Map<EDIFNet, List<Integer>> net2IntLocMap = randomPartPinAssignment(boundaryNets, intTileLocs, 10);
                setPartPinLocConstraint(net2IntLocMap, leftIslandDesign);

                for (List<Integer> tileLoc : intTileLocs) {
                    tileLoc.set(0, tileLoc.get(0) + 1);
                }
                setPartPinLocConstraint(net2IntLocMap, rightIslandDesign);
                // updatePartPinPosOfBoundary(boundaryNets, intTileDeviceLocs, leftIslandDesign, rightIslandDesign);
            }
        }
        //// Add Horizontal Partition Pin Position Constraints
        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1) - 1; y++) {
                List<List<Integer>> intTileLocs = getIntTilesLocOfHoriBoundary(Arrays.asList(x, y));
                List<EDIFNet> boundaryNets = new ArrayList(horiBoundary2NetMap[x][y]);

                List<Integer> upIslandLoc = getUpIslandLocOfHoriBoundary(Arrays.asList(x, y));
                List<Integer> downIslandLoc = getDownIslandLocOfHoriBoundary(Arrays.asList(x, y));
                Design upIslandDesign = islandDesigns[upIslandLoc.get(0)][upIslandLoc.get(1)];
                Design downIslandDesign = islandDesigns[downIslandLoc.get(0)][downIslandLoc.get(1)];
                
                Map<EDIFNet, List<Integer>> net2IntLocMap = randomPartPinAssignment(boundaryNets, intTileLocs, 15);
                setPartPinLocConstraint(net2IntLocMap, downIslandDesign);

                for (List<Integer> tileLoc : intTileLocs) {
                    tileLoc.set(1, tileLoc.get(1) + 1);
                }
                setPartPinLocConstraint(net2IntLocMap, upIslandDesign);
            }
        }

        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                islandDesigns[i][j].setAutoIOBuffers(false);
                islandDesigns[i][j].setDesignOutOfContext(true);
                islandDesigns[i][j].writeCheckpoint(String.format("%s/island_%d_%d.dcp", outputDir, i, j));
            }
        }
        logger.info("# Complete writing DCPs of all islands");
    }

    private void setPartPinLocConstraint(Map<EDIFNet, List<Integer>> net2LocMap, Design design) {
        for (EDIFNet net : net2LocMap.keySet()) {
            List<Integer> intTileLoc = net2LocMap.get(net);
            String partPinName = getPartitionPinNameFromNet(net);
            EDIFPort port = design.getNetlist().getTopCell().getPort(partPinName);
            assert port != null;
            addPartitionPinLocConstraint(design, port, intTileLoc);
        }
    }

    public void updatePartitionPinPos(String dcpFilePrefixs) {
        logger.info("# Start updating position of partition pins");

        //Read DCP of all islands
        logger.info("## Read placed DCP of all islands");
        Design[][] islandDesigns = new Design[gridDimension.get(0)][gridDimension.get(1)];
        //Design[][] islandUnplacedDesigns = new Design[gridDimension.get(0)][gridDimension.get(1)];
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                String dcpFile = String.format("%s_%d_%d.dcp", dcpFilePrefixs, i, j);
                islandDesigns[i][j] = Design.readCheckpoint(dcpFile);
                //islandUnplacedDesigns[i][j] = Design.readCheckpoint(String.format("./pr_result2/fft-16/island_%d_%d.dcp", i, j));
            }
        }

        // Update partition pin of vertical boundary
        for (int x = 0; x < gridDimension.get(0) - 1; x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                logger.info(String.format("## Update partition pins of vertical boundary (%d, %d)", x, y));
                List<List<Integer>> intTileLocs = getIntTilesLocOfVertBoundary(Arrays.asList(x, y));
                List<List<Integer>> intTileDeviceLocs = getDeviceLocsFromIntTileLocs(intTileLocs);
                logger.info("### Get device loc of all INT Tiles on this boundary:");
                for (int i = 0; i < intTileLocs.size(); i++) {
                    Integer intX = intTileLocs.get(i).get(0);
                    Integer intY = intTileLocs.get(i).get(1);
                    Integer intDevX = intTileDeviceLocs.get(i).get(0);
                    Integer intDevY = intTileDeviceLocs.get(i).get(1);
                    logger.info(String.format("Device position of INT_%d_%d: (%d, %d)", intX, intY, intDevX, intDevY));
                }

                Set<EDIFNet> boundaryNets = vertBoundary2NetMap[x][y];
                logger.info("### Get all nets crossing this boundary: " + boundaryNets.size());
                List<Integer> leftIslandLoc = getLeftIslandLocOfVertBoundary(Arrays.asList(x, y));
                List<Integer> rightIslandLoc = getRightIslandLocOfVertBoundary(Arrays.asList(x, y));
                Design leftIslandDesign = islandDesigns[leftIslandLoc.get(0)][leftIslandLoc.get(1)];
                Design rightIslandDesign = islandDesigns[rightIslandLoc.get(0)][rightIslandLoc.get(1)];
                
                updatePartPinPosOfBoundary(boundaryNets, intTileDeviceLocs, leftIslandDesign, rightIslandDesign);
                // for (EDIFNet net : boundaryNets) {
                //     logger.info("### Update partition pin for net:  " + net.getName());
                //     List<List<Integer>> cellDeviceLocs = new ArrayList<>();
                //     List<String> cellInstNames = NetlistUtils.getCellInstsNameOfNet(net);
                //     for (String cellInstName : cellInstNames) {
                //         Cell cell = leftIslandDesign.getCell(cellInstName);
                //         if (cell == null) {
                //             cell = rightIslandDesign.getCell(cellInstName);
                //         }
                //         assert cell != null;
                //         Tile cellTile = cell.getTile();
                //         cellDeviceLocs.add(Arrays.asList(cellTile.getColumn(), cellTile.getRow()));
                //     }
                //     Integer bestTileIdx = getBestIntTileLocOfPartPin(intTileDeviceLocs, cellDeviceLocs);
                //     List<Integer> bestIntTileLoc = intTileLocs.get(bestTileIdx);
                //     logger.info(String.format("#### Get best INT Tile INT_%d_%d", bestIntTileLoc.get(0), bestIntTileLoc.get(1)));
                    
                //     logger.info("#### Update partition pin position");
                //     String partPinName = getPartitionPinNameFromNet(net);
                //     EDIFPort leftIslandPort = leftIslandDesign.getNetlist().getTopCell().getPort(partPinName);
                //     assert leftIslandPort != null;
                //     addPartitionPinLocConstraint(leftIslandDesign, leftIslandPort, bestIntTileLoc, updateConstrGrp);
                //     EDIFPort rightIslandPort = rightIslandDesign.getNetlist().getTopCell().getPort(partPinName);
                //     assert rightIslandPort != null;
                //     addPartitionPinLocConstraint(rightIslandDesign, rightIslandPort, bestIntTileLoc, updateConstrGrp);
                // }
            }
        }

        // Update partition pin of horizontal boundary
        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1) - 1; y++) {
                logger.info(String.format("## Update partition pins of horizontal boundary (%d, %d)", x, y));
                List<List<Integer>> intTileLocs = getIntTilesLocOfHoriBoundary(Arrays.asList(x, y));
                List<List<Integer>> intTileDeviceLocs = getDeviceLocsFromIntTileLocs(intTileLocs);
                logger.info("### Get device location of all INT Tiles on this boundary");
                for (int i = 0; i < intTileLocs.size(); i++) {
                    Integer intX = intTileLocs.get(i).get(0);
                    Integer intY = intTileLocs.get(i).get(1);
                    Integer intDevX = intTileDeviceLocs.get(i).get(0);
                    Integer intDevY = intTileDeviceLocs.get(i).get(1);
                    logger.info(String.format("Device position of INT_%d_%d: (%d, %d)", intX, intY, intDevX, intDevY));
                }

                Set<EDIFNet> boundaryNets = horiBoundary2NetMap[x][y];
                logger.info("### Get all nets crossing this boundary:" + boundaryNets.size());
                List<Integer> upIslandLoc = getUpIslandLocOfHoriBoundary(Arrays.asList(x, y));
                List<Integer> downIslandLoc = getDownIslandLocOfHoriBoundary(Arrays.asList(x, y));
                logger.info("### Get up island location: " + upIslandLoc);
                logger.info("### Get down island location: " + downIslandLoc);
                Design upIslandDesign = islandDesigns[upIslandLoc.get(0)][upIslandLoc.get(1)];
                Design downIslandDesign = islandDesigns[downIslandLoc.get(0)][downIslandLoc.get(1)];
                updatePartPinPosOfBoundary(boundaryNets, intTileDeviceLocs, upIslandDesign, downIslandDesign);
            }
        }

        // Write update DCP
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                String dcpFile = String.format("%s_sync_%d_%d.dcp", dcpFilePrefixs, i, j);
                //islandDesigns[i][j].unplaceDesign();
                islandDesigns[i][j].setAutoIOBuffers(false);
                islandDesigns[i][j].setDesignOutOfContext(true);
                islandDesigns[i][j].writeCheckpoint(dcpFile);
            }
        }
    }


    public void mergeSeparateDesigns(String dcpFilePrefix) {
        logger.info("# Start merging separate designs");
        Design[][] routedIslandDesigns = new Design[gridDimension.get(0)][gridDimension.get(1)];
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                String dcpFile = String.format("%s_%d_%d.dcp", dcpFilePrefix, i, j);
                routedIslandDesigns[i][j] = Design.readCheckpoint(dcpFile);
            }
        }

        // Unroute Inter-Island Nets
        logger.info("## Sync name of inter-island nets");
        // for (int i = 0; i < gridDimension.get(0) - 1; i++) {
        //     for (int j = 0; j < gridDimension.get(1); j++) {
        //         logger.info("## Unroute vertical boundary: " + i + ", " + j);
        //         Set<EDIFNet> boundaryNets = vertBoundary2NetMap[i][j];
        //         List<Integer> leftIslandLoc = getLeftIslandLocOfVertBoundary(Arrays.asList(i, j));
        //         List<Integer> rightIslandLoc = getRightIslandLocOfVertBoundary(Arrays.asList(i, j));
        //         Design leftIslandDesign = routedIslandDesigns[leftIslandLoc.get(0)][leftIslandLoc.get(1)];
        //         Design rightIslandDesign = routedIslandDesigns[rightIslandLoc.get(0)][rightIslandLoc.get(1)];
        //         for (EDIFNet net : boundaryNets) {
        //             String netName = net.getName();
        //             logger.info("### Unroute net: " + netName);
        //             Net leftIslandNet = leftIslandDesign.getNet(netName);
        //             Net rightIslandNet = rightIslandDesign.getNet(netName);
        //             assert leftIslandNet != null && rightIslandNet != null;
        //             leftIslandNet.unroute();
        //             rightIslandNet.unroute();
        //         }
        //     }
        // }

        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1) - 1; j++) {
                logger.info("## Sync net name for horizontal boundary: " + i + ", " + j);
                Set<EDIFNet> boundaryNets = horiBoundary2NetMap[i][j];
                List<Integer> upIslandLoc = getUpIslandLocOfHoriBoundary(Arrays.asList(i, j));
                List<Integer> downIslandLoc = getDownIslandLocOfHoriBoundary(Arrays.asList(i, j));
                Design upIslandDesign = routedIslandDesigns[upIslandLoc.get(0)][upIslandLoc.get(1)];
                Design downIslandDesign = routedIslandDesigns[downIslandLoc.get(0)][downIslandLoc.get(1)];
                
                for (EDIFNet net : boundaryNets) {
                    String netName = net.getName();
                    logger.info("### Sync name of net: " + netName);
                    Net upIslandNet = upIslandDesign.getNet(netName);
                    Net downIslandNet = downIslandDesign.getNet(netName);
                    assert upIslandNet != null || downIslandNet != null;
                    if (upIslandNet == null) {
                        EDIFHierNet hierNet = flatTopNetlist.getHierNetFromName(netName);
                        EDIFHierNet parentNet = flatTopNetlist.getParentNet(hierNet);
                        upIslandNet = upIslandDesign.getNet(parentNet.getHierarchicalNetName());
                        downIslandNet.setName(parentNet.getHierarchicalNetName());
                    }
                    if (downIslandNet == null) {
                        EDIFHierNet hierNet = flatTopNetlist.getHierNetFromName(netName);
                        EDIFHierNet parentNet = flatTopNetlist.getParentNet(hierNet);
                        downIslandNet = downIslandDesign.getNet(parentNet.getHierarchicalNetName());
                        upIslandNet.setName(parentNet.getHierarchicalNetName());
                    }
                    assert upIslandNet != null && downIslandNet != null;
                }
            }
        }

        Design mergedDesign = new Design("island_merged", originDesign.getPartName());
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                logger.info(String.format("## Merge island (%d, %d)", i, j));
                MergeDesigns.mergeDesigns(()->new CustomDesignMerger(), mergedDesign, routedIslandDesigns[i][j]);
            }
        }

        // Set Constraints
        for (int i = 0; i < gridDimension.get(0); i++) {
            for (int j = 0; j < gridDimension.get(1); j++) {
                drawPblock(mergedDesign, String.format("island_%d_%d", i, j), islandPBlockRanges[i][j]);
            }
        }

        Double clockPeriod = 2.0;
        addClockConstraint(mergedDesign, clockPortName, clockPeriod);
        // Write Checkpoint
        mergedDesign.setAutoIOBuffers(false);
        mergedDesign.setDesignOutOfContext(true);
        mergedDesign.writeCheckpoint(String.format("%s_merged.dcp", dcpFilePrefix));
    }

    // Helper functions
    private void updatePartPinPosOfBoundary(
        Set<EDIFNet> boundaryNets, List<List<Integer>> intDeviceLocs, Design nearIsland0, Design nearIsland1
    ) {
        ConstraintGroup constrGrp = ConstraintGroup.LATE;
        for (EDIFNet net : boundaryNets) {
            logger.info("### Update partition pin for net:  " + net.getName());
            List<List<Integer>> cellDeviceLocs = new ArrayList<>();
            List<String> cellInstNames = NetlistUtils.getCellInstsNameOfNet(net);
            for (String cellInstName : cellInstNames) {
                List<List<Integer>> leafCellLocs = getLeafCellTileDeviceLocs(cellInstName, nearIsland0);
                if (leafCellLocs == null) {
                    leafCellLocs = getLeafCellTileDeviceLocs(cellInstName, nearIsland1);
                }
                assert leafCellLocs != null && leafCellLocs.size() >= 1: String.format("Unable to find cell %s in nearby islands", cellInstName);
                
                cellDeviceLocs.addAll(leafCellLocs);
            }

            List<Integer> bestTileDevLoc = getBestIntTileDeviceLoc(intDeviceLocs, cellDeviceLocs);
            Tile bestTile = targetDevice.getTile(bestTileDevLoc.get(1), bestTileDevLoc.get(0));
            logger.info(String.format("Get best INT Tile %s", bestTile.getName()));
            
            logger.info("Update partition pin position constraints");
            String partPinName = getPartitionPinNameFromNet(net);
            EDIFPort leftIslandPort = nearIsland0.getNetlist().getTopCell().getPort(partPinName);
            assert leftIslandPort != null;
            addPartitionPinLocConstraint(nearIsland0, leftIslandPort, bestTile.getName(), constrGrp);

            EDIFPort rightIslandPort = nearIsland1.getNetlist().getTopCell().getPort(partPinName);
            assert rightIslandPort != null;
            addPartitionPinLocConstraint(nearIsland1, rightIslandPort, bestTile.getName(), constrGrp);
        }
    }

    private Map<EDIFNet, List<Integer>> randomPartPinAssignment(List<EDIFNet> nets, List<List<Integer>> intTileLocs, Integer maxPartPinPerInt) {
        Map<EDIFNet, List<Integer>> net2IntLocMap = new HashMap<>();
        List<Integer> intTile2PinCount = new ArrayList<>(Collections.nCopies(intTileLocs.size(), 0));

        Integer middleIntIdx = intTileLocs.size() / 2;
        Integer maxTileNumUsed = (int) Math.ceil((double) nets.size() / maxPartPinPerInt);
        assert maxTileNumUsed <= intTileLocs.size();
        
        Integer idxOffset = middleIntIdx - maxTileNumUsed / 2;
        Random randomNum = new Random();
        for (EDIFNet net : nets) {
            Integer intTileIdx = middleIntIdx;
            while (intTile2PinCount.get(intTileIdx) >= maxPartPinPerInt) {
                Integer randomIdx = randomNum.nextInt(maxTileNumUsed);
                intTileIdx = idxOffset + randomIdx;
                if (intTileIdx >= nets.size()) {
                    intTileIdx = nets.size() - 1;
                }
            }
            net2IntLocMap.put(net, intTileLocs.get(intTileIdx));
            intTile2PinCount.set(intTileIdx, intTile2PinCount.get(intTileIdx) + 1);
        }
        
        return net2IntLocMap;
    }

    private List<List<Integer>> getLeafCellTileDeviceLocs(String cellName, Design design) {
        List<List<Integer>> childCellTileLocs = new ArrayList<>();
        EDIFCellInst cellInst = design.getNetlist().getTopCell().getCellInst(cellName);
        if (cellInst == null) {
            return null;
        }
        if (cellInst.getCellType().isLeafCellOrBlackBox()) {
            Cell cell = design.getCell(cellName);
            childCellTileLocs.add(Arrays.asList(cell.getTile().getColumn(), cell.getTile().getRow()));
            return childCellTileLocs;
        } else {
            EDIFHierCellInst hierCellInst = design.getNetlist().getTopHierCellInst().getChild(cellInst);
            for (EDIFHierCellInst subCellInst : cellInst.getCellType().getAllLeafDescendants(hierCellInst)) {
                String subCellName = subCellInst.getFullHierarchicalInstName();
                Cell subCell = design.getCell(subCellName);
                assert subCell != null;
                childCellTileLocs.add(Arrays.asList(subCell.getTile().getColumn(), subCell.getTile().getRow()));
            }
            return childCellTileLocs;            
        }

    }
    private List<Integer> getBestIntTileDeviceLoc(List<List<Integer>> intTileLocs, List<List<Integer>> cellLocs) {
        Integer minTotalWL = Integer.MAX_VALUE;
        List<Integer> bestIntTileLoc = null;
        Integer bestTileIdx = -1;
        for (int i = 0; i < intTileLocs.size(); i++) {
            List<Integer> intTileLoc = intTileLocs.get(i);
            Integer totalWL = 0;
            Integer intTileX = intTileLoc.get(0);
            Integer intTileY = intTileLoc.get(1);
            for (List<Integer> cellLoc : cellLocs) {
                totalWL += Math.abs(intTileX - cellLoc.get(0)) + Math.abs(intTileY - cellLoc.get(1));
            }
            if (totalWL < minTotalWL) {
                minTotalWL = totalWL;
                bestIntTileLoc = intTileLoc;
                bestTileIdx = i;
            }
        }
        return bestIntTileLoc;
    }

    private void copyCellInstToNewDesign(EDIFCellInst cellInst, Design newDesign) {
        EDIFNetlist newNetlist = newDesign.getNetlist();
        EDIFCell newTopCell = newNetlist.getTopCell();
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

        EDIFCellInst newCellInst = newTopCell.createChildCellInst(cellInst.getName(), newCellType);
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
        List<EDIFPortInst> incidentTopPortInsts = new ArrayList<>();

        for (EDIFPortInst portInst : srcNet.getPortInsts()) {
            EDIFCellInst cellInst = portInst.getCellInst();
            if (cellInst == null) { // Top-level port
                incidentTopPortInsts.add(portInst);
            } else {
                EDIFCellInst newCellInst = newCell.getCellInst(cellInst.getName());
                if (newCellInst == null) continue;
                if (newNet == null) {
                    newNet = newCell.createNet(srcNet.getName());
                }
                newNet.createPortInst(portInst.getName(), newCellInst);
            }
        }

        if (newNet != null) { // copy top-level ports if net has portInsts inside island
            for (EDIFPortInst portInst : incidentTopPortInsts) {
                EDIFPort port = portInst.getPort();
                EDIFPort newPort = newCell.getPort(EDIFTools.getRootBusName(port.getName()));
                if (newPort == null) {
                    newPort = newCell.createPort(port);
                }
                if (newPort.isBus()) {
                    Integer index = EDIFTools.getPortIndexFromName(portInst.getName());
                    newNet.createPortInst(newPort, newPort.getPortIndexFromNameIndex(index));
                } else {
                    newNet.createPortInst(newPort);
                }
            }
        }

        return newNet;
    }

    private List<Integer> getDeviceLocFromIntTileLoc(List<Integer> intTileLoc) {
        String intTileName = String.format("INT_X%dY%d", intTileLoc.get(0), intTileLoc.get(1));
        Tile intTile = targetDevice.getTile(intTileName);
        return Arrays.asList(intTile.getColumn(), intTile.getRow());
    }

    private List<List<Integer>> getDeviceLocsFromIntTileLocs(List<List<Integer>> intTileLocs) {
        List<List<Integer>> deviceLocs = new ArrayList<>();
        for (List<Integer> intTileLoc : intTileLocs) {
            deviceLocs.add(getDeviceLocFromIntTileLoc(intTileLoc));
        }
        return deviceLocs;
    }

    private List<Integer> getLeftBoundaryLocOfIsland(Integer x, Integer y) {
        if (x - 1 >= 0) {
            return Arrays.asList(x - 1, y);
        } else {
            return null;
        }
    }
    private List<Integer> getRightBoundaryLocOfIsland(Integer x, Integer y) {
        if (x < gridDimension.get(0) - 1) {
            return Arrays.asList(x, y);
        } else {
            return null;
        }
    }
    private List<Integer> getUpBoundaryLocOfIsland(Integer x, Integer y) {
        if (y < gridDimension.get(1) - 1) {
            return Arrays.asList(x, y);
        } else {
            return null;
        }
    }

    private List<Integer> getDownBoundaryLocOfIsland(Integer x, Integer y) {
        if (y - 1 >= 0) {
            return Arrays.asList(x, y - 1);
        } else {
            return null;
        }
    }

    private List<Integer> getLeftIslandLocOfVertBoundary(List<Integer> loc) {
        Integer x = loc.get(0);
        Integer y = loc.get(1);
        return Arrays.asList(x, y);
    }

    private List<Integer> getRightIslandLocOfVertBoundary(List<Integer> loc) {
        Integer x = loc.get(0);
        Integer y = loc.get(1);
        return Arrays.asList(x + 1, y);
    }

    private List<Integer> getUpIslandLocOfHoriBoundary(List<Integer> loc) {
        Integer x = loc.get(0);
        Integer y = loc.get(1);
        return Arrays.asList(x, y + 1);
    }
    private List<Integer> getDownIslandLocOfHoriBoundary(List<Integer> loc) {
        Integer x = loc.get(0);
        Integer y = loc.get(1);
        return Arrays.asList(x, y);
    }

    private List<List<Integer>> getIntTilesLocOfVertBoundary(List<Integer> loc) {
        Integer x = loc.get(0);
        Integer y = loc.get(1);
        List<List<Integer>> intTileLocs = new ArrayList<>();
        Integer intTileX = vertBoundaryX2IntTileXMap[x];
        for (int i = vertBoundaryY2IntTileYRangeMap[y][0]; i <= vertBoundaryY2IntTileYRangeMap[y][1]; i++) {
            intTileLocs.add(Arrays.asList(intTileX, i));
        }
        return intTileLocs;
    }
    private List<List<Integer>> getIntTilesLocOfHoriBoundary(List<Integer> loc) {
        Integer x = loc.get(0);
        Integer y = loc.get(1);
        List<List<Integer>> intTileLocs = new ArrayList<>();
        Integer intTileY = horiBoundaryY2IntTileYMap[y];
        for (int i = horiBoundaryX2IntTileXMap[x][0]; i <= horiBoundaryX2IntTileXMap[x][1]; i++) {
            intTileLocs.add(Arrays.asList(i, intTileY));
        }
        return intTileLocs;
    }

    private Boolean isCrossIslandNet(EDIFNet net) {
        return net2horiBoundaryLocMap.containsKey(net) || net2vertBoundaryLocMap.containsKey(net);
    }
    
    private void drawPblock(Design design, String pblockName, String pblockRange) {
        design.addXDCConstraint(String.format("create_pblock %s", pblockName));
        design.addXDCConstraint(String.format("resize_pblock %s -add { %s }", pblockName, pblockRange));
    }
    private void addPBlockConstraint(Design islandDesign, String pblockName, String pblockRange) {
        islandDesign.addXDCConstraint(String.format("create_pblock %s", pblockName));
        islandDesign.addXDCConstraint(String.format("resize_pblock %s -add { %s }", pblockName, pblockRange));
        // for (EDIFCellInst cellInst : cellInsts) {
        //     islandDesign.addXDCConstraint(String.format("add_cells_to_pblock %s -top", pblockName, cellInst.getName()));
        // }
        islandDesign.addXDCConstraint(String.format("add_cells_to_pblock %s -top", pblockName));
        islandDesign.addXDCConstraint(String.format("set_property IS_SOFT FALSE [get_pblocks %s]", pblockName));
        islandDesign.addXDCConstraint(String.format("set_property EXCLUDE_PLACEMENT true [get_pblocks %s]", pblockName));
        islandDesign.addXDCConstraint(String.format("set_property CONTAIN_ROUTING true [get_pblocks %s]", pblockName));
    }

    private void addClockConstraint(Design design, String clkPortName, Double period) {
        String constrString = String.format("create_clock -period %f -name %s [get_ports %s]", period, clockPortName, clockPortName);
        design.addXDCConstraint(constrString);
        logger.info("Add Clock Constraint: " + constrString);
    }

    private void addIODelayConstraint(Design design, EDIFPort port, String clkName, Double delay) {
        String commandStr = port.isInput() ? "set_input_delay" : "set_output_delay";
        String portName = port.getName();
        String constrStr = String.format("%s -clock %s %f %s", commandStr, clkName, delay, portName);
        design.addXDCConstraint(constrStr);
        logger.info("Add IO Delay Constraint: " + constrStr);
    }

    private String getPartPinConstrString(EDIFPort port, List<Integer> intLoc) {
        String portName = port.getName();
        Integer intX = intLoc.get(0);
        Integer intY = intLoc.get(1);
        return String.format("set_property HD.PARTPIN_LOCS INT_X%dY%d [get_ports %s]", intX, intY, portName);
    }

    private String getPartPinConstrString(EDIFPort port, String intName) {
        String portName = port.getName();
        return String.format("set_property HD.PARTPIN_LOCS %s [get_ports %s]", intName, portName);
    }

    private void addPartitionPinLocConstraint(Design design, EDIFPort port, List<Integer> intLoc) {
        String constrStr = getPartPinConstrString(port, intLoc);
        design.addXDCConstraint(constrStr);
        logger.info("Add PartPin Loc Constraint: " + constrStr);
    }

    private void addPartitionPinLocConstraint(Design design, EDIFPort port, List<Integer> intLoc, ConstraintGroup group) {
        String constrStr = getPartPinConstrString(port, intLoc);
        design.addXDCConstraint(group, constrStr);
        logger.info("Add PartPin Loc Constraint: " + constrStr);
    }

    private void addPartitionPinLocConstraint(Design design, EDIFPort port, String intName, ConstraintGroup group) {
        String constrStr = getPartPinConstrString(port, intName);
        design.addXDCConstraint(group, constrStr);
        logger.info("Add PartPin Loc Constraint: " + constrStr);
    }

    private String getPartitionPinNameFromNet(EDIFNet net) {
        String netName = net.getName();
        return netName + "_part_pin";
    }
}
