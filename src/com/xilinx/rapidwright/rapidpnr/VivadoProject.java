package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.LocalJob;
import com.xilinx.rapidwright.util.FileTools;

import com.xilinx.rapidwright.rapidpnr.VivadoTclUtils.TclCmdFile;

public class VivadoProject {

    public static final String BUILD_TCL_NAME = "vivado_build.tcl";
    public static final String INPUT_DCP_NAME = "input.dcp";
    public static final String OUTPUT_DCP_NAME = "output.dcp";

    private Path workDir;
    private Design design;
    private TclCmdFile tclCmdFile;
    private String vivadoCmd;

    public VivadoProject(Design design, Path workDir, String vivadoCmd, TclCmdFile tclCmdFile) {
        this.design = design;
        this.workDir = workDir;
        this.tclCmdFile = tclCmdFile;
        this.vivadoCmd = vivadoCmd;
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

    public void setVivadoCmd(String vivadoCmd) {
        this.vivadoCmd = vivadoCmd;
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
        assert FileTools.isExecutableOnPath(vivadoCmd);
        String launchVivadoCmd = String.format("%s -mode batch -source %s", vivadoCmd, tclPath.toString());

        Job job = new LocalJob();
        job.setRunDir(workDir.toString());
        job.setCommand(launchVivadoCmd);

        return job;
    }
}
