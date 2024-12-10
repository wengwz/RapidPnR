package com.xilinx.rapidwright.rapidpnr;

import java.util.Arrays;
import java.util.List;


public class RecursiveFloorPlacer extends AbstractApplication {

    protected AbstractNetlist abstractNetlist;
    protected List<Coordinate2D> islandPlaceResults;

    public RecursiveFloorPlacer(String jsonFilePath, Boolean enableLogger) {
        super(jsonFilePath, enableLogger);
    }

    public void run() {
        // read input design
        readInputDesign();

        // setup netlist database
        setupNetlistDatabase();

        // run netlist abstraction
        runNetlistAbstraction();

        // run island placement
        runIslandPlacement();

        // run physical implementation
        // runPhysicalImplementation();

    }

    public void runNetlistAbstraction() {
        abstractNetlist = new BasicAbstractNetlist(logger);
        abstractNetlist.buildAbstractNetlist(netlistDatabase);        
    }

    public void runIslandPlacement() {
        AbstractIslandPlacer islandPlacer = new RecursiveTreePlacer(logger, dirManager, designParams);
        islandPlaceResults = islandPlacer.run(abstractNetlist);
    }

    public void runPhysicalImplementation() {
        PhysicalImpl physicalImpl = new CompletePnR(logger, dirManager, designParams, netlistDatabase, false);
        physicalImpl.run(abstractNetlist, islandPlaceResults);
    }


    public static void main(String[] args) {
        String jsonFilePath = "workspace/json/blue-rdma-trial.json";

        RecursiveFloorPlacer recursiveFloorPlacer = new RecursiveFloorPlacer(jsonFilePath, true);
        recursiveFloorPlacer.run();

    }
}
