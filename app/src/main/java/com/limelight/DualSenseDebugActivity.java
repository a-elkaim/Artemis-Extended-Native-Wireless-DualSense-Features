package com.limelight;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.usbbtonandroid.DualSenseInput;
import com.example.usbbtonandroid.DualSenseInputParser;
import com.example.usbbtonandroid.DualSenseTouchPoint;
import com.limelight.dualsense.DirectDualSenseBt;
import com.limelight.dualsense.DirectDualSenseBtHidBridge;
import com.limelight.dualsense.DualSenseAudioBridge;
import com.limelight.dualsense.DualSenseBridge;
import com.limelight.utils.UiHelper;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Stand-alone test bench for the direct Android-Bluetooth DualSense path.
 * Every control calls the same code a stream uses (DirectDualSenseBt for
 * output reports, DualSenseAudioBridge.receive() for HD haptics / speaker
 * audio), so features can be verified without a host or a running game.
 */
public class DualSenseDebugActivity extends AppCompatActivity {
    private static final int BG = 0xFF0F141A;
    private static final int CARD = 0xFF1B222B;
    private static final int CARD_BORDER = 0xFF35404C;
    private static final int PRIMARY = 0xFF4D9DFF;
    private static final int TEXT_SECONDARY = 0xFFADB8C5;
    private static final int OK = 0xFF62D995;
    private static final int BAD = 0xFFFF6B6B;
    private static final int WARN = 0xFFFFC857;
    private static final int PERMISSION_REQUEST = 4210;

    // Apollo Extended sends 3 ms blocks of 48 kHz, 4-channel S16LE PCM:
    // channels 1/2 = speaker/jack, channels 3/4 = haptic actuators.
    private static final int SAMPLE_RATE = 48_000;
    private static final int PACKET_FRAMES = 144;
    private static final long PACKET_PERIOD_NS = 3_000_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService hidExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DualSenseDebugHid");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private volatile boolean resumed;
    private volatile boolean livePolling = true;

    private TextView statusView;
    private TextView warningView;
    private Button permissionButton;
    private TextView hidInputView;
    private TextView androidInputView;
    private TextView logView;
    private TextView audioStatusView;
    private Button audioButton;

    // Lightbar / LEDs
    private int red = 0, green = 80, blue = 255;
    private int playerMask = 0x04;

    // Triggers
    private Spinner triggerTarget, triggerMode;
    private SeekBar triggerPosition, triggerStrength, triggerFrequency;

    // Audio generator
    private volatile Thread audioThread;
    private volatile boolean audioRunning;
    private volatile int hapticTarget = 0; // 0 both, 1 left, 2 right
    private volatile int hapticPattern = 0; // 0 continuous, 1 pulses, 2 sweep
    private volatile int hapticHz = 120;
    private volatile int hapticAmp = 50;
    private volatile boolean speakerEnabled = false;
    private volatile int speakerHz = 440;
    private volatile int speakerAmp = 25;
    private volatile int controllerVolume = 60;

    private String lastAndroidKey = "—";
    private String lastAndroidAxes = "—";

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            pollHid();
            mainHandler.postDelayed(this, 120);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        setTitle(R.string.dualsense_debug_title);
        // The audio test calls into moonlight-core's Opus speaker encoder.
        try {
            System.loadLibrary("moonlight-core");
        } catch (Throwable t) {
            LimeLog.warning("DualSense debug: moonlight-core load failed: " + t.getMessage());
        }
        DirectDualSenseBt.initialize(this);
        DualSenseAudioBridge.initialize(this);
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        mainHandler.post(pollRunnable);
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(pollRunnable);
        stopAudio();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopAudio();
        final Context app = getApplicationContext();
        hidExecutor.execute(() -> {
            // Leave the controller in a neutral state.
            DirectDualSenseBt.sendRumble(app, (short) 0, (short) 0);
            DirectDualSenseBt.setAdaptiveTriggerEffects(app, (byte) 0x0c, (byte) 0x05, (byte) 0x05,
                    new byte[10], new byte[10]);
        });
        hidExecutor.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- polling

    private void pollHid() {
        if (!pollInFlight.compareAndSet(false, true)) return;
        final Context app = getApplicationContext();
        hidExecutor.execute(() -> {
            try {
                boolean connected = DirectDualSenseBt.isConnected(app);
                DirectDualSenseBtHidBridge.Status status = DirectDualSenseBt.debugStatus(app);
                DualSenseInput input = null;
                String raw = null;
                if (livePolling && connected) {
                    byte[] report = DirectDualSenseBt.readInputReport();
                    if (report != null) {
                        raw = hex(report, 20);
                        input = DualSenseInputParser.INSTANCE.parse(normalizeReport(report));
                    }
                }
                final DualSenseInput finalInput = input;
                final String finalRaw = raw;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    renderStatus(connected, status);
                    renderHidInput(connected, finalInput, finalRaw);
                    audioStatusView.setText((audioRunning ? "▶ Generating test signal\n" : "Idle\n") +
                            "Headset route: " + (DirectDualSenseBt.isHeadsetRoute() ? "jack" : "speaker") +
                            "\n" + DualSenseAudioBridge.diagnostics());
                });
            } catch (Throwable t) {
                LimeLog.warning("DualSense debug poll failed: " + t);
            } finally {
                pollInFlight.set(false);
            }
        });
    }

    /** DualSenseInputParser expects the 0xA1 HID DATA header in front of report 0x31. */
    private static byte[] normalizeReport(byte[] report) {
        if (report.length > 0 && (report[0] & 0xff) == 0x31) {
            byte[] withHeader = new byte[report.length + 1];
            withHeader[0] = (byte) 0xA1;
            System.arraycopy(report, 0, withHeader, 1, report.length);
            return withHeader;
        }
        return report;
    }

    private void renderStatus(boolean connected, DirectDualSenseBtHidBridge.Status s) {
        StringBuilder sb = new StringBuilder();
        boolean sdkOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        boolean permission = !sdkOk ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        line(sb, "Android 12+ (direct mode supported)", sdkOk);
        line(sb, "Nearby devices permission", permission);
        if (s != null) {
            line(sb, "Hidden API bypass", s.getHiddenApiReady());
            line(sb, "HID host profile proxy", s.getProxyReady());
            line(sb, "Controller connected over HID (" + s.getConnectedDeviceCount() + ")",
                    s.getConnectedDeviceCount() > 0);
            line(sb, "Output path (sendData / setReport)", s.getSendDataReady() || s.getSetReportReady());
            line(sb, "Input snapshot path (getReport)", s.getGetReportReady());
            if (s.getConnectedDeviceName() != null) sb.append("\nDevice: ").append(s.getConnectedDeviceName());
            if (s.getLastSendStatus() != null) sb.append("\nLast send: ").append(s.getLastSendStatus());
            if (s.getLastError() != null) sb.append("\nLast error: ").append(s.getLastError());
        }
        sb.append("\n\nAndroid input devices:");
        boolean anyPad = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.getVendorId() != 0x054c) continue;
            anyPad = true;
            sb.append(String.format(Locale.US, "\n • %s  [%04x:%04x]%s", dev.getName(),
                    dev.getVendorId(), dev.getProductId(),
                    DirectDualSenseBt.isBluetoothDualSenseInput(dev) ? "  Bluetooth DualSense" : ""));
        }
        if (!anyPad) sb.append("\n (no Sony controller visible to Android)");
        statusView.setText(sb.toString().trim());
        statusView.setTextColor(connected ? OK : Color.WHITE);

        permissionButton.setVisibility(permission ? View.GONE : View.VISIBLE);
        String warning = null;
        if (DualSenseBridge.getControllerConnected()) {
            warning = "The USB dongle bridge is connected and takes priority. Direct Bluetooth output is disabled while it is active.";
        } else if (!connected) {
            warning = "Direct mode is not active. Pair the DualSense in Android Bluetooth settings and check the items above.";
        }
        warningView.setVisibility(warning == null ? View.GONE : View.VISIBLE);
        if (warning != null) warningView.setText(warning);
    }

    private void renderHidInput(boolean connected, DualSenseInput in, String raw) {
        if (!livePolling) {
            hidInputView.setText("Polling paused");
            return;
        }
        if (!connected) {
            hidInputView.setText("Not connected");
            return;
        }
        if (in == null) {
            hidInputView.setText("No parsable report" + (raw != null ? "\nraw: " + raw : ""));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "L stick %3d,%3d   R stick %3d,%3d\nL2 %3d   R2 %3d   D-pad hat %d\n",
                in.getLeftX(), in.getLeftY(), in.getRightX(), in.getRightY(),
                in.getLeftTrigger(), in.getRightTrigger(), in.getDpadHat()));
        sb.append("Buttons: ").append(in.getPressed().isEmpty() ? "—" : android.text.TextUtils.join(" ", in.getPressed())).append('\n');
        if (in.getGyro() != null) {
            sb.append(String.format(Locale.US, "Gyro  %6d %6d %6d\n", in.getGyro().getFirst(),
                    in.getGyro().getSecond(), in.getGyro().getThird()));
        }
        if (in.getAccel() != null) {
            sb.append(String.format(Locale.US, "Accel %6d %6d %6d\n", in.getAccel().getFirst(),
                    in.getAccel().getSecond(), in.getAccel().getThird()));
        }
        sb.append("Touch: ");
        if (in.getTouches().isEmpty()) sb.append("—");
        for (DualSenseTouchPoint p : in.getTouches()) {
            sb.append(String.format(Locale.US, "#%d(%d,%d) ", p.getId(), p.getX(), p.getY()));
        }
        sb.append(String.format(Locale.US, "\nBattery: %s %s\nHeadphones: %s   Headset mic: %s\nraw: %s",
                in.getBatteryPercent() >= 0 ? in.getBatteryPercent() + "%" : "?", in.getBatteryStatus(),
                in.getHeadphonesConnected() ? "yes" : "no", in.getHeadsetMicrophoneConnected() ? "yes" : "no",
                raw));
        hidInputView.setText(sb.toString());
    }

    private static void line(StringBuilder sb, String label, boolean ok) {
        sb.append(ok ? "✔ " : "✘ ").append(label).append('\n');
    }

    // ------------------------------------------------------- Android input path

    private static boolean isGamepad(InputDevice dev) {
        if (dev == null) return false;
        int s = dev.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Swallow controller keys so Circle/B does not close the screen and the
        // D-pad does not move focus while testing; show them instead.
        if (isGamepad(event.getDevice()) && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                lastAndroidKey = KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", "");
                renderAndroidInput();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (isGamepad(e.getDevice()) && e.getAction() == MotionEvent.ACTION_MOVE) {
            lastAndroidAxes = String.format(Locale.US,
                    "X %.2f Y %.2f  Z %.2f RZ %.2f\nL2 %.2f R2 %.2f  HAT %.0f,%.0f",
                    e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                    e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
                    Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
                    Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)),
                    e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y));
            renderAndroidInput();
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void renderAndroidInput() {
        androidInputView.setText("Last key: " + lastAndroidKey + "\n" + lastAndroidAxes);
    }

    // ----------------------------------------------------------------- output

    private void runHid(String label, HidAction action) {
        final Context app = getApplicationContext();
        hidExecutor.execute(() -> {
            boolean ok;
            try {
                ok = action.run(app);
            } catch (Throwable t) {
                ok = false;
                LimeLog.warning("DualSense debug " + label + " failed: " + t);
            }
            if (!ok) {
                final String msg = label + ": not sent (controller not connected?)";
                mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private interface HidAction { boolean run(Context context); }

    private void sendLightbar() {
        final int r = red, g = green, b = blue;
        runHid("Lightbar", c -> DirectDualSenseBt.sendLed(c, (byte) r, (byte) g, (byte) b));
    }

    private void rumble(int low, int high, long durationMs) {
        runHid("Rumble", c -> DirectDualSenseBt.sendRumble(c, (short) (low << 8), (short) (high << 8)));
        if (durationMs > 0) {
            mainHandler.postDelayed(() -> runHid("Rumble stop",
                    c -> DirectDualSenseBt.sendRumble(c, (short) 0, (short) 0)), durationMs);
        }
    }

    /**
     * Builds one raw DualSense trigger effect (type byte + 10 parameter bytes)
     * using the standard zone encodings games send through the host.
     */
    private static byte[] buildTriggerEffect(int mode, int position, int strength, int frequency) {
        byte[] e = new byte[11];
        strength = Math.max(1, Math.min(8, strength));
        position = Math.max(0, Math.min(9, position));
        switch (mode) {
            case 1: { // Feedback: constant resistance from position to the end
                int force = (strength - 1) & 7, zones = 0;
                long forces = 0;
                for (int i = position; i < 10; i++) {
                    forces |= (long) force << (3 * i);
                    zones |= 1 << i;
                }
                e[0] = 0x21;
                e[1] = (byte) zones; e[2] = (byte) (zones >> 8);
                e[3] = (byte) forces; e[4] = (byte) (forces >> 8);
                e[5] = (byte) (forces >> 16); e[6] = (byte) (forces >> 24);
                break;
            }
            case 2: { // Weapon: resistance that snaps after a short travel
                int start = Math.max(2, Math.min(7, position));
                int end = Math.min(8, start + 3);
                int zones = (1 << start) | (1 << end);
                e[0] = 0x25;
                e[1] = (byte) zones; e[2] = (byte) (zones >> 8);
                e[3] = (byte) (strength - 1);
                break;
            }
            case 3: { // Vibration from position to the end
                int amp = (strength - 1) & 7, zones = 0;
                long amps = 0;
                for (int i = position; i < 10; i++) {
                    amps |= (long) amp << (3 * i);
                    zones |= 1 << i;
                }
                e[0] = 0x26;
                e[1] = (byte) zones; e[2] = (byte) (zones >> 8);
                e[3] = (byte) amps; e[4] = (byte) (amps >> 8);
                e[5] = (byte) (amps >> 16); e[6] = (byte) (amps >> 24);
                e[9] = (byte) Math.max(1, Math.min(255, frequency));
                break;
            }
            default: // Off
                e[0] = 0x05;
        }
        return e;
    }

    private void applyTrigger() {
        int target = triggerTarget.getSelectedItemPosition(); // 0 both, 1 L2, 2 R2
        byte[] effect = buildTriggerEffect(triggerMode.getSelectedItemPosition(),
                triggerPosition.getProgress(), triggerStrength.getProgress() + 1,
                triggerFrequency.getProgress() + 1);
        byte flags = (byte) (target == 0 ? 0x0c : target == 1 ? 0x08 : 0x04);
        byte[] params = new byte[10];
        System.arraycopy(effect, 1, params, 0, 10);
        runHid("Adaptive trigger", c -> DirectDualSenseBt.setAdaptiveTriggerEffects(c, flags,
                effect[0], effect[0], params, params));
    }

    // ------------------------------------------------------------ audio test

    private void toggleAudio() {
        if (audioRunning) stopAudio(); else startAudio();
    }

    private void startAudio() {
        if (audioRunning) return;
        if (DualSenseAudioBridge.isStreamActive()) {
            Toast.makeText(this, "A stream is feeding controller audio; stop it first", Toast.LENGTH_SHORT).show();
            return;
        }
        audioRunning = true;
        audioButton.setText("Stop test signal");
        DualSenseAudioBridge.configure(speakerEnabled ? "auto" : "haptics_only", controllerVolume);
        Thread t = new Thread(this::audioLoop, "DualSenseDebugAudio");
        t.setPriority(Thread.MAX_PRIORITY);
        audioThread = t;
        t.start();
    }

    private void stopAudio() {
        if (!audioRunning && audioThread == null) return;
        audioRunning = false;
        Thread t = audioThread;
        audioThread = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
        try {
            DualSenseAudioBridge.stop();
        } catch (Throwable th) {
            LimeLog.warning("DualSense debug audio stop failed: " + th);
        }
        if (audioButton != null) audioButton.setText("Start test signal");
    }

    private void audioLoop() {
        byte[] pcm;
        int sequence = 0;
        long frame = 0;
        double hapticPhase = 0, speakerPhase = 0;
        long next = System.nanoTime();
        while (audioRunning) {
            pcm = new byte[PACKET_FRAMES * 4 * 2];
            int target = hapticTarget, pattern = hapticPattern;
            double hAmp = hapticAmp / 100.0 * 16000.0;
            double sAmp = speakerEnabled ? speakerAmp / 100.0 * 16000.0 : 0.0;
            for (int i = 0; i < PACKET_FRAMES; i++, frame++) {
                double seconds = frame / (double) SAMPLE_RATE;
                double hz = hapticHz;
                double gate = 1.0;
                if (pattern == 1) {
                    gate = ((long) (seconds * 4)) % 2 == 0 ? 1.0 : 0.0; // 250 ms on / off
                } else if (pattern == 2) {
                    hz = 40 + 260 * ((seconds % 2.0) / 2.0); // 40→300 Hz every 2 s
                }
                hapticPhase += 2 * Math.PI * hz / SAMPLE_RATE;
                speakerPhase += 2 * Math.PI * speakerHz / SAMPLE_RATE;
                short h = (short) Math.round(Math.sin(hapticPhase) * hAmp * gate);
                short s = (short) Math.round(Math.sin(speakerPhase) * sAmp);
                int o = i * 8;
                putS16(pcm, o, s);
                putS16(pcm, o + 2, s);
                putS16(pcm, o + 4, target == 2 ? 0 : h);
                putS16(pcm, o + 6, target == 1 ? 0 : h);
            }
            if (hapticPhase > 2 * Math.PI) hapticPhase %= 2 * Math.PI;
            if (speakerPhase > 2 * Math.PI) speakerPhase %= 2 * Math.PI;
            try {
                DualSenseAudioBridge.receive(0, sequence, PACKET_FRAMES, 4, 0, pcm);
            } catch (Throwable t) {
                LimeLog.warning("DualSense debug audio push failed: " + t);
            }
            sequence = (sequence + 1) & 0xffff;
            next += PACKET_PERIOD_NS;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            } else if (wait < -50_000_000L) {
                next = System.nanoTime(); // fell far behind; resync instead of bursting
            }
        }
    }

    private static void putS16(byte[] b, int o, short v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    // ------------------------------------------------------------------- UI

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(18), dp(20), dp(32));
        scroll.addView(root);

        TextView title = text(getString(R.string.dualsense_debug_title), 25, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        root.addView(text("Tests the direct Android Bluetooth path (no dongle, no stream). " +
                "Every control uses the same code as a real streaming session.", 13, TEXT_SECONDARY));

        // Status
        root.addView(sectionTitle("Connection"));
        LinearLayout statusCard = card();
        warningView = text("", 13, WARN);
        warningView.setPadding(0, 0, 0, dp(8));
        statusCard.addView(warningView);
        statusView = mono("Checking…");
        statusCard.addView(fixedBox(statusView, 230));
        permissionButton = actionButton("Grant Nearby devices permission", true, v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
            }
        });
        permissionButton.setVisibility(View.GONE);
        statusCard.addView(permissionButton, match(dp(8)));
        root.addView(statusCard, match(dp(4)));

        // Input
        root.addView(sectionTitle("Input"));
        LinearLayout inputCard = card();
        Switch poll = new Switch(this);
        poll.setText("Live HID report polling");
        poll.setTextColor(Color.WHITE);
        poll.setChecked(true);
        poll.setOnCheckedChangeListener((b, checked) -> livePolling = checked);
        inputCard.addView(poll);
        inputCard.addView(text("HID report (what Artemis reads directly)", 13, TEXT_SECONDARY));
        hidInputView = mono("—");
        inputCard.addView(fixedBox(hidInputView, 190));
        TextView androidLabel = text("Android input events (what games see without the native path)", 13, TEXT_SECONDARY);
        androidLabel.setPadding(0, dp(10), 0, 0);
        inputCard.addView(androidLabel);
        androidInputView = mono("Last key: —\n—");
        inputCard.addView(fixedBox(androidInputView, 60));
        root.addView(inputCard, match(dp(4)));

        // Lights
        root.addView(sectionTitle("Lightbar & LEDs"));
        LinearLayout lightCard = card();
        lightCard.addView(slider("Red", 255, red, v -> { red = v; sendLightbar(); }));
        lightCard.addView(slider("Green", 255, green, v -> { green = v; sendLightbar(); }));
        lightCard.addView(slider("Blue", 255, blue, v -> { blue = v; sendLightbar(); }));
        LinearLayout presets = horizontal();
        int[][] colors = {{255, 0, 0}, {0, 255, 0}, {0, 80, 255}, {255, 255, 255}, {0, 0, 0}};
        String[] names = {"Red", "Green", "Blue", "White", "Off"};
        for (int i = 0; i < colors.length; i++) {
            final int[] c = colors[i];
            presets.addView(actionButton(names[i], false, v -> {
                red = c[0]; green = c[1]; blue = c[2];
                sendLightbar();
            }), weightedWithMargin());
        }
        lightCard.addView(presets);
        lightCard.addView(text("Player LEDs", 13, Color.WHITE), match(dp(8)));
        LinearLayout leds = horizontal();
        for (int i = 0; i < 5; i++) {
            final int bit = 1 << i;
            CheckBox box = new CheckBox(this);
            box.setText(String.valueOf(i + 1));
            box.setTextColor(Color.WHITE);
            box.setChecked((playerMask & bit) != 0);
            box.setOnCheckedChangeListener((b, checked) -> {
                playerMask = checked ? playerMask | bit : playerMask & ~bit;
                final int mask = playerMask;
                runHid("Player LEDs", c -> DirectDualSenseBt.setPlayerLeds(c, mask));
            });
            leds.addView(box, weighted());
        }
        lightCard.addView(leds);
        Switch mute = new Switch(this);
        mute.setText("Mute button LED");
        mute.setTextColor(Color.WHITE);
        mute.setOnCheckedChangeListener((b, checked) ->
                runHid("Mute LED", c -> DirectDualSenseBt.setMicrophoneMuted(c, checked)));
        lightCard.addView(mute, match(dp(6)));
        root.addView(lightCard, match(dp(4)));

        // Rumble
        root.addView(sectionTitle("Rumble (classic motors)"));
        LinearLayout rumbleCard = card();
        final int[] rumble = {200, 200};
        rumbleCard.addView(slider("Left / strong motor", 255, rumble[0], v -> rumble[0] = v));
        rumbleCard.addView(slider("Right / weak motor", 255, rumble[1], v -> rumble[1] = v));
        LinearLayout rumbleButtons = horizontal();
        rumbleButtons.addView(actionButton("Pulse 0.5 s", true, v -> rumble(rumble[0], rumble[1], 500)),
                weightedWithMargin());
        rumbleButtons.addView(actionButton("Hold", false, v -> rumble(rumble[0], rumble[1], 0)),
                weightedWithMargin());
        rumbleButtons.addView(actionButton("Stop", false, v -> rumble(0, 0, 0)), weightedWithMargin());
        rumbleCard.addView(rumbleButtons);
        root.addView(rumbleCard, match(dp(4)));

        // Adaptive triggers
        root.addView(sectionTitle("Adaptive triggers"));
        LinearLayout trigCard = card();
        triggerTarget = spinner(new String[]{"Both triggers", "L2 only", "R2 only"});
        trigCard.addView(labelled("Target", triggerTarget));
        triggerMode = spinner(new String[]{"Off", "Feedback (resistance)", "Weapon (snap)", "Vibration"});
        trigCard.addView(labelled("Effect", triggerMode));
        triggerPosition = seek(9, 2);
        trigCard.addView(labelled("Start position (0–9)", triggerPosition));
        triggerStrength = seek(7, 5);
        trigCard.addView(labelled("Strength (1–8)", triggerStrength));
        triggerFrequency = seek(254, 30);
        trigCard.addView(labelled("Vibration frequency (Hz)", triggerFrequency));
        LinearLayout trigButtons = horizontal();
        trigButtons.addView(actionButton("Apply", true, v -> applyTrigger()), weightedWithMargin());
        trigButtons.addView(actionButton("Reset both", false, v -> {
            triggerMode.setSelection(0);
            runHid("Trigger reset", c -> DirectDualSenseBt.setAdaptiveTriggerEffects(c, (byte) 0x0c,
                    (byte) 0x05, (byte) 0x05, new byte[10], new byte[10]));
        }), weightedWithMargin());
        trigCard.addView(trigButtons);
        root.addView(trigCard, match(dp(4)));

        // HD haptics & speaker
        root.addView(sectionTitle("HD haptics & speaker"));
        LinearLayout audioCard = card();
        audioCard.addView(text("Generates the same 4-channel 48 kHz signal Apollo Extended sends " +
                "and feeds it through the real audio pipeline.", 12, TEXT_SECONDARY));
        Spinner hTarget = spinner(new String[]{"Both actuators", "Left actuator", "Right actuator"});
        hTarget.setOnItemSelectedListener(new SimpleSelect(pos -> hapticTarget = pos));
        audioCard.addView(labelled("Haptic target", hTarget));
        Spinner hPattern = spinner(new String[]{"Continuous", "Pulses (250 ms)", "Sweep 40→300 Hz"});
        hPattern.setOnItemSelectedListener(new SimpleSelect(pos -> hapticPattern = pos));
        audioCard.addView(labelled("Haptic pattern", hPattern));
        audioCard.addView(slider("Haptic frequency (Hz)", 400, hapticHz, v -> hapticHz = Math.max(10, v)));
        audioCard.addView(slider("Haptic amplitude (%)", 100, hapticAmp, v -> hapticAmp = v));
        Switch speaker = new Switch(this);
        speaker.setText("Also play a tone on the speaker / headset");
        speaker.setTextColor(Color.WHITE);
        speaker.setOnCheckedChangeListener((b, checked) -> {
            speakerEnabled = checked;
            if (audioRunning) {
                DualSenseAudioBridge.configure(checked ? "auto" : "haptics_only", controllerVolume);
            }
        });
        audioCard.addView(speaker, match(dp(6)));
        audioCard.addView(slider("Tone frequency (Hz)", 2000, speakerHz, v -> speakerHz = Math.max(50, v)));
        audioCard.addView(slider("Tone amplitude (%)", 100, speakerAmp, v -> speakerAmp = v));
        audioCard.addView(slider("Controller volume (%)", 100, controllerVolume, v -> {
            controllerVolume = v;
            DualSenseAudioBridge.setControllerVolume(v);
        }));
        Switch jack = new Switch(this);
        jack.setText("Route audio to the headset jack");
        jack.setTextColor(Color.WHITE);
        jack.setChecked(DirectDualSenseBt.isHeadsetRoute());
        jack.setOnCheckedChangeListener((b, checked) ->
                runHid("Headset route", c -> DirectDualSenseBt.setHeadsetRoute(c, checked)));
        audioCard.addView(jack, match(dp(6)));
        audioButton = actionButton("Start test signal", true, v -> toggleAudio());
        audioCard.addView(audioButton, match(dp(8)));
        audioStatusView = mono("Idle");
        audioCard.addView(fixedBox(audioStatusView, 120));
        root.addView(audioCard, match(dp(4)));

        // Log
        root.addView(sectionTitle("Transport log"));
        LinearLayout logCard = card();
        LinearLayout logButtons = horizontal();
        logButtons.addView(actionButton("Refresh", false, v -> refreshLog()), weightedWithMargin());
        logButtons.addView(actionButton("Copy", false, v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("DualSense debug log", logView.getText()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        }), weightedWithMargin());
        logCard.addView(logButtons);
        logView = mono("Tap Refresh");
        logView.setTextIsSelectable(true);
        logView.setTextSize(11);
        logCard.addView(logView, match(dp(6)));
        root.addView(logCard, match(dp(4)));

        return scroll;
    }

    private void refreshLog() {
        hidExecutor.execute(() -> {
            String log = "Audio / HD haptics: " + DualSenseAudioBridge.diagnostics() + "\n\n" +
                    DirectDualSenseBt.debugFullLog();
            mainHandler.post(() -> logView.setText(log));
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) pollHid();
    }

    // -------------------------------------------------------- view helpers

    private interface ValueListener { void onValue(int value); }

    private static final class SimpleSelect implements android.widget.AdapterView.OnItemSelectedListener {
        private final ValueListener listener;
        SimpleSelect(ValueListener listener) { this.listener = listener; }
        @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
            listener.onValue(pos);
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
    }

    private SeekBar seek(int max, int initial) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(initial);
        return bar;
    }

    private View slider(String name, int max, int initial, ValueListener listener) {
        SeekBar bar = seek(max, initial);
        TextView label = text(name + ": " + initial, 13, Color.WHITE);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(name + ": " + progress);
                if (fromUser) listener.onValue(progress);
            }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        LinearLayout group = vertical();
        group.setPadding(0, dp(6), 0, dp(2));
        group.addView(label);
        group.addView(bar, match(0));
        return group;
    }

    private View labelled(String label, View control) {
        LinearLayout group = vertical();
        group.setPadding(0, dp(7), 0, dp(4));
        group.addView(text(label, 13, Color.WHITE));
        group.addView(control, match(0));
        return group;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        return spinner;
    }

    private View sectionTitle(String title) {
        TextView heading = text(title, 18, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(3), dp(16), dp(3), dp(6));
        return heading;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout view = vertical();
        view.setPadding(dp(16), dp(15), dp(16), dp(15));
        view.setBackground(rounded(CARD, CARD_BORDER, 18));
        return view;
    }

    private Button actionButton(String value, boolean primary, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(primary ? 0xFF06111D : Color.WHITE);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        GradientDrawable content = rounded(primary ? PRIMARY : 0xFF27313C, primary ? PRIMARY : 0xFF465463, 13);
        GradientDrawable mask = rounded(Color.WHITE, Color.WHITE, 13);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(primary ? 0x5506111D : 0x66FFFFFF), content, mask));
        button.setOnClickListener(action);
        return button;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    /**
     * Wraps a frequently-updated TextView in a fixed-height, internally
     * scrollable box so its changing line count never re-flows the page.
     */
    private View fixedBox(TextView content, int heightDp) {
        ScrollView box = new ScrollView(this);
        box.setNestedScrollingEnabled(true);
        box.setFillViewport(true);
        box.setScrollbarFadingEnabled(false);
        box.setVerticalScrollBarEnabled(true);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setBackground(rounded(0xFF141A21, 0xFF2A333E, 10));
        // Stop the outer page from auto-scrolling toward this box when its text changes.
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        box.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        params.setMargins(0, dp(4), 0, dp(4));
        box.setLayoutParams(params);
        return box;
    }

    private TextView mono(String value) {
        TextView view = text(value, 12, Color.WHITE);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private GradientDrawable rounded(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams match(int verticalMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, verticalMargin, 0, verticalMargin);
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams weightedWithMargin() {
        LinearLayout.LayoutParams params = weighted();
        params.setMargins(dp(4), dp(8), dp(4), 0);
        return params;
    }

    private static String hex(byte[] data, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, data.length); i++) {
            sb.append(String.format(Locale.US, "%02X ", data[i] & 0xff));
        }
        if (data.length > max) sb.append("…(").append(data.length).append(" B)");
        return sb.toString().trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
