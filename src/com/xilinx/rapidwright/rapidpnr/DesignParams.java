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
import java.lang.reflect.Type;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;



public class DesignParams {

    private String designName;

    private List<String> clkPortNames;
    private List<String> resetPortNames;
    private Map<String, Double> clkPeriods;
    private List<String> ignoreNetNames;

    private Path inputDcpPath;
    private Path workDir;

    // Layout Parameters
    private Coordinate2D gridDimension;
    private Coordinate2D vertBoundaryDim;
    private Coordinate2D horiBoundaryDim;
    private List<Integer> gridLimit;
    private Map<String, String> pblockName2RangeMap;

    // Island Placer Parameters
    private Path extIslandPlacerPath;
    private Path islandPlaceResPath;


    private class ParamsJson {
        public String designName;
    
        public List<String> clkPortNames;
        public List<String> resetPortNames;
        public Map<String, Double> clkPeriods;
        public List<String> ignoreNetNames;
    
        public String inputDcpPath;
        public String workDir;

        public List<Integer> gridDimension;
        public List<Integer> gridLimit;
        public String pblockRangeJsonPath;

        public String vivadoCmd;
        public Integer vivadoMaxThreadNum;

        public String extIslandPlacerPath;
        public String islandPlaceResPath;

    }

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

            assert params.inputDcpPath != null: "inputDcpPath not found in json file";
            inputDcpPath = Path.of(params.inputDcpPath);

            assert params.workDir != null: "workDir not found in json file";
            workDir = Path.of(params.workDir).toAbsolutePath().resolve(designName);

            //
            assert params.gridDimension != null: "gridDimension not found in json file";
            assert params.gridDimension.size() == 2: "gridDimension should have 2 elements";
            this.gridDimension = new Coordinate2D(params.gridDimension.get(0), params.gridDimension.get(1));
            this.vertBoundaryDim = new Coordinate2D(gridDimension.getX() - 1, gridDimension.getY());
            this.horiBoundaryDim = new Coordinate2D(gridDimension.getX(), gridDimension.getY() - 1);
            
            //
            assert params.gridLimit != null: "gridLimit not found in json file";
            assert params.gridLimit.size() == getIslandNum(): "gridLimit size does not match gridDimension";
            this.gridLimit = params.gridLimit;
            assert params.pblockRangeJsonPath != null: "pblockRangeJsonPath not found in json file";
            try (FileReader pblockReader = new FileReader(params.pblockRangeJsonPath)) {
                Type mapType = new TypeToken<Map<String, String>>() {}.getType();
                this.pblockName2RangeMap = gson.fromJson(pblockReader, mapType);
            }

            // set parameters related with Vivado
            if (params.vivadoCmd != null) {
                VivadoProject.setVivadoCmd(params.vivadoCmd);
            }
            if (params.vivadoMaxThreadNum != null) {
                VivadoProject.setVivadoMaxThread(params.vivadoMaxThreadNum);
            }

            // set parameters related with Island Placer
            assert params.extIslandPlacerPath != null: "extIslandPlacerPath not found in json file";
            extIslandPlacerPath = Path.of(params.extIslandPlacerPath).toAbsolutePath();

            if (params.islandPlaceResPath != null) {
                islandPlaceResPath = Path.of(params.islandPlaceResPath).toAbsolutePath();
            } else {
                islandPlaceResPath = null;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // setters
    public void setClkPeriod(String clkName, Double period) {
        assert clkPortNames.contains(clkName): "Clock port name not found: " + clkName;
        clkPeriods.replace(clkName, period);
    }

    // getters
    public String getDesignName() {
        return designName;
    }

    public String getClkPortName(int index) {
        return clkPortNames.get(index);
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

    public Coordinate2D getGridDim() {
        return new Coordinate2D(gridDimension.getX(), gridDimension.getY());
    }

    public Coordinate2D getVertBoundaryDim() {
        return new Coordinate2D(vertBoundaryDim.getX(), vertBoundaryDim.getY());
    }

    public Coordinate2D getHoriBoundaryDim() {
        return new Coordinate2D(horiBoundaryDim.getX(), horiBoundaryDim.getY());
    }

    public int getIslandNum() {
        return gridDimension.getX() * gridDimension.getY();
    }

    public int getHoriBoundaryNum() {
        return horiBoundaryDim.getX() * horiBoundaryDim.getY();
    }

    public int getVertBoundaryNum() {
        return vertBoundaryDim.getX() * vertBoundaryDim.getY();
    }

    public List<Integer> getGridLimit() {
        return Collections.unmodifiableList(gridLimit);
    }

    public Map<String, String> getPblockName2RangeMap() {
        return Collections.unmodifiableMap(pblockName2RangeMap);
    }

    public String getPblockRange(String pblockName) {
        return pblockName2RangeMap.get(pblockName);
    }

    public Path getExtIslandPlacerPath() {
        return extIslandPlacerPath;
    }

    public Path getIslandPlaceResPath() {
        return islandPlaceResPath;
    }

    public static void main(String[] args) {
        Path jsonConfigPath = Path.of("workspace/json/test_config.json");

        DesignParams params = new DesignParams(jsonConfigPath);
        System.out.println(params.getDesignName());
        System.out.println(params.getClkPortNames());
        System.out.println(params.getClkPeriod("clk1"));
    }

}
