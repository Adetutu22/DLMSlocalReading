package com.meter;

import com.meter.gui.SmartMonitorApp;
import javafx.application.Application;

public class Main {
    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  SMART METER MONITOR");
        System.out.println("  DLMS Push Listener via RS485/HAN Interface");
        System.out.println("============================================================");
        System.out.println();
        System.out.println("  Supported meter: ST402D");
        System.out.println("  Connection: RS485 at 9600 baud, 8N1");
        System.out.println("  Push interval: 60 seconds");
        System.out.println("============================================================");
        System.out.println();

        // Launch the JavaFX GUI
        Application.launch(SmartMonitorApp.class, args);
    }
}
