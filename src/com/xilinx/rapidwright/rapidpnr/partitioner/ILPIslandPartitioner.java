package com.xilinx.rapidwright.rapidpnr.partitioner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.Literal;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;

import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.HyperGraph;
import com.xilinx.rapidwright.util.RuntimeTracker;

public class ILPIslandPartitioner {

    public static class Config {
        public Coordinate2D islandDim;
        public List<Double> imbFactors;
    }

    private HierarchicalLogger logger;
    private HyperGraph hyperGraph;

    // compressed hypergraph
    //private HyperGraph compressedGraph;

    // config
    private Config config;
    private int islandNum;
    private Coordinate2D islandDim;

    // constraints
    private List<Double> islandSizeUpperBound;

    // island partition states
    private List<Coordinate2D> node2IslandLoc;

    public ILPIslandPartitioner(HierarchicalLogger logger, Config config, HyperGraph hyperGraph) {
        this.logger = logger;
        this.hyperGraph = hyperGraph;
        this.hyperGraph = hyperGraph.getCompressedGraph();

        this.config = config;
        this.islandDim = config.islandDim;
        this.islandNum = islandDim.getX() * islandDim.getY();

        setIslandSizeBound();
        node2IslandLoc = new ArrayList<>();

        logger.info("Num of nodes: " + this.hyperGraph.getNodeNum());
        logger.info("Num of edges: " + this.hyperGraph.getEdgeNum());
    }

    public List<Coordinate2D> run() {

        //runMIPSolver2();
        runCpSATSolver();

        assert checkIslandSizeConstr(): "Island size constraints are violated";
        assert checkEdgeLengthConstr(): "Edge length constraints are violated";

        return node2IslandLoc;
    }

    // ILP solvers based on Google OR-Tools
    private List<Coordinate2D> runMIPSolver() {
        // TODO: 
        // load necessary libraries
        Loader.loadNativeLibraries();

        double negInfinity = java.lang.Double.NEGATIVE_INFINITY;
        // create the linear solver with SCIP backend
        MPSolver solver = MPSolver.createSolver("SCIP");
        assert solver != null: "Failed to create a solver";

        // create variables
        //// x coordinates of nodes
        MPVariable[] nodeX = new MPVariable[hyperGraph.getNodeNum()];
        for (int i = 0; i < hyperGraph.getNodeNum(); i++) {
            nodeX[i] = solver.makeIntVar(0.0, islandDim.getX() - 1, "nodeX_" + i);
        }
        //// y coordinates of nodes
        MPVariable[] nodeY = new MPVariable[hyperGraph.getNodeNum()];
        for (int i = 0; i < hyperGraph.getNodeNum(); i++) {
            nodeY[i] = solver.makeIntVar(0.0, islandDim.getY() - 1, "nodeY_" + i);
        }
        //// x coordinate of left bound of edges
        MPVariable[] edgeLeftX = new MPVariable[hyperGraph.getEdgeNum()];
        for (int i = 0; i < hyperGraph.getEdgeNum(); i++) {
            edgeLeftX[i] = solver.makeIntVar(0.0, islandDim.getX() - 1, "edgeLeftX_" + i);
        }
        //// x coordinate of right bound of edges
        MPVariable[] edgeRightX = new MPVariable[hyperGraph.getEdgeNum()];
        for (int i = 0; i < hyperGraph.getEdgeNum(); i++) {
            edgeRightX[i] = solver.makeIntVar(0.0, islandDim.getX() - 1, "edgeRightX_" + i);
        }
        //// y coordinate of lower bound of edges
        MPVariable[] edgeLowerY = new MPVariable[hyperGraph.getEdgeNum()];
        for (int i = 0; i < hyperGraph.getEdgeNum(); i++) {
            edgeLowerY[i] = solver.makeIntVar(0.0, islandDim.getY() - 1, "edgeLowerY_" + i);
        }
        //// y coordinate of upper bound of edges
        MPVariable[] edgeUpperY = new MPVariable[hyperGraph.getEdgeNum()];
        for (int i = 0; i < hyperGraph.getEdgeNum(); i++) {
            edgeUpperY[i] = solver.makeIntVar(0.0, islandDim.getY() - 1, "edgeUpperY_" + i);
        }
        //// node to island mapping
        MPVariable[][] node2Island = new MPVariable[hyperGraph.getNodeNum()][islandNum];
        for (int i = 0; i < hyperGraph.getNodeNum(); i++) {
            for (int j = 0; j < islandNum; j++) {
                node2Island[i][j] = solver.makeBoolVar("node2Island_" + i + "_" + j);
            }
        }

        // create constraints
        //// edge bound constraints
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            for (int nodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                MPConstraint leftBoundConstr = solver.makeConstraint(negInfinity, 0.0);
                leftBoundConstr.setCoefficient(edgeLeftX[edgeId], 1.0);
                leftBoundConstr.setCoefficient(nodeX[nodeId], -1.0);

                MPConstraint rightBoundConstr = solver.makeConstraint(negInfinity, 0.0);
                rightBoundConstr.setCoefficient(edgeRightX[edgeId], -1.0);
                rightBoundConstr.setCoefficient(nodeX[nodeId], 1.0);

                MPConstraint lowerBoundConstr = solver.makeConstraint(negInfinity, 0.0);
                lowerBoundConstr.setCoefficient(edgeLowerY[edgeId], 1.0);
                lowerBoundConstr.setCoefficient(nodeY[nodeId], -1.0);

                MPConstraint upperBoundConstr = solver.makeConstraint(negInfinity, 0.0);
                upperBoundConstr.setCoefficient(edgeUpperY[edgeId], -1.0);
                upperBoundConstr.setCoefficient(nodeY[nodeId], 1.0);
            }
        }
        //// create edge length constraints
        // for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
        //     MPConstraint edgeLengthConstr = solver.makeConstraint(0.0, 1.0);
        //     edgeLengthConstr.setCoefficient(edgeLeftX[edgeId], -1.0);
        //     edgeLengthConstr.setCoefficient(edgeRightX[edgeId], 1.0);
        //     edgeLengthConstr.setCoefficient(edgeLowerY[edgeId], -1.0);
        //     edgeLengthConstr.setCoefficient(edgeUpperY[edgeId], 1.0);
        // }

        //// create island size constraints
        for (int islandId = 0; islandId < islandNum; islandId++) {
            Double upperBound = hyperGraph.getNodeWeightsSum(islandSizeUpperBound);
            MPConstraint islandSizeConstr = solver.makeConstraint(0.0, upperBound);

            for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
                islandSizeConstr.setCoefficient(node2Island[nodeId][islandId], hyperGraph.getNodeWeightsSum(nodeId));
            }
        }

        //// create node to island mapping constraints
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            MPConstraint node2IslandConstr = solver.makeConstraint(1.0, 1.0);

            for (int islandId = 0; islandId < islandNum; islandId++) {
                node2IslandConstr.setCoefficient(node2Island[nodeId][islandId], 1.0);
            }
        }

        MPVariable node2XMap[][] = new MPVariable[hyperGraph.getNodeNum()][islandDim.getX()];
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            for (int x = 0; x < islandDim.getX(); x++) {
                node2XMap[nodeId][x] = solver.makeBoolVar("node2XMap_" + nodeId + "_" + x);
                // mapping constraints: when nodeX[i] == j, node2XMap[i][j] == 1, otherwise node2XMap[i][j] == 0
                // enforce node2XMap[i][x] = 0 when nodeX[i] != j
                //   subconstr-1 formulation: nodeX[i] - x <= M * (1 - node2XMap[i][x]) -> nodeX[i] + M * node2XMap[i][x] <= M + x
                //   subconstr-2 formulation: x - nodeX[i] <= M * (1 - node2XMap[i][x]) -> M * node2XMap[i][x] - nodeX[i] <= M + x

                MPConstraint subConstr1 = solver.makeConstraint(negInfinity, islandDim.getX() + x);
                subConstr1.setCoefficient(nodeX[nodeId], 1.0);
                subConstr1.setCoefficient(node2XMap[nodeId][x], islandDim.getX());

                MPConstraint subConstr2 = solver.makeConstraint(negInfinity, islandDim.getX() + x);
                subConstr2.setCoefficient(nodeX[nodeId], -1.0);
                subConstr2.setCoefficient(node2XMap[nodeId][x], islandDim.getX());
            }
        }
        
        MPVariable node2YMap[][] = new MPVariable[hyperGraph.getNodeNum()][islandDim.getY()];
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            for (int y = 0; y < islandDim.getY(); y++) {
                node2YMap[nodeId][y] = solver.makeBoolVar("node2YMap_" + nodeId + "_" + y);

                MPConstraint subConstr1 = solver.makeConstraint(negInfinity, islandDim.getY() + y);
                subConstr1.setCoefficient(nodeY[nodeId], 1.0);
                subConstr1.setCoefficient(node2YMap[nodeId][y], islandDim.getY());

                MPConstraint subConstr2 = solver.makeConstraint(negInfinity, islandDim.getY() + y);
                subConstr2.setCoefficient(nodeY[nodeId], -1.0);
                subConstr2.setCoefficient(node2YMap[nodeId][y], islandDim.getY());
            }
        }

        for (int nodeId  = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            for (int islandId = 0; islandId < islandNum; islandId++) {
                // a*b can be transformed into 0 <= a + b - 2*p <= 1 with p in [0,1]
                // p is True if a AND b, False otherwise
                Coordinate2D islandLoc = getIslandLocFromIndex(islandId);
                MPConstraint node2IslandConstr = solver.makeConstraint(0, 1);
                
                node2IslandConstr.setCoefficient(node2Island[nodeId][islandId], -2);
                node2IslandConstr.setCoefficient(node2XMap[nodeId][islandLoc.getX()], 1);
                node2IslandConstr.setCoefficient(node2YMap[nodeId][islandLoc.getY()], 1);
            }
        }

        // create objective
        MPObjective objective = solver.objective();
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            objective.setCoefficient(edgeLeftX[edgeId], -1.0 * hyperGraph.getEdgeWeightsSum(edgeId));
            objective.setCoefficient(edgeRightX[edgeId], 1.0 * hyperGraph.getEdgeWeightsSum(edgeId));
            objective.setCoefficient(edgeLowerY[edgeId], -1.0 * hyperGraph.getEdgeWeightsSum(edgeId));
            objective.setCoefficient(edgeUpperY[edgeId], 1.0 * hyperGraph.getEdgeWeightsSum(edgeId));
        }

        // solve the problem
        List<Coordinate2D> node2IslandLoc = new ArrayList<>();
        MPSolver.ResultStatus resultStatus = solver.solve();
        if (resultStatus == MPSolver.ResultStatus.OPTIMAL || resultStatus == MPSolver.ResultStatus.FEASIBLE) {
            logger.info("Find feasible solution with objective value: " + objective.value());

            for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
                logger.info(String.format("node-%d x=%.3f y=%.3f", nodeId, nodeX[nodeId].solutionValue(), nodeY[nodeId].solutionValue()));
                String node2IslandStr = String.format("node-%d to island: ", nodeId);
                for (int islandId = 0; islandId < islandNum; islandId++) {
                    node2IslandStr += String.format("%.3f ", node2Island[nodeId][islandId].solutionValue());
                }
                logger.info(node2IslandStr);

                Coordinate2D nodeLoc = Coordinate2D.of((int) nodeX[nodeId].solutionValue(), (int) nodeY[nodeId].solutionValue());
                node2IslandLoc.add(nodeLoc);
            }
        } else {
            assert false: "Failed to find feasible solution";
        }
        return node2IslandLoc;
    }

    private List<Coordinate2D> runMIPSolver2() {
        assert islandDim.getX() == 2 && islandDim.getY() == 2;
        double negInfinity = java.lang.Double.NEGATIVE_INFINITY;

        logger.info("Start running MIPSolver2");
        logger.newSubStep();

        logger.info("Start formulating optimization problem");

        // load necessary libraries
        Loader.loadNativeLibraries();
        
        // create the linear solver with SCIP backend
        MPSolver solver = MPSolver.createSolver("SCIP");
        assert solver != null: "Failed to create a solver";

        // create variables
        //// x/y coordinates of nodes
        MPVariable[] nodeX = new MPVariable[hyperGraph.getNodeNum()];
        MPVariable[] nodeY = new MPVariable[hyperGraph.getNodeNum()];
        //// node to island mapping
        MPVariable[][] node2Island = new MPVariable[hyperGraph.getNodeNum()][islandNum];
        for (int i = 0; i < hyperGraph.getNodeNum(); i++) {
            nodeX[i] = solver.makeBoolVar("nodeX_" + i);
            nodeY[i] = solver.makeBoolVar("nodeY_" + i);
            for (int j = 0; j < islandNum; j++) {
                node2Island[i][j] = solver.makeBoolVar("node2Island_" + i + "_" + j);
            }
        }
        //// bound locations of edges
        MPVariable[] edgeLeftX = new MPVariable[hyperGraph.getEdgeNum()];
        MPVariable[] edgeRightX = new MPVariable[hyperGraph.getEdgeNum()];
        MPVariable[] edgeLowerY = new MPVariable[hyperGraph.getEdgeNum()];
        MPVariable[] edgeUpperY = new MPVariable[hyperGraph.getEdgeNum()];
        for (int i = 0; i < hyperGraph.getEdgeNum(); i++) {
            edgeLeftX[i] = solver.makeBoolVar("edgeLeftX_" + i);
            edgeRightX[i] = solver.makeBoolVar("edgeRightX_" + i);
            edgeLowerY[i] = solver.makeBoolVar("edgeLowerY_" + i);
            edgeUpperY[i] = solver.makeBoolVar("edgeUpperY_" + i);
        }

        // create constraints
        //// edge bound constraints
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            for (int nodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                MPConstraint leftBoundConstr = solver.makeConstraint(negInfinity, 0.0);
                leftBoundConstr.setCoefficient(edgeLeftX[edgeId], 1.0);
                leftBoundConstr.setCoefficient(nodeX[nodeId], -1.0);

                MPConstraint rightBoundConstr = solver.makeConstraint(negInfinity, 0.0);
                rightBoundConstr.setCoefficient(edgeRightX[edgeId], -1.0);
                rightBoundConstr.setCoefficient(nodeX[nodeId], 1.0);

                MPConstraint lowerBoundConstr = solver.makeConstraint(negInfinity, 0.0);
                lowerBoundConstr.setCoefficient(edgeLowerY[edgeId], 1.0);
                lowerBoundConstr.setCoefficient(nodeY[nodeId], -1.0);

                MPConstraint upperBoundConstr = solver.makeConstraint(negInfinity, 0.0);
                upperBoundConstr.setCoefficient(edgeUpperY[edgeId], -1.0);
                upperBoundConstr.setCoefficient(nodeY[nodeId], 1.0);
            }
        }

        // create edge length constraints
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            MPConstraint edgeLengthConstr = solver.makeConstraint(0.0, 1.0);
            edgeLengthConstr.setCoefficient(edgeLeftX[edgeId], -1.0);
            edgeLengthConstr.setCoefficient(edgeRightX[edgeId], 1.0);
            edgeLengthConstr.setCoefficient(edgeLowerY[edgeId], -1.0);
            edgeLengthConstr.setCoefficient(edgeUpperY[edgeId], 1.0);
        }

        //// create island size constraints
        for (int islandId = 0; islandId < islandNum; islandId++) {
            Double upperBound = hyperGraph.getNodeWeightsSum(islandSizeUpperBound);
            MPConstraint islandSizeConstr = solver.makeConstraint(0.0, upperBound);

            for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
                islandSizeConstr.setCoefficient(node2Island[nodeId][islandId], hyperGraph.getNodeWeightsSum(nodeId));
            }
        }

        //// create node to island mapping constraints
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            MPConstraint node2IslandConstr = solver.makeConstraint(1.0, 1.0);

            for (int islandId = 0; islandId < islandNum; islandId++) {
                node2IslandConstr.setCoefficient(node2Island[nodeId][islandId], 1.0);
            }
        }

        //// create island mapping to x/y coordinates constraints
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); ++nodeId) {
            for (int islandId = 0; islandId < islandNum; ++islandId) {
                Coordinate2D islandLoc = getIslandLocFromIndex(islandId);
                MPConstraint xConstr = solver.makeConstraint(0, 1);
                MPConstraint yConstr = solver.makeConstraint(0, 1);
                if (islandLoc.getX() == 0) {
                    xConstr.setCoefficient(node2Island[nodeId][islandId], 1);
                    xConstr.setCoefficient(nodeX[nodeId], 1);
                } else {
                    xConstr.setCoefficient(node2Island[nodeId][islandId], -1);
                    xConstr.setCoefficient(nodeX[nodeId], 1);
                }

                if (islandLoc.getY() == 0) {
                    yConstr.setCoefficient(node2Island[nodeId][islandId], 1);
                    yConstr.setCoefficient(nodeY[nodeId], 1);
                } else {
                    yConstr.setCoefficient(node2Island[nodeId][islandId], -1);
                    yConstr.setCoefficient(nodeY[nodeId], 1);
                }
            }
        }

        // create objective
        MPObjective objective = solver.objective();
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            objective.setCoefficient(edgeLeftX[edgeId], -1.0 * hyperGraph.getEdgeWeightsSum(edgeId));
            objective.setCoefficient(edgeRightX[edgeId], 1.0 * hyperGraph.getEdgeWeightsSum(edgeId));
            objective.setCoefficient(edgeLowerY[edgeId], -1.0 * hyperGraph.getEdgeWeightsSum(edgeId));
            objective.setCoefficient(edgeUpperY[edgeId], 1.0 * hyperGraph.getEdgeWeightsSum(edgeId));
        }

        logger.info("Complete formulating optimization problem");

        // launch kernel solver
        logger.info("Launch kernel solver");
        RuntimeTracker timer = new RuntimeTracker("SCIP Solver", (short) 0);
        timer.start();
        MPSolver.ResultStatus resultStatus = solver.solve();
        timer.stop();
        logger.info(String.format("Complete running kernel solver in %.2f sec", timer.getTimeInSec()));

        // collect results
        if (resultStatus == MPSolver.ResultStatus.OPTIMAL || resultStatus == MPSolver.ResultStatus.FEASIBLE) {
            logger.info("Find feasible solution with objective value: " + objective.value());

            for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
                logger.info(String.format("node-%d x=%.3f y=%.3f", nodeId, nodeX[nodeId].solutionValue(), nodeY[nodeId].solutionValue()));
                String node2IslandStr = String.format("node-%d to island: ", nodeId);
                for (int islandId = 0; islandId < islandNum; islandId++) {
                    node2IslandStr += String.format("%.3f ", node2Island[nodeId][islandId].solutionValue());
                }
                logger.info(node2IslandStr);

                Coordinate2D nodeLoc = Coordinate2D.of((int) nodeX[nodeId].solutionValue(), (int) nodeY[nodeId].solutionValue());
                node2IslandLoc.add(nodeLoc);
            }
        } else {
            logger.info("Failed to find feasible solution");
        }

        logger.endSubStep();
        logger.info("Complete running MIPSolver2");
        return node2IslandLoc;
    }

    private List<Coordinate2D> runCpSATSolver() {
        assert islandDim.getX() == 2 && islandDim.getY() == 2;

        logger.info("Start solving island placement through Cp-SAT solver");
        logger.newSubStep();

        logger.info("Start formulating optimization problem");
        // load libraries
        Loader.loadNativeLibraries();
        // create model
        CpModel model = new CpModel();

        // create variables
        //// x/y coordinates of nodes
        Literal[] nodeX = new Literal[hyperGraph.getNodeNum()];
        Literal[] nodeY = new Literal[hyperGraph.getNodeNum()];
        //// node2Island mapping
        Literal[][] node2Island = new Literal[hyperGraph.getNodeNum()][islandNum];
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            nodeX[nodeId] = model.newBoolVar("nodeX_" + nodeId);
            nodeY[nodeId] = model.newBoolVar("nodeY_" + nodeId);

            for (int islandId = 0; islandId < islandNum; islandId++) {
                node2Island[nodeId][islandId] = model.newBoolVar("node2Island_" + nodeId + "_" + islandId);
            }
        }
        //// bound locations of edges
        Literal[] edgeLeftX = new Literal[hyperGraph.getEdgeNum()];
        Literal[] edgeRightX = new Literal[hyperGraph.getEdgeNum()];
        Literal[] edgeLowerY = new Literal[hyperGraph.getEdgeNum()];
        Literal[] edgeUpperY = new Literal[hyperGraph.getEdgeNum()];
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            edgeLeftX[edgeId] = model.newBoolVar("edgeLeftX_" + edgeId);
            edgeRightX[edgeId] = model.newBoolVar("edgeRightX_" + edgeId);
            edgeLowerY[edgeId] = model.newBoolVar("edgeLowerY_" + edgeId);
            edgeUpperY[edgeId] = model.newBoolVar("edgeUpperY_" + edgeId);
        }

        // create constraints
        //// edge bound location constraints
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            List<Literal> incidentNodesX = new ArrayList<>();
            List<Literal> incidentNodesY = new ArrayList<>();
            for (int nodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                incidentNodesX.add(nodeX[nodeId]);
                incidentNodesY.add(nodeY[nodeId]);
                // model.addLessOrEqual(edgeLeftX[edgeId], nodeX[nodeId]);
                // model.addLessOrEqual(edgeLowerY[edgeId], nodeY[nodeId]);
                // model.addGreaterOrEqual(edgeRightX[edgeId], nodeX[nodeId]);
                // model.addGreaterOrEqual(edgeUpperY[edgeId], nodeY[nodeId]);
            }
            model.addMinEquality(edgeLeftX[edgeId], incidentNodesX);
            model.addMaxEquality(edgeRightX[edgeId], incidentNodesX);
            model.addMinEquality(edgeLowerY[edgeId], incidentNodesY);
            model.addMaxEquality(edgeUpperY[edgeId], incidentNodesY);
        }

        //// edge length constraints
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            LinearExprBuilder expr = LinearExpr.newBuilder();
            expr.addTerm(edgeLeftX[edgeId], -1);
            expr.addTerm(edgeRightX[edgeId], 1);
            expr.addTerm(edgeLowerY[edgeId], -1);
            expr.addTerm(edgeUpperY[edgeId], 1);
            model.addLessOrEqual(expr, 1);
        }
        
        //// island size constraints
        for (int islandId = 0; islandId < islandNum; islandId++) {
            LinearExprBuilder expr = LinearExpr.newBuilder();
            for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
                long nodeWeight = hyperGraph.getNodeWeightsSum(nodeId).longValue();
                expr.addTerm(node2Island[nodeId][islandId], nodeWeight);
            }

            long upperBound = (long) Math.ceil(hyperGraph.getNodeWeightsSum(islandSizeUpperBound));
            model.addLessOrEqual(expr, upperBound);
        }

        //// node to island mapping constraints
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            List<Literal> islandLiterals = new ArrayList<>();
            for (int islandId = 0; islandId < islandNum; islandId++) {
                islandLiterals.add(node2Island[nodeId][islandId]);
            }
            model.addExactlyOne(islandLiterals);
        }

        //// island mapping to x/y coordinates constraints
        //// x + node2Island[nodeId][islandId] <= 1 -> node2Island[nodeId][islandId] == 1 -> x == 0
        //// x - node2Island[nodeId][islandId] <= 0 -> node2Island[nodeId][islandId] == 1 -> x == 1
        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            for (int islandId = 0; islandId < islandNum; islandId++) {
                Coordinate2D islandLoc = getIslandLocFromIndex(islandId);
                LinearExprBuilder xExpr = LinearExpr.newBuilder();
                if (islandLoc.getX() == 1) {
                    xExpr.addTerm(nodeX[nodeId], 1);
                    xExpr.addTerm(node2Island[nodeId][islandId], -1);
                    model.addGreaterOrEqual(xExpr, 0);
                } else {
                    xExpr.addTerm(nodeX[nodeId], 1);
                    xExpr.addTerm(node2Island[nodeId][islandId], 1);
                    model.addLessOrEqual(xExpr, 1);
                }

                LinearExprBuilder yExpr = LinearExpr.newBuilder();
                if (islandLoc.getY() == 1) {
                    yExpr.addTerm(nodeY[nodeId], 1);
                    yExpr.addTerm(node2Island[nodeId][islandId], -1);
                    model.addGreaterOrEqual(yExpr, 0);
                } else {
                    yExpr.addTerm(nodeY[nodeId], 1);
                    yExpr.addTerm(node2Island[nodeId][islandId], 1);
                    model.addLessOrEqual(yExpr, 1);
                }

            }
        }

        // create objective
        LinearExprBuilder objective = LinearExpr.newBuilder();
        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            long edgeWeight = hyperGraph.getEdgeWeightsSum(edgeId).longValue();
            objective.addTerm(edgeLeftX[edgeId], -1 * edgeWeight);
            objective.addTerm(edgeRightX[edgeId], 1 * edgeWeight);
            objective.addTerm(edgeLowerY[edgeId], -1 * edgeWeight);
            objective.addTerm(edgeUpperY[edgeId], 1 * edgeWeight);
        }
        model.minimize(objective);
        logger.info("Complete formulating optimization problem");

        // solve the problem
        logger.info("Launch kernel solver");
        RuntimeTracker timer = new RuntimeTracker("CP-SAT Solver", (short) 0);
        timer.start();
        CpSolver solver = new CpSolver();
        CpSolverStatus resultStatus = solver.solve(model);
        timer.stop();
        logger.info(String.format("Complete running kernel solver in %.2f sec", timer.getTimeInSec()));

        if (resultStatus == CpSolverStatus.OPTIMAL || resultStatus == CpSolverStatus.FEASIBLE) {
            logger.info("Find feasible solution with objective value: " + solver.objectiveValue());

            for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
                long x = solver.value(nodeX[nodeId]);
                long y = solver.value(nodeY[nodeId]);
                logger.info(String.format("node-%d x=%d y=%d", nodeId, x, y));

                Coordinate2D nodeLoc = Coordinate2D.of((int) x, (int) y);
                node2IslandLoc.add(nodeLoc);
            }
        } else {
            logger.info("Failed to find feasible solution");
        }
        
        logger.endSubStep();
        logger.info("Complete solving island placement through Cp-SAT solver");
        return node2IslandLoc;
    }

    // constraint checkers
    private Boolean checkIslandSizeConstr() {
        logger.info("Start checking island size constraints");
        List<Double>[][] islandSizes = new ArrayList[islandDim.getX()][islandDim.getY()];
        islandDim.traverse((Coordinate2D loc) -> {
            List<Double> zeroIsland = new ArrayList<>(Collections.nCopies(hyperGraph.getNodeWeightDim(), 0.0));
            islandSizes[loc.getX()][loc.getY()] = zeroIsland;
        });

        for (int nodeId = 0; nodeId < hyperGraph.getNodeNum(); nodeId++) {
            Coordinate2D loc = node2IslandLoc.get(nodeId);
            assert loc.getX() >= 0 && loc.getX() < islandDim.getX();
            assert loc.getY() >= 0 && loc.getY() < islandDim.getY();
            List<Double> islandSize = islandSizes[loc.getX()][loc.getY()];
            List<Double> nodeWeight = hyperGraph.getWeightsOfNode(nodeId);
            HyperGraph.accuWeights(islandSize, nodeWeight);
        }

        Boolean hasViolation = false;
        for (int x = 0; x < islandDim.getX(); x++) {
            for (int y = 0; y < islandDim.getY(); y++) {
                List<Double> islandSize = islandSizes[x][y];
                double islandSizeSum = hyperGraph.getNodeWeightsSum(islandSize);
                double upperBound = hyperGraph.getNodeWeightsSum(islandSizeUpperBound);
                if (islandSizeSum > upperBound) {
                    hasViolation = true;
                    logger.info(String.format("The size of island%s(%.3f) exceeds the upper bound(%.3f)", Coordinate2D.of(x, y), islandSizeSum, upperBound));
                }
            }
        }

        logger.info("Complete checking island size constraints");
        return !hasViolation;
    }

    private Boolean checkEdgeLengthConstr() {
        logger.info("Start checking edge length constraints");
        Integer violatedEdgeNum = 0;

        for (int edgeId = 0; edgeId < hyperGraph.getEdgeNum(); edgeId++) {
            Set<Coordinate2D> nodeLocs = new HashSet<>();

            for (int nodeId : hyperGraph.getNodesOfEdge(edgeId)) {
                nodeLocs.add(node2IslandLoc.get(nodeId));
            }

            if (nodeLocs.size() > 2) {
                violatedEdgeNum++;
            } else if (nodeLocs.size() == 2) {
                Iterator<Coordinate2D> iter = nodeLocs.iterator();
                Coordinate2D loc0 = iter.next();
                Coordinate2D loc1 = iter.next();

                if (loc0.getManhattanDist(loc1) > 1) {
                    violatedEdgeNum++;
                }
            }

        }
        logger.info("The number of violated edges: " + violatedEdgeNum);

        logger.info("Complete checking edge length constraints");
        return violatedEdgeNum == 0;
    }

    private Coordinate2D getIslandLocFromIndex(int index) {
        // island is distribution:
        // 1 3 5
        // 0 2 4
        return Coordinate2D.of(index / islandDim.getY(), index % islandDim.getY());
    }

    private void setIslandSizeBound() {
        List<Double> totalNodeWeights = hyperGraph.getTotalNodeWeight();

        islandSizeUpperBound = new ArrayList<>();
        for (int i = 0; i < totalNodeWeights.size(); i++) {
            Double nodeWeight = totalNodeWeights.get(i);
            Double imbalanceFac = config.imbFactors.get(i);

            Double upperBound = nodeWeight * ((1.0 / islandNum) + imbalanceFac);
            islandSizeUpperBound.add(upperBound);
        }
        logger.info("Total node weights: " + totalNodeWeights);
        logger.info("Island size upper bounds: " + islandSizeUpperBound);
    }
}
