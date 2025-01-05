package com.xilinx.rapidwright.rapidpnr.utils;

import java.util.ArrayList;
import java.util.List;

public class VecOps {
    public static boolean lessEq(List<Double> vec1, List<Double> vec2) {
        assert vec1.size() == vec2.size();
        for (int i = 0; i < vec1.size(); i++) {
            if (vec1.get(i) > vec2.get(i)) {
                return false;                
            }
        }
        return true;
    }

    public static List<Double> add(List<Double> vec1, List<Double> vec2) {
        assert vec1.size() == vec2.size();
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < vec1.size(); i++) {
            result.add(vec1.get(i) + vec2.get(i));
        }
        return result;
    }

    public static void accu(List<Double> vec1, List<Double> vec2) {
        assert vec1.size() == vec2.size();
        for (int i = 0; i < vec1.size(); i++) {
            vec1.set(i, vec1.get(i) + vec2.get(i));
        }
    }

    public static List<Double> mulScalar(List<Double> vec, double scalar) {
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < vec.size(); i++) {
            result.add(vec.get(i) * scalar);
        }
        return result;
    }

    public static List<Double> getMax(List<Double> vec1, List<Double> vec2) {
        assert vec1.size() == vec2.size();
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < vec1.size(); i++) {
            result.add(Math.max(vec1.get(i), vec2.get(i)));
        }
        return result;
    }

}
