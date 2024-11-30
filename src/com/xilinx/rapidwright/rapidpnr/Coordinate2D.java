package com.xilinx.rapidwright.rapidpnr;

import java.util.function.Consumer;

import com.xilinx.rapidwright.util.Pair;

public class Coordinate2D extends Pair<Integer, Integer> {
    
    public Coordinate2D(Integer x, Integer y) {
        super(x, y);
    }

    public Integer getX() {
        return getFirst();
    }

    public Integer getY() {
        return getSecond();
    }

    public int getDistX(Coordinate2D other) {
        return Math.abs(getX() - other.getX());
    }

    public int getDistY(Coordinate2D other) {
        return Math.abs(getY() - other.getY());
    }

    public int getManhattanDist(Coordinate2D other) {
        return getDistX(other) + getDistY(other);
    }

    public static Coordinate2D of(int x, int y) {
        return new Coordinate2D(x, y);
    }

    public void traverse(Consumer<Coordinate2D> operation) {
        for (int x = 0; x < getX(); x++) {
            for (int y = 0; y < getY(); y++) {
                operation.accept(Coordinate2D.of(x, y));
            }
        }
    }

}
