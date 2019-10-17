/*  Copyright (C) 2018-2019 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti, maxirnilian, Sebastian Kranz

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.watchxplus;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;

import androidx.annotation.IntRange;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.watchxplus.WatchXPlusConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.CalendarEventSpec;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.CannedMessagesSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicSpec;
import nodomain.freeyourgadget.gadgetbridge.model.MusicStateSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;
import nodomain.freeyourgadget.gadgetbridge.service.devices.watchxplus.operations.InitOperation;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;
import nodomain.freeyourgadget.gadgetbridge.util.ArrayUtils;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class WatchXPlusDeviceSupport extends AbstractBTLEDeviceSupport {

    private boolean needsAuth;
    private int sequenceNumber = 0;
    private boolean isCalibrationActive = false;

    private byte ACK_CALIBRATION = 0;

    private final GBDeviceEventVersionInfo versionInfo = new GBDeviceEventVersionInfo();
    private final GBDeviceEventBatteryInfo batteryInfo = new GBDeviceEventBatteryInfo();

    private static final Logger LOG = LoggerFactory.getLogger(WatchXPlusDeviceSupport.class);

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String broadcastAction = intent.getAction();
            switch (broadcastAction) {
                case WatchXPlusConstants.ACTION_CALIBRATION:
                    enableCalibration(intent.getBooleanExtra(WatchXPlusConstants.ACTION_ENABLE, false));
                    break;
                case WatchXPlusConstants.ACTION_CALIBRATION_SEND:
                    int hour = intent.getIntExtra(WatchXPlusConstants.VALUE_CALIBRATION_HOUR, -1);
                    int minute = intent.getIntExtra(WatchXPlusConstants.VALUE_CALIBRATION_MINUTE, -1);
                    int second = intent.getIntExtra(WatchXPlusConstants.VALUE_CALIBRATION_SECOND, -1);
                    if (hour != -1 && minute != -1 && second != -1) {
                        sendCalibrationData(hour, minute, second);
                    }
                    break;
                case WatchXPlusConstants.ACTION_CALIBRATION_HOLD:
                    holdCalibration();
                    break;
            }
        }
    };

    public WatchXPlusDeviceSupport() {
        super(LOG);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ACCESS);
        addSupportedService(GattService.UUID_SERVICE_GENERIC_ATTRIBUTE);
        addSupportedService(WatchXPlusConstants.UUID_SERVICE_WATCHXPLUS);

        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(getContext());
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WatchXPlusConstants.ACTION_CALIBRATION);
        intentFilter.addAction(WatchXPlusConstants.ACTION_CALIBRATION_SEND);
        intentFilter.addAction(WatchXPlusConstants.ACTION_CALIBRATION_HOLD);
        broadcastManager.registerReceiver(broadcastReceiver, intentFilter);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        try {
            boolean auth = needsAuth;
            needsAuth = false;
            new InitOperation(auth, this, builder).perform();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return builder;
    }

    @Override
    public boolean connectFirstTime() {
        needsAuth = true;
        return super.connect();
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public void onNotification(NotificationSpec notificationSpec) {

        String senderOrTitle = StringUtils.getFirstOf(notificationSpec.sender, notificationSpec.title);

        String message = StringUtils.truncate(senderOrTitle, 32) + "\0";
//        TODO: Commented out to simplify testing
//        if (notificationSpec.subject != null) {
//            message += StringUtils.truncate(notificationSpec.subject, 128) + "\n\n";
//        }
//        if (notificationSpec.body != null) {
//            message += StringUtils.truncate(notificationSpec.body, 128);
//        }

        sendNotification(WatchXPlusConstants.NOTIFICATION_CHANNEL_DEFAULT, message);
    }

    private void sendNotification(int notificationChannel, String notificationText) {
        try {
            TransactionBuilder builder = performInitialized("showNotification");
            byte[] command = WatchXPlusConstants.CMD_NOTIFICATION_TEXT_TASK;
            byte[] text = notificationText.getBytes("UTF-8");
            byte[] value = new byte[text.length + 2];
            value[0] = (byte)(notificationChannel);
//            TODO: Split message into 9-byte arrays and send them one by one.
//             Set the message index to FF to indicate end of message
            value[1] = (byte) 0xFF;
            System.arraycopy(text, 0, value, 2, text.length);

            builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(command,
                            WatchXPlusConstants.KEEP_ALIVE,
                            value));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to send notification", e);
        }
    }

    private WatchXPlusDeviceSupport enableNotificationChannels(TransactionBuilder builder) {
        builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(WatchXPlusConstants.CMD_NOTIFICATION_SETTINGS,
                        WatchXPlusConstants.WRITE_VALUE,
                        new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));

        return this;
    }

    public WatchXPlusDeviceSupport authorizationRequest(TransactionBuilder builder, boolean firstConnect) {
        builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(WatchXPlusConstants.CMD_AUTHORIZATION_TASK,
                        WatchXPlusConstants.TASK,
                        new byte[]{(byte) (firstConnect ? 0x00 : 0x01)})); //possibly not the correct meaning

        return this;
    }

    private WatchXPlusDeviceSupport enableDoNotDisturb(TransactionBuilder builder, boolean active) {
        builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(WatchXPlusConstants.CMD_DO_NOT_DISTURB_SETTINGS,
                        WatchXPlusConstants.WRITE_VALUE,
                        new byte[]{(byte) (active ? 0x01 : 0x00)}));

        return this;
    }

    private void enableCalibration(boolean enable) {
        try {
            TransactionBuilder builder = performInitialized("enableCalibration");
            builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(WatchXPlusConstants.CMD_CALIBRATION_INIT_TASK,
                            WatchXPlusConstants.TASK,
                            new byte[]{(byte) (enable ? 0x01 : 0x00)}));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to start/stop calibration mode", e);
        }
    }

    private void holdCalibration() {
        try {
            TransactionBuilder builder = performInitialized("holdCalibration");
            builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(WatchXPlusConstants.CMD_CALIBRATION_KEEP_ALIVE,
                            WatchXPlusConstants.KEEP_ALIVE));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to keep calibration mode alive", e);
        }
    }

    private void sendCalibrationData(@IntRange(from=0,to=23)int hour, @IntRange(from=0,to=59)int minute, @IntRange(from=0,to=59)int second) {
        try {
            isCalibrationActive = true;
            TransactionBuilder builder = performInitialized("calibrate");
            int handsPosition = ((hour % 12) * 60 + minute) * 60 + second;
            builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(WatchXPlusConstants.CMD_CALIBRATION_TASK,
                            WatchXPlusConstants.TASK,
                            Conversion.toByteArr16(handsPosition)));
            performImmediately(builder);
        } catch (IOException e) {
            isCalibrationActive = false;
            LOG.warn("Unable to send calibration data", e);
        }
    }

    private void getTime() {
        try {
            TransactionBuilder builder = performInitialized("getTime");
            builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(WatchXPlusConstants.CMD_TIME_SETTINGS,
                            WatchXPlusConstants.READ_VALUE));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to get device time", e);
        }
    }

    private void handleTime(byte[] time) {
        GregorianCalendar now = BLETypeConversions.createCalendar();
        GregorianCalendar nowDevice = BLETypeConversions.createCalendar();
        int year = (nowDevice.get(Calendar.YEAR) / 100) * 100 + Conversion.fromBcd8(time[8]);
        nowDevice.set(year,
                Conversion.fromBcd8(time[9]) - 1,
                Conversion.fromBcd8(time[10]),
                Conversion.fromBcd8(time[11]),
                Conversion.fromBcd8(time[12]),
                Conversion.fromBcd8(time[13]));
        nowDevice.set(Calendar.DAY_OF_WEEK, Conversion.fromBcd8(time[16]) + 1);

        long timeDiff = (Math.abs(now.getTimeInMillis() - nowDevice.getTimeInMillis())) / 1000;
        if (10 < timeDiff && timeDiff < 120) {
            enableCalibration(true);
            setTime(BLETypeConversions.createCalendar());
            enableCalibration(false);
        }
    }

    private void setTime(Calendar calendar) {
        try {
            TransactionBuilder builder = performInitialized("setTime");
            int timezoneOffsetMinutes = (calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)) / (60 * 1000);
            int timezoneOffsetIndustrialMinutes = Math.round((Math.abs(timezoneOffsetMinutes) % 60) * 100f / 60f);
            byte[] time = new byte[]{Conversion.toBcd8(calendar.get(Calendar.YEAR) % 100),
                    Conversion.toBcd8(calendar.get(Calendar.MONTH) + 1),
                    Conversion.toBcd8(calendar.get(Calendar.DAY_OF_MONTH)),
                    Conversion.toBcd8(calendar.get(Calendar.HOUR_OF_DAY)),
                    Conversion.toBcd8(calendar.get(Calendar.MINUTE)),
                    Conversion.toBcd8(calendar.get(Calendar.SECOND)),
                    (byte) (timezoneOffsetMinutes / 60),
                    (byte) timezoneOffsetIndustrialMinutes,
                    (byte) (calendar.get(Calendar.DAY_OF_WEEK) - 1)
            };
            builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(WatchXPlusConstants.CMD_TIME_SETTINGS,
                            WatchXPlusConstants.WRITE_VALUE,
                            time));
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to set time", e);
        }
    }

    public WatchXPlusDeviceSupport getFirmwareVersion(TransactionBuilder builder) {
        builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(WatchXPlusConstants.CMD_FIRMWARE_INFO,
                        WatchXPlusConstants.READ_VALUE));

        return this;
    }

    private WatchXPlusDeviceSupport getBatteryState(TransactionBuilder builder) {
        builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(WatchXPlusConstants.CMD_BATTERY_INFO,
                        WatchXPlusConstants.READ_VALUE));

        return this;
    }

    private WatchXPlusDeviceSupport setFitnessGoal(TransactionBuilder builder) {
        int fitnessGoal = new ActivityUser().getStepsGoal();
        builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                buildCommand(WatchXPlusConstants.CMD_FITNESS_GOAL_SETTINGS,
                        WatchXPlusConstants.WRITE_VALUE,
                        Conversion.toByteArr16(fitnessGoal)));

        return this;
    }

    public WatchXPlusDeviceSupport initialize(TransactionBuilder builder) {
        getFirmwareVersion(builder)
                .getBatteryState(builder)
                .enableNotificationChannels(builder)
                .enableDoNotDisturb(builder, false)
                .setFitnessGoal(builder);
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));
        builder.setGattCallback(this);

        return this;
    }

    @Override
    public void onDeleteNotification(int id) {

    }

    @Override
    public void onSetTime() {
        getTime();
    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {
        try {
            TransactionBuilder builder = performInitialized("setAlarms");
            for (Alarm alarm : alarms) {
                setAlarm(alarm, alarm.getPosition() + 1, builder);
            }
            builder.queue(getQueue());
        } catch (IOException e) {
            LOG.warn("Unable to set alarms", e);
        }
    }

    // No useful use case at the moment, used to clear alarm slots for testing.
    private void deleteAlarm(TransactionBuilder builder, int index) {
        if (0 < index && index < 4) {
            byte[] alarmValue = new byte[]{(byte) index, 0x00, 0x00, 0x00, 0x00, 0x00};
            builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(WatchXPlusConstants.CMD_ALARM_SETTINGS,
                            WatchXPlusConstants.WRITE_VALUE,
                            alarmValue));
        }
    }

    private void setAlarm(Alarm alarm, int index, TransactionBuilder builder) {
        // Shift the GB internal repetition mask to match the device specific one.
        byte repetitionMask = (byte) ((alarm.getRepetition() << 1) | (alarm.isRepetitive() ? 0x80 : 0x00));
        repetitionMask |= (alarm.getRepetition(Alarm.ALARM_SUN) ? 0x01 : 0x00);
        if (0 < index && index < 4) {
            byte[] alarmValue = new byte[]{(byte) index,
                    Conversion.toBcd8(AlarmUtils.toCalendar(alarm).get(Calendar.HOUR_OF_DAY)),
                    Conversion.toBcd8(AlarmUtils.toCalendar(alarm).get(Calendar.MINUTE)),
                    repetitionMask,
                    (byte) (alarm.getEnabled() ? 0x01 : 0x00),
                    0x00 // TODO: Unknown
            };
            builder.write(getCharacteristic(WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE),
                    buildCommand(WatchXPlusConstants.CMD_ALARM_SETTINGS,
                            WatchXPlusConstants.WRITE_VALUE,
                            alarmValue));
        }
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        switch (callSpec.command) {
            case CallSpec.CALL_INCOMING:
                sendNotification(WatchXPlusConstants.NOTIFICATION_CHANNEL_PHONE_CALL, callSpec.name);
                break;
            case CallSpec.CALL_START:
            case CallSpec.CALL_END:
//                sendNotification(WatchXPlusConstants.NOTIFICATION_CHANNEL_PHONE_CALL, true);
//                break;
            default:
                break;
        }
    }

    @Override
    public void onSetCannedMessages(CannedMessagesSpec cannedMessagesSpec) {

    }

    @Override
    public void onSetMusicState(MusicStateSpec stateSpec) {

    }

    @Override
    public void onSetMusicInfo(MusicSpec musicSpec) {

    }

    @Override
    public void onEnableRealtimeSteps(boolean enable) {

    }

    @Override
    public void onInstallApp(Uri uri) {

    }

    @Override
    public void onAppInfoReq() {

    }

    @Override
    public void onAppStart(UUID uuid, boolean start) {

    }

    @Override
    public void onAppDelete(UUID uuid) {

    }

    @Override
    public void onAppConfiguration(UUID appUuid, String config, Integer id) {

    }

    @Override
    public void onAppReorder(UUID[] uuids) {

    }

    @Override
    public void onFetchRecordedData(int dataTypes) {

    }

    @Override
    public void onReset(int flags) {

    }

    @Override
    public void onHeartRateTest() {

    }

    @Override
    public void onEnableRealtimeHeartRateMeasurement(boolean enable) {

    }

    @Override
    public void onFindDevice(boolean start) {

    }

    @Override
    public void onSetConstantVibration(int integer) {

    }

    @Override
    public void onScreenshotReq() {

    }

    @Override
    public void onEnableHeartRateSleepSupport(boolean enable) {

    }

    @Override
    public void onSetHeartRateMeasurementInterval(int seconds) {

    }

    @Override
    public void onAddCalendarEvent(CalendarEventSpec calendarEventSpec) {

    }

    @Override
    public void onDeleteCalendarEvent(byte type, long id) {

    }

    @Override
    public void onSendConfiguration(String config) {
        TransactionBuilder builder;
        try {
            builder = performInitialized("sendConfig: " + config);
            switch (config) {
                case ActivityUser.PREF_USER_STEPS_GOAL:
                    setFitnessGoal(builder);
                    break;
            }
            builder.queue(getQueue());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onReadConfiguration(String config) {

    }

    @Override
    public void onTestNewFunction() {

    }

    @Override
    public void onSendWeather(WeatherSpec weatherSpec) {

    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);

        UUID characteristicUUID = characteristic.getUuid();
        if (WatchXPlusConstants.UUID_CHARACTERISTIC_WRITE.equals(characteristicUUID)) {
            byte[] value = characteristic.getValue();
            if (ArrayUtils.equals(value, WatchXPlusConstants.RESP_FIRMWARE_INFO, 5)) {
                handleFirmwareInfo(value);
            } else if (ArrayUtils.equals(value, WatchXPlusConstants.RESP_BATTERY_INFO, 5)) {
                handleBatteryState(value);
            } else if (ArrayUtils.equals(value, WatchXPlusConstants.RESP_TIME_SETTINGS, 5)) {
                handleTime(value);
            } else if (ArrayUtils.equals(value, WatchXPlusConstants.RESP_BUTTON_INDICATOR, 5)) {
                LOG.info("Unhandled action: Button pressed");
            } else if (ArrayUtils.equals(value, WatchXPlusConstants.RESP_ALARM_INDICATOR, 5)) {
                LOG.info("Alarm active: id=" + value[8]);
            } else if (isCalibrationActive && value.length == 7 && value[4] == ACK_CALIBRATION) {
                setTime(BLETypeConversions.createCalendar());
                isCalibrationActive = false;
            }

            return true;
        } else {
            LOG.info("Unhandled characteristic changed: " + characteristicUUID);
            logMessageContent(characteristic.getValue());
        }

        return false;
    }

    private byte[] buildCommand(byte[] command, byte action) {
        return buildCommand(command, action, null);
    }

    private byte[] buildCommand(byte[] command, byte action, byte[] value) {
        if (Arrays.equals(command, WatchXPlusConstants.CMD_CALIBRATION_TASK)) {
            ACK_CALIBRATION = (byte) sequenceNumber;
        }
        command = BLETypeConversions.join(command, value);
        byte[] result = new byte[7 + command.length];
        System.arraycopy(WatchXPlusConstants.CMD_HEADER, 0, result, 0, 5);
        System.arraycopy(command, 0, result, 6, command.length);
        result[2] = (byte) (command.length + 1);
        result[3] = WatchXPlusConstants.REQUEST;
        result[4] = (byte) sequenceNumber++;
        result[5] = action;
        result[result.length - 1] = calculateChecksum(result);

        return result;
    }

    private byte calculateChecksum(byte[] bytes) {
        byte checksum = 0x00;
        for (int i = 0; i < bytes.length - 1; i++) {
            checksum += (bytes[i] ^ i) & 0xFF;
        }
        return (byte) (checksum & 0xFF);
    }

    private void handleFirmwareInfo(byte[] value) {
        versionInfo.fwVersion = String.format(Locale.US,"%d.%d.%d", value[8], value[9], value[10]);
        handleGBDeviceEvent(versionInfo);
    }

    private void handleBatteryState(byte[] value) {
        batteryInfo.state = value[8] == 1 ? BatteryState.BATTERY_NORMAL : BatteryState.BATTERY_LOW;
        batteryInfo.level = value[9];
        handleGBDeviceEvent(batteryInfo);
    }

    @Override
    public void dispose() {
        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(getContext());
        broadcastManager.unregisterReceiver(broadcastReceiver);
        super.dispose();
    }

    private static class Conversion {
        static byte toBcd8(@IntRange(from = 0, to = 99) int value) {
            int high = (value / 10) << 4;
            int low = value % 10;
            return (byte) (high | low);
        }

        static int fromBcd8(byte value) {
            int high = ((value & 0xF0) >> 4) * 10;
            int low = value & 0x0F;
            return high + low;
        }

        static byte[] toByteArr16(int value) {
            return new byte[]{(byte) (value >> 8), (byte) value};
        }

        static byte[] toByteArr32(int value) {
            return new byte[]{(byte) (value >> 24),
                    (byte) (value >> 16),
                    (byte) (value >> 8),
                    (byte) value};
        }
    }
}
