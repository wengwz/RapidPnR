package com.xilinx.rapidwright.examples;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class IOConstraints {
    public static final HashMap<String, List<Integer>> udpConstraints = new HashMap<String, List<Integer>>() {
        {
            put("s_data_stream_tfirst", Arrays.asList(0,1));
            put("m_data_stream_tfirst", Arrays.asList(0,1));
            
            put("s_data_stream_tkeep[31:0]", Arrays.asList(0,1));
            put("m_data_stream_tkeep[31:0]", Arrays.asList(0,1));
            
            put("s_data_stream_tlast", Arrays.asList(0,1));
            put("m_data_stream_tlast", Arrays.asList(0,1));

            put("s_data_stream_tdata[255:0]", Arrays.asList(0,1));
            put("m_data_stream_tdata[255:0]", Arrays.asList(0,1));

            put("s_data_stream_tvalid", Arrays.asList(0,1));
            put("m_data_stream_tvalid", Arrays.asList(0,1));

            put("s_data_stream_tready", Arrays.asList(0,1));
            put("m_data_stream_tready", Arrays.asList(0,1));
            
            put("s_axis_tvalid", Arrays.asList(1,0));
            put("m_axis_tvalid", Arrays.asList(1,0));

            put("s_axis_tready", Arrays.asList(1,0));
            put("m_axis_tready", Arrays.asList(1,0));

            put("s_axis_tkeep[31:0]", Arrays.asList(1,0));
            put("m_axis_tkeep[31:0]", Arrays.asList(1,0));

            put("s_axis_tdata[255:0]", Arrays.asList(1,0));
            put("m_axis_tdata[255:0]", Arrays.asList(1,0));

            put("s_axis_tlast", Arrays.asList(1,0));
            put("m_axis_tlast", Arrays.asList(1,0));

            put("s_axis_tuser", Arrays.asList(1,0));
            put("m_axis_tuser", Arrays.asList(1,0));

        }
    };

    public static final HashMap<String, List<Integer>> rdmaConstraints = new HashMap<String, List<Integer>>() {
        {
            put("axiStreamTxOutUdp", Arrays.asList(0,2));
            put("axiStreamRxInUdp", Arrays.asList(0,2));
            put("dmaReadClt", Arrays.asList(2,0));
            put("dmaWriteClt", Arrays.asList(2,0));
        }
    };
}
