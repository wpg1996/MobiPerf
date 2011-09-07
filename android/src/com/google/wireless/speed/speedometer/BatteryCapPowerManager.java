// Copyright 2011 Google Inc. All Rights Reserved.

package com.google.wireless.speed.speedometer;

import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.concurrent.Callable;

/**
 * A basic power manager implementation that decides whether a measurement can be scheduled
 * based on the current battery level: no measurements will be scheduled if the current battery
 * is lower than a threshold.
 * 
 * @author wenjiezeng@google.com (Steve Zeng)
 *
 */
public class BatteryCapPowerManager {
  /** The minimum threshold below which no measurements will be scheduled */
  private int minBatteryThreshold;
  private boolean measureWhenCharging;
    
  public BatteryCapPowerManager(int batteryThresh, boolean measureWhenCharging, Context context) {
    this.minBatteryThreshold = batteryThresh;
    this.measureWhenCharging = measureWhenCharging;
  }
  
  /** 
   * Sets the minimum battery percentage below which measurements cannot be run.
   * 
   * @param batteryThresh the battery percentage threshold between 0 and 100
   */
  public synchronized void setBatteryThresh(int batteryThresh) throws IllegalArgumentException {
    if (batteryThresh < 0 || batteryThresh > 100) {
      throw new IllegalArgumentException("batteryCap must fall between 0 and 100, inclusive");
    }
    this.minBatteryThreshold = batteryThresh;
  }
  
  public synchronized int getBatteryThresh() {
    return this.minBatteryThreshold;
  }
  
  public synchronized void setMeasureWhenCharging(boolean value) {
    measureWhenCharging = value;
  }
  
  /** 
   * Returns whether a measurement can be run.
   */
  public synchronized boolean canScheduleExperiment() {
    return ((measureWhenCharging && PhoneUtils.getPhoneUtils().isCharging()) || 
        PhoneUtils.getPhoneUtils().getCurrentBatteryLevel() > minBatteryThreshold);
  }
  
  /**
   * A task wrapper that is power aware, the real logic is carried out by realTask
   * 
   * @author wenjiezeng@google.com (Steve Zeng)
   *
   */
  public static class PowerAwareTask implements Callable<MeasurementResult> {
    
    private MeasurementTask realTask;
    private BatteryCapPowerManager pManager;
    private MeasurementScheduler scheduler;
    
    public PowerAwareTask(MeasurementTask task, BatteryCapPowerManager manager, 
                          MeasurementScheduler scheduler) {
      realTask = task;
      pManager = manager;
      this.scheduler = scheduler;
    }
    
    private void broadcastMeasurementStart() {
      Intent intent = new Intent();
      intent.setAction(UpdateIntent.SYSTEM_STATUS_UPDATE_ACTION);
      intent.putExtra(UpdateIntent.STATUS_MSG_PAYLOAD, "System measurement " + 
          realTask.getDescriptor() + " is running.");
      
      scheduler.sendBroadcast(intent);
    }
    
    private void broadcastMeasurementEnd(MeasurementResult result) {
      Intent intent = new Intent();
      intent.setAction(UpdateIntent.MEASUREMENT_PROGRESS_UPDATE_ACTION);
      intent.putExtra(UpdateIntent.TASK_PRIORITY_PAYLOAD, (int) realTask.getDescription().priority);
      // A progress value MEASUREMENT_END_PROGRESS indicates the end of an measurement
      intent.putExtra(UpdateIntent.PROGRESS_PAYLOAD, Config.MEASUREMENT_END_PROGRESS);
      if (result != null) {
        intent.putExtra(UpdateIntent.STRING_PAYLOAD, result.toString());
      } else {
        String errorString = "Measurement " + realTask.getDescriptor() + " has failed. ";
        /* If the measurement fails because we are below battery threshold or because the
         * scheduler is paused, we print some extra information 
         * */
        if (!pManager.canScheduleExperiment()) {
          errorString += "It failed because battery levle is below setting threashold.";
        } else if (scheduler.isPauseRequested()) {
          errorString += "It failed because Speedometer is paused.";
        }
        errorString += "\n\nTimestamp: " + Calendar.getInstance().getTime();
        intent.putExtra(UpdateIntent.STRING_PAYLOAD, errorString);
      }
      
      scheduler.sendBroadcast(intent);
      
      intent.setAction(UpdateIntent.SYSTEM_STATUS_UPDATE_ACTION);
      intent.putExtra(UpdateIntent.STATUS_MSG_PAYLOAD, "Speedometer is running.");
      
      scheduler.sendBroadcast(intent);
    }
    
    private void broadcastPowerThreasholdReached() {
      Intent intent = new Intent();
      intent.setAction(UpdateIntent.SYSTEM_STATUS_UPDATE_ACTION);
      // A progress value MEASUREMENT_END_PROGRESS indicates the end of an measurement
      intent.putExtra(UpdateIntent.STATUS_MSG_PAYLOAD, 
          scheduler.getString(R.string.powerThreasholdReachedMsg));
      
      scheduler.sendBroadcast(intent);
    }
    
    @Override
    public MeasurementResult call() throws MeasurementError {
      MeasurementResult result = null;
      try {
        PhoneUtils.getPhoneUtils().acquireWakeLock();
        if (scheduler.isPauseRequested()) {
          throw new MeasurementError("Scheduler is paused.");
        }
        if (!pManager.canScheduleExperiment()) {
          broadcastPowerThreasholdReached();
          throw new MeasurementError("Not enough power");
        }
        scheduler.setCurrentTask(realTask);
        broadcastMeasurementStart();
        result = realTask.call(); 
        return result;
      } finally {
        PhoneUtils.getPhoneUtils().releaseWakeLock();
        scheduler.setCurrentTask(null);
        broadcastMeasurementEnd(result);
      }
    }
  }
}
