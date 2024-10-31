package com.xilinx.rapidwright.rapidpnr;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.nio.file.Path;
import java.io.FileReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xilinx.rapidwright.util.LocalJob;


public class IslandPlacer {

    private HierarchicalLogger logger;
    private DirectoryManager dirManager;
    private Path extIslandPlacerPath;
    private Path islandPlaceResPath;

    private Coordinate2D gridDim;
    private List<Integer> gridLimit;
    private AbstractNetlist abstractNetlist;

    private static class IslandPlacerInputJson {
        public static class AbstractGroup {
            Integer id;
            Integer primCellNum;
            Map<String, Integer> resTypeUtil;

        };
        public static class AbstractEdge {
            Integer id;
            Integer weight;
            Integer degree;
            List<Integer> incidentGroupIds;
        }

        public Integer gridWidth;
        public Integer gridHeight;
        public List<Integer> gridLimit;

        public Integer totalGroupNum;
        public Integer totalEdgeNum;

        public List<AbstractGroup> abstractGroups;
        public List<AbstractEdge> abstractEdges;
    }

    private class IslandPlacerOutputJson {
        List<List<Integer>> groupLocs;
    }

    public IslandPlacer(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams params, AbstractNetlist netlist) {
        this.logger = logger;
        this.dirManager = dirManager;

        this.extIslandPlacerPath = params.getExtIslandPlacerPath();
        this.gridDim = params.getGridDim();
        this.gridLimit = params.getGridLimit();
        this.islandPlaceResPath = params.getIslandPlaceResPath();

        this.abstractNetlist = netlist;

        assert Files.exists(extIslandPlacerPath): "External island placer not found on: " + extIslandPlacerPath.toString();
    }

    public List<Coordinate2D> run() {

        if (islandPlaceResPath != null) {
            logger.info("Start reading island placer results from previous run");

            List<Coordinate2D> placeResults = readIslandPlacerOutputJson(islandPlaceResPath);
            logger.info("Complete reading island placer results from previous run");

            return placeResults;
        }

        logger.info("Start running island placer");
        logger.newSubStep();
        Path workDir = dirManager.addSubDir(NameConvention.islandPlacerDirName);
        Path inputJsonPath = workDir.resolve(NameConvention.islandPlacerInputJsonName);
        Path outputJsonPath = workDir.resolve(NameConvention.islandPlacerOutputJsonName);

        writeIslandPlacerInputJson(inputJsonPath);

        // launch external island placer
        logger.info("Lanuch external island placer");
        LocalJob job = new LocalJob();
        job.setRunDir(workDir.toString());
        job.setCommand(getRunExtIslandPlacerCmd(inputJsonPath, outputJsonPath));
        job.launchJob();

        int maxRunTime = 120; // max run time in seconds
        while (!job.isFinished()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            maxRunTime--;

            if (maxRunTime == 0) {
                job.killJob();
                assert false: "Execution of external island placer is timeout";
            }
        }
        assert job.jobWasSuccessful(): "Fail to run external island placer";
        logger.info("External island placer finished");

        List<Coordinate2D> placeResults = readIslandPlacerOutputJson(outputJsonPath);

        logger.endSubStep();
        logger.info("Complete running island placer");
        return placeResults;
    }

    public void writeIslandPlacerInputJson(Path inputJsonPath) {
        logger.info("Start writing island placer input in JSON format");
        IslandPlacerInputJson inputJson = new IslandPlacerInputJson();
        inputJson.gridWidth = gridDim.getX();
        inputJson.gridHeight = gridDim.getY();
        inputJson.gridLimit = gridLimit;

        inputJson.totalGroupNum = abstractNetlist.getGroupNum();
        List<IslandPlacerInputJson.AbstractGroup> abstractGroups = new ArrayList<>();
        for (int i = 0; i < abstractNetlist.getGroupNum(); i++) {
            IslandPlacerInputJson.AbstractGroup group = new IslandPlacerInputJson.AbstractGroup();

            group.id = i;
            group.primCellNum = abstractNetlist.group2LeafCellNum.get(i);
            Map<String, Integer> resTypeUtil = NetlistUtils.getResTypeUtils(abstractNetlist.group2LeafCellUtils.get(i));
            group.resTypeUtil = resTypeUtil;

            abstractGroups.add(group);
        }
        inputJson.abstractGroups = abstractGroups;

        Map<Set<Integer>, List<Integer>> incidentGroup2EdgeIdMap = compressAbstractEdges(abstractNetlist.edge2GroupIds);
        inputJson.totalEdgeNum = incidentGroup2EdgeIdMap.size();
        
        List<IslandPlacerInputJson.AbstractEdge> abstractEdges = new ArrayList<>();
        Integer compressedEdgeId = 0;
        for (Map.Entry<Set<Integer>, List<Integer>> entry : incidentGroup2EdgeIdMap.entrySet()) {
            IslandPlacerInputJson.AbstractEdge edge = new IslandPlacerInputJson.AbstractEdge();
            edge.id = compressedEdgeId++;
            edge.weight = entry.getValue().size();
            edge.degree = entry.getKey().size();
            edge.incidentGroupIds = new ArrayList<>(entry.getKey());
            abstractEdges.add(edge);
        }
        inputJson.abstractEdges = abstractEdges;

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.writeString(inputJsonPath, gson.toJson(inputJson));
        } catch (IOException e) {
            assert false: "Fail to write island placer input in JSON format";
            e.printStackTrace();
        }

        logger.info("Complete writing island placer input in JSON format");

    }

    public List<Coordinate2D> readIslandPlacerOutputJson(Path outputJsonPath) {

        logger.info("Start reading island placer output in JSON format");
        List<Coordinate2D> groupPlaceResults = new ArrayList<>();
        Gson gson = new GsonBuilder().create();
        try (FileReader reader = new FileReader(outputJsonPath.toFile())) {
            IslandPlacerOutputJson placeResultJson = gson.fromJson(reader, IslandPlacerOutputJson.class);
            assert placeResultJson.groupLocs != null;

            // check island placer results
            assert placeResultJson.groupLocs.size() == abstractNetlist.getGroupNum();
            for (List<Integer> loc : placeResultJson.groupLocs) {
                assert loc.size() == 2;
                assert loc.get(0) >= 0 && loc.get(0) < gridDim.getX();
                assert loc.get(1) >= 0 && loc.get(1) < gridDim.getY();

                groupPlaceResults.add(new Coordinate2D(loc.get(0), loc.get(1)));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        logger.info("Complete reading island placer output in JSON format");
        return groupPlaceResults;
    }

    private Map<Set<Integer>, List<Integer>> compressAbstractEdges(List<Set<Integer>> edge2GroupIds) {
        logger.info("Start compressing abstract edges");
        logger.newSubStep();

        Map<Set<Integer>, List<Integer>> incidentGroup2EdgeIdMap = new HashMap<>();


        for (int i = 0; i < edge2GroupIds.size(); i++) {
            Set<Integer> incidentGroupIds = edge2GroupIds.get(i);
            if (incidentGroup2EdgeIdMap.containsKey(incidentGroupIds)) {
                incidentGroup2EdgeIdMap.get(incidentGroupIds).add(i);
            } else {
                incidentGroup2EdgeIdMap.put(incidentGroupIds, new ArrayList<>(Arrays.asList(i)));
            }
        }

        logger.info("Total number of compressed edges: " + incidentGroup2EdgeIdMap.size());
        logger.endSubStep();
        logger.info("Complete compressing abstract edges");
        return incidentGroup2EdgeIdMap;
    }

    private String getRunExtIslandPlacerCmd(Path inputJsonPath, Path outputJsonPath) {
        return extIslandPlacerPath.toString() + " " + inputJsonPath.toString() + " " + outputJsonPath.toString();
    }
}
