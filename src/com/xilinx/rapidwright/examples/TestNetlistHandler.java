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


import com.xilinx.rapidwright.examples.NetlistUtils;

public class TestNetlistHandler {
    public static void main(String[] args) {
        
        // String designName = "blue-udp-direct-rst-ooc";
        // String resetPortName = "udp_reset";
        // String clockPortName = "udp_clk";
        // String designName = "gnl_mid_origin";
        // HashMap<String, List<Integer>> ioConstrs = IOConstraints.gnlMidConstraints;
        // String resetPortName = null;
        // String clockPortName = "clk";

        String designName = "fft-16";
        HashMap<String, List<Integer>> ioConstrs = IOConstraints.fftConstraints;
        String resetPortName = "i_reset";
        String clockPortName = "i_clk";
        List<String> ignoreNets = Arrays.asList("i_ce");

        Path outputPath = Paths.get("./results", designName);
        try {
            Files.createDirectories(outputPath);
        } catch (IOException e) {
            System.out.println("Fail to Create Directory: " + e.getMessage());
        }

        String designDcpPath = Paths.get("./benchmarks", designName + ".dcp").toString();
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

        NetlistHandler netlistHandler = new NetlistHandler(designDcpPath, clockPortName, resetPortName, ignoreNets, logger);
        netlistHandler.printFlatNetlistInfo();
        netlistHandler.printToplevelPorts();
        netlistHandler.printAbstractGroupsInfo();
        netlistHandler.printAbstractEdgesInfo();

        String netlistJsonPath = Paths.get(outputPath.toString(), designName + ".json").toString();
        netlistHandler.writeProcessedNetlistJson(netlistJsonPath, ioConstrs);

        String flatDcpPath = Paths.get(outputPath.toString(), designName + "-flat.dcp").toString();
        netlistHandler.writeFlatNetlistDCP(flatDcpPath);
    }
}
