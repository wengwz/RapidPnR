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


    public static final HashMap<String, List<Integer>> gnlMidConstraints = new HashMap<String, List<Integer>>() {
        {
            put("ip[39:0]", Arrays.asList(0,0));
            put("ip[79:40]", Arrays.asList(0,1));
            put("op[39:0]", Arrays.asList(1,0));
            put("op[79:40]", Arrays.asList(1,1));
        }
    };
    public static final HashMap<String, List<Integer>> gnlMidConstraints2 = new HashMap<String, List<Integer>>() {
        {
            put("ip[19:0]", Arrays.asList(0,0));
            put("ip[39:20]", Arrays.asList(0,1));
            put("ip[79:40]", Arrays.asList(0,2));
            put("op[19:0]", Arrays.asList(2,0));
            put("op[39:20]", Arrays.asList(2,1));
            put("op[79:40]", Arrays.asList(2,2));
        }
    };

    public static final HashMap<String, List<Integer>> gnlSmallConstraints = new HashMap<String, List<Integer>>() {
        {
            put("ip[37:0]", Arrays.asList(0,0));
            put("ip[76:38]", Arrays.asList(0,1));
            put("op[40:0]", Arrays.asList(2,0));
            put("op[81:40]", Arrays.asList(2,1));
        }
    };

    public static final HashMap<String, List<Integer>> fftConstraints= new HashMap<String, List<Integer>>() {
        {
            put("i_sample[5:0]", Arrays.asList(0,0));
            put("i_sample[11:6]", Arrays.asList(0,1));
            put("o_result[8:0]", Arrays.asList(1,0));
            put("o_result[17:9]", Arrays.asList(1,1));
        }
    };
}
