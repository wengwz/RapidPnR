package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;

public class TestRWAPI {
    public static void main(String[] args) {
        String designName = "island_placed_routed_1_1";
        Design circuitDesign = Design.readCheckpoint("./pr_result2/fft-16/" + designName + ".dcp");
        String netName = "stage_8/FWBFLY.bfly/do_rnd_right_r/ROUND_RESULT.o_val[7]_i_2__0_n_0";
        Net net0 = circuitDesign.getNet(netName);
        if (net0 != null) {
            System.out.println(netName);
        }
        // for (Net net : circuitDesign.getNets()) {
        //     System.out.println("Net: " + net.getName());
        // }

        // for (SiteInst site: circuitDesign.getSiteInsts()) {
        //     System.out.println("Site: " + site.getName());
        // }
    }
}
