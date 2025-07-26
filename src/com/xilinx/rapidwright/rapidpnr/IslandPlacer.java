package com.xilinx.rapidwright.rapidpnr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


import com.xilinx.rapidwright.rapidpnr.partitioner.AbstractPartitioner;
import com.xilinx.rapidwright.rapidpnr.partitioner.Coarser;
import com.xilinx.rapidwright.rapidpnr.partitioner.FMPartitioner;
import com.xilinx.rapidwright.rapidpnr.partitioner.ILPIslandPartitioner;
import com.xilinx.rapidwright.rapidpnr.partitioner.MultiLevelPartitioner;
import com.xilinx.rapidwright.rapidpnr.partitioner.TritonPartitionWrapper;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierHyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.HyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.StatisticsUtils;
import com.xilinx.rapidwright.rapidpnr.utils.VecOps;

public class IslandPlacer extends AbstractIslandPlacer {
    private int maxPartialNetlistSize = 300;
    private static Set<String> checkResTypes = new HashSet<>(Arrays.asList("DSP", "BRAM", "URAM"));
    private static Set<Coordinate2D> allowedGridDim;
    static {
        allowedGridDim = new HashSet<>();
        allowedGridDim.add(Coordinate2D.of(2, 1));
        allowedGridDim.add(Coordinate2D.of(2, 2));
        allowedGridDim.add(Coordinate2D.of(3, 1));
    }

    private HierHyperGraph netlistGraph;
    private List<Coordinate2D> node2IslandLoc;

    private List<Integer>[][] island2Nodes;
    private List<Integer>[][] horiBoundary2Edges;
    private List<Integer>[][] vertBoundary2Edges;
    private List<Integer> edgeLengths;
    
    public IslandPlacer(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams designParams) {
        super(logger, dirManager, designParams);
        assert allowedGridDim.contains(gridDim): "Unsupported grid dimension: " + gridDim.toString();
    }

    public List<Coordinate2D> run(AbstractNetlist abstractNetlist) {

        this.abstractNetlist = abstractNetlist;

        this.netlistGraph = convertNetlist2HyperGraph(abstractNetlist);

        // initialize placement results
        node2IslandLoc = new ArrayList<>();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            node2IslandLoc.add(new Coordinate2D());
        }

        if (designParams.hasSingleBoundaryConstr()) {
            singleBoundaryPartitionPlace();
        } else {
            ilpPartialPlace(designParams.getPrePlaceResTypes());
            genericPartitionPlace();
        }

        buildNode2IslandMap();
        buildEdge2BoundaryMap();
        printIslandPlacementResult();

        assert checkResUtils(checkResTypes);

        return node2IslandLoc;
    }

    private void genericPartitionPlace() {
        logger.info("Start generic partition-based placement");
        logger.newSubStep();

        List<Integer> node2XLoc = getXLocOfNodes();
        List<Integer> node2YLoc = getYLocOfNodes();

        // partition in the first dimension
        Map<Integer, Integer> fixedNodes = new HashMap<>();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            int xLoc = node2XLoc.get(nodeId);
            if (xLoc != -1) {
                fixedNodes.put(nodeId, xLoc);
            }
        }
        AbstractPartitioner partitioner = buildPartitioner(netlistGraph);
        partitioner.setFixedNodes(fixedNodes);
        node2XLoc = partitioner.run();
        updateXLocOfNodes(node2XLoc);


        // partition in the second dimension
        if (gridDim.getY() == 1) {
            updateYLocOfNodes(Collections.nCopies(netlistGraph.getNodeNum(), 0));
            return;
        }

        for (int x = 0; x < gridDim.getX(); x++) {
            node2YLoc = getYLocOfNodes();

            List<List<Integer>> partialNodes = new ArrayList<>();
            Map<Integer, Integer> fixedPartialNodes = new HashMap<>();
            List<List<Integer>> virtualNodeCls = new ArrayList<>();

            for (int y = 0; y < gridDim.getY(); y++) {
                virtualNodeCls.add(new ArrayList<>());
            }   

            for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
                int xLoc = node2XLoc.get(nodeId);
                int yLoc = node2YLoc.get(nodeId);

                if (xLoc == x) {
                    int partialNodeId = partialNodes.size();
                    partialNodes.add(Arrays.asList(nodeId));
                    if (yLoc != -1) {
                        fixedPartialNodes.put(partialNodeId, yLoc);
                    }
                } else {
                    if (yLoc != -1) {
                        virtualNodeCls.get(yLoc).add(nodeId);
                    }
                }
            }

            Set<Integer> virtualClsIds = new HashSet<>();
            for (int y = 0; y < gridDim.getY(); y++) {
                if (virtualNodeCls.get(y).size() > 0) {
                    int virtualClsId = partialNodes.size();
                    virtualClsIds.add(virtualClsId);
                    partialNodes.add(virtualNodeCls.get(y));
                    fixedPartialNodes.put(virtualClsId, y);
                }
            }

            
            HierHyperGraph subClsGraph = netlistGraph.createClusteredChildGraph(partialNodes, false);
            
            // disable virtual nodes and edges
            for (int clsId : virtualClsIds) {
                subClsGraph.setNodeWeights(clsId, Arrays.asList(0.0));
            }
            for (int edgeId = 0; edgeId < subClsGraph.getEdgeNum(); edgeId++) {
                Set<Integer> nodeIds = new HashSet<>(subClsGraph.getNodesOfEdge(edgeId));
                if (nodeIds.containsAll(virtualClsIds) && nodeIds.size() == virtualClsIds.size()) {
                    subClsGraph.setEdgeWeights(edgeId, Arrays.asList(0.0));
                }
            }

            partitioner = buildPartitioner(subClsGraph);
            partitioner.setFixedNodes(fixedPartialNodes);
            logger.info("Fixed Node Constraints: " + fixedPartialNodes);
            List<Integer> subPartResult = partitioner.run();
            subClsGraph.updatePartResultOfParent(subPartResult, node2YLoc);
            updateYLocOfNodes(node2YLoc);
        }

        logger.endSubStep();
        logger.info("Complete generic partition-based placement");
    }

    private void singleBoundaryPartitionPlace() {
        logger.info("Start partition-based placement with single boundary constraint");
        logger.newSubStep();

        // iterative partition placement dont support pre-placement now
        List<Integer> node2XLoc = getXLocOfNodes();
        List<Integer> node2YLoc = getYLocOfNodes();
        assert node2YLoc.stream().allMatch(y -> y == -1) && node2XLoc.stream().allMatch(x -> x == -1);

        // partition in the first dimension
        AbstractPartitioner partitioner = buildPartitioner(netlistGraph);
        Map<Integer, Integer> fixedNodes = new HashMap<>();
        partitioner.setFixedNodes(fixedNodes);
        node2XLoc = partitioner.run();
        updateXLocOfNodes(node2XLoc);
        
        // cluster nodes of cut edges
        List<List<Integer>> cluster2Nodes = clusterNodesOfCutEdges(netlistGraph, node2XLoc);

        //partition in the second dimension
        for (int x = 0; x < gridDim.getX(); x++) {
            node2YLoc = getYLocOfNodes();
            Map<Integer, Integer> fixedClusters = getFixedClusters(cluster2Nodes, node2YLoc);

            List<List<Integer>> subCluster2Nodes = new ArrayList<>();
            Map<Integer, Integer> fixedSubClusters = new HashMap<>();

            for (int clsId = 0; clsId < cluster2Nodes.size(); clsId++) {
                List<Integer> cluster = cluster2Nodes.get(clsId);
                List<Integer> subCluster = new ArrayList<>();
                for (int nodeId : cluster) {
                    int xLoc = node2XLoc.get(nodeId);
                    assert xLoc >= 0 && xLoc < gridDim.getX();
                    if (xLoc == x) {
                        subCluster.add(nodeId);
                    }
                }
                if (subCluster.size() > 0) {
                    if (fixedClusters.containsKey(clsId)) {
                        int fixedLoc = fixedClusters.get(clsId);
                        fixedSubClusters.put(subCluster2Nodes.size(), fixedLoc);
                    }
                    subCluster2Nodes.add(subCluster);
                }
            }
            
            HierHyperGraph subClsGraph = netlistGraph.createClusteredChildGraph(subCluster2Nodes, false);

            partitioner = buildPartitioner(subClsGraph);
            partitioner.setFixedNodes(fixedSubClusters);
            logger.info("Fixed Node Constraints: " + fixedSubClusters);
            List<Integer> subPartResult = partitioner.run();
            subClsGraph.updatePartResultOfParent(subPartResult, node2YLoc);
            updateYLocOfNodes(node2YLoc);
        }

        logger.endSubStep();
        logger.info("Complete partition-based placement with single boundary constraint");
    }

    private void ilpPartialPlace(Set<String> criticalResTypes) {
        if (criticalResTypes.size() == 0) return;
        logger.info("Start pre-placement of nodes with critical resources");
        logger.newSubStep();

        // construct partial graph
        logger.info("Construct partial hypergraph for pre-placement");
        Set<Integer> criticalNodes = new HashSet<>();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            Map<String, Integer> resUtils = abstractNetlist.getResUtilOfNode(nodeId);
            boolean isCriticalNode = false;
            for (String resType : criticalResTypes) {
                if (resUtils.containsKey(resType) && resUtils.get(resType) > 0) {
                    isCriticalNode = true;
                    break;
                }
            }
            if (isCriticalNode) {
                criticalNodes.add(nodeId);
            }
        }

        logger.info(String.format("Total number of critical nodes: %d (Maximum: %d)", criticalNodes.size(), maxPartialNetlistSize));
        List<Integer> periNodes = new ArrayList<>();
        if (criticalNodes.size() < maxPartialNetlistSize) {
            Map<Integer, Double> nodes2Connectivity = new HashMap<>();

            for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
                if (criticalNodes.contains(nodeId)) continue;
                Double connectivity = 0.0;
                for (int edgeId : netlistGraph.getEdgesOfNode(nodeId)) {
                    int criticalNodeNum = 0;
                    for (int nNodeId : netlistGraph.getNodesOfEdge(edgeId)) {
                        if (criticalNodes.contains(nNodeId)) {
                            criticalNodeNum++;
                        }
                    }
                    connectivity += criticalNodeNum * netlistGraph.getEdgeWeightsSum(edgeId);
                }
                nodes2Connectivity.put(nodeId, connectivity);
            }
            
            List<Integer> sortedPeriNodes = nodes2Connectivity.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
            int extraNodeNum = maxPartialNetlistSize - criticalNodes.size();
            periNodes = sortedPeriNodes.subList(0, extraNodeNum);
            logger.info("Total number of added peripheral nodes: " + periNodes.size());
            // for (int nodeId : periNodes) {
            //     logger.info(" Node-" + nodeId + " Connectivity: " + nodes2Connectivity.get(nodeId));
            // }
        }

        List<List<Integer>> partialNodes = new ArrayList<>();
        for (int nodeId : criticalNodes) {
            partialNodes.add(Arrays.asList(nodeId));
        }
        for (int nodeId : periNodes) {
            partialNodes.add(Arrays.asList(nodeId));
        }
        HierHyperGraph partialGraph = netlistGraph.createClusteredChildGraph(partialNodes, false);

        // update node weights of partial graph
        partialGraph.setNodeWeightsFactor(Collections.nCopies(criticalResTypes.size(), 1.0));
        for (int nodeId = 0; nodeId < partialGraph.getNodeNum(); nodeId++) {
            int parentId = partialGraph.getParentsOfNode(nodeId).get(0);
            List<Double> weights = new ArrayList<>();
            Map<String, Integer> resUtil = abstractNetlist.getResUtilOfNode(parentId);
            for (String resType : criticalResTypes) {
                if (resUtil.containsKey(resType)) {
                    weights.add((double) resUtil.get(resType));
                } else {
                    weights.add(0.0);
                }
            }
            partialGraph.setNodeWeights(nodeId, weights);
        }
        logger.info("Partial Graph Info:\n" + partialGraph.getHyperGraphInfo(true), true);

        // run ILP solver
        ILPIslandPartitioner.Config ilpPartCfg = new ILPIslandPartitioner.Config();
        if (designParams.hasSingleBoundaryConstr()) {
            ilpPartCfg.maxEdgeLen = 1;
        } else {
            ilpPartCfg.maxEdgeLen = gridDim.getX() + gridDim.getY() - 2;
        }

        ilpPartCfg.compressGraph = true;
        ilpPartCfg.gridDim = gridDim;
        ilpPartCfg.gridLimits = new ArrayList<>();
        List<Double> maxNodeWeight = VecOps.mulScalar(partialGraph.getMaxNodeWeight(), 1.05);

        gridDim.traverse((Coordinate2D loc) -> {
            List<Double> limits = new ArrayList<>();
            for (String resType : criticalResTypes) {
                limits.add((double) designParams.getGridLimit(resType, loc));
            }
            limits = VecOps.getMax(limits, maxNodeWeight);
            ilpPartCfg.gridLimits.add(limits);
        });

        ILPIslandPartitioner ilpPlacer = new ILPIslandPartitioner(logger, ilpPartCfg, partialGraph);
        List<Coordinate2D> placeResults = ilpPlacer.run();

        for (int nodeId = 0; nodeId < criticalNodes.size(); nodeId++) {
            int parentId = partialGraph.getParentsOfNode(nodeId).get(0);
            Coordinate2D loc = placeResults.get(nodeId);
            node2IslandLoc.set(parentId, loc);
        }

        logger.endSubStep();
        logger.info("Complete pre-placement of nodes with critical resources");
    }

    private boolean checkResUtils(Set<String> resTypes) {
        List<String> illegalResTypes = new ArrayList<>();
        for (String resType : checkResTypes) {
            if (!checkResUtils(resType)) {
                illegalResTypes.add(resType);
            }
        }
        logger.info("Illegal Resource Types:" + illegalResTypes);
        return illegalResTypes.size() == 0;
    }

    private boolean checkResUtils(String resType) {
        logger.info("Start checking utilization of " + resType);

        Integer resUtils [][] = new Integer[gridDim.getX()][gridDim.getY()];
        boolean singleExtremeNode [][] = new boolean[gridDim.getX()][gridDim.getY()];

        gridDim.traverse((Coordinate2D loc) -> {
            int utils = 0;
            for (int nodeId : island2Nodes[loc.getX()][loc.getY()]) {
                Map<String, Integer> resUtil = abstractNetlist.getResUtilOfNode(nodeId);
                if (resUtil.containsKey(resType)) {
                    int limit = designParams.getGridLimit(resType, loc);
                    if (resUtil.get(resType) > limit) {
                        singleExtremeNode[loc.getX()][loc.getY()] = true;
                    }
                    utils += resUtil.get(resType);
                }
            }
            resUtils[loc.getX()][loc.getY()] = utils;
        });

        boolean hasOverflow = false;
        logger.info("Distribution of " + resType + ":");
        for (int y = gridDim.getY() - 1; y >= 0; y--) {
            String utilsStr = "";

            for (int x = 0; x < gridDim.getX(); x++) {
                int limit = designParams.getGridLimit(resType, Coordinate2D.of(x, y));
                utilsStr += String.format("%d(%d)  ", resUtils[x][y], limit);

                if (singleExtremeNode[x][y]) continue; // skip single extreme node
                if (resUtils[x][y] > limit + 30) { //
                    hasOverflow = true;
                }
            }
            logger.info(utilsStr);
        }

        if (hasOverflow) {
            logger.info("Utilization of " + resType + " exceeds the limit");
        }
        logger.info("Complete checking utilization of " + resType);
        return !hasOverflow;
    }

    private AbstractPartitioner buildPartitioner(HierHyperGraph graph) {
        AbstractPartitioner partitioner;

        List<Double> imbFactors = Collections.nCopies(graph.getNodeWeightDim(), designParams.getImbalanceFac());

        switch (designParams.getPartitionKernel()) {
            case TRITON: {
                TritonPartitionWrapper.Config config = new TritonPartitionWrapper.Config();
                config.openroadCmd = designParams.getOpenroadCmd();
                config.workDir = dirManager.getSubDir("triton_part");
                config.randomSeed = designParams.getRandomSeed();
                config.imbFactors = imbFactors;
                logger.info("Imbalance Factors: " + imbFactors.toString());
                logger.info("Random Seed: " + config.randomSeed);
                partitioner = new TritonPartitionWrapper(logger, config, graph);
                break;
            }

            case CUSTOM: {
                MultiLevelPartitioner.Config config = new MultiLevelPartitioner.Config();
                config.imbFactors = imbFactors;
                config.randomSeed = designParams.getRandomSeed();
                config.parallelRunNum = designParams.getParallelRunNum();
                Coarser.Config coarserCfg = config.coarserConfig;
                coarserCfg.levelShrinkRatio = designParams.getCoarserLevelShrinkRatio();
                coarserCfg.maxNodeSizeRatio = designParams.getCoarserMaxNodeSizeRatio();

                partitioner = new MultiLevelPartitioner(logger, config, graph);
                break;
            }
            
            case FM: {
                FMPartitioner.Config config = new FMPartitioner.Config();
                config.randomSeed = designParams.getRandomSeed();
                config.imbFactors = imbFactors;

                partitioner = new FMPartitioner(logger, config, graph);
                break;
            }
        
            default:
                partitioner = null;
                break;
        }
        return partitioner;
    }

    private List<List<Integer>> clusterNodesOfCutEdges(HyperGraph netlistGraph, List<Integer> partResult) {
        logger.info("Start clustering nodes of cut edges");
        List<List<Integer>> cluster2Nodes = new ArrayList<>();
        Map<Integer, Integer> node2Cluster = new HashMap<>();

        Set<Integer> visitedEdges = new HashSet<>();
        for (int edgeId = 0; edgeId < netlistGraph.getEdgeNum(); edgeId++) {
            if (!netlistGraph.isCutEdge(edgeId, partResult)) {
                visitedEdges.add(edgeId);
            }
        }

        int moreThanOneNodeClsNum = 0;
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            if (node2Cluster.containsKey(nodeId)) {
                continue;
            }

            int clusterId = cluster2Nodes.size();
            Set<Integer> clsNodes = new HashSet<>();

            Queue<Integer> searchNodeQueue = new LinkedList<>();
            clsNodes.add(nodeId);
            node2Cluster.put(nodeId, clusterId);
            searchNodeQueue.add(nodeId);

            while (!searchNodeQueue.isEmpty()) {
                Integer curNodeId = searchNodeQueue.poll();

                for (int edgeId : netlistGraph.getEdgesOfNode(curNodeId)) {
                    if (visitedEdges.contains(edgeId)) continue;
                    for (int searchNodeId : netlistGraph.getNodesOfEdge(edgeId)) {
                        if (node2Cluster.containsKey(searchNodeId)) continue;

                        clsNodes.add(searchNodeId);
                        node2Cluster.put(searchNodeId, clusterId);
                        searchNodeQueue.add(searchNodeId);
                    }
                    visitedEdges.add(edgeId);
                }
            }
            cluster2Nodes.add(new ArrayList<>(clsNodes));
            if (clsNodes.size() > 1) {
                moreThanOneNodeClsNum++;
            }
        }

        logger.info("Total number of nodes before clustering: " + netlistGraph.getNodeNum());
        logger.info("Total number of clusters: " + cluster2Nodes.size());
        logger.info("Total number of clusters with more than one node: " + moreThanOneNodeClsNum);
        logger.info("Complete clustering nodes of cut edges");
        return cluster2Nodes;
    }

    private Map<Integer, Integer> getFixedClusters(List<List<Integer>> clusters, List<Integer> placeResults) {
        Map<Integer, Integer> locConstr = new HashMap<>();

        for (int clsId = 0; clsId < clusters.size(); clsId++) {
            int prePlacedLoc = -1;
            for (int nodeId : clusters.get(clsId)) {
                int loc = placeResults.get(nodeId);
                if (loc != -1) {
                    assert prePlacedLoc == -1 || prePlacedLoc == loc: prePlacedLoc + " " + loc;
                    prePlacedLoc = loc;
                }
            }

            if (prePlacedLoc != -1) {
                locConstr.put(clsId, prePlacedLoc);
            }
        }

        return locConstr;
    }

    private HierHyperGraph convertNetlist2HyperGraph(AbstractNetlist netlist) {
        logger.info("Start converting abstract netlist to hypergraph");
        logger.newSubStep();
        Set<String> weightResTypes = new HashSet<>(Arrays.asList("FF", "LUT", "LUTM"));
        HierHyperGraph netlistGraph = new HierHyperGraph(Arrays.asList(1.0), Arrays.asList(1.0));
        
        for (int nodeId = 0; nodeId < netlist.getNodeNum(); nodeId++) {
            Map<String, Integer> resUtils = abstractNetlist.getResUtilOfNode(nodeId);
            double weight = 0.0;
            for (String resType : weightResTypes) {
                if (resUtils.containsKey(resType)) {
                    weight += resUtils.get(resType);
                }
            }
            netlistGraph.addNode(Arrays.asList((double) weight));
            //netlistGraph.addNode(Arrays.asList((double) abstractNetlist.getLeafCellNumOfNode(nodeId)));

            // Map<String, Integer> resUtils = netlist.getResUtilOfNode(nodeId);
            // if (resUtils.containsKey("DSP") || resUtils.containsKey("BRAM")) {
            //     logger.info(resUtils.toString());
            // }
        }

        Map<Set<Integer>, List<Integer>> compressedEdges = compressAbstractEdges(abstractNetlist.edge2NodeIds);
        for (Map.Entry<Set<Integer>, List<Integer>> entry : compressedEdges.entrySet()) {

            Set<Integer> nodeIds = entry.getKey();
            List<Integer> originEdgeIds = entry.getValue();

            int edgeDegree = nodeIds.size();

            if (edgeDegree <= designParams.getIgnoreEdgeDegree()) {
                List<Double> weights = Arrays.asList((double) originEdgeIds.size());
                netlistGraph.addEdge(nodeIds, weights);

            } else {
                for (int edgeId : originEdgeIds) {
                    logger.info("Splitting hyperedge-" + edgeId + " with degree-" + edgeDegree);
                    int srcNodeId = abstractNetlist.getSrcNodeIdOfEdge(edgeId);

                    for (int nodeId : nodeIds) {
                        
                        if (nodeId != srcNodeId && !netlistGraph.hasConnection(nodeId, srcNodeId)) {
                            List<Double> weights = Arrays.asList(0.0);
                            netlistGraph.addEdge(new HashSet<>(Arrays.asList(nodeId, srcNodeId)), weights);
                        }
                    }

                }
            }
        }

        logger.endSubStep();
        logger.info("Complete converting abstract netlist to hypergraph");
        return netlistGraph;
    }

    private void buildNode2IslandMap() {
        island2Nodes = new ArrayList[gridDim.getX()][gridDim.getY()];
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                island2Nodes[x][y] = new ArrayList<>();
            }
        }

        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            Coordinate2D loc = getLocOfNode(nodeId);
            island2Nodes[loc.getX()][loc.getY()].add(nodeId);
        }
    }

    private void buildEdge2BoundaryMap() {
        horiBoundary2Edges = new ArrayList[horiBoundaryDim.getX()][horiBoundaryDim.getY()];
        horiBoundaryDim.traverse((Coordinate2D loc) -> {
            horiBoundary2Edges[loc.getX()][loc.getY()] = new ArrayList<>();
        });
        
        vertBoundary2Edges = new ArrayList[vertBoundaryDim.getX()][vertBoundaryDim.getY()];
        vertBoundaryDim.traverse((Coordinate2D loc) -> {
            vertBoundary2Edges[loc.getX()][loc.getY()] = new ArrayList<>();
        });

        edgeLengths = new ArrayList<>();

        for (int edgeId = 0; edgeId < netlistGraph.getEdgeNum(); edgeId++) {
            List<Integer> nodeIds = netlistGraph.getNodesOfEdge(edgeId);
            Set<Coordinate2D> incidentNodeLocs = new HashSet<>();
            for (Integer nodeId : nodeIds) {
                incidentNodeLocs.add(getLocOfNode(nodeId));
            }

            int edgeLen = Coordinate2D.getHPWL(incidentNodeLocs);
            boolean hasSingleBoundConstr = designParams.hasSingleBoundaryConstr();
            assert !hasSingleBoundConstr || (hasSingleBoundConstr && edgeLen <= 1);
            edgeLengths.add(edgeLen);

            if (edgeLen == 1) {
                assert incidentNodeLocs.size() == 2;
                Iterator<Coordinate2D> iter = incidentNodeLocs.iterator();
                Coordinate2D loc0 = iter.next();
                Coordinate2D loc1 = iter.next();
    
                Integer xDist = loc0.getDistX(loc1);

                if (xDist == 1) {
                    Integer boundaryX = Math.min(loc0.getX(), loc1.getX());
                    Integer boundaryY = loc0.getY();
                    vertBoundary2Edges[boundaryX][boundaryY].add(edgeId);
                } else {
                    Integer boundaryX = loc0.getX();
                    Integer boundaryY = Math.min(loc0.getY(), loc1.getY());
                    horiBoundary2Edges[boundaryX][boundaryY].add(edgeId);
                }
            }
        }
    }

    private void printIslandPlacementResult() {
        logger.info("Statistics of island placement results:");
        logger.newSubStep();
        logger.info("Distribution of node weight:");

        List<Double>[][] nodeWeightDist = getWeightsDist(island2Nodes, netlistGraph::getWeightsOfNode);

        List<List<Double>> islandSizes = new ArrayList<>();
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                islandSizes.add(nodeWeightDist[x][y]);
            }
        }

        printWeightDist(nodeWeightDist, gridDim);

        List<Double> avgIslandSize = StatisticsUtils.getMean(islandSizes, 0);
        logger.info("Avg island size in each dim: " + avgIslandSize.toString());

        List<Double> islandSizeStdVar = StatisticsUtils.getStandardVariance(islandSizes, 0);
        logger.info("Std var of island sizes in each dim: " + islandSizeStdVar.toString());

        List<Double> maxIslandSize = StatisticsUtils.getMax(islandSizes, 0);
        logger.info("Max island size in each dim: " + maxIslandSize.toString());

        List<Double> maxIslandSizeImbRatio = StatisticsUtils.getImbalanceRatio(maxIslandSize, islandSizes);
        logger.info("Max Imb ratio of island size in each dim: " + maxIslandSizeImbRatio.toString());

        logger.info("Distribution of horizontal single-cut edges:");
        List<Double>[][] horiBoundarySizeDist = getWeightsDist(horiBoundary2Edges, netlistGraph::getWeightsOfEdge);
        printWeightDist(horiBoundarySizeDist, horiBoundaryDim);

        logger.info("Distribution of vertical single-cut edges:");
        List<Double>[][] vertBoundarySizeDist = getWeightsDist(vertBoundary2Edges, netlistGraph::getWeightsOfEdge);
        printWeightDist(vertBoundarySizeDist, vertBoundaryDim);

        List<List<Double>> boundarySizes = new ArrayList<>();
        for (int x = 0; x < horiBoundaryDim.getX(); x++) {
            for (int y = 0; y < horiBoundaryDim.getY(); y++) {
                boundarySizes.add(horiBoundarySizeDist[x][y]);
            }
        }

        for (int x = 0; x < vertBoundaryDim.getX(); x++) {
            for (int y = 0; y < vertBoundaryDim.getY(); y++) {
                boundarySizes.add(vertBoundarySizeDist[x][y]);
            }
        }

        List<Double> avgBoundarySize = StatisticsUtils.getMean(boundarySizes, 0);
        logger.info("Avg boundary cut size: " + avgBoundarySize.toString());
        List<Double> boundarySizeStdVar = StatisticsUtils.getStandardVariance(boundarySizes, 0);
        logger.info("Std var of boundary cut size: " + boundarySizeStdVar.toString());
        List<Double> maxBoundarySize = StatisticsUtils.getMax(boundarySizes, 0);
        logger.info("Max boundary cut size: " + maxBoundarySize.toString());
        
        int totalEdgeLen = 0;
        Map<Integer, Integer> len2EdgeWeight = new HashMap<>();
        for (int edgeId = 0; edgeId < netlistGraph.getEdgeNum(); edgeId++) {
            int edgeWeight = netlistGraph.getWeightsOfEdge(edgeId).get(0).intValue();
            int edgeLen = edgeLengths.get(edgeId);
            totalEdgeLen += edgeLen * edgeWeight;
            if (len2EdgeWeight.containsKey(edgeLen)) {
                len2EdgeWeight.put(edgeLen, len2EdgeWeight.get(edgeLen) + edgeWeight);
            } else {
                len2EdgeWeight.put(edgeLen, edgeWeight);
            }
        }
        logger.info("Total length of edges: " + totalEdgeLen);
        logger.info("Distribution of edge length:");
        for (Map.Entry<Integer, Integer> entry : len2EdgeWeight.entrySet()) {
            logger.info(entry.getKey() + ": " + entry.getValue());
        }
        
        logger.endSubStep();
    }

    private void printWeightDist(List<Double>[][] weightDist, Coordinate2D dim) {
        for (int y = dim.getY() - 1; y >= 0; y--) {
            String weightDistStr = "";
            for (int x = 0; x < dim.getX(); x++) {
                weightDistStr += weightDist[x][y].toString() + "  ";
            }
            logger.info(weightDistStr);
        }
    }

    private Coordinate2D getLocOfNode(int nodeId) {
        return node2IslandLoc.get(nodeId);
    }

    private Integer getXLocOfNode(int nodeId) {
        return node2IslandLoc.get(nodeId).getX();
    }

    private Integer getYLocOfNode(int nodeId) {
        return node2IslandLoc.get(nodeId).getY();
    }

    private List<Integer> getXLocOfNodes() {
        return node2IslandLoc.stream().map(loc -> loc.getX()).collect(Collectors.toList());
    }

    private List<Integer> getYLocOfNodes() {
        return node2IslandLoc.stream().map(loc -> loc.getY()).collect(Collectors.toList());
    }

    private void updateXLocOfNodes(List<Integer> xLocs) {
        assert xLocs.size() == netlistGraph.getNodeNum();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            Coordinate2D nodeLoc = node2IslandLoc.get(nodeId);
            int xLoc = xLocs.get(nodeId);
            assert nodeLoc.getX() == -1 || nodeLoc.getX() == xLoc;
            nodeLoc.setX(xLoc);
        }
    }

    private void updateYLocOfNodes(List<Integer> yLocs) {
        assert yLocs.size() == netlistGraph.getNodeNum();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            Coordinate2D nodeLoc = node2IslandLoc.get(nodeId);
            int yLoc = yLocs.get(nodeId);
            assert nodeLoc.getY() == -1 || nodeLoc.getY() == yLoc;
            nodeLoc.setY(yLoc);
        }
    }

    private List<Double>[][] getWeightsDist(List<Integer>[][] grid, Function<Integer, List<Double>> getWeight) {
        List<Double>[][] weightDist = new ArrayList[grid.length][];

        Integer weightDim = null;
        for (int x = 0; x < grid.length; x++) {
            weightDist[x] = new ArrayList[grid[x].length];

            for (int y = 0; y < grid[x].length; y++) {
                
                for (Integer elem : grid[x][y]) {
                    if (weightDist[x][y] == null) {
                        weightDist[x][y] = new ArrayList<>(getWeight.apply(elem));
                    } else {
                        HyperGraph.accuWeights(weightDist[x][y], getWeight.apply(elem));
                    }

                    weightDim = weightDist[x][y].size();
                }
            }
        }

        for (int x = 0; x < grid.length; x++) {
            for (int y = 0; y < grid[x].length; y++) {
                if (weightDist[x][y] == null) {
                    weightDist[x][y] = new ArrayList<>(Collections.nCopies(weightDim, 0.0));
                }
            }
        }
        return weightDist;
    }
}