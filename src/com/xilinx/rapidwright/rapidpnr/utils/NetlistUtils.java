package com.xilinx.rapidwright.rapidpnr.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class NetlistUtils {
    // public static final HashSet<String> regCellTypeNames = new HashSet<>(Arrays.asList("FDSE", "FDRE", "FDCE", "FDPE", "SRL16E"));
    public static final HashSet<String> regCellTypeNames = new HashSet<>(Arrays.asList("FDSE", "FDRE", "FDCE", "FDPE"));
    public static final HashSet<String> ioBufCellTypeNames = new HashSet<>(Arrays.asList("OBUF", "IBUFCTRL", "INBUF", "IBUF", "BUFGCE"));
    public static final HashSet<String> lutCellTypeNames = new HashSet<>(Arrays.asList("LUT1", "LUT2", "LUT3", "LUT4", "LUT5", "LUT6"));
    public static final HashSet<String> srlCellTypeNames = new HashSet<>(Arrays.asList("SRL16E", "SRLC32E"));

    // public static final HashSet<String> resTypeNames = new HashSet<>(Arrays.asList("LUT", "FF", "CARRY", "DSP", "BRAM", "IO", "URAM", "MISCS"));
    public static final HashSet<String> resTypeNames = new HashSet<>(Arrays.asList("LUT", "LUTM", "FF", "CARRY", "DSP", "BRAM", "IO", "URAM", "MISCS"));

    public static final HashMap<String, String> cellType2ResTypeMap = new HashMap<String, String>() {
        {
            // LUT
            put("LUT1", "MISCS");
            
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
            put("RAMS64E", "LUT");

            // put("RAMD32",  "LUTM");
            // put("RAMS32",  "LUTM");
            // put("RAMD64E", "LUTM");
            // put("SRL16E",  "LUTM");
            // put("SRLC32E", "LUTM");
            // put("RAMS64E", "LUTM");

            // CARRY
            put("CARRY8", "CARRY");

            // FF
            put("FDSE", "FF");
            put("FDRE", "FF");
            put("FDCE", "FF");
            put("FDPE", "FF");

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

            // DSP
            put("DSP48E2", "DSP");
            
            // URAM
            put("URAM288", "URAM");
        }
    };

    public static Set<String> sequentialLogicCellNames;
    public static Set<String> clkPortNames;
    static {
        // registers
        sequentialLogicCellNames = new HashSet<>();
        sequentialLogicCellNames.add("FDRE");
        sequentialLogicCellNames.add("FDSE");
        sequentialLogicCellNames.add("FDCE");
        sequentialLogicCellNames.add("FDPE");

        // Memory
        sequentialLogicCellNames.add("SRL16E");
        sequentialLogicCellNames.add("SRLC32E");
        sequentialLogicCellNames.add("RAMD64E");
        sequentialLogicCellNames.add("RAMD32");
        sequentialLogicCellNames.add("RAMS32");
        sequentialLogicCellNames.add("RAMB36E2");
        sequentialLogicCellNames.add("RAMB18E2");


        clkPortNames = new HashSet<>();
        clkPortNames.add("C");
        clkPortNames.add("CLK");
        clkPortNames.add("CLKBWRCLK");
        clkPortNames.add("CLKARDCLK");
    };

    public static boolean isSequentialLogic(EDIFCell cell) {
        return sequentialLogicCellNames.contains(cell.getName());
    }

    public static boolean isSequentialLogic(EDIFCellInst cellInst) {
        return isSequentialLogic(cellInst.getCellType());
    }

    public static boolean isClkPort(EDIFPort port) {
        return clkPortNames.contains(port.getName());
    }

    public static boolean isClkPort(EDIFPortInst portInst) {
        return isClkPort(portInst.getPort());
    }

    public static boolean isSourcePortInst(EDIFPortInst portInst) {
        EDIFCellInst cellInst = portInst.getCellInst();
        return cellInst != null ? portInst.isOutput() : portInst.isInput();
    }

    public static boolean isSinkPortInst(EDIFPortInst portInst) {
        return !isSourcePortInst(portInst);
    }

    public static HashSet<String> pseudoLeafCellNames = new HashSet<>(Arrays.asList("DSP48E2"));

    public static Map<String, Map<String, Integer>> nonPrimUnisimCellUtils;

    static {
        nonPrimUnisimCellUtils = new HashMap<>();
        nonPrimUnisimCellUtils.put("RAM32M", new HashMap<>() {
            {
                put("RAMD32", 3);
                put("RAMS32", 1);
            }
        });

        nonPrimUnisimCellUtils.put("RAM32M16", new HashMap<>() {
            {
                put("RAMD32", 7);
                put("RAMS32", 1);
            }
        });
    }

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
    public static Boolean isIOBufCellInst(EDIFCellInst cellInst) {
        return ioBufCellTypeNames.contains(cellInst.getCellType().getName());
    }

    public static List<EDIFPortInst> getSinkPortsOf(EDIFNet net) {
        List<EDIFPortInst> sinkPortInsts = new ArrayList<>();
        for (EDIFPortInst portInst : net.getPortInsts()) {
            if (portInst.getCellInst() == null) {
                if (portInst.isOutput()) { // top-level port
                    sinkPortInsts.add(portInst);
                }
            } else {
                if (portInst.isInput()) { // ports of internal cells
                    sinkPortInsts.add(portInst);
                }
            }
        }
        return sinkPortInsts;
    }

    public static List<EDIFNet> getFanoutNetOf(EDIFCellInst cellInst) {
        List<EDIFNet> outNets = new ArrayList<>();
        for (EDIFPortInst portInst : cellInst.getPortInsts()) {
            if (portInst.isOutput() && portInst.getNet() != null) {
                outNets.add(portInst.getNet());
            }
        }
        return outNets;
    }

    public static int getFanoutOfNet(EDIFNet net) {
        return net.getPortInsts().size() - 1;
    } 

    public static List<EDIFPortInst> getOutPortInstsOf(EDIFCellInst cellInst) {
        List<EDIFPortInst> outputPortInsts = new ArrayList<>();
        for (EDIFPortInst portInst : cellInst.getPortInsts()) {
            if (portInst.isOutput()) {
                outputPortInsts.add(portInst);
            }
        }
        return outputPortInsts;
    }

    public static List<EDIFPortInst> getInPortInstsOf(EDIFCellInst cellInst) {
        List<EDIFPortInst> inputPortInsts = new ArrayList<>();
        for (EDIFPortInst portInst : cellInst.getPortInsts()) {
            if (portInst.isInput()) {
                inputPortInsts.add(portInst);
            }
        }
        return inputPortInsts;
    }

    public static Integer getLeafCellNum(EDIFCell cell) {
        Map<EDIFCell, Integer> leafCellUtilMap = new HashMap<>();
        getLeafCellUtils(cell, leafCellUtilMap);
        Integer leafCellNum = 0;
        for (Integer amount : leafCellUtilMap.values()) {
            leafCellNum += amount;
        }
        return leafCellNum;
    }

    public static void getLeafCellUtils(EDIFCell cell, Map<EDIFCell, Integer> leafCellUtilMap) {
        assert leafCellUtilMap != null;
        if (cell.isLeafCellOrBlackBox() || pseudoLeafCellNames.contains(cell.getName())) {
            if (leafCellUtilMap.containsKey(cell)) {
                Integer amount = leafCellUtilMap.get(cell);
                leafCellUtilMap.replace(cell, amount + 1);
            } else {
                leafCellUtilMap.put(cell, 1);
            }
        } else if (nonPrimUnisimCellUtils.containsKey(cell.getName())) {
            EDIFLibrary hdiPrimLibrary = cell.getLibrary().getNetlist().getHDIPrimitivesLibrary();
            
            Map<String, Integer> unisimCellUtilMap = nonPrimUnisimCellUtils.get(cell.getName());
            for (Map.Entry<String, Integer> entry : unisimCellUtilMap.entrySet()) {
                String primCellName = entry.getKey();
                Integer amount = entry.getValue();
                EDIFCell primCell = hdiPrimLibrary.getCell(primCellName);
                if (leafCellUtilMap.containsKey(primCell)) {
                    Integer originAmount = leafCellUtilMap.get(primCell);
                    leafCellUtilMap.replace(primCell, originAmount + amount);
                } else {
                    leafCellUtilMap.put(primCell, amount);
                }
            }

        } else {
            for (EDIFCellInst childCellInst : cell.getCellInsts()) {
                getLeafCellUtils(childCellInst.getCellType(), leafCellUtilMap);
            }
        }
    }

    public static void calibrateLUTUtils(EDIFCell topCell, Map<EDIFCell, Integer> leafCellUtilMap) {
        calibrateLUTUtils(new HashSet<>(topCell.getCellInsts()), leafCellUtilMap);
    }
    public static void calibrateLUTUtils(Set<EDIFCellInst> cellInstsRange, Map<EDIFCell, Integer> leafCellUtilMap) {
        Set<String> packedLUTCellTypeName = new HashSet<>(Arrays.asList("LUT2", "LUT3", "LUT4", "LUT5"));
        //Set<String> packedLUTCellTypeName = new HashSet<>(Arrays.asList("LUT4", "LUT5"));

        Set<EDIFCellInst> packedLUTCellInsts = new HashSet<>();

        for (EDIFCellInst cellInst : cellInstsRange) {
            String cellTypeName = cellInst.getCellType().getName();

            if (!packedLUTCellTypeName.contains(cellTypeName)) continue;
            if (packedLUTCellInsts.contains(cellInst)) continue;

            Map<EDIFCellInst, Integer> neighbor2CommonNetNum = new HashMap<>();
            
            List<EDIFPortInst> inputPorts = getInPortInstsOf(cellInst);
            for (EDIFPortInst portInst : inputPorts) {
                EDIFNet net = portInst.getNet();
                
                for (EDIFPortInst sinkPortInst : getSinkPortsOf(net)) {
                    EDIFCellInst sinkCellInst = sinkPortInst.getCellInst();
                    if (sinkCellInst == null || sinkCellInst == cellInst) continue;

                    if (sinkCellInst.getCellName().equals(cellTypeName)) {
                        if (neighbor2CommonNetNum.containsKey(sinkCellInst)) {
                            Integer netNum = neighbor2CommonNetNum.get(sinkCellInst);
                            neighbor2CommonNetNum.replace(sinkCellInst, netNum + 1);
                        } else {
                            neighbor2CommonNetNum.put(sinkCellInst, 1);
                        }
                    }
                }
            }

            List<Map.Entry<EDIFCellInst, Integer>> sortedNeighbors = neighbor2CommonNetNum.entrySet().stream()
            .sorted(Map.Entry.<EDIFCellInst, Integer>comparingByValue().reversed()).collect(Collectors.toList());

            //Set<Map.Entry<EDIFCellInst, Integer>> sortedNeighbors = neighbor2CommonNetNum.entrySet();
            for (Map.Entry<EDIFCellInst, Integer> neighbor : sortedNeighbors) {
                
                EDIFCellInst neighborCellInst = neighbor.getKey();
                Integer commonNetNum = neighbor.getValue();
                
                Integer packThreshold = inputPorts.size();
                if (cellTypeName.equals("LUT3") || cellTypeName.equals("LUT2")) {
                    packThreshold = 2;
                }
                //else if (cellTypeName.equals("LUT2")) {
                //     packThreshold = 1;
                // }
                
                if (commonNetNum >= packThreshold) {
                    packedLUTCellInsts.add(neighborCellInst);
                    Integer amount = leafCellUtilMap.get(neighborCellInst.getCellType());
                    leafCellUtilMap.replace(neighborCellInst.getCellType(), amount - 1);
                    break;
                } 
            }
        }

        // for (EDIFCell cellType : leafCellUtilMap.keySet()) {
        //     Integer amount = leafCellUtilMap.get(cellType);
        //     if (cellType.getName().equals("LUT2")) {
        //         leafCellUtilMap.replace(cellType, (int) Math.ceil(amount / 3.0));
        //     } else if (cellType.getName().equals("LUT3")) {
        //         leafCellUtilMap.replace(cellType, (int) Math.ceil(amount / 2.0));
        //     }
        // }

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

            assert cellType2ResTypeMap.containsKey(primCell.getName()): String.format("Cell type %s not found", primCell.getName());
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

    public static EDIFCellInst getSourceCellInstOfNet(EDIFNet net) {
        List<EDIFPortInst> srcPortInsts = net.getSourcePortInsts(true);
        assert srcPortInsts.size() == 1;
        return srcPortInsts.get(0).getCellInst();
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

    public static Boolean isRegFanoutNet(EDIFNet net) {
        List<EDIFPortInst> srcPortInsts = net.getSourcePortInsts(true);
        assert srcPortInsts.size() == 1: String.format("Net %s has %d source ports", net.getName(), srcPortInsts.size());
        EDIFPortInst srcPortInst = srcPortInsts.get(0);
        EDIFCellInst srcCellInst = srcPortInst.getCellInst();
        if (srcCellInst == null) return false;
        return isRegisterCellInst(srcCellInst);
    }

    public static EDIFCellInst cellReplication(EDIFCellInst originCellInst, List<EDIFPortInst> transferPortInsts) {
        // replicate register originCellInst and transfer fanout cell insts specified in transferCellInsts to new register
        assert isRegisterCellInst(originCellInst) || isLutCellInst(originCellInst): "originCellInst must be FF or LUT";
        EDIFCell originCellType = originCellInst.getCellType();
        EDIFCell parentCell = originCellInst.getParentCell();

        EDIFPortInst outPortInst = getOutPortInstsOf(originCellInst).get(0);
        Set<EDIFPortInst> fanoutPortInsts = new HashSet<>(getSinkPortsOf(outPortInst.getNet()));
        assert fanoutPortInsts.containsAll(transferPortInsts): "transferPortInsts must be subset of fanoutPortInsts";
        if (fanoutPortInsts.size() == transferPortInsts.size()) {
            return originCellInst;
        }

        // check name confliction
        int repCellId = 0;
        String repCellInstName = originCellInst.getName() + "_rep_" + repCellId;
        while (parentCell.getCellInst(repCellInstName) != null) {
            repCellId++;
            repCellInstName = originCellInst.getName() + "_rep_" + repCellId;
        }
        assert parentCell.getNet(repCellInstName) == null: String.format("Net %s already exists", repCellInstName);

        EDIFCellInst repCellInst = parentCell.createChildCellInst(repCellInstName, originCellType);
        repCellInst.setPropertiesMap(originCellInst.createDuplicatePropertiesMap());

        // copy port connections
        for (EDIFPortInst portInst : originCellInst.getPortInsts()) {
            EDIFPort port = portInst.getPort();
            EDIFNet net = portInst.getNet();

            if (portInst.isInput()) { // copy connections of input ports
                net.createPortInst(port, repCellInst);
            } else { // transfer connections with specified output ports to new replicated cellInsts

                EDIFNet newNet = parentCell.createNet(repCellInstName);
                newNet.createPortInst(port, repCellInst);
                for (EDIFPortInst transferPortInst : transferPortInsts) {
                    net.removePortInst(transferPortInst);
                    newNet.addPortInst(transferPortInst);
                }
            }
        }
        return repCellInst;
    }

    public static EDIFCellInst insertLUTBufOnNet(EDIFNet net, List<EDIFPortInst> transferPortInsts) {
        EDIFCell parentCell = net.getParentCell();

        int bufId = 0;
        String bufCellInstName = net.getName() + "_buf_" + bufId;
        while (parentCell.getCellInst(bufCellInstName) != null) {
            bufId++;
            bufCellInstName = net.getName() + "_buf_" + bufId;
        }
        EDIFCellInst bufCellInst = createLUTBuf(bufCellInstName, parentCell);


        net.createPortInst("I0", bufCellInst);

        EDIFNet bufOutNet = parentCell.createNet(bufCellInstName);
        
        EDIFPortInst bufOutPortInst = bufOutNet.createPortInst("O", bufCellInst);
        assert bufOutPortInst != null;

        int transferPortNum = 0;
        Set<EDIFPortInst> originNetPortInsts = new HashSet<>(net.getPortInsts());
        for (EDIFPortInst portInst : transferPortInsts) {
            if (originNetPortInsts.contains(portInst) && isSinkPortInst(portInst)) {
                net.removePortInst(portInst);
                bufOutNet.addPortInst(portInst);
                transferPortNum++;
            }
        }

        assert transferPortNum > 0: "No legal transferred port instrances for buffer";

        return bufCellInst;
    }

    public static EDIFCellInst insertLUTBufOnNet(EDIFNet net) {
        List<EDIFPortInst> transferPortInsts = new ArrayList<>(net.getPortInsts());
        return insertLUTBufOnNet(net, transferPortInsts);
    }

    public static EDIFCellInst createLUTBuf(String cellInstName, EDIFCell parentCell) {
        EDIFNetlist netlist = parentCell.getNetlist();
        EDIFLibrary hdiPrimLibrary = netlist.getHDIPrimitivesLibrary();
        EDIFCell lut1Cell = hdiPrimLibrary.getCell("LUT1");
        if (lut1Cell == null) {
            lut1Cell = Design.getUnisimCell(Unisim.LUT1);
            hdiPrimLibrary.addCell(lut1Cell);
        }
        EDIFCellInst lut1CellInst = lut1Cell.createCellInst(cellInstName, parentCell);
        lut1CellInst.addProperty("INIT", "2'h2");
        return lut1CellInst;
    }

    public static boolean isCellHasIllegalNet(EDIFCell topCell) {
        boolean hasIllegalNet = false;
        for (EDIFNet net : topCell.getNets()) {
            int netDegree = net.getPortInsts().size();
            int srcPortInstNum = net.getSourcePortInsts(true).size();

            if (netDegree <= 1 || srcPortInstNum != 1) {
                hasIllegalNet = true;
                break;
            }
        }
        return hasIllegalNet;
    }

    public static boolean isCascadedWithMUXF(EDIFCellInst cellInst) {
        if (!isRegisterCellInst(cellInst)) return false;
        EDIFPortInst inPortInst = cellInst.getPortInst("D");
        EDIFNet inNet = inPortInst.getNet();
        EDIFCellInst srcCellInst = getSourceCellInstOfNet(inNet);
        if (srcCellInst == null) return false;
        return srcCellInst.getCellName().equals("MUXF7") || srcCellInst.getCellName().equals("MUXF8");
    }
        
}
