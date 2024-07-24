package com.xilinx.rapidwright.examples;

import java.util.List;
import java.util.Map;


final class PartitionGroupJson {
    public Integer id;
    public Integer primCellNum;
    public Map<String, Integer> resourceTypeUtil;
}

final class PartitionEdgeJson {
    public Integer id;
    public Integer primCellNum;
    public Integer weight; // Number of Partition Edges
    public Integer degree;
    public List<Integer> incidentPrimCellIds;
}

public class PartitionResultsJson {
    public Integer totalPrimCellNum;
    public Map<String, Integer> resourceTypeUtil;
    
    public Integer totalGroupNum;
    public Integer totalEdgeNum;
    public List<PartitionGroupJson> partitionGroups;
    public List<PartitionEdgeJson> partitionEdges;
}