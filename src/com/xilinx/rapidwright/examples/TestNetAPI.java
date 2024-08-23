package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;

public class TestNetAPI {
    public static void main(String[] args) {
        String designName = "island_placed_placed_0_0";
        Design circuitDesign = Design.readCheckpoint("./pr_result/" + designName + ".dcp");
        for (Net net : circuitDesign.getNets()) {
            System.out.println("Net: " + net.getName());
        }
    }
}
