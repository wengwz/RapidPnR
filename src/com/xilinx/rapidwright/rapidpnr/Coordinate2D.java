package com.xilinx.rapidwright.rapidpnr;

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


}
