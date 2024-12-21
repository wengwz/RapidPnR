package com.xilinx.rapidwright.rapidpnr.timing;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPortInst;

import com.xilinx.rapidwright.rapidpnr.utils.NetlistUtils;

public class TimingVertex {

    private String name;
    private EDIFPortInst portInst;

    private double recvDelay;
    private double driveDelay;

    private Integer driveLogicLevel;
    private Integer driveFanout;

    private Integer recvLogiceLevel;
    private Integer recvFanout;

    private boolean isEndpoint;
    private boolean isStartpoint;

    private String clkName;

    public TimingVertex(String name, EDIFPortInst portInst) {
        this.name = name;
        this.portInst = portInst;

        driveDelay = 0;
        recvDelay = 0;

        driveLogicLevel = 0;
        driveFanout = 0;

        recvLogiceLevel = 0;
        recvFanout = 0;

        clkName = null;

        EDIFCellInst cellInst = portInst.getCellInst();
        if (cellInst ==  null) { // top-level port
            isEndpoint = portInst.isOutput();
            isStartpoint = portInst.isInput();
        } else {
            EDIFCell cellType = cellInst.getCellType();
            // Treat all non-primitive cells in flat netlist as sequential logic
            boolean isSequential = !cellType.isPrimitive() || NetlistUtils.isSequentialLogic(cellInst);
            if (isSequential) {
                assert !NetlistUtils.isClkPort(portInst);
                isEndpoint = portInst.isInput();
                isStartpoint = portInst.isOutput();
            } else {
                isEndpoint = false;
                isStartpoint = false;
            }
        }
    }

    public String toString() {
        return name;
    }

    public boolean equals(Object o) {
        if (o instanceof TimingVertex)
            return ((TimingVertex)o).name.equals(name);
        return false;
    }

    public int compareTo(Object o) {
        if (o instanceof TimingVertex)
            return name.compareTo(((TimingVertex)o).name);
        return -1;
    }

    public int hashCode() {
        return name.hashCode();
    }

    // setters
    public void setClkName(String clkName) {
        this.clkName = clkName;
    }

    public void setDriveLogicLevel(Integer driveLogicLevel) {
        this.driveLogicLevel = driveLogicLevel;
    }

    public void setMaxDriveLogicLevel(Integer driveLogicLevel) {
        if (this.driveLogicLevel == null || driveLogicLevel > this.driveLogicLevel) {
            this.driveLogicLevel = driveLogicLevel;
        }
    }

    public void setDriveFanout(Integer driveFanout) {
        this.driveFanout = driveFanout;
    }

    public void setMaxDriveFanout(Integer driveFanout) {
        if (this.driveFanout == null || driveFanout > this.driveFanout) {
            this.driveFanout = driveFanout;
        }
    }

    public void setDriveDelay(double driveDelay) {
        this.driveDelay = driveDelay;
    }

    public void setMaxDriveDelay(double driveDelay) {
        if (driveDelay > this.driveDelay) {
            this.driveDelay = driveDelay;
        }
    }

    public void setRecvLogicLevel(Integer recvLogicLevel) {
        this.recvLogiceLevel = recvLogicLevel;
    }

    public void setMaxRecvLogicLevel(Integer recvLogicLevel) {
        if (this.recvLogiceLevel == null || recvLogicLevel > this.recvLogiceLevel) {
            this.recvLogiceLevel = recvLogicLevel;
        }
    }

    public void setRecvFanout(Integer recvFanout) {
        this.recvFanout = recvFanout;
    }

    public void setMaxRecvFanout(Integer recvFanout) {
        if (this.recvFanout == null || recvFanout > this.recvFanout) {
            this.recvFanout = recvFanout;
        }
    }

    public void setRecvDelay(double recvDelay) {
        this.recvDelay = recvDelay;
    }

    public void setMaxRecvDelay(double recvDelay) {
        if (recvDelay > this.recvDelay) {
            this.recvDelay = recvDelay;
        }
    }

    // getters
    public String getName() {
        return name;
    }

    public String getClkName() {
        return clkName;
    }

    public EDIFPortInst getPortInst() {
        return portInst;
    }

    public boolean isEndpoint() {
        return isEndpoint;
    }

    public boolean isStartpoint() {
        return isStartpoint;
    }

    public int getDriveLogicLevel() {
        return driveLogicLevel;
    }

    public int getDriveFanout() {
        return driveFanout;
    }

    public double getDriveDelay() {
        return driveDelay;
    }

    public int getRecvLogicLevel() {
        return recvLogiceLevel;
    }

    public int getRecvFanout() {
        return recvFanout;
    }

    public double getRecvDelay() {
        return recvDelay;
    }

}
