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
//import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.nio.file.Path;

public class IslandPlacer2 extends AbstractIslandPlacer {

    //
    HyperGraph netlistGraph;
    HyperGraph islandGraph;
    // place results
    private List<Coordinate2D> node2IslandLoc;
    private List<Integer>[][] island2Nodes;
    private List<Integer>[][] horiBoundary2Edges;
    private List<Integer>[][] vertBoundary2Edges;


    public IslandPlacer2(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams params) {
        super(logger, dirManager, params);
    }


    public List<Coordinate2D> run(AbstractNetlist abstractNetlist) {
        logger.info("Start running island placement");
        logger.newSubStep();
        this.abstractNetlist = abstractNetlist;
        this.netlistGraph = convertNetlist2HyperGraph(abstractNetlist);
        this.islandGraph = createIslandGraph();

        netlistGraph.setEdgeWeightsFactor(Arrays.asList(1.0));
        netlistGraph.setNodeWeightsFactor(Arrays.asList(1.0));

        initialPlace();

        refine();

        logger.endSubStep();
        logger.info("Complete running island placement");
        return null;
    }

    private void initialPlace() {
        logger.info("Start initial island placement");
        Path workDir = dirManager.addSubDir(NameConvention.islandPlacerDirName);

        // perform partition in the first dimension
        TritonPartitionWrapper partitioner = new TritonPartitionWrapper(logger, netlistGraph, workDir);
        List<Integer> partResultDim0 = partitioner.run();

        List<List<Integer>> cluster2Nodes = clusterNodesOfCutEdges(netlistGraph, partResultDim0);
        HyperGraph clsHyperGraph = netlistGraph.createClusteredHyperGraph(cluster2Nodes);

        logger.info("Number of nodes in clustered hypergraph: " + clsHyperGraph.getNodeNum());
        logger.info("Number of edges in clustered hypergraph: " + clsHyperGraph.getEdgeNum());

        TritonPartitionWrapper clsPartitioner = new TritonPartitionWrapper(logger, clsHyperGraph, workDir);
        List<Integer> clsPartResult = clsPartitioner.run();

        List<Integer> partResultDim1 = extractNodePartResultFromCluster(clsPartResult, cluster2Nodes);

        node2IslandLoc = new ArrayList<>();
        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            node2IslandLoc.add(Coordinate2D.of(partResultDim0.get(nodeId), partResultDim1.get(nodeId)));
        }

        buildNode2IslandMap();
        buildEdge2BoundaryMap();

        printIslandPlacementResult();

        logger.info("Complete initial island placement");
    }

    private void refine() {

        class PlaceStateTracker {

            List<Double> cutSize;
            List<List<Double>> island2NodeWeight;
            List<Double> nodeWeightStdVar;
    
            List<Integer> node2IslandId;
            List<Set<Integer>> node2CddtIslandIds;

            public PlaceStateTracker(List<Coordinate2D> initialPlaceResult) {
                cutSize = new ArrayList<>(Collections.nCopies(netlistGraph.getEdgeWeightDim(), 0.0));
                
                island2NodeWeight = new ArrayList<>();
                List<Integer> initialCddtIslandIds = new ArrayList<>();
                for (int i = 0; i < gridDim.getX() * gridDim.getY(); i++) {
                    initialCddtIslandIds.add(i);
                    island2NodeWeight.add(new ArrayList<>(Collections.nCopies(netlistGraph.getNodeWeightDim(), 0.0)));
                }

                node2IslandId = new ArrayList<>(Collections.nCopies(netlistGraph.getNodeNum(), -1));
                node2CddtIslandIds = new ArrayList<>(Collections.nCopies(netlistGraph.getNodeNum(), new HashSet<>(initialCddtIslandIds)));

                for (Integer nodeId = 0; nodeId < initialPlaceResult.size(); nodeId++) {
                    moveNode(nodeId, getIslandIndexFromLoc(initialPlaceResult.get(nodeId)));
                }

                //
                nodeWeightStdVar = StatisticsUtils.getStandardVariance(island2NodeWeight, 0);
            }

            public void moveNode(int nodeId, int islandId) {
                if (node2IslandId.get(nodeId) == islandId) {
                    return;
                }

                // update cut size
                HyperGraph.accuWeights(cutSize, getCutSizeIncr(nodeId, islandId));

                // update NodeWeight of islands
                int oldIslandId = node2IslandId.get(nodeId);
                if (oldIslandId != -1) {
                    HyperGraph.decWeights(island2NodeWeight.get(oldIslandId), netlistGraph.getWeightsOfNode(nodeId));
                }
                HyperGraph.accuWeights(island2NodeWeight.get(islandId), netlistGraph.getWeightsOfNode(nodeId));
                nodeWeightStdVar = StatisticsUtils.getStandardVariance(island2NodeWeight, 0);

                // update candidate Islands for neighbors
                int maxDist = gridDim.getX() + gridDim.getY() - 2;
                List<List<Integer>> dist2IslandIds = islandGraph.getDistance2Nodes(islandId, maxDist);

                List<List<Integer>> dist2NodeIds = netlistGraph.getDistance2Nodes(nodeId, maxDist);
                for (int dist = 1; dist < dist2IslandIds.size(); dist++) {
                    dist2IslandIds.get(dist).addAll(dist2IslandIds.get(dist - 1));
                    Set<Integer> cddtIslandIds = new HashSet<>(dist2IslandIds.get(dist));

                    if (dist >= dist2NodeIds.size()) continue;

                    for (Integer nNodeId : dist2NodeIds.get(dist)) {
                        Set<Integer> mergedCddt = mergeCddtIslandIds(node2CddtIslandIds.get(nNodeId), cddtIslandIds);
                        node2CddtIslandIds.set(nNodeId, mergedCddt);
                    }
                }

                //
                node2IslandId.set(nodeId, islandId);
            }

            public Double getMoveGain(int nodeId, int newIslandId) {
                int originIslandId = node2IslandId.get(nodeId);
                if (newIslandId == originIslandId) {
                    return 0.0;
                }

                double cutSizeGainW = 1.0;
                double nodeWeightVarGainW = 1.0;

                List<Double> cutSizeIncr = getCutSizeIncr(nodeId, newIslandId);

                List<List<Double>> newIsland2NodeWeight = new ArrayList<>();
                for (int i = 0; i < island2NodeWeight.size(); i++) {
                    newIsland2NodeWeight.add(new ArrayList<>(island2NodeWeight.get(i)));
                }

                HyperGraph.decWeights(newIsland2NodeWeight.get(originIslandId), netlistGraph.getWeightsOfNode(nodeId));
                HyperGraph.accuWeights(newIsland2NodeWeight.get(newIslandId), netlistGraph.getWeightsOfNode(nodeId));
                
                List<Double> newNodeWeightStdVar = StatisticsUtils.getStandardVariance(newIsland2NodeWeight, 0);
                HyperGraph.decWeights(newNodeWeightStdVar, nodeWeightStdVar);

                double cutSizeGain = -netlistGraph.getEdgeWeightsSum(cutSizeIncr);
                double weightStdVarGain = -netlistGraph.getNodeWeightsSum(newNodeWeightStdVar);
 
                double totalGain = cutSizeGainW * cutSizeGain + nodeWeightVarGainW * weightStdVarGain;

                //logger.info(String.format("Gain of moving node-%d to island-%d: cutSizeGain= %.2f weightVarGain=%.2f totalGain=%.2f", nodeId, newIslandId, cutSizeGain, weightStdVarGain, totalGain));

                return totalGain;
            }

            public List<Double> getCutSizeIncr(int nodeId, int islandId) {
                List<Double> oldNodeCutSize = netlistGraph.getCutSizeOfNode(node2IslandId, nodeId);
                Integer originIslandId = node2IslandId.get(nodeId);
                node2IslandId.set(nodeId, islandId);
                List<Double> newNodeCutSize = netlistGraph.getCutSizeOfNode(node2IslandId, nodeId);
                node2IslandId.set(nodeId, originIslandId);

                return HyperGraph.getWeightsDiff(newNodeCutSize, oldNodeCutSize);
            }

            public Set<Integer> mergeCddtIslandIds(Set<Integer> target, Set<Integer> source) {
                Set<Integer> merged = new HashSet<>();
                for (Integer islandId : target) {
                    if (source.contains(islandId)) {
                        merged.add(islandId);
                    }
                }
                return merged;
            }

            public List<Integer> generateRandomNumSeq(int maxNum) {
                List<Integer> numSeq = new ArrayList<>();
                for (int i = 0; i < maxNum; i++) {
                    numSeq.add(i);
                }
                Collections.shuffle(numSeq);

                return numSeq;
            }

            public int getIslandIdOfNode(int nodeId) {
                return node2IslandId.get(nodeId);
            }

            public void printPlaceResult() {
                logger.info("Cut Size: " + cutSize.toString());
                logger.info("Island Size Std Variance: " + nodeWeightStdVar.toString());
                logger.info("Island Sizes: " + island2NodeWeight.toString());
            }
        };

        logger.info("Start SA-based placement refinement");

        PlaceStateTracker placeStateTracker = new PlaceStateTracker(node2IslandLoc);
        placeStateTracker.printPlaceResult();

        int maxIter = 10;
        for (int iter = 0; iter < maxIter; iter++) {
            logger.info("Iteration-" + iter + ":");
            logger.newSubStep();

            List<Integer> randomNodeIdSeq = placeStateTracker.generateRandomNumSeq(netlistGraph.getNodeNum());

            for (int nodeId : randomNodeIdSeq) {
                List<Integer> cddtIslandIds = new ArrayList<>(placeStateTracker.node2CddtIslandIds.get(nodeId));
                Collections.shuffle(cddtIslandIds);

                int originIslandId = placeStateTracker.getIslandIdOfNode(nodeId);
                for (int islandId : cddtIslandIds) {
                    if (islandId == originIslandId) continue;

                    Double moveGain = placeStateTracker.getMoveGain(nodeId, islandId);
                    if (moveGain > 0) {
                        placeStateTracker.moveNode(nodeId, islandId);
                        logger.info(String.format("Move node-%d from island-%d to island-%d", nodeId, originIslandId, islandId));
                        break;
                    }
                }
            }

            placeStateTracker.printPlaceResult();

            logger.endSubStep();
        }

        for (int nodeId = 0; nodeId < netlistGraph.getNodeNum(); nodeId++) {
            Coordinate2D loc = getIslandLocFromIndex(placeStateTracker.getIslandIdOfNode(nodeId));
            node2IslandLoc.set(nodeId, loc);
        }

        buildNode2IslandMap();
        buildEdge2BoundaryMap();
        printIslandPlacementResult();
        logger.info("Complete SA-based placement refinement");
    }

    // helper functions
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
            assert xDist + yDist == 1;

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
        printWeightDist(nodeWeightDist, gridDim);

        logger.info("Distribution of horizontal edge weight:");

        List<Double>[][] horiEdgeWeightDist = getWeightsDist(horiBoundary2Edges, netlistGraph::getWeightsOfEdge);
        printWeightDist(horiEdgeWeightDist, horiBoundaryDim);

        logger.info("Distribution of vertical edge weight:");
        List<Double>[][] vertEdgeWeightDist = getWeightsDist(vertBoundary2Edges, netlistGraph::getWeightsOfEdge);
        printWeightDist(vertEdgeWeightDist, vertBoundaryDim);

        logger.endSubStep();
    }

    // private void checkAndPrintPlaceResult() {
        
    // }

    private List<Double>[][] getWeightsDist(List<Integer>[][] grid, Function<Integer, List<Double>> getWeight) {
        List<Double>[][] weightDist = new ArrayList[grid.length][];
        for (int x = 0; x < grid.length; x++) {
            weightDist[x] = new ArrayList[grid[x].length];
            for (int y = 0; y < grid[x].length; y++) {

                for (Integer elem : grid[x][y]) {
                    if (weightDist[x][y] == null) {
                        weightDist[x][y] = new ArrayList<>(getWeight.apply(elem));
                    } else {
                        HyperGraph.accuWeights(weightDist[x][y], getWeight.apply(elem));
                    }
                }
            }
        }
        return weightDist;
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

    private int getIslandIndexFromLoc(Coordinate2D loc) {
        return loc.getX() * gridDim.getY() + loc.getY();
    } 

    private Coordinate2D getIslandLocFromIndex(int index) {
        return Coordinate2D.of(index / gridDim.getY(), index % gridDim.getY());
    }

    private HyperGraph convertNetlist2HyperGraph(AbstractNetlist netlist) {
        HyperGraph netlistGraph = new HyperGraph(1, 1);

        for (int groupId = 0; groupId < netlist.getGroupNum(); groupId++) {
            List<Double> weights = Arrays.asList((double) abstractNetlist.getLeafCellNumOfGroup(groupId));
            netlistGraph.addNode(weights);
        }

        Map<Set<Integer>, List<Integer>> compressedEdges = compressAbstractEdges(abstractNetlist.edge2GroupIds);

        for (Map.Entry<Set<Integer>, List<Integer>> entry : compressedEdges.entrySet()) {
            Set<Integer> groupIds = entry.getKey();
            List<Integer> originEdgeIds = entry.getValue();

            List<Double> weights = Arrays.asList((double) originEdgeIds.size());
            netlistGraph.addEdge(groupIds, weights);
        }

        return netlistGraph;
    }

    private HyperGraph createIslandGraph() {
        HyperGraph islandGraph = new HyperGraph(1, 1);

        // add island nodes
        for (int x = 0; x < gridDim.getX(); x++) {
            for (int y = 0; y < gridDim.getY(); y++) {
                List<Double> weights = Arrays.asList(0.0);
                islandGraph.addNode(weights);
            }
        }

        // add horizontal boundary edges
        for (int x = 0; x < horiBoundaryDim.getX(); x++) {
            for (int y = 0; y < horiBoundaryDim.getY(); y++) {
                List<Double> weights = Arrays.asList(0.0);
                Set<Integer> nodeIds = new HashSet<>();
                nodeIds.add(getIslandIndexFromLoc(Coordinate2D.of(x, y)));
                nodeIds.add(getIslandIndexFromLoc(Coordinate2D.of(x, y + 1)));
                islandGraph.addEdge(nodeIds, weights);
            }
        }

        // add vertical boundary edges
        for (int x = 0; x < vertBoundaryDim.getX(); x++) {
            for (int y = 0; y < vertBoundaryDim.getY(); y++) {
                List<Double> weights = Arrays.asList(0.0);
                Set<Integer> nodeIds = new HashSet<>();
                nodeIds.add(getIslandIndexFromLoc(Coordinate2D.of(x, y)));
                nodeIds.add(getIslandIndexFromLoc(Coordinate2D.of(x + 1, y)));
                islandGraph.addEdge(nodeIds, weights);
            }
        }

        return islandGraph;
    }

    private List<Integer> extractNodePartResultFromCluster(List<Integer> partResult, List<List<Integer>> cluster2Nodes) {
        Map<Integer, Integer> node2ClusterMap = new HashMap<>();
        for (int clusterId = 0; clusterId < cluster2Nodes.size(); clusterId++) {
            for (int nodeId : cluster2Nodes.get(clusterId)) {
                node2ClusterMap.put(nodeId, clusterId);
            }
        }

        List<Integer> nodePartResult = new ArrayList<>(Collections.nCopies(node2ClusterMap.size(), -1));
        for (Integer nodeId : node2ClusterMap.keySet()) {
            Integer clusterId = node2ClusterMap.get(nodeId);
            nodePartResult.set(nodeId, partResult.get(clusterId));
        }

        return nodePartResult;
    }

    private List<List<Integer>> clusterNodesOfCutEdges(HyperGraph netlistGraph, List<Integer> partResult) {
        List<List<Integer>> cluster2Nodes = new ArrayList<>();
        Map<Integer, Integer> node2Cluster = new HashMap<>();

        Set<Integer> visitedEdges = new HashSet<>();
        for (int edgeId = 0; edgeId < netlistGraph.getEdgeNum(); edgeId++) {
            if (!netlistGraph.isCutEdge(edgeId, partResult)) {
                visitedEdges.add(edgeId);
            }
        }
        
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
        }

        return cluster2Nodes;
    }

}
