package com.xilinx.rapidwright.examples;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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
    private List<String> clkPortNames;
    private List<String> rstPortNames;
    private Set<EDIFCellInst> globalResetTreeCellInsts;
    private Set<EDIFNet> globalResetNets;
    private Set<EDIFNet> globalClockNets;
    private Set<EDIFNet> ignoreNets;
    private Set<EDIFNet> illegalNets;
    private Set<EDIFCellInst> staticSourceCellInsts;

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

    public NetlistHandler(String designFilePath, Boolean isFlatNetlist, String clkPortName, String rstPortName, List<String> ignoreNets, Logger logger) {
        this(designFilePath, isFlatNetlist, Arrays.asList(clkPortName), Arrays.asList(rstPortName), ignoreNets, logger);
    }

    public NetlistHandler(String designFilePath, Boolean isFlatNetlist, List<String> clkPortNames, List<String> rstPortNames, List<String> ignoreNets, Logger logger) {
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

        this.clkPortNames = new ArrayList<>(clkPortNames);
        this.rstPortNames = new ArrayList<>(rstPortNames);
        traverseGlobalClockNetwork();
        traverseGlobalResetNetwork(rstPortNames.get(0));
        this.ignoreNets = new HashSet<>();
        if (ignoreNets != null) {
            for (String netName : ignoreNets) {
                EDIFNet net = flatTopCell.getNet(netName);
                assert net != null: "Invalid Ignore Net Name: " + netName;
                this.ignoreNets.add(net);
            }
        }
        filterIllegalNets();
        filterStaticSourceCellInsts();

        flatNetlistLeafCellUtil = new HashMap<>();
        NetlistUtils.getLeafCellUtils(flatTopCell, flatNetlistLeafCellUtil);
        flatNetlistUnisimCellNum = flatTopCell.getCellInsts().size();
        flatNetlistLeafCellNum = flatNetlistLeafCellUtil.values().stream().mapToInt(Integer::intValue).sum();

        // buildGroup2CellInstMap();
        // buildEdge2GroupMap();
        buildTPAwareGroup2CellInstMap();
        buildTPAwareEdge2GroupMap();

        //buildNetClustering();
    }

    private void filterStaticSourceCellInsts() {
        logger.info("# Start filtering static-source cell instances:");
        staticSourceCellInsts = new HashSet<>();
        for (EDIFCellInst cellInst : flatTopCell.getCellInsts()) {
            if (cellInst.getCellType().isStaticSource()) {
                staticSourceCellInsts.add(cellInst);
                logger.info("## Find static-source cell instance: " + cellInst.getName());
            }
        }
    }

    private void filterIllegalNets() {
        // filter nets without incident ports or source cell
        logger.info("# Start filtering illegal nets(without incident cells or source cell):");
        illegalNets = new HashSet<>();
        int emptyNetsNum = 0;
        int undrivenNetsNum = 0;
        for (EDIFNet net : flatTopCell.getNets()) {
            int incidentPortNum = net.getPortInsts().size();
            if (incidentPortNum == 0) {
                illegalNets.add(net);
                emptyNetsNum++;
            } else {
                List<EDIFPortInst> srcPortInsts = net.getSourcePortInsts(true);
                assert srcPortInsts.size() <= 1: String.format("The number of source ports of net %s: %d", net.getName(), srcPortInsts.size());
                if (srcPortInsts.size() == 0) {
                    illegalNets.add(net);
                    logger.info("## Undriven Net: " + net.getName());
                    undrivenNetsNum++;
                }
            }
        }

        assert undrivenNetsNum == 0: "Undriven Nets Exist!";
        logger.info("## Total number of illegal nets: " + illegalNets.size());
        logger.info("## Number of empty nets: " + emptyNetsNum);
        logger.info("## Number of undriven nets: " + undrivenNetsNum);
    }

    private void reduceRegisterFanout(int maxRegFanout, int targetFanout) {
        logger.info("# Start reducing fanout of registered net through register replication:");

        int handledNetNum = 0;
        List<String> flatTopCellNetNames = flatTopCell.getNets().stream().map(EDIFNet::getName).collect(Collectors.toList());
        for (String netName : flatTopCellNetNames) {
            EDIFNet edifNet = flatTopCell.getNet(netName);
            if (edifNet.isVCC() || edifNet.isGND()) continue;
            if (globalClockNets.contains(edifNet) || globalResetNets.contains(edifNet)) continue;
            if (illegalNets.contains(edifNet)) continue;

            int netFanout = edifNet.getPortInsts().size() - 1;
            if (NetlistUtils.isRegFanoutNet(edifNet) && netFanout > targetFanout) {
                logger.info("## The fanout of registered net " + edifNet.getName() + ": " + netFanout);
            }
            if (NetlistUtils.isRegFanoutNet(edifNet) && netFanout > maxRegFanout) {
                logger.info("## Reduce fanout of net: " + edifNet.getName() + " with fanout=" + netFanout);
                EDIFCellInst srcCellInst = NetlistUtils.getSourceCellInstOfNet(edifNet);
                List<EDIFPortInst> sinkPortInsts = NetlistUtils.getSinkPortsOf(edifNet);

                List<List<EDIFPortInst>> splitSinkPortInsts = new ArrayList<>();
                for (int i = 0; i < sinkPortInsts.size(); i += targetFanout) {
                    splitSinkPortInsts.add(sinkPortInsts.subList(i, Math.min(i + targetFanout, sinkPortInsts.size())));
                }

                for (int i = 1; i < splitSinkPortInsts.size(); i++) {
                    String repCellInstName = String.format("%s_rep%d", srcCellInst.getName(), i);
                    NetlistUtils.registerReplication(srcCellInst, repCellInstName, splitSinkPortInsts.get(i));
                }

                logger.info(String.format("## Replicate %d registers for net %s", splitSinkPortInsts.size() - 1, edifNet.getName()));

                handledNetNum++;
            }
        }

        logger.info("## Total number of processed registered nets: " + handledNetNum);
        logger.info("# Complete reducing fanout of high-fanout registered net");
    }

    private void traverseGlobalClockNetwork() {
        // TODO: only applicable under ooc mode
        logger.info("# Start Traversing Global Clock Network:");
        globalClockNets = new HashSet<>();

        for (String clkPortName : clkPortNames) {
            EDIFNet clkNet = flatTopCell.getNet(clkPortName);
            assert clkNet != null: "Invalid Clock Port Name: " + clkPortName;
            globalClockNets.add(clkNet);        
        }

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
                logger.info("  Toplevel Reset Port "+ resetPortName + " -> " + originResetNet.getName() + ":");
            } else {
                EDIFCellInst searchRstInst = searchRstInsts.poll();
                List<EDIFPortInst> fanoutPortInsts = NetlistUtils.getOutPortInstsOf(searchRstInst);
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
                    String portName = incidentPortInst.getName();
                    assert portName.equals("D") || portName.equals("S") || portName.equals("R") || portName.equals("CLR") || portName.equals("PRE"):
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
                    List<EDIFPortInst> incidentCellInstOutPorts = NetlistUtils.getOutPortInstsOf(incidentCellInst);
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
                } else {
                    cellTypeInfo += " Ports: ";
                    for (EDIFPort port : cell.getPorts()) {
                        cellTypeInfo += port.getName() + " ";
                    }
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
            if (illegalNets.contains(net)) continue;

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
        logger.info("### Number of illegal nets: " + illegalNets.size());
        logger.info("### Number of other nets: " + net2DegreeMap.size());
        logger.info("### Top 50 Fanout Nets:");
        for (int i = 0; i < 50; i++) {
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

        logger.info("### Number of Clock Nets: " + globalClockNets.size());
        logger.info("### Number of Reset Nets: " + globalResetNets.size());
        logger.info("### Number of Ignored Nets: " + ignoreNets.size());
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
        Map<Integer, Integer> lutNum2AmountMap = new HashMap<>();


        for (int i = 0; i < group2CellInstMap.size(); i++){
            Set<Integer> grpIncidnetEdges = group2EdgeIdxMap.get(i);
            Integer grpPrimCellNum = group2LeafCellNumMap.get(i);
            Integer grpLutNum = 0;

            for (Map.Entry<EDIFCell, Integer> entry : group2LeafCellUtilMap.get(i).entrySet()) {
                if (NetlistUtils.cellType2ResTypeMap.get(entry.getKey().getName()).equals("LUT")) {
                    grpLutNum += entry.getValue();
                }
            }

            if (leafCellNum2AmountMap.containsKey(grpPrimCellNum)) {
                Integer amount = leafCellNum2AmountMap.get(grpPrimCellNum);
                leafCellNum2AmountMap.replace(grpPrimCellNum, amount + 1);
            } else {
                leafCellNum2AmountMap.put(grpPrimCellNum, 1);
            }

            if (lutNum2AmountMap.containsKey(grpLutNum)) {
                Integer amount = lutNum2AmountMap.get(grpLutNum);
                lutNum2AmountMap.replace(grpLutNum, amount + 1);
            } else {
                lutNum2AmountMap.put(grpLutNum, 1);
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


        Integer totalLUTNum = 0;
        for (Map.Entry<EDIFCell, Integer> entry : flatNetlistLeafCellUtil.entrySet()) {
            if (NetlistUtils.cellType2ResTypeMap.get(entry.getKey().getName()).equals("LUT")) {
                totalLUTNum += entry.getValue();
            }
        }
        logger.info("## Total Number of LUTs: " + totalLUTNum);
        logger.info("## Group LUT Distribution:");
        List<Map.Entry<Integer, Integer>> sortedLUTNum2AmountMap = lutNum2AmountMap.entrySet()
        .stream()
        .sorted(Map.Entry.<Integer, Integer>comparingByKey())
        .collect(Collectors.toList());
        for (Map.Entry<Integer, Integer> entry : sortedLUTNum2AmountMap) {
            float singleGroupRatio = (float)entry.getKey() / totalLUTNum * 100;
            float totalGroupsRatio = (float)entry.getKey() * (float)entry.getValue() / totalLUTNum * 100;
            logger.info(String.format("## Number of groups with %d LUTs(%f): %d (%f)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
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

    // public void printIllegalNets() {
    //     List<EDIFNet> noSinkNets = new ArrayList<>();
    //     List<EDIFNet> undrivenNets = new ArrayList<>();
    //     logger.info("# Start Printing Illegal Nets:");
    //     for (EDIFNet net : flatTopCell.getNets()) {
    //         if (net.isVCC() || net.isGND()) continue;
    //         if (globalClockNets.contains(net) || globalResetNets.contains(net)) continue;
    //         if (ignoreNets.contains(net)) continue;

    //         if (net.getSourcePortInsts(true).size() == 0) {
    //             undrivenNets.add(net);
    //         }
    //     }
        
    //     logger.info("## Total number of undriven nets: " + undrivenNets.size());
    //     logger.info("## Undriven Net Names:");
    //     for (EDIFNet net : undrivenNets) {
    //         List<String> portNames = new ArrayList<>();
    //         for (EDIFPortInst portInst : net.getPortInsts()) {
    //             portNames.add(portInst.getName());
    //         }
    //         String portsNameString = String.join(", ", portNames);
    //         logger.info(String.format("   %s: %d port: %s", net.getName(), portNames.size(), portsNameString));
    //     }
    // }

    public void removeHighFanoutNets(int threshold) {
        logger.info("# Start Removing High Fanout Nets:");
        EDIFNet vccNet = EDIFTools.getStaticNet(NetType.VCC, flatTopCell, flatNetlist);
        //EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, flatTopCell, flatNetlist);
        List<EDIFNet> netToRemove = new ArrayList<>();
        for (EDIFNet net : flatTopCell.getNets()) {
            if (net.isVCC() || net.isGND()) continue;
            if (globalClockNets.contains(net) || globalResetNets.contains(net)) continue;
            if (ignoreNets.contains(net)) continue;

            int fanoutNum = net.getPortInsts().size();

            if (fanoutNum > threshold) {
                logger.info("## Removing High Fanout Net: " + net.getName() + " with " + fanoutNum + " fanouts");
                List<EDIFPortInst> portInsts = new ArrayList<>(net.getPortInsts());
                for (EDIFPortInst portInst : portInsts) {
                    String portInstName = portInst.getName();
                    EDIFCellInst cellInst = portInst.getCellInst();
                    net.removePortInst(portInst);
                    if (portInst.isInput()) {
                        vccNet.createPortInst(portInstName, cellInst);
                    }
                }
                netToRemove.add(net);
            }
        }
        for (EDIFNet net : netToRemove) {
            flatTopCell.removeNet(net);
        }
    }

    public void removeIOInsts() {
        logger.info("# Start removing IO buffer instances to create OOC netlist:");
        Map<String, EDIFNet> internalNetMap = flatTopCell.getInternalNetMap();
        for (Map.Entry<String, EDIFNet> entry : internalNetMap.entrySet()) {
            String portName = entry.getKey();
            EDIFNet internalNet = entry.getValue();
            
            logger.info("## Removing IO buffer instances of port: " + portName);
            Queue<EDIFPortInst> portInstQ = new LinkedList<>();
            for (EDIFPortInst portInst : internalNet.getPortInsts()) {
                if (!portInst.isTopLevelPort()) {
                    portInstQ.add(portInst);
                }
            }

            while(!portInstQ.isEmpty()) {
                EDIFPortInst portInst = portInstQ.poll();
                EDIFCellInst cellInst = portInst.getCellInst();
                assert cellInst != null;

                if (NetlistUtils.isIOBufCellInst(cellInst)) {
                    logger.info("### Removing IO buffer instance: " + cellInst.getName());

                    List<EDIFPortInst> fanoutPortInsts = NetlistUtils.getOutPortInstsOf(cellInst);
                    assert fanoutPortInsts.size() == 1;
                    EDIFPortInst fanoutPortInst = fanoutPortInsts.get(0);
                    EDIFNet fanoutNet = fanoutPortInst.getNet();
                    for (EDIFPortInst incidentPortInst : NetlistUtils.getSinkPortsOf(fanoutNet)) {
                        String incidentPortName = incidentPortInst.getName();
                        EDIFCellInst incidentCellInst = incidentPortInst.getCellInst();
                        fanoutNet.removePortInst(incidentPortInst);
                        EDIFPortInst newIncidentPortInst = internalNet.createPortInst(incidentPortName, incidentCellInst);
                        portInstQ.add(newIncidentPortInst);
                    }
                    fanoutNet.removePortInst(fanoutPortInst);
                    flatTopCell.removeNet(fanoutNet);

                    // Remove fanin ports from their nets
                    List<EDIFPortInst> faninPortInsts = NetlistUtils.getInPortInstsOf(cellInst);
                    for (EDIFPortInst faninPortInst : faninPortInsts) {
                        EDIFNet faninNet = faninPortInst.getNet();
                        faninNet.removePortInst(faninPortInst);
                        if (NetlistUtils.getSinkPortsOf(faninNet).size() == 0) {
                            flatTopCell.removeNet(faninNet);
                        }
                    }

                    flatTopCell.removeCellInst(cellInst);
                }
            }
        }
    }

    private void buildTPAwareGroup2CellInstMap() {
        logger.info("# Start timing-path-aware cell clustering");
        cellInst2GroupMap = new HashMap<>();
        group2CellInstMap = new ArrayList<>();
        group2EdgeIdxMap = new ArrayList<>();
        group2LeafCellUtilMap = new ArrayList<>();
        group2LeafCellNumMap = new ArrayList<>();


        Set<EDIFNet> visitedNetsCls = new HashSet<>();
        // Remove global clock/reset nets and ignore nets
        visitedNetsCls.addAll(globalClockNets);
        visitedNetsCls.addAll(globalResetNets);
        visitedNetsCls.addAll(ignoreNets);
        visitedNetsCls.addAll(illegalNets);

        // Remove static nets and reg-fanout nets
        for (EDIFNet net : flatTopCell.getNets()) {
            boolean staticNet = net.isVCC() || net.isGND();
            if (visitedNetsCls.contains(net)) continue;

            List<EDIFPortInst> srcPortInsts = net.getSourcePortInsts(true);
            assert srcPortInsts.size() == 1;
            
            //boolean highFanoutNet = net.getPortInsts().size() > 500;
            EDIFPortInst srcPortInst = srcPortInsts.get(0);
            EDIFCellInst srcCellInst = srcPortInst.getCellInst();
            
            boolean isRegFanoutNet = false;
            if (srcCellInst != null) {
                isRegFanoutNet = NetlistUtils.isRegisterCellInst(srcCellInst);
            }

            if (staticNet || isRegFanoutNet) {
                visitedNetsCls.add(net);
            }
        }
        logger.info("## The number of visited nets before expansion: " + visitedNetsCls.size());

        for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
            if (globalResetTreeCellInsts.contains(cellInst)) continue;
            if (staticSourceCellInsts.contains(cellInst)) continue;
            if (cellInst2GroupMap.containsKey(cellInst)) continue;

            Integer grpIdx = group2CellInstMap.size();
            Set<EDIFCellInst> grpCellInsts = new HashSet<>();
            Queue<EDIFCellInst> cellInstToSearch =  new LinkedList<>();

            grpCellInsts.add(cellInst);
            cellInst2GroupMap.put(cellInst, grpIdx);
            cellInstToSearch.add(cellInst);

            while (!cellInstToSearch.isEmpty()) {
                EDIFCellInst expandCellInst = cellInstToSearch.poll();
                
                for (EDIFPortInst expandPortInst : expandCellInst.getPortInsts()) {
                    EDIFNet expandNet = expandPortInst.getNet();
                    if (visitedNetsCls.contains(expandNet)) continue;
                    visitedNetsCls.add(expandNet);

                    for (EDIFPortInst portInst : expandNet.getPortInsts()) {
                        EDIFCellInst portCellInst = portInst.getCellInst();
                        if (portCellInst == null) continue; // Skip toplevel ports
                        if (cellInst2GroupMap.containsKey(portCellInst)) continue;

                        cellInst2GroupMap.put(portCellInst, grpIdx);
                        grpCellInsts.add(portCellInst);
                        cellInstToSearch.add(portCellInst);
                    }
                }
            }

            group2CellInstMap.add(grpCellInsts);
            group2EdgeIdxMap.add(new HashSet<>());
            Map<EDIFCell, Integer> primCellUtilMap = new HashMap<>();
            for (EDIFCellInst cellInstInGrp : grpCellInsts) {
                NetlistUtils.getLeafCellUtils(cellInstInGrp.getCellType(), primCellUtilMap);
            }
            group2LeafCellUtilMap.add(primCellUtilMap);
            Integer primCellNum = primCellUtilMap.values().stream().mapToInt(Integer::intValue).sum();
            group2LeafCellNumMap.add(primCellNum);
        }

        int grpCellInstsNum = cellInst2GroupMap.size();
        int rstTreeCellInstsNum = globalResetTreeCellInsts.size();
        int totalCellInstsNum = flatTopCell.getCellInsts().size();
        int staticSourceCellInstsNum = staticSourceCellInsts.size();
        assert totalCellInstsNum == grpCellInstsNum + rstTreeCellInstsNum + staticSourceCellInstsNum;
    }

    private void buildTPAwareEdge2GroupMap() {
        logger.info("# Start building timing-path-aware edge-group map:");
        edge2GroupIdxMap = new ArrayList<>();
        for (EDIFNet net : flatTopCell.getNets()) {
            if (net.isVCC() || net.isGND()) continue;
            if (globalClockNets.contains(net) || globalResetNets.contains(net)) continue;
            if (ignoreNets.contains(net)) continue;

            Set<Integer> incidentGrpIdxs = new HashSet<>();
            for (EDIFPortInst portInst : net.getPortInsts()) {
                EDIFCellInst cellInst = portInst.getCellInst();
                if (cellInst == null) continue; // Skip toplevel ports
                assert cellInst2GroupMap.containsKey(cellInst);
                Integer groupIdx = cellInst2GroupMap.get(cellInst);
                incidentGrpIdxs.add(groupIdx);
            }

            if (incidentGrpIdxs.size() > 1) {
                assert NetlistUtils.isRegFanoutNet(net);
                edge2GroupIdxMap.add(incidentGrpIdxs);
                for (Integer groupIdx : incidentGrpIdxs) {
                    group2EdgeIdxMap.get(groupIdx).add(edge2GroupIdxMap.size() - 1);
                }
            }
        }
    }

    // private void buildNetClustering() {
    //     List<Set<EDIFNet>> cluster2NetMap = new ArrayList<>();
    //     Map<EDIFNet, Integer> net2ClusterMap = new HashMap<>();

    //     Set<EDIFCellInst> visitedCellInsts = new HashSet<>();
        
    //     for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
    //         if (NetlistUtils.isRegisterCellInst(cellInst)) {
    //             visitedCellInsts.add(cellInst);
    //         }
    //     }

    //     for (EDIFNet net : flatNetlist.getTopCell().getNets()) {
    //         if (net.isGND() || net.isVCC()) continue;
    //         if (globalClockNets.contains(net) || globalResetNets.contains(net)) continue;
    //         if (ignoreNets.contains(net)) continue;

    //         if (net2ClusterMap.containsKey(net)) continue;

    //         Integer clusterIdx = cluster2NetMap.size();
    //         Set<EDIFNet> netCluster = new HashSet<>();
    //         Queue<EDIFNet> netToSearch = new LinkedList<>();

    //         netCluster.add(net);
    //         net2ClusterMap.put(net, clusterIdx);
    //         netToSearch.add(net);

    //         while (!netToSearch.isEmpty()) {
    //             EDIFNet expandNet = netToSearch.poll();
    //             for (EDIFPortInst portInst : expandNet.getPortInsts()) {
    //                 EDIFCellInst cellInst = portInst.getCellInst();
    //                 if (cellInst == null) continue; // Skip toplevel ports
    //                 if (visitedCellInsts.contains(cellInst)) continue;

    //                 visitedCellInsts.add(cellInst);
    //                 for (EDIFPortInst expandPortInst : cellInst.getPortInsts()) {
    //                     EDIFNet newNet = expandPortInst.getNet();
    //                     if (net2ClusterMap.containsKey(newNet)) continue;

    //                     net2ClusterMap.put(newNet, clusterIdx);
    //                     netCluster.add(newNet);
    //                     netToSearch.add(newNet);
    //                 }
    //             }
    //         }
    //         cluster2NetMap.add(netCluster);
    //     }

    //     // print net cluster info
    //     Integer totalNetClusterNum = cluster2NetMap.size();
    //     logger.info("# Net Clustering Info:");
    //     logger.info("## Total Number of Net Clusters: " + totalNetClusterNum);
    //     Map<Integer, Integer> netClusterSize2AmountMap = new HashMap<>();
    //     for (Set<EDIFNet> netCluster : cluster2NetMap) {
    //         Integer clusterSize = netCluster.size();
    //         if (netClusterSize2AmountMap.containsKey(clusterSize)) {
    //             Integer amount = netClusterSize2AmountMap.get(clusterSize);
    //             netClusterSize2AmountMap.replace(clusterSize, amount + 1);
    //         } else {
    //             netClusterSize2AmountMap.put(clusterSize, 1);
    //         }
    //     }

    //     List<Map.Entry<Integer, Integer>> sortedNetClusterSize2AmountMap = netClusterSize2AmountMap.entrySet()
    //         .stream()
    //         .sorted(Map.Entry.<Integer, Integer>comparingByKey())
    //         .collect(Collectors.toList());
    //     for (Map.Entry<Integer, Integer> entry : sortedNetClusterSize2AmountMap) {
    //         float ratio = (float) entry.getValue() / totalNetClusterNum * 100;
    //         logger.info(String.format("    Cluster with %d nets: %d (%.2f%%)", entry.getKey(), entry.getValue(), ratio));
    //     }
    // }

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
            }

            if (groupIdxs.size() > 1) {
                edge2GroupIdxMap.add(groupIdxs);
                for (Integer groupIdx : groupIdxs) {
                    group2EdgeIdxMap.get(groupIdx).add(edgeIdx);
                }
            }
            
            if (groupIdxs.size() == 0) {
                logger.info("Net " + net.getName() + " with "+ net.getPortInsts().size() +" ports has no incident group");
            }
            if (groupIdxs.size() == 1) {
                logger.info("Net " + net.getName() + " with "+ net.getPortInsts().size() +" ports has only one incident group");
            }
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
        // for (int i = 0; i < edge2GroupIdxMap.size(); i++) {
        //     PartitionEdgeJson partitionEdgeJson = new PartitionEdgeJson();
        //     partitionEdgeJson.id = i;
        //     partitionEdgeJson.primCellNum = 1; // Unused field
        //     partitionEdgeJson.weight = 1; // Default weight
        //     partitionEdgeJson.degree = edge2GroupIdxMap.get(i).size();
        //     partitionEdgeJson.incidentGroupIds = new ArrayList<>(edge2GroupIdxMap.get(i));
        //     partitionEdgeJson.edgeCellNames = new ArrayList<>(); // Unused field
            
        //     partitionEdgeJsons.add(partitionEdgeJson);
        // }
        // partitionResultsJson.partitionEdges = partitionEdgeJsons;

        // Add Constracted Edge Info
        Map<Set<Integer>, List<Integer>> incidentGroup2EdgeIdMap = new HashMap<>();
        for (int i = 0; i < edge2GroupIdxMap.size(); i++) {
            Set<Integer> incidentGroupIds = edge2GroupIdxMap.get(i);
            if (incidentGroup2EdgeIdMap.containsKey(incidentGroupIds)) {
                incidentGroup2EdgeIdMap.get(incidentGroupIds).add(i);
            } else {
                incidentGroup2EdgeIdMap.put(incidentGroupIds, new ArrayList<>(Arrays.asList(i)));
            }
        }

        Integer contractedEdgeNum = 0;
        for (Map.Entry<Set<Integer>, List<Integer>> entry : incidentGroup2EdgeIdMap.entrySet()) {
            PartitionEdgeJson partitionEdgeJson = new PartitionEdgeJson();
            partitionEdgeJson.id = contractedEdgeNum;
            partitionEdgeJson.primCellNum = 1; // Unused field
            partitionEdgeJson.weight = entry.getValue().size(); // Default weight
            partitionEdgeJson.degree = entry.getKey().size();
            partitionEdgeJson.incidentGroupIds = new ArrayList<>(entry.getKey());
            partitionEdgeJson.edgeCellNames = new ArrayList<>(); // Unused field
            partitionEdgeJsons.add(partitionEdgeJson);
            contractedEdgeNum += 1;
        }
        partitionResultsJson.totalEdgeNum = contractedEdgeNum;
        partitionResultsJson.partitionEdges = partitionEdgeJsons;
        logger.info("## Total number of contracted edges: " + contractedEdgeNum);

        // Add Reset and Clock Info
        List<String> resetCellNames = globalResetTreeCellInsts.stream().map(cellInst -> cellInst.getName()).collect(Collectors.toList());
        List<String> resetNetNames = globalResetNets.stream().map(net -> net.getName()).collect(Collectors.toList());
        List<String> clkNetNames = globalClockNets.stream().map(net -> net.getName()).collect(Collectors.toList());
        List<String> ignoreNetNames = ignoreNets.stream().map(net -> net.getName()).collect(Collectors.toList());
        partitionResultsJson.rstPortNames = rstPortNames;
        partitionResultsJson.clkPortNames = clkPortNames;
        partitionResultsJson.resetTreeCellNames = resetCellNames;
        partitionResultsJson.resetNetNames = resetNetNames;
        partitionResultsJson.clkNetNames = clkNetNames;
        partitionResultsJson.ignoreNetNames = ignoreNetNames;

        for (EDIFNet net : illegalNets) {
            partitionResultsJson.ignoreNetNames.add(net.getName());
        }
        
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

    public void writeReducedFanoutNetlistDCP(String dcpOutputPath) {
        reduceRegisterFanout(500, 100);
        Design reducedFanoutDesign = new Design(flatNetlist.getName(), originDesign.getPartName());
        reducedFanoutDesign.setNetlist(flatNetlist);
        reducedFanoutDesign.setAutoIOBuffers(false);
        reducedFanoutDesign.writeCheckpoint(dcpOutputPath);
    }

    public static void main(String[] args) {
        
        // String designName = "blue-udp-direct-rst-ooc";
        // String resetPortName = "udp_reset";
        // String clockPortName = "udp_clk";
        // String designName = "gnl_mid_origin";
        // HashMap<String, List<Integer>> ioConstrs = IOConstraints.gnlMidConstraints;
        // String resetPortName = null;
        // String clockPortName = "clk";

        // String designName = "fft-16";
        // Boolean isFlat = false;
        // HashMap<String, List<Integer>> ioConstrs = IOConstraints.fftConstraints;
        // String resetPortName = "i_reset";
        // String clockPortName = "i_clk";
        // List<String> ignoreNets = Arrays.asList("i_ce");

        // String designName = "ispd16-fpga01";
        // Boolean isFlat = true;
        // String resetPortName = null;
        // String clockPortName = "clk1";
        // List<String> ignoreNets = new ArrayList<>();
        // Path outputPath = Paths.get("./results", designName);

        // String designName = "ispd16-fpga01-ooc-rh";
        // Boolean isFlat = true;
        // String resetPortName = null;
        // String clockPortName = "clk1";
        // List<String> ignoreNets = new ArrayList<>(Arrays.asList("ip_151", "ip_150"));
        // Path outputPath = Paths.get("./results", designName);

        // String designName = "blue-rdma-direct-rst-ooc";
        // // List<String> clockPortNames = new ArrayList<>(Arrays.asList("CLK_udpClock", "CLK_dmacClock", "CLK"));
        // // List<String> resetPortNames = new ArrayList<>(Arrays.asList("RST_N_udpReset", "RST_N_dmacReset", "RST_N"));
        // String clockPortName = "CLK";
        // String resetPortName = "RST_N";
        // Boolean isFlat = false;
        // List<String> ignoreNets = new ArrayList<>();
        // Path outputPath = Paths.get("./results", designName);

        // String designName = "blue-rdma-direct-rst-ooc-flat2";
        // String clkPortNames = "CLK";
        // String rstPortNames = "RST_N";
        // Boolean isFlat = true;
        // List<String> ignoreNets = new ArrayList<>();
        // Path outputPath = Paths.get("./results", designName);

        String designName = "blue-rdma-direct-rst-ooc-flat2";
        String clkPortNames = "CLK";
        String rstPortNames = "RST_N";
        Boolean isFlat = true;
        List<String> ignoreNets = new ArrayList<>();
        Path outputPath = Paths.get("./results", designName);

        // String designName = "nax_riscv_ooc";
        // String clockPortName = "clk";
        // String resetPortName = "reset";
        // Boolean isFlat = true;
        // List<String> ignoreNets = new ArrayList<>();

        // Path outputPath = Paths.get("./results", designName);

        // String designName = "boom";
        // Boolean isFlat = false;
        // String resetPortName = null;
        // String clockPortName = null;
        // List<String> ignoreNets = new ArrayList<>();
        // Path outputPath = Paths.get("./results", designName);

        // String designName = "nvdla-ooc";
        // List<String> clkPortNames = Arrays.asList("core_clk", "csb_clk");
        // // String resetPortName = "reset";
        // List<String> rstPortNames = Arrays.asList("rstn", "csb_rstn");
        // Boolean isFlat = true;
        // List<String> ignoreNets = Arrays.asList("nvdla_top/u_partition_o/u_NV_NVDLA_cdp/u_dp/u_NV_NVDLA_CDP_DP_intp/i___94_n_0", "arb_weight[3]_i_2_n_0");

        // Path outputPath = Paths.get("./results", designName);

        try {
            Files.createDirectories(outputPath);
        } catch (IOException e) {
            System.out.println("Fail to Create Directory: " + e.getMessage());
        }

        String designDcpPath = Paths.get("./benchmarks", designName + ".dcp").toString();
        String logFilePath = Paths.get(outputPath.toString(), designName + ".log").toString();
        
        Logger logger = Logger.getLogger(designName);
        logger.setUseParentHandlers(false);
        // Setup Logger
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
        logger.setLevel(Level.INFO);

        NetlistHandler netlistHandler = new NetlistHandler(designDcpPath, isFlat, clkPortNames, rstPortNames, ignoreNets, logger);
        netlistHandler.printFlatNetlistInfo();
        netlistHandler.printToplevelPorts();
        //netlistHandler.printUndrivenNets();
        netlistHandler.printAbstractGroupsInfo();
        netlistHandler.printAbstractEdgesInfo();

        String netlistJsonPath = Paths.get(outputPath.toString(), designName + ".json").toString();
        netlistHandler.writeProcessedNetlistJson(netlistJsonPath, null);

        String flatDcpPath = Paths.get(outputPath.toString(), designName + "-flat.dcp").toString();
        //netlistHandler.removeIOInsts();
        //netlistHandler.removeHighFanoutNets(2000);
        //netlistHandler.writeFlatNetlistDCP(flatDcpPath);

        // String reducedFanoutDcpPath = Paths.get(outputPath.toString(), designName + "-rep.dcp").toString();
        // netlistHandler.writeReducedFanoutNetlistDCP(reducedFanoutDcpPath);
    }

}
