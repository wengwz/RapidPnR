package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;

import com.xilinx.rapidwright.design.Design;


public class AbstractApplication {
    protected DesignParams designParams;
    protected DirectoryManager dirManager;
    protected HierarchicalLogger logger;

    protected Design inputDesign;
    protected NetlistDatabase netlistDatabase;

    public AbstractApplication(String jsonFilePath, Boolean enableLogger) {
        // read design parameters from json file
        Path jsonPath = Path.of(jsonFilePath).toAbsolutePath();
        designParams = new DesignParams(jsonPath);

        // setup directory manager
        dirManager = new DirectoryManager(designParams.getWorkDir());

        // setup logger
        setupLogger(enableLogger);
    }

    protected void setupLogger(Boolean enableLogger) {
        logger = new HierarchicalLogger("RapidPnR");
        logger.setUseParentHandlers(false);

        if (!enableLogger) {
            return;
        }

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

    protected void readInputDesign() {
        logger.info("Reading input design checkpoint: " + designParams.getInputDcpPath().toString());

        inputDesign = Design.readCheckpoint(designParams.getInputDcpPath().toString());

        logger.info("Read input design checkpoint successfully");
    }

    public void setupNetlistDatabase() {
        netlistDatabase = new NetlistDatabase(logger, inputDesign, designParams);
        
        logger.info("Information of netlist database:");
        logger.newSubStep();
        netlistDatabase.printCellLibraryInfo();
        netlistDatabase.printNetlistInfo();
        logger.endSubStep();
    }

}
