/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.device.Node;

public enum RouteNodeType {
    EXCLUSIVE_SOURCE,
    EXCLUSIVE_SINK_BOTH,
    EXCLUSIVE_SINK_EAST,
    EXCLUSIVE_SINK_WEST,
    EXCLUSIVE_SINK_NON_LOCAL,

    /**
     * Denotes {@link RouteNode} objects that correspond to a super long line {@link Node},
     * i.e. nodes that span two SLRs.
     */
    SUPER_LONG_LINE,

    /**
     * Denotes {@link RouteNode} objects that correspond to {@link Node} objects that enter
     * a Laguna tile from an adjacent INT tile, or those Laguna tile nodes leading to a SUPER_LONG_LINE
     * going in the given direction
     */
    LOCAL_EAST_LEADING_TO_NORTHBOUND_LAGUNA,
    LOCAL_WEST_LEADING_TO_NORTHBOUND_LAGUNA,
    LOCAL_EAST_LEADING_TO_SOUTHBOUND_LAGUNA,
    LOCAL_WEST_LEADING_TO_SOUTHBOUND_LAGUNA,
    NON_LOCAL_LEADING_TO_NORTHBOUND_LAGUNA,
    NON_LOCAL_LEADING_TO_SOUTHBOUND_LAGUNA,

    NON_LOCAL,

    LOCAL_BOTH,
    LOCAL_EAST,
    LOCAL_WEST,

    LOCAL_RESERVED,

    /**
     * Denotes {@link RouteNode} objects that should be treated as being inaccessible and
     * never queued for exploration during routing. Typically, these are routing nodes that
     * have already been created but later discovered to not be needed (e.g. is a dead-end node).
     */
    INACCESSIBLE;

    public static final RouteNodeType[] values = values();

    public boolean isAnyExclusiveSink() {
        return this == EXCLUSIVE_SINK_BOTH || this == EXCLUSIVE_SINK_EAST || this == EXCLUSIVE_SINK_WEST || this == EXCLUSIVE_SINK_NON_LOCAL;
    }

    public static boolean isAnyExclusiveSink(int ordinal) {
        return ordinal == EXCLUSIVE_SINK_BOTH.ordinal() || ordinal == EXCLUSIVE_SINK_EAST.ordinal() || ordinal == EXCLUSIVE_SINK_WEST.ordinal() || ordinal == EXCLUSIVE_SINK_NON_LOCAL.ordinal();
    }

    public boolean isAnyLocal() {
        return this == LOCAL_BOTH || this == LOCAL_EAST || this == LOCAL_WEST || this == LOCAL_RESERVED ||
               this == LOCAL_EAST_LEADING_TO_NORTHBOUND_LAGUNA || this == LOCAL_EAST_LEADING_TO_SOUTHBOUND_LAGUNA ||
               this == LOCAL_WEST_LEADING_TO_NORTHBOUND_LAGUNA || this == LOCAL_WEST_LEADING_TO_SOUTHBOUND_LAGUNA;
    }

    public static boolean isAnyLocal(int ordinal) {
        return ordinal == LOCAL_BOTH.ordinal() || ordinal == LOCAL_EAST.ordinal() || ordinal == LOCAL_WEST.ordinal() || ordinal == LOCAL_RESERVED.ordinal() ||
               ordinal == LOCAL_EAST_LEADING_TO_NORTHBOUND_LAGUNA.ordinal() || ordinal == LOCAL_EAST_LEADING_TO_SOUTHBOUND_LAGUNA.ordinal() ||
               ordinal == LOCAL_WEST_LEADING_TO_NORTHBOUND_LAGUNA.ordinal() || ordinal == LOCAL_WEST_LEADING_TO_SOUTHBOUND_LAGUNA.ordinal();
    }

    public boolean isEastLocal() {
        return this == LOCAL_EAST || this == LOCAL_EAST_LEADING_TO_NORTHBOUND_LAGUNA || this == LOCAL_EAST_LEADING_TO_SOUTHBOUND_LAGUNA;
    }

    public boolean isWestLocal() {
        return this == LOCAL_WEST || this == LOCAL_WEST_LEADING_TO_NORTHBOUND_LAGUNA || this == LOCAL_WEST_LEADING_TO_SOUTHBOUND_LAGUNA;
    }

    public boolean isLocalLeadingToLaguna() {
        return this == LOCAL_EAST_LEADING_TO_NORTHBOUND_LAGUNA || this == LOCAL_EAST_LEADING_TO_SOUTHBOUND_LAGUNA ||
               this == LOCAL_WEST_LEADING_TO_NORTHBOUND_LAGUNA || this == LOCAL_WEST_LEADING_TO_SOUTHBOUND_LAGUNA;
    }

    public boolean leadsToLaguna() {
        return this == LOCAL_EAST_LEADING_TO_NORTHBOUND_LAGUNA || this == LOCAL_WEST_LEADING_TO_NORTHBOUND_LAGUNA || this == NON_LOCAL_LEADING_TO_NORTHBOUND_LAGUNA ||
               this == LOCAL_EAST_LEADING_TO_SOUTHBOUND_LAGUNA || this == LOCAL_WEST_LEADING_TO_SOUTHBOUND_LAGUNA || this == NON_LOCAL_LEADING_TO_SOUTHBOUND_LAGUNA;
    }

    public boolean leadsToNorthboundLaguna() {
        return this == LOCAL_EAST_LEADING_TO_NORTHBOUND_LAGUNA || this == LOCAL_WEST_LEADING_TO_NORTHBOUND_LAGUNA || this == NON_LOCAL_LEADING_TO_NORTHBOUND_LAGUNA;
    }

    public boolean leadsToSouthboundLaguna() {
        return this == LOCAL_EAST_LEADING_TO_SOUTHBOUND_LAGUNA || this == LOCAL_WEST_LEADING_TO_SOUTHBOUND_LAGUNA ||this == NON_LOCAL_LEADING_TO_SOUTHBOUND_LAGUNA;
    }
}
