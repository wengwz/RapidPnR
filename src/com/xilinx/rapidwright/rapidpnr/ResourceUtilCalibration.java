package com.xilinx.rapidwright.rapidpnr;

import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.nio.file.Path;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.design.Unisim;

public class ResourceUtilCalibration {

    public static Design testLUT1Design() {
        Design design = new Design("testLUT1", "xcvu3p-ffvc1517-1-e");
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();
        EDIFCell lut1 = Design.getUnisimCell(Unisim.LUT1);
        EDIFCell lut6 = Design.getUnisimCell(Unisim.LUT6);

        netlist.getHDIPrimitivesLibrary().addCell(lut1);
        netlist.getHDIPrimitivesLibrary().addCell(lut6);
        design.setAutoIOBuffers(false);

        List<EDIFCellInst> lut1CellInsts = new ArrayList<>();

        EDIFCellInst sinkLut6CellInst = lut6.createCellInst("sintk_lut6", topCell);
        EDIFCellInst sourceLut6CellInst = lut6.createCellInst("source_lut6", topCell);

        EDIFNet srcNet = topCell.createNet("src");
        srcNet.createPortInst("O", sourceLut6CellInst);

        for (int i = 0; i < 6; i++) {
            EDIFNet sinkNet = topCell.createNet("sink_" + i);
            sinkNet.createPortInst("I" + i, sinkLut6CellInst);

            EDIFCellInst cellInst = lut1.createCellInst("lut1_" + i, topCell);
            srcNet.createPortInst("I0", cellInst);
            sinkNet.createPortInst("O", cellInst);
            lut1CellInsts.add(cellInst);
            cellInst.addProperty("INIT", "2'h2");
        }

        design.writeCheckpoint("workspace/testLUT1.dcp");


        return design;
    }
    public static void main(String[] args) {

        testLUT1Design();

        Path workDir = Path.of("workspace", "res_calib");
        String partName = "xcvu3p-ffvc1517-1-e";
        Map<Unisim, Integer> unisimCellType2AmountMap = new HashMap<>();

        Boolean commonInputNet = true;
        unisimCellType2AmountMap.put(Unisim.LUT1, 2);
        unisimCellType2AmountMap.put(Unisim.LUT2, 2);
        unisimCellType2AmountMap.put(Unisim.LUT3, 3);
        unisimCellType2AmountMap.put(Unisim.LUT5, 2);
        // unisimCellType2AmountMap.put(Unisim.RAM32X1D, 1);
        // unisimCellType2AmountMap.put(Unisim.RAM32M16, 1);
        // unisimCellType2AmountMap.put(Unisim.RAM32M, 1);
        //Design baseDesign = new Design("base", "xcvu3p-ffvc1517-1-e");

        if (!workDir.toFile().exists()) {
            workDir.toFile().mkdirs();
        }

        for (Unisim unisim : unisimCellType2AmountMap.keySet()) {
            EDIFCell cellType = Design.getUnisimCell(unisim);
            String cellTypeName = cellType.getName();
            int amount = unisimCellType2AmountMap.get(unisim);

            System.out.println(String.format("Create design with %d cell instances of %s", amount, cellTypeName));

            String designName = cellTypeName + "_" + amount;
            Design design = new Design(designName, partName);
            EDIFNetlist netlist = design.getNetlist();
            EDIFCell topCell = netlist.getTopCell();
            netlist.getHDIPrimitivesLibrary().addCell(cellType);
            design.setAutoIOBuffers(false);

            for (int i = 0; i < amount; i++) {
                cellType.createCellInst(cellTypeName + "_" + i, topCell);
            }

            for (EDIFCellInst cellInst : topCell.getCellInsts()) {

                for (EDIFPort port : cellType.getPorts()) {
                    String topPortName = cellInst.getName() + "_" + port.getName();
                    if (commonInputNet && port.isInput()) {
                        topPortName = port.getName();
                    }

                    EDIFPort topPort = topCell.getPort(topPortName);
                    if (topPort == null) {
                        topPort = topCell.createPort(topPortName, port.getDirection(), port.getWidth());
                    }

                    if (port.getWidth() == 1) {
                        EDIFNet net = topCell.getNet(topPortName);
                        if (net == null) {
                            net = topCell.createNet(topPortName);
                            net.createPortInst(topPort);
                        }
                        net.createPortInst(port, cellInst);
                    } else {
                        for (int i = 0; i < topPort.getWidth(); i++) {
                            EDIFNet net = topCell.createNet(topPort.getPortInstNameFromPort(i));
                            net.createPortInst(topPort, i);
                            net.createPortInst(port, i, cellInst);
                        }
                    }
                }
            }

            design.writeCheckpoint(workDir.resolve(designName + ".dcp").toString());
        }

    }
}
