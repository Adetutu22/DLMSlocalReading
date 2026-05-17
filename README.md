# Smart Meter Local Reading System (DLMS/COSEM)

## Overview

This project implements a fully local smart meter data acquisition and monitoring system using the DLMS/COSEM protocol stack over an RS-485 HAN (Home Area Network) interface. It enables direct, cloud-independent communication with smart electricity meters — decoding push-based metering data in real time and presenting it through a JavaFX GUI with live dashboards and historical analytics. 
The system was developed as a thesis project exploring energy transparency and protocol implementation at the application layer.

---

## Objectives

- Enable local access to smart meter data without cloud dependency  
- Implement DLMS/COSEM communication stack  
- Decode and interpret OBIS-coded energy data  
- Provide real-time monitoring and historical analytics  
- Support energy transparency and advanced analysis  

---

## Features

### Communication
- RS-485 (HAN interface) communication  
- DLMS/COSEM protocol with HDLC framing  
- Push-based data reception (periodic meter transmission)  

### Data Processing
- OBIS code parsing and mapping  
- DLMS APDU decoding  
- Structured data extraction from push messages  

### Data Storage
- SQLite database for local persistence  
- Timestamped measurement storage  
- Efficient query system for analytics  

### Visualization
- Real-time monitoring dashboard  
- Historical data analysis and visualization
- Charts for:
  - Energy consumption  
  - Power trends  
  - Phase-based measurements  

---

## Key Functionalities

### Real-Time Monitoring
- Instantaneous power (import/export)  
- Per-phase values (L1, L2, L3)  
- Active energy registers  
- Live DLMS push message decoding
- Meter connection status monitoring

### Historical Analysis
- Hourly and daily energy consumption tracking
- Time-based comparisons  
- Minimum and maximum value monitoring
- Configurable historical data views


### Advanced Analytics
- Peak load detection and tracking
- Phase imbalance detection  
- Abnormal consumption detection and analysis
- Photovoltaic (export energy) monitoring  

---

## System Architecture

**Smart Meter (DLMS Push)** → **RS-485 HAN Interface** → **USB-to-RS485 Adapter** → **Gurux DLMS Library** → **Data Processing (OBIS Parser)** → **SQLite Database** → **JavaFX GUI (Monitoring & Analytics)**

## Technologies Used

| Component      | Technology |
|---------------|-----------|
| Language      | Java |
| GUI           | JavaFX |
| Protocol      | DLMS/COSEM |
| Communication | RS-485 |
| Library       | Gurux DLMS |
| Database      | SQLite |
| Build Tool    | Maven |

---

## Hardware Requirements

- Smart Meter with HAN (RS-485) interface  
- USB-to-RS485 adapter  
- RJ-12 connection cable  
- Computer (Windows)  
