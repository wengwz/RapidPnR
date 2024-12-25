package com.xilinx.rapidwright.rapidpnr;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;

public class NameConvention {

    public static String resultDirName = "results";
    public static String mergeDirName = "merge";
    public static String islandPlacerDirName = "island_placer";
    public static String islandPlacerInputJsonName = "input.json";
    public static String islandPlacerOutputJsonName = "output.json";

    public static String getIslandName(int x, int y) {
        return String.format("island_%d_%d", x, y);
    }

    public static String getIslandName(Coordinate2D loc) {
        return getIslandName(loc.getX(), loc.getY());
    }

    public static String getPeriIslandName(int x, int y) {
        return String.format("peri_island_%d_%d", x, y);
    }

    public static String getPeriIslandName(Coordinate2D loc) {
        return getPeriIslandName(loc.getX(), loc.getY());
    }

    public static String getIslandInstName(int x, int y) {
        return String.format("island_%d_%d_inst", x, y);
    }

    public static String getIslandInstName(Coordinate2D loc) {
        return getIslandInstName(loc.getX(), loc.getY());
    }

    public static String getVertBoundaryName(int x, int y) {
        return String.format("vert_boundary_%d_%d", x, y);
    }

    public static String getVertBoundaryName(Coordinate2D loc) {
        return getVertBoundaryName(loc.getX(), loc.getY());
    }

    public static String getVertBoundaryInstName(int x, int y) {
        return String.format("vert_boundary_%d_%d_inst", x, y);
    }

    public static String getVertBoundaryInstName(Coordinate2D loc) {
        return getVertBoundaryInstName(loc.getX(), loc.getY());
    }

    public static String getHoriBoundaryName(int x, int y) {
        return String.format("hori_boundary_%d_%d", x, y);
    }

    public static String getHoriBoundaryName(Coordinate2D loc) {
        return getHoriBoundaryName(loc.getX(), loc.getY());
    }

    public static String getHoriBoundaryInstName(int x, int y) {
        return String.format("hori_boundary_%d_%d_inst", x, y);
    }

    public static String getHoriBoundaryInstName(Coordinate2D loc) {
        return getHoriBoundaryInstName(loc.getX(), loc.getY());
    }

    public static String getRepCellName(EDIFCellInst originCell, Coordinate2D loc) {
        return String.format("%s_%d_%d", originCell.getName(), loc.getX(), loc.getY());
    }

    public static String addSuffixDcp(String name) {
        return name + ".dcp";
    }

    public static String addSuffixTcl(String name) {
        return name + ".tcl";
    }

    public static String addSuffixXdc(String name) {
        return name + ".xdc";
    }

    public static String addSuffixRpt(String name) {
        return name + ".rpt";
    }

    public static String getIslandDcpName(int x, int y) {
        return addSuffixDcp(getIslandName(x, y));
    }

    public static String getIslandDcpName(Coordinate2D loc) {
        return getIslandDcpName(loc.getX(), loc.getY());
    }

    public static String getVertBoundaryDcpName(int x, int y) {
        return addSuffixDcp(getVertBoundaryName(x, y));
    }

    public static String getVertBoundaryDcpName(Coordinate2D loc) {
        return getVertBoundaryDcpName(loc.getX(), loc.getY());
    }

    public static String getHoriBoundaryDcpName(int x, int y) {
        return addSuffixDcp(getHoriBoundaryName(x, y));
    }

    public static String getHoriBoundaryDcpName(Coordinate2D loc) {
        return getHoriBoundaryDcpName(loc.getX(), loc.getY());
    }
}
