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

final class CustomFormatter extends Formatter {
    @Override
    public String format(LogRecord record) {
        return record.getLevel() + ": " + record.getMessage() + "\n";
    }
}

public class TestCircuitPartioner {
    public static void main(String[] args) {
        //String clockPortName = "ap_clk";

        // String designName = "cnn_13x2_direct_rst";
        // String resetPortName = "ap_rst_n";

        // String designName = "stencil6";
        // String resetPortName = "ap_rst_n";

        // String designName = "page_rank";
        // String resetPortName = "ap_rst_n";

        // String designName = "lu20x20";
        // String resetPortName = "ap_rst_n";

        // String designName = "fft";
        // String resetPortName = "i_reset";

        String designName = "blue-udp-nocrc-direct-rst";
        String resetPortName = "udp_reset";
        // String designName = "blue-rdma";
        // String resetPortName = "RST_N";

        // String designName = "blue-rdma-direct-rst";
        // String resetPortName = "RST_N";
        
        Path outputPath = Paths.get("./result3", designName);
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

        Design circuitDesign = Design.readCheckpoint(designDcpPath);
        EDIFNetlist logicalNetlist = circuitDesign.getNetlist();


        CircuitPartioner partioner = new CircuitPartioner(logicalNetlist, circuitDesign.getPartName(), resetPortName + "_IBUF_inst", logger);
        partioner.printHierNetlistInfo();
        partioner.printFlatNetlistInfo();
        
        partioner.printPartitionGroupsInfo();
        partioner.printPartitionEdgesInfo();
        partioner.printCellInstDistribution();
        //partioner.printRegCtrlPortIncidentNets(Paths.get(outputPath.toString(), designName + "-reg-ctrl-nets.txt").toString());
        //partioner.writePartitionNetlist(Paths.get(outputPath.toString(), designName + "-partiton.dcp").toString());
        //partioner.writeFlatNetlistDCP(Paths.get(outputPath.toString(), designName + "-flat.dcp").toString());
        partioner.writePartitionGroupsResult(Paths.get(outputPath.toString(), "partition-grp-results.txt").toString());
        partioner.writePartitionResutlJson(Paths.get(outputPath.toString(), designName + ".json").toString());

        partioner.printIOInstInfo();
        //partioner.writeRegEdgesRemovedFlatNetlist(Paths.get(outputPath.toString(), designName + "-edge-removed.dcp").toString());
    }
}
