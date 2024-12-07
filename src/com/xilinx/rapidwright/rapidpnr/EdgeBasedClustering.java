package com.xilinx.rapidwright.rapidpnr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class EdgeBasedClustering extends AbstractNetlist {

    public static final Predicate<EDIFNet> FFBasedNetFilter = net -> {
        List<EDIFPortInst> srcPortInsts = net.getSourcePortInsts(true);
        assert srcPortInsts.size() == 1;
        
        EDIFPortInst srcPortInst = srcPortInsts.get(0);
        EDIFCellInst srcCellInst = srcPortInst.getCellInst();
        
        boolean isRegFanoutNet = false;
        if (srcCellInst != null) {
            isRegFanoutNet = NetlistUtils.isRegisterCellInst(srcCellInst);
        }

        return isRegFanoutNet;
    };

    public static final Predicate<EDIFNet> CLBBasedFilter = net -> {
        List<EDIFPortInst> srcPortInsts = net.getSourcePortInsts(true);
        assert srcPortInsts.size() == 1;
        
        EDIFPortInst srcPortInst = srcPortInsts.get(0);
        EDIFCellInst srcCellInst = srcPortInst.getCellInst();
        
        boolean isFiltered = false;
        if (srcCellInst != null) {
            boolean isRegFanoutNet = NetlistUtils.isRegisterCellInst(srcCellInst);
            boolean isBigLUT = srcCellInst.getCellType().getName().equals("LUT6") || srcCellInst.getCellType().getName().equals("LUT5");
            isFiltered = isRegFanoutNet || isBigLUT;
        }

        return isFiltered;
    };
    
    private Predicate<EDIFNet> netFilter;

    public EdgeBasedClustering(HierarchicalLogger logger) {
        this(logger, FFBasedNetFilter);
    }

    public EdgeBasedClustering(HierarchicalLogger logger, Predicate<EDIFNet> netFilter) {
        super(logger);

        this.netFilter = netFilter;;
    }

    public void setNetFilter(Predicate<EDIFNet> netFilter) {
        this.netFilter = netFilter;
    }

    protected void buildNode2CellInstsMap() {

        logger.info("Start building node2CellInstsMap based on edge-based method:");

        cellInst2NodeIdMap = new HashMap<>();
        node2CellInsts = new ArrayList<>();
        node2EdgeIds = new ArrayList<>();


        Set<EDIFNet> visitedNetsCls = new HashSet<>();

        
        // Remove global clock/reset nets and ignore nets
        visitedNetsCls.addAll(netlistDatabase.globalClockNets);
        visitedNetsCls.addAll(netlistDatabase.globalResetNets);
        visitedNetsCls.addAll(netlistDatabase.illegalNets);
        visitedNetsCls.addAll(netlistDatabase.ignoreNets);

        // Remove static nets and reg-fanout nets
        for (EDIFNet net : netlistDatabase.originTopCell.getNets()) {
            boolean staticNet = net.isVCC() || net.isGND();
            if (visitedNetsCls.contains(net)) continue;

            if (staticNet || netFilter.test(net)) {
                visitedNetsCls.add(net);
            }
        }
        
        logger.info("The number of visited nets before expansion: " + visitedNetsCls.size());

        for (EDIFCellInst cellInst : netlistDatabase.originTopCell.getCellInsts()) {
            if (netlistDatabase.globalResetTreeCellInsts.contains(cellInst)) continue;
            if (netlistDatabase.staticSourceCellInsts.contains(cellInst)) continue;
            if (cellInst2NodeIdMap.containsKey(cellInst)) continue;

            Integer grpIdx = node2CellInsts.size();
            Set<EDIFCellInst> grpCellInsts = new HashSet<>();
            Queue<EDIFCellInst> cellInstToSearch =  new LinkedList<>();

            grpCellInsts.add(cellInst);
            cellInst2NodeIdMap.put(cellInst, grpIdx);
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
                        if (cellInst2NodeIdMap.containsKey(portCellInst)) continue;

                        cellInst2NodeIdMap.put(portCellInst, grpIdx);
                        grpCellInsts.add(portCellInst);
                        cellInstToSearch.add(portCellInst);
                    }
                }
            }

            node2CellInsts.add(grpCellInsts);
            node2EdgeIds.add(new HashSet<>());
        }

        int grpCellInstsNum = cellInst2NodeIdMap.size();
        int rstTreeCellInstsNum = netlistDatabase.globalResetTreeCellInsts.size();
        int totalCellInstsNum = netlistDatabase.originTopCell.getCellInsts().size();
        int staticSourceCellInstsNum = netlistDatabase.staticSourceCellInsts.size();
        assert totalCellInstsNum == grpCellInstsNum + rstTreeCellInstsNum + staticSourceCellInstsNum;

        logger.info("Complete building node2CellInstsMap based on edge-based method");
    }

    protected void buildEdge2NodeMap() {
        logger.info("Start building mapping between edges and abstract nodes:");
        edge2NodeIds = new ArrayList<>();
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
                assert cellInst2NodeIdMap.containsKey(cellInst);
                Integer groupIdx = cellInst2NodeIdMap.get(cellInst);
                incidentGrpIds.add(groupIdx);
            }

            if (incidentGrpIds.size() > 1) {
                assert netFilter.test(net);
                edge2NodeIds.add(incidentGrpIds);
                for (Integer groupIdx : incidentGrpIds) {
                    node2EdgeIds.get(groupIdx).add(edge2NodeIds.size() - 1);
                }
                edge2OriginNet.add(net);
            }
        }

        logger.info("Complete building mapping between edges and abstract nodes");
    }
}
