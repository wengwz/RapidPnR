package com.xilinx.rapidwright.rapidpnr.utils;

import java.util.Collection;
import java.util.function.Consumer;

import com.xilinx.rapidwright.util.Pair;

public class Coordinate2D extends Pair<Integer, Integer> {

    public Coordinate2D() {
        super(-1, -1);
    }

    public Coordinate2D(Coordinate2D loc) {
        super(loc.getX(), loc.getY());
    }
    
    public Coordinate2D(Integer x, Integer y) {
        super(x, y);
    }

    public Integer getX() {
        return getFirst();
    }

    public Integer getY() {
        return getSecond();
    }

    public void setX(int x) {
        setFirst(x);
    }

    public void setY(int y) {
        setSecond(y);
    }

    public static int getHPWL(Collection<Coordinate2D> locs) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Coordinate2D loc : locs) {
            minX = Math.min(minX, loc.getX());
            minY = Math.min(minY, loc.getY());
            maxX = Math.max(maxX, loc.getX());
            maxY = Math.max(maxY, loc.getY());
        }
        return (maxX - minX) + (maxY - minY);
    }

    public int getDistX(Coordinate2D other) {
        return Math.abs(getX() - other.getX());
    }

    public int getDistY(Coordinate2D other) {
        return Math.abs(getY() - other.getY());
    }

    public int getIdxOf(boolean xFirst, Coordinate2D loc) {
        assert loc.getX() < getX() && loc.getY() < getY();
        return xFirst ? loc.getY() * getX() + loc.getX() : loc.getX() * getY() + loc.getY();
    }

    public int getIdxOf(Coordinate2D loc) {
        return getIdxOf(false, loc);
    }

    public Coordinate2D getLocOf(boolean xFirst, int idx) {
        assert idx < getX() * getY();
        int x = xFirst ? idx % getX() : idx / getY();
        int y = xFirst ? idx / getX() : idx % getY();
        return Coordinate2D.of(x, y);
    }

    public Coordinate2D getLocOf(int idx) {
        return getLocOf(false, idx);
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
