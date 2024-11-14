package com.xilinx.rapidwright.rapidpnr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;


import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class AbstractNetlist {
    
    private HierarchicalLogger logger;
    private NetlistDatabase netlistDatabase;

    public List<Set<EDIFCellInst>> group2CellInsts;
    public Map<EDIFCellInst, Integer> cellInst2GroupIdMap;

    public List<Set<Integer>> edge2GroupIds;
    public List<Set<Integer>> group2EdgeIds;
    public List<EDIFNet> edge2OriginNet;

    public List<Map<EDIFCell, Integer>> group2LeafCellUtils;
    public List<Integer> group2LeafCellNum;

    public AbstractNetlist(HierarchicalLogger logger, NetlistDatabase netlistDatabase) {
        this.logger = logger;
        this.netlistDatabase = netlistDatabase;

        buildAbstractNetlist();
    }

    public void buildAbstractNetlist() {
        logger.info("Start building abstract netlist:");
        logger.newSubStep();

        buildCellInst2GroupMap();

        buildEdge2GroupMap();

        
        logger.endSubStep();
        logger.info("Complete building abstract netlist");
    }


    private void buildCellInst2GroupMap() {

        logger.info("Start building timing-path-aware cellInst-group map:");

        cellInst2GroupIdMap = new HashMap<>();
        group2CellInsts = new ArrayList<>();
        group2EdgeIds = new ArrayList<>();
        group2LeafCellUtils = new ArrayList<>();
        group2LeafCellNum = new ArrayList<>();


        Set<EDIFNet> visitedNetsCls = new HashSet<>();

        
        // Remove global clock/reset nets and ignore nets
        visitedNetsCls.addAll(netlistDatabase.globalClockNets);
        visitedNetsCls.addAll(netlistDatabase.globalResetNets);
        visitedNetsCls.addAll(netlistDatabase.illegalNets);
        // for (EDIFNet net : netlistDatabase.originTopCell.getNets()) {
        //     if (visitedNetsCls.contains(net)) continue;

        //     if (net.getPortInsts().size() > 1000) {
        //         netlistDatabase.ignoreNets.add(net);
        //     }
        // }
        visitedNetsCls.addAll(netlistDatabase.ignoreNets);

        // Remove static nets and reg-fanout nets
        for (EDIFNet net : netlistDatabase.originTopCell.getNets()) {
            boolean staticNet = net.isVCC() || net.isGND();
            if (visitedNetsCls.contains(net)) continue;

            List<EDIFPortInst> srcPortInsts = net.getSourcePortInsts(true);
            assert srcPortInsts.size() == 1;
            
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
        logger.info("The number of visited nets before expansion: " + visitedNetsCls.size());

        for (EDIFCellInst cellInst : netlistDatabase.originTopCell.getCellInsts()) {
            if (netlistDatabase.globalResetTreeCellInsts.contains(cellInst)) continue;
            if (netlistDatabase.staticSourceCellInsts.contains(cellInst)) continue;
            if (cellInst2GroupIdMap.containsKey(cellInst)) continue;

            Integer grpIdx = group2CellInsts.size();
            Set<EDIFCellInst> grpCellInsts = new HashSet<>();
            Queue<EDIFCellInst> cellInstToSearch =  new LinkedList<>();

            grpCellInsts.add(cellInst);
            cellInst2GroupIdMap.put(cellInst, grpIdx);
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
                        if (cellInst2GroupIdMap.containsKey(portCellInst)) continue;

                        cellInst2GroupIdMap.put(portCellInst, grpIdx);
                        grpCellInsts.add(portCellInst);
                        cellInstToSearch.add(portCellInst);
                    }
                }
            }

            group2CellInsts.add(grpCellInsts);
            group2EdgeIds.add(new HashSet<>());
            Map<EDIFCell, Integer> primCellUtilMap = new HashMap<>();
            for (EDIFCellInst cellInstInGrp : grpCellInsts) {
                NetlistUtils.getLeafCellUtils(cellInstInGrp.getCellType(), primCellUtilMap);
            }
            group2LeafCellUtils.add(primCellUtilMap);
            Integer primCellNum = primCellUtilMap.values().stream().mapToInt(Integer::intValue).sum();
            group2LeafCellNum.add(primCellNum);
        }

        int grpCellInstsNum = cellInst2GroupIdMap.size();
        int rstTreeCellInstsNum = netlistDatabase.globalResetTreeCellInsts.size();
        int totalCellInstsNum = netlistDatabase.originTopCell.getCellInsts().size();
        int staticSourceCellInstsNum = netlistDatabase.staticSourceCellInsts.size();
        assert totalCellInstsNum == grpCellInstsNum + rstTreeCellInstsNum + staticSourceCellInstsNum;

        logger.info("Complete building timing-path-aware cellInst-group map");
    }

    private void buildEdge2GroupMap() {
        logger.info("Start building timing-path-aware edge-group map:");
        edge2GroupIds = new ArrayList<>();
        edge2OriginNet = new ArrayList<>();
        
        for (EDIFNet net : netlistDatabase.originTopCell.getNets()) {
            if (net.isVCC() || net.isGND()) continue;
            if (netlistDatabase.globalClockNets.contains(net)) continue;
            if (netlistDatabase.globalResetNets.contains(net)) continue;
            if (netlistDatabase.ignoreNets.contains(net)) continue;

            Set<Integer> incidentGrpIds = new HashSet<>();
            for (EDIFPortInst portInst : net.getPortInsts()) {
                EDIFCellInst cellInst = portInst.getCellInst();
                if (cellInst == null) continue; // Skip toplevel ports
                assert cellInst2GroupIdMap.containsKey(cellInst);
                Integer groupIdx = cellInst2GroupIdMap.get(cellInst);
                incidentGrpIds.add(groupIdx);
            }

            if (incidentGrpIds.size() > 1) {
                assert NetlistUtils.isRegFanoutNet(net);
                edge2GroupIds.add(incidentGrpIds);
                for (Integer groupIdx : incidentGrpIds) {
                    group2EdgeIds.get(groupIdx).add(edge2GroupIds.size() - 1);
                }
                edge2OriginNet.add(net);
            }
        }

        logger.info("Complete building timing-path-aware edge-group map");
    }


    public void printAbstractNetlistInfo() {
        printAbstractGroupsInfo();
        printAbstractEdgesInfo();
    }

    public void printAbstractGroupsInfo() {
        logger.info("Abstract Group Info:");
        logger.newSubStep();

        Integer totalAbstractGroupNum = group2CellInsts.size();
        logger.info("Total Number of Abstract Groups: " + totalAbstractGroupNum);

        Map<Integer, Integer> leafCellNum2AmountMap = new HashMap<>();
        Map<Integer, Integer> incidentEdgeNum2AmountMap = new HashMap<>();
        Map<Integer, Integer> lutNum2AmountMap = new HashMap<>();


        for (int i = 0; i < group2CellInsts.size(); i++){
            Set<Integer> grpIncidnetEdges = group2EdgeIds.get(i);
            Integer grpPrimCellNum = group2LeafCellNum.get(i);
            Integer grpLutNum = 0;

            for (Map.Entry<EDIFCell, Integer> entry : group2LeafCellUtils.get(i).entrySet()) {
                // if (NetlistUtils.cellType2ResTypeMap.get(entry.getKey().getName()) == null) {
                //     continue;
                // }
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

        Integer totalLeafCellNum = group2LeafCellNum.stream().mapToInt(Integer::intValue).sum();
        List<Map.Entry<Integer, Integer>> sortedLeafCellNum2AmountMap = leafCellNum2AmountMap.entrySet()
        .stream()
        .sorted(Map.Entry.<Integer, Integer>comparingByKey())
        .collect(Collectors.toList());
        logger.info("Total Num of Leaf Cells Involved in Groups: " + totalLeafCellNum);
        logger.info("Group Leaf Cell Num Distribution:");

        logger.newSubStep();
        for (Map.Entry<Integer, Integer> entry : sortedLeafCellNum2AmountMap) {
            float singleGroupRatio = (float)entry.getKey() / totalLeafCellNum * 100;
            float totalGroupsRatio = (float)entry.getKey() * (float)entry.getValue() / totalLeafCellNum * 100;
            logger.info(String.format("Number of groups with %d leaf cells(%f): %d (%f)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
        }
        logger.endSubStep();


        Integer totalLUTNum = 0;
        for (Map.Entry<EDIFCell, Integer> entry : netlistDatabase.netlistLeafCellUtilMap.entrySet()) {
            if (NetlistUtils.cellType2ResTypeMap.get(entry.getKey().getName()).equals("LUT")) {
                totalLUTNum += entry.getValue();
            }
        }

        logger.info("Total Num of LUTs: " + totalLUTNum);
        logger.info("Group LUT Num Distribution:");
        List<Map.Entry<Integer, Integer>> sortedLUTNum2AmountMap = lutNum2AmountMap.entrySet()
        .stream()
        .sorted(Map.Entry.<Integer, Integer>comparingByKey())
        .collect(Collectors.toList());
        logger.newSubStep();
        for (Map.Entry<Integer, Integer> entry : sortedLUTNum2AmountMap) {
            float singleGroupRatio = (float)entry.getKey() / totalLUTNum * 100;
            float totalGroupsRatio = (float)entry.getKey() * (float)entry.getValue() / totalLUTNum * 100;
            logger.info(String.format("Number of groups with %d LUTs(%f): %d (%f)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
        }
        logger.endSubStep();
    

        Integer totalEdgeNum = edge2GroupIds.size();
        List<Map.Entry<Integer, Integer>> sortedIncidentEdgeNum2AmountMap = incidentEdgeNum2AmountMap.entrySet()
        .stream()
        .sorted(Map.Entry.<Integer, Integer>comparingByKey())
        .collect(Collectors.toList());
        logger.info("Total Num of Abstract Edges:" + totalEdgeNum);
        logger.info("Group Incident Edge Num Distribution:");

        logger.newSubStep();
        for (Map.Entry<Integer, Integer> entry : sortedIncidentEdgeNum2AmountMap) {
            float edgeNumRatio = (float)entry.getKey() / totalEdgeNum * 100;
            float groupNumRatio = (float)entry.getValue() / totalAbstractGroupNum * 100;
            logger.info(String.format("Number of Groups Incident with %d(%f) Edges: %d (%f)", entry.getKey(), edgeNumRatio, entry.getValue(), groupNumRatio));
        }
        logger.endSubStep();

        logger.endSubStep();
    }

    public void printAbstractEdgesInfo() {
        logger.info("Abstract Edges Info:");
        logger.newSubStep();
        
        logger.info("Total Number of Abstract Edges: " + edge2GroupIds.size());

        Map<Integer, Integer> degree2AmountMap = new HashMap<>();
        for (int i = 0; i < edge2GroupIds.size(); i++) {
            Set<Integer> groupIds = edge2GroupIds.get(i);

            Integer edgeDegree = (groupIds.size() / 10) * 10;

            if (degree2AmountMap.containsKey(edgeDegree)) {
                Integer amount = degree2AmountMap.get(edgeDegree);
                degree2AmountMap.replace(edgeDegree, amount + 1);
            } else {
                degree2AmountMap.put(edgeDegree, 1);
            }
        }

        logger.info("Edge Degree Distribution:");
        List<Map.Entry<Integer, Integer>> sortedDegree2AmountMap = degree2AmountMap.entrySet()
            .stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByKey())
            .collect(Collectors.toList());

        logger.newSubStep();
        for (Map.Entry<Integer, Integer> entry : sortedDegree2AmountMap) {
            float edgeNumRatio = (float) entry.getValue() / edge2GroupIds.size() * 100;
            logger.info(String.format("Degree from %d to %d: %d (%.2f%%)", entry.getKey(), entry.getKey() + 10, entry.getValue(), edgeNumRatio));
        }
        logger.endSubStep();

        logger.endSubStep();
    }


    int getGroupNum() {
        return group2CellInsts.size();
    }

    int getEdgeNum() {
        return edge2GroupIds.size();
    }

    Set<EDIFCellInst> getCellInstsOfGroup(int grpId) {
        return group2CellInsts.get(grpId);
    }

}
