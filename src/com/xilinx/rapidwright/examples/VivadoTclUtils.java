package com.xilinx.rapidwright.examples;

import java.util.ArrayList;

import org.objenesis.strategy.StdInstantiatorStrategy;

import java.nio.file.Path;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.FileTools;

public class VivadoTclUtils {

    public static class TclCmdFile {
        private String filePath;
        ArrayList<String> cmdLines;

        public TclCmdFile(Path path) {
            this.filePath = path.toString();
            cmdLines = new ArrayList<>();
        }

        public void addCmd(String cmd) {
            cmdLines.add(cmd);
        }

        public void writeToFile() {
            FileTools.writeLinesToTextFile(cmdLines, filePath);
        }
    }

    public static void drawPblock(Design design, String pblockName, String pblockRange) {
        design.addXDCConstraint(String.format("create_pblock %s", pblockName));
        design.addXDCConstraint(String.format("resize_pblock %s -add { %s }", pblockName, pblockRange));
    }

    public static void setPblockProperties(Design design, String pblockName, Boolean isSoft, Boolean excludePlace, Boolean containRouting) {
        if (!isSoft) {
            design.addXDCConstraint(String.format("set_property IS_SOFT FALSE [get_pblocks %s]", pblockName));
        }
        if (excludePlace) {
            design.addXDCConstraint(String.format("set_property EXCLUDE_PLACEMENT true [get_pblocks %s]", pblockName));
        }
        if (containRouting) {
            design.addXDCConstraint(String.format("set_property CONTAIN_ROUTING true [get_pblocks %s]", pblockName));
        }
    }

    public static void addCellToPblock(Design design, String pblockName, String cellName) {
        design.addXDCConstraint(String.format("add_cells_to_pblock %s [get_cells %s]", pblockName, cellName));
    }

    public static void addStrictPblocConstr(Design design, EDIFCellInst cellInst, String pblockRange) {
        String pblockName = "pblock_" + cellInst.getName();
        drawPblock(design, pblockName, pblockRange);
        setPblockProperties(design, pblockName, false, true, true);
        addCellToPblock(design, pblockName, cellInst.getName());
    }

    public static void addClockConstraint(Design design, String clkPortName, Double period) {
        String constrString = String.format("create_clock -period %f -name %s [get_ports %s]", period, clkPortName, clkPortName);
        design.addXDCConstraint(constrString);
    }

    public static void addIODelayConstraint(Design design, EDIFPort port, String clkName, Double delay) {
        String commandStr = port.isInput() ? "set_input_delay" : "set_output_delay";
        String portName = port.getName();
        String constrStr = String.format("%s -clock %s %f %s", commandStr, clkName, delay, portName);
        design.addXDCConstraint(constrStr);
    }
    
    public static String readCheckPoint(String cellInstName, String dcpPath) {
        return String.format("read_checkpoint -cell %s %s", cellInstName, dcpPath);
    }

    public static String openCheckpoint(String dcpPath) {
        return String.format("open_checkpoint %s", dcpPath);
    }

    public static String readCheckpoint(boolean isStrict, String dcpPath) {
        if (isStrict) {
            return String.format("read_checkpoint -strict %s", dcpPath);
        } else {
            return String.format("read_checkpoint %s", dcpPath);
        }
    }

    public static String writeCheckpoint(boolean force, String cellInstName, String dcpPath) {
        String cmdStr = "write_checkpoint";
        if (force) {
            cmdStr += " -force";
        }
        if (cellInstName != null) {
            cmdStr += " -cell " + cellInstName;
        }
        assert dcpPath != null;
        return cmdStr + " " + dcpPath;
    }

    public static String placeDesign(String directive) {
        String cmdString = "place_design";
        if (directive != null) {
            cmdString += " -directive " + directive;
        }
        return cmdString;
    }

    public static String routeDesign(String directive) {
        String cmdString = "route_design";
        if (directive != null) {
            cmdString += " -directive " + directive;
        }
        return cmdString;
    }

    public static String physOptDesign() {
        return "phys_opt_design";
    }

    public static String reportTimingSummary(int maxPathNum, String filePath) {
        if (maxPathNum <= 0) {
            return String.format("report_timing_summary -file %s", filePath);
        } else {
            return String.format("report_timing_summary -max %d -file %s", maxPathNum, filePath);
        }
    }

    public static String updateCellBlackbox(String cellInstName) {
        return String.format("update_design -cells [get_cells %s] -black_box", cellInstName);
    }

    public static String lockDesign(boolean unlock, String level, String cellInstName) {
        String cmdStr = "lock_design";
        if (unlock) {
            cmdStr += " -unlock";
        }
        if (level != null) {
            cmdStr += String.format(" -level %s", level);
        }

        if (cellInstName != null) {
            cmdStr += " " + cellInstName;
        }
        return cmdStr;
    }

    public static String setProperty(String propertyName, boolean value, String cellInstName) {
        String valStr = value ? "TRUE" : "FALSE";
        String targetName = "[current_design]";
        if (cellInstName != null) {
            targetName = String.format("[get_cells %s]", cellInstName);
        }

        return String.format("set_property %s %s %s", propertyName, valStr, targetName); 
    }

    public static String setPropertyHDReConfig(boolean val, String cellInstName) {
        return setProperty("HD.RECONFIGURABLE", val, cellInstName);
    }

    public static void setPropertyHDReConfig(Design design, EDIFCellInst cellInst) {
        design.addXDCConstraint(setPropertyHDReConfig(true, cellInst.getName()));
    }

    public static String setPropertyHDPartition(boolean val, String cellInstName) {
        return setProperty("HD.PARTITION", val, cellInstName);
    }

    public static void setPropertyHDPartition(Design design) {
        design.addXDCConstraint(setPropertyHDPartition(true, null));
    }

    public static void setPropertyHDPartition(Design design, EDIFCellInst cellInst) {
        design.addXDCConstraint(setPropertyHDPartition(true, cellInst.getName()));
    }

    public static String setPropertyDontTouch(boolean val, String cellInstName) {
        return setProperty("DONT_TOUCH", val, cellInstName);
    }
    
    public static void setPropertyDontTouch(Design design, EDIFCellInst cellInst) {
        design.addXDCConstraint(setPropertyDontTouch(true, cellInst.getName()));
    }

    public static String exitVivad() {
        return "exit";
    }

    public static String setMaxThread(int maxThreadNum) {
        return String.format("set_param general.maxThreads %d", maxThreadNum);
    }

    public static String launchVivadoTcl(String vivadoPath, String tclPath) {
        return String.format("%s -mode batch -source %s", vivadoPath, tclPath);
    }
}
