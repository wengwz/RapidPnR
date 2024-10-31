package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;

import com.xilinx.rapidwright.design.Design;


public class RapidPnR {

    private DesignParams designParams;
    private DirectoryManager dirManager;
    private HierarchicalLogger logger;

    private Design inputDesign;
    private NetlistDatabase netlistDatabase;
    private AbstractNetlist abstractNetlist;
    private List<Coordinate2D> groupPlaceResults;

    private Design outputDesign;


    public RapidPnR(String jsonFilePath) {
        // read design parameters from json file
        Path jsonPath = Path.of(jsonFilePath).toAbsolutePath();
        designParams = new DesignParams(jsonPath);

        // setup directory manager
        dirManager = new DirectoryManager(designParams.getWorkDir());

        // setup logger
        setupLogger();

    }

    private void setupLogger() {
        logger = new HierarchicalLogger("RapidPnR");
        logger.setUseParentHandlers(false);

        Path logFilePath = dirManager.getRootDir().resolve("rapidPnR.log");
        
        // Setup Logger
        try {
            FileHandler fileHandler = new FileHandler(logFilePath.toString(), false);
            fileHandler.setFormatter(new CustomFormatter());
            logger.addHandler(fileHandler);

            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new CustomFormatter());
            logger.addHandler(consoleHandler);
        } catch (Exception e) {
            System.out.println("Fail to open log file: " + logFilePath.toString());
        }
        logger.setLevel(Level.INFO);

        logger.info("Setup hierarchical logger for RapidPnR successfully");
    }

    private void readInputDesign() {
        logger.info("Reading input design checkpoint: " + designParams.getInputDcpPath().toString());

        inputDesign = Design.readCheckpoint(designParams.getInputDcpPath().toString());

        logger.info("Read input design checkpoint successfully");
    }

    private void writeOutputDesign() {
        Path rootDir = dirManager.getRootDir();
        Path outputDcpPath = rootDir.resolve(designParams.getDesignName());
        logger.info("Writing output design checkpoint: " + outputDcpPath.toString());

        outputDesign.writeCheckpoint(outputDcpPath.toString());

        logger.info("Write output design checkpoint successfully");
    }

    public void setupNetlistDatabase() {
        netlistDatabase = new NetlistDatabase(logger, inputDesign, designParams);
        
        logger.info("Information of netlist database:");
        logger.newSubStep();
        netlistDatabase.printCellLibraryInfo();
        netlistDatabase.printNetlistInfo();
        logger.endSubStep();
    }

    private void runNetlistAbstraction() {
        abstractNetlist = new AbstractNetlist(logger, netlistDatabase);
        abstractNetlist.printAbstractNetlistInfo();
    }

    private void runIslandPlacement() {
        IslandPlacer islandPlacer = new IslandPlacer(logger, dirManager, designParams, abstractNetlist);
        groupPlaceResults = islandPlacer.run();
    }

    private void runPhysicalImplementation() {
        IncrementalIslandPnR pnR = new IncrementalIslandPnR(logger, dirManager, designParams, netlistDatabase);
        pnR.run(abstractNetlist, groupPlaceResults);
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

                case WRITE_DESIGN:
                    writeOutputDesign();
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

    public static void main(String[] args) {
        String jsonFilePath = "workspace/json/blue-rdma.json";
        RapidPnR rapidPnR = new RapidPnR(jsonFilePath);
        rapidPnR.run(RapidPnRStep.PHYSICAL_IMPLEMENTATION);
    }

}
