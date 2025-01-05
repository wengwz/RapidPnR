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

public class IslandPlacer3 extends AbstractIslandPlacer {

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
    
    public IslandPlacer3(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams designParams) {
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

        if (gridDim.getY() == 1) {
            this.node2IslandLoc = biPartitionPlace();
        } else {
            this.node2IslandLoc = iterativePartitionPlace();
        }

        assert checkResUtils(checkResTypes);

        return node2IslandLoc;
    }

    private List<Coordinate2D> biPartitionPlace() {
        ilpPrePlace(designParams.getPrePlaceResTypes());

        logger.info("Start bi-partition placement");
        assert gridDim.getY() == 1: "Bi-partition placement only supports 1-D grid";

        List<Integer> node2YLoc = getYLocOfNodes();
        assert node2YLoc.stream().allMatch(y -> (y == -1) || (y == 0));

        AbstractPartitioner partitioner = buildPartitioner(netlistGraph);
        Map<Integer, Integer> fixedNodes = new HashMap<>();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            int xLoc = getXLocOfNode(nodeId);
            if (xLoc != -1) {
                fixedNodes.put(nodeId, xLoc);
            }
        }
        partitioner.setFixedNodes(fixedNodes);
        List<Integer> partResult = partitioner.run();

        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            Coordinate2D loc = node2IslandLoc.get(nodeId);
            loc.setX(partResult.get(nodeId));
            loc.setY(0);
        }
        
        // Map<Integer, Integer> cutEdgeDegree2NumMap = new HashMap<>();
        // for (int edgeId = 0; edgeId < netlistGraph.getEdgeNum(); edgeId++) {
        //     Set<Integer> blkIds = new HashSet<>();
        //     for (int nodeId : netlistGraph.getNodesOfEdge(edgeId)) {
        //         int blkId = partResult.get(nodeId);
        //         assert blkId != -1;
        //         blkIds.add(blkId);
        //     }
        //     int degree = blkIds.size();
        //     if (degree == 1) continue;
        //     if (!cutEdgeDegree2NumMap.containsKey(degree)) {
        //         cutEdgeDegree2NumMap.put(degree, 0);
        //     }
        //     cutEdgeDegree2NumMap.put(degree, cutEdgeDegree2NumMap.get(degree) + 1);
        // }

        // logger.info("Cut edge degree distribution: " + cutEdgeDegree2NumMap.toString());

        buildNode2IslandMap();
        buildEdge2BoundaryMap();
        printIslandPlacementResult();
        logger.info("Complete bi-partition placement");

        return node2IslandLoc;
    }

    private List<Coordinate2D> iterativePartitionPlace() {
        logger.info("Start iterative min-cut placement");
        logger.newSubStep();

        // iterative partition placement dont support pre-placement now
        List<Integer> node2XLoc = getXLocOfNodes();
        List<Integer> node2YLoc = getYLocOfNodes();
        assert node2YLoc.stream().allMatch(y -> y == -1) && node2XLoc.stream().allMatch(x -> x == -1);

        AbstractPartitioner partitioner;
        // partition in the first dimension
        Set<String> prePlaceResTypes = designParams.getPrePlaceResTypes();
        Map<Integer, Integer> fixedNodes = new HashMap<>();
        
        if (prePlaceResTypes.size() > 0) {
            HierHyperGraph partialGraph = buildPartialGraphOfRes(prePlaceResTypes, true);
            List<List<Double>> gridLimits = new ArrayList<>();
            for (int x = 0; x < gridDim.getX(); x++) {
                List<Double> acculimits = new ArrayList<>(Collections.nCopies(prePlaceResTypes.size(), 0.0));
                for (int y = 0; y < gridDim.getY(); y++) {
                    List<Double> limits = new ArrayList<>();
                    for (String resType : prePlaceResTypes) {
                        limits.add((double) designParams.getGridLimit(resType, Coordinate2D.of(x, y)));
                    }
                    VecOps.accu(acculimits, limits);                 
                }
                gridLimits.add(acculimits);
            }
            List<Integer> partialPartRes = ilpBiPartPrePlace(partialGraph, gridLimits);
            partialGraph.updatePartResultOfParent(partialPartRes, node2XLoc);
            
            for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
                int xLoc = node2XLoc.get(nodeId);
                if (xLoc != -1) {
                    fixedNodes.put(nodeId, xLoc);
                }
            }
        }

        partitioner = buildPartitioner(netlistGraph);
        partitioner.setFixedNodes(fixedNodes);
        node2XLoc = partitioner.run();
        updateXLocOfNodes(node2XLoc);

        // Coarser.Config config = new Coarser.Config();
        // config.scheme = Coarser.Scheme.HEC;
        // config.maxNodeSizeRatio = 0.3;
        // HierHyperGraph clsGraph = Coarser.coarsening(config, netlistGraph);
        // logger.info(clsGraph.getHyperGraphInfo(false));
        // partitioner = buildPartitioner(clsGraph);
        // List<Integer> clsPartResult = partitioner.run();
        // clsGraph.updatePartResultOfParent(clsPartResult, node2XLoc);
        // updateXLocOfNodes(node2XLoc);

        // if (node2XLoc.stream().allMatch(x -> x == -1)) {
        //     partitioner = buildPartitioner(netlistGraph);
        //     node2XLoc = partitioner.run();
        // } else {
        //     List<List<Integer>> clusters = new ArrayList<>();
        //     for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
        //         clusters.add(Arrays.asList(nodeId));
        //     }
        //     Map<Integer, Integer> fixedClusters = getFixedClusters(clusters, node2XLoc);
        //     HierHyperGraph clsGraph = netlistGraph.createClusteredChildGraph(clusters, false);
        //     partitioner = buildPartitioner(clsGraph);
        //     partitioner.setFixedNodes(fixedClusters);
        //     List<Integer> clsPartRes = partitioner.run();
        //     clsGraph.updatePartResultOfParent(clsPartRes, node2XLoc);
        // }

        // update node2IslandLoc
        // for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
        //     Coordinate2D nodeLoc = node2IslandLoc.get(nodeId);
        //     int xLoc = node2XLoc.get(nodeId);
        //     assert nodeLoc.getX() == -1 || nodeLoc.getX() == xLoc;
        //     nodeLoc.setX(xLoc);
        // }
        
        // cluster nodes of cut edges
        List<List<Integer>> cluster2Nodes = clusterNodesOfCutEdges(netlistGraph, node2XLoc);
        // HierHyperGraph clsGraph = netlistGraph.createClusteredChildGraph(cluster2Nodes, false);
        // partitioner = buildPartitioner(clsGraph);
        // List<Integer> clsPartResult = partitioner.run();
        // node2XLoc = clsGraph.getPartResultOfParent(clsPartResult);
        // cluster2Nodes = clusterNodesOfCutEdges(netlistGraph, node2XLoc);
        // updateXLocOfNodes(node2XLoc);

        // partition in the second dimension
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

            for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
                int yLoc = node2YLoc.get(nodeId);
                Coordinate2D nodeLoc = node2IslandLoc.get(nodeId);
                assert nodeLoc.getY() == -1 || yLoc == nodeLoc.getY();
                nodeLoc.setY(yLoc);
            }
        }

        buildNode2IslandMap();
        buildEdge2BoundaryMap();

        printIslandPlacementResult();

        logger.endSubStep();
        logger.info("Complete iterative min-cut placement");

        return node2IslandLoc;
    }

    private List<Integer> ilpBiPartPrePlace(HierHyperGraph partialGraph, List<List<Double>> gridLimits) {
        logger.info("Start ILP-based bi-partition pre-placement");
        // run ILP solver
        ILPIslandPartitioner.Config ilpPartCfg = new ILPIslandPartitioner.Config();
        ilpPartCfg.compressGraph = true;
        ilpPartCfg.gridDim = Coordinate2D.of(2, 1);
        ilpPartCfg.gridLimits = gridLimits;

        ILPIslandPartitioner ilpPlacer = new ILPIslandPartitioner(logger, ilpPartCfg, partialGraph);
        List<Coordinate2D> placeResults = ilpPlacer.run();
        partialGraph.updateLocOfParent(placeResults, node2IslandLoc);

        List<Integer> partResult = new ArrayList<>();
        for (int nodeId = 0; nodeId < partialGraph.getNodeNum(); nodeId++) {
            Coordinate2D loc = node2IslandLoc.get(nodeId);
            partResult.add(loc.getX());
        }
        logger.info("Complete ILP-based bi-partition pre-placement");
        return partResult;
    }

    private HierHyperGraph buildPartialGraphOfRes(Set<String> resTypes, boolean addPeriNodes) {
        logger.info("Construct partial hypergraph for pre-placement");

        Set<Integer> criticalNodes = new HashSet<>();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            Map<String, Integer> resUtils = abstractNetlist.getResUtilOfNode(nodeId);
            boolean isCriticalNode = false;
            for (String resType : resTypes) {
                if (resUtils.containsKey(resType) && resUtils.get(resType) > 0) {
                    isCriticalNode = true;
                    break;
                }
            }
            if (isCriticalNode) {
                criticalNodes.add(nodeId);
            }
        }

        if (addPeriNodes) {
            List<Integer> periNodes = new ArrayList<>();
            for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
                if (criticalNodes.contains(nodeId)) continue;
                Set<Integer> netWithCriticalNodes = new HashSet<>();
                for (int edgeId : netlistGraph.getEdgesOfNode(nodeId)) {
                    Set<Integer> criticalNodeIds = new HashSet<>();
                    for (int nNodeId : netlistGraph.getNodesOfEdge(edgeId)) {
                        if (criticalNodes.contains(nNodeId)) {
                            criticalNodeIds.add(nNodeId);
                        }
                    }
                    if (criticalNodeIds.size() > 0) {
                        netWithCriticalNodes.add(edgeId);
                    }
                }
                if (netWithCriticalNodes.size() > 3) {
                    periNodes.add(nodeId);
                }
            }
            // add peripheral nodes to critical nodes
            criticalNodes.addAll(periNodes);            
        }


        List<List<Integer>> partialNodes = criticalNodes.stream().map(Arrays::asList).collect(Collectors.toList());
        HierHyperGraph partialGraph = netlistGraph.createClusteredChildGraph(partialNodes, false);

        // update node weights of partial graph
        partialGraph.setNodeWeightsFactor(Collections.nCopies(resTypes.size(), 1.0));
        for (int nodeId = 0; nodeId < partialGraph.getNodeNum(); nodeId++) {
            int parentId = partialGraph.getParentsOfNode(nodeId).get(0);
            List<Double> weights = new ArrayList<>();
            Map<String, Integer> resUtil = abstractNetlist.getResUtilOfNode(parentId);
            for (String resType : resTypes) {
                if (resUtil.containsKey(resType)) {
                    weights.add((double) resUtil.get(resType));
                } else {
                    weights.add(0.0);
                }
            }
            partialGraph.setNodeWeights(nodeId, weights);
        }
        logger.info("Partial Graph Info:\n" + partialGraph.getHyperGraphInfo(true), true);

        return partialGraph;
    }

    private void ilpPrePlace(Set<String> criticalResTypes) {
        if (criticalResTypes.size() == 0) return;
        logger.info("Start pre-placement of critical nodes");
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

        List<Integer> periNodes = new ArrayList<>();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            if (criticalNodes.contains(nodeId)) continue;
            Set<Integer> netWithCriticalNodes = new HashSet<>();
            for (int edgeId : netlistGraph.getEdgesOfNode(nodeId)) {
                Set<Integer> criticalNodeIds = new HashSet<>();
                for (int nNodeId : netlistGraph.getNodesOfEdge(edgeId)) {
                    if (criticalNodes.contains(nNodeId)) {
                        criticalNodeIds.add(nNodeId);
                    }
                }
                if (criticalNodeIds.size() > 0) {
                    netWithCriticalNodes.add(edgeId);
                }
            }
            if (netWithCriticalNodes.size() > 3) {
                periNodes.add(nodeId);
            }
        }
        // add peripheral nodes to critical nodes
        criticalNodes.addAll(periNodes);

        List<List<Integer>> partialNodes = criticalNodes.stream().map(Arrays::asList).collect(Collectors.toList());
        HierHyperGraph partialGraph = netlistGraph.createClusteredChildGraph(partialNodes, false);
        logger.info("Total number of critical nodes: " + criticalNodes.size());

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
        partialGraph.updateLocOfParent(placeResults, node2IslandLoc);

        // update locations of nodes incident to cut edges
        for (int edgeId = 0; edgeId < netlistGraph.getEdgeNum(); edgeId++) {
            Set<Coordinate2D> nodeLocs = new HashSet<>();
            for (int nodeId : netlistGraph.getNodesOfEdge(edgeId)) {
                Coordinate2D loc = node2IslandLoc.get(nodeId);
                if (loc.getX() != -1 && loc.getY() != -1) {
                    nodeLocs.add(loc);
                }
            }

            if (nodeLocs.size() > 1) {
                Iterator<Coordinate2D> iter = nodeLocs.iterator();
                Coordinate2D loc1 = iter.next();
                Coordinate2D loc2 = iter.next();
                int xDist = loc1.getDistX(loc2);
                int yDist = loc1.getDistY(loc2);
                assert xDist + yDist == 1: loc1 + " " + loc2;
                if (xDist != 0) {
                    for (int nodeId : netlistGraph.getNodesOfEdge(edgeId)) {
                        node2IslandLoc.get(nodeId).setY(loc1.getY());
                    }
                } else {
                    for (int nodeId : netlistGraph.getNodesOfEdge(edgeId)) {
                        node2IslandLoc.get(nodeId).setX(loc1.getX());
                    }
                }
            }
        }

        logger.endSubStep();
        logger.info("Complete pre-placement of critical nodes");
    }

    // private List<Coordinate2D> legalization() {
    //     logger.info("Start legalization of inital placement");
    //     logger.newSubStep();
    //     Set<String> illegalResTypes = new HashSet<>();
    //     for (String resType : checkResTypes) {
    //         if (!checkResUtils(resType)) {
    //             illegalResTypes.add(resType);
    //         }
    //     }

    //     //assert illegalResTypes.size() == 0: "Resource overflow found";

    //     if (illegalResTypes.size() > 0) {
    //         logger.info("Start legalizing resource overflow of " + illegalResTypes.toString());
    //         List<Integer> criticalNodes = new ArrayList<>();

    //         for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
    //             Map<String, Integer> resUtils = abstractNetlist.getResUtilOfNode(nodeId);
    //             boolean isCriticalNode = false;
    //             for (String resType : illegalResTypes) {
    //                 if (resUtils.containsKey(resType) && resUtils.get(resType) > 0) {
    //                     isCriticalNode = true;
    //                     break;
    //                 }
    //             }
    //             if (isCriticalNode) {
    //                 criticalNodes.add(nodeId);
    //             }
    //         }
    //         logger.info("Total number of critical nodes: " + criticalNodes.size());

    //         List<List<Integer>> dist2Nodes = netlistGraph.getDistance2Nodes(criticalNodes, 0);
    //         List<List<Integer>> partialNodes = new ArrayList<>();
    //         Map<Coordinate2D, List<Integer>> loc2Nodes = new HashMap<>();
    //         for (int dist = 0; dist < dist2Nodes.size(); dist++) {
    //             for (int nodeId : dist2Nodes.get(dist)) {
    //                 if (dist == 0) {
    //                     partialNodes.add(Arrays.asList(nodeId));
    //                 } else {
    //                     Coordinate2D loc = node2IslandLoc.get(nodeId);
    //                     if (!loc2Nodes.containsKey(loc)) {
    //                         loc2Nodes.put(loc, new ArrayList<>());
    //                     }
    //                     loc2Nodes.get(loc).add(nodeId);
    //                 }
    //             }
    //         }

    //         Map<Integer, Coordinate2D> fixedNodes = new HashMap<>();
    //         for (Coordinate2D loc : loc2Nodes.keySet()) {
    //             fixedNodes.put(partialNodes.size(), loc);
    //             partialNodes.add(loc2Nodes.get(loc));
    //         }

    //         logger.info("Total number of partial nodes: " + partialNodes.size());

    //         HierHyperGraph partialGraph = netlistGraph.createClusteredChildGraph(partialNodes, false);
    //         // update node weights of partial graph
    //         partialGraph.setNodeWeightsFactor(Collections.nCopies(illegalResTypes.size(), 1.0));
    //         for (int nodeId = 0; nodeId < partialGraph.getNodeNum(); nodeId++) {
    //             int parentId = partialGraph.getParentsOfNode(nodeId).get(0);
    //             List<Double> weights = new ArrayList<>();

    //             for (String resType : illegalResTypes) {
    //                 Map<String, Integer> resUtil = abstractNetlist.getResUtilOfNode(parentId);
    //                 if (resUtil.containsKey(resType)) {
    //                     weights.add((double) resUtil.get(resType));
    //                 } else {
    //                     weights.add(0.0);
    //                 }
    //             }
    //             partialGraph.setNodeWeights(nodeId, weights);
    //         }
            
    //         logger.info("Num of fixed nodes: " + fixedNodes.size());
    //         HyperGraph compPartialGraph = partialGraph.getCompressedGraph();
    //         logger.info("Compressed Partial Graph Info:\n" + compPartialGraph.getHyperGraphInfo(true), true);
        
    //         ILPIslandPartitioner.Config ilpPartCfg = new ILPIslandPartitioner.Config();
    //         ilpPartCfg.gridDim = gridDim;
    //         ilpPartCfg.gridLimits = new ArrayList<>();
    //         gridDim.traverse((Coordinate2D loc) -> {
    //             List<Double> limits = new ArrayList<>();
    //             for (String resType : illegalResTypes) {
    //                 limits.add((double) designParams.getGridLimit(resType, loc));
    //             }
    //             ilpPartCfg.gridLimits.add(limits);
    //         });

    //         ILPIslandPartitioner ilpPart = new ILPIslandPartitioner(logger, ilpPartCfg, compPartialGraph);
    //         ilpPart.setFixedNodes(fixedNodes);
    //         List<Coordinate2D> ilpPartRes = ilpPart.run();
    //     }


    //     logger.endSubStep();
    //     logger.info("Complete legalization of inital placement");
    //     return node2IslandLoc;
    // }


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
                if (resUtils[x][y] > limit) {
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
        for (int x = 0; x < horiBoundaryDim.getX(); x++) {
            for (int y = 0; y < horiBoundaryDim.getY(); y++) {
                horiBoundary2Edges[x][y] = new ArrayList<>();
            }
        }

        vertBoundary2Edges = new ArrayList[vertBoundaryDim.getX()][vertBoundaryDim.getY()];
        for (int x = 0; x < vertBoundaryDim.getX(); x++) {
            for (int y = 0; y < vertBoundaryDim.getY(); y++) {
                vertBoundary2Edges[x][y] = new ArrayList<>();
            }
        }

        for (int edgeId = 0; edgeId < netlistGraph.getEdgeNum(); edgeId++) {
            List<Integer> nodeIds = netlistGraph.getNodesOfEdge(edgeId);
            Set<Coordinate2D> incidentNodeLocs = new HashSet<>();
            for (Integer nodeId : nodeIds) {
                incidentNodeLocs.add(getLocOfNode(nodeId));
            }

            assert incidentNodeLocs.size() <= 2;
            if (incidentNodeLocs.size() < 2) continue;

            Iterator<Coordinate2D> iter = incidentNodeLocs.iterator();
            Coordinate2D loc0 = iter.next();
            Coordinate2D loc1 = iter.next();

            Integer xDist = loc0.getDistX(loc1);
            Integer yDist = loc0.getDistY(loc1);
            assert xDist + yDist == 1: "dist=" + (xDist + yDist);

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

        logger.info("Distribution of horizontal boundary size:");
        List<Double>[][] horiBoundarySizeDist = getWeightsDist(horiBoundary2Edges, netlistGraph::getWeightsOfEdge);
        printWeightDist(horiBoundarySizeDist, horiBoundaryDim);

        logger.info("Distribution of vertical boundary size:");
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
            nodeLoc.setX(xLoc);
        }
    }

    private void updateYLocOfNodes(List<Integer> yLocs) {
        assert yLocs.size() == netlistGraph.getNodeNum();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            Coordinate2D nodeLoc = node2IslandLoc.get(nodeId);
            int yLoc = yLocs.get(nodeId);
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
