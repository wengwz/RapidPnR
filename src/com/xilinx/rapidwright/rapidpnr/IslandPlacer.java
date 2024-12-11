package com.xilinx.rapidwright.rapidpnr;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.xilinx.rapidwright.util.LocalJob;
import com.xilinx.rapidwright.rapidpnr.utils.Coordinate2D;
import com.xilinx.rapidwright.rapidpnr.utils.DirectoryManager;
import com.xilinx.rapidwright.rapidpnr.utils.HierarchicalLogger;

public class IslandPlacer extends AbstractIslandPlacer{

    private Path extIslandPlacerPath;

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

    // private class IslandPlacerOutputJson {
    //     List<List<Integer>> groupLocs;
    // }

    public IslandPlacer(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams params) {
        super(logger, dirManager, params);

        this.extIslandPlacerPath = params.getExtIslandPlacerPath();
        assert Files.exists(extIslandPlacerPath): "External island placer not found on: " + extIslandPlacerPath.toString();
    }

    public List<Coordinate2D> run(AbstractNetlist netlist) {

        this.abstractNetlist = netlist;

        if (islandPlaceResPath != null) {
            logger.info("Start reading island placer results from previous run");

            List<Coordinate2D> placeResults = readIslandPlaceResultJson(islandPlaceResPath);
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

        int maxRunTime = 1000; // max run time in seconds
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

        List<Coordinate2D> placeResults = readIslandPlaceResultJson(outputJsonPath);
        assert placeResults.size() == abstractNetlist.getNodeNum(): "Num of groups in Json file: " + placeResults.size() + " Num of groups in abstract netlist: " + abstractNetlist.getNodeNum();

        logger.endSubStep();
        logger.info("Complete running island placer");
        return placeResults;
    }

    private void writeIslandPlacerInputJson(Path inputJsonPath) {
        logger.info("Start writing island placer input in JSON format");
        IslandPlacerInputJson inputJson = new IslandPlacerInputJson();
        inputJson.gridWidth = gridDim.getX();
        inputJson.gridHeight = gridDim.getY();
        inputJson.gridLimit = gridLimit;

        inputJson.totalGroupNum = abstractNetlist.getNodeNum();
        List<IslandPlacerInputJson.AbstractGroup> abstractGroups = new ArrayList<>();
        for (int i = 0; i < abstractNetlist.getNodeNum(); i++) {
            IslandPlacerInputJson.AbstractGroup group = new IslandPlacerInputJson.AbstractGroup();

            group.id = i;
            group.primCellNum = abstractNetlist.getLeafCellNumOfNode(i);
            group.resTypeUtil = abstractNetlist.getResUtilOfNode(i);

            abstractGroups.add(group);
        }
        inputJson.abstractGroups = abstractGroups;

        Map<Set<Integer>, List<Integer>> incidentGroup2EdgeIdMap = compressAbstractEdges(abstractNetlist.edge2NodeIds);
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



    // private Map<Set<Integer>, List<Integer>> compressAbstractEdges(List<Set<Integer>> edge2GroupIds) {
    //     logger.info("Start compressing abstract edges");
    //     logger.newSubStep();

    //     Map<Set<Integer>, List<Integer>> incidentGroup2EdgeIdMap = new HashMap<>();


    //     for (int i = 0; i < edge2GroupIds.size(); i++) {
    //         Set<Integer> incidentGroupIds = edge2GroupIds.get(i);
    //         if (incidentGroup2EdgeIdMap.containsKey(incidentGroupIds)) {
    //             incidentGroup2EdgeIdMap.get(incidentGroupIds).add(i);
    //         } else {
    //             incidentGroup2EdgeIdMap.put(incidentGroupIds, new ArrayList<>(Arrays.asList(i)));
    //         }
    //     }

    //     logger.info("Total number of compressed edges: " + incidentGroup2EdgeIdMap.size());
    //     logger.endSubStep();
    //     logger.info("Complete compressing abstract edges");
    //     return incidentGroup2EdgeIdMap;
    // }

    private String getRunExtIslandPlacerCmd(Path inputJsonPath, Path outputJsonPath) {
        //TODO: 
        return extIslandPlacerPath.toString() + "  2800" + " " + inputJsonPath.toString() + " " + outputJsonPath.toString();
    }
}
