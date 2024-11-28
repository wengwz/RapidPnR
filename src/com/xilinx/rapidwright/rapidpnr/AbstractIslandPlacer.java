package com.xilinx.rapidwright.rapidpnr;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

abstract public class AbstractIslandPlacer {
    protected HierarchicalLogger logger;
    protected DirectoryManager dirManager;
    protected Path islandPlaceResPath;

    protected DesignParams designParams;
    protected Coordinate2D gridDim;
    protected Coordinate2D horiBoundaryDim;
    protected Coordinate2D vertBoundaryDim;
    protected List<Integer> gridLimit;
    protected AbstractNetlist abstractNetlist;


    public class IslandPlaceResultJson {
        List<List<Integer>> groupLocs;
    }

    public AbstractIslandPlacer(HierarchicalLogger logger, DirectoryManager dirManager, DesignParams params) {
        this.logger = logger;
        this.dirManager = dirManager;

        this.designParams = params;
        this.gridDim = params.getGridDim();
        this.horiBoundaryDim = params.getHoriBoundaryDim();
        this.vertBoundaryDim = params.getVertBoundaryDim();
        
        this.gridLimit = params.getGridLimit();
        this.islandPlaceResPath = params.getIslandPlaceResPath();
    }

    public abstract List<Coordinate2D> run(AbstractNetlist netlist);

    protected List<Coordinate2D> readIslandPlaceResultJson(Path islandPlaceResultPath) {
        logger.info("Start reading island placer output in JSON format");
        List<Coordinate2D> placeResults = new ArrayList<>();
        Gson gson = new GsonBuilder().create();

        try (FileReader reader = new FileReader(islandPlaceResultPath.toFile())) {
            IslandPlaceResultJson placeResultJson = gson.fromJson(reader, IslandPlaceResultJson.class);
            assert placeResultJson.groupLocs != null;

            // check island placer results
            for (List<Integer> loc : placeResultJson.groupLocs) {
                assert loc.size() == 2;
                assert loc.get(0) >= 0 && loc.get(0) < gridDim.getX();
                assert loc.get(1) >= 0 && loc.get(1) < gridDim.getY();

                placeResults.add(new Coordinate2D(loc.get(0), loc.get(1)));
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        logger.info("Complete reading island placer output in JSON format");
        return placeResults;
    }

    protected void writeIslandPlaceResultJson(Path islandPlaceResultPath, List<Coordinate2D> groupLocs) {
        logger.info("Start writing results of island placer in JSON format");
        Gson gson = new GsonBuilder().create();
        IslandPlaceResultJson placeResultJson = new IslandPlaceResultJson();
        List<List<Integer>> groupLocsJson = new ArrayList<>();
        for (Coordinate2D loc : groupLocs) {
            groupLocsJson.add(Arrays.asList(loc.getX(), loc.getY()));
        }
        placeResultJson.groupLocs = groupLocsJson;

        try {
            Files.write(islandPlaceResultPath, gson.toJson(placeResultJson).getBytes());
        } catch (IOException e) {
            assert false: "Fail to write island placer input in JSON format";
            e.printStackTrace();
        }

        logger.info("Complete writing results of island placer in JSON format");
    }

    protected Map<Set<Integer>, List<Integer>> compressAbstractEdges(List<Set<Integer>> edge2GroupIds) {
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

}
