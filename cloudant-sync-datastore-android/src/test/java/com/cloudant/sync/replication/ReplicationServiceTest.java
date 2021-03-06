package com.cloudant.sync.replication;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.test.ServiceTestCase;

import com.cloudant.android.TestReplicationService;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReplicationServiceTest extends ServiceTestCase<TestReplicationService> {

    private static final long ALARM_TOLERANCE_MS = 500;
    private static final int DEFAULT_WAIT_SECONDS = 5;
    private Context mMockContext;
    private SharedPreferences mMockPreferences;
    private SharedPreferences.Editor mMockPreferencesEditor;
    private AlarmManager mMockAlarmManager;
    private WifiManager mMockWifiManager;
    private WifiManager.WifiLock mMockWifiLock;
    private Replicator[] mMockReplicators;

    ReplicationPolicyManager mMockReplicationPolicyManager;
    private TestReplicationService mService;

    public ReplicationServiceTest() {
        super(TestReplicationService.class);
    }

    @BeforeClass
    public void setUp() {
        mMockContext = mock(Context.class);
        mMockPreferences = mock(SharedPreferences.class);
        when(mMockContext.getSharedPreferences("com.cloudant.preferences", Context.MODE_PRIVATE)).thenReturn(mMockPreferences);
        when(mMockContext.getPackageName()).thenReturn("cloudant.com.androidtest");
        when(mMockContext.getDir(anyString(), anyInt())).thenReturn(new File("/data/data/cloudant.com.androidtest/files"));
        when(mMockContext.getApplicationContext()).thenReturn(mMockContext);
        mMockPreferencesEditor = mock(SharedPreferences.Editor.class);
        when(mMockPreferences.edit()).thenReturn(mMockPreferencesEditor);
        mMockAlarmManager = mock(AlarmManager.class);
        mMockWifiManager = mock(WifiManager.class);
        mMockWifiLock = mock(WifiManager.WifiLock.class);
        mMockReplicationPolicyManager = mock(ReplicationPolicyManager.class);
        mMockReplicators = new Replicator[]{mock(Replicator.class)};
    }

    @Test
    public void testServiceStart() {
        Intent intent = new Intent(getContext(), TestReplicationService.class);
        startService(intent);
    }

    @Test
    public void testServiceBind() {
        Intent intent = new Intent(getContext(), TestReplicationService.class);
        bindService(intent);
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * the device has been rebooted, if the value for the next alarm scheduled in SharedPreferences
     * is in the past, then the SharedPreferences are updated to indicate that the next
     * alarm should be triggered immediately.
     */
    @Test
    public void testOnStartCommandRebootImmediateAlarm() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent intent = new Intent(mMockContext, TestReplicationService.class);
        intent.putExtra(ReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_DEVICE_REBOOTED);
        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(1);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                assertEquals("Unexpected command received",
                    PeriodicReplicationService.COMMAND_DEVICE_REBOOTED,
                    operationId);
                latch.countDown();
            }
        });

        ArgumentCaptor<String> captorPrefKeys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> captorPrefValues = ArgumentCaptor.forClass(Long.class);
        service.onStartCommand(intent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
            verify(mMockPreferencesEditor, times(2)).putLong(captorPrefKeys.capture(), captorPrefValues.capture());
            List<String> prefsKeys = captorPrefKeys.getAllValues();
            List<Long> prefsValues = captorPrefValues.getAllValues();
            assertEquals("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueElapsed", prefsKeys.get(0));
            assertTrue("Alarm elapsed time not within " + ALARM_TOLERANCE_MS + "ms of current time",
                Math.abs(SystemClock.elapsedRealtime() - prefsValues.get(0)) < ALARM_TOLERANCE_MS);
            assertEquals("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueClock", prefsKeys.get(1));
            assertTrue("Alarm clock time not within " + ALARM_TOLERANCE_MS + "ms of current time",
                Math.abs(System.currentTimeMillis() - prefsValues.get(1)) < ALARM_TOLERANCE_MS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * the device has been rebooted, if the next alarm scheduled in SharedPreferences is within
     * the alarm period of the current time, the alarm scheduled in SharedPreferences is unchanged.
     */
    @Test
    public void testOnStartCommandRebootDelayedAlarm() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent intent = new Intent(mMockContext, TestReplicationService.class);
        intent.putExtra(ReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_DEVICE_REBOOTED);
        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(1);

        long timeReturned = System.currentTimeMillis() + ((1000 * service.getUnboundIntervalInSeconds()) / 2);
        when(mMockPreferences.getLong("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueClock", 0)).thenReturn(timeReturned);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                assertEquals("Unexpected command received",
                    PeriodicReplicationService.COMMAND_DEVICE_REBOOTED,
                    operationId);
                latch.countDown();
            }
        });

        ArgumentCaptor<String> captorPrefKeys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> captorPrefValues = ArgumentCaptor.forClass(Long.class);
        service.onStartCommand(intent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
            verify(mMockPreferencesEditor, never()).putLong(captorPrefKeys.capture(), captorPrefValues.capture());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * the device has been rebooted, if the next alarm scheduled in SharedPreferences is scheduled
     * more than the alarm period in the future, the next alarm is scheduled one alarm period from
     * the current time.
     */
    @Test
    public void testOnStartCommandRebootLateAlarm() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent intent = new Intent(mMockContext, TestReplicationService.class);
        intent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_DEVICE_REBOOTED);
        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(1);

        long timeReturned = System.currentTimeMillis() + ((1000 * service.getUnboundIntervalInSeconds()) * 10);
        when(mMockPreferences.getLong("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueClock", 0)).thenReturn(timeReturned);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                assertEquals("Unexpected command received",
                    PeriodicReplicationService.COMMAND_DEVICE_REBOOTED,
                    operationId);
                latch.countDown();
            }
        });

        ArgumentCaptor<String> captorPrefKeys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> captorPrefValues = ArgumentCaptor.forClass(Long.class);
        service.onStartCommand(intent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
            verify(mMockPreferencesEditor, times(2)).putLong(captorPrefKeys.capture(), captorPrefValues.capture());
            List<String> prefsKeys = captorPrefKeys.getAllValues();
            List<Long> prefsValues = captorPrefValues.getAllValues();
            assertEquals("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueElapsed", prefsKeys.get(0));
            long expectedElapsedTime = SystemClock.elapsedRealtime() + (1000 * service.getUnboundIntervalInSeconds());
            long expectedRealTime = System.currentTimeMillis() + (1000 * service.getUnboundIntervalInSeconds());
            assertTrue("Alarm elapsed time not within " + ALARM_TOLERANCE_MS + "ms of expected time",
                Math.abs(expectedElapsedTime - prefsValues.get(0)) < ALARM_TOLERANCE_MS);
            assertEquals("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueClock", prefsKeys.get(1));
            assertTrue("Alarm clock time not within " + ALARM_TOLERANCE_MS + "ms of expected time",
                Math.abs(expectedRealTime - prefsValues.get(1)) < ALARM_TOLERANCE_MS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * periodic replications should be started, the {@link AlarmManager}, is setup to fire
     * at the correct time and with the correct frequency.
     */
    @Test
    public void testOnStartCommandStartPeriodicReplications() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent intent = new Intent(mMockContext, TestReplicationService.class);
        intent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_START_PERIODIC_REPLICATION);
        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(1);

        long timeReturned = SystemClock.elapsedRealtime();
        when(mMockPreferences.getBoolean("com.cloudant.sync.replication.PeriodicReplicationService.periodicReplicationsActive", false)).thenReturn(false);
        when(mMockContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mMockAlarmManager);
        when(mMockPreferences.getLong("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueElapsed", 0)).thenReturn(timeReturned);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                assertEquals("Unexpected command received",
                    PeriodicReplicationService.COMMAND_START_PERIODIC_REPLICATION,
                    operationId);
                latch.countDown();
            }
        });

        service.onStartCommand(intent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
            ArgumentCaptor<String> captorPrefKeys = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Boolean> captorPrefValues = ArgumentCaptor.forClass(Boolean.class);
            verify(mMockPreferencesEditor, times(1)).putBoolean(captorPrefKeys.capture(), captorPrefValues.capture());
            String prefsKey = captorPrefKeys.getValue();
            Boolean prefsValue = captorPrefValues.getValue();
            assertEquals("com.cloudant.sync.replication.PeriodicReplicationService.periodicReplicationsActive", prefsKey);
            assertTrue("Alarm manager should be set in running state", prefsValue);


            ArgumentCaptor<Integer> captorType = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Long> captorTriggerAtMillis = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> captorIntervalMillis = ArgumentCaptor.forClass(Long.class);

            verify(mMockAlarmManager, times(1)).setInexactRepeating(captorType.capture(), captorTriggerAtMillis.capture(), captorIntervalMillis.capture(), Mockito.any(PendingIntent.class));
            assertEquals("Incorrect alarm type", AlarmManager.ELAPSED_REALTIME_WAKEUP, (int) captorType.getValue());
            assertEquals("Incorrect initial trigger time", timeReturned, (long) captorTriggerAtMillis.getValue());
            assertEquals("Incorrect alarm period", service.getUnboundIntervalInSeconds() * 1000, (long) captorIntervalMillis.getValue());

            // Unfortunately, we can't do much testing of the PendingIntent itself as there aren't
            // methods to extract anything useful.
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * periodic replications should be started, if they have already been started, the
     * {@link AlarmManager} is not invoked to restart the periodic replications.
     */
    @Test
    public void testOnStartCommandStartPeriodicReplicationsAlreadyStarted() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent intent = new Intent(mMockContext, TestReplicationService.class);
        intent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_START_PERIODIC_REPLICATION);
        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(1);

        when(mMockPreferences.getBoolean("com.cloudant.sync.replication.PeriodicReplicationService.periodicReplicationsActive", false)).thenReturn(true);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                assertEquals("Unexpected command received",
                    PeriodicReplicationService.COMMAND_START_PERIODIC_REPLICATION,
                    operationId);
                latch.countDown();
            }
        });

        service.onStartCommand(intent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
            verify(mMockAlarmManager, never()).setInexactRepeating(Mockito.anyInt(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(PendingIntent.class));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * periodic replications should be stopped, the {@link AlarmManager}, is cancelled.
     */
    @Test
    public void testOnStartCommandStopPeriodicReplications() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent intent = new Intent(mMockContext, TestReplicationService.class);
        intent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_STOP_PERIODIC_REPLICATION);
        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(1);

        long timeReturned = SystemClock.elapsedRealtime();
        when(mMockPreferences.getBoolean("com.cloudant.sync.replication.PeriodicReplicationService.periodicReplicationsActive", false)).thenReturn(true);
        when(mMockContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mMockAlarmManager);
        when(mMockPreferences.getLong("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueElapsed", 0)).thenReturn(timeReturned);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                assertEquals("Unexpected command received",
                    PeriodicReplicationService.COMMAND_STOP_PERIODIC_REPLICATION,
                    operationId);
                latch.countDown();
            }
        });

        service.onStartCommand(intent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
            ArgumentCaptor<String> captorPrefKeys = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Boolean> captorPrefValues = ArgumentCaptor.forClass(Boolean.class);
            verify(mMockPreferencesEditor, times(1)).putBoolean(captorPrefKeys.capture(), captorPrefValues.capture());
            String prefsKey = captorPrefKeys.getValue();
            Boolean prefsValue = captorPrefValues.getValue();
            assertEquals("com.cloudant.sync.replication.PeriodicReplicationService.periodicReplicationsActive", prefsKey);
            assertFalse("Alarm manager should be set in stopped state", prefsValue);

            verify(mMockAlarmManager, times(1)).cancel(Mockito.any(PendingIntent.class));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * periodic replications should be stopped, if they have already been stopped, the
     * {@link AlarmManager} is not cancelled again.
     */
    @Test
    public void testOnStartCommandStopPeriodicReplicationsAlreadyStopped() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent intent = new Intent(mMockContext, TestReplicationService.class);
        intent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_STOP_PERIODIC_REPLICATION);
        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(1);

        long timeReturned = SystemClock.elapsedRealtime();
        when(mMockPreferences.getBoolean("com.cloudant.sync.replication.PeriodicReplicationService.periodicReplicationsActive", false)).thenReturn(false);
        when(mMockContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mMockAlarmManager);
        when(mMockPreferences.getLong("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueElapsed", 0)).thenReturn(timeReturned);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                assertEquals("Unexpected command received",
                    PeriodicReplicationService.COMMAND_STOP_PERIODIC_REPLICATION,
                    operationId);
                latch.countDown();
            }
        });

        service.onStartCommand(intent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
            verify(mMockAlarmManager, never()).cancel(Mockito.any(PendingIntent.class));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * a replication should be started, a {@link android.net.wifi.WifiManager.WifiLock} is acquired,
     * the {@link ReplicationPolicyManager} is started and the next alarm time is stored in
     * SharedPreferences.
     */
    @Test
    public void testOnStartCommandStartReplication() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent intent = new Intent(mMockContext, TestReplicationService.class);
        intent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_START_REPLICATION);
        service.setReplicationPolicyManager(mMockReplicationPolicyManager);

        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        when(mMockWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ReplicationService")).thenReturn(mMockWifiLock);

        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(1);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                assertEquals("Unexpected command received",
                    PeriodicReplicationService.COMMAND_START_REPLICATION,
                    operationId);
                latch.countDown();
            }
        });

        service.onStartCommand(intent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
            verify(mMockWifiLock, times(1)).acquire();
            verify(mMockReplicationPolicyManager, times(1)).startReplications();
            ArgumentCaptor<String> captorPrefKeys = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> captorPrefValues = ArgumentCaptor.forClass(Long.class);
            verify(mMockPreferencesEditor, times(2)).putLong(captorPrefKeys.capture(), captorPrefValues.capture());
            List<String> prefsKeys = captorPrefKeys.getAllValues();
            List<Long> prefsValues = captorPrefValues.getAllValues();
            assertEquals("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueElapsed", prefsKeys.get(0));
            assertTrue("Alarm elapsed time not within " + ALARM_TOLERANCE_MS + "ms of current time",
                Math.abs(SystemClock.elapsedRealtime() + (service.getUnboundIntervalInSeconds() * 1000) - prefsValues.get(0)) < ALARM_TOLERANCE_MS);
            assertEquals("com.cloudant.sync.replication.PeriodicReplicationService.alarmDueClock", prefsKeys.get(1));
            assertTrue("Alarm clock time not within " + ALARM_TOLERANCE_MS + "ms of current time",
                Math.abs(System.currentTimeMillis() + (service.getUnboundIntervalInSeconds() * 1000) - prefsValues.get(1)) < ALARM_TOLERANCE_MS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * a replication should be stopped, the {@link android.net.wifi.WifiManager.WifiLock} is
     * released and the {@link ReplicationPolicyManager} is stopped.
     */
    @Test
    public void testOnStartCommandStopReplication() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        service.setReplicationPolicyManager(mMockReplicationPolicyManager);

        when(mMockContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mMockWifiManager);
        when(mMockWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ReplicationService")).thenReturn(mMockWifiLock);
        when(mMockWifiLock.isHeld()).thenReturn(true);

        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(2);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                if (operationId == PeriodicReplicationService.COMMAND_START_REPLICATION && latch.getCount() == 2) {
                    latch.countDown();
                } else if (operationId == PeriodicReplicationService.COMMAND_STOP_REPLICATION && latch.getCount() == 1) {
                    latch.countDown();
                } else {
                    fail("Unexpected command received");
                }
            }
        });

        Intent startIntent = new Intent(mMockContext, TestReplicationService.class);
        startIntent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_START_REPLICATION);
        service.onStartCommand(startIntent, 0, 0);
        Intent stopIntent = new Intent(mMockContext, TestReplicationService.class);
        stopIntent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_STOP_REPLICATION);
        service.onStartCommand(stopIntent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
            verify(mMockWifiLock, times(1)).release();
            verify(mMockReplicationPolicyManager, times(1)).stopReplications();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when an intent is sent to the {@link PeriodicReplicationService} indicating that
     * a replication should be started, the {@link PeriodicReplicationService} waits until
     * {@link ReplicationService#setReplicators(Replicator[])} is called before processing
     * the start replication operation.
     */
    @Test
    public void testOnStartCommandStartReplicationWaitsForSetReplicators() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent intent = new Intent(mMockContext, TestReplicationService.class);
        intent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_START_REPLICATION);
        service.setReplicationPolicyManager(mMockReplicationPolicyManager);

        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(1);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                assertEquals("Unexpected command received",
                    PeriodicReplicationService.COMMAND_START_REPLICATION,
                    operationId);
                latch.countDown();
            }
        });

        service.onStartCommand(intent, 0, 0);
        try {
            // Wait a bit to check the OperationStartedListener isn't called.
            latch.await(1000, TimeUnit.MILLISECONDS);
            assertEquals("CountDownLatch should not be decremented", latch.getCount(), 1);
            service.setReplicators(mMockReplicators);
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when intents are sent to the {@link PeriodicReplicationService}
     * the commands are queued until {@link ReplicationService#setReplicators(Replicator[])} is
     * called and the queued messages are then processed.
     */
    @Test
    public void testOnStartCommandStartReplicationCommandsQueuedBeforeSetReplicators() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent startIntent = new Intent(mMockContext, TestReplicationService.class);
        startIntent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_START_REPLICATION);

        Intent stopIntent = new Intent(mMockContext, TestReplicationService.class);
        stopIntent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService
            .COMMAND_STOP_REPLICATION);

        service.setReplicationPolicyManager(mMockReplicationPolicyManager);

        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(2);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                if (latch.getCount() == 2 && operationId == PeriodicReplicationService
                    .COMMAND_START_REPLICATION) {
                    latch.countDown();
                } else if (latch.getCount() == 1 && operationId == PeriodicReplicationService
                    .COMMAND_STOP_REPLICATION) {
                    latch.countDown();
                } else {
                    fail("Unexpected command or commands received in incorrect order");
                }
            }
        });

        service.onStartCommand(startIntent, 0, 0);
        service.onStartCommand(stopIntent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when intents are sent to the {@link PeriodicReplicationService}, consecutive
     * duplicate commands are ignored and commands are queued until
     * {@link ReplicationService#setReplicators(Replicator[])}
     * is called and the queued messages are then processed.
     */
    @Test
    public void testOnStartCommandStartReplicationCommandsQueuedConsecutiveDuplicatesRemoved() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        Intent startIntent = new Intent(mMockContext, TestReplicationService.class);
        startIntent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService.COMMAND_START_REPLICATION);

        Intent stopIntent = new Intent(mMockContext, TestReplicationService.class);
        stopIntent.putExtra(PeriodicReplicationService.EXTRA_COMMAND, PeriodicReplicationService
            .COMMAND_STOP_REPLICATION);

        service.setReplicationPolicyManager(mMockReplicationPolicyManager);

        service.onCreate();
        final CountDownLatch latch = new CountDownLatch(2);

        service.setOperationStartedListener(new PeriodicReplicationService
            .OperationStartedListener() {
            @Override
            public void operationStarted(int operationId) {
                if (latch.getCount() == 2 && operationId == PeriodicReplicationService
                    .COMMAND_START_REPLICATION) {
                    latch.countDown();
                } else if (latch.getCount() == 1 && operationId == PeriodicReplicationService
                    .COMMAND_STOP_REPLICATION) {
                    latch.countDown();
                } else {
                    fail("Unexpected command or commands received in incorrect order");
                }
            }
        });

        service.onStartCommand(startIntent, 0, 0);
        service.onStartCommand(startIntent, 0, 0);
        service.onStartCommand(stopIntent, 0, 0);
        service.setReplicators(mMockReplicators);
        try {
            assertTrue("The countdown should reach zero", latch.await(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check that when {@link PeriodicReplicationService#setReplicators(Replicator[])}, is called
     * with a null argument, {@link IllegalArgumentException} is thrown.
     */
    @Test
    public void testSetReplicatorsNull() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        try {
            service.setReplicators(null);
            fail("IllegalArgumentException should have been thrown");
        } catch (IllegalArgumentException e) {
            // Success.
        }
    }

    /**
     * Check that when {@link PeriodicReplicationService#setReplicators(Replicator[])}, is called
     * with an empty array, {@link IllegalArgumentException} is thrown.
     */
    @Test
    public void testSetReplicatorsEmpty() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        try {
            service.setReplicators(new Replicator[]{});
            fail("IllegalArgumentException should have been thrown");
        } catch (IllegalArgumentException e) {
            // Success.
        }
    }

    /**
     * Check that when {@link PeriodicReplicationService#setReplicators(Replicator[])}, is called
     * multiple times, {@link IllegalStateException} is thrown.
     */
    @Test
    public void testSetReplicatorsMultipleInvocations() {
        PeriodicReplicationService service = new TestReplicationService(mMockContext);
        service.setReplicators(mMockReplicators);
        try {
            service.setReplicators(mMockReplicators);
            fail("IllegalStateException should have been thrown");
        } catch (IllegalStateException e) {
            // Success.
        }
    }

}
