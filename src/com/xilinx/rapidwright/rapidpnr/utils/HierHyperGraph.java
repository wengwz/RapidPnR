package com.xilinx.rapidwright.rapidpnr.utils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

public class HierHyperGraph extends HyperGraph {

    protected HierHyperGraph parentGraph;
    protected List<List<Integer>> child2ParentMap;
    protected List<Boolean> isExternalVirtualNode;
    protected Map<Integer, Integer> fixedNodes;

    public HierHyperGraph(List<Double> nodeWeightFac, List<Double> edgeWeightFac) {
        super(nodeWeightFac, edgeWeightFac);
        this.parentGraph = null;
        this.child2ParentMap = new ArrayList<>();
        this.isExternalVirtualNode = new ArrayList<>();
        fixedNodes = new HashMap<>();
    }

    public HierHyperGraph(HierHyperGraph parentGraph, List<List<Integer>> child2Parent, boolean addExtVirtualNodes) {
        super(parentGraph.getNodeWeightsFactor(), parentGraph.getEdgeWeightsFactor());

        this.parentGraph = parentGraph;
        this.child2ParentMap = new ArrayList<>();
        this.isExternalVirtualNode = new ArrayList<>();
        this.fixedNodes = new HashMap<>();

        buildChildGraph(child2Parent, addExtVirtualNodes);
    }

    public HierHyperGraph(HierHyperGraph parentGraph, List<List<Integer>> child2Parent, int blkNum, List<Integer> partResult) {
        super(parentGraph.getNodeWeightDim() * blkNum, parentGraph.getEdgeWeightDim());
        List<Double> nodeWeightFac = new ArrayList<>();
        for (int i = 0; i < blkNum; i++) {
            nodeWeightFac.addAll(parentGraph.getNodeWeightsFactor());
        }
        setNodeWeightsFactor(nodeWeightFac);
        setEdgeWeightsFactor(parentGraph.getEdgeWeightsFactor());

        this.parentGraph = parentGraph;
        this.child2ParentMap = new ArrayList<>();
        this.isExternalVirtualNode = new ArrayList<>();

        buildChildGraph(child2Parent, blkNum, partResult);
    }
    
    private void buildChildGraph(List<List<Integer>> child2Parent, boolean addExtVirtualNodes) {
        List<Integer> parent2Child = new ArrayList<>(Collections.nCopies(parentGraph.getNodeNum(), -1));

        Map<Integer, Integer> parentfixedNodes = parentGraph.getFixedNodes();

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
            Set<Integer> fixedBlkIds = new HashSet<>();

            for (int parentNodeId : parents) {
                accuWeights(weights, parentGraph.getWeightsOfNode(parentNodeId));

                isVirtualParents.add(parentGraph.isExtVirtualNode(parentNodeId));
                
                if (parentfixedNodes.containsKey(parentNodeId)) {
                    fixedBlkIds.add(parentfixedNodes.get(parentNodeId));
                }
            }

            int nodeId = super.addNode(weights);
            this.child2ParentMap.add(new ArrayList<>(parents));

            boolean hasVirtualParent = isVirtualParents.contains(true);
            assert !hasVirtualParent: "Virtual nodes aren't permitted in child2Parent";
            this.isExternalVirtualNode.add(false);

            assert fixedBlkIds.size() <= 1: "Multiple fixed blocks are assigned to a child node";
            if (fixedBlkIds.size() == 1) {
                fixedNodes.put(nodeId, fixedBlkIds.iterator().next());
            }
        }

        // add edges to child hypergraph
        for (int parentEdgeId = 0; parentEdgeId < parentGraph.getEdgeNum(); parentEdgeId++) {
            Set<Integer> childNodeIds = new HashSet<>();
            Set<Integer> externalNodes = new HashSet<>();

            for (int parentNodeId : parentGraph.getNodesOfEdge(parentEdgeId)) {
                int childId = parent2Child.get(parentNodeId);
                if (childId != -1 && !isExtVirtualNode(childId)) {
                    childNodeIds.add(childId);
                } else {
                    externalNodes.add(parentNodeId);
                }
            }

            boolean hasExternalNode = externalNodes.size() > 0;
            boolean hasInternalNode = childNodeIds.size() > 0;
            if (addExtVirtualNodes && hasExternalNode && hasInternalNode) {
                for (int extNodeId : externalNodes) {
                    int childId = parent2Child.get(extNodeId);
                    if (childId == -1) {
                        childId = super.addNode(Collections.nCopies(nodeWeightDim, 0.0));
                        child2ParentMap.add(new ArrayList<>(Arrays.asList(extNodeId)));
                        isExternalVirtualNode.add(true);
                        parent2Child.set(extNodeId, childId);
                    }
                    childNodeIds.add(childId);
                }
            }

            if (childNodeIds.size() > 1) {
                addEdge(childNodeIds, parentGraph.getWeightsOfEdge(parentEdgeId));
            }
        }
    }


    private void buildChildGraph(List<List<Integer>> child2Parent, int blkNum, List<Integer> partResult) {
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
            List<Double> totalWeights = new ArrayList<>(Collections.nCopies(nodeWeightDim, 0.0));
            
            for (int parentNodeId : parents) {
                List<Double> parentWeights = parentGraph.getWeightsOfNode(parentNodeId);
                int parentBlkId = partResult.get(parentNodeId);
                assert parentBlkId >= 0 && parentBlkId < blkNum: String.format("Partition result of parent node-%d is invalid", parentNodeId);
                
                List<Double> weights = new ArrayList<>(Collections.nCopies(nodeWeightDim, 0.0));
                for (int i = 0; i < parentWeights.size(); i++) {
                    weights.set(i + parentBlkId * parentWeights.size(), parentWeights.get(i));
                }

                accuWeights(totalWeights, weights);

                isVirtualParents.add(parentGraph.isExtVirtualNode(parentNodeId));
            }

            boolean hasVirtualParent = isVirtualParents.contains(true);
            assert !hasVirtualParent: "Virtual nodes aren't permitted in child2Parent";

            super.addNode(totalWeights);
            this.child2ParentMap.add(new ArrayList<>(parents));
            this.isExternalVirtualNode.add(false);
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

    public HierHyperGraph createClusteredChildGraph(List<List<Integer>> cluster2Nodes, int blkNum, List<Integer> partResult) {
        return new HierHyperGraph(this, cluster2Nodes, blkNum, partResult);
    }

    public void setFixedNodes(Map<Integer, Integer> fixedNodes) {
        this.fixedNodes = new HashMap<>(fixedNodes);
    }

    // getters
    public Map<Integer, Integer> getFixedNodes() {
        return Collections.unmodifiableMap(fixedNodes);
    }

    public boolean isExtVirtualNode(int nodeId) {
        return isExternalVirtualNode.get(nodeId);
    }

    public Boolean isRootGraph() {
        return parentGraph == null;
    }

    public HierHyperGraph getParentGraph() {
        return parentGraph;
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

    public void updatePartResultOfParent(List<Integer> childPartResult, List<Integer> parentPartResult) {
        assert childPartResult.size() == nodeNum;
        assert parentPartResult.size() == parentGraph.getNodeNum();
        
        for (int childNodeId = 0; childNodeId < childPartResult.size(); childNodeId++) {
            for (int parentNodeId : child2ParentMap.get(childNodeId)) {
                parentPartResult.set(parentNodeId, childPartResult.get(childNodeId));
            }
        }
    }

    public void updateLocOfParent(List<Coordinate2D> childLocs, List<Coordinate2D> parentLocs) {
        assert childLocs.size() == nodeNum;
        assert parentLocs.size() == parentGraph.getNodeNum();
        for (int childId = 0; childId < nodeNum; childId++) {
            for (int parentId : child2ParentMap.get(childId)) {
                Coordinate2D childLoc = childLocs.get(childId);
                parentLocs.set(parentId, Coordinate2D.of(childLoc.getX(), childLoc.getY()));
            }
        }
    }

    public List<Integer> getPartResultOfParent(List<Integer> partResult) {
        assert partResult.size() == nodeNum;
        
        List<Integer> parentPartResult = new ArrayList<>(Collections.nCopies(parentGraph.getNodeNum(), -1));
        
        for (int childNodeId = 0; childNodeId < partResult.size(); childNodeId++) {
            for (int parentNodeId : child2ParentMap.get(childNodeId)) {
                parentPartResult.set(parentNodeId, partResult.get(childNodeId));
            }
        }

        return parentPartResult;
    }

    public List<Integer> getPartResultFromParent(List<Integer> parentPartRes) {
        assert parentPartRes.size() == parentGraph.getNodeNum();
        List<Integer> childPartRes = new ArrayList<>(Collections.nCopies(nodeNum, -1));

        for (int childNodeId = 0; childNodeId < nodeNum; childNodeId++) {
            for (int parentNodeId : child2ParentMap.get(childNodeId)) {

                int childPartId = childPartRes.get(childNodeId);
                int parentPartId = parentPartRes.get(parentNodeId);

                if (parentPartId == -1) {
                    continue;
                } else {
                    if (childPartId == -1) {
                        childPartRes.set(childNodeId, parentPartId);
                    } else {
                        assert childPartId == parentPartId: "Inconsistent partition result";
                    }
                }
            }
        }
        return childPartRes;
    }

    public int getHierarchicalLevel() {
        if (isRootGraph()) {
            return 0;
        } else {
            return 1 + parentGraph.getHierarchicalLevel();
        }
    }

    public List<Coordinate2D> getLocOfParent(List<Coordinate2D> loc) {
        assert loc.size() == nodeNum;
        List<Coordinate2D> parentLocs = new ArrayList<>(Collections.nCopies(parentGraph.getNodeNum(), null));

        for (int childNodeId = 0; childNodeId < nodeNum; childNodeId++) {
            for (int parentNodeId : child2ParentMap.get(childNodeId)) {
                parentLocs.set(parentNodeId, loc.get(childNodeId));
            }
        }

        return parentLocs;
    }

    public static HierHyperGraph convertToHierHyperGraph(HyperGraph hyperGraph) {
        HierHyperGraph hierHyperGraph = new HierHyperGraph(hyperGraph.getNodeWeightsFactor(), hyperGraph.getEdgeWeightsFactor());

        hierHyperGraph.nodeNum = hyperGraph.nodeNum;
        hierHyperGraph.edgeNum = hyperGraph.edgeNum;
        
        hierHyperGraph.node2Edges = hyperGraph.node2Edges;
        hierHyperGraph.node2Weights = hyperGraph.node2Weights;
        hierHyperGraph.edge2Nodes = hyperGraph.edge2Nodes;
        hierHyperGraph.edge2Weights = hyperGraph.edge2Weights;

        hierHyperGraph.parentGraph = null;
        hierHyperGraph.child2ParentMap = new ArrayList<>();
        hierHyperGraph.isExternalVirtualNode = new ArrayList<>();

        for (int nodeId = 0; nodeId < hierHyperGraph.getNodeNum(); nodeId++) {
            hierHyperGraph.child2ParentMap.add(new ArrayList<>());
            hierHyperGraph.isExternalVirtualNode.add(false);
        }

        return hierHyperGraph;
    }

}
