package com.xilinx.rapidwright.rapidpnr;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.RuntimeTracker;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoProject;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.TclCmdFile;
import com.xilinx.rapidwright.rapidpnr.utils.VivadoTclUtils.VivadoTclCmd;

public class TestMaxFrequency {
    private static int MAX_PARALLEL_JOBS = 4;
    private static class Benchmark {
        String designName;
        String mainClkName;
        Double minPeriod;
        Double maxPeriod;
        Map<String, Double> period2RuntimeMap;
        Map<String, Double> period2SlackMap;

        public Benchmark(String designName, String mainClkName, Double maxPeriod, Double minPeriod, Double step) {
            assert step > 0.01;
            this.designName = designName;
            this.mainClkName = mainClkName;
            this.maxPeriod = maxPeriod;
            this.minPeriod = minPeriod;

            period2RuntimeMap = new HashMap<>();
            period2SlackMap = new HashMap<>();
            for (Double period = minPeriod; period <= maxPeriod; period += step) {
                period2SlackMap.put(toString(period), -1.0);
                period2RuntimeMap.put(toString(period), -1.0);
            }
        }

        public static String toString(Double value) {
            return String.format("%.2f", value);
        }

    }

    Map<String, Benchmark> benchmarks;
    Path jsonFilePath;

    public TestMaxFrequency(Path jsonFilePath) {
        this.jsonFilePath = jsonFilePath;
        if (jsonFilePath.toFile().exists()) {
            // read existing JSON file
            Gson gson = new Gson();
            try (BufferedReader reader = new BufferedReader(new FileReader(jsonFilePath.toString()))){
                Type mapType = new TypeToken<Map<String, Benchmark>>() {}.getType();
                benchmarks = gson.fromJson(reader, mapType);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            benchmarks = new HashMap<>();
        }
    }

    public void addBenchmark(String designName, Double maxPeriod, Double minPeriod, Double step, String mainClkName) {
        assert step > 0.01;
        if (benchmarks.containsKey(designName)) {
            Benchmark benchmark = benchmarks.get(designName);
            assert benchmark.mainClkName.equals(mainClkName): String.format("mainClkName mismatch: %s vs %s", benchmark.mainClkName, mainClkName);
            if (maxPeriod > benchmark.maxPeriod) {
                benchmark.maxPeriod = maxPeriod;
            }
            if (minPeriod < benchmark.minPeriod) {
                benchmark.minPeriod = minPeriod;
            }
            
            for (Double period = minPeriod; period <= maxPeriod; period += step) {
                String periodKey = Benchmark.toString(period);
                if (!benchmark.period2SlackMap.containsKey(periodKey)) {
                    benchmark.period2SlackMap.put(periodKey, -1.0);
                    benchmark.period2RuntimeMap.put(periodKey, -1.0);
                }
            }
        } else {
            Benchmark benchmark = new Benchmark(designName, mainClkName, maxPeriod, minPeriod, step);
            benchmarks.put(designName, benchmark);
        }
    }

    public void parallelUpdate() {
        // TODO: parallelRun can't support recording runtime now
        JobQueue jobQueue = new JobQueue();

        for (Benchmark benchmark : benchmarks.values()) {
            System.out.println("Benchmark Name: " + benchmark.designName);
            DesignParams designParams = new DesignParams(Path.of("workspace", "json", benchmark.designName + ".json"));
            Path designDir = designParams.getWorkDir().resolve("baseline");

            for (String periodKey : benchmark.period2SlackMap.keySet()) {
                if (benchmark.period2SlackMap.get(periodKey) != -1.0) {
                    continue;
                }

                Path sampleDir = designDir.resolve(periodKey);
                if (!sampleDir.toFile().exists()) {
                    sampleDir.toFile().mkdirs();
                }
                
                designParams.setClkPeriod(benchmark.mainClkName, Double.parseDouble(periodKey));
                // prepare input design
                //Design design = Design.readCheckpoint(designParams.getInputDcpPath());
                //design.setAutoIOBuffers(false);
                //setDesignConstraints(design, designParams);

                // create tcl cmd file
                TclCmdFile tclCmdFile = createTclCmdFile(designParams);

                // create vivado project
                VivadoProject vivadoProject = new VivadoProject(sampleDir, tclCmdFile);
                Job vivadoJob = vivadoProject.createVivadoJob();
                jobQueue.addJob(vivadoJob);
            }
        }

        System.out.println("Start running PnR of all design samples in parallel");
        RuntimeTracker timer = new RuntimeTracker("Parallel PnR of all samples", (short) 0);
        timer.start();
        jobQueue.runAllToCompletion(MAX_PARALLEL_JOBS);
        timer.stop();

        // parse slacks
        for (Benchmark benchmark : benchmarks.values()) {
            DesignParams designParams = new DesignParams(Path.of("workspace", "json", benchmark.designName + ".json"));
            Path designDir = designParams.getWorkDir().resolve("baseline");

            for (String periodKey : benchmark.period2SlackMap.keySet()) {
                Path sampleDir = designDir.resolve(periodKey);
                if (benchmark.period2SlackMap.get(periodKey) == -1.0) {
                    Path slackFilePath = sampleDir.resolve("slack.txt");
                    benchmark.period2SlackMap.put(periodKey, parseSlack(slackFilePath));
                }
            }
        }

        System.out.println("Complete running PnR of all design samples in parallel");
        System.out.println(timer.toString());
    }

    public void parseSlack() {
        for (Benchmark benchmark : benchmarks.values()) {
            DesignParams designParams = new DesignParams(Path.of("workspace", "json", benchmark.designName + ".json"));
            Path designDir = designParams.getWorkDir().resolve("baseline");

            for (String periodKey : benchmark.period2SlackMap.keySet()) {
                Path sampleDir = designDir.resolve(periodKey);

                if (benchmark.period2SlackMap.get(periodKey) == -1.0) {
                    Path slackFilePath = sampleDir.resolve("slack.txt");
                    if (slackFilePath.toFile().exists()) {
                        benchmark.period2SlackMap.put(periodKey, parseSlack(slackFilePath));
                    }
                }
            }
        }
    }

    public void sequentialUpdate() {
        JobQueue jobQueue = new JobQueue();

        for (Benchmark benchmark : benchmarks.values()) {

            DesignParams designParams = new DesignParams(Path.of("workspace", "json", benchmark.designName + ".json"));
            Path designDir = designParams.getWorkDir().resolve("baseline");

            for (String periodKey : benchmark.period2SlackMap.keySet()) {

                if (benchmark.period2SlackMap.get(periodKey) != -1.0 && benchmark.period2RuntimeMap.get(periodKey) != -1.0) {
                    continue;
                }

                Path sampleDir = designDir.resolve(periodKey);
                if (!sampleDir.toFile().exists()) {
                    sampleDir.toFile().mkdirs();
                }
                
                designParams.setClkPeriod(benchmark.mainClkName, Double.parseDouble(periodKey));
                // prepare input design
                // Design design = Design.readCheckpoint(designParams.getInputDcpPath());
                // design.setAutoIOBuffers(false);

                // create tcl cmd file
                TclCmdFile tclCmdFile = createTclCmdFile(designParams);

                // create vivado project
                VivadoProject vivadoProject = new VivadoProject(sampleDir, tclCmdFile);
                Job vivadoJob = vivadoProject.createVivadoJob();
                jobQueue.addJob(vivadoJob);

                System.out.println("Start running PnR of " + benchmark.designName + " with period = " + periodKey);
                RuntimeTracker timer = new RuntimeTracker(benchmark.designName + "_" + periodKey, (short) 0);
                timer.start();
                boolean success = jobQueue.runAllToCompletion();
                timer.stop();

                // update database
                double runTime = (double) timer.getTime() * 1e-9;
                benchmark.period2RuntimeMap.put(periodKey, runTime);
                if (success) {
                    System.out.println(String.format("PnR of %s with period = %sn finished in %.2f seconds", benchmark.designName, periodKey, runTime));
                } else {
                    System.out.println("PnR of " + benchmark.designName + " with period = " + periodKey + " failed");
                }

                // parse slack
                Path slackFilePath = sampleDir.resolve("slack.txt");
                benchmark.period2SlackMap.put(periodKey, parseSlack(slackFilePath));
            }
        }
    }

    // private void setDesignConstraints(Design design, DesignParams designParams) {
    //     String pblockRange = designParams.getPblockRange("complete");
    //     assert pblockRange != null;
    //     //// add pblock constraints
    //     VivadoTclCmd.addStrictTopCellPblockConstr(design, pblockRange);
    //     //// add clock constraints
    //     Map<String, Double> clkName2PeriodMap = designParams.getClkPortName2PeriodMap();
    //     VivadoTclCmd.createClocks(design, clkName2PeriodMap);
    //     VivadoTclCmd.setAsyncClockGroupsForEachClk(design, clkName2PeriodMap.keySet());
    //     //// disable inserting IO buffers
    //     design.setAutoIOBuffers(false);
    // }

    private TclCmdFile createTclCmdFile(DesignParams designParams) {

        TclCmdFile tclCmdFile = new TclCmdFile();

        tclCmdFile.addCmd(VivadoTclCmd.setMaxThread(VivadoProject.MAX_THREAD));
        //tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(VivadoProject.INPUT_DCP_NAME));
        tclCmdFile.addCmd(VivadoTclCmd.openCheckpoint(designParams.getInputDcpPath().toString()));

        // set constraints
        String pblockRange = designParams.getPblockRange("complete");
        assert pblockRange != null;
        tclCmdFile.addCmds(VivadoTclCmd.addStrictTopCellPblockConstr(pblockRange));

        Map<String, Double> clk2PeriodMap = designParams.getClkPortName2PeriodMap();
        for (String clkName : designParams.getClkPortName2PeriodMap().keySet()) {
            tclCmdFile.addCmd(VivadoTclCmd.createClock(clkName, clk2PeriodMap.get(clkName)));
        }
        tclCmdFile.addCmd(VivadoTclCmd.setClockGroups(true, clk2PeriodMap.keySet()));

        // run PnR flow
        tclCmdFile.addCmd(VivadoTclCmd.placeDesign());
        tclCmdFile.addCmd(VivadoTclCmd.routeDesign());
        tclCmdFile.addCmd(VivadoTclCmd.reportTimingSummary(0, "timing.rpt"));
        tclCmdFile.addCmds(VivadoTclCmd.saveWorstSetupSlack("slack.txt"));
        tclCmdFile.addCmd(VivadoTclCmd.writeCheckpoint(true, null, VivadoProject.OUTPUT_DCP_NAME));

        return tclCmdFile;
    }

    private Double parseSlack(Path slackFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(slackFile.toString()))){
            return Double.parseDouble(reader.readLine());
        } catch (Exception e) {
            System.out.println("Fail to parse slack file: " + slackFile.toString());
            return null;
        }
    }

    public void save() {
        save(jsonFilePath);
    }

    public void save(Path filePath) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            gson.toJson(benchmarks, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void print() {
        System.out.println("Benchmark Database:");
        for (Benchmark benchmark : benchmarks.values()) {
            System.out.println(benchmark.designName + ": ");

            List<String> periodKeys = new ArrayList<>(benchmark.period2SlackMap.keySet());
            List<Double> periods = periodKeys.stream().map(Double::parseDouble).collect(Collectors.toList());
            Collections.sort(periods);
            
            for (Double period : periods) {
                String periodKey = Benchmark.toString(period);
                assert benchmark.period2SlackMap.containsKey(periodKey);
                assert benchmark.period2RuntimeMap.containsKey(periodKey);
                Double slack = benchmark.period2SlackMap.get(periodKey);
                Double runtime = benchmark.period2RuntimeMap.get(periodKey);
                System.out.println(String.format("\tperiod = %.2fns slack = %.3f runtime = %.3f", period, slack, runtime));
            }
        }
    }

    public static void main(String[] args) {
        Path jsonFilePath = Path.of("workspace/report", "max-freq-no-runtime.json");
        Boolean isParallel = true;

        TestMaxFrequency tester = new TestMaxFrequency(jsonFilePath);

        //tester.addBenchmark("spam-filter", 4.0, 4.0, 0.1, "ap_clk");
        //tester.addBenchmark("fireflyv2", 3.01, 2.9, 0.1, "clk");
        //tester.addBenchmark("boom", 8.61, 8.2, 0.2, "auto_tap_clock_in_clock");

        //tester.addBenchmark("minimap-small", 3.21, 3.1, 0.1, "ap_clk");
        // tester.addBenchmark("toooba", 4.2, 3.6, 0.2, "CLK");
        //tester.addBenchmark("tensil", 7.11, 6.9, 0.2, "clock");
        //tester.addBenchmark("ispd16-fpga04", 8.01, 7.6, 0.2, "clk1");

        // tester.addBenchmark("minimap-small", 4.21, 3.8, 0.4, "ap_clk");
        //tester.addBenchmark("toooba", 4.2, 3.6, 0.2, "CLK");
        // tester.addBenchmark("tensil", 7.4, 7.4, 0.2, "clock");
        // tester.addBenchmark("ispd16-fpga04", 9.5, 9.4, 0.2, "clk1");

        // tester.addBenchmark("toooba", 4.2, 3.6, 0.2, "clk");
        //tester.addBenchmark("ispd16-fpga04", 11.0, 10.0, 0.5, "clk1");
        //tester.addBenchmark("ispd16-fpga02", 3.8, 3.8, 0.1, "clk1");

        //tester.addBenchmark("blue-rdma", 4.0, 2.8, 0.1, "CLK");
        //tester.addBenchmark("nvdla-small", 4.5, 3.3, 0.1, "core_clk");
        //tester.addBenchmark("nvdla-small-256", 6.0, 4.5, 0.1, "core_clk");
        //tester.addBenchmark("nvdla-small-256-full", 5.9, 4.3, 0.1, "core_clk");
        //benchmarkDB.addBenchmark("corundum", 3.8, 3.0, 0.1, "main_clk");

        //tester.addBenchmark("hardcaml-ntt", 2.9, 2.6, 0.1, "ap_clk");

        //tester.addBenchmark("blue-rdma", 3.8, 3.7, 0.2, "CLK");
        //tester.addBenchmark("nvdla-small", 4.0, 3.9, 0.1, "core_clk");
        //tester.addBenchmark("nvdla-small-256", 5.4, 5.3, 0.2, "core_clk");
        //tester.addBenchmark("nvdla-small-256-full", 5.2, 5.1, 0.2, "core_clk");

        // tester.addBenchmark("corundum", 3.8, 3.0, 0.2, "main_clk");

        // tester.addBenchmark("supranational-ntt", 4.0, 2.3, 0.2, "clk_i");
        // tester.addBenchmark("hardcaml-ntt", 4.5, 3.5, 0.2, "ap_clk");

        //tester.addBenchmark("corundum", 4.0, 3.8, 0.2, "main_clk");
        //tester.addBenchmark("supranational-ntt", 2.2, 1.8, 0.4, "clk_i");
        //tester.addBenchmark("ntt-small", 1.9, 1.6, 0.1, "clk_i");
        //tester.addBenchmark("corundum", 3.6, 3.4, 0.1, "main_clk");
        
        if (isParallel) {
            tester.parallelUpdate();
        } else {
            tester.sequentialUpdate();
        }

        //System.out.println("Complete running PnR of all design samples");
        tester.print();
        // save experimental results to JSON files
        tester.save();
    }
}
