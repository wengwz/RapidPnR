package com.xilinx.rapidwright.rapidpnr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFCellInst;

public class BasicAbstractNetlist extends AbstractNetlist{

    public BasicAbstractNetlist(HierarchicalLogger logger) {
        super(logger);
    }

    protected void buildNode2CellInstsMap() {
        logger.info("Start building mapping between nodes and cellInsts");

        cellInst2NodeIdMap = new HashMap<>();
        node2CellInsts = new ArrayList<>();
        node2EdgeIds = new ArrayList<>();

        for (EDIFCellInst cellInst : netlistDatabase.originTopCell.getCellInsts()) {
            if (netlistDatabase.globalResetTreeCellInsts.contains(cellInst)) continue;
            if (cellInst.getCellType().isStaticSource()) continue;

            int nodeId = node2CellInsts.size();
            cellInst2NodeIdMap.put(cellInst, nodeId);

            Set<EDIFCellInst> cellInstSet = new HashSet<>();
            cellInstSet.add(cellInst);
            node2CellInsts.add(cellInstSet);
        }

        logger.info("Complete building mapping between nodes and cellInsts");
    }
}
