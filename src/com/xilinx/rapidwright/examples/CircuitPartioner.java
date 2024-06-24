package com.xilinx.rapidwright.examples;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    public static final Set<String> regCellTypeNames = Set.of("FDSE", "FDRE", "FDCE");
    
    //
    private String partName;
    private EDIFNetlist hierNetlist;
    private EDIFNetlist flatNetlist;
    private Logger logger;

    // Partition Resutls
    private List<List<EDIFCellInst>> partitionEdges;
    private List<List<EDIFCellInst>> partitionGroups;
    private Map<EDIFCellInst, Integer> regCellInst2EdgeIdxMap;
    private Map<EDIFCellInst, Integer> cellInst2GroupIdxMap;

    // Connection Info
    private List<Set<Integer>> edge2SinkGroupsIdxMap;
    private List<Set<Integer>> edge2SourceGroupIdxMap;
    private List<List<Integer>> group2EdgesIdxMap;

    // Global Reset Info
    private Set<EDIFCellInst> globalResetBridgeRegs;
    private Set<EDIFNet> globalResetNets;

    public CircuitPartioner(EDIFNetlist hierNetlist, String partName, String rstSrcInstName, Logger logger) {
        this.hierNetlist = hierNetlist;
        this.partName = partName;
        this.logger = logger;
        flatNetlist = EDIFTools.createFlatNetlist(hierNetlist, partName);

        //Part part = PartNameTools.getPart(partName);
        //flatNetlist.collapseMacroUnisims(part.getSeries());

        
        //EDIFCellInst rstSourceCellInst = flatNetlist.getCellInstFromHierName(rstSrcInstName);
        //traverseGlobalResetNetwork(rstSourceCellInst);
        buildPartitionGroups();
        //printCellInstOfType("IBUF");
        //assert rstSourceCellInst != null: "Invalid Reset Source Instance Name: " + rstSrcInstName;
        //traverseGlobalResetNetwork(rstSourceCellInst);
        //buildPartitionEdges();
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
            Boolean netVisitedTag = net.isGND() || net.isVCC();
            if (netVisitedTag) {
                logger.info("Constant Net " + net.getName() + " is visited");
            }
            netVisitedTags.put(net, netVisitedTag);
        }

        // Initialize inst visited tags
        for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
            EDIFCell cellType = cellInst.getCellType();
            Boolean visitedTag = cellType.isStaticSource() || isRegisterCellInst(cellInst);
            cellInstVisitedTags.put(cellInst, visitedTag);

            if (isRegisterCellInst(cellInst)) {
                for (EDIFPortInst portInst : cellInst.getPortInsts()) {
                    EDIFNet incidentNet = portInst.getNet();
                    netVisitedTags.replace(incidentNet, true);
                }
            }
        }

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
                                if (expandCellInst == null) continue;

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
            }
        }

    }

    private void buildPartitionEdges() {
        partitionEdges = new ArrayList<>();
        regCellInst2EdgeIdxMap = new HashMap<>();
        edge2SinkGroupsIdxMap = new ArrayList<>();
        edge2SourceGroupIdxMap = new ArrayList<>();

        logger.info("# Start Building Partition Edges");
        Set<EDIFCellInst> visitedRegCellInsts = new HashSet<>();

        for (EDIFCellInst cellInst : flatNetlist.getTopCell().getCellInsts()) {
            // EDIFCell cellType = cellInst.getCellType();
            if (!isRegisterCellInst(cellInst)) continue;
            if (!visitedRegCellInsts.contains(cellInst) && !globalResetBridgeRegs.contains(cellInst)) {
                List<EDIFCellInst> edgeRegCellInsts = new ArrayList<>();

                // Breadth-First Expansion
                Queue<EDIFCellInst> searchQueue = new LinkedList<>();
                searchQueue.add(cellInst);
                edgeRegCellInsts.add(cellInst);
                visitedRegCellInsts.add(cellInst);

                while (!searchQueue.isEmpty()) {
                    EDIFCellInst searchCellInst = searchQueue.poll();

                    for (EDIFPortInst portInst : searchCellInst.getPortInsts()) {
                        String portName = portInst.getPort().getName();
                        if (portName.equals("C")) continue;
                        
                        EDIFNet searchNet = portInst.getNet();
                        if (globalResetNets.contains(searchNet)) continue;

                        for (EDIFPortInst expandPortInst : searchNet.getPortInsts()) {
                            EDIFCellInst expandCellInst = expandPortInst.getCellInst();
                            if (isRegisterCellInst(expandCellInst) && !visitedRegCellInsts.contains(expandCellInst)) {
                                searchQueue.add(expandCellInst);
                                edgeRegCellInsts.add(expandCellInst);
                                visitedRegCellInsts.add(expandCellInst);
                            } else if (isFunctionalCellInst(expandCellInst)) {

                            }
                        }
                    }
                }

                // Set<Integer> sinkGroupIdxSet = new HashSet<>();
                // Set<Integer> sourceGroupIdxSet = new HashSet<>();

                // for (EDIFCellInst edgeCellInst : edgeRegCellInsts) {

                //     for (EDIFPortInst portInst : edgeCellInst.getPortInsts()) {

                //     }
                //     EDIFPortInst portInstQ = edgeCellInst.getPortInst("Q");
                //     assert portInstQ != null;
                //     for(EDIFPortInst sinkPortInst : portInstQ.getNet().getPortInsts()) {
                //         EDIFCellInst sinkCellInst = sinkPortInst.getCellInst();
                //         if (!isRegisterCellInst(sinkCellInst)) {
                //             assert cellInst2GroupIdxMap.containsKey(sinkCellInst);
                //             sinkGroupIdxSet.add(cellInst2GroupIdxMap.get(sinkCellInst));
                //         }
                //     }

                //     EDIFPortInst portInstD = edgeCellInst.getPortInst("D");
                //     assert portInstD != null;
                //     for (EDIFPortInst sourcePortInst : portInstD.getNet().getPortInsts()) {
                //         EDIFCellInst sourceCellInst = sourcePortInst.getCellInst();
                //         if (sourcePortInst.isOutput() && !isRegisterCellInst(sourceCellInst)) {
                //             assert cellInst2GroupIdxMap.containsKey(sourceCellInst);
                //             if (sourceGroupIdx != -1 && sourceGroupIdx != cellInst2GroupIdxMap.get(sourceCellInst)) {
                //                 for (EDIFCellInst testCellInst : edgeRegCellInsts) {
                //                     logger.info(testCellInst.getName());
                //                 }
                //             }
                //             assert sourceGroupIdx == -1 || sourceGroupIdx == cellInst2GroupIdxMap.get(sourceCellInst);
                //             sourceGroupIdx = cellInst2GroupIdxMap.get(sourceCellInst);
                //         }
                //     }
                // }
                // if (sourceGroupIdx == -1) {
                //     for (EDIFCellInst edgeCellInst : edgeRegCellInsts) {
                //         logger.info(edgeCellInst.getName());
                //     }
                // }
                // assert sourceGroupIdx != -1;
                // assert sinkGroupIdxSet.size() > 0;
            
                // if (sinkGroupIdxSet.size() == 1 && sinkGroupIdxSet.contains(sourceGroupIdx)) {
                //     // TODO:
                // } else {
                //     Integer edgeIdx = edge2SourceGroupIdxMap.size();
                //     assert edgeIdx == edge2SinkGroupsIdxMap.size();
                //     partitionEdges.add(edgeRegCellInsts);
                //     for (EDIFCellInst edgeCellInst : edgeRegCellInsts) {
                //         regCellInst2EdgeIdxMap.put(edgeCellInst, edgeIdx);
                //     }

                //     edge2SourceGroupIdxMap.add(sourceGroupIdx);
                //     edge2SinkGroupsIdxMap.add(sinkGroupIdxSet);
                    
                //     group2EdgesIdxMap.get(sourceGroupIdx).add(edgeIdx);
                //     for (Integer gIdx : sinkGroupIdxSet) {
                //         group2EdgesIdxMap.get(gIdx).add(edgeIdx);
                //     }
                // }
            }
        }
    }

    private void traverseGlobalResetNetwork(EDIFCellInst rstSourceInst) {
        logger.info("# Start Traversing Global Reset Network:");
        globalResetNets = new HashSet<>();
        globalResetBridgeRegs = new HashSet<>();
        assert rstSourceInst.getCellName().equals("IBUF"): "Global Reset Source Input Buffer: " + rstSourceInst.getCellName();
        
        Queue<EDIFCellInst> searchRstInsts = new LinkedList<>();
        searchRstInsts.add(rstSourceInst);

        while (!searchRstInsts.isEmpty()) {
            EDIFCellInst searchRstInst = searchRstInsts.poll();
            
            List<EDIFPortInst> fanoutPortInsts = getOutputPorts(searchRstInst);
            assert fanoutPortInsts.size() == 1;
            EDIFPortInst fanoutPortInst = fanoutPortInsts.get(0);
            
            EDIFNet fanoutRstNet = fanoutPortInst.getNet();
            assert !globalResetNets.contains(fanoutRstNet);
            globalResetNets.add(fanoutRstNet);

            logger.info(searchRstInst.getName() + ":" + fanoutPortInst.getName() + "->" + fanoutRstNet.getName() + ":");
            for (EDIFPortInst incidentPortInst : fanoutRstNet.getPortInsts()) {
                if (incidentPortInst.isInput()) {
                    EDIFCellInst incidentCellInst = incidentPortInst.getCellInst();
                    logger.info("  " + incidentCellInst.getName() + ": " + incidentPortInst.getName());
                    
                    assert isRegisterCellInst(incidentCellInst);
                    assert incidentPortInst.getName().equals("D") || incidentPortInst.getName().equals("S") || incidentPortInst.getName().equals("R");
                    
                    if (incidentPortInst.getName().equals("D")) {
                        searchRstInsts.add(incidentCellInst);
                        assert !globalResetBridgeRegs.contains(incidentCellInst);
                        globalResetBridgeRegs.add(incidentCellInst);
                    }
                }
            }
        }

    }

    private Boolean isRegisterCellInst(EDIFCellInst cellInst) {
        return regCellTypeNames.contains(cellInst.getCellType().getName());
    }

    private Boolean isFunctionalCellInst(EDIFCellInst cellInst) {
        return !cellInst.getCellType().isStaticSource() && !isRegisterCellInst(cellInst);
    }

    private List<EDIFPortInst> getOutputPorts(EDIFCellInst cellInst) {
        List<EDIFPortInst> outputPortInsts = new ArrayList<>();
        for (EDIFPortInst portInst : cellInst.getPortInsts()) {
            if (portInst.isOutput()) {
                outputPortInsts.add(portInst);
            }
        }
        return outputPortInsts;
    }

    private boolean isClockPortInst(EDIFPortInst portInst) {
        return isRegisterCellInst(portInst.getCellInst()) && portInst.getName() == "D";
    }

    public void printPartitionEdgesInfo() {
        logger.info("# Partition Edge Info:");
        logger.info("## Total Number of Partition Groups: " + partitionEdges.size());

        long totalRegCellInstNum = flatNetlist.getTopCell().getCellInsts()
                                              .stream().filter(cellInst -> isRegisterCellInst(cellInst)).count();

        Map<Integer, Integer> size2AmountMap = new HashMap<>();
        size2AmountMap.clear();
        for (List<EDIFCellInst> partitionEdge : partitionEdges) {
            Integer edgeSize = partitionEdge.size();
            if (size2AmountMap.containsKey(edgeSize)) {
                Integer amount = size2AmountMap.get(edgeSize);
                size2AmountMap.replace(edgeSize, amount + 1);
            } else {
                size2AmountMap.put(edgeSize, 1);
            }
        }

        List<Map.Entry<Integer, Integer>>sortedEntryList = new ArrayList<>(size2AmountMap.entrySet());
        Collections.sort(sortedEntryList, Map.Entry.comparingByKey());
        for (Map.Entry<Integer, Integer> entry : sortedEntryList) {
            float singleGroupRatio = (float)entry.getKey() / totalRegCellInstNum * 100;
            float totalGroupsRatio = (float)entry.getKey() * (float)entry.getValue() / totalRegCellInstNum * 100;
            logger.info(String.format("## Number of edge with %d cells(%f): %d (%f)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
        }
    }

    public void printPartitionGroupsInfo() {
        logger.info("");
        logger.info("# Partition Groups Info:");
        logger.info("## Total Number of Partition Groups: " + partitionGroups.size());

        Collection<EDIFCellInst> cellInstCls = flatNetlist.getTopCell().getCellInsts();
        long totalFuncCellInstNum = cellInstCls.stream().filter(cellInst -> isFunctionalCellInst(cellInst)).count();

        Map<Integer, Integer> size2AmountMap = new HashMap<>();
        long partitionGroupsCellCount = 0;
        for (List<EDIFCellInst> partitionGroup : partitionGroups) {
            Integer groupSize = partitionGroup.size();
            partitionGroupsCellCount += groupSize;
            if (size2AmountMap.containsKey(groupSize)) {
                Integer amount = size2AmountMap.get(groupSize);
                size2AmountMap.replace(groupSize, amount + 1);
            } else {
                size2AmountMap.put(groupSize, 1);
            }
        }
        assert partitionGroupsCellCount == totalFuncCellInstNum;

        List<Map.Entry<Integer, Integer>> sortedEntryList = new ArrayList<>(size2AmountMap.entrySet());
        Collections.sort(sortedEntryList, Map.Entry.comparingByKey());
        for (Map.Entry<Integer, Integer> entry : sortedEntryList) {
            float singleGroupRatio = (float)entry.getKey() / totalFuncCellInstNum * 100;
            float totalGroupsRatio = (float)entry.getKey() * (float)entry.getValue() / totalFuncCellInstNum * 100;
            logger.info(String.format("## Number of Group with %d cells(%f): %d (%f)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
        }
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

// // Backward Expansion
// EDIFCellInst backwardExpandCellInst = cellInst;
// while (sourceGroupIdx == -1) {
//     visitedRegCellInsts.add(backwardExpandCellInst);
//     edgeRegCellInsts.add(backwardExpandCellInst);
    
//     EDIFPortInst portInstD = backwardExpandCellInst.getPortInst("D");
//     for (EDIFPortInst expandPortInst : portInstD.getNet().getPortInsts()) {
//         if (expandPortInst.isOutput()) {
//             EDIFCellInst expandCellInst = expandPortInst.getCellInst();
//             if (isRegisterCellInst(expandCellInst)) {
//                 assert !visitedRegCellInsts.contains(expandCellInst): 
//                 backwardExpandCellInst.getName() + ":" + backwardExpandCellInst.getCellName() + "->" + 
//                 expandCellInst.getName() + ":" + expandCellInst.getCellName();
//                 backwardExpandCellInst = expandCellInst;
//             } else {
//                 assert cellInst2GroupIdxMap.containsKey(expandCellInst);
//                 sourceGroupIdx = cellInst2GroupIdxMap.get(expandCellInst);
//             }
//         }
//     } 
// }
// // Forward Expansion
// forwardSearchQueue.add(cellInst);
// while (!forwardSearchQueue.isEmpty()) {
//     EDIFCellInst searchCellInst = forwardSearchQueue.poll();
//     EDIFPortInst searchPortInstQ = searchCellInst.getPortInst("Q");
//     for (EDIFPortInst expandPortInst : searchPortInstQ.getNet().getPortInsts()) {
//         if (expandPortInst  == searchPortInstQ) continue;
//         EDIFCellInst expandCellInst = expandPortInst.getCellInst();
//         if (isRegisterCellInst(expandCellInst)) {
//             assert !visitedRegCellInsts.contains(expandCellInst);
//             forwardSearchQueue.add(expandCellInst);
//             visitedRegCellInsts.add(expandCellInst);
//             edgeRegCellInsts.add(expandCellInst);
//         } else {
//             assert cellInst2GroupIdxMap.containsKey(expandCellInst);
//             sinkGroupIdxSet.add(cellInst2GroupIdxMap.get(expandCellInst));
//         }
//     }
// }
