package com.xilinx.rapidwright.rapidpnr;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;


import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class NetlistDatabase {

    HierarchicalLogger logger;

    Device targetDevice;
    String partName;
    EDIFNetlist originNetlist;
    EDIFCell originTopCell;

    // Original Netlist Info
    //// Reset & Clock
    public Set<EDIFPort> clkPorts;
    public Set<EDIFPort> resetPorts;
    public Set<EDIFNet> globalClockNets;
    public Set<EDIFNet> globalResetNets;
    public Set<EDIFCellInst> globalResetTreeCellInsts;
    public Set<EDIFNet> ignoreNets;
    public Set<EDIFNet> illegalNets;
    public Set<EDIFCellInst> staticSourceCellInsts;

    //// Resource Utils
    public int netlistUnisimCellNum;
    public int netlistLeafCellNum;
    public Map<EDIFCell, Integer> netlistLeafCellUtilMap;


    // Clustered Netlist Info
    public List<Set<EDIFCellInst>> group2CellInst;
    public Map<EDIFCellInst, Integer> cellInst2GroupIdMap;

    public List<Set<Integer>> edge2GroupIds;
    public List<Set<Integer>> group2EdgeIds;

    public List<Map<EDIFCell, Integer>> group2LeafCellUtils;
    public List<Integer> group2LeafCellNum;

    public NetlistDatabase(HierarchicalLogger logger, Design design, DesignParams params) {

        this.logger = logger;
        this.originNetlist = design.getNetlist();
        this.originTopCell = originNetlist.getTopCell();
        this.targetDevice = design.getDevice();
        this.partName = design.getPartName();

        this.ignoreNets = new HashSet<>();
        for (String ignoreNetName : params.getIgnoreNetNames()) {
            EDIFNet ignoreNet = originTopCell.getNet(ignoreNetName);
            assert ignoreNet != null : "Ignore net not found: " + ignoreNetName;
            ignoreNets.add(ignoreNet);
        }

        this.clkPorts = new HashSet<>();
        for (String clkPortName : params.getClkPortNames()) {
            EDIFPort clkPort = originTopCell.getPort(clkPortName);
            assert clkPort != null : "Clock port not found: " + clkPortName;
            clkPorts.add(clkPort);
        }

        this.resetPorts = new HashSet<>();
        for (String resetPortName : params.getResetPortNames()) {
            EDIFPort resetPort = originTopCell.getPort(resetPortName);
            assert resetPort != null : "Reset port not found: " + resetPortName;
            resetPorts.add(resetPort);
        }

        logger.info("Start building netlist database:");
        logger.newSubStep();

        traverseGlobalClockNetwork(params.getClkPortNames());
        traverseGlobalResetNetwork(params.getResetPortNames());
        filterIllegalNets();
        filterStaticSourceCellInsts();
        collectResourceUtilInfo();

        logger.endSubStep();
        logger.info("Complete building netlist database");
    }

    private void traverseGlobalClockNetwork(List<String> clkPortNames) {
        // TODO: only applicable under ooc mode
        logger.info("Start Traversing Global Clock Network:");
        globalClockNets = new HashSet<>();

        for (String clkPortName : clkPortNames) {
            EDIFNet clkNet = originTopCell.getNet(clkPortName);
            assert clkNet != null: "Invalid Clock Port Name: " + clkPortName;
            globalClockNets.add(clkNet);   
        }

        logger.info("Complete Traversing Global Clock Network");
    }

    
    private void traverseGlobalResetNetwork(List<String> rstPortNames) {
        logger.info("Start Traversing Global Reset Network:");
        logger.newSubStep();

        globalResetNets = new HashSet<>();
        globalResetTreeCellInsts = new HashSet<>();

        String resetPortName = rstPortNames.get(0); // TODO: only support single reset port
        if (resetPortName == null) return;

        List<EDIFCellInst> nonRegResetSinkInsts = new ArrayList<>();
        List<EDIFCellInst> nonRegLutResetSinkInsts = new ArrayList<>();

        EDIFNet originResetNet = originTopCell.getNet(resetPortName);
        assert originResetNet != null: "Invalid Reset Port Name: " + resetPortName;

        Queue<EDIFCellInst> searchRstInsts = new LinkedList<>();

        while (!searchRstInsts.isEmpty() || !globalResetNets.contains(originResetNet)) {
            EDIFNet fanoutRstNet;
            if (searchRstInsts.isEmpty()) {
                fanoutRstNet = originResetNet;
                logger.info("Toplevel Reset Port "+ resetPortName + " -> " + originResetNet.getName() + ":");
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
                    "Invalid Reset Port Name: " + portName;
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
                    logger.info("    LUT Reset Signal Fanout: " + (incidentCellInstOutPort.getNet().getPortInsts().size() - 1));
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

        logger.info("Global Reset Signal Bridges CellInsts: ");
        for (EDIFCellInst cellInst : globalResetTreeCellInsts) {
            logger.info("    " + cellInst.getName() + "(" + cellInst.getCellName() + ")");
        }
        logger.info("Global Reset Signal Nets: ");
        for (EDIFNet net : globalResetNets) {
            logger.info("    " + net.getName());
        }
        logger.info("Non-Register Reset Sink Cell Insts: ");
        for (EDIFCellInst cellInst : nonRegResetSinkInsts) {
            logger.info("    " + cellInst.getName() + "( " + cellInst.getCellName() + " )");
        }
        logger.info("Non-Register LUT-Reset Cell Insts: ");
        for (EDIFCellInst cellInst : nonRegLutResetSinkInsts) {
            logger.info("    " + cellInst.getName() + "( " + cellInst.getCellName() + " )");
        }

        logger.endSubStep();
        logger.info("Complete Traversing Global Reset Network");
    }

    private void filterIllegalNets() {
        // filter nets without incident ports or source cell
        logger.info("Start filtering illegal nets(without incident cells or source cell):");
        logger.newSubStep();

        illegalNets = new HashSet<>();
        int emptyNetsNum = 0;
        int undrivenNetsNum = 0;
        int noneSinkNetsNum = 0;

        for (EDIFNet net : originTopCell.getNets()) {
            int incidentPortNum = net.getPortInsts().size();
            if (incidentPortNum == 0) {
                illegalNets.add(net);
                emptyNetsNum++;
            } else {
                List<EDIFPortInst> srcPortInsts = net.getSourcePortInsts(true);
                assert srcPortInsts.size() <= 1: String.format("The number of source ports of net %s: %d", net.getName(), srcPortInsts.size());
                if (srcPortInsts.size() == 0) {
                    illegalNets.add(net);
                    logger.info("Undriven Net: " + net.getName());
                    undrivenNetsNum++;
                } else if (incidentPortNum == 1) {
                    illegalNets.add(net);
                    //logger.info("None-Sink Net: " + net.getName());
                    noneSinkNetsNum++;
                }
            }
        }

        assert undrivenNetsNum == 0: "Undriven Nets Exist!";
        logger.info("Total number of illegal nets: " + illegalNets.size());
        logger.info("Number of empty nets: " + emptyNetsNum);
        logger.info("Number of undriven nets: " + undrivenNetsNum);
        logger.info("Number of nets without sink: " + noneSinkNetsNum);

        logger.endSubStep();
        logger.info("Complete filtering illegal nets successfully");
    }

    private void filterStaticSourceCellInsts() {
        logger.info("Start filtering static-source cell instances:");
        logger.newSubStep();

        staticSourceCellInsts = new HashSet<>();
        for (EDIFCellInst cellInst : originTopCell.getCellInsts()) {
            if (cellInst.getCellType().isStaticSource()) {
                staticSourceCellInsts.add(cellInst);
                logger.info("Find static-source cell instance: " + cellInst.getName());
            }
        }

        logger.info("Total number of static-source cell instances: " + staticSourceCellInsts.size());

        logger.endSubStep();
        logger.info("Complete filtering static-source cell instances");
    }

    private void collectResourceUtilInfo() {
        logger.info("Start collecting resource utilization info");
        
        netlistUnisimCellNum = originTopCell.getCellInsts().size(); // TODO: only applicable for flat netlist
        netlistLeafCellUtilMap = new HashMap<>();
        NetlistUtils.getLeafCellUtils(originTopCell, netlistLeafCellUtilMap);
        netlistLeafCellNum = netlistLeafCellUtilMap.values().stream().mapToInt(Integer::intValue).sum();

        logger.info("Complete collecting resource utilization info");
    }

    //
    public Device getTargetDevice() {
        return targetDevice;
    }

    public boolean isGlobalClockNet(EDIFNet net) {
        return globalClockNets.contains(net);
    }

    public boolean isGlobalResetNet(EDIFNet net) {
        return globalResetNets.contains(net);
    }

    public boolean isIgnoreNet(EDIFNet net) {
        return ignoreNets.contains(net);
    }

    public boolean isIllegalNet(EDIFNet net) {
        return illegalNets.contains(net);
    }

    public boolean isStaticSourceCellInst(EDIFCellInst cellInst) {
        return staticSourceCellInsts.contains(cellInst);
    }

    public void printToplevelPort() {
        logger.info("Top-level ports of original design: ");
        logger.newSubStep();

        for (EDIFPort port : originTopCell.getPorts()) {
            logger.info(port.getName() + ": " + port.getDirection() + " " + port.getWidth());
        }

        logger.endSubStep();
    }

    public void printCellLibraryInfo() {
        logger.info("Cell Library Info:");
        logger.newSubStep();

        for (Map.Entry<String, EDIFLibrary> entry : originNetlist.getLibrariesMap().entrySet()) {
            logger.info("Library " + entry.getKey() + ":");
            
            logger.newSubStep();
            for (EDIFCell cell : entry.getValue().getCells()) {
                if (cell == originNetlist.getTopCell()) continue;

                String cellTypeInfo = cell.getName();
                cellTypeInfo += " isleaf: " + cell.isLeafCellOrBlackBox();
                cellTypeInfo += " isprim: " + cell.isPrimitive();
                if (!cell.isLeafCellOrBlackBox()) {
                    Map<EDIFCell, Integer> leafCellUtilMap = new HashMap<>();
                    NetlistUtils.getLeafCellUtils(cell, leafCellUtilMap);
                    cellTypeInfo += " (";
                    for (Map.Entry<EDIFCell, Integer> leafCellEntry : leafCellUtilMap.entrySet()) {
                        cellTypeInfo += leafCellEntry.getKey().getName() + ":" + leafCellEntry.getValue();
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
            logger.endSubStep();

        }
        logger.endSubStep();
    }

    public void printUnisimCellUtils() {
        logger.info("Unisim/Blackbox Cell Utilization:");
        logger.newSubStep();
        logger.info("Total number of Unisim/Blackbox cells: " + netlistUnisimCellNum);

        Map<EDIFCell, Integer> cell2AmountMap = new HashMap<>();
        for (EDIFCellInst cellInst : originTopCell.getCellInsts()) {
            EDIFCell cellType = cellInst.getCellType();
            if (cell2AmountMap.containsKey(cellType)) {
                Integer amount = cell2AmountMap.get(cellType);
                cell2AmountMap.replace(cellType, amount + 1);
            } else {
                cell2AmountMap.put(cellType, 1);
            }
        }

        logger.info("Unisim/Blackbox Cell Distribution:");
        logger.newSubStep();
        for (Map.Entry<EDIFCell, Integer> entry : cell2AmountMap.entrySet()) {
            float ratio = (float) entry.getValue() / netlistUnisimCellNum * 100;
            logger.info(String.format("%s: %d (%.2f%%)", entry.getKey().getName(), entry.getValue(), ratio));
        }
        logger.endSubStep();

        logger.endSubStep();
    }

    public void printLeafCellUtils() {
        logger.info("Leaf(Primitve/Blackbox) Cell Utilization:");
        logger.newSubStep();
        
        logger.info("Total number of Primitive/Leaf cells: " + netlistLeafCellNum);
        logger.info("Primitive/Leaf Cell Distribution:");
        logger.newSubStep();
        for (Map.Entry<EDIFCell, Integer> entry : netlistLeafCellUtilMap.entrySet()) {
            float ratio = (float) entry.getValue() / netlistLeafCellNum * 100;
            logger.info(String.format("%s: %d (%.2f%%)", entry.getKey().getName(), entry.getValue(), ratio));
        }
        logger.endSubStep();

        logger.endSubStep();
    }

    public void printResourceTypeUtils() {
        logger.info("Resource Type Distribution:");
        Map<String, Integer> resTypeUtil = NetlistUtils.getResTypeUtils(netlistLeafCellUtilMap);

        logger.newSubStep();
        for (Map.Entry<String, Integer> entry : resTypeUtil.entrySet()) {
            float ratio = (float) entry.getValue() / netlistLeafCellNum * 100;
            logger.info(String.format("%s: %d (%.2f%%)", entry.getKey(), entry.getValue(), ratio));
        }
        logger.endSubStep();
    }

    public void printConnectionInfo() {
        logger.info("Netlist Connection Info:");

        Integer totalNetAmount = originTopCell.getNets().size();
        Integer vccGndNetCount = 0;
        Map<EDIFNet, Integer> net2DegreeMap = new HashMap<>();
        Map<Integer, Integer> degree2NetAmountMap = new HashMap<>();

        for (EDIFNet net : originTopCell.getNets()) {
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
        
        logger.newSubStep();
        
        logger.info("Total number of nets: " + totalNetAmount);
        logger.info("Number of VCC&GND nets: " + vccGndNetCount);
        logger.info("Number of illegal nets: " + illegalNets.size());
        logger.info("Number of other nets: " + net2DegreeMap.size());
        logger.info("Top 50 Fanout Nets:");
        logger.newSubStep();
        for (int i = 0; i < 50; i++) {
            EDIFNet net = sortedNet2DegreeMap.get(i).getKey();
            Integer fanoutNum = sortedNet2DegreeMap.get(i).getValue();
            logger.info(net.getName() + ": " + fanoutNum);
        }
        logger.endSubStep();

        logger.info("Net Degree Distribution:");
        List<Map.Entry<Integer, Integer>> sortedDegree2AmountMap = degree2NetAmountMap.entrySet()
            .stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByKey())
            .collect(Collectors.toList());

        logger.newSubStep();
        for (Map.Entry<Integer, Integer> entry : sortedDegree2AmountMap) {
            float ratio = (float) entry.getValue() / totalNetAmount * 100;
            logger.info(String.format("Degree from %d to %d: %d (%.2f%%)", entry.getKey(), entry.getKey() + 50, entry.getValue(), ratio));
        }
        logger.endSubStep();

        logger.endSubStep();
    }



    public void printNetlistInfo() {
        // print information of given flat netlist (consisting of only UniSim/Blackbox cells)
        logger.info("Netlist Information: ");
        logger.newSubStep();

        printToplevelPort();

        printUnisimCellUtils();

        printLeafCellUtils();

        printResourceTypeUtils();

        printConnectionInfo();

        logger.endSubStep();
    }

}
