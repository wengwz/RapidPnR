package com.xilinx.rapidwright.rapidpnr;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.rapidpnr.utils.NetlistUtils;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;


public class RapidPnR {

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

    protected DirectoryManager dirManager;
    protected RuntimeTrackerTree rootTimer;
    protected HierarchicalLogger logger;

    protected DesignParams designParams;
    protected Design inputDesign;
    protected NetlistDatabase netlistDatabase;

    private AbstractNetlist abstractNetlist;
    private List<Coordinate2D> abstractNodeLoc;

    public RapidPnR(String jsonFilePath, Boolean enableLogger) {
        // read design parameters from json file
        Path jsonPath = Path.of(jsonFilePath).toAbsolutePath();
        designParams = new DesignParams(jsonPath);

        // setup directory manager
        dirManager = new DirectoryManager(designParams.getWorkDir());

        // setup logger
        setupLogger(enableLogger);

        // setup runtime tracker
        rootTimer = new RuntimeTrackerTree("RapidPnR", false);
    }

    protected void setupLogger(Boolean enableLogger) {
        Path logFilePath = dirManager.getRootDir().resolve("rapidPnR.log");

        if (enableLogger) {
            Level logLevel = designParams.isVerbose() ? Level.FINE : Level.INFO;
            logger = HierarchicalLogger.createLogger("application", logFilePath, true, logLevel);
        } else {
            logger = HierarchicalLogger.createPseduoLogger("application");
        }

        logger.info("Setup hierarchical logger for RapidPnR successfully");
    }

    public RuntimeTracker createSubTimer(String name) {
        return rootTimer.createRuntimeTracker(name, rootTimer.getRootRuntimeTracker());
    }

    protected void readInputDesign() {
        logger.info("Reading input design checkpoint: " + designParams.getInputDcpPath().toString());

        inputDesign = Design.readCheckpoint(designParams.getInputDcpPath().toString());

        logger.info("Read input design checkpoint successfully");
    }

    public void setupNetlistDatabase() {
        RuntimeTracker timer = createSubTimer("Setup NetlistDB");
        timer.start();

        logger.infoHeader("Setup Netlist Database");
        netlistDatabase = new NetlistDatabase(logger, inputDesign, designParams);
        
        logger.info("Information of netlist database:");
        logger.newSubStep();
        netlistDatabase.printCellLibraryInfo();
        netlistDatabase.printNetlistInfo();
        logger.endSubStep();

        timer.stop();
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

        islandPlacer = new IslandPlacer(logger, dirManager, designParams);
        abstractNodeLoc = islandPlacer.run(abstractNetlist);
        timer.stop();
    }

    private void runPhysicalImplementation() {
        logger.infoHeader("Physical Implementation");
        RuntimeTracker timer = createSubTimer("Physical Implementation");
        timer.start();
        AbstractPhysicalImpl physicalImpl;

        physicalImpl = new FastParallelIslandPnR(logger, dirManager, designParams, netlistDatabase);
        //physicalImpl = new CompletePnR(logger, dirManager, designParams, netlistDatabase, true);
        //physicalImpl = new IncrementalIslandPnR(logger, dirManager, designParams, netlistDatabase);
        //physicalImpl = new ParallelIslandPnR(logger, dirManager, designParams, netlistDatabase);
        
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

        String jsonFilePath = "test/rapidpnr/config/blue-rdma.json";
        // String jsonFilePath = "test/rapidpnr/config/corundum.json";
        // String jsonFilePath = "test/rapidpnr/config/hardcaml-ntt.json";
        // String jsonFilePath = "test/rapidpnr/config/ispd-fpga02.json";
        // String jsonFilePath = "test/rapidpnr/config/minimap.json";
        // String jsonFilePath = "test/rapidpnr/config/ntt-large.json";
        // String jsonFilePath = "test/rapidpnr/config/ntt-small.json";
        // String jsonFilePath = "test/rapidpnr/config/nvdla-1.json";
        // String jsonFilePath = "test/rapidpnr/config/nvdla-2.json";
        // String jsonFilePath = "test/rapidpnr/config/nvdla-3.json";

        RapidPnR rapidPnR = new RapidPnR(jsonFilePath, true);
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
