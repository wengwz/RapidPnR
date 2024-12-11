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

    public static HierarchicalLogger createLogger(String logName, Path logFilePath, Boolean enableConsle) {
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
        logger.setLevel(Level.INFO);

        return logger;
    }

    public static HierarchicalLogger createPseduoLogger(String logName) {
        return createLogger(logName, null, false);
    }
}
