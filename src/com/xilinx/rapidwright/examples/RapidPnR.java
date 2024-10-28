package com.xilinx.rapidwright.examples;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.examples.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.Job;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Tile;
import static com.xilinx.rapidwright.examples.NameConvention.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class RapidPnR {
    private Logger logger;
    private int logHierDepth = 0; // log prefix to indicate hierarchy of flow

    private DirectoryManager dirManager;
    
    private Design originDesign;
    private EDIFNetlist originTopNetlist;
    private EDIFCell originTopCell;

    private EDIFNetlist flatTopNetlist;
    private EDIFCell flatTopCell;

    private Device targetDevice;

    // Abstracted Netlist Info
    private List<String> resetPortNames;
    private List<String> clockPortNames;
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

    private Set<EDIFCellInst>[][] vertBoundary2CellInstMap;
    private Set<EDIFCellInst>[][] horiBoundary2CellInstMap;
    private Map<EDIFCellInst, List<Integer>> cellInst2vertBoundaryMap;
    private Map<EDIFCellInst, List<Integer>> cellInst2horiBoundaryMap;

    // Configuration Parameters
    private Map<String, Double> clk2PeriodMap = new HashMap<>();
    private Double partPin2RegDelay;
    private int maxThreadNum = 24;
    private String vivadoPath = "vivado";


    private static String[][] islandPBlockRanges;
    static {
        islandPBlockRanges = new String[][] {
            {
                "SLICE_X0Y300:SLICE_X83Y445 BUFG_GT_X0Y120:BUFG_GT_X0Y167 BUFG_GT_SYNC_X0Y75:BUFG_GT_SYNC_X0Y104 CMACE4_X0Y3:CMACE4_X0Y3 DSP48E2_X0Y120:DSP48E2_X9Y177 RAMB18_X0Y120:RAMB18_X5Y177 RAMB36_X0Y60:RAMB36_X5Y88 URAM288_X0Y80:URAM288_X1Y115",
                "SLICE_X0Y454:SLICE_X83Y599 CMACE4_X0Y5:CMACE4_X0Y5 DSP48E2_X0Y182:DSP48E2_X9Y239 RAMB18_X0Y182:RAMB18_X5Y239 RAMB36_X0Y91:RAMB36_X5Y119 URAM288_X0Y124:URAM288_X1Y159"
            },
            {
                "SLICE_X91Y300:SLICE_X168Y445 DSP48E2_X11Y120:DSP48E2_X18Y177 RAMB18_X7Y120:RAMB18_X11Y177 RAMB36_X7Y60:RAMB36_X11Y88 URAM288_X2Y80:URAM288_X3Y115",
                "SLICE_X91Y454:SLICE_X168Y599 DSP48E2_X11Y182:DSP48E2_X18Y239 RAMB18_X7Y182:RAMB18_X11Y239 RAMB36_X7Y91:RAMB36_X11Y119 URAM288_X2Y124:URAM288_X3Y159"
            }
        };
    }

    private static String[][] horiBoundaryPBlockRanges;
    static {
        horiBoundaryPBlockRanges = new String[][] {
            {
                "SLICE_X0Y446:SLICE_X87Y453"
            },
            {
                "SLICE_X88Y446:SLICE_X168Y453"
            }
        };
    }

    private static String[][] vertBoundaryPBlockRanges;
    static {
        vertBoundaryPBlockRanges = new String[][] {
            {
                "SLICE_X90Y360:SLICE_X90Y445 SLICE_X84Y300:SLICE_X89Y445",
                "SLICE_X90Y454:SLICE_X90Y539 SLICE_X84Y454:SLICE_X89Y599"
            }
        };
    }

    private static int[] vertBoundaryX2IntTileXMap = {56};
    private static int[] horiBoundaryY2IntTileYMap = {449};
    private static int[][] vertBoundaryY2IntTileYRangeMap = {
        {300, 449}, {450, 599}
    };
    private static int[][] horiBoundaryX2IntTileXMap = {
        {0, 56}, {57, 107}
    };

    public RapidPnR(String designFile, String netlistJsonFile, String placeJsonFile, String rootDir) {
        this.dirManager = new DirectoryManager(rootDir);
        setupLogger();

        logger.info(logMsg("Start reading input files"));
        newSubStep();
        originDesign = Design.readCheckpoint(designFile);
        originTopNetlist = originDesign.getNetlist();
        originTopCell = originTopNetlist.getTopCell();
        targetDevice = Device.getDevice(originDesign.getPartName());

        
        flatTopNetlist = originTopNetlist;
        flatTopCell = originTopCell;

        // Read abstracted netlist info in JSON format
        readAbstractNetlistJson(netlistJsonFile);
        readIslandPlaceJson(placeJsonFile);

        endSubStep();

        logger.info(logMsg("Complete reading input files"));

        // Setup configuration parameters
        partPin2RegDelay = 0.2;
        for (String clkPortName : clockPortNames) {
            clk2PeriodMap.put(clkPortName, 4.0);
        }

        logger.info(logMsg("Start preprocessing input files"));
        newSubStep();
        // Build cellInsts to island map
        buildCellInst2IslandMap();

        // Build net to boundary map
        buildNet2BoundaryMap();

        // Build cellInst to boundary map
        buildCellInst2BoundaryMap();
        endSubStep();
        logger.info(logMsg("Complete preprocessing input files"));
    }

    private void setupLogger() {
        logger = Logger.getLogger("RapidPnR");
        logger.setUseParentHandlers(false);

        Path logFilePath = dirManager.getRootDir().resolve("rapidPnR.log");
        
        // Setup Logger
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

        logger.info(logMsg("Setup logger for RapidPnR successfully"));
    }

    private void readAbstractNetlistJson(String jsonFilePath) {
        logger.info(logMsg("Start reading abstracted netlist from " + jsonFilePath));
        // Read partition result from json file
        Gson gson = new GsonBuilder().create();
        try (FileReader reader = new FileReader(jsonFilePath)) {
            PartitionResultsJson partitionResults = gson.fromJson(reader, PartitionResultsJson.class);
            clockPortNames = partitionResults.clkPortNames;
            resetPortNames = partitionResults.rstPortNames;

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
                if (ignoreNet == null) continue;
                //assert ignoreNet != null: "Invalid name of ignore net in JSON file: " + ignoreNetName;
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
        logger.info(logMsg("Complete reading abstracted netlist from " + jsonFilePath));
    }

    private void readIslandPlaceJson(String jsonFilePath) {
        logger.info(logMsg("Start reading placement results from " + jsonFilePath));

        Gson gson = new GsonBuilder().create();
        try (FileReader reader = new FileReader(jsonFilePath)) {
            IslandPlaceResultJson placeResults = gson.fromJson(reader, IslandPlaceResultJson.class);
            gridDimension = placeResults.gridDimension;
            partitionGroupLocs = placeResults.partitionGroupLocs;
        } catch (IOException e) {
            e.printStackTrace();
        }

        logger.info(logMsg("Complete reading placement results from " + jsonFilePath));
    }

    private void buildCellInst2IslandMap() {
        logger.info(logMsg("Start building cellInst to region map"));
        newSubStep();
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
                logger.info(logMsg(String.format("The number of cells in island (%d, %d):%d", i, j, island2CellInstMap[i][j].size())));
            }
        }

        endSubStep();
        logger.info(logMsg("Complete building cellInst to region map"));
    }

    private void buildNet2BoundaryMap() {
        logger.info(logMsg("Start building net to boundary map"));

        newSubStep();
        vertBoundary2NetMap = new HashSet[gridDimension.get(0) - 1][gridDimension.get(1)];
        horiBoundary2NetMap = new HashSet[gridDimension.get(0)][gridDimension.get(1) - 1];

        vertBoundary2CellInstMap = new HashSet[gridDimension.get(0) - 1][gridDimension.get(1)];
        horiBoundary2CellInstMap = new HashSet[gridDimension.get(0)][gridDimension.get(1) - 1];

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

        int totalNumOfBoundaryNet = 0;
        logger.info(logMsg("Vertical Boundary Net Count:"));
        for (int x = 0; x < gridDimension.get(0) - 1; x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                logger.info(logMsg(String.format("## The number of nets on vert boundary(%d, %d): %d", x, y, vertBoundary2NetMap[x][y].size())));
                totalNumOfBoundaryNet += vertBoundary2NetMap[x][y].size();
            }
        }
        logger.info(logMsg("Horizontal Boundary Net Count:"));
        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1) - 1; y++) {
                logger.info(logMsg(String.format("The number of nets on hori boundary(%d, %d): %d", x, y, horiBoundary2NetMap[x][y].size())));
                totalNumOfBoundaryNet += horiBoundary2NetMap[x][y].size();
            }
        }

        logger.info(logMsg("Total number of boundary nets: " + totalNumOfBoundaryNet));
        
        endSubStep();
        logger.info(logMsg("Complete building net to boundary map"));
    }

    private void buildCellInst2BoundaryMap() {
        logger.info(logMsg("Start building cellInst to boundary map"));
        vertBoundary2CellInstMap = new HashSet[gridDimension.get(0) - 1][gridDimension.get(1)];
        horiBoundary2CellInstMap = new HashSet[gridDimension.get(0)][gridDimension.get(1) - 1];

        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                if (x < gridDimension.get(0) - 1) {
                    vertBoundary2CellInstMap[x][y] = new HashSet<>();
                }
                if (y < gridDimension.get(1) - 1) {
                    horiBoundary2CellInstMap[x][y] = new HashSet<>();
                }
            }
        }

        cellInst2horiBoundaryMap = new HashMap<>();
        cellInst2vertBoundaryMap = new HashMap<>();

        // build cellInst to vertical boundary map
        for (int x = 0; x < gridDimension.get(0) - 1; x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                for (EDIFNet net : vertBoundary2NetMap[x][y]) {
                    EDIFCellInst srcCellInst = NetlistUtils.getSourceCellInstOfNet(net);
                    assert srcCellInst != null;
                    assert NetlistUtils.isRegisterCellInst(srcCellInst);
                    // remove cellInst from island and then insert it into boundary
                    List<Integer> loc = cellInst2IslandMap.get(srcCellInst);
                    island2CellInstMap[loc.get(0)][loc.get(1)].remove(srcCellInst);
                    cellInst2IslandMap.remove(srcCellInst);

                    vertBoundary2CellInstMap[x][y].add(srcCellInst);
                    cellInst2vertBoundaryMap.put(srcCellInst, Arrays.asList(x, y));
                }
            }
        }

        // build cellInst to horizontal boundary map
        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1) - 1; y++) {
                for (EDIFNet net : horiBoundary2NetMap[x][y]) {
                    EDIFCellInst srcCellInst = NetlistUtils.getSourceCellInstOfNet(net);
                    assert srcCellInst != null;
                    assert NetlistUtils.isRegisterCellInst(srcCellInst);
                    // remove cellInst from island and then insert it into boundary
                    List<Integer> loc = cellInst2IslandMap.get(srcCellInst);
                    island2CellInstMap[loc.get(0)][loc.get(1)].remove(srcCellInst);
                    cellInst2IslandMap.remove(srcCellInst);

                    horiBoundary2CellInstMap[x][y].add(srcCellInst);
                    cellInst2horiBoundaryMap.put(srcCellInst, Arrays.asList(x, y));
                }
            }
        }
        logger.info(logMsg("Complete building cellInst to boundary map"));
    }

    private EDIFCell copyPartialNetlistToCell(EDIFCell newCell, Set<EDIFCellInst> originCellInsts) {
        // Copy partial netlist including originCellInsts to newCell
        logger.info(logMsg("Copy partial netlist to cell: " + newCell.getName()));
        newSubStep();

        //// Copy partial netlist
        // Copy CellInsts
        for (EDIFCellInst cellInst : originCellInsts) {
            copyCellInstToNewCell(cellInst, newCell);
        }

        //// Copy Nets
        int netNum = 0;
        int partPinNum = 0;
        for (EDIFNet net : flatTopCell.getNets()) {
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

                assert isSrcPortOutOfIsland ^ isSinkPortOutOfIsland;

                if (hasOutOfIslandPortInst) {
                    partPinNum += 1;
                    EDIFDirection dir = isSrcPortOutOfIsland ? EDIFDirection.INPUT : EDIFDirection.OUTPUT;
                    String partPinName = newNet.getName();
                    EDIFPort newPort = newCell.createPort(partPinName, dir, 1);
                    newNet.createPortInst(newPort);
                }
            }
        }

        logger.info(logMsg("Number of cellInsts in the partial netlist: " + originCellInsts.size()));
        logger.info(logMsg("Number of nets in the partial netlist: " + netNum));
        logger.info(logMsg("Number of partition pins in the partial netlist: " + partPinNum));

        endSubStep();
        logger.info(logMsg("Complete copying partial netlist to cell: " + newCell.getName()));
        return newCell;
    }

    void run() { // kernel function of the entire flow
        logger.info(logMsg("Start running RapidPnR flow"));

        newSubStep();
        setupEnvironment();

        parallelIslandImplAndMerge();
        // Parallel placement and routing of each island
        //parallelIslandImpl();

        // Iterative refinement of each island
        // TODO:

        // Merge partitions to form complete design
        //mergeIslands();

        //test();
        endSubStep();
        logger.info(logMsg("Complete running RapidPnR flow successfully"));
    }

    void setupEnvironment() {
        logger.info(logMsg("Start setting up environment for RapidPnR flow"));
        dirManager.addSubDir(resultDirName);

        logger.info(logMsg("Complete setting up environment for RapidPnR flow"));
    }

    void parallelIslandImpl() {
        logger.info(logMsg("Start parallel implementation of each island"));

        JobQueue jobQueue = new JobQueue();
        newSubStep();

        setupVivadoEnvForIslandImpl(0, 0, false);
        setupVivadoEnvForIslandImpl(1, 1, false);

        setupVivadoEnvForIslandImpl(0, 1, true);
        setupVivadoEnvForIslandImpl(1, 0, true);

        logger.info("Launch Vivado runs for each island");
        Job island_0_0_job = JobQueue.createJob();
        island_0_0_job.setRunDir(dirManager.getSubDir(getIslandName(0, 0)).toString());
        island_0_0_job.setCommand(VivadoTclUtils.launchVivadoTcl(vivadoPath, vivadoBuildTclName));
        jobQueue.addJob(island_0_0_job);

        Job island_1_1_job = JobQueue.createJob();
        island_1_1_job.setRunDir(dirManager.getSubDir(getIslandName(1, 1)).toString());
        island_1_1_job.setCommand(VivadoTclUtils.launchVivadoTcl(vivadoPath, vivadoBuildTclName));
        jobQueue.addJob(island_1_1_job);

        boolean success = jobQueue.runAllToCompletion();

        if (!success) {
            logger.severe(logMsg("Fail to complete parallel implementation of each island"));
            System.exit(1);
        }

        endSubStep();

        logger.info(logMsg("Complete parallel implementation of each island"));
    }

    void parallelIslandImplAndMerge() {
        logger.info(logMsg("Start parallel implementation of each island"));

        JobQueue jobQueue = new JobQueue();
        newSubStep();

        dirManager.addSubDir(getIslandName(0, 0));
        dirManager.addSubDir(getIslandName(1, 1));
        dirManager.addSubDir(getIslandName(0, 1));
        dirManager.addSubDir(getIslandName(1, 0));

        logger.info("Create DCP for each island");
        Design island_0_0_design = createDCPforIslandImpl(0, 0);
        Design island_1_1_design = createDCPforIslandImpl(1, 1);
        Map<String, EDIFCell> peripheralCells = new HashMap<>();
        peripheralCells.put(getIslandInstName(0, 0), island_0_0_design.getNetlist().getTopCell());
        peripheralCells.put(getIslandInstName(1, 1), island_1_1_design.getNetlist().getTopCell());

        Design island_0_1_design = createDCPforIslandImpl(0, 1, peripheralCells);
        Design island_1_0_design = createDCPforIslandImpl(1, 0, peripheralCells);

        Path workPath = dirManager.getSubDir(getIslandName(0, 0));
        Path dcpPath = workPath.resolve(vivadoInputDcpName);
        island_0_0_design.writeCheckpoint(dcpPath.toString());

        workPath = dirManager.getSubDir(getIslandName(1, 1));
        dcpPath = workPath.resolve(vivadoInputDcpName);
        island_1_1_design.writeCheckpoint(dcpPath.toString());

        workPath = dirManager.getSubDir(getIslandName(0, 1));
        dcpPath = workPath.resolve(vivadoInputDcpName);
        island_0_1_design.writeCheckpoint(dcpPath.toString());

        workPath = dirManager.getSubDir(getIslandName(1, 0));
        dcpPath = workPath.resolve(vivadoInputDcpName);
        island_1_0_design.writeCheckpoint(dcpPath.toString());

        createTclCmdFileForIslandImpl2(0, 0, false);
        createTclCmdFileForIslandImpl2(1, 1, false);
        createTclCmdFileForIslandImpl2(0, 1, true);
        createTclCmdFileForIslandImpl2(1, 0, true);
        //
        logger.info("Launch Vivado runs for each island");
        // Job island_0_0_job = JobQueue.createJob();
        // island_0_0_job.setRunDir(dirManager.getSubDir(getIslandName(0, 0)).toString());
        // island_0_0_job.setCommand(VivadoTclUtils.launchVivadoTcl(vivadoPath, vivadoBuildTclName));
        // jobQueue.addJob(island_0_0_job);

        // Job island_1_1_job = JobQueue.createJob();
        // island_1_1_job.setRunDir(dirManager.getSubDir(getIslandName(1, 1)).toString());
        // island_1_1_job.setCommand(VivadoTclUtils.launchVivadoTcl(vivadoPath, vivadoBuildTclName));
        // jobQueue.addJob(island_1_1_job);

        // boolean success = jobQueue.runAllToCompletion();

        // if (!success) {
        //     logger.severe(logMsg("Fail to complete parallel implementation of each island"));
        //     System.exit(1);
        // }

        // Job island_0_1_job = JobQueue.createJob();
        // island_0_1_job.setRunDir(dirManager.getSubDir(getIslandName(0, 1)).toString());
        // island_0_1_job.setCommand(VivadoTclUtils.launchVivadoTcl(vivadoPath, vivadoBuildTclName));
        // jobQueue.addJob(island_0_1_job);

        // Job island_1_0_job = JobQueue.createJob();
        // island_1_0_job.setRunDir(dirManager.getSubDir(getIslandName(1, 0)).toString());
        // island_1_0_job.setCommand(VivadoTclUtils.launchVivadoTcl(vivadoPath, vivadoBuildTclName));
        // jobQueue.addJob(island_1_0_job);

        // boolean success = jobQueue.runAllToCompletion();
        
        // if (!success) {
        //     logger.severe(logMsg("Fail to complete parallel implementation of each island"));
        //     System.exit(1);
        // }

        Map<List<Integer>, EDIFCell> island2TopCellMap = new HashMap<>();
        island2TopCellMap.put(Arrays.asList(0, 0), island_0_0_design.getNetlist().getTopCell());
        island2TopCellMap.put(Arrays.asList(1, 1), island_1_1_design.getNetlist().getTopCell());
        //island2TopCellMap.put(Arrays.asList(0, 1), island_0_1_design.getNetlist().getCell(getIslandName(0, 1)));
        //island2TopCellMap.put(Arrays.asList(1, 0), island_1_0_design.getNetlist().getCell(getIslandName(1, 0)));
        Design mergeDesign = createDCPforIslandMerge(island2TopCellMap);

        logger.info("Write checkpoint of top design");
        dirManager.addSubDir(mergeDirName);
        workPath = dirManager.getSubDir(mergeDirName);
        dcpPath = workPath.resolve(vivadoInputDcpName);
        mergeDesign.writeCheckpoint(dcpPath.toString());

        createTclCmdFileForIslandMerge2();


        endSubStep();

        logger.info(logMsg("Complete parallel implementation of each island"));
    }

    void mergeIslands() {
        logger.info(logMsg("Start merging islands to form complete design"));

        newSubStep();
        //
        logger.info(logMsg("Create working directory for merging islands"));
        dirManager.addSubDir(mergeDirName);

        //
        Design mergeDesign = createDCPforIslandMerge();
        
        //
        logger.info("Write checkpoint of top design");
        Path workPath = dirManager.getSubDir(mergeDirName);
        Path dcpPath = workPath.resolve(vivadoInputDcpName);
        mergeDesign.writeCheckpoint(dcpPath.toString());

        //
        createTclCmdFileForIslandMerge();

        endSubStep();

        logger.info(logMsg("Complete merging islands to form complete design"));
    }


    void setupVivadoEnvForIslandImpl(int x, int y, Boolean readBoundaryImpl) {
        logger.info(logMsg("Start setting up env for vivado impl of " + getIslandName(x, y)));

        newSubStep();
        logger.info(logMsg("Create working directory for " + getIslandName(x, y)));
        dirManager.addSubDir(getIslandName(x, y));

        Design islandDesign = createDCPforIslandImpl(x, y);

        logger.info(logMsg("Write checkpoint of top design"));
        Path workPath = dirManager.getSubDir(getIslandName(x, y));
        Path dcpPath = workPath.resolve(vivadoInputDcpName);
        islandDesign.writeCheckpoint(dcpPath.toString());

        createTclCmdFileForIslandImpl(x, y, readBoundaryImpl);

        endSubStep();
        logger.info(logMsg("Complete setting up env for impl of " + getIslandName(x, y)));
    }

    Design createDCPforIslandImpl(int x, int y) {
        
        logger.info(logMsg("Start creating DCP for " + getIslandName(x, y)));
        
        String designName = "island_boundary";
        String partName = originDesign.getPartName();

        Design topDesign = new Design(designName, partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        newSubStep();
        logger.info(logMsg("Create cells and cellInsts for island and boundary"));
        
        EDIFCell islandCell = new EDIFCell(workLib, getIslandName(x, y));
        copyPartialNetlistToCell(islandCell, island2CellInstMap[x][y]);
        EDIFCellInst islandCellInst = islandCell.createCellInst(getIslandInstName(x, y), topCell);
        VivadoTclUtils.addStrictPblocConstr(topDesign, islandCellInst, islandPBlockRanges[x][y]);
        //VivadoTclUtils.setPropertyDontTouch(topDesign, islandCellInst);

        List<Integer> upBoundaryLoc = getUpBoundaryLocOf(x, y);
        if (upBoundaryLoc != null) {
            logger.info(logMsg("Create cell and cellInst for up boundary"));
            EDIFCell newCell = new EDIFCell(workLib, getHoriBoundaryName(upBoundaryLoc));
            Set<EDIFCellInst> cellInsts = horiBoundary2CellInstMap[upBoundaryLoc.get(0)][upBoundaryLoc.get(1)];
            copyPartialNetlistToCell(newCell, cellInsts);

            EDIFCellInst newCellInst = newCell.createCellInst(getHoriBoundaryInstName(x, y), topCell);
            VivadoTclUtils.addStrictPblocConstr(topDesign, newCellInst, horiBoundaryPBlockRanges[upBoundaryLoc.get(0)][upBoundaryLoc.get(1)]);
            //VivadoTclUtils.setPropertyDontTouch(topDesign, islandCellInst);
        }

        List<Integer> downBoundaryLoc = getDownBoundaryLocOf(x, y);
        if (downBoundaryLoc != null) {
            logger.info(logMsg("Create cell and cellInst for down boundary"));
            EDIFCell newCell = new EDIFCell(workLib, getHoriBoundaryName(downBoundaryLoc));
            Set<EDIFCellInst> cellInsts = horiBoundary2CellInstMap[downBoundaryLoc.get(0)][downBoundaryLoc.get(1)];
            copyPartialNetlistToCell(newCell, cellInsts);

            EDIFCellInst newCellInst = newCell.createCellInst(getHoriBoundaryInstName(downBoundaryLoc), topCell);
            VivadoTclUtils.addStrictPblocConstr(topDesign, newCellInst, horiBoundaryPBlockRanges[downBoundaryLoc.get(0)][downBoundaryLoc.get(1)]);
            //VivadoTclUtils.setPropertyDontTouch(topDesign, islandCellInst);
        }

        List<Integer> leftBoundaryLoc = getLeftBoundaryLocOf(x, y);
        if (leftBoundaryLoc != null) {
            logger.info(logMsg("Create cell and cellInst for left boundary"));
            EDIFCell newCell = new EDIFCell(workLib, getVertBoundaryName(leftBoundaryLoc));
            Set<EDIFCellInst> cellInsts = vertBoundary2CellInstMap[leftBoundaryLoc.get(0)][leftBoundaryLoc.get(1)];
            copyPartialNetlistToCell(newCell, cellInsts);

            EDIFCellInst newCellInst = newCell.createCellInst(getVertBoundaryInstName(leftBoundaryLoc), topCell);
            VivadoTclUtils.addStrictPblocConstr(topDesign, newCellInst, vertBoundaryPBlockRanges[leftBoundaryLoc.get(0)][leftBoundaryLoc.get(1)]);
            //VivadoTclUtils.setPropertyDontTouch(topDesign, islandCellInst);
        }

        List<Integer> rightBoundaryLoc = getRightBoundaryLocOf(x, y);
        if (rightBoundaryLoc != null) {
            logger.info(logMsg("Create cell and cellInst for right boundary"));
            EDIFCell newCell = new EDIFCell(workLib, getVertBoundaryName(rightBoundaryLoc));
            Set<EDIFCellInst> cellInsts = vertBoundary2CellInstMap[rightBoundaryLoc.get(0)][rightBoundaryLoc.get(1)];
            copyPartialNetlistToCell(newCell, cellInsts);

            EDIFCellInst newCellInst = newCell.createCellInst(getVertBoundaryInstName(rightBoundaryLoc), topCell);
            //newCellInsts.add(newCellInst);
            VivadoTclUtils.addStrictPblocConstr(topDesign, newCellInst, vertBoundaryPBlockRanges[rightBoundaryLoc.get(0)][rightBoundaryLoc.get(1)]);
            //VivadoTclUtils.setPropertyDontTouch(topDesign, newCellInst);
        }

        logger.info(logMsg("Create nets connecting cellInsts of boundary and island"));

        connectCellInstsOfCustomCell(topCell);
        // for (EDIFCellInst newCellInst : newCellInsts) {
        //     EDIFCell newCell = newCellInst.getCellType();
        //     for (EDIFPort port : newCell.getPorts()) {
        //         String portName = port.getName();
        //         EDIFNet newNet = topCell.getNet(portName);
        //         if (newNet == null) {
        //             newNet = topCell.createNet(portName);
        //         }
        //         newNet.createPortInst(port, newCellInst);
        //     }
        // }

        // for (EDIFNet newNet : topCell.getNets()) {
        //     int portInstNum = newNet.getPortInsts().size();
        //     int srcPortInstNum = newNet.getSourcePortInsts(true).size();

        //     assert portInstNum >= 1;

        //     if (portInstNum == 1 || srcPortInstNum == 0) {
        //         EDIFDirection portDir = srcPortInstNum == 0 ? EDIFDirection.INPUT : EDIFDirection.OUTPUT;
        //         EDIFPort topPort = topCell.createPort(newNet.getName(), portDir, 1);
        //         newNet.createPortInst(topPort);
        //     }
        // }

        // add constraints
        logger.info(logMsg("Add Vivado constraints for top design"));
        VivadoTclUtils.addClockConstraint(topDesign, clockPortNames.get(0), clk2PeriodMap.get(clockPortNames.get(0)));
        topDesign.setAutoIOBuffers(false);
        topDesign.setDesignOutOfContext(true);
        VivadoTclUtils.setPropertyHDPartition(topDesign);

        endSubStep();

        logger.info(logMsg("Complete creating DCP for " + getIslandName(x, y)));

        return topDesign;
    }

    void test() {

        Design island_0_0 = createDCPforIslandImpl(0, 0);
        Design island_1_1 = createDCPforIslandImpl(1, 1);

        Map<String, EDIFCell> peripherals = new HashMap<>();
        peripherals.put("island_0_0", island_0_0.getNetlist().getTopCell());
        peripherals.put("island_1_1", island_1_1.getNetlist().getTopCell());

        Design island_0_1 = createDCPforIslandImpl(0, 1, peripherals);
        Design island_1_0 = createDCPforIslandImpl(1, 0, peripherals);

        peripherals.put("island_0_1", island_0_1.getNetlist().getCell(getIslandName(0, 1)));
        peripherals.put("island_1_0", island_1_0.getNetlist().getCell(getIslandName(1, 0)));

        Path workPath = dirManager.addSubDir(getIslandName(0, 1));
        Path dcpPath = workPath.resolve(vivadoInputDcpName);
        island_0_1.writeCheckpoint(dcpPath.toString());

        workPath = dirManager.addSubDir(getIslandName(1, 0));
        dcpPath = workPath.resolve(vivadoInputDcpName);
        island_1_0.writeCheckpoint(dcpPath.toString());

        // Design completeDesign = createDCPforIslandMerge(peripherals);
        // workPath = dirManager.addSubDir(mergeDirName);
        // dcpPath = workPath.resolve(vivadoInputDcpName);
        // completeDesign.writeCheckpoint(dcpPath.toString());
    }

    Design createDCPforIslandImpl(int x, int y, Map<String, EDIFCell> peripherals) {
        String designName = "island_with_context";
        String partName = originDesign.getPartName();

        Design topDesign = new Design(designName, partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        // add island cell
        EDIFCell islandCell = new EDIFCell(workLib, getIslandName(x, y));
        copyPartialNetlistToCell(islandCell, island2CellInstMap[x][y]);
        EDIFCellInst islandCellInst = islandCell.createCellInst(getIslandInstName(x, y), topCell);
        VivadoTclUtils.addStrictPblocConstr(topDesign, islandCellInst, islandPBlockRanges[x][y]);
        VivadoTclUtils.setPropertyHDReConfig(topDesign, islandCellInst);

        // add peripheral cells as blackboxes
        for (Map.Entry<String, EDIFCell> entry : peripherals.entrySet()) {
            String peripheralCellName = entry.getKey();
            EDIFCell peripheralCell = entry.getValue();

            EDIFCell newCell = new EDIFCell(workLib, peripheralCellName);
            for (EDIFPort port : peripheralCell.getPorts()) {
                newCell.createPort(port);
            }
            EDIFCellInst newCellInst = newCell.createCellInst(peripheralCellName, topCell);
            VivadoTclUtils.setPropertyHDPartition(topDesign, newCellInst);
        }

        connectCellInstsOfCustomCell(topCell);

        // add constraints
        VivadoTclUtils.addClockConstraint(topDesign, clockPortNames.get(0), clk2PeriodMap.get(clockPortNames.get(0)));
        topDesign.setAutoIOBuffers(false);
        //topDesign.setDesignOutOfContext(true);
        
        return topDesign;
    }

    void createTclCmdFileForIslandImpl(int x, int y, Boolean readBoundaryImpl) {
        logger.info(logMsg("Start generating file of Tcl commands for" + getIslandName(x, y)));

        Path islandPath = dirManager.getSubDir(getIslandName(x, y));
        Path resultPath = dirManager.getSubDir(resultDirName);

        Path tclCmdPath = islandPath.resolve(vivadoBuildTclName);
        TclCmdFile tclCmdFile = new TclCmdFile(tclCmdPath);

        List<Pair<String, String>> cellInstDcpPathPairs = new ArrayList<>();
        cellInstDcpPathPairs.add(new Pair<>(getIslandInstName(x, y), resultPath.resolve(getIslandDcpName(x, y)).toString()));
        
        // Add Tcl commands for Vivado implementation
        tclCmdFile.addCmd(VivadoTclUtils.setMaxThread(maxThreadNum));
        tclCmdFile.addCmd(VivadoTclUtils.openCheckpoint(vivadoInputDcpName));

        
        List<Integer> upBoundaryLoc = getUpBoundaryLocOf(x, y);
        if (upBoundaryLoc != null) {
            String instName = getHoriBoundaryInstName(upBoundaryLoc);
            String dcpPath = resultPath.resolve(getHoriBoundaryDcpName(upBoundaryLoc)).toString();
            if (readBoundaryImpl) {
                tclCmdFile.addCmd(VivadoTclUtils.updateCellBlackbox(instName));
                tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(instName, dcpPath));
                tclCmdFile.addCmd(VivadoTclUtils.lockDesign(false, "routing", instName));
            }
            cellInstDcpPathPairs.add(new Pair<>(instName, dcpPath));
        }
        List<Integer> downBoundaryLoc = getDownBoundaryLocOf(x, y);
        if (downBoundaryLoc != null) {
            String instName = getHoriBoundaryInstName(downBoundaryLoc);
            String dcpPath = resultPath.resolve(getHoriBoundaryDcpName(downBoundaryLoc)).toString();
            if (readBoundaryImpl) {
                tclCmdFile.addCmd(VivadoTclUtils.updateCellBlackbox(instName));
                tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(instName, dcpPath));
                tclCmdFile.addCmd(VivadoTclUtils.lockDesign(false, "routing", instName));
            }
            cellInstDcpPathPairs.add(new Pair<>(instName, dcpPath));
        }
        List<Integer> leftBoundaryLoc = getLeftBoundaryLocOf(x, y);
        if (leftBoundaryLoc != null) {
            String instName = getVertBoundaryInstName(leftBoundaryLoc);
            String dcpPath = resultPath.resolve(getVertBoundaryDcpName(leftBoundaryLoc)).toString();
            if (readBoundaryImpl) {
                tclCmdFile.addCmd(VivadoTclUtils.updateCellBlackbox(instName));
                tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(instName, dcpPath));
                tclCmdFile.addCmd(VivadoTclUtils.lockDesign(false, "routing", instName));
            }
            cellInstDcpPathPairs.add(new Pair<>(instName, dcpPath));
        }
        List<Integer> rightBoundaryLoc = getRightBoundaryLocOf(x, y);
        if (rightBoundaryLoc != null) {
            String instName = getVertBoundaryInstName(rightBoundaryLoc);
            String dcpPath = resultPath.resolve(getVertBoundaryDcpName(rightBoundaryLoc)).toString();
            if (readBoundaryImpl) {
                tclCmdFile.addCmd(VivadoTclUtils.updateCellBlackbox(instName));
                tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(instName, dcpPath));
                tclCmdFile.addCmd(VivadoTclUtils.lockDesign(false, "routing", instName));
            }
            cellInstDcpPathPairs.add(new Pair<>(instName, dcpPath));
        }
        
        tclCmdFile.addCmd(VivadoTclUtils.placeDesign(null));
        tclCmdFile.addCmd(VivadoTclUtils.routeDesign(null));
        tclCmdFile.addCmd(VivadoTclUtils.physOptDesign());

        String timingRptPath = addSuffixRpt("timing_summary");
        tclCmdFile.addCmd(VivadoTclUtils.reportTimingSummary(0, timingRptPath));

        for (Pair<String, String> cellInstDcpPath : cellInstDcpPathPairs) {
            tclCmdFile.addCmd(VivadoTclUtils.writeCheckpoint(true, cellInstDcpPath.getFirst(), cellInstDcpPath.getSecond()));
        }

        tclCmdFile.addCmd(VivadoTclUtils.writeCheckpoint(true, null, vivadoOutputDcpName));

        // write files of Tcl commands
        tclCmdFile.writeToFile();

        logger.info(logMsg("Complete generating Tcl commands file for " + getIslandName(x, y)));
    }

    void createTclCmdFileForIslandImpl2(int x, int y, Boolean readPeripheral) {
        logger.info(logMsg("Start generating file of Tcl commands for" + getIslandName(x, y)));

        Path islandPath = dirManager.getSubDir(getIslandName(x, y));
        Path resultPath = dirManager.getSubDir(resultDirName);

        Path tclCmdPath = islandPath.resolve(vivadoBuildTclName);
        TclCmdFile tclCmdFile = new TclCmdFile(tclCmdPath);
        
        // Add Tcl commands for Vivado implementation
        tclCmdFile.addCmd(VivadoTclUtils.setMaxThread(maxThreadNum));
        tclCmdFile.addCmd(VivadoTclUtils.openCheckpoint(vivadoInputDcpName));

        
        List<Integer> upIslandLoc = getUpIslandLocOf(x, y);
        if (upIslandLoc != null && readPeripheral) {
            String instName = getIslandInstName(upIslandLoc);
            String dcpPath = resultPath.resolve(getIslandDcpName(upIslandLoc)).toString();
            
            tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(instName, dcpPath));
            tclCmdFile.addCmd(VivadoTclUtils.lockDesign(false, "routing", instName));
        }

        List<Integer> downIslandLoc = getDownIslandLocOf(x, y);
        if (downIslandLoc != null && readPeripheral) {
            String instName = getIslandInstName(downIslandLoc);
            String dcpPath = resultPath.resolve(getIslandDcpName(downIslandLoc)).toString();
            
            tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(instName, dcpPath));
            tclCmdFile.addCmd(VivadoTclUtils.lockDesign(false, "routing", instName));
        }

        List<Integer> leftIslandLoc = getLeftIslandLocOf(x, y);
        if (leftIslandLoc != null && readPeripheral) {
            String instName = getIslandInstName(leftIslandLoc);
            String dcpPath = resultPath.resolve(getIslandDcpName(leftIslandLoc)).toString();
            
            tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(instName, dcpPath));
            tclCmdFile.addCmd(VivadoTclUtils.lockDesign(false, "routing", instName));
        }

        List<Integer> rightIslandLoc = getRightIslandLocOf(x, y);
        if (rightIslandLoc != null && readPeripheral) {
            String instName = getIslandInstName(rightIslandLoc);
            String dcpPath = resultPath.resolve(getIslandDcpName(rightIslandLoc)).toString();
            
            tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(instName, dcpPath));
            tclCmdFile.addCmd(VivadoTclUtils.lockDesign(false, "routing", instName));
        }
        
        tclCmdFile.addCmd(VivadoTclUtils.placeDesign(null));
        tclCmdFile.addCmd(VivadoTclUtils.routeDesign(null));
        tclCmdFile.addCmd(VivadoTclUtils.physOptDesign());

        String timingRptPath = addSuffixRpt("timing_summary");
        tclCmdFile.addCmd(VivadoTclUtils.reportTimingSummary(0, timingRptPath));

        if (readPeripheral) {
            tclCmdFile.addCmd(VivadoTclUtils.writeCheckpoint(true, getIslandInstName(x, y), resultPath.resolve(getIslandDcpName(x, y)).toString()));
        } else {
            tclCmdFile.addCmd(VivadoTclUtils.writeCheckpoint(true, null, resultPath.resolve(getIslandDcpName(x, y)).toString()));
        }

        tclCmdFile.addCmd(VivadoTclUtils.writeCheckpoint(true, null, vivadoOutputDcpName));

        // write files of Tcl commands
        tclCmdFile.writeToFile();

        logger.info(logMsg("Complete generating Tcl commands file for " + getIslandName(x, y)));
    }

    private Design createDCPforIslandMerge(Map<List<Integer>, EDIFCell> cells) {

        String designName = "complete_design";
        String partName = originDesign.getPartName();

        Design topDesign = new Design(designName, partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        EDIFCell islandCell = new EDIFCell(workLib, getIslandName(0, 1));
        copyPartialNetlistToCell(islandCell, island2CellInstMap[0][1]);
        EDIFCellInst islandCellInst = islandCell.createCellInst(getIslandInstName(0, 1), topCell);
        VivadoTclUtils.addStrictPblocConstr(topDesign, islandCellInst, islandPBlockRanges[0][1]);

        islandCell = new EDIFCell(workLib, getIslandName(1, 0));
        copyPartialNetlistToCell(islandCell, island2CellInstMap[1][0]);
        islandCellInst = islandCell.createCellInst(getIslandInstName(1, 0), topCell);
        VivadoTclUtils.addStrictPblocConstr(topDesign, islandCellInst, islandPBlockRanges[1][0]);


        // Add peripheral cells to the top design
        for (Map.Entry<List<Integer>, EDIFCell> entry : cells.entrySet()) {
            List<Integer> loc = entry.getKey();
            EDIFCell cell = entry.getValue();

            EDIFCell newCell = new EDIFCell(workLib, getIslandName(loc));
            for (EDIFPort port : cell.getPorts()) {
                newCell.createPort(port);
            }
            EDIFCellInst newCellInst = newCell.createCellInst(getIslandInstName(loc), topCell);

            VivadoTclUtils.setPropertyHDPartition(topDesign, newCellInst);
            //VivadoTclUtils.setPropertyHDPartition(topDesign, newCellInst);
        }

        connectCellInstsOfCustomCell(topCell);

        //
        VivadoTclUtils.addClockConstraint(topDesign, clockPortNames.get(0), clk2PeriodMap.get(clockPortNames.get(0)));
        topDesign.setAutoIOBuffers(false);
        //topDesign.setDesignOutOfContext(true);
        
        return topDesign;
    }

    private Design createDCPforIslandMerge() {
        logger.info(logMsg("Start creating DCP for merging design"));

        String designName = "merge_island";
        String partName = originDesign.getPartName();

        Design topDesign = new Design(designName, partName);
        EDIFNetlist topNetlist = topDesign.getNetlist();
        EDIFLibrary workLib = topNetlist.getWorkLibrary();
        EDIFCell topCell = topNetlist.getTopCell();

        newSubStep();
        logger.info(logMsg("Create cells and cellInsts of all islands and boundaries"));
        List<EDIFCellInst> newCellInsts = new ArrayList<>();

        // Create cells and cellInsts for islands
        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                EDIFCell islandCell = new EDIFCell(workLib, getIslandName(x, y));
                copyPartialNetlistToCell(islandCell, island2CellInstMap[x][y]);
                EDIFCellInst islandCellInst = islandCell.createCellInst(getIslandInstName(x, y), topCell);
                newCellInsts.add(islandCellInst);
                VivadoTclUtils.addStrictPblocConstr(topDesign, islandCellInst, islandPBlockRanges[x][y]);
            }
        }

        // Create cells and cellInsts for horizontal boundaries
        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1) - 1; y++) {
                EDIFCell newCell = new EDIFCell(workLib, getHoriBoundaryName(x, y));
                copyPartialNetlistToCell(newCell, horiBoundary2CellInstMap[x][y]);

                EDIFCellInst newCellInst = newCell.createCellInst(getHoriBoundaryInstName(x, y), topCell);
                newCellInsts.add(newCellInst);
                VivadoTclUtils.addStrictPblocConstr(topDesign, newCellInst, horiBoundaryPBlockRanges[x][y]);
            }
        }

        // Create cells and cellInsts for vertical boundaries
        for (int x = 0; x < gridDimension.get(0) - 1; x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                EDIFCell newCell = new EDIFCell(workLib, getVertBoundaryName(x, y));
                copyPartialNetlistToCell(newCell, vertBoundary2CellInstMap[x][y]);

                EDIFCellInst newCellInst = newCell.createCellInst(getVertBoundaryInstName(x, y), topCell);
                newCellInsts.add(newCellInst);
                VivadoTclUtils.addStrictPblocConstr(topDesign, newCellInst, vertBoundaryPBlockRanges[x][y]);
            }
        }

        logger.info(logMsg("Copy top-level ports and internal nets of original top cell"));
        for (EDIFPort port : flatTopCell.getPorts()) {
            topCell.createPort(port);
        }

        for (Map.Entry<String, EDIFNet> entry : flatTopCell.getInternalNetMap().entrySet()) {
            String portInstName = entry.getKey();
            EDIFNet internalNet = entry.getValue();
            String internalNetName = internalNet.getName();

            if (internalNet.isGND() || internalNet.isVCC()) {
                NetType netType = internalNet.isGND() ? NetType.GND : NetType.VCC;
                EDIFNet newStaticNet = EDIFTools.getStaticNet(netType, topCell, topNetlist);
                newStaticNet.createPortInst(portInstName, topCell);
                continue;
            }

            EDIFNet newInternalNet = topCell.getNet(internalNetName);
            if (newInternalNet == null) {
                newInternalNet = topCell.createNet(internalNetName);
            }
            internalNet.createPortInst(portInstName, topCell);
        }

        logger.info("Create nets connecting cellInsts of boundaries and islands");
        for (EDIFCellInst cellInst : newCellInsts) {
            EDIFCell newCell = cellInst.getCellType();
            for (EDIFPort port : newCell.getPorts()) {
                String portName = port.getName();
                String netName = portName;

                EDIFNet originNet = flatTopCell.getNet(portName);
                assert originNet != null;
                if (resetNets.contains(originNet)) { // TODO: to be modified
                    netName = resetPortNames.get(0);
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
            assert net.getPortInsts().size() > 1;
            assert net.getSourcePortInsts(true).size() == 1;
        }

        logger.info("Add Vivado constraints for top design");
        VivadoTclUtils.addClockConstraint(topDesign, clockPortNames.get(0), clk2PeriodMap.get(clockPortNames.get(0)));
        for (EDIFCellInst cellInst : newCellInsts) {
            //VivadoTclUtils.setPropertyHDPartition(topDesign, cellInst);
        }
        topDesign.setAutoIOBuffers(false);

        endSubStep();
        logger.info(logMsg("Complete creating DCP for merging design"));

        return topDesign;
    }

    private void createTclCmdFileForIslandMerge() {
        logger.info(logMsg("Start creating file of Tcl commands for merging design"));


        logger.info(logMsg("Complete creating file of Tcl commands for merging design"));
    }

    private void createTclCmdFileForIslandMerge2() {
        logger.info(logMsg("Start creating file of Tcl commands for merging design"));
        Path workDir = dirManager.getSubDir(mergeDirName);
        Path resultPath = dirManager.getSubDir(resultDirName);

        Path tclCmdPath = workDir.resolve(vivadoBuildTclName);
        TclCmdFile tclCmdFile = new TclCmdFile(tclCmdPath);
        
        // Add Tcl commands for Vivado implementation
        tclCmdFile.addCmd(VivadoTclUtils.setMaxThread(maxThreadNum));
        tclCmdFile.addCmd(VivadoTclUtils.openCheckpoint(vivadoInputDcpName));

        tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(getIslandInstName(0, 0), resultPath.resolve(getIslandDcpName(0, 0)).toString()));
        tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(getIslandInstName(1, 1), resultPath.resolve(getIslandDcpName(1, 1)).toString()));
        tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(getIslandInstName(0, 1), resultPath.resolve(getIslandDcpName(0, 1)).toString()));
        tclCmdFile.addCmd(VivadoTclUtils.readCheckPoint(getIslandInstName(1, 0), resultPath.resolve(getIslandDcpName(1, 0)).toString()));

        tclCmdFile.addCmd(VivadoTclUtils.writeCheckpoint(true, null, vivadoOutputDcpName));

        tclCmdFile.writeToFile();

        logger.info(logMsg("Complete creating file of Tcl commands for merging design"));
    }

    private void connectCellInstsOfCustomCell(EDIFCell cell) {
        for (EDIFCellInst cellInst : cell.getCellInsts()) {
            EDIFCell cellType = cellInst.getCellType();
            assert !cellType.isStaticSource();

            for (EDIFPort port : cellType.getPorts()) {
                String portName = port.getName();
                EDIFNet net = cell.getNet(portName);
                if (net == null) {
                    net = cell.createNet(portName);
                }
                net.createPortInst(port, cellInst);
            }
        }

        for (EDIFNet newNet : cell.getNets()) {
            int portInstNum = newNet.getPortInsts().size();
            int srcPortInstNum = newNet.getSourcePortInsts(true).size();

            assert portInstNum >= 1;

            if (portInstNum == 1 || srcPortInstNum == 0) {
                EDIFDirection portDir = srcPortInstNum == 0 ? EDIFDirection.INPUT : EDIFDirection.OUTPUT;
                EDIFPort topPort = cell.createPort(newNet.getName(), portDir, 1);
                newNet.createPortInst(topPort);
            }
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

    private List<Integer> getLeftBoundaryLocOf(Integer x, Integer y) {
        if (x - 1 >= 0) {
            return Arrays.asList(x - 1, y);
        } else {
            return null;
        }
    }
    private List<Integer> getRightBoundaryLocOf(Integer x, Integer y) {
        if (x < gridDimension.get(0) - 1) {
            return Arrays.asList(x, y);
        } else {
            return null;
        }
    }
    private List<Integer> getUpBoundaryLocOf(Integer x, Integer y) {
        if (y < gridDimension.get(1) - 1) {
            return Arrays.asList(x, y);
        } else {
            return null;
        }
    }

    private List<Integer> getDownBoundaryLocOf(Integer x, Integer y) {
        if (y - 1 >= 0) {
            return Arrays.asList(x, y - 1);
        } else {
            return null;
        }
    }

    private List<Integer> getUpIslandLocOf(Integer x, Integer y) {
        if (y + 1 < gridDimension.get(1)) {
            return Arrays.asList(x, y + 1);
        } else {
            return null;
        }
    }

    private List<Integer> getDownIslandLocOf(Integer x, Integer y) {
        if (y - 1 >= 0) {
            return Arrays.asList(x, y - 1);
        } else {
            return null;
        }
    }

    private List<Integer> getLeftIslandLocOf(Integer x, Integer y) {
        if (x - 1 >= 0) {
            return Arrays.asList(x - 1, y);
        } else {
            return null;
        }
    }

    private List<Integer> getRightIslandLocOf(Integer x, Integer y) {
        if (x + 1 < gridDimension.get(0)) {
            return Arrays.asList(x + 1, y);
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

    public Map<EDIFNet, List<Integer>> extractPartPinLocs(String routedDcpPath) {
        logger.info("# Start extracting partition pin locations of routed design");

        Design routedDesign = Design.readCheckpoint(routedDcpPath);
        Device targetDevice = routedDesign.getDevice();
        int[][] horiBoundary2RowMap = {{467}, {467}};
        int[][] vertBoundary2ColMap = {{347, 347}};


        Map<EDIFNet, List<Integer>> partPinNet2TileLocMap = new HashMap<>();

        // Extract partition pin locations for horizontal boundary
        for (int x = 0; x < gridDimension.get(0); x++) {
            for (int y = 0; y < gridDimension.get(1) - 1; y++) {
                int horiBoundaryRow = horiBoundary2RowMap[x][y];
                for (EDIFNet edifNet : horiBoundary2NetMap[x][y]) {
                    EDIFHierNet routedHierEdifNet = routedDesign.getNetlist().getTopHierCellInst().getNet(edifNet.getName());
                    EDIFHierNet parentHierNet = routedDesign.getNetlist().getParentNet(routedHierEdifNet);
                    Net net = routedDesign.getNet(parentHierNet.getHierarchicalNetName());
                    assert net != null;
                    
                    int prePipRow = net.getPIPs().get(0).getTile().getRow();
                    int prePipCol = net.getPIPs().get(0).getTile().getColumn();
                    for (PIP pip : net.getPIPs()) {
                        int curPipRow = pip.getTile().getRow();
                        int curPipCol = pip.getTile().getColumn();
                        Boolean crossBoundaryU = (prePipRow > horiBoundaryRow) && (curPipRow < horiBoundaryRow);
                        Boolean crossBoundaryD = (prePipRow < horiBoundaryRow) && (curPipRow > horiBoundaryRow);
                        if (crossBoundaryU || crossBoundaryD) {
                            assert curPipCol == prePipCol: edifNet.getName();
                            List<Integer> intTileLoc = Arrays.asList(prePipCol, horiBoundaryRow);
                            partPinNet2TileLocMap.put(edifNet, intTileLoc);
                            break;
                        } else if (curPipRow == horiBoundaryRow) {
                            List<Integer> intTileLoc = Arrays.asList(curPipCol, curPipRow);
                            partPinNet2TileLocMap.put(edifNet, intTileLoc);
                            break;
                        }
                        prePipRow = curPipRow;
                        prePipCol = curPipCol;
                    }
                }
            }
        }

        // Extract partition pin locations for vertical boundary
        for (int x = 0; x < gridDimension.get(0) - 1; x++) {
            for (int y = 0; y < gridDimension.get(1); y++) {
                int vertBoundaryCol = vertBoundary2ColMap[x][y];

                for (EDIFNet edifNet : vertBoundary2NetMap[x][y]) {
                    EDIFHierNet routedHierEdifNet = routedDesign.getNetlist().getTopHierCellInst().getNet(edifNet.getName());
                    EDIFHierNet parentHierNet = routedDesign.getNetlist().getParentNet(routedHierEdifNet);
                    Net net = routedDesign.getNet(parentHierNet.getHierarchicalNetName());
                    assert net != null;
                    
                    int prePipRow = net.getPIPs().get(0).getTile().getRow();
                    int prePipCol = net.getPIPs().get(0).getTile().getColumn();

                    logger.info("### Extract partition pin location for net: " + edifNet.getName());
                    for (PIP pip : net.getPIPs()) {
                        int curPipRow = pip.getTile().getRow();
                        int curPipCol = pip.getTile().getColumn();
                        logger.info("### PIP: " + curPipCol + ", " + curPipRow);
                        Boolean crossBoundaryL = (prePipCol > vertBoundaryCol) && (curPipCol < vertBoundaryCol);
                        Boolean crossBoundaryR = (prePipCol < vertBoundaryCol) && (curPipCol > vertBoundaryCol);
                        if (crossBoundaryL || crossBoundaryR) {
                            assert Math.abs(curPipRow - prePipRow) <= 2: edifNet.getName();
                            List<Integer> intTileLoc = Arrays.asList(vertBoundaryCol, curPipRow);
                            partPinNet2TileLocMap.put(edifNet, intTileLoc);
                            logger.info("### Add partition pin location: " + intTileLoc);
                            break;
                        } else if (curPipCol == vertBoundaryCol) {
                            List<Integer> intTileLoc = Arrays.asList(curPipCol, curPipRow);
                            partPinNet2TileLocMap.put(edifNet, intTileLoc);
                            logger.info("### Add partition pin location: " + intTileLoc);
                            break;
                        }
                        prePipRow = curPipRow;
                        prePipCol = curPipCol;
                    }
                }
            }
        }

        // Convert tile loc to INT Tile loc
        for (List<Integer> tileLoc : partPinNet2TileLocMap.values()) {
            Tile intTile = targetDevice.getTile(tileLoc.get(1), tileLoc.get(0));
            String tileName = intTile.getName();
            int xIdx = tileName.indexOf("X");
            int yIdx = tileName.indexOf("Y");
            assert tileName.substring(0, xIdx).equals("INT_");
            String xString = tileName.substring(xIdx + 1, yIdx);
            String yString = tileName.substring(yIdx + 1);

            tileLoc.set(0, Integer.parseInt(xString));
            tileLoc.set(1, Integer.parseInt(yString));
        }

        logger.info("## Total number of partition pins extracted from routed design: " + partPinNet2TileLocMap.size());
        return partPinNet2TileLocMap;
    }

    // helper functions
    private String logMsg(String msg) {
        if (logHierDepth > 0) {
            return "#".repeat(logHierDepth) + " " + msg;
        }
        return msg;
    }

    private void newSubStep() {
        logHierDepth++;
    }

    private void endSubStep() {
        if (logHierDepth > 0) {
            logHierDepth--;
        }
    }

    public static void main(String[] args) {

        String designName = "blue-rdma-direct-rst-ooc-flat2";
        String rootDir = "blue-rdma-peripheral";
        String designDcpPath = String.format("./benchmarks/%s/%s.dcp", designName, designName);
        String abstractNetlistJsonPath = String.format("./benchmarks/%s/%s.json", designName, designName);
        String placeJsonPath = String.format("./benchmarks/%s/place_result.json", designName);

        RapidPnR pnR = new RapidPnR(designDcpPath, abstractNetlistJsonPath, placeJsonPath, rootDir);
        pnR.run();
    }
}
