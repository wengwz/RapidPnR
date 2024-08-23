package com.xilinx.rapidwright.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;


import com.xilinx.rapidwright.examples.ParallelIterativePnR;

public class TestParallelIterativePnR {
    public static void main(String[] args) {

        String designName = "fft-16";

        Path outputPath = Paths.get("./pr_result2", designName);
        try {
            Files.createDirectories(outputPath);
        } catch (IOException e) {
            System.out.println("Fail to Create Directory: " + e.getMessage());
        }
        String designDcpPath = String.format("./benchmarks/%s/%s.dcp", designName, designName);
        String abstractNetlistJsonPath = String.format("./benchmarks/%s/%s.json", designName, designName);
        String placeJsonPath = String.format("./benchmarks/%s/%s-place.json", designName, designName);
        String logFilePath = Paths.get(outputPath.toString(), designName + ".log").toString();
        
        Logger logger = Logger.getLogger(designName);
        logger.setUseParentHandlers(false);
        // Setup Logger
        try {
            FileHandler fileHandler = new FileHandler(logFilePath, false);
            fileHandler.setFormatter(new CustomFormatter());
            logger.addHandler(fileHandler);

            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new CustomFormatter());
            logger.addHandler(consoleHandler);
        } catch (Exception e) {
            System.out.println("Fail to open log file: " + logFilePath);
        }
        logger.setLevel(Level.INFO);

        ParallelIterativePnR pnR = new ParallelIterativePnR(designDcpPath, abstractNetlistJsonPath, placeJsonPath, logger);
        //pnR.generateIslandDCPs(outputPath.toString());
        //String placedIslandDcpPrefix = String.format("%s/island_placed", outputPath.toString());
        //pnR.updatePartitionPinPos(placedIslandDcpPrefix);

        String routedIslandDcpPrefix = String.format("%s/island_placed_routed", outputPath.toString());
        pnR.mergeSeparateDesigns(routedIslandDcpPrefix);
    }
}
