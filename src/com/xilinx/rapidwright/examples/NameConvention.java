package com.xilinx.rapidwright.examples;

import java.util.List;

public class NameConvention {

    public static String vivadoBuildTclName = "vivado_build.tcl";
    public static String vivadoInputDcpName = "input.dcp";
    public static String vivadoOutputDcpName = "output.dcp";
    public static String resultDirName = "results";
    public static String mergeDirName = "merge";

    public static String getIslandName(int x, int y) {
        return String.format("island_%d_%d", x, y);
    }

    public static String getIslandName(List<Integer> loc) {
        return getIslandName(loc.get(0), loc.get(1));
    }

    public static String getIslandInstName(int x, int y) {
        return String.format("island_%d_%d_inst", x, y);
    }

    public static String getIslandInstName(List<Integer> loc) {
        return getIslandInstName(loc.get(0), loc.get(1));
    }

    public static String getVertBoundaryName(int x, int y) {
        return String.format("vert_boundary_%d_%d", x, y);
    }

    public static String getVertBoundaryName(List<Integer> loc) {
        return getVertBoundaryName(loc.get(0), loc.get(1));
    }

    public static String getVertBoundaryInstName(int x, int y) {
        return String.format("vert_boundary_%d_%d_inst", x, y);
    }

    public static String getVertBoundaryInstName(List<Integer> loc) {
        return getVertBoundaryInstName(loc.get(0), loc.get(1));
    }

    public static String getHoriBoundaryName(int x, int y) {
        return String.format("hori_boundary_%d_%d", x, y);
    }

    public static String getHoriBoundaryName(List<Integer> loc) {
        return getHoriBoundaryName(loc.get(0), loc.get(1));
    }

    public static String getHoriBoundaryInstName(int x, int y) {
        return String.format("hori_boundary_%d_%d_inst", x, y);
    }

    public static String getHoriBoundaryInstName(List<Integer> loc) {
        return getHoriBoundaryInstName(loc.get(0), loc.get(1));
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

    public static String getIslandDcpName(List<Integer> loc) {
        return getIslandDcpName(loc.get(0), loc.get(1));
    }

    public static String getVertBoundaryDcpName(int x, int y) {
        return addSuffixDcp(getVertBoundaryName(x, y));
    }

    public static String getVertBoundaryDcpName(List<Integer> loc) {
        return getVertBoundaryDcpName(loc.get(0), loc.get(1));
    }

    public static String getHoriBoundaryDcpName(int x, int y) {
        return addSuffixDcp(getHoriBoundaryName(x, y));
    }

    public static String getHoriBoundaryDcpName(List<Integer> loc) {
        return getHoriBoundaryDcpName(loc.get(0), loc.get(1));
    }
}
