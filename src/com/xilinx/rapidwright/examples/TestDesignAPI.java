package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.design.Cell;

import java.util.Collection;
import java.nio.file.Paths;

public class TestDesignAPI {
// Test Goal-1: how to traverse clock and reset network in the OOC mode
// Test Goal-2: how the logical netlist is mapped to the physical netlist
// 
    public static void main(String[] args) {
        String designName = "blue-rdma";
        String designDcpPath = Paths.get("./benchmarks", designName + ".dcp").toString();
        Design circuitDesign = Design.readCheckpoint(designDcpPath);

        // Collection<Cell> cellList = circuitDesign.getCells();
        // if (cellList.size() > 0) {
        //     System.out.println("Cells in design: ");
        //     for (Cell cell : cellList) {
        //         System.out.println(cell.getName());
        //     }
        // } else {
        //     System.out.println("No cells in design.");
        // }

        EDIFNetlist topNetlist = circuitDesign.getNetlist();
        EDIFCell topCell = topNetlist.getTopCell();

        EDIFCellInst topCellInst = topNetlist.getTopCellInst();

        System.out.println("Top cell instance name: " + topCellInst.getName());
        for (EDIFPortInst portInst : topCellInst.getPortInsts()) {
            System.out.println(portInst.getName());
        }

        System.out.println("Top cell name: " + topCell.getName());
        for (EDIFPort port : topCell.getPorts()) {
            System.out.println(port.getName());
        }

        EDIFNet resetNet = topCell.getNet("RST_N");

        for (EDIFPortInst portInst : resetNet.getPortInsts()) {
            if (portInst.getCellInst() == null) {
                System.out.println("PortInst " + portInst.getName() + " has no cell instance.");
                System.out.println("PortInst: " + portInst.getName() + " Port: "+ portInst.getPort().getName() + " Cell: " + portInst.getPort().getParentCell().getName());
            } else {
                System.out.println(portInst.getName() + " " + portInst.getCellInst().getName());

            }
        }

        

    }
    
}
