package com.xilinx.rapidwright.rapidpnr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.xilinx.rapidwright.rapidpnr.partitioner.Coarser;
import com.xilinx.rapidwright.rapidpnr.partitioner.ILPIslandPartitioner;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierHyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.HyperGraph;
import com.xilinx.rapidwright.rapidpnr.utils.StatisticsUtils;

public class MultilevelIslandPlacer extends AbstractIslandPlacer {

    int coarsenStopNodeNum;
    Coarser.Config coarserConfig;
    ILPIslandPartitioner.Config ilpPartConfig;

    HierHyperGraph netlistGraph;
    private List<Coordinate2D> node2IslandLoc;
    private List<Integer>[][] island2Nodes;
    private List<Integer>[][] horiBoundary2Edges;
    private List<Integer>[][] vertBoundary2Edges;

    public MultilevelIslandPlacer(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams params) {
        super(logger, dirManager, params);

        coarsenStopNodeNum = 200;
        // coarserConfig = new Coarser.Config(Coarser.Scheme.FC, params.getRandomSeed(), 1.8, 0.20);
        coarserConfig = new Coarser.Config(Coarser.Scheme.FC, 9999, 2.0, 0.1);

        ilpPartConfig = new ILPIslandPartitioner.Config();
        ilpPartConfig.gridDim = gridDim;
        //ilpPartConfig.imbFactors = Arrays.asList(0.05);
    }

    public List<Coordinate2D> run(AbstractNetlist abstractNetlist) {
        logger.info("Start running island placement");
        logger.newSubStep();

        this.abstractNetlist = abstractNetlist;
        netlistGraph = convertNetlist2HyperGraph(abstractNetlist);

        // coarsening phase
        HierHyperGraph coarsestGraph = coarsen(coarsenStopNodeNum);

        // initial partition
        List<Coordinate2D> initialPartRes = initialPlace(coarsestGraph);

        // uncoarsening and refinement
        node2IslandLoc = uncoarsenAndRefine(coarsestGraph, initialPartRes);
        

        buildIsland2NodeMap();
        buildBoundary2EdgeMap();
        printIslandPlaceInfo();

        logger.endSubStep();
        logger.info("Complete running island placement");
        return node2IslandLoc;
    }

    private HierHyperGraph convertNetlist2HyperGraph(AbstractNetlist netlist) {
        HierHyperGraph netlistGraph = new HierHyperGraph(Arrays.asList(1.0), Arrays.asList(1.0));

        for (int nodeId = 0; nodeId < netlist.getNodeNum(); nodeId++) {
            List<Double> weights = Arrays.asList((double) abstractNetlist.getLeafCellNumOfNode(nodeId));
            netlistGraph.addNode(weights);
        }

        Map<Set<Integer>, List<Integer>> compressedEdges = compressAbstractEdges(abstractNetlist.edge2NodeIds);

        for (Map.Entry<Set<Integer>, List<Integer>> entry : compressedEdges.entrySet()) {
            Set<Integer> groupIds = entry.getKey();
            List<Integer> originEdgeIds = entry.getValue();

            List<Double> weights = Arrays.asList((double) originEdgeIds.size());
            netlistGraph.addEdge(groupIds, weights);
        }
        return netlistGraph;
    }

    private HierHyperGraph coarsen(int stopNodeNum) {
        logger.info("Start coarsening phase");
        logger.newSubStep();

        HierHyperGraph curGraph = netlistGraph;

        int coarseLevel = 0;
        logger.info("Original Hypergraph: \n" + curGraph.getHyperGraphInfo(true), true);

        while(curGraph.getNodeNum() > stopNodeNum) {
            logger.info(String.format("The level of coarsening %d", coarseLevel));
            logger.info("Build coarsened hypergraph");
            coarserConfig.seed += coarseLevel;
            curGraph = Coarser.coarsening(coarserConfig, curGraph);
            logger.info("Coarsened Hypergraph: \n" + curGraph.getHyperGraphInfo(false), true);
            coarseLevel++;
            if (coarseLevel > 200) {
                break;
            }
        }

        logger.info("Coarsest Hypergraph:\n" + curGraph.getHyperGraphInfo(true), true);

        logger.endSubStep();
        logger.info(String.format("Complete coarsening phase with %d levels of hypergraphs", coarseLevel + 1));
        return curGraph;
    }

    public List<Coordinate2D> initialPlace(HierHyperGraph coarsestGraph) {
        logger.info("Start initial placement of coarsest hypergraph");
        logger.newSubStep();

        ILPIslandPartitioner ilpIslandPart = new ILPIslandPartitioner(logger, ilpPartConfig, coarsestGraph);
        List<Coordinate2D> initialPartRes = ilpIslandPart.run();

        logger.endSubStep();
        logger.info("Complete initial placement of coarsest hypergraph");
        return initialPartRes;
    }

    public List<Coordinate2D> uncoarsenAndRefine(HierHyperGraph coarsestGraph, List<Coordinate2D> partRes) {
        logger.info("Start uncoarsening and refinement phase");
        logger.newSubStep();

        HierHyperGraph curHyperGraph = coarsestGraph;
        List<Coordinate2D> curPartRes = partRes;


        List<Coordinate2D> rootPartRes = new ArrayList<>(Collections.nCopies(netlistGraph.getNodeNum(), null));
        for (int nodeId = 0; nodeId < curHyperGraph.getNodeNum(); nodeId++) {
            Coordinate2D loc = curPartRes.get(nodeId);
            for (int rootId : curHyperGraph.getRootParentsOfNode(nodeId)) {
                rootPartRes.set(rootId, loc);
            }
        }

        curPartRes = rootPartRes;

        // while (!coarsestGraph.isRootGraph()) {
            
        // }

        logger.endSubStep();
        logger.info("Complete uncoarsening and refinement phase");
        return curPartRes;
    }

    // helper function
    private Coordinate2D getLocOfNode(int nodeId) {
        return node2IslandLoc.get(nodeId);
    }

    private void printIslandPlaceInfo() {
        logger.info("Island placement results:");
        logger.newSubStep();

        printNodeDistributionInfo();

        printEdgeDistributionInfo();

        logger.endSubStep();
    }
    
    private void printNodeDistributionInfo() {
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
    }

    private void printEdgeDistributionInfo() {
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

    private void buildIsland2NodeMap() {
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

    private void buildBoundary2EdgeMap() {
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
}
