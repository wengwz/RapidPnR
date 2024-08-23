package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.examples.ParallelIslandPnR;

public class TestParallelPnR {
    public static void main(String[] args) {
        String designFilePath = "./benchmarks/blue-udp-ooc/blue-udp-ooc.dcp";
        String partitionJsonPath = "./benchmarks/blue-udp-ooc/blue-udp-ooc.json";
        String placeJsonPath = "./benchmarks/blue-udp-ooc/legalized_result.json";

        ParallelIslandPnR pnr = new ParallelIslandPnR(designFilePath, partitionJsonPath, placeJsonPath);
        //pnr.separateDesignAndCreateDCPs();
        //pnr.syncIslandCellToAnchorDCPs();
        pnr.syncAnchorCellToIslandDCPs();

    }
}
