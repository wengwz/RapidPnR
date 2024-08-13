package com.xilinx.rapidwright.examples;

import java.util.List;
import java.util.Map;


final class PartitionGroupJson {
    public Integer id;
    public Integer primCellNum;
    public Map<String, Integer> resourceTypeUtil;
    public List<Integer> loc;
    public List<String> groupCellNames;
}

final class PartitionEdgeJson {
    public Integer id;
    public Integer primCellNum;
    public Integer weight; // Number of Partition Edges
    public Integer degree;
    public List<Integer> incidentGroupIds;
    public List<String> edgeCellNames;
}

public class PartitionResultsJson {
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

