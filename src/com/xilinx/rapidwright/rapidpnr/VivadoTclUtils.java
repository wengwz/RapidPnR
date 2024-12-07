package com.xilinx.rapidwright.rapidpnr;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.nio.file.Path;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.FileTools;

public class VivadoTclUtils {

    public static class TclCmdFile {

        ArrayList<String> cmdLines;

        public TclCmdFile() {
            cmdLines = new ArrayList<>();
        }

        public void addCmd(String cmd) {
            cmdLines.add(cmd);
        }

        public void addCmds(List<String> cmds) {
            for (String cmd : cmds) {
                cmdLines.add(cmd);
            }
        }

        public void writeToFile(Path filePath) {
            FileTools.writeLinesToTextFile(cmdLines, filePath.toString());
        }
    }

    public static class VivadoTclCmd  {

        public static class RouteType {
            public static final String Antennas = "ANTENNAS";
            public static final String Conflicts = "CONFLICTS";
            public static final String Partial = "PARTIAL";
            public static final String Unplaced = "UNPLACED";
            public static final String Unrouted = "UNROUTED";
        }
        public static class IncrImplDirective {
            public static final String RuntimeOpt = "RuntimeOptimized";
            public static final String TimingClosure = "TimingClosure";
            public static final String Quick = "Quick";
        }

        public static class PlacerDirective {
            public static final String Default = "Default";
            public static final String RuntimeOpt = "RuntimeOptimized";
            public static final String Quick = "Quick";
            public static final String SpreadLogicHigh = "AltSpreadLogic_high";
            public static final String SpreadLogicMedium = "AltSpreadLogic_medium";
            public static final String SpreadLogicLow = "AltSpreadLogic_low";
        }

        public static class LockDesignLevel {
            public static final String Logic = "logic";
            public static final String Placement = "placement";
            public static final String Routing = "routing";
        }

        public static String createPblock(String pblockName) {
            return String.format("create_pblock %s", pblockName);
        }

        public static String resizePblock(String pblockName, String pblockRange) {
            return String.format(String.format("resize_pblock %s -add { %s }", pblockName, pblockRange));
        }

        public static List<String> drawPblock(String pblockName, String pblockRange) {
            List<String> tclCmds = new ArrayList<>();
            tclCmds.add(createPblock(pblockName));
            tclCmds.add(resizePblock(pblockName, pblockRange));
            return tclCmds;
        }

        public static void drawPblock(Design design, String pblockName, String pblockRange) {
            for (String cmd : drawPblock(pblockName, pblockRange)) {
                design.addXDCConstraint(cmd);
            }
        }

        public static List<String> setPblockProperties(String pblockName, Boolean isSoft, Boolean excludePlace, Boolean containRoute) {
            List<String> tclCmds = new ArrayList<>();
            String targetCmd = getPblocks(pblockName);
            tclCmds.add(setBooleanProperty("IS_SOFT", isSoft, targetCmd));
            tclCmds.add(setBooleanProperty("EXCLUDE_PLACEMENT", excludePlace, targetCmd));
            tclCmds.add(setBooleanProperty("CONTAIN_ROUTING", containRoute, targetCmd));
            return tclCmds;
        }
    
        public static void setPblockProperties(Design design, String pblockName, Boolean isSoft, Boolean excludePlace, Boolean containRoute) {
            for (String cmd : setPblockProperties(pblockName, isSoft, excludePlace, containRoute)) {
                design.addXDCConstraint(cmd);
            }
        }

        public static String addCellToPblock(String pblockName, String cellName) {
            return String.format("add_cells_to_pblock %s [%s]", pblockName, getCells(cellName));
        }

        public static void addCellToPblock(Design design, String pblockName, String cellName) {
            design.addXDCConstraint(addCellToPblock(pblockName, cellName));
        }

        public static String addTopCellToPblock(String pblockName) {
            return String.format("add_cells_to_pblock %s -top", pblockName);
        }

        public static void addTopCellToPblock(Design design, String pblockName) {
            // add top cell to pblock
            design.addXDCConstraint(addTopCellToPblock(pblockName));
        }

        public static List<String> addCellPblockConstr(String cellInstName, String pblockRange, Boolean isSoft, Boolean excludePlace, Boolean containRoute) {
            List<String> cmds = new ArrayList<>();
            String pblockName = "pblock_" + cellInstName;

            cmds.addAll(drawPblock(pblockName, pblockRange));
            cmds.addAll(setPblockProperties(pblockName, isSoft, excludePlace, containRoute));
            cmds.add(addCellToPblock(pblockName, cellInstName));

            return cmds;
        }

        public static void addCellPblockConstr(Design design, EDIFCellInst cellInst, String pblockRange, Boolean isSoft, Boolean excludePlace, Boolean containRoute) {
            for (String cmd : addCellPblockConstr(cellInst.getName(), pblockRange, isSoft, excludePlace, containRoute)) {
                design.addXDCConstraint(cmd);
            }
        }

        public static List<String> addTopCellPblockConstr(String pblockRange, Boolean isSoft, Boolean excludePlace, Boolean containRoute) {
            List<String> cmds = new ArrayList<>();
            String pblockName = "pblock_top";

            cmds.addAll(drawPblock(pblockName, pblockRange));
            cmds.addAll(setPblockProperties(pblockName, isSoft, excludePlace, containRoute));
            cmds.add(addTopCellToPblock(pblockName));

            return cmds;
        }

        public static void addTopCellPblockConstr(Design design, String pblockRange, Boolean isSoft, Boolean excludePlace, Boolean containRoute) {
            for (String cmd : addTopCellPblockConstr(pblockRange, isSoft, excludePlace, containRoute)) {
                design.addXDCConstraint(cmd);
            }
        }

        public static List<String> addStrictCellPblockConstr(String cellInstName, String pblockRange) {
            return addCellPblockConstr(cellInstName, pblockRange, false, true, true);
        }

        public static void addStrictCellPblockConstr(Design design, EDIFCellInst cellInst, String pblockRange) {
            for (String cmd : addStrictCellPblockConstr(cellInst.getName(), pblockRange)) {
                design.addXDCConstraint(cmd);
            }
        }

        public static List<String> addStrictTopCellPblockConstr(String pblockRange) {
            return addTopCellPblockConstr(pblockRange, false, true, true);
        }

        public static void addStrictTopCellPblockConstr(Design design, String pblockRange) {
            for (String cmd : addStrictTopCellPblockConstr(pblockRange)) {
                design.addXDCConstraint(cmd);
            }
        }

        public static String setClockGroups(boolean isAsync, Set<String> clkGroups) {
            String cmdStr = "set_clock_groups";
            if (isAsync) {
                cmdStr += " -asynchronous";
            }
            for (String clkGroup : clkGroups) {
                cmdStr += " -group " + clkGroup;
            }
            return cmdStr;
        }

        public static void setClockGroups(Design design, boolean isAsync, Set<String> clkGroups) {
            design.addXDCConstraint(setClockGroups(isAsync, clkGroups));
        }

        public static void setAsyncClockGroupsForEachClk(Design design, Collection<String> clkPortNames) {
            EDIFCell topCell = design.getNetlist().getTopCell();
            Set<String> validClkPortNames = new HashSet<>();

            for (String clkPortName : clkPortNames) {
                if (topCell.getPort(clkPortName) != null) {
                    validClkPortNames.add(clkPortName);
                }
            }
            if (clkPortNames.size() > 1) {
                design.addXDCConstraint(setClockGroups(true, validClkPortNames));
            }
        }

        public static String createClock(String clkPortName, Double period) {
            return String.format("create_clock -period %f -name %s [get_ports %s]", period, clkPortName, clkPortName);
        }

        public static void createClock(Design design, String clkPortName, Double period) {
            design.addXDCConstraint(createClock(clkPortName, period));
        }

        public static void createClocks(Design design, Map<String, Double> clk2PeriodMap) {
            for (String clkName : clk2PeriodMap.keySet()) {
                EDIFCell topCell = design.getNetlist().getTopCell();
                // check existence of port
                if (topCell.getPort(clkName) != null) {
                    createClock(design, clkName, clk2PeriodMap.get(clkName));
                }
            }
        }
    
        public static void addIODelayConstraint(Design design, EDIFPort port, String clkName, Double delay) {
            String commandStr = port.isInput() ? "set_input_delay" : "set_output_delay";
            String portName = port.getName();
            String constrStr = String.format("%s -clock %s %f %s", commandStr, clkName, delay, portName);
            design.addXDCConstraint(constrStr);
        }
        
        public static String readCheckpoint(String cellInstName, boolean autoIncr, boolean incr, String incrDirective, String dcpPath) {
            String cmdStr = "read_checkpoint";

            if (cellInstName != null) {
                cmdStr += " -cell " + cellInstName;
            }

            if (autoIncr) {
                cmdStr += " -auto_incremental";
            } else if (incr) {
                cmdStr += " -incremental";
                if (incrDirective != null) {
                    cmdStr += " -directive " + incrDirective;
                }
            }

            cmdStr += " " + dcpPath;
            return cmdStr;
        }

        public static String readCheckpoint(String cellInstName, String dcpPath) {
            return readCheckpoint(cellInstName, false, false, null, dcpPath);
        }

        public static String readCheckpoint(Map<String, Path> cellInst2DcpMap) {
            String cellInst2DcpStr = "";
            for (String cellInstName : cellInst2DcpMap.keySet()) {
                if (cellInst2DcpStr.length() > 0) {
                    cellInst2DcpStr += " ";
                }
                cellInst2DcpStr += cellInstName + " " + cellInst2DcpMap.get(cellInstName).toString();
            }
            return String.format("read_checkpoint -dcp_cell_list {%s}", cellInst2DcpStr);
        }
    
        public static String openCheckpoint(String dcpPath) {
            return String.format("open_checkpoint %s", dcpPath);
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

        public static String writeEDIF(boolean force, String cellInstName, String edifPath) {
            String cmdStr = "write_edif";
            if (force) {
                cmdStr += " -force";
            }
            if (cellInstName != null) {
                cmdStr += " -cell " + cellInstName;
            }
            assert edifPath != null;
            return cmdStr + " " + edifPath;
        }
    
        public static String placeDesign(String directive, boolean noPSIP) {
            String cmdString = "place_design";
            if (directive != null) {
                cmdString += " -directive " + directive;
            }

            if (noPSIP) {
                cmdString += " -no_psip";
            }
            
            return cmdString;
        }

        public static String placeDesign() {
            return placeDesign(null, false);
        }
    
        public static String routeDesign(String directive, Boolean ultraThreads) {
            String cmdString = "route_design";
            if (directive != null) {
                cmdString += " -directive " + directive;
            }

            if (ultraThreads) {
                cmdString += " -ultrathreads";
            }

            return cmdString;
        }

        public static String routeDesignPartial(String target, Boolean delayEn) {
            String cmdString = String.format("route_design -nets [%s]", target);
            if (delayEn) {
                cmdString += " -delay";
            }
            return cmdString;
        }

        public static String routeDesign() {
            return routeDesign(null, false);
        }

        public static List<String> routeUnroutedNetsWithMinDelay() {
            List<String> cmds = new ArrayList<>();
            String target = reportRouteStatus(RouteType.Unrouted, true);
            cmds.add(String.format("set unrouted_nets [%s]", target));
            cmds.add(routeDesignPartial(target, true));
            return cmds;
        }
    
        public static String physOptDesign() {
            return "phys_opt_design";
        }

        public static List<String> conditionalPhysOptDesign() {
            List<String> cmds = new ArrayList<>();
            cmds.add("if {[get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]] < 0} {");
            cmds.add("    phys_opt_design");
            cmds.add("}");
            return cmds;
        }
    
        public static String reportTimingSummary(int maxPathNum, String filePath) {
            if (maxPathNum <= 0) {
                return String.format("report_timing_summary -file %s", filePath);
            } else {
                return String.format("report_timing_summary -max %d -file %s", maxPathNum, filePath);
            }
        }

        public static String reportRouteStatus(String routeType, Boolean returnNets) {
            String cmdStr = "report_route_status";
            if (routeType != null) {
                cmdStr += " -route_type " + routeType;
            }
            if (returnNets) {
                cmdStr += " -return_nets";
            }
            return cmdStr;
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
    
        public static String setProperty(String propertyName, String value, String targetCmd) {
            return String.format("set_property %s %s [%s]", propertyName, value, targetCmd);
        }

        public static String setBooleanProperty(String propertyName, boolean value, String targetCmd) {
            String valStr = value ? "TRUE" : "FALSE";
            return setProperty(propertyName, valStr, targetCmd);
        }

        public static String setPropertyHDReConfig(boolean val, String cellInstName) {
            String targetCmd = getCells(cellInstName);
            return setBooleanProperty("HD.RECONFIGURABLE", val, targetCmd);
        }
    
        public static void setPropertyHDReConfig(Design design, EDIFCellInst cellInst) {
            design.addXDCConstraint(setPropertyHDReConfig(true, cellInst.getName()));
        }
    
        public static String setPropertyHDPartition(boolean val, String cellInstName) {
            String targetCmd = getCells(cellInstName);
            return setBooleanProperty("HD.PARTITION", val, targetCmd);
        }
    
        public static void setPropertyHDPartition(Design design) {
            String targetCmd = "[current_design]";
            design.addXDCConstraint(setBooleanProperty("HD.PARTITION", true, targetCmd));
        }
    
        public static void setPropertyHDPartition(Design design, EDIFCellInst cellInst) {
            design.addXDCConstraint(setPropertyHDPartition(true, cellInst.getName()));
        }

        public static String setPropertyDontTouch(boolean val, String cellInstName) {
            String targetCmd = getCells(cellInstName);
            return setBooleanProperty("DONT_TOUCH", val, targetCmd);
        }

        public static void setPropertyDontTouch(Design design, EDIFCellInst cellInst) {
            design.addXDCConstraint(setPropertyDontTouch(true, cellInst.getName()));
        }
    
        public static String exitVivado() {
            return "exit";
        }

        public static String source(String filePath) {
            return String.format("source %s", filePath);
        }
    
        public static String setMaxThread(int maxThreadNum) {
            return String.format("set_param general.maxThreads %d", maxThreadNum);
        }

        public static String getCells(String pattern) {
            return String.format("get_cells %s", pattern);
        }

        public static String getPblocks(String pattern) {
            return String.format("get_pblocks %s", pattern);
        }

        public static List<String> saveWorstSetupSlack(String fileName) {
            List<String> cmds = new ArrayList<>();
            cmds.add("set fd [open " + fileName + " w]");
            cmds.add("puts $fd [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]");
            cmds.add("close $fd");
            return cmds;
        }

        public static String deletePblock(String pblockName) {
            return String.format("delete_pblock %s", pblockName);
        }

        public static String deletePblock() {
            return "delete_pblock *";
        }
    }
}
