package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.LocalJob;
import com.xilinx.rapidwright.util.FileTools;

import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.TclCmdFile;

public class VivadoProject {
    public static String VIVADO_CMD = "vivado";
    public static int MAX_THREAD = 24;
    public static final String BUILD_TCL_NAME = "vivado_build.tcl";
    public static final String INPUT_DCP_NAME = "input.dcp";
    public static final String OUTPUT_DCP_NAME = "output.dcp";
    public static final String OUTPUT_EDIF_NAME = "output.edif";

    private Path workDir;
    private Design design;
    private TclCmdFile tclCmdFile;

    static public void setVivadoCmd(String vivadoCmd) {
        VIVADO_CMD = vivadoCmd;
    }

    static public void setVivadoMaxThread(int maxThread) {
        MAX_THREAD = maxThread;
    }

    static public String getVivadoCmd() {
        return VIVADO_CMD;
    }
    static public int getVivadoMaxThread() {
        return MAX_THREAD;
    }

    public VivadoProject(Design design, Path workDir, TclCmdFile tclCmdFile) {
        this.design = design;
        this.workDir = workDir;
        this.tclCmdFile = tclCmdFile;
    }

    public void setDesign(Design design) {
        this.design = design;
    }

    public void setWorkDir(Path workDir) {
        this.workDir = workDir;
    }

    public void setTclCmdFile(TclCmdFile tclCmdFile) {
        this.tclCmdFile = tclCmdFile;
    }

    public Job createVivadoJob() {
        //// setup execution environment
        // create work directory
        if (!workDir.toFile().exists()) {
            workDir.toFile().mkdirs();
        }

        // write design checkpoint
        Path dcpPath = workDir.resolve(INPUT_DCP_NAME);
        design.writeCheckpoint(dcpPath.toString());

        // write tcl command file
        Path tclPath = workDir.resolve(BUILD_TCL_NAME);
        tclCmdFile.writeToFile(tclPath);

        //
        assert FileTools.isExecutableOnPath(VIVADO_CMD);
        String launchVivadoCmd = String.format("%s -mode batch -source %s", VIVADO_CMD, tclPath.toString());

        Job job = new LocalJob();
        job.setRunDir(workDir.toString());
        job.setCommand(launchVivadoCmd);

        return job;
    }
}
