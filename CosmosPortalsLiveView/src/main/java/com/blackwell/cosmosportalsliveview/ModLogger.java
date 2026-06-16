package com.blackwell.cosmosportalsliveview;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ModLogger {
    
    private static final String LOG_FILE_NAME = "cosmosportalsliveview.log";
    private static File logFile;
    private static PrintWriter writer;
    
    static {
        try {
            File configDir = new File("./config");
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            logFile = new File(configDir, LOG_FILE_NAME);
            writer = new PrintWriter(new FileWriter(logFile, true));
            logInfo("=== CosmosPortalsLiveView Log Started ===");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static void logInfo(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        String logMessage = "[" + timestamp + "] [INFO] " + message;
        System.out.println(logMessage);
        if (writer != null) {
            writer.println(logMessage);
            writer.flush();
        }
    }
    
    public static void logError(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        String logMessage = "[" + timestamp + "] [ERROR] " + message;
        System.err.println(logMessage);
        if (writer != null) {
            writer.println(logMessage);
            writer.flush();
        }
    }
    
    public static void logWarn(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        String logMessage = "[" + timestamp + "] [WARN] " + message;
        System.out.println(logMessage);
        if (writer != null) {
            writer.println(logMessage);
            writer.flush();
        }
    }
    
    public static void logException(String message, Exception e) {
        logError(message + ": " + e.getMessage());
        if (writer != null) {
            e.printStackTrace(writer);
            writer.flush();
        }
    }
    
    public static void close() {
        if (writer != null) {
            logInfo("=== CosmosPortalsLiveView Log Closed ===");
            writer.close();
        }
    }
}
