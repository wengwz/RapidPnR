package com.xilinx.rapidwright.examples;

import java.io.FileWriter;
import java.io.IOException;
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
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.python.antlr.ast.While;

import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.BooleanArraySerializer;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;


public class CircuitPartioner {

    public static final HashSet<String> regCellTypeNames = new HashSet<>(Arrays.asList("FDSE", "FDRE", "FDCE"));
    public static final HashSet<String> lutCellTypeNames = new HashSet<>(Arrays.asList("LUT1", "LUT2", "LUT3", "LUT4", "LUT5", "LUT6"));
    public static final HashSet<String> srlCellTypeNames = new HashSet<>(Arrays.asList("SRL16E", "SRLC32E"));
    
    //
    private String partName;
    private EDIFNetlist hierNetlist;
    private EDIFNetlist flatNetlist;
    private Logger logger;

    // Partition Resutls
    private List<List<EDIFCellInst>> partitionEdges;
    private List<List<EDIFCellInst>> partitionGroups;
    private Map<EDIFCellInst, Integer> cellInst2EdgeIdxMap;
    private Map<EDIFCellInst, Integer> cellInst2GroupIdxMap;

    Integer totalEdgeCellInstNum = 0;
    Integer totalGroupCellInstNum = 0;
    Integer totalGroupNonFuncCellInstNum = 0;

    // Connection Info
    private List<Set<Integer>> edge2SinkGroupsIdxMap;
    private List<Set<Integer>> edge2SourceGroupIdxMap;
    private List<List<Integer>> group2EdgesIdxMap;

    // Global Reset Info
    private Set<EDIFCellInst> globalResetTreeCellInsts;
    private Set<EDIFNet> globalResetNets;
    private Set<EDIFNet> constantVccGndNets;

    public CircuitPartioner(EDIFNetlist hierNetlist, String partName, String rstSrcInstName, Logger logger) {
        this.hierNetlist = hierNetlist;
        this.partName = partName;
        this.logger = logger;
        flatNetlist = EDIFTools.createFlatNetlist(hierNetlist, partName);

        //Part part = PartNameTools.getPart(partName);
        //flatNetlist.collapseMacroUnisims(part.getSeries());

        EDIFCellInst rstSourceCellInst = flatNetlist.getCellInstFromHierName(rstSrcInstName);
        assert rstSourceCellInst != null: "Invalid Reset Source Instance Name: " + rstSrcInstName;
        traverseGlobalResetNetwork(rstSourceCellInst);
        traverseConstantVccGndNetwork();
        //traverseGlobalClockNetwork();
        buildPartitionGroups();
        buildPartitionEdges();
    }

    public void printHierNetlistInfo() {
        logger.info("# Origianl Hierarchy Netlist Information:");

        Map<EDIFCell, Integer> leafCell2AmountMap = new HashMap<>();
        logger.info("## Cell Library Info:");
        for (Map.Entry<String, EDIFLibrary> entry : hierNetlist.getLibrariesMap().entrySet()) {
            logger.info("### " + entry.getKey() + ":");
            
            for (EDIFCell cell : entry.getValue().getCells()) {
                String cellTypeInfo = "    " + cell.getName();
                cellTypeInfo += " isleaf: " + cell.isLeafCellOrBlackBox();
                cellTypeInfo += " isprim: " + cell.isPrimitive();
                logger.info(cellTypeInfo);

                if (cell.isLeafCellOrBlackBox()) {
                    String cellPortsInfo = "      ports: ";
                    for (EDIFPort port : cell.getPorts()) {
                        cellPortsInfo += port.getName() + " ";
                    }
                    logger.info(cellPortsInfo);
                    //
                    leafCell2AmountMap.put(cell, 0);
                }
            }
        }

        logger.info("");
        logger.info("## Resource Utilization:");
        Integer regHierCellInstAmount = 0;
        Integer vccGndHierCellInstAmount = 0;
        Integer otherHierCellInstAmount = 0;
        List<EDIFHierCellInst> allLeafHierCellInsts = hierNetlist.getAllLeafHierCellInstances(true);
        for (EDIFHierCellInst hierCellInst : allLeafHierCellInsts) {
            EDIFCell edifCell = hierCellInst.getCellType();
            assert leafCell2AmountMap.containsKey(edifCell);
            if (leafCell2AmountMap.containsKey(edifCell)) {
                Integer cellAmount = leafCell2AmountMap.get(edifCell);
                leafCell2AmountMap.replace(edifCell, cellAmount + 1);
            } else {
                logger.severe("Primitive Cell Library doesn't conation " + edifCell.getName());
            }

            if (edifCell.isStaticSource()) {
                vccGndHierCellInstAmount += 1;
            } else if (regCellTypeNames.contains(edifCell.getName())) {
                regHierCellInstAmount += 1;
            } else {
                otherHierCellInstAmount += 1;
            }
        }
        logger.info("### Top Cell Name: " + hierNetlist.getTopCell().getName());
        logger.info("### Total number of leaf cells: " + allLeafHierCellInsts.size());
        logger.info("### Total number of register cells: " + regHierCellInstAmount);
        logger.info("### Total number of VCC&GND cells: " + vccGndHierCellInstAmount);
        logger.info("### Total number of ohter cells: " + otherHierCellInstAmount);
        logger.info("### Utilization of Each Leaf/Blackbox Cell: ");
        for (Map.Entry<EDIFCell, Integer> entry : leafCell2AmountMap.entrySet()) {
            logger.info("    " + entry.getKey().getName() + ": " + entry.getValue());
        }

        logger.info("");
        logger.info("## Connection Information:");
        Map<EDIFHierNet, List<EDIFHierPortInst>> hierNet2HierPortMap = hierNetlist.getPhysicalNetPinMap();
        Map<EDIFHierNet, Integer> hierNet2FanoutMap = new HashMap<>();
        Integer vccGndNetAmount = 0;
        for (Map.Entry<EDIFHierNet, List<EDIFHierPortInst>> entry: hierNet2HierPortMap.entrySet()) {
            EDIFHierNet hierNet = entry.getKey();
            if (hierNet.getNet().isGND() || hierNet.getNet().isVCC()) {
                vccGndNetAmount += 1;
            } else {
                hierNet2FanoutMap.put(entry.getKey(), entry.getValue().size());
            }
        }
        List<Map.Entry<EDIFHierNet, Integer>> sortedHierNets = hierNet2FanoutMap.entrySet()
            .stream()
            .sorted(Map.Entry.<EDIFHierNet, Integer>comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());

        logger.info("### Number of VCC&GND nets: " + vccGndNetAmount);
        logger.info("### Number of other nets: " + hierNet2FanoutMap.size());
        logger.info("### Top 20 Fanout Nets:");
        for (int i = 0; i < 20; i++) {
            EDIFHierNet hierNet = sortedHierNets.get(i).getKey();
            Integer fanoutNum = sortedHierNets.get(i).getValue();
            logger.info("    " + hierNet.getHierarchicalNetName() + ": " + fanoutNum);
        }
    }

    public void printFlatNetlistInfo() {
        logger.info("");
        logger.info("# Flattened Netlist Information:");
        logger.info("## Cell Library Info:");
        Map<EDIFCell, Integer> edifCell2AmountMap = new HashMap<>();
        for (Map.Entry<String, EDIFLibrary> entry : flatNetlist.getLibrariesMap().entrySet()) {
            logger.info("### " + entry.getKey() + ":");
            
            for (EDIFCell cell : entry.getValue().getCells()) {
                if (cell == flatNetlist.getTopCell()) continue;
                String cellTypeInfo = "    " + cell.getName();
                cellTypeInfo += " isleaf: " + cell.isLeafCellOrBlackBox();
                cellTypeInfo += " isprim: " + cell.isPrimitive();
                logger.info(cellTypeInfo);
                edifCell2AmountMap.put(cell, 0);
            }
        }

        logger.info("");
        logger.info("## Resource Utilization: ");
        Collection<EDIFCellInst> allCellInsts = flatNetlist.getTopCell().getCellInsts();
        Integer nonLeafCellInstAmount = 0;
        Integer leafCellInstAmount = 0;
        Integer regCellInstAmount = 0;
        Integer vccGndCellInstAmount = 0;
        Integer otherCellInstAmount = 0;
        for (EDIFCellInst cellInst : allCellInsts) {
            EDIFCell cellType = cellInst.getCellType();
            assert edifCell2AmountMap.containsKey(cellType);
            Integer cellAmount = edifCell2AmountMap.get(cellType);
            edifCell2AmountMap.replace(cellType, cellAmount + 1);
            
            if (cellType.isLeafCellOrBlackBox()) {
                leafCellInstAmount += 1;
                if (regCellTypeNames.contains(cellType.getName())) {
                    regCellInstAmount += 1;
                } else if (cellType.isStaticSource()) {
                    vccGndCellInstAmount += 1;
                } else {
                    otherCellInstAmount += 1;
                }
            } else {
                nonLeafCellInstAmount += 1;
            }
        }
        logger.info("### Total Number of Cells: " +  allCellInsts.size());
        logger.info("### Non-Leaf Cell Amount: " + nonLeafCellInstAmount);
        logger.info("### Leaf Cell Amount: " + leafCellInstAmount);
        logger.info("    Register Cell Amount: " + regCellInstAmount);
        logger.info("    VCC&GND Cell Amount: " + vccGndCellInstAmount);
        logger.info("    Other Cell Amount: " + otherCellInstAmount);

        logger.info("### Utilizaiton of Each HDI Primitive:");
        for (Map.Entry<EDIFCell, Integer> entry : edifCell2AmountMap.entrySet()) {
            logger.info("    " + entry.getKey() + ": " + entry.getValue());
        }

        logger.info("");
        logger.info("## Connection Information:");
        Collection<EDIFNet> allNets = flatNetlist.getTopCell().getNets();
        Map<EDIFNet, Integer> net2FanoutMap = new HashMap<>();
        Integer vccGndNetAmount = 0;
        for (EDIFNet edifNet : allNets) {
            if (edifNet.isGND() || edifNet.isVCC()) {
                vccGndNetAmount += 1;
            } else {
                net2FanoutMap.put(edifNet, edifNet.getPortInsts().size());
            }
        }
        List<Map.Entry<EDIFNet, Integer>> sortedNets = net2FanoutMap.entrySet()
            .stream()
            .sorted(Map.Entry.<EDIFNet, Integer>comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());

        logger.info("### Number of VCC&GND nets: " + vccGndNetAmount);
        logger.info("### Number of other nets: " + net2FanoutMap.size());
        logger.info("### Top 20 Fanout Nets:");
        for (int i = 0; i < 20; i++) {
            EDIFNet hierNet = sortedNets.get(i).getKey();
            Integer fanoutNum = sortedNets.get(i).getValue();
            logger.info("    " + hierNet.getName() + ": " + fanoutNum);
        }
    }

    private void buildPartitionGroups() {
        partitionGroups = new ArrayList<>();
        cellInst2GroupIdxMap = new HashMap<>();
        group2EdgesIdxMap = new ArrayList<>();

        logger.info("# Start Building Partition Groups");
        Map<EDIFNet, Boolean> netVisitedTags = new HashMap<>();
        Map<EDIFCellInst, Boolean> cellInstVisitedTags = new HashMap<>();
        
        // Initialize net visited tags
        for (EDIFNet net : flatNetlist.getTopCell().getNets()) {
            // Remove VCC, GND and Global Reset Nets
            Boolean netVisitedTag = constantVccGndNets.contains(net) || globalResetNets.contains(net);
            if (netVisitedTag) {
                logger.info("Constant Net " + net.getName() + " is visited");
            }
            netVisitedTags.put(net, netVisitedTag);
        }

        //Set<EDIFNet> multiSinkRegInputNets = new HashSet<>();
        // Initialize inst visited tags
        for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
            cellInstVisitedTags.put(cellInst, !isFunctionalCellInst(cellInst));

            if (isPartitionEdgeCellInst(cellInst)) {
                // for (EDIFPortInst portInst : cellInst.getPortInsts()) {
                //     EDIFNet incidentNet = portInst.getNet();
                //     netVisitedTags.replace(incidentNet, true);
                // }

                for (EDIFPortInst portInst : cellInst.getPortInsts()) {
                    // if (portInst.isOutput()) {
                    //     EDIFNet incidentNet = portInst.getNet();
                    //     netVisitedTags.replace(incidentNet, true);
                    // }
                    if (portInst.isOutput() || isClockPortInst(portInst)) {
                        EDIFNet incidentNet = portInst.getNet();
                        netVisitedTags.replace(incidentNet, true);
                    }
                }
            }
        }
        // logger.info("# Mutli-Sink Register Input Nets:");
        // for (EDIFNet net : multiSinkRegInputNets) {
        //     logger.info("  " + net.getName());
        //     for (EDIFPortInst portInst : net.getPortInsts()) {
        //         logger.info("    " + portInst.getCellInst().getName() + "("+ portInst.getCellInst().getCellName() + ")" + ": " + portInst.getName());
        //     }
        // }

        // Breadth-First-Search Based Cell Clustering
        logger.info("## Start breadth-first-search Partition");
        for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
            assert cellInstVisitedTags.containsKey(cellInst);
            if (!cellInstVisitedTags.get(cellInst)) {
                Integer groupIdx = partitionGroups.size();
                List<EDIFCellInst> partitionGroup = new ArrayList<>();
                Queue<EDIFCellInst> searchQueue = new LinkedList<>();
                
                logger.info("Start Expand Group " + groupIdx);

                searchQueue.add(cellInst);
                partitionGroup.add(cellInst);
                assert !cellInst2GroupIdxMap.containsKey(cellInst);
                cellInst2GroupIdxMap.put(cellInst, groupIdx);
                cellInstVisitedTags.replace(cellInst, true);

                logger.info("Add Seed CellInst: " + cellInst.getName());

                while (!searchQueue.isEmpty()) {
                    EDIFCellInst searchCellInst = searchQueue.poll();
                    logger.info("Expand CellInst: " + searchCellInst.getName() + ": " + searchCellInst.getCellName());
                    for (EDIFPortInst searchPortInst : searchCellInst.getPortInsts()) {
                        EDIFNet searchNet = searchPortInst.getNet();

                        assert netVisitedTags.containsKey(searchNet);
                        if (!netVisitedTags.get(searchNet)) {
                            logger.info(" Expand Net: " + searchNet.getName() + " from Port " + searchPortInst.getName());

                            Collection<EDIFPortInst> expandPortInsts = searchNet.getPortInsts();
                            for (EDIFPortInst portInst : expandPortInsts) {
                                EDIFCellInst expandCellInst = portInst.getCellInst();
                                if (expandCellInst == null) continue; // Top-Level Ports

                                assert cellInstVisitedTags.containsKey(expandCellInst): expandCellInst.getName() + ": " + expandCellInst.getCellName();
                                if (!cellInstVisitedTags.get(expandCellInst)) {
                                    logger.info("  Reach CellInst: " + expandCellInst.getName() + ": " + expandCellInst.getCellName());
                                    searchQueue.add(expandCellInst);
                                    partitionGroup.add(expandCellInst);
                                    cellInst2GroupIdxMap.put(expandCellInst, groupIdx);
                                    cellInstVisitedTags.replace(expandCellInst, true);
                                }
                            }
                            netVisitedTags.put(searchNet, true);
                        }
                    }
                }
                partitionGroups.add(partitionGroup);
                group2EdgesIdxMap.add(new ArrayList<>());
                totalGroupCellInstNum += partitionGroup.size();
            }
        }
    }

    private void buildPartitionEdges() {
        partitionEdges = new ArrayList<>();
        cellInst2EdgeIdxMap = new HashMap<>();
        edge2SinkGroupsIdxMap = new ArrayList<>();
        edge2SourceGroupIdxMap = new ArrayList<>();

        logger.info("# Start Building Partition Edges");
        Integer totalSearchedEdgeNum = 0;
        Integer intraGroupEdgeNum = 0;
        Integer interGroupEdgeNum = 0;

        Set<EDIFCellInst> visitedPartitionCellInsts = new HashSet<>();
        Set<EDIFNet> visitedNets = new HashSet<>();
        visitedNets.addAll(constantVccGndNets);
        visitedNets.addAll(globalResetNets);
        for (EDIFNet net : visitedNets) {
            logger.info("# Visited Net: " + net.getName());
        }

        for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
            if (!isPartitionEdgeCellInst(cellInst)) continue;

            if (!visitedPartitionCellInsts.contains(cellInst)) {
                Integer edgeIdx = partitionEdges.size();

                List<EDIFCellInst> edgeCellInsts = new ArrayList<>();
                Set<Integer> sinkGroupIdxSet = new HashSet<>();
                Set<Integer> sourceGroupIdxSet = new HashSet<>();

                Queue<EDIFCellInst> searchQueue = new LinkedList<>();
                searchQueue.add(cellInst);
                edgeCellInsts.add(cellInst);
                //cellInst2EdgeIdxMap.put(cellInst, edgeIdx);
                visitedPartitionCellInsts.add(cellInst);

                logger.info("# Start Expanding Edge Group " + edgeIdx);
                while (!searchQueue.isEmpty()) {
                    EDIFCellInst searchCellInst = searchQueue.poll();
                    logger.info("## Expand Edge Cell Inst: " + searchCellInst.getName() + "(" + searchCellInst.getCellName() + ")");

                    for (EDIFPortInst portInst : searchCellInst.getPortInsts()) {
                        if (isClockPortInst(portInst)) continue;
                        EDIFNet searchNet = portInst.getNet();
                        if (visitedNets.contains(searchNet)) continue;

                        logger.info("    Expand Net: " + searchNet.getName() + " from Port: " + portInst.getName());
                        
                        for (EDIFPortInst expandPortInst : searchNet.getPortInsts()) {
                            EDIFCellInst expandCellInst = expandPortInst.getCellInst();
                            if (isPartitionEdgeCellInst(expandCellInst)) {
                                logger.info("     Visit Edge Cell Inst: " + expandCellInst.getName() + "(" + expandCellInst.getCellName() + ")");
                                if (visitedPartitionCellInsts.contains(expandCellInst)) continue;
                                
                                edgeCellInsts.add(expandCellInst);
                                visitedPartitionCellInsts.add(expandCellInst);
                                searchQueue.add(expandCellInst);
                            } else {
                                assert isFunctionalCellInst(expandCellInst);
                                assert cellInst2GroupIdxMap.containsKey(expandCellInst);
                                Integer expandCellGroupIdx = cellInst2GroupIdxMap.get(expandCellInst);
                                
                                if (expandPortInst.isInput()) {
                                    sinkGroupIdxSet.add(expandCellGroupIdx);
                                } else {
                                    sourceGroupIdxSet.add(expandCellGroupIdx);
                                }
                            }
                        }
                        visitedNets.add(searchNet);
                    }
                }

                Set<Integer> mergedGroupIdxSet = new HashSet<>(sinkGroupIdxSet);
                mergedGroupIdxSet.addAll(sourceGroupIdxSet);
                totalSearchedEdgeNum += 1;
                if (mergedGroupIdxSet.size() == 1) {
                    intraGroupEdgeNum += 1;
                    Integer groupIdx = mergedGroupIdxSet.iterator().next();
                    partitionGroups.get(groupIdx).addAll(edgeCellInsts);
                    for (EDIFCellInst edgeCellInst : edgeCellInsts) {
                        cellInst2GroupIdxMap.put(edgeCellInst, groupIdx);
                    }
                    totalGroupCellInstNum += edgeCellInsts.size();
                    totalGroupNonFuncCellInstNum += edgeCellInsts.size();
                } else {
                    interGroupEdgeNum += 1;
                    partitionEdges.add(edgeCellInsts);
                    edge2SinkGroupsIdxMap.add(sinkGroupIdxSet);
                    edge2SourceGroupIdxMap.add(sourceGroupIdxSet);

                    for (Integer groupIdx : mergedGroupIdxSet) {
                        group2EdgesIdxMap.get(groupIdx).add(edgeIdx);
                    }
                    for (EDIFCellInst edgeCellInst : edgeCellInsts) {
                        cellInst2EdgeIdxMap.put(edgeCellInst, edgeIdx);
                    }
                    totalEdgeCellInstNum += edgeCellInsts.size();
                }
            }
        }

        logger.info("## Total Number of Searched Partition Edges: " + totalSearchedEdgeNum);
        logger.info("## Number of Intra-Group Edges: " + intraGroupEdgeNum + " " + (float)intraGroupEdgeNum / totalSearchedEdgeNum * 100 + "%");
        logger.info("## Number of Inter-Group Edges: " + interGroupEdgeNum + " " + (float)interGroupEdgeNum / totalSearchedEdgeNum * 100 + "%");
    }

    private void traverseGlobalResetNetwork(EDIFCellInst rstSourceInst) {
        logger.info("# Start Traversing Global Reset Network:");
        List<EDIFCellInst> nonRegResetSinkInsts = new ArrayList<>();
        List<EDIFCellInst> nonRegLutResetSinkInsts = new ArrayList<>();
        globalResetNets = new HashSet<>();
        globalResetTreeCellInsts = new HashSet<>();
        assert rstSourceInst.getCellName().equals("IBUF"): "Global Reset Source Input Buffer: " + rstSourceInst.getCellName();
        
        Queue<EDIFCellInst> searchRstInsts = new LinkedList<>();
        searchRstInsts.add(rstSourceInst);

        while (!searchRstInsts.isEmpty()) {
            EDIFCellInst searchRstInst = searchRstInsts.poll();
            
            List<EDIFPortInst> fanoutPortInsts = getOutputPortsOfCellInst(searchRstInst);
            assert fanoutPortInsts.size() == 1;
            EDIFPortInst fanoutPortInst = fanoutPortInsts.get(0);
            EDIFNet fanoutRstNet = fanoutPortInst.getNet();
            
            assert !globalResetNets.contains(fanoutRstNet);
            globalResetNets.add(fanoutRstNet);

            logger.info("  " + searchRstInst.getName() + ":" + fanoutPortInst.getName() + "->" + fanoutRstNet.getName() + ":");
            for (EDIFPortInst incidentPortInst : getSinkPortsOfNet(fanoutRstNet)) {
                
                EDIFCellInst incidentCellInst = incidentPortInst.getCellInst();
                logger.info("    " + incidentCellInst.getName() + "(" + incidentCellInst.getCellName() + ")" + ": " + incidentPortInst.getName());
                
                //assert isRegisterCellInst(incidentCellInst): "Non-Register Instances on The Reset Tree";
                // Reset Signals may connect to RAMB36E2 and DSP
                //assert isRegisterCellInst(incidentCellInst) || isLutCellInst(incidentCellInst);         
                if (isRegisterCellInst(incidentCellInst)) {
                    assert incidentPortInst.getName().equals("D") || incidentPortInst.getName().equals("S") || incidentPortInst.getName().equals("R");
                    if (incidentPortInst.getName().equals("D")) {
                        searchRstInsts.add(incidentCellInst);
                        assert !globalResetTreeCellInsts.contains(incidentCellInst);
                        globalResetTreeCellInsts.add(incidentCellInst);
                    }
                } else if (isLutOneCellInst(incidentCellInst)) {
                    assert !globalResetTreeCellInsts.contains(incidentCellInst);
                    globalResetTreeCellInsts.add(incidentCellInst);
                    searchRstInsts.add(incidentCellInst);
                } else if (isLutCellInst(incidentCellInst)) {
                    List<EDIFPortInst> incidentCellInstOutPorts = getOutputPortsOfCellInst(incidentCellInst);
                    //assert incidentCellInstOutPorts.size() == 1;
                    EDIFPortInst incidentCellInstOutPort = incidentCellInstOutPorts.get(0);
                    for (EDIFPortInst portInst : getSinkPortsOfNet(incidentCellInstOutPort.getNet())) {
                        if (!isRegisterCellInst(portInst.getCellInst())) {
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

        logger.info("## Global Reset Signal Bridges Registers: ");
        for (EDIFCellInst cellInst : globalResetTreeCellInsts) {
            logger.info("    " + cellInst.getName());
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

    private void traverseGlobalClockNetwork() {
        logger.info("# Start Traversing Global Clock Network:");
        //List<EDIFCellInst> nonRegClockSinkInsts = new ArrayList<>();

        // assert getOutputPortsOfCellInst(clkSourceInst).size() == 1;
        // EDIFPortInst clkPortInst  = getOutputPortsOfCellInst(clkSourceInst).get(0);

        logger.info("## Non-Register Clock Sink Cell Insts: ");
        for (EDIFPortInst sinkPortInst : getSinkPortsOfNet(flatNetlist.getTopCell().getNet("ap_clk_IBUF_BUFG"))) {
            EDIFCellInst sinkCellInst = sinkPortInst.getCellInst();
            if (!(isRegisterCellInst(sinkCellInst) && sinkPortInst.getName().equals("C"))) {
                logger.info("    " + sinkCellInst.getName() + "(" + sinkCellInst.getCellName() + ")" + ": " + sinkPortInst.getName());
            }
        }

    }

    private void traverseConstantVccGndNetwork() {
        logger.info("# Start Traversing Constant VCC&GND Network:");
        constantVccGndNets = new HashSet<>();
        for (EDIFNet net : flatNetlist.getTopCell().getNets()) {
            if (net.isGND() || net.isVCC()) {
                constantVccGndNets.add(net);
            }
        }
        logger.info("## Constant VCC&GND Nets:");
        for (EDIFNet net : constantVccGndNets) {
            logger.info("   " + net.getName());
        }
    }

    private Boolean isRegisterCellInst(EDIFCellInst cellInst) {
        return regCellTypeNames.contains(cellInst.getCellType().getName());
    }
    private Boolean isLutCellInst(EDIFCellInst cellInst) {
        return lutCellTypeNames.contains(cellInst.getCellName());
    }
    private Boolean isLutOneCellInst(EDIFCellInst cellInst) {
        return cellInst.getCellName().equals("LUT1");
    }
    private Boolean isSRLCellInst(EDIFCellInst  cellInst) {
        return srlCellTypeNames.contains(cellInst.getCellType().getName());
    }

    private Boolean isVccGndCellInst(EDIFCellInst cellInst) {
        return cellInst.getCellType().isStaticSource();
    }
    private Boolean isGlobalResetTreeCellInst(EDIFCellInst cellInst) {
        return globalResetTreeCellInsts.contains(cellInst);
    }
    private Boolean isPartitionEdgeCellInst(EDIFCellInst cellInst) {
        return (isRegisterCellInst(cellInst) || isSRLCellInst(cellInst)) && !isGlobalResetTreeCellInst(cellInst);
    }
    private Boolean isFunctionalCellInst(EDIFCellInst cellInst) {
        return !isVccGndCellInst(cellInst) && !isPartitionEdgeCellInst(cellInst) && !isGlobalResetTreeCellInst(cellInst);
    }

    private List<EDIFPortInst> getSinkPortsOfNet(EDIFNet net) {
        List<EDIFPortInst> sinkPortInsts = new ArrayList<>();
        for (EDIFPortInst portInst : net.getPortInsts()) {
            if (portInst.isInput()) {
                sinkPortInsts.add(portInst);
            }
        }
        return sinkPortInsts;
    }
    private List<EDIFPortInst> getOutputPortsOfCellInst(EDIFCellInst cellInst) {
        List<EDIFPortInst> outputPortInsts = new ArrayList<>();
        for (EDIFPortInst portInst : cellInst.getPortInsts()) {
            if (portInst.isOutput()) {
                outputPortInsts.add(portInst);
            }
        }
        return outputPortInsts;
    }

    private boolean isClockPortInst(EDIFPortInst portInst) {
        Boolean isRegisterClkPort = isRegisterCellInst(portInst.getCellInst()) && portInst.getName().equals("C");
        Boolean isSRLClkPort = isSRLCellInst(portInst.getCellInst()) && portInst.getName().equals("CLK");
        return isRegisterClkPort || isSRLClkPort;
    }

    public void printPartitionEdgesInfo() {
        logger.info("# Partition Edge Info:");
        logger.info("## Total Number of Partition Edges: " + partitionEdges.size());

        

        Map<Integer, Integer> size2AmountMap = new HashMap<>();
        Map<Integer, Integer> degree2AmountMap = new HashMap<>();

        size2AmountMap.clear();
        for (int i = 0; i < partitionEdges.size(); i++) {
            List<EDIFCellInst> partitionEdge = partitionEdges.get(i);
            Set<Integer> sinkGroupIdxSet = edge2SinkGroupsIdxMap.get(i);
            Set<Integer> sourceGroupIdxSet = edge2SourceGroupIdxMap.get(i);
            Set<Integer> mergedGroupIdxSet = new HashSet<>(sinkGroupIdxSet);
            mergedGroupIdxSet.addAll(sourceGroupIdxSet);

            Integer edgeSize = partitionEdge.size();
            Integer edgeDegree = mergedGroupIdxSet.size();

            if (size2AmountMap.containsKey(edgeSize)) {
                Integer amount = size2AmountMap.get(edgeSize);
                size2AmountMap.replace(edgeSize, amount + 1);
            } else {
                size2AmountMap.put(edgeSize, 1);
            }

            if (degree2AmountMap.containsKey(edgeDegree)) {
                Integer amount = degree2AmountMap.get(edgeDegree);
                degree2AmountMap.replace(edgeDegree, amount + 1);
            } else {
                degree2AmountMap.put(edgeDegree, 1);
            }
            
        }

        logger.info("## Edge Size Distribution:");
        List<Map.Entry<Integer, Integer>>sortedEntryList = new ArrayList<>(size2AmountMap.entrySet());
        Collections.sort(sortedEntryList, Map.Entry.comparingByKey());
        for (Map.Entry<Integer, Integer> entry : sortedEntryList) {
            float singleGroupRatio = (float)entry.getKey() / totalEdgeCellInstNum * 100;
            float totalGroupsRatio = (float)entry.getKey() * (float)entry.getValue() / totalEdgeCellInstNum * 100;
            logger.info(String.format("## Number of edge with %d cells(%f): %d (%f)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
        }

        logger.info("## Edge Degree Distribution:");
        sortedEntryList = new ArrayList<>(degree2AmountMap.entrySet());
        Collections.sort(sortedEntryList, Map.Entry.comparingByKey());
        for (Map.Entry<Integer, Integer> entry : sortedEntryList) {
            float edgeNumRatio = (float)entry.getValue() / partitionEdges.size() * 100;
            logger.info(String.format("## Number of edge with %d degrees: %d (%f)", entry.getKey(), entry.getValue(), edgeNumRatio));
        }
    }

    public void printPartitionGroupsInfo() {
        logger.info("");
        logger.info("# Partition Groups Info:");
        logger.info("## Total Number of Partition Groups: " + partitionGroups.size());

        Collection<EDIFCellInst> cellInstCls = flatNetlist.getTopCell().getCellInsts();
        long totalFuncCellInstNum = cellInstCls.stream().filter(cellInst -> isFunctionalCellInst(cellInst)).count();
        long totalCellCount = cellInstCls.size();

        Map<Integer, Integer> size2AmountMap = new HashMap<>();
        Map<Integer, Integer> degree2AmountMap = new HashMap<>();

        long partitionGroupsCellCount = 0;
        for (int i = 0; i < partitionGroups.size(); i++){
            List<EDIFCellInst> partitionGroup = partitionGroups.get(i);
            
            Integer groupSize = partitionGroup.size();
            partitionGroupsCellCount += groupSize;
            if (size2AmountMap.containsKey(groupSize)) {
                Integer amount = size2AmountMap.get(groupSize);
                size2AmountMap.replace(groupSize, amount + 1);
            } else {
                size2AmountMap.put(groupSize, 1);
            }

            List<Integer> groupIncidentEdgeIdx = group2EdgesIdxMap.get(i);
            Integer groupDegree = groupIncidentEdgeIdx.size();
            if (degree2AmountMap.containsKey(groupDegree)) {
                Integer amount = degree2AmountMap.get(groupDegree);
                degree2AmountMap.replace(groupDegree, amount + 1);
            } else {
                degree2AmountMap.put(groupDegree, 1);
            }

        }
        assert partitionGroupsCellCount == totalGroupCellInstNum;
        assert totalGroupCellInstNum == totalGroupNonFuncCellInstNum + totalFuncCellInstNum;

        logger.info("## Group Size Distribution:");
        List<Map.Entry<Integer, Integer>> sortedEntryList = new ArrayList<>(size2AmountMap.entrySet());
        Collections.sort(sortedEntryList, Map.Entry.comparingByKey());
        for (Map.Entry<Integer, Integer> entry : sortedEntryList) {
            float singleGroupRatio = (float)entry.getKey() / totalCellCount * 100;
            float totalGroupsRatio = (float)entry.getKey() * (float)entry.getValue() / totalCellCount * 100;
            logger.info(String.format("## Number of Group with %d cells(%f): %d (%f)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
        }

        logger.info("## Group Degree Distribution:");
        sortedEntryList = new ArrayList<>(degree2AmountMap.entrySet());
        Collections.sort(sortedEntryList, Map.Entry.comparingByKey());
        for (Map.Entry<Integer, Integer> entry : sortedEntryList) {
            float groupNumRatio = (float)entry.getValue() / partitionGroups.size() * 100;
            logger.info(String.format("## Number of Group with %d degrees: %d (%f)", entry.getKey(), entry.getValue(), groupNumRatio));
        }
    }

    public void printCellInstDistribution() {
        long totalVccGndCellInstNum = flatNetlist.getTopCell().getCellInsts().stream().filter(cellInst -> cellInst.getCellType().isStaticSource()).count();
        Integer totalCellInstNum = flatNetlist.getTopCell().getCellInsts().size();
        Integer resetTreeCellInstNum = globalResetTreeCellInsts.size();
        logger.info("# Cell Instance Distribution:");
        logger.info("## Total Number of Cell Instances: " + totalCellInstNum);
        logger.info("## Total Number of VCC&GND Cell Instances: " + totalVccGndCellInstNum + " " + (float)totalVccGndCellInstNum / totalCellInstNum * 100 + "%");
        logger.info("## Total Number of Reset Tree Cell Instances: " + resetTreeCellInstNum + " " + (float)resetTreeCellInstNum / totalCellInstNum * 100 + "%");
        logger.info("## Total Number of Partition Group Cell Instances: " + totalGroupCellInstNum + " " + (float)totalGroupCellInstNum / totalCellInstNum * 100 + "%");
        logger.info("## Total Number of Partition Edge Cell Instances: " + totalEdgeCellInstNum + " " + (float)totalEdgeCellInstNum / totalCellInstNum * 100 + "%");
        assert totalCellInstNum == totalGroupCellInstNum + totalEdgeCellInstNum + totalVccGndCellInstNum + resetTreeCellInstNum;
    }

    public void writeFlatNetlistDCP(String dcpPath) {
        Design flatNetlistDesign = new Design(flatNetlist.getName(), partName);
        flatNetlistDesign.setNetlist(flatNetlist);
        flatNetlistDesign.writeCheckpoint(dcpPath);
    }

    public void writePartitionGroupsResult(String groupsResultPath) {
        try {
            FileWriter groupResFileWriter = new FileWriter(groupsResultPath);
            for (int i = 0; i < partitionGroups.size(); i++) {
                List<EDIFCellInst> partitionGroup = partitionGroups.get(i);
                groupResFileWriter.write(String.format("GROUP ID = %d (SIZE=%d)\n", i, partitionGroup.size()));
                for (EDIFCellInst cellInst : partitionGroup) {
                    groupResFileWriter.write(cellInst.getName() + ": ");
                    groupResFileWriter.write(cellInst.getCellType().getName() + "\n");
                }
                groupResFileWriter.write("END GROUP\n\n");
            }
            groupResFileWriter.close();
            logger.info("# Save Partition Groups Results to File: " + groupsResultPath);
        } catch (IOException e) {
            logger.info("Error occurred while saving partition results: " + e.getMessage());
        }
    }

    public void writeRegEdgesRemovedFlatNetlist(String dcpPath) {
        logger.info("# Generate Register Edges Removed Flat Netlist");

        EDIFNetlist newFlatNetlist = EDIFTools.createFlatNetlist(hierNetlist, partName);
        
        for (EDIFCellInst cellInst : newFlatNetlist.getTopCell().getCellInsts()) {
            if (isRegisterCellInst(cellInst) || cellInst.getCellType().isStaticSource()) {
                //logger.info("Remove Nets incidents to " + cellInst.getName());
                for (EDIFPortInst portInst : cellInst.getPortInsts()) {
                    newFlatNetlist.getTopCell().removeNet(portInst.getNet());
                    //logger.info("  Remove Net " + portInst.getNet().getName());
                }
            }
        }
        for (EDIFNet edifNet : newFlatNetlist.getTopCell().getNets()) {
            if (edifNet.isGND() || edifNet.isVCC()) {
                newFlatNetlist.getTopCell().removeNet(edifNet);
            }
        }

        Design regEdgeRemovedDesign = new Design(newFlatNetlist.getName(), partName);
        regEdgeRemovedDesign.setNetlist(newFlatNetlist);
        regEdgeRemovedDesign.writeCheckpoint(dcpPath);
        logger.info("# Finish Generating Register Edges Removed Flat Netlist");
    }

    public void writePartitionNetlist(String dcpPath) {
        logger.info("# Generate Partition Netlist");
        
    }

    public void printRegCtrlPortIncidentNets(String outputPath) {
        try {
            FileWriter regCtrlPortNetWriter = new FileWriter(outputPath);
            regCtrlPortNetWriter.write("Clock Enable Signals: \n");
            for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
                if (isRegisterCellInst(cellInst)) {
                    EDIFPortInst clkEnPortInst = cellInst.getPortInst("CE");
                    if (clkEnPortInst != null && !clkEnPortInst.getNet().isGND() && !clkEnPortInst.getNet().isVCC()) {
                        regCtrlPortNetWriter.write(cellInst.getName() + " " + clkEnPortInst.getName() + ": " + clkEnPortInst.getNet().getName() + "\n");
                    }
                }
            }

            regCtrlPortNetWriter.write("\nReset/Set Signals:\n");
            for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
                if (isRegisterCellInst(cellInst)) {
                    EDIFPortInst resetPortInst = cellInst.getPortInst("R");
                    EDIFPortInst setPortInst = cellInst.getPortInst("S");
                    if (resetPortInst != null) {
                        if (!resetPortInst.getNet().isGND() && !resetPortInst.getNet().isVCC()) {
                            regCtrlPortNetWriter.write(cellInst.getName() + " " + resetPortInst.getName() + ": " + resetPortInst.getNet().getName() + "\n");
                        }
                    }

                    if (setPortInst != null) {
                        if (!setPortInst.getNet().isGND() && !setPortInst.getNet().isVCC()) {
                            regCtrlPortNetWriter.write(cellInst.getName() + " " + setPortInst.getName() + ": " + setPortInst.getNet().getName() + "\n");
                        }
                    }
                }
            }
            regCtrlPortNetWriter.close();
        } catch (IOException e) {
            logger.info("Error occurred while saving partition results: " + e.getMessage());
        }
    }

    public void printCellInstOfType(String cellTypeName) {
        Integer cellInstCount = 0;
        logger.info("# CellInst of Type " + cellTypeName);
        for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
            if (cellInst.getCellName() == cellTypeName) {
                cellInstCount += 1;
                logger.info("  " + cellInst.getName());
            }
        }
        logger.info("Total number of CellInsts of Type " + cellTypeName + ": " + cellInstCount);
    }

}

