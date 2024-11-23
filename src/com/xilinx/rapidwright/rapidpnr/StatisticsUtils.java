package com.xilinx.rapidwright.rapidpnr;

import java.util.List;
import java.util.ArrayList;

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
}
