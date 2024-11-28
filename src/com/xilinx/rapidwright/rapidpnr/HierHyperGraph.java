package com.xilinx.rapidwright.rapidpnr;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

public class HierHyperGraph extends HyperGraph {

    HyperGraph parentGraph;
    List<List<Integer>> child2Parent;

    public HierHyperGraph(HyperGraph parentGraph, List<List<Integer>> child2Parent) {
        super(parentGraph.getNodeWeightsFactor(), parentGraph.getEdgeWeightsFactor());

        this.parentGraph = parentGraph;
        this.child2Parent = child2Parent;

        buildHyperGraph();
    }
    

    private void buildHyperGraph() {
        List<Integer> parent2Child = new ArrayList<>(Collections.nCopies(parentGraph.getNodeNum(), -1));

        for (int childId = 0; childId < child2Parent.size(); childId++) {
            for (int parentId : child2Parent.get(childId)) {
                assert parent2Child.get(parentId) == -1: String.format("Parent node-%d are included in two child nodes", parentId);
                parent2Child.set(parentId, childId);
            }
        }

        // add nodes to child hypergraph
        for (List<Integer> parents : child2Parent) {
            List<Double> weights = new ArrayList<>(Collections.nCopies(nodeWeightDim, 0.0));
            for (int parentNodeId : parents) {
                accuWeights(weights, parentGraph.getWeightsOfNode(parentNodeId));
            }

            addNode(weights);
        }

        // add edges to child hypergraph
        for (int parentEdgeId = 0; parentEdgeId < parentGraph.getEdgeNum(); parentEdgeId++) {
            Set<Integer> childNodeIds = new HashSet<>();
            for (int parentNodeId : parentGraph.getNodesOfEdge(parentEdgeId)) {
                if (parent2Child.get(parentNodeId) != -1) {
                    childNodeIds.add(parent2Child.get(parentNodeId));
                }
            }

            if (childNodeIds.size() > 1) {
                List<Double> weights = new ArrayList<>(parentGraph.getWeightsOfEdge(parentEdgeId));
                addEdge(childNodeIds, weights);
            }
        }
    }

    public List<Integer> getPartResultOfParent(List<Integer> childPartResult, List<Integer> parentPartResult) {
        assert childPartResult.size() == nodeNum;
        assert parentPartResult.size() == parentGraph.getNodeNum();
        
        for (int childNodeId = 0; childNodeId < childPartResult.size(); childNodeId++) {
            for (int parentNodeId : child2Parent.get(childNodeId)) {
                parentPartResult.set(parentNodeId, childPartResult.get(childNodeId));
            }
        }

        return parentPartResult;
    }
}
