package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;
import java.util.logging.Level;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;


public class AbstractApplication {
    protected DesignParams designParams;
    protected DirectoryManager dirManager;
    protected HierarchicalLogger logger;

    protected Design inputDesign;
    protected NetlistDatabase netlistDatabase;
    protected RuntimeTrackerTree rootTimer;

    public AbstractApplication(String jsonFilePath, Boolean enableLogger) {
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

    public RuntimeTracker createSubTimer(String name) {
        return rootTimer.createRuntimeTracker(name, rootTimer.getRootRuntimeTracker());
    }

}
