package com.xilinx.rapidwright.rapidpnr.utils;

import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.Handler;

public class HierarchicalLogger {
    public static class CustomFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getLevel() + ": " + record.getMessage() + "\n";
        }
    }

    private Logger logger;
    private int logHierDepth = 0;

    public HierarchicalLogger(String name) {
        logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
    }

    public void setLevel(Level level) {
        logger.setLevel(level);
    }

    public void setUseParentHandlers(boolean useParentHandlers) {
        logger.setUseParentHandlers(useParentHandlers);
    }

    public void addHandler(Handler handler) {
        logger.addHandler(handler);
    }

    public void log(Level level, String msg) {
        if (logHierDepth > 0) {
            msg = "#".repeat(logHierDepth) + " " + msg;
        }
        logger.log(level, msg);
    }

    public void severe(String msg) {
        this.log(Level.SEVERE, msg);
    }
  
    public void warning(String msg) {
       this.log(Level.WARNING, msg);
    }

    public void info(String msg) {
       this.log(Level.INFO, msg);
    }

    public void info(String msg, boolean autoIndent) {
        if (autoIndent) {
            int idx = msg.indexOf('\n');
            if (idx != -1 && idx != msg.length() - 1) {
                String firstLine = msg.substring(0, idx + 1);
                String rest = msg.substring(idx + 1);
                int identNum = 7 + logHierDepth;

                msg = firstLine + insertAtHeadOfEachLine(" ".repeat(identNum), rest);
            }
        }
        this.log(Level.INFO, msg);
    }

    public void config(String msg) {
       this.log(Level.CONFIG, msg);
    }

    public void fine(String msg) {
       this.log(Level.FINE, msg);
    }

    public void finer(String msg) {
       this.log(Level.FINER, msg);
    }

    public void finest(String msg) {
       this.log(Level.FINEST, msg);
    }

    public void newSubStep() {
        logHierDepth++;
    }

    public void endSubStep() {
        if (logHierDepth > 0) {
            logHierDepth--;
        }
    }

    public void logHeader(Level level, String headerName) {
        int headerLen = 80;
        int headerNameLen = headerName.length();
        String separatorStr = "=".repeat(headerLen);
        int frontBlankSpace = (headerLen - 4 - headerNameLen) / 2;
        int backBlankSpace = headerLen - 4 - headerNameLen - frontBlankSpace;
        String nameStr = String.format("==" + " ".repeat(frontBlankSpace) + headerName + " ".repeat(backBlankSpace) + "==");
        log(level, "");
        log(level, separatorStr);
        log(level, nameStr);
        log(level, separatorStr);
    }

    public void infoHeader(String name) {
        logHeader(Level.INFO, name);
    }

    public static HierarchicalLogger createLogger(String logName, Path logFilePath, Boolean enableConsle, Level level) {
        HierarchicalLogger logger = new HierarchicalLogger(logName);
        logger.setUseParentHandlers(false);

        // Setup Logger
        try {
            if (logFilePath != null) {
                FileHandler fileHandler = new FileHandler(logFilePath.toString(), false);
                fileHandler.setFormatter(new CustomFormatter());
                logger.addHandler(fileHandler);
            }

            if (enableConsle) {
                ConsoleHandler consoleHandler = new ConsoleHandler();
                consoleHandler.setFormatter(new CustomFormatter());
                logger.addHandler(consoleHandler);
            }

        } catch (Exception e) {
            System.out.println("Fail to open log file: " + logFilePath.toString());
        }
        logger.setLevel(level);

        return logger;
    }

    public static HierarchicalLogger createLogger(String logName, Path logFilePath, Boolean enableConsle) {
        return createLogger(logName, logFilePath, enableConsle, Level.INFO);
    }
    public static HierarchicalLogger createPseduoLogger(String logName) {
        return createLogger(logName, null, false, Level.INFO);
    }

    public static String insertAtHeadOfEachLine(String head, String logInfo) {
        String newLogInfo = "";
        int start = 0;
        int end = 0;
        for (end = 0; end < logInfo.length(); end++) {
            char ch = logInfo.charAt(end);
            if (ch =='\n') {
                newLogInfo += head + logInfo.substring(start, end + 1);
                start = end + 1;
            }
        }

        newLogInfo += head + logInfo.substring(start, end);

        return newLogInfo;
    }

    public static void main(String[] args) {
        String logInfo = "12345\n234588\n99998\n77778";
        String newLogInfo = insertAtHeadOfEachLine("  ", logInfo);

        System.out.println(logInfo);
        System.out.println(newLogInfo);
    }
}
