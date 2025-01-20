package com.xilinx.rapidwright.rapidpnr;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.NetlistUtils;
import com.xilinx.rapidwright.util.RuntimeTracker;


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
    private List<Coordinate2D> abstractNodeLoc;

    public RapidPnR(String jsonFilePath, Boolean enableLogger) {
        super(jsonFilePath, enableLogger);
    }

    private void runNetlistAbstraction() {
        RuntimeTracker timer = createSubTimer("Netlist Abstraction");
        timer.start();
        logger.infoHeader("Netlist Abstraction");
        int abstractLevel = designParams.getAbstractLevel();
        Predicate<EDIFNet> netFilter;
        if (abstractLevel == 7) {
            netFilter = EdgeBasedClustering.FFNetFilter;
        } else if (abstractLevel == 0) {
            netFilter = EdgeBasedClustering.basicNetFilter;            
        } else {
            netFilter = new EdgeBasedClustering.CLBAwareFilter(abstractLevel);
        }
        abstractNetlist = new EdgeBasedClustering(logger, netFilter);
        abstractNetlist.buildAbstractNetlist(netlistDatabase);
        abstractNetlist.printAbstractNetlistInfo();
        timer.stop();
    }

    private void runIslandPlacement() {
        RuntimeTracker timer = createSubTimer("Island Placement");
        timer.start();
        logger.infoHeader("Island Placement");
        AbstractIslandPlacer islandPlacer;
        //AbstractIslandPlacer islandPlacer = new IslandPlacer(logger, dirManager, designParams);
        //islandPlacer = new IslandPlacer2(logger, dirManager, designParams);
        islandPlacer = new IslandPlacer3(logger, dirManager, designParams);
        // islandPlacer = new MultilevelIslandPlacer(logger, dirManager, designParams);
        abstractNodeLoc = islandPlacer.run(abstractNetlist);
        timer.stop();
    }

    private void runPhysicalImplementation() {
        logger.infoHeader("Physical Implementation");
        RuntimeTracker timer = createSubTimer("Physical Implementation");
        timer.start();
        AbstractPhysicalImpl physicalImpl;

        //physicalImpl = new CompletePnR(logger, dirManager, designParams, netlistDatabase, true);
        //physicalImpl = new IncrementalIslandPnR(logger, dirManager, designParams, netlistDatabase);
        //physicalImpl = new ParallelIslandPnR(logger, dirManager, designParams, netlistDatabase);
        physicalImpl = new FastParallelIslandPnR(logger, dirManager, designParams, netlistDatabase);
        //physicalImpl = new FasterParallelIslandPnR(logger, dirManager, designParams, netlistDatabase);
        
        physicalImpl.run(abstractNetlist, abstractNodeLoc);
        timer.stop();
    }

    public void run(RapidPnRStep endStep) {
        logger.info("Start running RapidPnR");

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

        logger.info(rootTimer.toString());

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

    public static void reportNetlistAbstractionInBatch(Path outputPath, List<String> jsonFiles) {
        List<Integer> abstractLevels = List.of(7, 2);
        String reportContent = "";

        for (String jsonFilePath : jsonFiles) {
            for (int level : abstractLevels) {
                RapidPnR rapidPnR = new RapidPnR(jsonFilePath, false);
                rapidPnR.designParams.setAbstractLevel(level);
                rapidPnR.run(RapidPnRStep.NETLIST_ABSTRACTION);
                int totalLeafCellNum = rapidPnR.netlistDatabase.netlistLeafCellNum;
                List<Integer> node2LeafCellNum = rapidPnR.abstractNetlist.node2LeafCellNum;
                int maxLeafCellNum = node2LeafCellNum.stream().mapToInt(Integer::intValue).max().getAsInt();

                if (maxLeafCellNum > 1000) {
                    double maxCellNum = (double) maxLeafCellNum / 1000;
                    reportContent += String.format("%.1fk & ", maxCellNum);
                } else {
                    reportContent += maxLeafCellNum + " & ";
                }

                double ratio = (double) maxLeafCellNum / totalLeafCellNum * 100;
                reportContent += String.format("%.2f & ", ratio);

                int totalNodeNum = rapidPnR.abstractNetlist.getNodeNum();
                if (totalNodeNum > 1000) {
                    double totalNodeNumK = (double) totalNodeNum / 1000;
                    reportContent += String.format("%.1fk & ", totalNodeNumK);
                } else {
                    reportContent += totalNodeNum + " & ";
                }
            }
            reportContent += "\\\\ \\hline\n";
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            writer.write(reportContent);
        } catch (IOException e) {
            e.printStackTrace();
        }    
    }

    public static void main(String[] args) {
        // tpw:
        //String jsonFilePath = "workspace/json/nvdla-small.json";
        //String jsonFilePath = "workspace/json/nvdla-small-256.json"; //seed=1001
        //String jsonFilePath = "workspace/json/nvdla-small-256-full.json";
        //String jsonFilePath = "workspace/json/blue-rdma.json";
        //String jsonFilePath = "workspace/json/ntt-small.json";
        //String jsonFilePath = "workspace/json/corundum.json";
        //String jsonFilePath = "workspace/json/ntt-large.json";
        
        // none-tpw:
        //String jsonFilePath = "workspace/json/minimap-small.json";
        //String jsonFilePath = "workspace/json/tensil.json";
        //String jsonFilePath = "workspace/json/hardcaml-ntt.json";
        //String jsonFilePath = "workspace/json/ispd16-fpga02.json";
        //String jsonFilePath = "workspace/json/ispd16-fpga02-2x1.json";
        //String jsonFilePath = "workspace/json/ispd16-fpga04.json";
        //String jsonFilePath = "workspace/json/fireflyv2.json";
        //String jsonFilePath = "workspace/json/boom.json";
        //String jsonFilePath = "workspace/json/boom-large.json";
        //String jsonFilePath = "workspace/json/serpens.json";

        // other:
        //String jsonFilePath = "workspace/json/toooba.json";
        //String jsonFilePath = "workspace/json/miaow.json";
        //String jsonFilePath = "workspace/json/mm_int16.json";
        //String jsonFilePath = "workspace/json/minimap.json";
        //String jsonFilePath = "workspace/json/isp.json";

        // final
        //String jsonFilePath = "test/rapidpnr/benchmarks/blue-rdma.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/nvdla-1.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/nvdla-2.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/nvdla-3.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/ntt-small.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/ntt-large.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/corundum.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/minimap-small.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/minimap-large.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/ispd16-fpga02.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks/hardcaml-ntt.json";

        //String jsonFilePath = "test/rapidpnr/benchmarks2/blue-rdma.json";
        // String jsonFilePath = "test/rapidpnr/benchmarks2/corundum.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks2/ntt-small.json";
        String jsonFilePath = "test/rapidpnr/benchmarks2/nvdla-2.json";
        // String jsonFilePath = "test/rapidpnr/benchmarks2/corundum.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks2/hardcaml-ntt.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks2/ntt-large.json";
        //String jsonFilePath = "test/rapidpnr/benchmarks2/nvdla-3.json";

        RapidPnR rapidPnR = new RapidPnR(jsonFilePath, true);
        //rapidPnR.run(RapidPnRStep.NETLIST_ABSTRACTION);
        //rapidPnR.run(RapidPnRStep.ISLAND_PLACEMENT);
        rapidPnR.run(RapidPnRStep.PHYSICAL_IMPLEMENTATION);

        // List<String> jsonFiles = List.of(
        //     "test/rapidpnr/benchmarks/blue-rdma.json",
        //     "test/rapidpnr/benchmarks/nvdla-1.json",
        //     "test/rapidpnr/benchmarks/nvdla-2.json",
        //     "test/rapidpnr/benchmarks/nvdla-3.json",
        //     "test/rapidpnr/benchmarks/ntt-small.json",
        //     "test/rapidpnr/benchmarks/ntt-large.json",
        //     "test/rapidpnr/benchmarks/corundum.json",
        //     "test/rapidpnr/benchmarks/minimap-small.json",
        //     "test/rapidpnr/benchmarks/minimap-large.json",
        //     "test/rapidpnr/benchmarks/ispd16-fpga02.json",
        //     "test/rapidpnr/benchmarks/hardcaml-ntt.json"
        // );
        //Path outputPath = Path.of("test/rapidpnr/results/res_utils2.txt");
        //reportResUtilsInBatch(outputPath, jsonFiles);
        // Path outputPath = Path.of("test/rapidpnr/results/netlist-abs.txt");
        // reportNetlistAbstractionInBatch(outputPath, jsonFiles);
    }

}