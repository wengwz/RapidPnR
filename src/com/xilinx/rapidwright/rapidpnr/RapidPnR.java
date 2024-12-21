package com.xilinx.rapidwright.rapidpnr;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.NetlistUtils;


public class RapidPnR extends AbstractApplication {
    
    public enum RapidPnRStep {
        READ_DESIGN,
        DATABASE_SETUP,
        NETLIST_ABSTRACTION,
        ISLAND_PLACEMENT,
        PHYSICAL_IMPLEMENTATION;
    
        public static RapidPnRStep[] getOrderedSteps() {
            return new RapidPnRStep[] {
                READ_DESIGN, DATABASE_SETUP, NETLIST_ABSTRACTION, ISLAND_PLACEMENT, PHYSICAL_IMPLEMENTATION
            };
        }
    
        public static RapidPnRStep getLastStep() {
            return PHYSICAL_IMPLEMENTATION;
        }
    };

    private AbstractNetlist abstractNetlist;
    private List<Coordinate2D> groupPlaceResults;

    public RapidPnR(String jsonFilePath, Boolean enableLogger) {
        super(jsonFilePath, enableLogger);
    }

    private void runNetlistAbstraction() {
        abstractNetlist = new EdgeBasedClustering(logger, EdgeBasedClustering.CLBAwareFilter);
        //abstractNetlist = new EdgeBasedClustering(logger);
        abstractNetlist.buildAbstractNetlist(netlistDatabase);

        abstractNetlist.printAbstractNetlistInfo();
    }

    private void runIslandPlacement() {
        AbstractIslandPlacer islandPlacer;
        //AbstractIslandPlacer islandPlacer = new IslandPlacer(logger, dirManager, designParams);
        islandPlacer = new IslandPlacer2(logger, dirManager, designParams);
        // islandPlacer = new MultilevelIslandPlacer(logger, dirManager, designParams);
        groupPlaceResults = islandPlacer.run(abstractNetlist);
    }

    private void runPhysicalImplementation() {
        AbstractPhysicalImpl physicalImpl;

        //physicalImpl = new CompletePnR(logger, dirManager, designParams, netlistDatabase, true);
        //physicalImpl = new IncrementalIslandPnR(logger, dirManager, designParams, netlistDatabase);
        //physicalImpl = new ParallelIslandPnR(logger, dirManager, designParams, netlistDatabase);
        physicalImpl = new FastParallelIslandPnR(logger, dirManager, designParams, netlistDatabase);
        //physicalImpl = new FasterParallelIslandPnR(logger, dirManager, designParams, netlistDatabase);
        
        physicalImpl.run(abstractNetlist, groupPlaceResults);
    }

    public void run(RapidPnRStep endStep) {
        logger.info("Start running RapidPnR");
        logger.newSubStep();

        RapidPnRStep[] orderedSteps = RapidPnRStep.getOrderedSteps();

        for (RapidPnRStep step : orderedSteps) {
            switch (step) {
                case READ_DESIGN:
                    readInputDesign();
                    break;

                case DATABASE_SETUP:
                    setupNetlistDatabase();
                    break;
                
                case NETLIST_ABSTRACTION:
                    runNetlistAbstraction();
                    break;

                case ISLAND_PLACEMENT:
                    runIslandPlacement();
                    break;
                
                case PHYSICAL_IMPLEMENTATION:
                    runPhysicalImplementation();
                    break;
                
                default:
                    break;
            }

            if (step == endStep) {
                break;
            }
        }

        logger.endSubStep();
        logger.info("Complete running RapidPnR");
    }

    public void run() {
        run(RapidPnRStep.getLastStep());
    }

    public static void reportResUtilsInBatch(Path outputPath, List<String> jsonFiles) {
        Map<String, Map<String, Integer>> design2ResUtilsMap = new HashMap<>();
        String reportContent = "";

        for (String jsonFilePath : jsonFiles) {
            RapidPnR rapidPnR = new RapidPnR(jsonFilePath, false);
            rapidPnR.run(RapidPnRStep.DATABASE_SETUP);
            String designName = rapidPnR.designParams.getDesignName();
            Map<EDIFCell, Integer> resUtils = rapidPnR.netlistDatabase.netlistLeafCellUtilMap;
            Map<String, Integer> resUtilsMap = NetlistUtils.getResTypeUtils(resUtils);
            Integer totalLeafCellNum = rapidPnR.netlistDatabase.netlistLeafCellNum;
            resUtilsMap.put("Total", totalLeafCellNum);

            design2ResUtilsMap.put(designName, resUtilsMap);
        }

        for (String designName : design2ResUtilsMap.keySet()) {
            Map<String, Integer> resUtilsMap = design2ResUtilsMap.get(designName);
            reportContent += designName + ":\n";
            for (String resType : resUtilsMap.keySet()) {
                Integer resNum = resUtilsMap.get(resType);
                reportContent += "  " + resType + ": " + resNum + "\n";
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            writer.write(reportContent);
        } catch (IOException e) {
            e.printStackTrace();
        }
        
    }

    public static void main(String[] args) {
        //String jsonFilePath = "workspace/json/nvdla-small.json";
        //String jsonFilePath = "workspace/json/nvdla-small-256.json"; //seed=1001
        //String jsonFilePath = "workspace/json/nvdla-small-256-full.json";
        //String jsonFilePath = "workspace/json/blue-rdma.json";
        //String jsonFilePath = "workspace/json/corundum.json";
        //String jsonFilePath = "workspace/json/ntt-large.json";
        //String jsonFilePath = "workspace/json/ntt-small.json";
        //String jsonFilePath = "workspace/json/toooba.json";
        //String jsonFilePath = "workspace/json/tensil.json";
        
        //String jsonFilePath = "workspace/json/cnn13x2.json";
        //String jsonFilePath = "workspace/json/miaow.json";
        //String jsonFilePath = "workspace/json/mm_int16.json";
        //String jsonFilePath = "workspace/json/hardcaml-ntt.json";
        String jsonFilePath = "workspace/json/minimap-small.json";
        //String jsonFilePath = "workspace/json/minimap.json";
        //String jsonFilePath = "workspace/json/minimap-tapa.json";
        //String jsonFilePath = "workspace/json/isp.json";

        //String jsonFilePath = "workspace/json/ispd16-fpga02.json";
        //String jsonFilePath = "workspace/json/ispd16-exp03.json";
        //String jsonFilePath = "workspace/json/ispd16-fpga04.json";
        
        RapidPnR rapidPnR = new RapidPnR(jsonFilePath, true);
        //rapidPnR.run(RapidPnRStep.NETLIST_ABSTRACTION);
        rapidPnR.run(RapidPnRStep.ISLAND_PLACEMENT);
        //rapidPnR.run(RapidPnRStep.PHYSICAL_IMPLEMENTATION);

        // Path outputPath = Path.of("workspace/report/res_utils.txt");
        // List<String> jsonFiles = List.of(
        //     "workspace/json/nvdla-small.json",
        //     "workspace/json/nvdla-small-256.json",
        //     "workspace/json/nvdla-small-256-full.json",
        //     "workspace/json/blue-rdma.json",
        //     "workspace/json/corundum.json",
        //     "workspace/json/supranational-ntt.json",
        //     "workspace/json/ntt-small.json"
        // );
        // reportResUtilsInBatch(outputPath, jsonFiles);
    }

}