// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.host.realtime;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking Unity facade for Android Node's explicit Binder service. All IPC executes on one
 * HandlerThread; Unity callers only read defensive copies of compact cached payloads.
 */
public final class AndroidPerceptionBridge {
    private static final int WORLD_MAGIC = 0x43465753; // CFWS
    private static final int FOCUS_MAGIC = 0x43464653; // CFFS
    private static final int HEAD_POSE_MAGIC = 0x43464850; // CFHP
    private static final int TOUCH_MAGIC = 0x43465442; // CFTB
    private static final int PAYLOAD_VERSION = 1;
    private static final int TOUCH_HEADER_BYTES = 8;
    private static final int TOUCH_EVENT_BYTES = 36;
    private static final int MAXIMUM_TOUCH_EVENTS = 128;

    private static final Client CLIENT = new Client();

    private AndroidPerceptionBridge() {}

    public static void initialize() {
        CLIENT.startIfPossible();
    }

    public static long elapsedRealtimeNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }

    public static void shutdown() {
        CLIENT.stop();
    }

    public static byte[] pollWorldState(long lastRevision) {
        CLIENT.startIfPossible();
        return CLIENT.snapshotAfter(
                CLIENT.world, CLIENT.worldEpoch, WORLD_MAGIC, lastRevision);
    }

    public static byte[] pollFocusState(long lastRevision) {
        CLIENT.startIfPossible();
        return CLIENT.snapshotAfter(
                CLIENT.focus, CLIENT.focusEpoch, FOCUS_MAGIC, lastRevision);
    }

    public static byte[] pollHeadPose(long lastSequence) {
        CLIENT.startIfPossible();
        return CLIENT.snapshotAfter(
                CLIENT.headPose, CLIENT.headPoseEpoch, HEAD_POSE_MAGIC, lastSequence);
    }

    public static byte[] drainTouchEvents(int maximumEvents) {
        CLIENT.startIfPossible();
        if (maximumEvents < 1 || maximumEvents > MAXIMUM_TOUCH_EVENTS) return null;
        byte[] result = CLIENT.takeTouchBatch(maximumEvents);
        CLIENT.requestTouchDrain(maximumEvents);
        return result;
    }

    private static final class Client {
        private static final String HOST_PACKAGE = "org.conceptflow.mpl.androidhost";
        private static final String SERVICE_CLASS =
                "org.conceptflow.mpl.host.realtime.PerceptionBusIpcService";
        private static final String DESCRIPTOR =
                "org.conceptflow.mpl.host.realtime.IPerceptionBridge";

        private static final int TRANSACTION_POLL_WORLD_STATE = 1;
        private static final int TRANSACTION_POLL_FOCUS_STATE = 2;
        private static final int TRANSACTION_POLL_HEAD_POSE = 3;
        private static final int TRANSACTION_DRAIN_TOUCH_EVENTS = 4;
        private static final int STATUS_OK = 0;
        private static final int STATUS_NO_UPDATE = 1;
        private static final int MAXIMUM_STATUS = 5;

        private static final int MAXIMUM_WORLD_BYTES = 65_536;
        private static final int MAXIMUM_FOCUS_BYTES = 1_024;
        private static final int MAXIMUM_HEAD_POSE_BYTES = 256;
        private static final int MAXIMUM_TOUCH_BYTES = 8_192;
        private static final long POLL_INTERVAL_MS = 20L;
        private static final long BIND_TIMEOUT_MS = 5_000L;
        private static final long[] RECONNECT_DELAYS_MS =
                {250L, 500L, 1_000L, 2_000L, 5_000L, 15_000L, 30_000L};

        private final Object startLock = new Object();
        private final AtomicReference<byte[]> world = new AtomicReference<>();
        private final AtomicReference<byte[]> focus = new AtomicReference<>();
        private final AtomicReference<byte[]> headPose = new AtomicReference<>();
        private final AtomicReference<byte[]> touch = new AtomicReference<>();
        private final SnapshotEpochGate worldEpoch = new SnapshotEpochGate();
        private final SnapshotEpochGate focusEpoch = new SnapshotEpochGate();
        private final SnapshotEpochGate headPoseEpoch = new SnapshotEpochGate();
        private final AtomicInteger requestedTouchMaximum = new AtomicInteger();
        private final AtomicBoolean touchWakePosted = new AtomicBoolean();
        private final CallbackGenerationGate callbackGate = new CallbackGenerationGate();

        private volatile Handler handler;
        private Context context;
        private IBinder remote;
        private IBinder.DeathRecipient linkedDeathRecipient;
        private boolean bound;
        private boolean binding;
        private boolean reconnectPosted;
        private int reconnectAttempt;
        private long nextPollElapsedRealtime;
        private long lastWorldRevision;
        private long lastFocusRevision;
        private long lastHeadSequence;
        private volatile GenerationServiceConnection activeConnection;

        private final class GenerationServiceConnection implements ServiceConnection {
            private final long generation;
            private final Handler callbackHandler;

            GenerationServiceConnection(long generation, Handler callbackHandler) {
                this.generation = generation;
                this.callbackHandler = callbackHandler;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (!isCurrentConnection(this)) return;
                if (!isExpectedComponent(name)) {
                    disconnectAndRetry(this, null);
                    return;
                }
                try {
                    IBinder.DeathRecipient recipient = () -> postCallback(
                            this,
                            () -> disconnectAndRetry(this, service));
                    service.linkToDeath(recipient, 0);
                    if (!isCurrentConnection(this)) {
                        service.unlinkToDeath(recipient, 0);
                        return;
                    }
                    linkedDeathRecipient = recipient;
                    remote = service;
                    binding = false;
                    bound = true;
                    reconnectAttempt = 0;
                    armSnapshotRebootstrap();
                } catch (RemoteException error) {
                    disconnectAndRetry(this, service);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (isCurrentConnection(this)) disconnectAndRetry(this, remote);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                if (isCurrentConnection(this)) disconnectAndRetry(this, remote);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                if (isCurrentConnection(this)) disconnectAndRetry(this, remote);
            }
        }

        private final Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    IBinder current = remote;
                    if (current != null) {
                        lastWorldRevision = pollSnapshot(
                                current,
                                TRANSACTION_POLL_WORLD_STATE,
                                lastWorldRevision,
                                WORLD_MAGIC,
                                MAXIMUM_WORLD_BYTES,
                                world);
                        lastFocusRevision = pollSnapshot(
                                current,
                                TRANSACTION_POLL_FOCUS_STATE,
                                lastFocusRevision,
                                FOCUS_MAGIC,
                                MAXIMUM_FOCUS_BYTES,
                                focus);
                        lastHeadSequence = pollSnapshot(
                                current,
                                TRANSACTION_POLL_HEAD_POSE,
                                lastHeadSequence,
                                HEAD_POSE_MAGIC,
                                MAXIMUM_HEAD_POSE_BYTES,
                                headPose);
                        drainTouchIfRequested(current);
                    }
                } catch (RemoteException | RuntimeException error) {
                    disconnectAndRetry(activeConnection, remote);
                } finally {
                    scheduleNextPoll();
                }
            }
        };

        void startIfPossible() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    || handler != null
                    || callbackGate.isStopping()) return;
            synchronized (startLock) {
                if (handler != null || callbackGate.isStopping()) return;
                Context resolved = resolveUnityApplicationContext();
                if (resolved == null) return;
                HandlerThread thread = new HandlerThread("ConceptFlow-Perception-IPC");
                thread.start();
                context = resolved;
                handler = new Handler(thread.getLooper());
                nextPollElapsedRealtime = SystemClock.elapsedRealtime();
                handler.post(this::bindIfNeeded);
                handler.post(pollRunnable);
            }
        }

        void stop() {
            synchronized (startLock) {
                Handler current = handler;
                if (current == null || callbackGate.isStopping()) return;
                callbackGate.beginStopping();
                current.removeCallbacksAndMessages(null);
                current.post(() -> {
                    GenerationServiceConnection oldConnection = activeConnection;
                    activeConnection = null;
                    IBinder old = remote;
                    remote = null;
                    IBinder.DeathRecipient recipient = linkedDeathRecipient;
                    linkedDeathRecipient = null;
                    if (old != null && recipient != null) old.unlinkToDeath(recipient, 0);
                    if (bound && context != null && oldConnection != null) {
                        try {
                            context.unbindService(oldConnection);
                        } catch (RuntimeException ignored) {
                            // The framework may already have removed the binding.
                        }
                    }
                    bound = false;
                    binding = false;
                    reconnectPosted = false;
                    clearCaches();
                    current.getLooper().quitSafely();
                    synchronized (startLock) {
                        if (handler == current) handler = null;
                        context = null;
                        callbackGate.finishStopping();
                    }
                });
            }
        }

        byte[] snapshotAfter(
                AtomicReference<byte[]> cache,
                SnapshotEpochGate epoch,
                int expectedMagic,
                long lastCounter) {
            if (lastCounter < 0L) return null;
            byte[] value = cache.get();
            if (value == null) return null;
            long counter = readSnapshotCounter(value, expectedMagic);
            if (!epoch.shouldDeliver(counter, lastCounter)) return null;
            return value.clone();
        }

        byte[] takeTouchBatch(int maximumEvents) {
            while (true) {
                byte[] value = touch.get();
                if (value == null) return null;
                int count = touchCount(value);
                if (count < 0) {
                    touch.compareAndSet(value, null);
                    return null;
                }
                if (count <= maximumEvents) {
                    if (touch.compareAndSet(value, null)) return value.clone();
                    continue;
                }
                int returnedBytes = TOUCH_HEADER_BYTES + maximumEvents * TOUCH_EVENT_BYTES;
                int remainingCount = count - maximumEvents;
                byte[] returned = Arrays.copyOf(value, returnedBytes);
                writeUnsignedShort(returned, 6, maximumEvents);
                byte[] remaining = new byte[TOUCH_HEADER_BYTES + remainingCount * TOUCH_EVENT_BYTES];
                System.arraycopy(value, 0, remaining, 0, TOUCH_HEADER_BYTES);
                writeUnsignedShort(remaining, 6, remainingCount);
                System.arraycopy(
                        value,
                        returnedBytes,
                        remaining,
                        TOUCH_HEADER_BYTES,
                        remainingCount * TOUCH_EVENT_BYTES);
                if (touch.compareAndSet(value, remaining)) return returned;
            }
        }

        void requestTouchDrain(int maximumEvents) {
            requestedTouchMaximum.accumulateAndGet(maximumEvents, Math::max);
            Handler current = handler;
            if (current == null
                    || callbackGate.isStopping()
                    || !touchWakePosted.compareAndSet(false, true)) return;
            if (!current.post(() -> {
                touchWakePosted.set(false);
                if (handler != current || callbackGate.isStopping()) return;
                IBinder service = remote;
                if (service == null) return;
                try {
                    drainTouchIfRequested(service);
                } catch (RemoteException | RuntimeException error) {
                    disconnectAndRetry(activeConnection, service);
                }
            })) touchWakePosted.set(false);
        }

        private void bindIfNeeded() {
            reconnectPosted = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    || callbackGate.isStopping()
                    || remote != null
                    || bound
                    || binding) return;
            Context bindContext = context;
            Handler callbackHandler = handler;
            if (bindContext == null || callbackHandler == null) return;
            binding = true;
            long generation = callbackGate.beginAttempt();
            GenerationServiceConnection connection =
                    new GenerationServiceConnection(generation, callbackHandler);
            activeConnection = connection;
            Intent intent = new Intent().setComponent(new ComponentName(HOST_PACKAGE, SERVICE_CLASS));
            try {
                boolean accepted = bindContext.bindService(
                        intent,
                        Context.BIND_AUTO_CREATE,
                        command -> postCallback(connection, command),
                        connection);
                binding = false;
                bound = accepted;
                if (!accepted) {
                    retireConnection(connection);
                    scheduleReconnect();
                } else {
                    callbackHandler.postDelayed(() -> {
                        if (isCurrentConnection(connection) && bound && remote == null) {
                            disconnectAndRetry(connection, null);
                        }
                    }, BIND_TIMEOUT_MS);
                }
            } catch (RuntimeException error) {
                binding = false;
                bound = false;
                retireConnection(connection);
                scheduleReconnect();
            }
        }

        private long pollSnapshot(
                IBinder service,
                int transaction,
                long lastCounter,
                int expectedMagic,
                int maximumBytes,
                AtomicReference<byte[]> destination) throws RemoteException {
            IpcReply response = transact(service, transaction, lastCounter, maximumBytes);
            if (response.status != STATUS_OK || response.payload == null) return lastCounter;
            long counter = readSnapshotCounter(response.payload, expectedMagic);
            if (counter <= lastCounter) {
                throw new IllegalStateException("non-monotonic perception snapshot");
            }
            destination.set(response.payload.clone());
            return counter;
        }

        private void drainTouchIfRequested(IBinder service) throws RemoteException {
            if (touch.get() != null) return;
            int maximum = requestedTouchMaximum.getAndSet(0);
            if (maximum == 0) return;
            IpcReply response = transact(service, TRANSACTION_DRAIN_TOUCH_EVENTS, maximum, MAXIMUM_TOUCH_BYTES);
            if (response.status != STATUS_OK || response.payload == null) return;
            if (touchCount(response.payload) < 0) {
                throw new IllegalStateException("malformed touch payload");
            }
            touch.compareAndSet(null, response.payload.clone());
        }

        private IpcReply transact(
                IBinder service,
                int transaction,
                long argument,
                int maximumBytes) throws RemoteException {
            Parcel request = Parcel.obtain();
            Parcel response = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                if (transaction == TRANSACTION_DRAIN_TOUCH_EVENTS) {
                    request.writeInt((int) argument);
                } else {
                    request.writeLong(argument);
                }
                if (!service.transact(transaction, request, response, 0)) {
                    throw new RemoteException("perception transaction was not handled");
                }
                response.readException();
                int status = response.readInt();
                if (status < STATUS_OK || status > MAXIMUM_STATUS) {
                    throw new IllegalStateException("unknown perception status");
                }
                if (status == STATUS_NO_UPDATE) {
                    requireNoTrailingData(response);
                    return new IpcReply(status, null);
                }
                if (status != STATUS_OK) {
                    requireNoTrailingData(response);
                    return new IpcReply(status, null);
                }
                int payloadPosition = response.dataPosition();
                int payloadLength = response.readInt();
                response.setDataPosition(payloadPosition);
                if (payloadLength < 0 || payloadLength > maximumBytes) {
                    throw new IllegalStateException("perception payload exceeded lane bound");
                }
                byte[] payload = response.createByteArray();
                if (payload == null || payload.length != payloadLength) {
                    throw new IllegalStateException("missing perception payload");
                }
                requireNoTrailingData(response);
                return new IpcReply(status, payload);
            } finally {
                response.recycle();
                request.recycle();
            }
        }

        private void scheduleNextPoll() {
            Handler current = handler;
            if (current == null || callbackGate.isStopping()) return;
            long now = SystemClock.elapsedRealtime();
            nextPollElapsedRealtime = Math.max(nextPollElapsedRealtime + POLL_INTERVAL_MS, now);
            current.postDelayed(pollRunnable, Math.max(0L, nextPollElapsedRealtime - now));
        }

        private boolean isCurrentConnection(GenerationServiceConnection connection) {
            return connection != null
                    && activeConnection == connection
                    && handler == connection.callbackHandler
                    && callbackGate.accepts(connection.generation);
        }

        private void postCallback(GenerationServiceConnection connection, Runnable command) {
            if (!isCurrentConnection(connection)) return;
            Handler callbackHandler = connection.callbackHandler;
            callbackHandler.post(() -> {
                if (isCurrentConnection(connection)) command.run();
            });
        }

        private void retireConnection(GenerationServiceConnection connection) {
            if (activeConnection != connection) return;
            activeConnection = null;
            callbackGate.invalidate();
        }

        private void disconnectAndRetry(
                GenerationServiceConnection expectedConnection,
                IBinder expected) {
            if (expectedConnection != null && activeConnection != expectedConnection) return;
            if (expected != null && remote != null && remote != expected) return;
            GenerationServiceConnection oldConnection = activeConnection;
            retireConnection(oldConnection);
            IBinder old = remote;
            remote = null;
            IBinder.DeathRecipient recipient = linkedDeathRecipient;
            linkedDeathRecipient = null;
            if (old != null && recipient != null) old.unlinkToDeath(recipient, 0);
            clearCaches();
            if (bound && context != null && oldConnection != null) {
                try {
                    context.unbindService(oldConnection);
                } catch (RuntimeException ignored) {
                    // The framework may already have removed a dead binding.
                }
            }
            bound = false;
            binding = false;
            scheduleReconnect();
        }

        private void scheduleReconnect() {
            Handler current = handler;
            if (current == null || callbackGate.isStopping() || reconnectPosted) return;
            reconnectPosted = true;
            int index = Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1);
            reconnectAttempt = Math.min(reconnectAttempt + 1, RECONNECT_DELAYS_MS.length - 1);
            current.postDelayed(this::bindIfNeeded, RECONNECT_DELAYS_MS[index]);
        }

        private void clearCaches() {
            world.set(null);
            focus.set(null);
            headPose.set(null);
            touch.set(null);
            requestedTouchMaximum.set(0);
            touchWakePosted.set(false);
            lastWorldRevision = 0L;
            lastFocusRevision = 0L;
            lastHeadSequence = 0L;
            worldEpoch.clear();
            focusEpoch.clear();
            headPoseEpoch.clear();
        }

        private void armSnapshotRebootstrap() {
            worldEpoch.arm();
            focusEpoch.arm();
            headPoseEpoch.arm();
        }

        private static Context resolveUnityApplicationContext() {
            try {
                Class<?> unityPlayer = Class.forName("com.unity3d.player.UnityPlayer");
                Field field = unityPlayer.getField("currentActivity");
                Object activity = field.get(null);
                if (!(activity instanceof Context)) return null;
                return ((Context) activity).getApplicationContext();
            } catch (ReflectiveOperationException | RuntimeException error) {
                return null;
            }
        }

        private static boolean isExpectedComponent(ComponentName name) {
            return name != null
                    && HOST_PACKAGE.equals(name.getPackageName())
                    && SERVICE_CLASS.equals(name.getClassName());
        }

        private static void requireNoTrailingData(Parcel parcel) {
            if (parcel.dataAvail() != 0) throw new IllegalStateException("trailing perception reply data");
        }
    }

    static final class CallbackGenerationGate {
        private final AtomicLong generation = new AtomicLong();
        private final AtomicBoolean stopping = new AtomicBoolean();

        long beginAttempt() {
            return generation.incrementAndGet();
        }

        void invalidate() {
            generation.incrementAndGet();
        }

        void beginStopping() {
            stopping.set(true);
            generation.incrementAndGet();
        }

        void finishStopping() {
            generation.incrementAndGet();
            stopping.set(false);
        }

        boolean accepts(long candidateGeneration) {
            return !stopping.get() && generation.get() == candidateGeneration;
        }

        boolean isStopping() {
            return stopping.get();
        }
    }

    private static final class IpcReply {
        final int status;
        final byte[] payload;

        IpcReply(int status, byte[] payload) {
            this.status = status;
            this.payload = payload;
        }
    }

    private static long readSnapshotCounter(byte[] bytes, int expectedMagic) {
        if (bytes == null || bytes.length < 16 || readInt(bytes, 0) != expectedMagic) return -1L;
        if (readUnsignedShort(bytes, 4) != PAYLOAD_VERSION) return -1L;
        long value = 0L;
        for (int index = 8; index < 16; index++) value = (value << 8) | (bytes[index] & 0xffL);
        return value > 0L ? value : -1L;
    }

    private static int touchCount(byte[] bytes) {
        if (bytes == null || bytes.length < TOUCH_HEADER_BYTES || readInt(bytes, 0) != TOUCH_MAGIC) return -1;
        if (readUnsignedShort(bytes, 4) != PAYLOAD_VERSION) return -1;
        int count = readUnsignedShort(bytes, 6);
        if (count > MAXIMUM_TOUCH_EVENTS || bytes.length != TOUCH_HEADER_BYTES + count * TOUCH_EVENT_BYTES) return -1;
        return count;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static void writeUnsignedShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }
}
