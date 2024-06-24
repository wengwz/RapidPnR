package com.xilinx.rapidwright.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;

// final class CustomFormatter extends Formatter {
//     @Override
//     public String format(LogRecord record) {
//         return record.getLevel() + ": " + record.getMessage() + "\n";
//     }
// }

public class TestCircuitPartioner {
    public static void main(String[] args) {
        String designName = "cnn13x2";
        String resetPortName = "udp_reset";
        // String designName = "blue-rdma-direct-rst";
        // String resetPortName = "ap_rst_n";
        Path outputPath = Paths.get("./result3", designName);
        try {
            Files.createDirectories(outputPath);
        } catch (IOException e) {
            System.out.println("Fail to Create Directory: " + e.getMessage());
        }

        String designDcpPath = Paths.get("./benchmark", designName + ".dcp").toString();
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

        Design circuitDesign = Design.readCheckpoint(designDcpPath);
        EDIFNetlist logicalNetlist = circuitDesign.getNetlist();


        CircuitPartioner partioner = new CircuitPartioner(logicalNetlist, circuitDesign.getPartName(), resetPortName + "_IBUF_inst", logger);
        partioner.printHierNetlistInfo();
        partioner.printFlatNetlistInfo();
        partioner.printPartitionGroupsInfo();
        partioner.printRegCtrlPortIncidentNets(Paths.get(outputPath.toString(), designName + "-reg-ctrl-nets.txt").toString());
        partioner.writeFlatNetlistDCP(Paths.get(outputPath.toString(), designName + "-flat.dcp").toString());
        partioner.writePartitionGroupsResult(Paths.get(outputPath.toString(), "partition-grp-results.txt").toString());
        //partioner.writeRegEdgesRemovedFlatNetlist(Paths.get(outputPath.toString(), designName + "-edge-removed.dcp").toString());
    }
}
