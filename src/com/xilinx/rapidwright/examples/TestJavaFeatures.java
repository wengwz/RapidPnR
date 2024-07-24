package com.xilinx.rapidwright.examples;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.kenai.jffi.Array;

public class TestJavaFeatures {
    public static void main(String[] args) {
        Integer a1 = 1;
        Integer a2 = 2;
        Integer a3 = 3;

        Integer b1 = 1;
        Integer b2 = 2;
        Integer b4 = 4;
        Integer b5 = 5;

        Set<Integer> set1 = new HashSet<>();
        set1.add(a1);
        set1.add(a2);
        set1.add(a3);
        set1.add(b1);
        set1.add(b2);
        set1.add(b4);
        set1.add(b5);

        Set<Integer> set2 = new HashSet<>();
        set2.add(b1);
        set2.add(b2);
        set2.add(b4);
        set2.add(b5);


        for (Integer i : set1) {
            System.out.println(i);
        }

        System.out.println("----");
        Set<Integer> set3 = new HashSet<>(Arrays.asList(1, 2, 3, 4, 5));

        for (Integer i : set3) {
            System.out.println(i);
        }

        System.out.println("----");
        if (set1.equals(set3)) {
            System.out.println("Equal");
        } else {
            System.out.println("Not Equal");
        }
    }
}
