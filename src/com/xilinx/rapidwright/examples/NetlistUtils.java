package com.xilinx.rapidwright.examples;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class NetlistUtils {
    public static final HashSet<String> regCellTypeNames = new HashSet<>(Arrays.asList("FDSE", "FDRE", "FDCE"));
    public static final HashSet<String> ioCellTypeNames = new HashSet<>(Arrays.asList("OBUF", "IBUFCTRL", "INBUF", "IBUF"));
    public static final HashSet<String> lutCellTypeNames = new HashSet<>(Arrays.asList("LUT1", "LUT2", "LUT3", "LUT4", "LUT5", "LUT6"));
    public static final HashSet<String> srlCellTypeNames = new HashSet<>(Arrays.asList("SRL16E", "SRLC32E"));

    public static final HashSet<String> resTypeNames = new HashSet<>(Arrays.asList("LUT", "FF", "CARRY", "DSP", "BRAM", "IO", "MISCS"));
    public static final HashMap<String, String> cellType2ResTypeMap = new HashMap<String, String>() {
        {
            // LUT
            put("LUT1", "LUT");
            put("LUT2", "LUT");
            put("LUT3", "LUT");
            put("LUT4", "LUT");
            put("LUT5", "LUT");
            put("LUT6", "LUT");
            put("RAMD32", "LUT");
            put("RAMS32", "LUT");
            put("RAMD64E", "LUT");
            put("SRL16E", "LUT");
            put("SRLC32E", "LUT");

            // CARRY
            put("CARRY8", "CARRY");

            // FF
            put("FDSE", "FF");
            put("FDRE", "FF");
            put("FDCE", "FF");
            // BRAM
            put("RAMB36E2", "BRAM");
            put("RAMB18E2", "BRAM");

            // IO
            put("OBUF", "IO");
            put("INBUF", "IO");
            put("IBUFCTRL", "IO");
            put("BUFGCE", "IO");

            // MISCS
            put("MUXF7", "MISCS");
            put("MUXF8", "MISCS");
            put("INV", "MISCS");
            put("VCC", "MISCS");
            put("GND", "MISCS");

        }
    };

    public static Boolean isRegisterCellInst(EDIFCellInst cellInst) {
        return regCellTypeNames.contains(cellInst.getCellType().getName());
    }
    public static Boolean isLutCellInst(EDIFCellInst cellInst) {
        return lutCellTypeNames.contains(cellInst.getCellName());
    }
    public static Boolean isLutOneCellInst(EDIFCellInst cellInst) {
        return cellInst.getCellName().equals("LUT1");
    }
    public static Boolean isSRLCellInst(EDIFCellInst  cellInst) {
        return srlCellTypeNames.contains(cellInst.getCellType().getName());
    }
    public static Boolean isIOCellInst(EDIFCellInst cellInst) {
        return ioCellTypeNames.contains(cellInst.getCellType().getName());
    }

    public static List<EDIFPortInst> getSinkPortsOf(EDIFNet net) {
        List<EDIFPortInst> sinkPortInsts = new ArrayList<>();
        for (EDIFPortInst portInst : net.getPortInsts()) {
            if (portInst.isInput()) {
                sinkPortInsts.add(portInst);
            }
        }
        return sinkPortInsts;
    }

    public static List<EDIFPortInst> getOutPortsOf(EDIFCellInst cellInst) {
        List<EDIFPortInst> outputPortInsts = new ArrayList<>();
        for (EDIFPortInst portInst : cellInst.getPortInsts()) {
            if (portInst.isOutput()) {
                outputPortInsts.add(portInst);
            }
        }
        return outputPortInsts;
    }

    public static void getLeafCellUtils(EDIFCell cell, Map<EDIFCell, Integer> leafCellUtilMap) {
        assert leafCellUtilMap != null;
        if (cell.isLeafCellOrBlackBox()) {
            if (leafCellUtilMap.containsKey(cell)) {
                Integer amount = leafCellUtilMap.get(cell);
                leafCellUtilMap.replace(cell, amount + 1);
            } else {
                leafCellUtilMap.put(cell, 1);
            }
        } else {
            for (EDIFCellInst childCellInst : cell.getCellInsts()) {
                getLeafCellUtils(childCellInst.getCellType(), leafCellUtilMap);
            }
        }
    }

    public static Map<String, Integer> getResTypeUtils(EDIFCell cell) {
        Map<EDIFCell, Integer> leafCellUtilMap = new HashMap<>();
        getLeafCellUtils(cell, leafCellUtilMap);
        return getResTypeUtils(leafCellUtilMap);
    }

    public static Map<String, Integer> getResTypeUtils(Map<EDIFCell, Integer> primCellUtilMap) {
        Map<String, Integer> resTypeUtil = new HashMap<>();
        for (Map.Entry<EDIFCell, Integer> entry : primCellUtilMap.entrySet()) {
            EDIFCell primCell = entry.getKey();
            Integer amount = entry.getValue();

            assert cellType2ResTypeMap.containsKey(primCell.getName());
            String resType = cellType2ResTypeMap.get(primCell.getName());
            if (resTypeUtil.containsKey(resType)) {
                Integer resTypeAmount = resTypeUtil.get(resType);
                resTypeUtil.replace(resType, resTypeAmount + amount);
            } else {
                resTypeUtil.put(resType, amount);
            }
        }
        return resTypeUtil;
    }

    public static List<EDIFCellInst> getCellInstsOfNet(EDIFNet net) {
        List<EDIFCellInst> cellInsts = new ArrayList<>();
        for (EDIFPortInst portInst : net.getPortInsts()) {
            EDIFCellInst cellInst = portInst.getCellInst();
            if (cellInst == null) continue;
            cellInsts.add(cellInst);
        }
        return cellInsts;
    }

    public static List<String> getCellInstsNameOfNet(EDIFNet net) {
        List<String> cellInstNames = new ArrayList<>();
        for (EDIFPortInst portInst : net.getPortInsts()) {
            EDIFCellInst cellInst = portInst.getCellInst();
            if (cellInst == null) continue;
            cellInstNames.add(cellInst.getName());
        }
        return cellInstNames;
    }
}
