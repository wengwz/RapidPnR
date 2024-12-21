package com.xilinx.rapidwright.rapidpnr.timing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.traverse.TopologicalOrderIterator;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.rapidpnr.NetlistDatabase;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.NetlistUtils;

public class SimpleTimingPredictor {
    private HierarchicalLogger logger;
    private NetlistDatabase netlistDB;

    private EDIFNetlist topNetlist;
    private EDIFCell topCell;

    private DefaultDirectedGraph<TimingVertex, TimingEdge> timingGraph;
    private Map<EDIFPortInst, TimingVertex> portInstToVertexMap;

    public SimpleTimingPredictor(HierarchicalLogger logger, NetlistDatabase netlistDB) {
        this.logger = logger;
        this.netlistDB = netlistDB;

        this.topNetlist = netlistDB.getTopNetlist();
        this.topCell = topNetlist.getTopCell();

        buildTimingGraph();
        computeLogicLevelAndFanout();
    }

    public Integer getDriveLogicLevelOf(EDIFPortInst portInst) {
        assert portInstToVertexMap.containsKey(portInst);
        TimingVertex vertex = portInstToVertexMap.get(portInst);
        return vertex.getDriveLogicLevel();
    }

    public Integer getRecvLogicLevelOf(EDIFPortInst portInst) {
        assert portInstToVertexMap.containsKey(portInst);
        TimingVertex vertex = portInstToVertexMap.get(portInst);
        return vertex.getRecvLogicLevel();
    }

    public Integer getDriveFanoutOf(EDIFPortInst portInst) {
        assert portInstToVertexMap.containsKey(portInst);
        TimingVertex vertex = portInstToVertexMap.get(portInst);
        return vertex.getDriveFanout();
    }

    public Integer getRecvFanoutOf(EDIFPortInst portInst) {
        assert portInstToVertexMap.containsKey(portInst);
        TimingVertex vertex = portInstToVertexMap.get(portInst);
        return vertex.getRecvFanout();
    }

    public Double getDriveDelayOf(EDIFPortInst portInst) {
        assert portInstToVertexMap.containsKey(portInst);
        TimingVertex vertex = portInstToVertexMap.get(portInst);
        return vertex.getDriveDelay();
    }

    public Double getRecvDelayOf(EDIFPortInst portInst) {
        assert portInstToVertexMap.containsKey(portInst);
        TimingVertex vertex = portInstToVertexMap.get(portInst);
        return vertex.getRecvDelay();
    }

    public Double predictOutputDelayOf(EDIFPortInst portInst, Double clkPeriod) {
        assert portInstToVertexMap.containsKey(portInst);
        TimingVertex vertex = portInstToVertexMap.get(portInst);

        // Integer totalLogicLevel = vertex.getDriveLogicLevel() + vertex.getRecvLogicLevel();

        // Double ratio = (vertex.getDriveLogicLevel() + 1.0) / (totalLogicLevel + 2.0);
        // String mainClkName = designParams.getMainClkName();
        // Double mainClkPeriod = designParams.getClkPeriod(mainClkName);

        double totalDelay = vertex.getDriveDelay() + vertex.getRecvDelay();
        Double ratio = vertex.getDriveDelay() / totalDelay;
        Double estimatedDelay = ratio * clkPeriod;

        logger.info(String.format("Prediction of output delay of %s: drive-delay=%f recv-delay=%f delay=%f", portInst.getFullName(), vertex.getDriveDelay(), vertex.getRecvDelay(), estimatedDelay));

        return estimatedDelay;
    }

    public Double predictInputDelayOf(EDIFPortInst portInst, Double clkPeriod) {
        assert portInstToVertexMap.containsKey(portInst);

        TimingVertex vertex = portInstToVertexMap.get(portInst);

        // Integer totalLogicLevel = vertex.getDriveLogicLevel() + vertex.getRecvLogicLevel();
        // Double ratio = (vertex.getRecvLogicLevel() + 1.0) / (totalLogicLevel + 2.0);

        double totalDelay = vertex.getDriveDelay() + vertex.getRecvDelay();
        Double ratio = vertex.getRecvDelay() / totalDelay;
        Double estimatedDelay = ratio * clkPeriod;

        logger.info(String.format("Prediction of input delay of %s: drive-delay=%f recv-delay=%f delay=%f", portInst.getFullName(), vertex.getDriveDelay(), vertex.getRecvDelay(), estimatedDelay));

        return estimatedDelay;
    }

    private void buildTimingGraph() {
        logger.info("Start building timing graph");

        timingGraph = new DefaultDirectedGraph<>(TimingEdge.class);
        portInstToVertexMap = new HashMap<>();

        // add net edges
        for (EDIFNet net : topCell.getNets()) { // only consider top-level nets
            if (net.isVCC() || net.isGND()) continue;
            if (netlistDB.isGlobalClockNet(net)) continue;
            if (netlistDB.isGlobalResetNet(net)) continue;
            if (netlistDB.isIgnoreNet(net)) continue;
            if (netlistDB.isIllegalNet(net)) continue;

            TimingVertex srcVertex = null;
            Set<TimingVertex> dstVertices = new HashSet<>();

            for (EDIFPortInst portInst : net.getPortInsts()) {
                EDIFCellInst cellInst = portInst.getCellInst();

                TimingVertex vertex = new TimingVertex(portInst.getFullName(), portInst);
                timingGraph.addVertex(vertex);
                portInstToVertexMap.put(portInst, vertex);

                if (cellInst == null) { // top-level port
                    if (portInst.isInput()) {
                        assert srcVertex == null: "Multiple source vertices in a net";
                        srcVertex = vertex;
                    } else {
                        dstVertices.add(vertex);
                    }
                } else {
                    if (portInst.isInput()) {
                        dstVertices.add(vertex);
                    } else {
                        assert srcVertex == null: "Multiple source vertices in a net";
                        srcVertex = vertex;
                    }
                }
            }

            // add timing edges
            assert srcVertex != null && !dstVertices.isEmpty();
            int netFanout = dstVertices.size();
            for (TimingVertex dstVertex : dstVertices) {
                TimingEdge edge = new TimingEdge(false, netFanout);
                assert timingGraph.addEdge(srcVertex, dstVertex, edge);
            }
        }

        // add logical timing edges
        for (EDIFCellInst cellInst : topCell.getCellInsts()) {
            if (cellInst.getCellType().isStaticSource()) continue;
            if (NetlistUtils.isSequentialLogic(cellInst)) continue;
            if (!cellInst.getCellType().isLeafCellOrBlackBox()) continue;

            List<EDIFPortInst> inputPorts = new ArrayList<>();
            List<EDIFPortInst> outputPorts = new ArrayList<>();

            for (EDIFPortInst portInst : cellInst.getPortInsts()) {
                if (portInst.isInput()) {
                    inputPorts.add(portInst);
                } else {
                    outputPorts.add(portInst);
                }
            }
            assert inputPorts.size() >= 1 && outputPorts.size() >= 1;

            for (EDIFPortInst inputPort : inputPorts) {
                TimingVertex srcVertex = portInstToVertexMap.get(inputPort);

                if (srcVertex == null) {
                    EDIFNet incidentNet = inputPort.getNet();
                    assert incidentNet.isGND() || incidentNet.isVCC() || 
                           netlistDB.isIgnoreNet(incidentNet) || netlistDB.isIllegalNet(incidentNet) || 
                           netlistDB.isGlobalResetNet(incidentNet);
                    continue;
                }

                for (EDIFPortInst outputPort : outputPorts) {
                    TimingVertex dstVertex = portInstToVertexMap.get(outputPort);
                    assert dstVertex != null;

                    TimingEdge edge = new TimingEdge(true, 0);
                    assert timingGraph.addEdge(srcVertex, dstVertex, edge);
                }
            }
        }

        // check timing graph
        for (TimingVertex vertex : timingGraph.vertexSet()) {
            if (vertex.isStartpoint()) {
                assert timingGraph.inDegreeOf(vertex) == 0;
            }
            if (vertex.isEndpoint()) {
                assert timingGraph.outDegreeOf(vertex) == 0;
            }
        }

        logger.info("Complete building timing graph");

    }

    private void computeLogicLevelAndFanout() {
        logger.info("Start computing logic level, fanout and delay of timing vertices");
        List<TimingVertex> orderedTimingVertexs = new ArrayList<>();
        TopologicalOrderIterator<TimingVertex, TimingEdge> iterator = new TopologicalOrderIterator<>(timingGraph);

        while (iterator.hasNext()) {
            TimingVertex vertex = iterator.next();
            orderedTimingVertexs.add(vertex);
        }

        // forward propagation
        for (TimingVertex srcVertex : orderedTimingVertexs) {
            for (TimingEdge outEdge : timingGraph.outgoingEdgesOf(srcVertex)) {
                TimingVertex dstVertex = timingGraph.getEdgeTarget(outEdge);
                Integer accuFanout = srcVertex.getRecvFanout() + outEdge.getNetFanout();
                dstVertex.setMaxRecvFanout(accuFanout);

                Integer accuLogicLevel = srcVertex.getRecvLogicLevel();
                if (outEdge.isLogic()) {
                    accuLogicLevel += 1;
                }
                dstVertex.setMaxRecvLogicLevel(accuLogicLevel);

                double accuDelay = srcVertex.getRecvDelay() + outEdge.predictDelay();
                dstVertex.setMaxRecvDelay(accuDelay);
            }
        }

        Collections.reverse(orderedTimingVertexs);

        // backward propagation
        for (TimingVertex dstVertex : orderedTimingVertexs) {
            for (TimingEdge inEdge : timingGraph.incomingEdgesOf(dstVertex)) {
                TimingVertex srcVertex = timingGraph.getEdgeSource(inEdge);
                Integer accuFanout = dstVertex.getDriveFanout() + inEdge.getNetFanout();
                srcVertex.setMaxDriveFanout(accuFanout);

                Integer accuLogicLevel = dstVertex.getDriveLogicLevel();
                if (inEdge.isLogic()) {
                    accuLogicLevel += 1;
                }
                srcVertex.setMaxDriveLogicLevel(accuLogicLevel);

                double accuDelay = dstVertex.getDriveDelay() + inEdge.predictDelay();
                srcVertex.setMaxDriveDelay(accuDelay);
            }
        }

        logger.info("Complete computing logic level, fanout and delay of timing vertices");
    }

    public static class TestTimingVertex {
        public String name;
        public Integer driveLogicLevel = 0;
        public Integer driveFanout = 0;
        public Integer recvLogiceLevel = 0;
        public Integer recvFanout = 0;

        public TestTimingVertex(Integer id) {
            this.name = id.toString();
        }

        public String toString() {
            return name;
        }
    
        public boolean equals(Object o) {
            if (o instanceof TestTimingVertex)
                return ((TestTimingVertex)o).name.equals(name);
            return false;
        }
    
        public int compareTo(Object o) {
            if (o instanceof TestTimingVertex)
                return name.compareTo(((TestTimingVertex)o).name);
            return -1;
        }
    
        public int hashCode() {
            return name.hashCode();
        }
    };

    public static void main(String[] args) {

        DefaultDirectedGraph<TestTimingVertex, TimingEdge> graph = new DefaultDirectedGraph<>(TimingEdge.class);

        List<TestTimingVertex> vertices = new ArrayList<>();
        for (int id = 0; id < 9; id++) {
            TestTimingVertex vertex = new TestTimingVertex(id);
            graph.addVertex(vertex);
            vertices.add(vertex);
        }

        List<TimingEdge> edges = new ArrayList<>();
        for (int edgeId = 0; edgeId < 10; edgeId++) {
            TimingEdge edge = new TimingEdge(false, 1);
            edges.add(edge);
        }

        graph.addEdge(vertices.get(0), vertices.get(1), edges.get(0));
        graph.addEdge(vertices.get(0), vertices.get(2), edges.get(1));
        graph.addEdge(vertices.get(0), vertices.get(3), edges.get(2));
        graph.addEdge(vertices.get(1), vertices.get(4), edges.get(3));
        graph.addEdge(vertices.get(2), vertices.get(3), edges.get(4));
        graph.addEdge(vertices.get(2), vertices.get(6), edges.get(5));
        graph.addEdge(vertices.get(3), vertices.get(5), edges.get(6));
        graph.addEdge(vertices.get(6), vertices.get(5), edges.get(7));
        graph.addEdge(vertices.get(4), vertices.get(5), edges.get(8));
        graph.addEdge(vertices.get(7), vertices.get(8), edges.get(9));


        List<TestTimingVertex> orderedTimingVertexs = new ArrayList<>();
        TopologicalOrderIterator<TestTimingVertex, TimingEdge> iterator = new TopologicalOrderIterator<>(graph);
        while (iterator.hasNext()) {
            TestTimingVertex vertex = iterator.next();
            orderedTimingVertexs.add(vertex);
        }

        for (TestTimingVertex vertex : orderedTimingVertexs) {
            System.out.println(vertex);
        }

        // forward propagation
        for (TestTimingVertex srcVertex : orderedTimingVertexs) {
            for (TimingEdge outEdge : graph.outgoingEdgesOf(srcVertex)) {
                TestTimingVertex dstVertex = graph.getEdgeTarget(outEdge);
                Integer accuFanout = srcVertex.recvFanout + outEdge.getNetFanout();
                if (accuFanout > dstVertex.recvFanout) {
                    dstVertex.recvFanout = accuFanout;
                }
            }
        }

        Collections.reverse(orderedTimingVertexs);

        // backward propagation
        for (TestTimingVertex dstVertex : orderedTimingVertexs) {
            for (TimingEdge inEdge : graph.incomingEdgesOf(dstVertex)) {
                TestTimingVertex srcVertex = graph.getEdgeSource(inEdge);
                Integer accuFanout = dstVertex.driveFanout + inEdge.getNetFanout();
                if (accuFanout > srcVertex.driveFanout) {
                    srcVertex.driveFanout = accuFanout;
                }
            }
        }

        for (TestTimingVertex vertex : orderedTimingVertexs) {
            System.out.println(vertex.name + " " + vertex.driveFanout + " " + vertex.recvFanout);
        }
    }


}
