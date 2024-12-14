package com.xilinx.rapidwright.rapidpnr.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class SortedList<T extends Comparable<T>> {

    private int len;
    private List<T> index2Value;
    private List<Integer> sortedIndices;
    private List<Integer> index2Rank;

    private Comparator<Integer> idxCmp = (t1, t2) -> index2Value.get(t1).compareTo(index2Value.get(t2));

    public SortedList(List<T> values, Boolean reverse) {
        this.index2Value = values;
        this.len = values.size();

        sortedIndices = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            sortedIndices.add(i);
        }

        if (reverse) {
            idxCmp = idxCmp.reversed();
        }

        sortedIndices.sort(idxCmp);

        index2Rank = new ArrayList<>(Collections.nCopies(len, 0));
        for (int rank = 0; rank < sortedIndices.size(); rank++) {
            index2Rank.set(sortedIndices.get(rank), rank);
        }
    }

    public void update(int idx, T value) {
        assert idx >=0 && idx < len;
        index2Value.set(idx, value);

        int rank = index2Rank.get(idx);

        while (rank > 0 && idxCmp.compare(idx, getIndexOfRank(rank - 1)) < 0) {
            swapSortedIndices(idx, getIndexOfRank(rank - 1));
            rank--;
        }


        while (rank < len - 1 && idxCmp.compare(idx, getIndexOfRank(rank + 1)) > 0) {
            swapSortedIndices(idx, getIndexOfRank(rank + 1));
            rank++;
        }
    }

    private void swapSortedIndices(int idx1, int idx2) {
        int rank1 = index2Rank.get(idx1);
        int rank2 = index2Rank.get(idx2);

        sortedIndices.set(rank1, idx2);
        sortedIndices.set(rank2, idx1);

        index2Rank.set(idx1, rank2);
        index2Rank.set(idx2, rank1);
    }

    public List<Integer> getSortedIndices() {
        return Collections.unmodifiableList(sortedIndices);
    }

    public List<T> getSortedValues() {
        List<T> sortedValues = new ArrayList<>();
        for (int idx : sortedIndices) {
            sortedValues.add(index2Value.get(idx));
        }
        return sortedValues;
    }

    public int getRankOfIndex(int idx) {
        return index2Rank.get(idx);
    }

    public int getIndexOfRank(int rank) {
        return sortedIndices.get(rank);
    }

    public T getValueOf(int idx) {
        return index2Value.get(idx);
    }

    public int size() {
        return len;
    }
    
    public static List<Integer> genRandomIntegers(int len) {
        List<Integer> randomIntegers = new ArrayList<>();
        Random random = new Random();
        for (int i = 0; i < len; i++) {
            randomIntegers.add(random.nextInt());
        }
        return randomIntegers;
    }

    public static void main(String[] args) {
        int a = 2;
        int b = 3;
        System.out.println(String.format("a: %d b: %d cmp: %d", a, b, Integer.compare(a, b)));

        int len = 10000;
        List<Integer> randomIntegers = genRandomIntegers(len);
        SortedList<Integer> sortedList = new SortedList<>(new ArrayList<>(randomIntegers), true);

        List<Integer> refSortedValues = randomIntegers.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList());

        List<Integer> sortedValues = sortedList.getSortedValues();
        for (int i = 0; i < sortedValues.size(); i++) {
            System.out.println("Index: " + i + " Value: " + sortedValues.get(i) + " Value: " + refSortedValues.get(i));
            assert sortedValues.get(i).equals(refSortedValues.get(i)): "Sorted values are not correct";
        }
        assert refSortedValues.equals(sortedValues): "Sorted values are not correct";


        // update random list
        for (int i = 0; i < len; i++) {
            boolean randomBool = new Random().nextBoolean();
            if (randomBool) {
                Integer randomValue = new Random().nextInt();
                sortedList.update(i, randomValue);
                randomIntegers.set(i, randomValue);
            }
        }

        refSortedValues = randomIntegers.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList());

        sortedValues = sortedList.getSortedValues();
        for (int i = 0; i < sortedValues.size(); i++) {
            System.out.println("Index: " + i + " Value: " + sortedValues.get(i) + " Value: " + refSortedValues.get(i));
            assert sortedValues.get(i).equals(refSortedValues.get(i)): "Sorted values are not correct";
        }


    }
}
