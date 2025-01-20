package com.xilinx.rapidwright.rapidpnr.timing;

import org.jgrapht.graph.DefaultEdge;

public class TimingEdge extends DefaultEdge {

    private boolean isLogic = false;
    private Integer netFanout = 0;

    public TimingEdge(boolean isLogic, Integer netFanout) {
        this.isLogic = isLogic;
        if (!isLogic) {
            this.netFanout = netFanout;
        } else {
            this.netFanout = 0;
        }
    }

    public void setEdgeProperty(boolean isLogic, Integer netFanout) {
        this.isLogic = isLogic;
        if (!isLogic) {
            this.netFanout = netFanout;
        } else {
            this.netFanout = 0;
        }
    }

    public boolean isLogic() {
        return isLogic;
    }

    public Integer getNetFanout() {
        return netFanout;
    }

    public double predictDelay() {
        if (isLogic) {
            return 1.0;
        } else {
            //return 1.0 + Math.log(netFanout);
            return 1.0 + 1.5 * Math.log(netFanout);
        }
    }
    
}
