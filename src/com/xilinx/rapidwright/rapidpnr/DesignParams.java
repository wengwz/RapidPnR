package com.xilinx.rapidwright.rapidpnr;

import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.HashSet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.NetlistUtils;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoProject;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.VivadoTclCmd;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.VivadoTclCmd.RouteDirective;


public class DesignParams {
    public static enum PartitionKernel {
        TRITON,
        FM,
        CUSTOM;
        public static PartitionKernel fromString(String name) {
            return Enum.valueOf(PartitionKernel.class, name);
        }
    };

    private String designName;
    private List<String> clkPortNames;
    private String mainClkName;
    private List<String> resetPortNames;
    private Map<String, Double> clkPeriods;
    private List<String> ignoreNetNames;

    private Boolean verbose = true;

    private String openroadCmd;
    private Path inputDcpPath;
    private Path workDir;

    // Layout Parameters
    private Coordinate2D gridDim;
    private Coordinate2D vertBoundaryDim;
    private Coordinate2D horiBoundaryDim;
    private List<Integer> dspGridLimit;
    private List<Integer> bramGridLimit;
    private List<Integer> uramGridLimit;
    private Map<String, String> pblockName2Range;

    // Netlist Abstraction Parameters
    private Integer abstractLevel = 2;

    // Island Placer Parameters
    private Set<String> prePlaceResTypes;
    private Boolean singleBoundaryConstr = true;
    private Double imbalanceFac = 0.01;
    private Integer randomSeed = 999;
    private Integer parallelRunNum = 20;
    private Integer ignoreEdgeDegree = Integer.MAX_VALUE;
    private Path extIslandPlacerPath = null;
    private Path islandPlaceResPath = null;
    private PartitionKernel partitionKernel = PartitionKernel.TRITON;
    private Double coarserLevelShrinkRatio = 2.0;
    private Double coarserMaxNodeSizeRatio = 1.0;

    // Physical Implementation Parameters
    private String boundaryPlaceOpt = null;
    private RouteDirective mergeRouteOpt = RouteDirective.Default;
    private Boolean mergeRoutePhysSyn = true;
    private RouteDirective islandRouteOpt = RouteDirective.Default;
    private Boolean islandRoutePhysSyn = true;
    private Boolean islandIODelayConstr = false;
    private Boolean fullRouteMerge = false;
    private Integer boundaryNeighborSize = 5000;
    private Integer boundaryNeighborDist = 1;
    private Double islandPeriodDecrement = 0.0;

    private class ParamsJson {
        public String designName;
        public List<String> clkPortNames;
        public String mainClkName;
        public List<String> resetPortNames;
        public Map<String, Double> clkPeriods;
        public List<String> ignoreNetNames;
        public Boolean verbose;
    
        public String inputDcpPath;
        public String workDir;
        public String vivadoCmd;
        public Integer vivadoMaxThreadNum;
        public String openroadCmd;

        public String layoutInfoJsonPath;

        public Integer abstractLevel;
        
        public List<String> prePlaceResTypes;
        public Boolean singleBoundaryConstr;
        public Integer randomSeed;
        public Double imbalanceFac;
        public Integer ignoreEdgeDegree;
        public Integer parallelRunNum;
        public String extIslandPlacerPath;
        public String islandPlaceResPath;
        public String partitionKernel;
        public Double coarserLevelShrinkRatio;
        public Double coarserMaxNodeSizeRatio;
        public Boolean islandIODelayConstr;

        public Boolean fullRouteMerge;
        public String boundaryPlaceOpt;
        public String islandRouteOpt;
        public Boolean islandRoutePhysSyn;
        public String mergeRouteOpt;
        public Boolean mergeRoutePhysSyn;
        public Integer boundaryNeighborSize;
        public Integer boundaryNeighborDist;
        public Double islandPeriodDecrement;
    }

    private class LayoutInfoJson {
        public List<Integer> gridDim;
        public List<Integer> bramGridLimit;
        public List<Integer> dspGridLimit;
        public List<Integer> uramGridLimit;
        public Map<String, String> pblockName2Range;
    };

    public DesignParams(Path jsonFilePath) {
        Gson gson = new GsonBuilder().create();
        try (FileReader reader = new FileReader(jsonFilePath.toFile())) {
            ParamsJson params = gson.fromJson(reader, ParamsJson.class);
            designName = params.designName;
            assert designName != null: "designName not found in json file";

            Set<String> clkPortNameSet = new HashSet<>();
            clkPortNames = new ArrayList<>();
            clkPeriods = new HashMap<>();

            for (String clkPortName : params.clkPortNames) {
                assert !clkPortNameSet.contains(clkPortName): "Duplicate clock port name: " + clkPortName;
                assert params.clkPeriods.containsKey(clkPortName): "Clock period not found for port: " + clkPortName;
                
                clkPortNameSet.add(clkPortName);
                clkPortNames.add(clkPortName);
                clkPeriods.put(clkPortName, params.clkPeriods.get(clkPortName));
            }
            assert !clkPortNames.isEmpty(): "No clock port specified in json file";

            if (params.mainClkName != null) {
                assert clkPortNameSet.contains(params.mainClkName): "Main clock name not found: " + params.mainClkName;
                this.mainClkName = params.mainClkName;
            }

            Set<String> resetPortNameSet = new HashSet<>();
            resetPortNames = new ArrayList<>();
            for (String resetPortName : params.resetPortNames) {
                assert !resetPortNameSet.contains(resetPortName): "Duplicate reset port name: " + resetPortName;
                resetPortNameSet.add(resetPortName);
                resetPortNames.add(resetPortName);
            }

            ignoreNetNames = new ArrayList<>();
            if (params.ignoreNetNames != null) {
                Set<String> ignoreNetNameSet = new HashSet<>(params.ignoreNetNames);
                ignoreNetNames.addAll(ignoreNetNameSet);
            }

            if (params.verbose != null) {
                this.verbose = params.verbose;
            }

            assert params.inputDcpPath != null: "inputDcpPath not found in json file";
            inputDcpPath = Path.of(params.inputDcpPath).toAbsolutePath();

            assert params.workDir != null: "workDir not found in json file";
            workDir = Path.of(params.workDir).toAbsolutePath().resolve(designName);

            // Vivado Parameters
            if (params.vivadoCmd != null) {
                VivadoProject.setVivadoCmd(params.vivadoCmd);
            }
            if (params.vivadoMaxThreadNum != null) {
                VivadoProject.setVivadoMaxThread(params.vivadoMaxThreadNum);
            }

            this.openroadCmd = params.openroadCmd;

            // Layout Parameters
            // assert params.gridDimension != null: "gridDimension not found in json file";
            // assert params.gridDimension.size() == 2: "gridDimension should have 2 elements";
            // this.gridDimension = new Coordinate2D(params.gridDimension.get(0), params.gridDimension.get(1));
            // this.vertBoundaryDim = new Coordinate2D(gridDimension.getX() - 1, gridDimension.getY());
            // this.horiBoundaryDim = new Coordinate2D(gridDimension.getX(), gridDimension.getY() - 1);
            
            // //
            // assert params.gridLimit != null: "gridLimit not found in json file";
            // assert params.gridLimit.size() == getIslandNum(): "gridLimit size does not match gridDimension";
            // this.gridLimit = params.gridLimit;
            // assert params.pblockRangeJsonPath != null: "pblockRangeJsonPath not found in json file";
            assert params.layoutInfoJsonPath != null: "layoutInfoJsonPath not found in json file:" + jsonFilePath.toString();
            LayoutInfoJson layoutInfo;
            try (FileReader layoutReader = new FileReader(params.layoutInfoJsonPath)) {
                layoutInfo = gson.fromJson(layoutReader, LayoutInfoJson.class);
            }
            assert layoutInfo.gridDim != null: "gridDim not found in layout info file";
            this.gridDim = new Coordinate2D(layoutInfo.gridDim.get(0), layoutInfo.gridDim.get(1));
            this.vertBoundaryDim = new Coordinate2D(gridDim.getX() - 1, gridDim.getY());
            this.horiBoundaryDim = new Coordinate2D(gridDim.getX(), gridDim.getY() - 1);

            assert layoutInfo.bramGridLimit != null: "bram grid limits not found in layout info file";
            assert layoutInfo.dspGridLimit != null: "dsp grid limits not found in layout info file";
            assert layoutInfo.uramGridLimit != null: "uram grid limits not found in layout info file";
            this.bramGridLimit = layoutInfo.bramGridLimit;
            this.dspGridLimit = layoutInfo.dspGridLimit;
            this.uramGridLimit = layoutInfo.uramGridLimit;


            assert layoutInfo.pblockName2Range != null: "pblock ranges not found in layout info file";
            this.pblockName2Range = layoutInfo.pblockName2Range;

            //
            if (params.abstractLevel != null) {
                assert params.abstractLevel >= 0 && params.abstractLevel <= 7: 
                "The level of netlist abstraction should in the range of [0, 7]"; 
                this.abstractLevel = params.abstractLevel;
            }

            // set parameters related with Island Placer
            if (params.singleBoundaryConstr != null) {
                this.singleBoundaryConstr = params.singleBoundaryConstr;
            }

            this.prePlaceResTypes = new HashSet<>();
            if (params.prePlaceResTypes != null) {
                for (String resName : params.prePlaceResTypes) {
                    assert NetlistUtils.resTypeNames.contains(resName);
                    this.prePlaceResTypes.add(resName);
                }
            }

            if (params.extIslandPlacerPath != null) {
                extIslandPlacerPath = Path.of(params.extIslandPlacerPath).toAbsolutePath();
            }

            if (params.islandPlaceResPath != null) {
                islandPlaceResPath = Path.of(params.islandPlaceResPath).toAbsolutePath();
            }

            if (params.randomSeed != null) {
                this.randomSeed = params.randomSeed;
            }

            if (params.imbalanceFac != null) {
                this.imbalanceFac = params.imbalanceFac;
            }

            if (params.ignoreEdgeDegree != null) {
                this.ignoreEdgeDegree = params.ignoreEdgeDegree;
            }

            if (params.parallelRunNum != null) {
                this.parallelRunNum = params.parallelRunNum;
            }

            if (params.partitionKernel != null) {
                this.partitionKernel = PartitionKernel.fromString(params.partitionKernel);
            }

            if (params.coarserLevelShrinkRatio != null) {
                this.coarserLevelShrinkRatio = params.coarserLevelShrinkRatio;
                assert this.coarserLevelShrinkRatio >= 1.0;
            }

            if (params.coarserMaxNodeSizeRatio != null) {
                this.coarserMaxNodeSizeRatio = params.coarserMaxNodeSizeRatio;
                assert coarserMaxNodeSizeRatio >= 0.0 && coarserMaxNodeSizeRatio <= 1.0;
            }

            // set parameters related with Physical Implementation
            if (params.fullRouteMerge != null) {
                this.fullRouteMerge = params.fullRouteMerge;
            }
            if (params.boundaryNeighborSize != null) {
                this.boundaryNeighborSize = params.boundaryNeighborSize;
            }
            if (params.boundaryNeighborDist != null) {
                this.boundaryNeighborDist = params.boundaryNeighborDist;
            }
            if (params.islandPeriodDecrement != null) {
                this.islandPeriodDecrement = params.islandPeriodDecrement;
            }
            if (params.islandIODelayConstr != null) {
                this.islandIODelayConstr = params.islandIODelayConstr;
            }

            if (params.boundaryPlaceOpt != null) {
                assert VivadoTclCmd.PlacerDirective.isVaildDirective(params.boundaryPlaceOpt);
                this.boundaryPlaceOpt = params.boundaryPlaceOpt;
            }

            if (params.mergeRouteOpt != null) {
                this.mergeRouteOpt = RouteDirective.fromString(params.mergeRouteOpt);
            }

            if (params.islandRouteOpt != null) {
                this.islandRouteOpt = RouteDirective.fromString(params.islandRouteOpt);
            }

            if (params.mergeRoutePhysSyn != null) {
                this.mergeRoutePhysSyn = params.mergeRoutePhysSyn;
            }

            if (params.islandRoutePhysSyn != null) {
                this.islandRoutePhysSyn = params.islandRoutePhysSyn;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // helper functions
    private int getIdFromLoc(Coordinate2D loc) { // grid limints are stored in column-major order
        return loc.getX() * gridDim.getY() + loc.getY();
    }

    // setters
    public void setClkPeriod(String clkName, Double period) {
        assert clkPortNames.contains(clkName): "Clock port name not found: " + clkName;
        clkPeriods.replace(clkName, period);
    }

    public void setAbstractLevel(int level) {
        assert level >= 0 && level <= 7: "The level of netlist abstraction should in the range of [0, 7]";
        this.abstractLevel = level;
    }

    // getters
    public String getDesignName() {
        return designName;
    }

    public String getClkPortName(int index) {
        return clkPortNames.get(index);
    }

    public String getMainClkName() {
        return mainClkName;
    }

    public List<String> getClkPortNames() {
        return Collections.unmodifiableList(clkPortNames);
    }

    public double getClkPeriod(String clkName) {
        return clkPeriods.get(clkName);
    }

    public Map<String, Double> getClkPortName2PeriodMap() {
        return Collections.unmodifiableMap(clkPeriods);
    }

    public String getResetPortName(int index) {
        return resetPortNames.get(index);
    }

    public List<String> getResetPortNames() {
        return Collections.unmodifiableList(resetPortNames);
    }

    public List<String> getIgnoreNetNames() {
        return Collections.unmodifiableList(ignoreNetNames);
    }

    public Path getInputDcpPath() {
        return inputDcpPath;
    }

    public Path getWorkDir() {
        return workDir;
    }

    public String getOpenroadCmd() {
        return openroadCmd;
    }

    public int getAbstractLevel() {
        return this.abstractLevel;
    }

    public Coordinate2D getGridDim() {
        return new Coordinate2D(gridDim.getX(), gridDim.getY());
    }

    public Coordinate2D getVertBoundaryDim() {
        return new Coordinate2D(vertBoundaryDim.getX(), vertBoundaryDim.getY());
    }

    public Coordinate2D getHoriBoundaryDim() {
        return new Coordinate2D(horiBoundaryDim.getX(), horiBoundaryDim.getY());
    }

    public int getIslandNum() {
        return gridDim.getX() * gridDim.getY();
    }

    public int getHoriBoundaryNum() {
        return horiBoundaryDim.getX() * horiBoundaryDim.getY();
    }

    public int getVertBoundaryNum() {
        return vertBoundaryDim.getX() * vertBoundaryDim.getY();
    }

    public List<Integer> getGridLimit(String resType) {
        if (resType.equals("BRAM")) {
            return Collections.unmodifiableList(bramGridLimit);
        } else if (resType.equals("DSP")) {
            return Collections.unmodifiableList(dspGridLimit);
        } else if (resType.equals("URAM")) {
            return Collections.unmodifiableList(uramGridLimit);
        } else {
            throw new IllegalArgumentException("Unknown resource type: " + resType);
        }
    }

    public int getGridLimit(String resType, Coordinate2D loc) {
        assert loc.getX() >= 0 && loc.getX() < gridDim.getX();
        assert loc.getY() >= 0 && loc.getY() < gridDim.getY();
        int id = getIdFromLoc(loc);
        if (resType.equals("BRAM")) {
            return bramGridLimit.get(id);
        } else if (resType.equals("DSP")) {
            return dspGridLimit.get(id);
        } else if (resType.equals("URAM")) {
            return uramGridLimit.get(id);
        } else {
            throw new IllegalArgumentException("Unknown resource type: " + resType);
        }
    }

    public boolean isVerbose() {
        return verbose;
    }

    public Map<String, String> getPblockName2Range() {
        return Collections.unmodifiableMap(pblockName2Range);
    }

    public String getPblockRange(String pblockName) {
        return pblockName2Range.get(pblockName);
    }

    public Path getExtIslandPlacerPath() {
        return extIslandPlacerPath;
    }

    public Path getIslandPlaceResPath() {
        return islandPlaceResPath;
    }

    public boolean hasSingleBoundaryConstr() {
        return singleBoundaryConstr;
    }

    public Set<String> getPrePlaceResTypes() {
        return Collections.unmodifiableSet(prePlaceResTypes);
    }

    public Integer getRandomSeed() {
        return randomSeed;
    }

    public Double getImbalanceFac() {
        return imbalanceFac;
    }

    public int getIgnoreEdgeDegree() {
        return ignoreEdgeDegree;
    }

    public int getParallelRunNum() {
        return parallelRunNum;
    }

    public PartitionKernel getPartitionKernel() {
        return partitionKernel;
    }

    public double getCoarserLevelShrinkRatio() {
        return coarserLevelShrinkRatio;
    }

    public double getCoarserMaxNodeSizeRatio() {
        return coarserMaxNodeSizeRatio;
    }

    public boolean isFullRouteMerge() {
        return fullRouteMerge;
    }

    public int getBoundaryNeighborSize() {
        return boundaryNeighborSize;
    }

    public int getBoundaryNeighborDist() {
        return boundaryNeighborDist;
    }

    public double getIslandPeriodDecrement() {
        return islandPeriodDecrement;
    }

    public boolean hasIslandIODelayConstr() {
        return islandIODelayConstr;
    }

    public String getBoundaryPlaceOpt() {
        return boundaryPlaceOpt;
    }

    public RouteDirective getMergeRouteOpt() {
        return mergeRouteOpt;
    }

    public RouteDirective getIslandRouteOpt() {
        return islandRouteOpt;
    }

    public boolean hasMergeRoutePhysSyn() {
        return mergeRoutePhysSyn;
    }

    public boolean hasIslandRoutePhysSyn() {
        return islandRoutePhysSyn;
    }
 
    public static void main(String[] args) {
        Path jsonConfigPath = Path.of("workspace/json/test_config.json");

        DesignParams params = new DesignParams(jsonConfigPath);
        System.out.println(params.getDesignName());
        System.out.println(params.getClkPortNames());
        System.out.println(params.getClkPeriod("clk1"));
    }

}
