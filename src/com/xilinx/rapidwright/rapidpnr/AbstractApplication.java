package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;


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
        Path logFilePath = dirManager.getRootDir().resolve("rapidPnR.log");

        if (enableLogger) {
            logger = HierarchicalLogger.createLogger("application", logFilePath, true);
        } else {
            logger = HierarchicalLogger.createPseduoLogger("application");
        }

        logger.info("Setup hierarchical logger for RapidPnR successfully");
    }

    protected void readInputDesign() {
        logger.info("Reading input design checkpoint: " + designParams.getInputDcpPath().toString());

        inputDesign = Design.readCheckpoint(designParams.getInputDcpPath().toString());

        logger.info("Read input design checkpoint successfully");
    }

    public void setupNetlistDatabase() {
        logger.infoHeader("Setup Netlist Database");
        netlistDatabase = new NetlistDatabase(logger, inputDesign, designParams);
        
        logger.info("Information of netlist database:");
        logger.newSubStep();
        netlistDatabase.printCellLibraryInfo();
        netlistDatabase.printNetlistInfo();
        logger.endSubStep();
    }

}
