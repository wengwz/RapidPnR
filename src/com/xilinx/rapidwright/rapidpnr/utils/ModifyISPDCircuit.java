package com.xilinx.rapidwright.rapidpnr.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

public class ModifyISPDCircuit {

    public static void main(String[] args) {
        String inputDCPPath = "/home/wanzheng/tb/ispd16-fpga04.dcp";
        String outputDCPPath = "workspace/dcp/ispd16-fpga04.dcp";
        Integer targetDSPNum = 200;
        Integer targetBRAMNum = 200;

        Design design = Design.readCheckpoint(inputDCPPath);
        EDIFCell topCell = design.getNetlist().getTopCell();

        // Remove extra DSPs and BRAMs
        Set<EDIFCellInst> dspCellInsts = new HashSet<>();
        Set<EDIFCellInst> bramCellInsts = new HashSet<>();
        
        for (EDIFCellInst cellInst : topCell.getCellInsts()) {
            if (cellInst.getCellName().equals("RAMB36E2")) {
                bramCellInsts.add(cellInst);
            } else if (cellInst.getCellName().equals("DSP48E2")) {
                dspCellInsts.add(cellInst);
            }
        }

        System.out.println("Num of DSPs: " + dspCellInsts.size());
        System.out.println("Num of BRAMs: " + bramCellInsts.size());

        while (dspCellInsts.size() > targetDSPNum) {
            EDIFCellInst cellInst = dspCellInsts.iterator().next();
            if (topCell.getCellInst(cellInst.getName()) != null) {
                removeCellInst(cellInst);
            }
            dspCellInsts.remove(cellInst);
        }

        while (bramCellInsts.size() > targetBRAMNum) {
            EDIFCellInst cellInst = bramCellInsts.iterator().next();
            if (topCell.getCellInst(cellInst.getName()) != null) {
                removeCellInst(cellInst);
            }
            bramCellInsts.remove(cellInst);
        }

        // remove IO Buffers
        Design newDesign = removeIO(design);

        newDesign.setAutoIOBuffers(false);

        newDesign.writeCheckpoint(outputDCPPath);
    }

    public static Design removeIO(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        List<EDIFCellInst> topCellInsts = new ArrayList<>(topCell.getCellInsts());

        for (EDIFCellInst cellInst : topCellInsts) {
            if (!NetlistUtils.isIOBufCellInst(cellInst)) {
                continue;
            }

            EDIFPortInst outPortInst = cellInst.getPortInst("O");
            EDIFNet outNet = outPortInst.getNet();
            EDIFPortInst inPortInst = cellInst.getPortInst("I");
            EDIFNet inNet = inPortInst.getNet();
            assert outNet != null && inNet != null;
            outNet.removePortInst(outPortInst);
            inNet.removePortInst(inPortInst);

            EDIFNet removedNet = outNet;
            EDIFNet extendedNet = inNet;

            if (cellInst.getCellName().equals("OBUF")) {
                removedNet = inNet;
                extendedNet = outNet;
            }

            List<EDIFPortInst> transferredPortInsts = new ArrayList<>(removedNet.getPortInsts());
            for (EDIFPortInst portInst : transferredPortInsts) {
                removedNet.removePortInst(portInst);
                extendedNet.addPortInst(portInst);
            }

            topCell.removeNet(removedNet);
            if (cellInst.getCellName().equals("BUFGCE")) {
                EDIFPortInst cePortInst = cellInst.getPortInst("CE");
                EDIFNet ceNet = cePortInst.getNet();
                assert ceNet != null && (ceNet.isVCC() || ceNet.isGND());
                ceNet.removePortInst(cePortInst);

                if (ceNet.getPortInsts().size() == 1) {
                    topCell.removeNet(ceNet);
                }
            }

            topCell.removeCellInst(cellInst);
        }
        return design;
    }

    public static void removeCellInst(EDIFCellInst cellInst) {
        EDIFCell cellType = cellInst.getCellType();
        EDIFCell parentCell = cellInst.getParentCell();

        EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, parentCell, parentCell.getNetlist());
        System.out.println(String.format("Remove cell instance %s of type %s from cell %s", cellInst.getName(), cellType.getName(), parentCell.getName()));

        for (EDIFPortInst portInst : cellInst.getPortInsts()) {
            EDIFNet net = portInst.getNet();
            if (net == null) {
                System.out.println(String.format("PortInst %s of cellInst %s is not connected to any net", portInst.getName(), cellInst.getName()));
                continue;
            }

            if (portInst.isInput()) {
                net.removePortInst(portInst);

                if (net.getPortInsts().size() == 0) {
                    parentCell.removeNet(net);
                } else if (net.getPortInsts().size() == 1) {
                    EDIFPortInst remainPortInst = net.getPortInsts().iterator().next();
                    assert remainPortInst != null;
                    EDIFCellInst remainCellInst = remainPortInst.getCellInst();

                    if (remainCellInst == null) {
                        net.removePortInst(remainPortInst);
                        parentCell.removeNet(net);
                    } else {
                        List<EDIFPortInst> outputPortInsts = NetlistUtils.getOutPortInstsOf(remainCellInst);
                        if (outputPortInsts.size() == 1) {
                            removeCellInst(remainCellInst);
                        } else {
                            net.removePortInst(remainPortInst);
                            parentCell.removeNet(net);
                        }
                    }
                }
            } else {
                for (EDIFPortInst sinkPortInst : NetlistUtils.getSinkPortsOf(net)) {
                    net.removePortInst(sinkPortInst);
                    gndNet.addPortInst(sinkPortInst);
                }

                net.removePortInst(portInst);
                parentCell.removeNet(net);
            }            
        }

        parentCell.removeCellInst(cellInst);
    }
}
