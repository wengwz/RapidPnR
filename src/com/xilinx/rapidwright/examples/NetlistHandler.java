package com.xilinx.rapidwright.examples;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.xilinx.rapidwright.examples.PartitionResultsJson;
import com.xilinx.rapidwright.examples.PartitionGroupJson;
import com.xilinx.rapidwright.examples.PartitionEdgeJson;

public class NetlistHandler {

    private Logger logger;
    private Design originDesign;
    private EDIFNetlist originNetlist;
    private EDIFCell originTopCell;

    private EDIFNetlist flatNetlist;
    private EDIFCell flatTopCell;

    // Flat Netlist Info
    //// Reset & Clock
    private String clkPortName;
    private String rstPortName;
    private Set<EDIFCellInst> globalResetTreeCellInsts;
    private Set<EDIFNet> globalResetNets;
    private Set<EDIFNet> globalClockNets;
    private Set<EDIFNet> ignoreNets;

    //// Resource Utils
    Integer flatNetlistUnisimCellNum;
    Integer flatNetlistLeafCellNum;
    private Map<EDIFCell, Integer> flatNetlistLeafCellUtil;

    // Abstract Netlist Info
    private List<Set<EDIFCellInst>> group2CellInstMap;
    private Map<EDIFCellInst, Integer> cellInst2GroupMap;

    private List<Set<Integer>> edge2GroupIdxMap;
    private List<Set<Integer>> group2EdgeIdxMap;

    private List<Map<EDIFCell, Integer>> group2LeafCellUtilMap;
    private List<Integer> group2LeafCellNumMap;


    
    public NetlistHandler(String designFilePath, Boolean isFlatNetlist, String clkPortName, String rstPortName, List<String> ignoreNets,Logger logger) {
        originDesign = Design.readCheckpoint(designFilePath);
        originNetlist = originDesign.getNetlist();
        originTopCell = originNetlist.getTopCell();

        if (isFlatNetlist) {
            flatNetlist = originNetlist;
        } else {
            flatNetlist = EDIFTools.createFlatNetlist(originNetlist, originDesign.getPartName());
        }
        flatTopCell = flatNetlist.getTopCell();

        this.logger = logger;

        this.clkPortName = clkPortName;
        this.rstPortName = rstPortName;
        traverseGlobalClockNetwork(clkPortName);
        traverseGlobalResetNetwork(rstPortName);
        this.ignoreNets = new HashSet<>();
        if (ignoreNets != null) {
            for (String netName : ignoreNets) {
                EDIFNet net = flatTopCell.getNet(netName);
                assert net != null: "Invalid Ignore Net Name: " + netName;
                this.ignoreNets.add(net);
            }
        }

        flatNetlistLeafCellUtil = new HashMap<>();
        NetlistUtils.getLeafCellUtils(flatTopCell, flatNetlistLeafCellUtil);
        flatNetlistUnisimCellNum = flatTopCell.getCellInsts().size();
        flatNetlistLeafCellNum = flatNetlistLeafCellUtil.values().stream().mapToInt(Integer::intValue).sum();

        buildGroup2CellInstMap();
        buildEdge2GroupMap();
    }

    private void traverseGlobalClockNetwork(String clkPortName) {
        // only applicable under ooc mode
        logger.info("# Start Traversing Global Clock Network:");
        globalClockNets = new HashSet<>();

        EDIFNet clkNet = flatTopCell.getNet(clkPortName);
        assert clkNet != null: "Invalid Clock Port Name: " + clkPortName;
        globalClockNets.add(clkNet);
    }

    private void traverseGlobalResetNetwork(String resetPortName) {
        globalResetNets = new HashSet<>();
        globalResetTreeCellInsts = new HashSet<>();

        if (resetPortName == null) return;

        logger.info("# Start Traversing Global Reset Network:");
        List<EDIFCellInst> nonRegResetSinkInsts = new ArrayList<>();
        List<EDIFCellInst> nonRegLutResetSinkInsts = new ArrayList<>();

        EDIFNet originResetNet = flatNetlist.getTopCell().getNet(resetPortName);
        assert originResetNet != null: "Invalid Reset Port Name: " + resetPortName;

        Queue<EDIFCellInst> searchRstInsts = new LinkedList<>();

        while (!searchRstInsts.isEmpty() || !globalResetNets.contains(originResetNet)) {
            EDIFNet fanoutRstNet;
            if (searchRstInsts.isEmpty()) {
                fanoutRstNet = originResetNet;
                logger.info("  Toplevel Reset Port "+ resetPortName + "->" + originResetNet.getName() + ":");
            } else {
                EDIFCellInst searchRstInst = searchRstInsts.poll();
                List<EDIFPortInst> fanoutPortInsts = NetlistUtils.getOutPortsOf(searchRstInst);
                assert fanoutPortInsts.size() == 1;
                EDIFPortInst fanoutPortInst = fanoutPortInsts.get(0);
                fanoutRstNet = fanoutPortInst.getNet();
                logger.info("  " + searchRstInst.getName() + ":" + fanoutPortInst.getName() + "->" + fanoutRstNet.getName() + ":");
            }
            
            assert !globalResetNets.contains(fanoutRstNet);
            globalResetNets.add(fanoutRstNet);

            for (EDIFPortInst incidentPortInst : NetlistUtils.getSinkPortsOf(fanoutRstNet)) {
                
                EDIFCellInst incidentCellInst = incidentPortInst.getCellInst();
                if (incidentCellInst == null) continue; // Special case for toplevel reset ports
                logger.info("    " + incidentCellInst.getName() + "(" + incidentCellInst.getCellName() + ")" + ": " + incidentPortInst.getName());
                
                //assert isRegisterCellInst(incidentCellInst): "Non-Register Instances on The Reset Tree";
                // Reset Signals may connect to RAMB36E2 and DSP
                //assert isRegisterCellInst(incidentCellInst) || isLutCellInst(incidentCellInst);         
                if (NetlistUtils.isRegisterCellInst(incidentCellInst)) {
                    assert incidentPortInst.getName().equals("D") || incidentPortInst.getName().equals("S") || incidentPortInst.getName().equals("R"): 
                    "Invalid Reset Port Name: " + incidentPortInst.getName();
                    if (incidentPortInst.getName().equals("D")) {
                        searchRstInsts.add(incidentCellInst);
                        assert !globalResetTreeCellInsts.contains(incidentCellInst);
                        globalResetTreeCellInsts.add(incidentCellInst);
                    }
                } else if (NetlistUtils.isLutOneCellInst(incidentCellInst)) {
                    assert !globalResetTreeCellInsts.contains(incidentCellInst);
                    globalResetTreeCellInsts.add(incidentCellInst);
                    searchRstInsts.add(incidentCellInst);
                } else if (NetlistUtils.isLutCellInst(incidentCellInst)) {
                    List<EDIFPortInst> incidentCellInstOutPorts = NetlistUtils.getOutPortsOf(incidentCellInst);
                    //assert incidentCellInstOutPorts.size() == 1;
                    EDIFPortInst incidentCellInstOutPort = incidentCellInstOutPorts.get(0);
                    for (EDIFPortInst portInst : NetlistUtils.getSinkPortsOf(incidentCellInstOutPort.getNet())) {
                        if (!NetlistUtils.isRegisterCellInst(portInst.getCellInst())) {
                            nonRegLutResetSinkInsts.add(portInst.getCellInst());
                        } else {
                            //assert incidentCellInstOutPort.getNet().getPortInsts().size() == 2;
                        }
                    }
                    //assert netSinkPorts.size() == 1;
                    //assert isRegisterCellInst(netSinkPorts.get(0).getCellInst()) && netSinkPorts.get(0).getName().equals("D");
                    // LUTs incorporating reset logic may connect to multiple RAMB36E2
                } else {
                    nonRegResetSinkInsts.add(incidentCellInst);

                }
            }                
            
        }

        logger.info("## Global Reset Signal Bridges CellInsts: ");
        for (EDIFCellInst cellInst : globalResetTreeCellInsts) {
            logger.info("    " + cellInst.getName() + "(" + cellInst.getCellName() + ")");
        }
        logger.info("## Global Reset Signal Nets: ");
        for (EDIFNet net : globalResetNets) {
            logger.info("    " + net.getName());
        }
        logger.info("## Non-Register Reset Sink Cell Insts: ");
        for (EDIFCellInst cellInst : nonRegResetSinkInsts) {
            logger.info("    " + cellInst.getName() + "( " + cellInst.getCellName() + " )");
        }
        logger.info("## Non-Register LUT-Reset Cell Insts: ");
        for (EDIFCellInst cellInst : nonRegLutResetSinkInsts) {
            logger.info("    " + cellInst.getName() + "( " + cellInst.getCellName() + " )");
        }
    }

    public void printFlatNetlistInfo() {
        logger.info("# Flat Netlist Info:");
        logger.info("## Cell Library Info:");
        for (Map.Entry<String, EDIFLibrary> entry : flatNetlist.getLibrariesMap().entrySet()) {
            logger.info("### " + entry.getKey() + ":");
            
            for (EDIFCell cell : entry.getValue().getCells()) {
                if (cell == flatNetlist.getTopCell()) continue;
                String cellTypeInfo = "    " + cell.getName();
                cellTypeInfo += " isleaf: " + cell.isLeafCellOrBlackBox();
                cellTypeInfo += " isprim: " + cell.isPrimitive();
                if (!cell.isLeafCellOrBlackBox()) {
                    Map<EDIFCell, Integer> leafCellUtilMap = new HashMap<>();
                    NetlistUtils.getLeafCellUtils(cell, leafCellUtilMap);
                    cellTypeInfo += "(";
                    for (Map.Entry<EDIFCell, Integer> leafCellEntry : leafCellUtilMap.entrySet()) {
                        cellTypeInfo += " " + leafCellEntry.getKey().getName() + ":" + leafCellEntry.getValue();
                    }
                    cellTypeInfo += ")";
                }
                logger.info(cellTypeInfo);
            }
        }

        logger.info("## Unisim/Blackbox Cell Utilization:");
        Map<EDIFCell, Integer> cell2AmountMap = new HashMap<>();
        for (EDIFCellInst cellInst : flatTopCell.getCellInsts()) {
            EDIFCell cellType = cellInst.getCellType();
            if (cell2AmountMap.containsKey(cellType)) {
                Integer amount = cell2AmountMap.get(cellType);
                cell2AmountMap.replace(cellType, amount + 1);
            } else {
                cell2AmountMap.put(cellType, 1);
            }
        }
        logger.info("### Total number of Unisim/Blackbox cells: " + flatNetlistUnisimCellNum);
        logger.info("### Unisim/Blackbox Cell Distribution:");
        for (Map.Entry<EDIFCell, Integer> entry : cell2AmountMap.entrySet()) {
            float ratio = (float) entry.getValue() / flatNetlistUnisimCellNum * 100;
            logger.info(String.format("    %s: %d (%.2f%%)", entry.getKey().getName(), entry.getValue(), ratio));
        }

        logger.info("## Primitive/Leaf Cell Utilization:");
        
        logger.info("### Total number of Primitive/Leaf cells: " + flatNetlistLeafCellNum);
        logger.info("### Primitive/Leaf Cell Distribution:");
        for (Map.Entry<EDIFCell, Integer> entry : flatNetlistLeafCellUtil.entrySet()) {
            float ratio = (float) entry.getValue() / flatNetlistLeafCellNum * 100;
            logger.info(String.format("    %s: %d (%.2f%%)", entry.getKey().getName(), entry.getValue(), ratio));
        }

        logger.info("## Resource Type Distribution:");
        Map<String, Integer> resTypeUtil = NetlistUtils.getResTypeUtils(flatNetlistLeafCellUtil);
        for (Map.Entry<String, Integer> entry : resTypeUtil.entrySet()) {
            float ratio = (float) entry.getValue() / flatNetlistLeafCellNum * 100;
            logger.info(String.format("    %s: %d (%.2f%%)", entry.getKey(), entry.getValue(), ratio));
        }

        logger.info("## Connection Info:");
        Integer totalNetAmount = flatTopCell.getNets().size();
        Integer vccGndNetCount = 0;
        Map<EDIFNet, Integer> net2DegreeMap = new HashMap<>();
        Map<Integer, Integer> degree2NetAmountMap = new HashMap<>();
        for (EDIFNet net : flatTopCell.getNets()) {
            if (net.isVCC() || net.isGND()) {
                vccGndNetCount++;
                continue;
            }

            int netDegree = net.getPortInsts().size();
            assert !net2DegreeMap.containsKey(net);
            net2DegreeMap.put(net, netDegree);

            netDegree = (netDegree / 50) * 50;
            if (degree2NetAmountMap.containsKey(netDegree)) {
                Integer amount = degree2NetAmountMap.get(netDegree);
                degree2NetAmountMap.replace(netDegree, amount + 1);
            } else {
                degree2NetAmountMap.put(netDegree, 1);
            }
        }

        List<Map.Entry<EDIFNet, Integer>> sortedNet2DegreeMap = net2DegreeMap.entrySet()
            .stream()
            .sorted(Map.Entry.<EDIFNet, Integer>comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());
        logger.info("### Total number of nets: " + totalNetAmount);
        logger.info("### Number of VCC&GND nets: " + vccGndNetCount);
        logger.info("### Number of other nets: " + net2DegreeMap.size());
        logger.info("### Top 40 Fanout Nets:");
        for (int i = 0; i < 30; i++) {
            EDIFNet hierNet = sortedNet2DegreeMap.get(i).getKey();
            Integer fanoutNum = sortedNet2DegreeMap.get(i).getValue();
            logger.info("    " + hierNet.getName() + ": " + fanoutNum);
        }

        logger.info("### Net Degree Distribution:");
        List<Map.Entry<Integer, Integer>> sortedDegree2AmountMap = degree2NetAmountMap.entrySet()
            .stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByKey())
            .collect(Collectors.toList());
        for (Map.Entry<Integer, Integer> entry : sortedDegree2AmountMap) {
            float ratio = (float) entry.getValue() / totalNetAmount * 100;
            logger.info(String.format("    Degree from %d to %d: %d (%.2f%%)", entry.getKey(), entry.getKey() + 50, entry.getValue(), ratio));
        }
    }

    public void printToplevelPorts() {
        logger.info("# Toplevel Ports:");
        for (EDIFPort port : flatTopCell.getPorts()) {
            logger.info("  " + port.getName() + ": " + port.getDirection() + " " + port.getWidth());
        }
    }

    public void printAbstractGroupsInfo() {
        Integer totalAbstractGroupNum = group2CellInstMap.size();
        logger.info("");
        logger.info("# Abstract Groups Info:");
        logger.info("## Total Number of Abstract Groups: " + totalAbstractGroupNum);

        Map<Integer, Integer> leafCellNum2AmountMap = new HashMap<>();
        Map<Integer, Integer> incidentEdgeNum2AmountMap = new HashMap<>();


        for (int i = 0; i < group2CellInstMap.size(); i++){
            Set<Integer> grpIncidnetEdges = group2EdgeIdxMap.get(i);
            Integer grpPrimCellNum = group2LeafCellNumMap.get(i);

            if (leafCellNum2AmountMap.containsKey(grpPrimCellNum)) {
                Integer amount = leafCellNum2AmountMap.get(grpPrimCellNum);
                leafCellNum2AmountMap.replace(grpPrimCellNum, amount + 1);
            } else {
                leafCellNum2AmountMap.put(grpPrimCellNum, 1);
            }

            Integer grpIncidentEdgeNum = grpIncidnetEdges.size();
            if (incidentEdgeNum2AmountMap.containsKey(grpIncidentEdgeNum)) {
                Integer amount = incidentEdgeNum2AmountMap.get(grpIncidentEdgeNum);
                incidentEdgeNum2AmountMap.replace(grpIncidentEdgeNum, amount + 1);
            } else {
                incidentEdgeNum2AmountMap.put(grpIncidentEdgeNum, 1);
            }
        }

        Integer totalLeafCellNum = group2LeafCellNumMap.stream().mapToInt(Integer::intValue).sum();
        List<Map.Entry<Integer, Integer>> sortedLeafCellNum2AmountMap = leafCellNum2AmountMap.entrySet()
        .stream()
        .sorted(Map.Entry.<Integer, Integer>comparingByKey())
        .collect(Collectors.toList());
        logger.info("## Total Num of Leaf Cells Involved in Groups: " + totalLeafCellNum);
        logger.info("## Group Leaf Cell Util Distribution:");
        for (Map.Entry<Integer, Integer> entry : sortedLeafCellNum2AmountMap) {
            float singleGroupRatio = (float)entry.getKey() / totalLeafCellNum * 100;
            float totalGroupsRatio = (float)entry.getKey() * (float)entry.getValue() / totalLeafCellNum * 100;
            logger.info(String.format("## Number of groups with %d leaf cells(%f): %d (%f)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
        }

        Integer totalEdgeNum = edge2GroupIdxMap.size();
        List<Map.Entry<Integer, Integer>> sortedIncidentEdgeNum2AmountMap = incidentEdgeNum2AmountMap.entrySet()
        .stream()
        .sorted(Map.Entry.<Integer, Integer>comparingByKey())
        .collect(Collectors.toList());
        logger.info("## Total Num of Abstract Edges:" + totalEdgeNum);
        logger.info("## Group Incident Edge Num Distribution:");
        for (Map.Entry<Integer, Integer> entry : sortedIncidentEdgeNum2AmountMap) {
            float edgeNumRatio = (float)entry.getKey() / totalEdgeNum * 100;
            float groupNumRatio = (float)entry.getValue() / totalAbstractGroupNum * 100;
            logger.info(String.format("## Number of Groups Incident with %d(%f) Edges: %d (%f)", entry.getKey(), edgeNumRatio, entry.getValue(), groupNumRatio));
        }
    }

    public void printAbstractEdgesInfo() {
        logger.info("");
        logger.info("# Abstract Edges Info:");
        logger.info("## Total Number of Abstract Edges: " + edge2GroupIdxMap.size());

        Map<Integer, Integer> degree2AmountMap = new HashMap<>();
        for (int i = 0; i < edge2GroupIdxMap.size(); i++) {
            Set<Integer> groupIdxSet = edge2GroupIdxMap.get(i);

            Integer edgeDegree = (groupIdxSet.size() / 10) * 10;

            if (degree2AmountMap.containsKey(edgeDegree)) {
                Integer amount = degree2AmountMap.get(edgeDegree);
                degree2AmountMap.replace(edgeDegree, amount + 1);
            } else {
                degree2AmountMap.put(edgeDegree, 1);
            }
            
        }

        logger.info("## Edge Degree Distribution:");
        List<Map.Entry<Integer, Integer>> sortedDegree2AmountMap = degree2AmountMap.entrySet()
            .stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByKey())
            .collect(Collectors.toList());

        for (Map.Entry<Integer, Integer> entry : sortedDegree2AmountMap) {
            float ratio = (float) entry.getValue() / edge2GroupIdxMap.size() * 100;
            logger.info(String.format("    Degree from %d to %d: %d (%.2f%%)", entry.getKey(), entry.getKey() + 10, entry.getValue(), ratio));
        }
    }

    private void buildGroup2CellInstMap() {
        logger.info("# Start Building Cell Instance Groups:");
        cellInst2GroupMap = new HashMap<>();
        group2CellInstMap = new ArrayList<>();
        group2EdgeIdxMap = new ArrayList<>();
        group2LeafCellUtilMap = new ArrayList<>();
        group2LeafCellNumMap = new ArrayList<>();

        // cluster cellInsts of high-degree nets
        // for (EDIFNet net : flatTopCell.getNets()) {
        //     if (net.isVCC() || net.isGND()) continue;
        //     if (globalClockNets.contains(net) || globalResetNets.contains(net)) continue;
        //     if (net.getPortInsts().size() < 200) continue;

        //     List<EDIFCellInst> cellInsts = new ArrayList<>();
        //     Set<Integer> incidentGrpIdxs = new HashSet<>();
        //     for (EDIFPortInst portInst : net.getPortInsts()) {
        //         EDIFCellInst cellInst = portInst.getCellInst();
        //         if (cellInst2GroupMap.containsKey(cellInst)) {
        //             incidentGrpIdxs.add(cellInst2GroupMap.get(cellInst));
        //         }

        //         cellInsts.add(portInst.getCellInst());
        //     }

        //     assert incidentGrpIdxs.size() <= 1: "Net incident to multiple groups" + incidentGrpIdxs.size() + " Total number of groups: " + group2CellInstMap.size();
        //     Integer incidnetGrpIdx = incidentGrpIdxs.isEmpty() ? -1 : incidentGrpIdxs.iterator().next();

        //     if (incidnetGrpIdx == -1) {
        //         logger.info(String.format("  New Group: %s(%d)", net.getName(), cellInsts.size()));
        //         group2CellInstMap.add(new HashSet<>(cellInsts));
        //         Integer newGrpIdx = group2CellInstMap.size() - 1;
        //         for (EDIFCellInst cellInst : cellInsts) {
        //             cellInst2GroupMap.put(cellInst, newGrpIdx);
        //         }
        //     } else {
        //         logger.info(String.format("  Merge Group: %s(%d)", net.getName(), cellInsts.size()));
        //         group2CellInstMap.get(incidnetGrpIdx).addAll(cellInsts);
        //         for (EDIFCellInst cellInst : cellInsts) {
        //             cellInst2GroupMap.put(cellInst, incidnetGrpIdx);
        //         }
        //     }
        // }

        //

        Integer totalUnisimCellNum = 0;

        for (EDIFCellInst cellInst : flatTopCell.getCellInsts()) {
            if (cellInst.getCellType().isStaticSource()) continue;
            if (globalResetTreeCellInsts.contains(cellInst)) continue;

            assert !cellInst2GroupMap.containsKey(cellInst);
            Integer groupIdx = group2CellInstMap.size();
            Set<EDIFCellInst> cellInsts = new HashSet<>();
            Map<EDIFCell, Integer> primCellUtilMap = new HashMap<>();

            cellInsts.add(cellInst);
            NetlistUtils.getLeafCellUtils(cellInst.getCellType(), primCellUtilMap);
            Integer leafCellNum = primCellUtilMap.values().stream().mapToInt(Integer::intValue).sum();

            group2CellInstMap.add(cellInsts);
            group2EdgeIdxMap.add(new HashSet<>());
            cellInst2GroupMap.put(cellInst, groupIdx);
            group2LeafCellUtilMap.add(primCellUtilMap);
            group2LeafCellNumMap.add(leafCellNum);

            totalUnisimCellNum += 1;
        }

        //assert totalUnisimCellNum + globalResetTreeCellInsts.size() + 2 == flatNetlistUnisimCellNum;
    }

    private void buildEdge2GroupMap() {
        edge2GroupIdxMap = new ArrayList<>();
        // Default
        for (EDIFNet net : flatTopCell.getNets()) {
            if (net.isVCC() || net.isGND()) continue;
            if (globalClockNets.contains(net) || globalResetNets.contains(net)) continue;
            if (ignoreNets.contains(net)) continue;

            Integer edgeIdx = edge2GroupIdxMap.size();
            Set<Integer> groupIdxs = new HashSet<>();
            for (EDIFPortInst portInst : net.getPortInsts()) {
                EDIFCellInst cellInst = portInst.getCellInst();
                if (cellInst == null) continue; // Special case for toplevel ports
                assert cellInst2GroupMap.containsKey(cellInst);
                Integer groupIdx = cellInst2GroupMap.get(cellInst);
                groupIdxs.add(groupIdx);
                group2EdgeIdxMap.get(groupIdx).add(edgeIdx);
            }
            if (groupIdxs.size() == 0) {
                logger.info("Net " + net.getName() + "with "+ net.getPortInsts().size() +"ports has no incident group");
            }
            edge2GroupIdxMap.add(groupIdxs);
        }
        //
    }

    public void writeProcessedNetlistJson(String filePath, Map<String, List<Integer>> ioConstraints) {
        logger.info("# Start Writing Partition Resutls in JSON Format");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        PartitionResultsJson partitionResultsJson = new PartitionResultsJson();

        Map<String, Integer> resTypeUtil = NetlistUtils.getResTypeUtils(flatTopCell);
        partitionResultsJson.totalPrimCellNum = resTypeUtil.values().stream().mapToInt(Integer::intValue).sum();
        partitionResultsJson.resourceTypeUtil = resTypeUtil;
        partitionResultsJson.totalGroupNum = group2CellInstMap.size();
        partitionResultsJson.totalEdgeNum = edge2GroupIdxMap.size();

        // Add group info
        List<PartitionGroupJson> partitionGroupJsons = new ArrayList<>();
        for (int i = 0; i < group2CellInstMap.size(); i++) {
            PartitionGroupJson partitionGroupJson = new PartitionGroupJson();
            Map<EDIFCell, Integer> primCellUtilMap = group2LeafCellUtilMap.get(i);

            partitionGroupJson.id = i;
            partitionGroupJson.primCellNum = group2LeafCellNumMap.get(i);
            partitionGroupJson.resourceTypeUtil = NetlistUtils.getResTypeUtils(primCellUtilMap);
            partitionGroupJson.groupCellNames = group2CellInstMap.get(i).stream().map(cellInst -> cellInst.getName()).collect(Collectors.toList());
            
            partitionGroupJsons.add(partitionGroupJson);
        }

        // Add IO Constraints
        Map<Integer, List<Integer>> grpIdx2LocConstrMap = new HashMap<>();
        if (ioConstraints != null) {
            Map<String, List<Integer>> extIOConstraints = processIOConstraints(ioConstraints);
            for (EDIFPort port : flatNetlist.getTopCell().getPorts()) {
                
                List<EDIFNet> internalNets = port.getInternalNets();
                
                for (int i = 0; i < internalNets.size(); i++) {
                    EDIFNet net = internalNets.get(i);
                    String portInstName = port.getPortInstNameFromPort(i);
                    List<Integer> locConstr = extIOConstraints.get(portInstName);
                    if (net == null || net.isGND() || net.isVCC() || globalResetNets.contains(net)) continue;
                    if (locConstr == null) continue;

                    for (EDIFPortInst portInst : net.getPortInsts()) {
                        EDIFCellInst cellInst = portInst.getCellInst();
                        if (cellInst == null) continue; // Skip toplevel ports

                        assert cellInst2GroupMap.containsKey(cellInst);
                        Integer incidentGrpIdx = cellInst2GroupMap.get(cellInst);
                        
                        if (grpIdx2LocConstrMap.containsKey(incidentGrpIdx)) {
                            assert grpIdx2LocConstrMap.get(incidentGrpIdx).equals(locConstr):
                            String.format("Multi constraints on group %d through cell %s", incidentGrpIdx, cellInst.getName());
                        } else {
                            grpIdx2LocConstrMap.put(incidentGrpIdx, locConstr);
                            logger.info(String.format("Set constraint on group %d through cell %s by port %s", incidentGrpIdx, cellInst.getName(), portInst));
                        }
                    }
                }
            }
        }

        for (Map.Entry<Integer, List<Integer>> entry : grpIdx2LocConstrMap.entrySet()) {
            partitionGroupJsons.get(entry.getKey()).loc = entry.getValue();
        }
        partitionResultsJson.partitionGroups = partitionGroupJsons;

        // Add edge info
        List<PartitionEdgeJson> partitionEdgeJsons = new ArrayList<>();
        for (int i = 0; i < edge2GroupIdxMap.size(); i++) {
            PartitionEdgeJson partitionEdgeJson = new PartitionEdgeJson();
            partitionEdgeJson.id = i;
            partitionEdgeJson.primCellNum = 1; // Unused field
            partitionEdgeJson.weight = 1; // Default weight
            partitionEdgeJson.degree = edge2GroupIdxMap.get(i).size();
            partitionEdgeJson.incidentGroupIds = new ArrayList<>(edge2GroupIdxMap.get(i));
            partitionEdgeJson.edgeCellNames = new ArrayList<>(); // Unused field
            
            partitionEdgeJsons.add(partitionEdgeJson);
        }
        partitionResultsJson.partitionEdges = partitionEdgeJsons;

        // Add Reset and Clock Info
        List<String> resetCellNames = globalResetTreeCellInsts.stream().map(cellInst -> cellInst.getName()).collect(Collectors.toList());
        List<String> resetNetNames = globalResetNets.stream().map(net -> net.getName()).collect(Collectors.toList());
        List<String> clkNetNames = globalClockNets.stream().map(net -> net.getName()).collect(Collectors.toList());
        List<String> ignoreNetNames = ignoreNets.stream().map(net -> net.getName()).collect(Collectors.toList());
        partitionResultsJson.rstPortName = rstPortName;
        partitionResultsJson.clkPortName = clkPortName;
        partitionResultsJson.resetTreeCellNames = resetCellNames;
        partitionResultsJson.resetNetNames = resetNetNames;
        partitionResultsJson.clkNetNames = clkNetNames;
        partitionResultsJson.ignoreNetNames = ignoreNetNames;
        
        //
        try {
            String jsonString = gson.toJson(partitionResultsJson);
            FileWriter jsonFileWriter = new FileWriter(filePath);
            jsonFileWriter.write(jsonString);
            jsonFileWriter.close();
        } catch (IOException e) {
            logger.info("Error occurred while saving partition results: " + e.getMessage());
        }
        logger.info("# Finish Writing Partition Resutls in JSON Format");
    }

    private Map<String, List<Integer>> processIOConstraints(Map<String, List<Integer>> ioConstr) {
        Map<String, List<Integer>> extIOConstr = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : ioConstr.entrySet()) {
            String portName = entry.getKey();
            Integer portWidth = EDIFTools.getWidthOfPortFromName(portName);
            List<Integer> constrLoc = entry.getValue();
            EDIFPort port = new EDIFPort(portName, null, portWidth);
            if (portWidth == 1) {
                extIOConstr.put(portName, constrLoc);
            } else {
                for (int i = 0; i < portWidth; i++) {
                    String portInstName = port.getPortInstNameFromPort(i);
                    extIOConstr.put(portInstName, constrLoc);
                }
            }
        }
        return extIOConstr;
    }

    public void writeFlatNetlistDCP(String dcpOutputPath) {
        Design flatNetlistDesign = new Design(flatNetlist.getName(), originDesign.getPartName());
        flatNetlistDesign.setNetlist(flatNetlist);
        flatNetlistDesign.setAutoIOBuffers(false);
        flatNetlistDesign.writeCheckpoint(dcpOutputPath);
    }

}
