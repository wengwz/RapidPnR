package com.xilinx.rapidwright.rapidpnr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;


import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;

abstract public class AbstractNetlist {

    public static boolean calibrateLUTUtils = true;
    
    protected HierarchicalLogger logger;
    protected NetlistDatabase netlistDatabase;

    //
    public List<Set<EDIFCellInst>> node2CellInsts;
    public Map<EDIFCellInst, Integer> cellInst2NodeIdMap;

    //
    public List<Set<Integer>> edge2NodeIds;
    public List<Set<Integer>> node2EdgeIds;
    public List<EDIFNet> edge2OriginNet;

    // Resource utilization of abstract nodes
    public List<Map<EDIFCell, Integer>> node2LeafCellUtils;
    public List<Integer> node2LeafCellNum;
    public List<Map<String, Integer>> node2ResUtils;

    public AbstractNetlist(HierarchicalLogger logger) {
        this.logger = logger;
    }

    public void buildAbstractNetlist(NetlistDatabase netlistDatabase) {
        logger.info("Start building abstract netlist:");
        logger.newSubStep();

        this.netlistDatabase = netlistDatabase;

        buildNode2CellInstsMap();

        buildEdge2NodeMap();

        buildNode2ResUtilMap();

        logger.endSubStep();
        logger.info("Complete building abstract netlist");
    }

    abstract protected void buildNode2CellInstsMap();

    abstract protected void buildEdge2NodeMap();

    protected void buildNode2ResUtilMap() {
        logger.info("Start building resource utilization of abstract nodes:");
        node2LeafCellUtils = new ArrayList<>();
        node2LeafCellNum = new ArrayList<>();
        node2ResUtils = new ArrayList<>();

        for (Set<EDIFCellInst> nodeCellInsts : node2CellInsts) {

            Map<EDIFCell, Integer> primCellUtilMap = new HashMap<>();
            for (EDIFCellInst cellInst : nodeCellInsts) {
                NetlistUtils.getLeafCellUtils(cellInst.getCellType(), primCellUtilMap);
            }

            Integer primCellNum = primCellUtilMap.values().stream().mapToInt(Integer::intValue).sum();
            node2LeafCellNum.add(primCellNum);

            if (calibrateLUTUtils) {
                NetlistUtils.calibrateLUTUtils(nodeCellInsts, primCellUtilMap); // calibrate usage of LUTs
            }

            node2LeafCellUtils.add(primCellUtilMap);
            node2ResUtils.add(NetlistUtils.getResTypeUtils(primCellUtilMap));
        }
    }

    public void printAbstractNetlistInfo() {
        printAbstractGroupsInfo();
        printAbstractEdgesInfo();
    }

    public void printAbstractGroupsInfo() {
        logger.info("Abstract Group Info:");
        logger.newSubStep();

        Integer totalAbstractGroupNum = node2CellInsts.size();
        logger.info("Total Number of Abstract Groups: " + totalAbstractGroupNum);

        Map<Integer, Integer> leafCellNum2AmountMap = new HashMap<>();
        Map<Integer, Integer> incidentEdgeNum2AmountMap = new HashMap<>();
        Map<Integer, Integer> lutNum2AmountMap = new HashMap<>();


        for (int i = 0; i < node2CellInsts.size(); i++){
            Set<Integer> grpIncidnetEdges = node2EdgeIds.get(i);
            Integer grpPrimCellNum = node2LeafCellNum.get(i);
            Integer grpLutNum = 0;
            if (node2ResUtils.get(i).get("LUT") != null) {
                grpLutNum = node2ResUtils.get(i).get("LUT");
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

        Integer totalLeafCellNum = node2LeafCellNum.stream().mapToInt(Integer::intValue).sum();
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
            logger.info(String.format("Number of groups with %d leaf cells(%.2f%%): %d (%.2f%%)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
        }
        logger.endSubStep();


        Integer totalLUTNum = node2ResUtils.stream().mapToInt(map -> (map.get("LUT") == null) ? 0 : map.get("LUT")).sum();
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
            logger.info(String.format("Number of groups with %d LUTs(%.2f%%): %d (%.2f%%)", entry.getKey(), singleGroupRatio, entry.getValue(), totalGroupsRatio));
        }
        logger.endSubStep();
    

        Integer totalEdgeNum = edge2NodeIds.size();
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
            logger.info(String.format("Number of Groups Incident with %d(%.2f%%) Edges: %d (%.2f%%)", entry.getKey(), edgeNumRatio, entry.getValue(), groupNumRatio));
        }
        logger.endSubStep();

        logger.endSubStep();
    }

    public void printAbstractEdgesInfo() {
        logger.info("Abstract Edges Info:");
        logger.newSubStep();
        
        logger.info("Total Number of Abstract Edges: " + edge2NodeIds.size());

        Map<Integer, Integer> degree2AmountMap = new HashMap<>();
        for (int i = 0; i < edge2NodeIds.size(); i++) {
            Set<Integer> groupIds = edge2NodeIds.get(i);

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
            float edgeNumRatio = (float) entry.getValue() / edge2NodeIds.size() * 100;
            logger.info(String.format("Degree from %d to %d: %d (%.2f%%)", entry.getKey(), entry.getKey() + 10, entry.getValue(), edgeNumRatio));
        }
        logger.endSubStep();

        logger.endSubStep();
    }

    public int getNodeNum() {
        return node2CellInsts.size();
    }

    public int getEdgeNum() {
        return edge2NodeIds.size();
    }

    public int getLeafCellNumOfNode(int id) {
        return node2LeafCellNum.get(id);
    }

    public Map<String, Integer> getResUtilOfNode(int id) {
        return node2ResUtils.get(id);
    }

    public Set<EDIFCellInst> getCellInstsOfNode(int grpId) {
        return node2CellInsts.get(grpId);
    }

}
