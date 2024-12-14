package com.xilinx.rapidwright.rapidpnr.utils;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;

public class StatisticsUtils {
    public static Double getMean(List<Double> data) {
        Double sum = 0.0;
        for (Double d : data) {
            sum += d;
        }
        return sum / data.size();
    }

    public static List<Double> getMean(List<List<Double>> data, int dim) {
        assert dim == 0 || dim == 1;
        assert data.stream().mapToInt(List::size).distinct().count() == 1;

        if (dim == 0) {
            data = getTranspose(data);
        }

        List<Double> means = new ArrayList<>();

        for (List<Double> row : data) {
            means.add(getMean(row));
        }

        return means;
    }

    public static <T extends Comparable<? super T>> T getMax(List<T> data) {
        return Collections.max(data);
    }

    public static List<Double> getMax(List<List<Double>> data, int dim) {
        assert dim == 0 || dim == 1;
        assert data.stream().mapToInt(List::size).distinct().count() == 1;

        if (dim == 0) {
            data = getTranspose(data);
        }

        List<Double> maxes = new ArrayList<>();
        for (List<Double> row : data) {
            maxes.add(getMax(row));
        }

        return maxes;
    }

    public static List<List<Double>> getTranspose(List<List<Double>> data) {
        List<List<Double>> transposed = new ArrayList<>();
        assert data.stream().mapToInt(List::size).distinct().count() == 1;
        for (int i = 0; i < data.get(0).size(); i++) {
            List<Double> row = new ArrayList<>();
            for (int j = 0; j < data.size(); j++) {
                row.add(data.get(j).get(i));
            }
            transposed.add(row);
        }
        return transposed;
    }

    public static Double getImbalanceRatio(Double data, List<Double> dataVec) {
        Double mean = getMean(dataVec);
        return Math.abs(mean - data) / mean;
    }

    public static List<Double> getImbalanceRatio(List<Double> data, List<List<Double>> dataVec) {
        assert dataVec.stream().mapToInt(List::size).distinct().count() == 1;
        assert data.size() == dataVec.get(0).size();

        List<List<Double>> transposedDataVec = getTranspose(dataVec);
        List<Double> imbalanceRatios = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            imbalanceRatios.add(getImbalanceRatio(data.get(i), transposedDataVec.get(i)));
        }

        return imbalanceRatios;
    }

    public static Double getVariance(List<Double> data) {
        Double mean = getMean(data);
        Double totalVar = 0.0;
        for (Double d : data) {
            totalVar += (d - mean) * (d - mean);
        }
        return totalVar / data.size();
    }

    public static List<Double> getVariance(List<List<Double>> data, int dim) {
        assert dim == 0 || dim == 1;
        assert data.stream().mapToInt(List::size).distinct().count() == 1;

        if (dim == 0) {
            data = getTranspose(data);
        }

        List<Double> variances = new ArrayList<>();
        for (List<Double> row : data) {
            variances.add(getVariance(row));
        }

        return variances;
    }


    public static Double getStandardVariance(List<Double> data) {
        return Math.sqrt(getVariance(data));
    }

    public static List<Double> getStandardVariance(List<List<Double>> data, int dim) {
        assert dim == 0 || dim == 1;
        assert data.stream().mapToInt(List::size).distinct().count() == 1;

        if (dim == 0) {
            data = getTranspose(data);
        }

        List<Double> stdVariances = new ArrayList<>();
        for (List<Double> row : data) {
            stdVariances.add(getStandardVariance(row));
        }
        return stdVariances;
    }

    public static String getValueDistInfo(List<Double> values, int numBins) {
        String distInfo = "";
        Map<Double, Integer> value2Count = new HashMap<>();
        for (Double value : values) {
            value2Count.put(value, value2Count.getOrDefault(value, 0) + 1);
        }

        Double maxValue = Collections.max(value2Count.keySet());
        Double minValue = Collections.min(value2Count.keySet());
        Double totalValue = values.stream().reduce(0.0, Double::sum);

        Double binWidth = (maxValue - minValue) / numBins;
        List<Integer> binCounts = new ArrayList<>(Collections.nCopies(numBins, 0));
        List<Double> binSizes = new ArrayList<>(Collections.nCopies(numBins, 0.0));

        for (Double value : value2Count.keySet()) {
            Double total = value2Count.get(value) * value;
            int binIdx = (int) ((value - minValue) / binWidth);
            if (binIdx == numBins) {
                binIdx = numBins - 1;
            }
            
            binCounts.set(binIdx, binCounts.get(binIdx) + value2Count.get(value));
            binSizes.set(binIdx, binSizes.get(binIdx) + total);
        }

        for (int binIdx = 0; binIdx < numBins; binIdx++) {
            Double lowerBound = minValue + binIdx * binWidth;
            Double upperBound = minValue + (binIdx + 1) * binWidth;
            if (upperBound > maxValue) {
                upperBound = maxValue;
            }

            Double ratio = binSizes.get(binIdx) / totalValue * 100;
            distInfo += String.format("%.2f ~ %.2f: %d (%.2f%%)", lowerBound, upperBound, binCounts.get(binIdx), ratio);
            if (binIdx != numBins - 1) {
                distInfo += "\n";
            }
        }
        return distInfo;
    }

    public static String getBasicValueDistInfo(List<Double> data) {
        Double avg = getMean(data);
        Double min = Collections.min(data);
        Double max = Collections.max(data);
        Double stdVar = getStandardVariance(data);
        return String.format(" Min=%.2f Max=%.2f Avg=%.2f StdVar=%.2f", min, max, avg, stdVar);
    }
}
