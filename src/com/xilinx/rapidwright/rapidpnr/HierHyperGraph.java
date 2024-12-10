package com.xilinx.rapidwright.rapidpnr;

import java.util.List;
import java.util.Set;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

public class HierHyperGraph extends HyperGraph {

    HierHyperGraph parentGraph;
    List<List<Integer>> child2ParentMap;
    List<Boolean> isExternalVirtualNode;

    public HierHyperGraph(List<Double> nodeWeightFac, List<Double> edgeWeightFac) {
        super(nodeWeightFac, edgeWeightFac);
        this.parentGraph = null;
        this.child2ParentMap = new ArrayList<>();
        this.isExternalVirtualNode = new ArrayList<>();
    }

    public HierHyperGraph(HierHyperGraph parentGraph, List<List<Integer>> child2Parent, boolean addExtVirtualNodes) {
        super(parentGraph.getNodeWeightsFactor(), parentGraph.getEdgeWeightsFactor());

        this.parentGraph = parentGraph;
        this.child2ParentMap = new ArrayList<>();
        this.isExternalVirtualNode = new ArrayList<>();

        buildHyperGraph(child2Parent, addExtVirtualNodes);
    }
    
    private void buildHyperGraph(List<List<Integer>> child2Parent, boolean addExtVirtualNodes) {
        List<Integer> parent2Child = new ArrayList<>(Collections.nCopies(parentGraph.getNodeNum(), -1));

        for (int childId = 0; childId < child2Parent.size(); childId++) {
            for (int parentId : child2Parent.get(childId)) {
                assert parentId < parentGraph.getNodeNum(): "Index of parent node is out of range";
                assert parent2Child.get(parentId) == -1: String.format("Parent node-%d are included in more than one child nodes", parentId);
                parent2Child.set(parentId, childId);
            }
        }

        // add nodes to child hypergraph
        for (List<Integer> parents : child2Parent) {
            List<Boolean> isVirtualParents = new ArrayList<>();
            List<Double> weights = new ArrayList<>(Collections.nCopies(nodeWeightDim, 0.0));
            for (int parentNodeId : parents) {
                accuWeights(weights, parentGraph.getWeightsOfNode(parentNodeId));

                isVirtualParents.add(parentGraph.isExtVirtualNode(parentNodeId));
            }

            boolean hasVirtualParent = isVirtualParents.contains(true);
            assert !hasVirtualParent: "Virtual nodes aren't permitted in child2Parent";

            super.addNode(weights);
            this.child2ParentMap.add(new ArrayList<>(parents));
            this.isExternalVirtualNode.add(false);
        }

        // add edges to child hypergraph
        for (int parentEdgeId = 0; parentEdgeId < parentGraph.getEdgeNum(); parentEdgeId++) {
            Set<Integer> childNodeIds = new HashSet<>();

            Set<Integer> externalNodes = new HashSet<>();

            for (int parentNodeId : parentGraph.getNodesOfEdge(parentEdgeId)) {
                if (parent2Child.get(parentNodeId) != -1 && !parentGraph.isExtVirtualNode(parentNodeId)) {
                    childNodeIds.add(parent2Child.get(parentNodeId));
                } else {
                    externalNodes.add(parentNodeId);
                }
            }

            boolean hasExternalNode = externalNodes.size() > 0;
            boolean hasInternalNode = childNodeIds.size() > 0;
            if (addExtVirtualNodes && hasExternalNode && hasInternalNode) {
                for (int extNodeId : externalNodes) {
                    if (parent2Child.get(extNodeId) != -1) {
                        childNodeIds.add(parent2Child.get(extNodeId));
                    } else {
                        int childNodeId = super.addNode(Collections.nCopies(nodeWeightDim, 0.0));
                        child2ParentMap.add(new ArrayList<>(Collections.singletonList(extNodeId)));
                        isExternalVirtualNode.add(true);
                        parent2Child.set(extNodeId, childNodeId);
                        childNodeIds.add(childNodeId);
                    }
                }
            }

            if (childNodeIds.size() > 1) {
                addEdge(childNodeIds, parentGraph.getWeightsOfEdge(parentEdgeId));
            }
        }
    }

    @Override
    public int addNode(List<Double> weights) { // add nodes to root hierhypergraph
        int nodeId = super.addNode(weights);
        child2ParentMap.add(new ArrayList<>());
        isExternalVirtualNode.add(false);
        return nodeId;
    }

    public HierHyperGraph createClusteredChildGraph(List<List<Integer>> cluster2Nodes, boolean addExtVirtualNodes) {
        return new HierHyperGraph(this, cluster2Nodes, addExtVirtualNodes);
    }

    // getters
    public boolean isExtVirtualNode(int nodeId) {
        return isExternalVirtualNode.get(nodeId);
    }

    public Boolean isRootGraph() {
        return parentGraph == null;
    }

    public List<Integer> getParentsOfNode(int nodeId) {
        assert nodeId < nodeNum;
        return Collections.unmodifiableList(child2ParentMap.get(nodeId));
    }

    public List<Integer> getRootParentsOfNode(int nodeId) {
        assert nodeId < nodeNum;

        List<Integer> rootParents = new ArrayList<>();

        if (isRootGraph()) {
            rootParents.add(nodeId);
        } else {
            for (int parentId : getParentsOfNode(nodeId)) {
                rootParents.addAll(parentGraph.getRootParentsOfNode(parentId));
            }
        }

        return rootParents;
    }

    public List<Integer> getPartResultOfParent(List<Integer> childPartResult, List<Integer> parentPartResult) {
        assert childPartResult.size() == nodeNum;
        assert parentPartResult.size() == parentGraph.getNodeNum();
        
        for (int childNodeId = 0; childNodeId < childPartResult.size(); childNodeId++) {
            for (int parentNodeId : child2ParentMap.get(childNodeId)) {
                parentPartResult.set(parentNodeId, childPartResult.get(childNodeId));
            }
        }

        return parentPartResult;
    }
}
