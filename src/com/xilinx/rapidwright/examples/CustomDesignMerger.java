/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.xilinx.rapidwright.examples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.design.merge.AbstractDesignMerger;
import com.xilinx.rapidwright.design.merge.MergeDesigns;

/**
 * Provides a basic design merging behavior when merging designs.  If no other design merger is
 * implemented, {@link MergeDesigns#mergeDesigns(com.xilinx.rapidwright.design.Design...)} is used
 * by default.
 */
public class CustomDesignMerger extends AbstractDesignMerger {

    private Map<String, String> replacedNets = new HashMap<>();
    private Integer mergeNetCount = 0;

    private EDIFPortInst getSingleSource(EDIFNet net) {
        List<EDIFPortInst> srcs = net.getSourcePortInsts(true);
        if (srcs.size() == 0) return null;
        if (srcs.size() == 1) return srcs.get(0);
        throw new RuntimeException("ERROR: Net "+ net +" has more than one source!");
    }

    private boolean checkIfNetSourcesMergeCompatible(EDIFNet n0, EDIFNet n1) {
        List<EDIFPortInst> srcs0 = n0.getSourcePortInsts(true);
        List<EDIFPortInst> srcs1 = n1.getSourcePortInsts(true);

        if (srcs0.size() != 1 || srcs1.size() != 1) {
            return false;
        }

        if (!srcs0.get(0).getFullName().equals(srcs1.get(0).getFullName())) {
            if (!srcs0.get(0).isTopLevelPort() && !srcs1.get(0).isTopLevelPort()) {
                return false;
            }
        }
        return true;
    }

    private List<EDIFPortInst> getPortInstsOfTopLevelPort(EDIFPort port) {
        Set<EDIFPortInst> portInsts = new HashSet<>();
        for (EDIFNet net : port.getInternalNets()) {
            if (net == null) continue;
            for (EDIFPortInst portInst : net.getPortInsts()) {
                if (portInst.getPort() == port) {
                    portInsts.add(portInst);
                }
            }
        }
        return new ArrayList<>(portInsts);
    }

    @Override
    public void mergePorts(EDIFPort p0, EDIFPort p1) {
        if (!p0.isBusRangeEqual(p1)) {
            // TODO - Perhaps there are future use cases where disjoint ranges could be
            // merged, but we'll leave that exercise for another day
            throw new RuntimeException("ERROR: Port range mismatch " + p0.getName()
                + " and " + p1.getName());
        }

        if (p0.getDirection() != p1.getDirection()) {
            boolean p0IsOutput = p0.isOutput();
            // Two ports with same name but opposite direction, plan to remove both and connect
            List<EDIFNet> nets0 = p0.getInternalNets();
            List<EDIFNet> nets1 = p1.getInternalNets();

            assert nets0.size() == 1 && nets0.get(0) != null;
            assert nets1.size() == 1 && nets1.get(0) != null;
            
            EDIFNet net0 = nets0.get(0);
            EDIFNet net1 = nets1.get(0);
            List<EDIFPortInst> toSwitch = new ArrayList<>();
            ArrayList<EDIFPortInst> toRemove = new ArrayList<>();

            for (EDIFPortInst portInst0 : net0.getPortInsts()) {
                if (portInst0.isTopLevelPort() && portInst0.getPort() == p0) {
                    toRemove.add(portInst0);
                } else if (!p0IsOutput) {
                    toSwitch.add(portInst0);
                    // Update site routing if net is not the same name
                    if (!net0.getName().equals(net1.getName()) && portInst0.isInput()) {
                        replacedNets.put(net0.getName(), net1.getName());
                    }
                }
            }
            for (EDIFPortInst pi : toSwitch) {
                net0.removePortInst(pi);
                net1.addPortInst(pi);
            }
            toSwitch.clear();

            for (EDIFPortInst portInst1 : net1.getPortInsts()) {
                if (portInst1.isTopLevelPort() && portInst1.getPort() == p1) {
                    toRemove.add(portInst1);
                } else if (p0IsOutput) {
                    toSwitch.add(portInst1);
                }
            }
            for (EDIFPortInst pi : toSwitch) {
                net1.removePortInst(pi);
                net0.addPortInst(pi);
            }
            toSwitch.clear();
            

            for (EDIFPortInst remove : toRemove) {
                EDIFNet net = remove.getNet();
                net.removePortInst(remove);
                if (net.getPortInsts().size() == 0) {
                    net.getParentCell().removeNet(net);
                }
            }

            p0.getParentCell().removePort(p0);
            p1.getParentCell().removePort(p1);
            return;
        }

        if (p0.getWidth() == 1) {
            assert p0.getDirection() == EDIFDirection.INPUT: "Two merged designs is not allowed to have identical 1-bit output ports";
            List<EDIFNet> nets0 = p0.getInternalNets();
            List<EDIFNet> nets1 = p1.getInternalNets();

            assert nets0.size() == 1 && nets0.get(0) != null;
            assert nets1.size() == 1 && nets1.get(0) != null;

            EDIFNet net1 = nets1.get(0);
            List<EDIFPortInst> net1PortInsts = new ArrayList<>(net1.getPortInsts());

            for (EDIFPortInst portInst : net1PortInsts) {
                portInst.getNet().removePortInst(portInst);
                nets0.get(0).addPortInst(portInst);
            }
            net1.getParentCell().removeNet(net1);
            p1.getParentCell().removePort(p1);

        } else {
            for (EDIFPortInst portInst : getPortInstsOfTopLevelPort(p1)) {
                assert p0.getParentCell().getInternalNet(portInst.getName()) == null;
                portInst.setPort(p0);
            }
        }
    }

    @Override
    public void mergeLogicalNets(EDIFNet n0, EDIFNet n1) {
        assert (n0.isGND() || n0.isVCC()) && (n1.isGND() || n1.isVCC());
        //assert false: "Logical nets of two merged design can't have any overlaps: " + n0.getName();

        for (EDIFPortInst p1 : new ArrayList<>(n1.getPortInsts())) {
            if (p1.isOutput() && !p1.isTopLevelPort()) continue;
            if (n0.getPortInst(p1.getCellInst(), p1.getName()) == null) {
                n1.removePortInst(p1);
                n0.addPortInst(p1);
            }
        }
    }

    @Override
    public void mergeCellInsts(EDIFCellInst i0, EDIFCellInst i1) {
        assert i0.getCellType().isStaticSource() && i1.getCellType().isStaticSource();
    }

    @Override
    public void mergeSiteInsts(SiteInst s0, SiteInst s1) {
        assert false: "SiteInsts of two merged design can't have any overlaps";
    }

    @Override
    public void mergePhysicalNets(Net n0, Net n1) {
        if (n0.getName().equals("stage_8/FWBFLY.bfly/do_rnd_right_r/ROUND_RESULT.o_val[7]_i_2__0_n_0")) {
            System.out.println(String.format("Merging %d physical nets %s and %s", mergeNetCount, n0.getName(), n1.getName()));
        }
        Set<PIP> pips = new HashSet<>(n0.getPIPs());
        if (n1 != null) pips.addAll(n1.getPIPs());
        n0.setPIPs(pips);
        mergeNetCount++;
    }


}

