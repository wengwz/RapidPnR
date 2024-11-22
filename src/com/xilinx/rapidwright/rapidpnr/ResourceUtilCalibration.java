package com.xilinx.rapidwright.rapidpnr;

import java.util.Map;
import java.util.HashMap;
import java.nio.file.Path;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.design.Unisim;

public class ResourceUtilCalibration {


    public static void main(String[] args) {

        Path workDir = Path.of("workspace", "res_calib");
        String partName = "xcvu3p-ffvc1517-1-e";
        Map<Unisim, Integer> unisimCellType2AmountMap = new HashMap<>();

        unisimCellType2AmountMap.put(Unisim.LUT2, 1);
        unisimCellType2AmountMap.put(Unisim.LUT3, 3);
        unisimCellType2AmountMap.put(Unisim.RAM32X1D, 1);
        unisimCellType2AmountMap.put(Unisim.RAM32M16, 1);
        unisimCellType2AmountMap.put(Unisim.RAM32M, 1);
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
                    EDIFPort topPort = topCell.createPort(topPortName, port.getDirection(), port.getWidth());
                    if (port.getWidth() == 1) {
                        EDIFNet net = topCell.createNet(topPortName);
    
                        net.createPortInst(topPort);
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
