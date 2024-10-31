package com.xilinx.rapidwright.rapidpnr;

public enum RapidPnRStep {
    READ_DESIGN,
    DATABASE_SETUP,
    NETLIST_ABSTRACTION,
    ISLAND_PLACEMENT,
    PHYSICAL_IMPLEMENTATION,
    WRITE_DESIGN;

    public static RapidPnRStep[] getOrderedSteps() {
        return new RapidPnRStep[] {
            READ_DESIGN, DATABASE_SETUP, NETLIST_ABSTRACTION, ISLAND_PLACEMENT, PHYSICAL_IMPLEMENTATION, WRITE_DESIGN
        };
    }

    public static RapidPnRStep getLastStep() {
        return WRITE_DESIGN;
    }
}
