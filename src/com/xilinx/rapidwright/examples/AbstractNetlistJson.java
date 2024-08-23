package com.xilinx.rapidwright.examples;

import java.util.List;
import java.util.Map;

final class GroupJson {
    public Integer id;
    public Integer weight;
    public Map<String, Integer> resTypeUtil;
    public List<Integer> loc;
    public List<String> grpCellNames;
}

final class EdgeJson {
    public Integer id;
    public Integer primCellNum;
    public Integer weight; // Number of Partition Edges
    public Integer degree;
    public List<Integer> incidentGroupIds;
    public List<String> edgeCellNames;
}

public class AbstractNetlistJson {
    public Integer totalPrimCellNum;
    public String rstPortName;
    public String clkPortName;
    public Map<String, Integer> resourceTypeUtil;
    
    public Integer totalGroupNum;
    public Integer totalEdgeNum;

    public Integer totalGroupCellNum;
    public Integer totalEdgeCellNum;

    public List<PartitionGroupJson> partitionGroups;
    public List<PartitionEdgeJson> partitionEdges;

    public List<String> resetTreeCellNames;
    public List<String> resetNetNames;
}
