package com.growthinternal.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements Hub.Callback {
    private static final int REQ_PERMS = 1;
    private static final int REQ_PHOTO = 2;
    private static final int REQ_CAMERA = 3;
    private static final int REQ_SEND_PHOTO = 4;
    private static final int REQ_VIDEO = 5;
    private static final int REQ_SEND_VIDEO = 6;
    private static final int VIDEO_SECONDS = 15;

    // Growth Internal palette: navy (#131F37), crimson red (#D61E20), cool light surface.
    private static final int BRAND = Color.rgb(19, 31, 55);
    private static final int BG = Color.rgb(244, 246, 249);
    private static final int CARD = Color.WHITE;
    private static final int LINE = Color.rgb(222, 227, 235);
    private static final int TEXT = Color.rgb(19, 31, 55);
    private static final int SUB = Color.rgb(98, 110, 126);
    private static final int ACCENT = Color.rgb(25, 45, 82);
    private static final int LEAF = Color.rgb(46, 168, 92);
    private static final int ACCENT_SOFT = Color.rgb(228, 234, 245);
    private static final int DANGER = Color.rgb(214, 30, 32);
    private static final int DANGER_SOFT = Color.rgb(253, 234, 235);
    private static final int AMBER = Color.rgb(217, 140, 20);
    private static final int AMBER_SOFT = Color.rgb(254, 243, 219);
    private static final int AMBER_TEXT = Color.rgb(146, 84, 0);
    private static final long PRESENCE_EVERY_MS = 30000;

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private AudioManager audio;
    private SharedPreferences prefs;
    private Handler ui;

    private Hub hub;
    // Multihop: how to reach our owner's group (from JOIN_INFO), and the group we bridge to.
    private byte[] upstreamInfo;
    private Bridge.Info bridgeTo;
    private Bridge bridge;
    private android.net.Network uplinkNetwork;
    private java.net.InetAddress uplinkTarget;
    private boolean isOwner;
    private boolean p2pGroupActive;
    private Aware aware;
    private Lan lan;
    private Bt bt;
    private Ble ble;
    private final Voice voice = new Voice();
    private String myName;
    private byte[] myPhoto;
    private boolean callMode;
    private boolean speakerOn = true;
    private final Map<String, byte[]> members = new LinkedHashMap<String, byte[]>();
    private final Set<String> talkers = new LinkedHashSet<String>();
    private final List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    private final Map<String, Aware.Peer> awarePeers = new LinkedHashMap<String, Aware.Peer>();
    private final Map<String, Lan.Peer> lanPeers = new LinkedHashMap<String, Lan.Peer>();
    private final Map<String, android.bluetooth.BluetoothDevice> btPeers =
            new LinkedHashMap<String, android.bluetooth.BluetoothDevice>();
    private final Map<String, String> btNames = new LinkedHashMap<String, String>();
    private final Map<String, Integer> btPsms = new HashMap<String, Integer>();
    private final Map<String, Long> recentMeshTexts = new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 256;
        }
    };
    private final Set<String> bleSosNames = new HashSet<String>();
    private final Map<String, Presence> presences = new HashMap<String, Presence>();
    private final Set<String> sosSilenced = new HashSet<String>();
    private final Presence me = new Presence();
    private int statusBeforeSos = Presence.UNSET;
    private LocationManager locations;
    private LocationListener locationListener;
    private Location myLocation;
    private boolean alarmOn;
    private boolean visible;
    private SensorManager sensors;
    private SensorEventListener headingListener;
    private SensorEventListener proximityListener;
    private boolean nearEar;
    private final ApkShare apkShare = new ApkShare();
    private float heading;
    private RadarView radar;
    private View compassPage;
    private View mapPage;
    private MapView mapView;
    private TextView mapStatus, mapDownload;
    private volatile boolean mapDownloading;
    private LinearLayout compassList;
    private TextView compassHint;

    private final Runnable presenceTick = new Runnable() {
        @Override public void run() {
            sendPresence();
            ui.postDelayed(this, PRESENCE_EVERY_MS);
        }
    };

    private final Runnable alarmLoop = new Runnable() {
        @Override public void run() {
            if (!alarmOn) return;
            beep(AudioManager.STREAM_ALARM, ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500);
            vibrate(new long[] {0, 700, 300, 700});
            ui.postDelayed(this, 2500);
        }
    };

    private TextView statusText, emptyPeers, talkText, pttLabel, alertText;
    private TextView sosChip, statusChip, sosTitle, sosDetail, sosSilence;
    private View statusDot, nearbyCard, groupCard, talkBanner, alertBanner, sosCard;
    private ImageView myAvatar, talkAvatar, callIcon, speakerIcon, sosAvatar;
    private LinearLayout peerList, memberRow, chatList, memberStrip;
    private View memberScroll;
    private ImageView groupChevron;
    private TextView groupTitle;
    private boolean groupOpen;
    private ScrollView chatScroll;
    private EditText input;
    private FrameLayout pttButton, callButton, speakerButton;

    @Override protected void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        ui = new Handler(Looper.getMainLooper());
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        prefs = getSharedPreferences("growthinternal", MODE_PRIVATE);
        if (state == null) {
            new Thread(new Runnable() {
                @Override public void run() { clearClips(); }
            }, "clear-clips").start();
        }
        myName = prefs.getString("name", Build.MODEL);
        String p = prefs.getString("photo", null);
        myPhoto = p == null ? null : Base64.decode(p, Base64.DEFAULT);
        me.status = prefs.getInt("status", Presence.UNSET);
        if (me.status == Presence.SOS) statusBeforeSos = Presence.NEED_HELP;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        Alerts.createChannels(this);
        // SOS must be seen on a locked phone.
        w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        FrameLayout frame = new FrameLayout(this);
        frame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            }
        });
        frame.addView(buildUi());
        compassPage = buildCompass();
        frame.addView(compassPage);
        mapPage = buildMap();
        frame.addView(mapPage);
        if (state == null) {
            frame.addView(buildSplash());
            w.setStatusBarColor(BRAND);
            w.setNavigationBarColor(BRAND);
            w.getDecorView().setSystemUiVisibility(0);
        }
        setContentView(frame);
        refreshAll();

        aware = new Aware(this, new Aware.Listener() {
            @Override public void onPeer(final Aware.Peer peer) {
                ui.post(new Runnable() {
                    @Override public void run() { onAwarePeer(peer); }
                });
            }
            @Override public void onResponderRequested() {
                ui.post(new Runnable() {
                    @Override public void run() { ensureActiveHub(); }
                });
            }
            @Override public void onTargetReady(final String peerName,
                    final java.net.InetAddress address, final int port) {
                ui.post(new Runnable() {
                    @Override public void run() { onAwareTargetReady(peerName, address, port); }
                });
            }
        });
        lan = new Lan(new Lan.Listener() {
            @Override public void onPeer(final Lan.Peer peer) {
                ui.post(new Runnable() {
                    @Override public void run() { onLanPeer(peer); }
                });
            }
        });
        lan.start();
        bt = new Bt(this, new Bt.Listener() {
            @Override public void onIncoming(final android.bluetooth.BluetoothSocket socket) {
                ui.post(new Runnable() {
                    @Override public void run() { onBtIncoming(socket); }
                });
            }
            @Override public void onL2capReady(final int psm) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (ble != null && neededPermissions().length == 0) {
                            ble.updateAdvertise(myName, buildPresence(), activeGroupOwner(), psm);
                        }
                    }
                });
            }
        });
        ble = new Ble(new Ble.Listener() {
            @Override public void onBeacon(final Ble.Beacon beacon, final android.bluetooth.BluetoothDevice device) {
                ui.post(new Runnable() {
                    @Override public void run() { onBleBeacon(beacon, device); }
                });
            }
            @Override public void onMesh(final Ble.MeshPacket packet) {
                ui.post(new Runnable() {
                    @Override public void run() { onBleMesh(packet); }
                });
            }
        });

        p2p = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) {
            setStatus("HP ini tidak mendukung Wi‑Fi Direct", DANGER);
        } else {
            channel = p2p.initialize(this, getMainLooper(), null);
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { onP2pEvent(i); }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class)
                            .invoke(this, receiver, f, 4 /* RECEIVER_NOT_EXPORTED */);
                } catch (Exception e) {
                    registerReceiver(receiver, f);
                }
            } else {
                registerReceiver(receiver, f);
            }
        }

        addSystemMessage("Komunikasi lapangan tanpa internet & sinyal. Nyalakan Wi‑Fi dan Lokasi di setiap HP Tim, "
                + "lalu ketuk ikon cari.");
        if (neededPermissions().length == 0) {
            startLocation();
            startOfflineTransports();
        }
        // First launch: the default name is the phone model, which nobody in the group recognizes.
        if (state == null && !prefs.contains("name")) {
            ui.postDelayed(new Runnable() {
                @Override public void run() { if (!isFinishing()) askName(); }
            }, 1500);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
    }

    @Override protected void onPause() {
        visible = false;
        super.onPause();
    }

    /** System notification only when the user is not looking at the app. */
    private void notifyAway(String channel, String title, String text) {
        if (!visible) Alerts.show(this, channel, title, text);
    }

    private void updateKeepAlive() {
        if (hub == null) {
            KeepAliveService.stop(this);
            return;
        }
        String t = members.isEmpty() ? "Menunggu Tim bergabung" : "Tersambung dengan " + members.size() + " orang";
        if (me.status == Presence.SOS) t = "Panggilan Prioritas aktif · " + t;
        KeepAliveService.update(this, t);
    }

    // shortcut: uses legacy onBackPressed because build.sh compiles against android-23; migrate to OnBackInvokedCallback when compileSdk >= 33
    @Override public void onBackPressed() {
        if (mapPage.getVisibility() == View.VISIBLE) mapPage.setVisibility(View.GONE);
        else if (compassPage.getVisibility() == View.VISIBLE) hideCompass();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        stopHeading();
        stopProximity();
        stopAlarm();
        apkShare.stop();
        ui.removeCallbacks(presenceTick);
        if (locations != null && locationListener != null) locations.removeUpdates(locationListener);
        if (receiver != null) unregisterReceiver(receiver);
        if (aware != null) aware.stop();
        if (lan != null) lan.stop();
        if (bt != null) bt.stop();
        if (ble != null) ble.stop();
        stopHub();
        stopBridge();
        if (p2p != null) p2p.removeGroup(channel, null);
        voice.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------ UI kit

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private GradientDrawable shape(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private GradientDrawable ovalStroke(int color, int stroke) {
        GradientDrawable g = oval(color);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private Drawable pressable(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), content, null);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setBackground(shape(CARD, 18, LINE));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private static LinearLayout.LayoutParams wrap() {
        return lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams fill() {
        return lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams grow() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams gapTop(LinearLayout.LayoutParams p, int gapDp) {
        p.topMargin = dp(gapDp);
        return p;
    }

    private ImageView icon(int res, int color, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        v.setLayoutParams(new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER));
        return v;
    }

    private FrameLayout roundButton(int sizeDp, int bg, ImageView glyph) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(pressable(oval(bg)));
        f.setElevation(dp(2));
        f.addView(glyph);
        f.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    private ImageView avatar(String name, byte[] photo, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageBitmap(Avatars.circle(name, photo, dp(sizeDp)));
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    // ------------------------------------------------------------ layout

    private View buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(6), dp(14), dp(6));

        // Header: title, status pill, own avatar.
        LinearLayout header = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_small);
        LinearLayout.LayoutParams logoLp = lp(dp(36), dp(36));
        logoLp.rightMargin = dp(10);
        header.addView(logo, logoLp);
        LinearLayout titles = column();
        TextView brand = text("Growth Internal", 19, BRAND, true);
        brand.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        titles.addView(brand);
        LinearLayout pill = row();
        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = lp(dp(8), dp(8));
        dotLp.rightMargin = dp(6);
        pill.addView(statusDot, dotLp);
        statusText = text("", 12, SUB, false);
        pill.addView(statusText);
        titles.addView(pill, wrap());
        header.addView(titles, grow());
        myAvatar = new ImageView(this);
        myAvatar.setBackground(pressable(oval(Color.TRANSPARENT)));
        myAvatar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showProfileMenu(); }
        });
        header.addView(myAvatar, lp(dp(38), dp(38)));
        root.addView(header, fill());

        // Alert banner (someone left / connection lost).
        alertBanner = buildBanner();
        root.addView(alertBanner, gapTop(fill(), 8));

        // Incoming SOS: stays until the sender cancels it.
        sosCard = buildSosCard();
        root.addView(sosCard, gapTop(fill(), 8));

        // Nearby devices card (before joining a group).
        LinearLayout nearby = card();
        LinearLayout nh = row();
        LinearLayout nt = column();
        nt.addView(text("Perangkat sekitar", 16, TEXT, true));
        nt.addView(text("Ketuk untuk menghubungkan", 13, SUB, false));
        nh.addView(nt, grow());
        FrameLayout search = roundButton(40, ACCENT_SOFT, icon(R.drawable.ic_search, ACCENT, 20));
        search.setElevation(0);
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { discover(); }
        });
        nh.addView(search);
        nearby.addView(nh, fill());
        emptyPeers = text("Belum ada perangkat. Pastikan HP lain juga membuka aplikasi dan menekan cari.",
                13, SUB, false);
        nearby.addView(emptyPeers, gapTop(fill(), 10));
        peerList = column();
        ScrollView peerScroll = new ScrollView(this);
        peerScroll.addView(peerList);
        nearby.addView(peerScroll, gapTop(lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 4));
        nearbyCard = nearby;
        root.addView(nearby, gapTop(fill(), 8));

        // Group card (after joining). Folds to one line so the chat gets the room.
        LinearLayout group = card();
        group.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout gh = row();
        gh.setBackground(pressable(shape(Color.TRANSPARENT, 12, 0)));
        gh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setGroupOpen(!groupOpen); }
        });
        groupTitle = text("Tim", 15, TEXT, true);
        gh.addView(groupTitle, wrap());
        memberStrip = row();
        LinearLayout.LayoutParams msl = grow();
        msl.leftMargin = dp(10);
        gh.addView(memberStrip, msl);
        groupChevron = icon(R.drawable.ic_expand, SUB, 22);
        LinearLayout.LayoutParams chl = lp(dp(22), dp(22));
        chl.rightMargin = dp(8);
        gh.addView(groupChevron, chl);
        TextView leave = text("Keluar", 13, DANGER, true);
        leave.setPadding(dp(10), dp(5), dp(10), dp(5));
        leave.setBackground(pressable(shape(DANGER_SOFT, 14, 0)));
        leave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { disconnect(); }
        });
        gh.addView(leave);
        group.addView(gh, fill());
        memberRow = row();
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(memberRow);
        memberScroll = hs;
        group.addView(hs, gapTop(fill(), 8));
        groupCard = group;
        root.addView(group, gapTop(fill(), 8));
        setGroupOpen(prefs.getBoolean("group_open", false));

        // Who is talking now.
        LinearLayout talk = row();
        talk.setBackground(shape(ACCENT, 18, 0));
        talk.setPadding(dp(12), dp(10), dp(16), dp(10));
        talkAvatar = new ImageView(this);
        talk.addView(talkAvatar, lp(dp(40), dp(40)));
        talkText = text("", 15, Color.WHITE, true);
        LinearLayout.LayoutParams ttl = grow();
        ttl.leftMargin = dp(12);
        talk.addView(talkText, ttl);
        talkBanner = talk;
        root.addView(talk, gapTop(fill(), 8));

        // Field team actions: priority call, own status, instruction to everyone.
        LinearLayout chips = row();
        sosChip = chip("Prioritas", Color.WHITE, DANGER);
        sosChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSos(); }
        });
        statusChip = chip("", TEXT, CARD);
        statusChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askStatus(); }
        });
        TextView announce = chip("Instruksi", AMBER_TEXT, AMBER_SOFT);
        announce.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askAnnouncement(); }
        });
        chips.addView(sosChip);
        TextView compass = chip("Kompas", ACCENT, ACCENT_SOFT);
        compass.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCompass(); }
        });
        LinearLayout.LayoutParams kl = wrap();
        kl.leftMargin = dp(8);
        chips.addView(compass, kl);
        TextView map = chip("Peta", ACCENT, ACCENT_SOFT);
        map.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMap(); }
        });
        LinearLayout.LayoutParams ml = wrap();
        ml.leftMargin = dp(8);
        chips.addView(map, ml);
        LinearLayout.LayoutParams cl = wrap();
        cl.leftMargin = dp(8);
        chips.addView(statusChip, cl);
        LinearLayout.LayoutParams al = wrap();
        al.leftMargin = dp(8);
        chips.addView(announce, al);
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        root.addView(chipScroll, gapTop(fill(), 8));

        // Chat.
        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatList = column();
        chatList.setPadding(0, dp(8), 0, dp(8));
        chatScroll.addView(chatList);
        LinearLayout.LayoutParams chatLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        chatLp.topMargin = dp(2);
        root.addView(chatScroll, chatLp);

        // Message input.
        LinearLayout inputRow = row();
        FrameLayout photo = roundButton(42, CARD, icon(R.drawable.ic_image, ACCENT, 20));
        photo.setElevation(0);
        photo.setBackground(pressable(ovalStroke(CARD, LINE)));
        photo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askPhotoSource(); }
        });
        LinearLayout.LayoutParams pl = lp(dp(42), dp(42));
        pl.rightMargin = dp(8);
        inputRow.addView(photo, pl);
        input = new EditText(this);
        input.setHint("Tulis pesan");
        input.setTextSize(15);
        input.setTextColor(TEXT);
        input.setHintTextColor(SUB);
        input.setBackground(shape(CARD, 24, LINE));
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, android.view.KeyEvent e) {
                sendText();
                return true;
            }
        });
        inputRow.addView(input, grow());
        FrameLayout send = roundButton(42, ACCENT, icon(R.drawable.ic_send, Color.WHITE, 18));
        send.setElevation(0);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendText(); }
        });
        LinearLayout.LayoutParams sl = lp(dp(42), dp(42));
        sl.leftMargin = dp(8);
        inputRow.addView(send, sl);
        root.addView(inputRow, gapTop(fill(), 4));

        // Voice controls: call mode, push-to-talk, speaker.
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        callIcon = icon(R.drawable.ic_call, ACCENT, 20);
        callButton = roundButton(44, CARD, callIcon);
        callButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleCallMode(); }
        });
        pttButton = roundButton(60, ACCENT, icon(R.drawable.ic_mic, Color.WHITE, 28));
        pttButton.setElevation(dp(4));
        pttButton.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (callMode) return false;
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
                    startTalking();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    stopTalking();
                }
                return true;
            }
        });
        speakerIcon = icon(R.drawable.ic_volume, ACCENT, 20);
        speakerButton = roundButton(44, CARD, speakerIcon);
        speakerButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                speakerOn = !speakerOn;
                applyAudioRoute();
                refreshControls();
            }
        });
        LinearLayout.LayoutParams side = lp(dp(44), dp(44));
        LinearLayout.LayoutParams mid = lp(dp(60), dp(60));
        mid.leftMargin = dp(28);
        mid.rightMargin = dp(28);
        controls.addView(callButton, side);
        controls.addView(pttButton, mid);
        controls.addView(speakerButton, side);
        root.addView(controls, gapTop(fill(), 8));
        pttLabel = text("", 12, SUB, false);
        pttLabel.setGravity(Gravity.CENTER);
        root.addView(pttLabel, gapTop(fill(), 2));
        return root;
    }

    private TextView chip(String label, int fg, int bg) {
        TextView t = text(label, 13, fg, true);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setBackground(pressable(shape(bg, 18, bg == CARD ? LINE : 0)));
        return t;
    }

    private View buildSosCard() {
        LinearLayout c = column();
        c.setBackground(shape(DANGER, 18, 0));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout top = row();
        sosAvatar = new ImageView(this);
        top.addView(sosAvatar, lp(dp(44), dp(44)));
        LinearLayout t = column();
        t.setPadding(dp(12), 0, 0, 0);
        sosTitle = text("", 17, Color.WHITE, true);
        sosDetail = text("", 14, 0xE6FFFFFF, false);
        t.addView(sosTitle);
        t.addView(sosDetail);
        top.addView(t, grow());
        c.addView(top, fill());
        sosSilence = text("Bisu Dering", 14, DANGER, true);
        sosSilence.setGravity(Gravity.CENTER);
        sosSilence.setPadding(dp(12), dp(8), dp(12), dp(8));
        sosSilence.setBackground(pressable(shape(Color.WHITE, 14, 0)));
        sosSilence.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (Map.Entry<String, Presence> e : presences.entrySet()) {
                    if (e.getValue().status == Presence.SOS) sosSilenced.add(e.getKey());
                }
                refreshSos();
            }
        });
        c.addView(sosSilence, gapTop(fill(), 10));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCompass(); }
        });
        c.setVisibility(View.GONE);
        return c;
    }

    /** Brand splash on cold start, matching the window background, then fades away. */
    private View buildSplash() {
        final FrameLayout s = new FrameLayout(this);
        s.setBackgroundColor(BRAND);
        s.setClickable(true);
        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.splash_logo);
        art.setAdjustViewBounds(true);
        s.addView(art, new FrameLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                s.animate().alpha(0f).setDuration(350).withEndAction(new Runnable() {
                    @Override public void run() {
                        s.setVisibility(View.GONE);
                        getWindow().setStatusBarColor(BG);
                        getWindow().setNavigationBarColor(BG);
                        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                    }
                });
            }
        }, 1200);
        return s;
    }

    private View buildBanner() {
        LinearLayout b = row();
        b.setBackground(shape(DANGER_SOFT, 14, 0));
        b.setPadding(dp(14), dp(10), dp(8), dp(10));
        alertText = text("", 14, DANGER, true);
        b.addView(alertText, grow());
        TextView close = text("Tutup", 13, DANGER, false);
        close.setPadding(dp(8), dp(4), dp(8), dp(4));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { alertBanner.setVisibility(View.GONE); }
        });
        b.addView(close);
        b.setVisibility(View.GONE);
        return b;
    }

    // ----------------------------------------------------------- refresh

    private void refreshAll() {
        myAvatar.setImageBitmap(Avatars.circle(myName, myPhoto, dp(44)));
        boolean inGroup = hub != null;
        nearbyCard.setVisibility(inGroup ? View.GONE : View.VISIBLE);
        groupCard.setVisibility(inGroup ? View.VISIBLE : View.GONE);
        if (!inGroup) setStatus("Belum terhubung", SUB);
        refreshMembers();
        refreshTalkers();
        refreshControls();
        refreshChips();
        refreshSos();
    }

    private void setStatus(String s, int color) {
        statusText.setText(s);
        statusDot.setBackground(oval(color));
    }

    private void refreshPeers() {
        peerList.removeAllViews();
        boolean any = !awarePeers.isEmpty() || !lanPeers.isEmpty() || !peers.isEmpty() || !btPeers.isEmpty();
        emptyPeers.setVisibility(any ? View.GONE : View.VISIBLE);
        for (final Aware.Peer ap : awarePeers.values()) {
            addPeerRow(ap.name, "Wi‑Fi Aware · Koneksi otomatis cepat", new View.OnClickListener() {
                @Override public void onClick(View v) { connectAware(ap); }
            });
        }
        for (final Lan.Peer lp : lanPeers.values()) {
            if (awarePeers.containsKey(lp.name)) continue;
            addPeerRow(lp.name, lp.inGroup
                    ? "Hotspot/Wi‑Fi Lokal · Grup aktif"
                    : "Hotspot/Wi‑Fi Lokal · Siap terhubung", new View.OnClickListener() {
                @Override public void onClick(View v) { connectLan(lp); }
            });
        }
        for (final WifiP2pDevice d : peers) {
            String n = TextUtils.isEmpty(d.deviceName) ? d.deviceAddress : d.deviceName;
            addPeerRow(n, statusText(d.status), new View.OnClickListener() {
                @Override public void onClick(View v) { connect(d); }
            });
        }
        for (Map.Entry<String, android.bluetooth.BluetoothDevice> e : btPeers.entrySet()) {
            final android.bluetooth.BluetoothDevice dev = e.getValue();
            final String n = btNames.get(e.getKey());
            if (n == null || awarePeers.containsKey(n) || lanPeers.containsKey(n)) continue;
            final Integer psmObj = btPsms.get(e.getKey());
            final int psm = psmObj != null ? psmObj : 0;
            addPeerRow(n, "Bluetooth Mesh · Growth Internal", new View.OnClickListener() {
                @Override public void onClick(View v) { connectBt(dev, psm, n); }
            });
        }
    }

    private void addPeerRow(String name, String subtitle, View.OnClickListener onClick) {
        LinearLayout r = row();
        r.setPadding(dp(4), dp(10), dp(4), dp(10));
        r.setBackground(pressable(shape(CARD, 12, 0)));
        r.addView(avatar(name, null, 36));
        LinearLayout c = column();
        c.setPadding(dp(12), 0, 0, 0);
        c.addView(text(name, 15, TEXT, true));
        c.addView(text(subtitle, 12, SUB, false));
        r.addView(c, grow());
        r.addView(text("Hubungkan", 14, ACCENT, true));
        r.setOnClickListener(onClick);
        peerList.addView(r, fill());
    }

    private void refreshMembers() {
        memberRow.removeAllViews();
        memberStrip.removeAllViews();
        addMemberChip("Kamu", myName, myPhoto, false, buildPresence());
        addMiniAvatar(myName, myPhoto, false, buildPresence());
        int shown = 1;
        for (Map.Entry<String, byte[]> e : members.entrySet()) {
            boolean talking = talkers.contains(e.getKey());
            Presence pr = presences.get(e.getKey());
            addMemberChip(e.getKey(), e.getKey(), e.getValue(), talking, pr);
            if (shown < 5) addMiniAvatar(e.getKey(), e.getValue(), talking, pr);
            shown++;
        }
        if (shown > 5) {
            TextView more = text("+" + (shown - 5), 12, SUB, true);
            LinearLayout.LayoutParams l = wrap();
            l.leftMargin = dp(4);
            memberStrip.addView(more, l);
        }
        groupTitle.setText("Tim · " + shown);
    }

    /** Small face for the folded group card; the ring shows status, green while talking. */
    private void addMiniAvatar(String name, byte[] photo, boolean talking, Presence pr) {
        FrameLayout ring = new FrameLayout(this);
        int pad = dp(2);
        ring.setPadding(pad, pad, pad, pad);
        int status = pr == null ? Presence.UNSET : pr.status;
        int color = talking ? LEAF : status == Presence.UNSET ? LINE : statusColor(status);
        ring.setBackground(oval(color));
        ImageView a = avatar(name, photo, 26);
        a.setLayoutParams(new FrameLayout.LayoutParams(dp(26), dp(26)));
        ring.addView(a);
        LinearLayout.LayoutParams l = wrap();
        l.rightMargin = dp(4);
        memberStrip.addView(ring, l);
    }

    private void setGroupOpen(boolean open) {
        groupOpen = open;
        memberScroll.setVisibility(open ? View.VISIBLE : View.GONE);
        memberStrip.setVisibility(open ? View.INVISIBLE : View.VISIBLE);
        groupChevron.setRotation(open ? 180 : 0);
        prefs.edit().putBoolean("group_open", open).apply();
    }

    private static int statusColor(int status) {
        switch (status) {
            case Presence.SAFE: return ACCENT;
            case Presence.NEED_HELP: return AMBER;
            case Presence.INJURED:
            case Presence.SOS: return DANGER;
            default: return SUB;
        }
    }

    private void addMemberChip(String label, String name, byte[] photo, boolean talking, Presence pr) {
        boolean sos = pr != null && pr.status == Presence.SOS;
        LinearLayout c = column();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout ring = new FrameLayout(this);
        int pad = dp(3);
        ring.setPadding(pad, pad, pad, pad);
        ring.setBackground(oval(sos ? DANGER : talking ? LEAF : Color.TRANSPARENT));
        ImageView a = avatar(name, photo, 48);
        a.setLayoutParams(new FrameLayout.LayoutParams(dp(48), dp(48)));
        ring.addView(a);
        c.addView(ring, wrap());
        TextView t = text(label, 12, talking ? ACCENT : SUB, talking);
        t.setMaxWidth(dp(76));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(t, gapTop(wrap(), 4));
        if (pr != null) {
            String info = Presence.label(pr.status);
            if (pr.battery >= 0) info += " · " + pr.battery + "%";
            TextView st = text(info, 11, statusColor(pr.status), false);
            st.setSingleLine(true);
            c.addView(st, wrap());
        }
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCompass(); }
        });
        LinearLayout.LayoutParams l = wrap();
        l.rightMargin = dp(16);
        memberRow.addView(c, l);
    }

    private void refreshTalkers() {
        if (talkers.isEmpty()) {
            talkBanner.setVisibility(View.GONE);
            return;
        }
        String first = talkers.iterator().next();
        talkAvatar.setImageBitmap(Avatars.circle(first, members.get(first), dp(40)));
        talkText.setText(talkers.size() == 1 ? first + " sedang bicara"
                : first + " dan " + (talkers.size() - 1) + " lainnya sedang bicara");
        talkBanner.setVisibility(View.VISIBLE);
    }

    private void refreshControls() {
        boolean talking = voice.isCapturing();
        pttButton.setBackground(pressable(oval(callMode ? LINE : talking ? DANGER : ACCENT)));
        callButton.setBackground(pressable(oval(callMode ? DANGER : CARD)));
        callIcon.setImageResource(callMode ? R.drawable.ic_call_end : R.drawable.ic_call);
        callIcon.setColorFilter(callMode ? Color.WHITE : ACCENT, PorterDuff.Mode.SRC_IN);
        speakerIcon.setImageResource(speakerOn ? R.drawable.ic_volume : R.drawable.ic_volume_off);
        speakerIcon.setColorFilter(speakerOn ? ACCENT : SUB, PorterDuff.Mode.SRC_IN);
        if (callMode) pttLabel.setText("Mode telepon aktif · ketuk merah untuk menutup");
        else if (talking) pttLabel.setText("Sedang bicara, lepas untuk berhenti");
        else pttLabel.setText("Tahan untuk bicara");
    }

    private void addBubble(String from, String body, boolean mine) {
        LinearLayout wrapRow = row();
        wrapRow.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout b = column();
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(shape(mine ? ACCENT : CARD, 16, mine ? 0 : LINE));
        if (!mine) b.addView(text(from, 12, ACCENT, true));
        TextView t = text(body, 15, mine ? Color.WHITE : TEXT, false);
        t.setTextIsSelectable(true);
        b.addView(t);
        TextView time = text(now(), 11, mine ? 0xCCFFFFFF : SUB, false);
        time.setGravity(Gravity.END);
        b.addView(time, fill());
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.72f);
        t.setMaxWidth(maxW);
        wrapRow.addView(b, wrap());
        addChatView(wrapRow);
    }

    private void addImageBubble(String from, final byte[] jpeg, Bitmap thumb, boolean mine) {
        if (thumb == null) return;
        LinearLayout wrapRow = row();
        wrapRow.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout b = column();
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setBackground(shape(mine ? ACCENT : CARD, 16, mine ? 0 : LINE));
        if (!mine) {
            TextView name = text(from, 12, ACCENT, true);
            name.setPadding(dp(8), dp(2), dp(8), dp(4));
            b.addView(name);
        }
        ImageView img = new ImageView(this);
        img.setImageBitmap(thumb);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(shape(LINE, 12, 0));
        img.setClipToOutline(true);
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.62f);
        int h = Math.min(w, Math.round(w * (float) thumb.getHeight() / thumb.getWidth()));
        img.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPhoto(jpeg); }
        });
        b.addView(img, lp(w, h));
        TextView time = text(now(), 11, mine ? 0xCCFFFFFF : SUB, false);
        time.setGravity(Gravity.END);
        time.setPadding(dp(8), dp(4), dp(8), 0);
        b.addView(time, fill());
        wrapRow.addView(b, wrap());
        addChatView(wrapRow);
    }

    private void showPhoto(final byte[] jpeg) {
        final android.app.Dialog d = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackgroundColor(Color.BLACK);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { d.dismiss(); }
        });
        d.setContentView(v);
        d.show();
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap full = Photos.decode(jpeg, 2048);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (full == null) d.dismiss();
                        else v.setImageBitmap(full);
                    }
                });
            }
        }, "photo-view").start();
    }

    private void askPhotoSource() {
        if (!ready()) return;
        new AlertDialog.Builder(this)
                .setTitle("Kirim ke grup")
                .setItems(new String[] {"Ambil foto dengan kamera",
                                "Rekam video (maks. " + VIDEO_SECONDS + " detik)",
                                "Rekam pesan suara (Voice Note maks. 10 detik)",
                                "Pilih foto dari galeri", "Pilih video dari galeri"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                if (which == 0) takePhoto();
                                else if (which == 1) recordVideo();
                                else if (which == 2) recordVoiceNote();
                                else if (which == 3) pickPhotoToSend();
                                else pickVideoToSend();
                            }
                        })
                .show();
    }

    private void recordVoiceNote() {
        if (!ready()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions();
            return;
        }
        if (voice.isCapturing()) {
            toast("Mikrofon sedang dipakai");
            return;
        }
        final java.io.ByteArrayOutputStream pcmBuf = new java.io.ByteArrayOutputStream();
        try {
            voice.startCapture(new Voice.Sink() {
                @Override public void onPcm(byte[] pcm) {
                    synchronized (pcmBuf) {
                        if (pcmBuf.size() < Voice.RATE * 2 * 10) {
                            pcmBuf.write(pcm, 0, pcm.length);
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            toast(e.getMessage());
            return;
        }
        final AlertDialog[] holder = new AlertDialog[1];
        final Runnable finishSend = new Runnable() {
            private boolean done;
            @Override public void run() {
                if (done) return;
                done = true;
                voice.stopCapture();
                if (holder[0] != null && holder[0].isShowing()) holder[0].dismiss();
                byte[] pcm;
                synchronized (pcmBuf) { pcm = pcmBuf.toByteArray(); }
                if (pcm.length < Voice.FRAME_BYTES * 5 || hub == null) return;
                byte[] adpcm = Adpcm.encode(pcm);
                hub.sendVoiceNote(adpcm);
                addVoiceNoteBubble(myName, adpcm, true);
            }
        };
        holder[0] = new AlertDialog.Builder(this)
                .setTitle("Merekam Pesan Suara…")
                .setMessage("Bicara sekarang (maksimal 10 detik). Pesan suara otomatis tersimpan & diteruskan ke anggota yang baru bergabung.")
                .setCancelable(false)
                .setPositiveButton("Kirim", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ui.removeCallbacks(finishSend);
                        finishSend.run();
                    }
                })
                .setNegativeButton("Batal", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ui.removeCallbacks(finishSend);
                        voice.stopCapture();
                    }
                })
                .show();
        ui.postDelayed(finishSend, 10000L);
    }

    private void addVoiceNoteBubble(final String from, final byte[] adpcm, boolean mine) {
        if (adpcm == null || adpcm.length <= 4) return;
        int secs = Math.max(1, ((adpcm.length - 4) * 2) / Voice.RATE);
        LinearLayout wrapRow = row();
        wrapRow.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout b = column();
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(pressable(shape(mine ? ACCENT : CARD, 16, mine ? 0 : LINE)));
        if (!mine) b.addView(text(from, 12, ACCENT, true));
        TextView playLabel = text("▶ Putar Pesan Suara (" + secs + " dtk)", 15,
                mine ? Color.WHITE : ACCENT, true);
        b.addView(playLabel);
        TextView time = text("Voice Note · " + now(), 11, mine ? 0xCCFFFFFF : SUB, false);
        time.setGravity(Gravity.END);
        b.addView(time, fill());
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        byte[] pcm = Adpcm.decode(adpcm);
                        if (pcm.length > 0) voice.play("vn:" + from, pcm);
                    }
                }, "vn-play").start();
            }
        });
        wrapRow.addView(b, wrap());
        addChatView(wrapRow);
    }

    /** The camera app writes a full-size photo into our CaptureProvider; no camera permission needed. */
    private void takePhoto() {
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, CaptureProvider.PHOTO);
        i.setClipData(ClipData.newRawUri("", CaptureProvider.PHOTO));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (RuntimeException e) {
            toast("Tidak ada aplikasi kamera");
        }
    }

    private void pickPhotoToSend() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Pilih foto"), REQ_SEND_PHOTO);
        } catch (RuntimeException e) {
            toast("Tidak ada aplikasi galeri");
        }
    }

    /** Asks the camera app for a short, low-quality clip written into our CaptureProvider. */
    private void recordVideo() {
        CaptureProvider.videoFile(this).delete();
        Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        i.putExtra(MediaStore.EXTRA_DURATION_LIMIT, VIDEO_SECONDS);
        i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);
        i.putExtra(MediaStore.EXTRA_SIZE_LIMIT, (long) Clips.MAX_BYTES);
        i.putExtra(MediaStore.EXTRA_OUTPUT, CaptureProvider.VIDEO);
        i.setClipData(ClipData.newRawUri("", CaptureProvider.VIDEO));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_VIDEO);
        } catch (RuntimeException e) {
            toast("Tidak ada aplikasi kamera");
        }
    }

    private void pickVideoToSend() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("video/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Pilih video"), REQ_SEND_VIDEO);
        } catch (RuntimeException e) {
            toast("Tidak ada aplikasi galeri");
        }
    }

    /** Reads the clip off the UI thread, refuses anything over the limit, then sends it in chunks. */
    private void sendVideo(final Uri uri) {
        toast("Menyiapkan video…");
        new Thread(new Runnable() {
            @Override public void run() {
                String problem = null;
                try {
                    final byte[] clip = readLimited(uri, Clips.MAX_BYTES);
                    if (clip == null) {
                        problem = "Video terlalu besar (maks. 4 MB). Rekam lebih pendek.";
                    } else if (clip.length == 0) {
                        problem = "Video kosong";
                    } else if (hub == null || !hub.hasPeers()) {
                        problem = "Belum terhubung ke HP lain";
                    } else {
                        hub.sendVideo(clip);
                        final java.io.File f = saveClip(clip);
                        final Bitmap thumb = videoThumb(f);
                        ui.post(new Runnable() {
                            @Override public void run() { addVideoBubble(myName, f, thumb, true); }
                        });
                    }
                } catch (Exception e) {
                    problem = "Video tidak bisa dibuka";
                }
                if (problem != null) {
                    final String msg = problem;
                    ui.post(new Runnable() {
                        @Override public void run() { toast(msg); }
                    });
                }
            }
        }, "video-prepare").start();
    }

    /** Returns the content's bytes, or null if it is larger than max. */
    private byte[] readLimited(Uri uri, int max) throws java.io.IOException {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("no stream");
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > max) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private java.io.File videoDir() {
        java.io.File d = new java.io.File(getCacheDir(), "videos");
        d.mkdirs();
        return d;
    }

    /** Clips live in the cache only for this session, like the rest of the chat. */
    private synchronized java.io.File saveClip(byte[] clip) throws java.io.IOException {
        java.io.File f = new java.io.File(videoDir(),
                "clip-" + System.currentTimeMillis() + "-" + (clipCount++) + ".mp4");
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(clip);
        } finally {
            out.close();
        }
        return f;
    }

    private int clipCount;

    private void clearClips() {
        java.io.File[] old = videoDir().listFiles();
        if (old != null) for (java.io.File f : old) f.delete();
    }

    private static Bitmap videoThumb(java.io.File clip) {
        try {
            return android.media.ThumbnailUtils.createVideoThumbnail(clip.getPath(),
                    MediaStore.Images.Thumbnails.MINI_KIND);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void addVideoBubble(String from, final java.io.File clip, Bitmap thumb, boolean mine) {
        LinearLayout wrapRow = row();
        wrapRow.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout b = column();
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setBackground(shape(mine ? ACCENT : CARD, 16, mine ? 0 : LINE));
        if (!mine) {
            TextView name = text(from, 12, ACCENT, true);
            name.setPadding(dp(8), dp(2), dp(8), dp(4));
            b.addView(name);
        }
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.62f);
        int h = w * 9 / 16;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(shape(0xFF1F2328, 12, 0));
        frame.setClipToOutline(true);
        if (thumb != null) {
            ImageView img = new ImageView(this);
            img.setImageBitmap(thumb);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            h = Math.min(w * 4 / 3, Math.round(w * (float) thumb.getHeight() / thumb.getWidth()));
        }
        FrameLayout play = new FrameLayout(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0x99000000);
        play.setBackground(dot);
        play.addView(icon(R.drawable.ic_play, Color.WHITE, 28));
        frame.addView(play, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        frame.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playVideo(clip); }
        });
        b.addView(frame, lp(w, h));
        TextView time = text("Video · " + now(), 11, mine ? 0xCCFFFFFF : SUB, false);
        time.setGravity(Gravity.END);
        time.setPadding(dp(8), dp(4), dp(8), 0);
        b.addView(time, fill());
        wrapRow.addView(b, wrap());
        addChatView(wrapRow);
    }

    private void playVideo(java.io.File clip) {
        final android.app.Dialog d = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        final android.widget.VideoView v = new android.widget.VideoView(this);
        box.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        android.widget.MediaController mc = new android.widget.MediaController(this);
        mc.setAnchorView(v);
        v.setMediaController(mc);
        v.setVideoPath(clip.getPath());
        v.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            @Override public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                toast("Video tidak bisa diputar");
                d.dismiss();
                return true;
            }
        });
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { d.dismiss(); }
        });
        d.setContentView(box);
        d.show();
        v.start();
    }

    /** Shrinks the photo off the UI thread, then sends it and shows it in the chat. */
    private void sendPhoto(final Uri uri) {
        toast("Menyiapkan foto…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final byte[] jpeg = Photos.prepare(getContentResolver(), uri);
                    final Bitmap thumb = Photos.decode(jpeg, 720);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (hub == null || !hub.hasPeers()) {
                                toast("Belum terhubung ke HP lain");
                                return;
                            }
                            hub.sendImage(jpeg);
                            addImageBubble(myName, jpeg, thumb, true);
                        }
                    });
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { toast("Foto tidak bisa dibuka"); }
                    });
                }
            }
        }, "photo-prepare").start();
    }

    private void addSystemMessage(String s) {
        TextView t = text(s, 12, SUB, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), dp(6), dp(24), dp(6));
        addChatView(t);
    }

    private void addChatView(View v) {
        LinearLayout.LayoutParams l = fill();
        l.topMargin = dp(6);
        chatList.addView(v, l);
        chatScroll.post(new Runnable() {
            @Override public void run() { chatScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Someone dropped out: banner + vibration + tone, so it is noticed without looking. */
    private void alertLost(String message) {
        alertText.setText(message);
        alertBanner.setVisibility(View.VISIBLE);
        addSystemMessage(message);
        vibrate(new long[] {0, 300, 150, 300});
        beep(AudioManager.STREAM_NOTIFICATION, ToneGenerator.TONE_PROP_NACK, 600);
    }

    // ------------------------------------------------------ disaster mode

    private void refreshChips() {
        boolean sos = me.status == Presence.SOS;
        sosChip.setText(sos ? "Akhiri Prioritas" : "Prioritas");
        int st = sos ? statusBeforeSos : me.status;
        statusChip.setText(st == Presence.UNSET ? "Atur status" : "Status: " + Presence.label(st));
        statusChip.setTextColor(statusColor(sos ? statusBeforeSos : me.status));
    }

    private Presence buildPresence() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            me.battery = level < 0 || scale <= 0 ? -1 : level * 100 / scale;
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            me.charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                    || st == BatteryManager.BATTERY_STATUS_FULL;
        }
        me.hasLocation = false;
        if (myLocation != null && shareLocation()) {
            me.hasLocation = true;
            me.lat = myLocation.getLatitude();
            me.lon = myLocation.getLongitude();
            me.accuracy = myLocation.getAccuracy();
        }
        return me;
    }

    private boolean activeGroupOwner() {
        return hub != null && isOwner;
    }

    private int groupMemberCount() {
        return hub == null ? 0 : members.size() + 1;
    }

    private void sendPresence() {
        Presence p = buildPresence();
        if (hub != null) hub.sendPresence(p.encode());
        int port = hub != null ? hub.port() : Hub.PORT;
        boolean ownerActive = activeGroupOwner();
        int count = groupMemberCount();
        if (ble != null && neededPermissions().length == 0) {
            ble.updateAdvertise(myName, p, ownerActive, bt != null ? bt.l2capPsm() : 0);
            if (p.status == Presence.SOS) {
                ble.broadcastMeshPresence(myName, p);
            }
        }
        if (aware != null && neededPermissions().length == 0) {
            aware.updatePublish(myName, port, ownerActive, count);
        }
        if (lan != null) {
            lan.announce(myName, port, ownerActive, count);
        }
    }

    private void setMyStatus(int status) {
        me.status = status;
        prefs.edit().putInt("status", status).apply();
        sendPresence();
        updateKeepAlive();
        refreshChips();
        refreshMembers();
    }

    private void toggleSos() {
        if (me.status == Presence.SOS) {
            setMyStatus(statusBeforeSos == Presence.UNSET ? Presence.NEED_HELP : statusBeforeSos);
            addSystemMessage("Panggilan prioritas diakhiri.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Kirim Panggilan Prioritas?")
                .setMessage("Semua HP Tim akan membunyikan dering perhatian dan menampilkan arah posisimu sampai panggilan diakhiri.\n\n"
                        + "Prioritas + SMS juga menyiapkan SMS berisi koordinat posisimu ke nomor PIC / Basecamp. "
                        + "SMS butuh sinyal operator, tapi tidak butuh kuota internet.")
                .setPositiveButton("Kirim Prioritas", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        statusBeforeSos = me.status;
                        setMyStatus(Presence.SOS);
                        addSystemMessage(hub != null && hub.hasPeers()
                                ? "Panggilan prioritas terkirim ke seluruh Tim."
                                : "Panggilan prioritas aktif. Akan terkirim begitu tersambung ke HP Tim.");
                    }
                })
                .setNeutralButton("Prioritas + SMS", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        statusBeforeSos = me.status;
                        setMyStatus(Presence.SOS);
                        sendSosSms();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private List<String> emergencyContacts() {
        return EmergencySms.parseNumbers(prefs.getString("sms_contacts", ""));
    }

    /** SMS goes over the carrier network: useful when towers still work but there is no data. */
    private void sendSosSms() {
        final List<String> to = emergencyContacts();
        if (to.isEmpty()) {
            askEmergencyContacts(new Runnable() {
                @Override public void run() { if (!emergencyContacts().isEmpty()) sendSosSms(); }
            });
            return;
        }
        Presence p = buildPresence();
        boolean has = myLocation != null;
        long age = has ? (System.currentTimeMillis() - myLocation.getTime()) / 60000 : -1;
        String body = EmergencySms.body(myName, has,
                has ? myLocation.getLatitude() : 0, has ? myLocation.getLongitude() : 0,
                has ? myLocation.getAccuracy() : 0, age, p.battery);
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + EmergencySms.join(to, ";")));
        i.putExtra("sms_body", body);
        try {
            startActivity(i);
            addSystemMessage("Laporan prioritas disiapkan di aplikasi SMS. Tekan kirim di sana. "
                    + "SMS hanya terkirim jika masih ada sinyal operator.");
        } catch (RuntimeException e) {
            toast("Tidak ada aplikasi SMS");
        }
    }

    private void askEmergencyContacts(final Runnable then) {
        final EditText e = new EditText(this);
        e.setHint("Contoh: 0812xxxx, 0813xxxx");
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setText(EmergencySms.join(emergencyContacts(), ", "));
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("Kontak PIC / Basecamp (maks. " + EmergencySms.MAX_CONTACTS + ")")
                .setMessage("Nomor PIC atau Basecamp yang menerima SMS berisi koordinat posisimu saat butuh supporting/backup prioritas. "
                        + "Pisahkan dengan koma.")
                .setView(box)
                .setPositiveButton("Simpan", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        List<String> n = EmergencySms.parseNumbers(e.getText().toString());
                        prefs.edit().putString("sms_contacts", EmergencySms.join(n, ",")).apply();
                        toast(n.isEmpty() ? "Kontak PIC dikosongkan" : n.size() + " kontak PIC disimpan");
                        if (then != null) then.run();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void askStatus() {
        final int[] values = {Presence.SAFE, Presence.NEED_HELP, Presence.INJURED};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = Presence.label(values[i]);
        new AlertDialog.Builder(this)
                .setTitle("Status tugas lapangan")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (me.status == Presence.SOS) {
                            statusBeforeSos = values[which];
                            refreshChips();
                        } else {
                            setMyStatus(values[which]);
                        }
                    }
                })
                .show();
    }

    private void askAnnouncement() {
        if (!ready()) return;
        final EditText e = new EditText(this);
        e.setHint("Contoh: Rotasi pos 3 dimulai 10 menit lagi, peserta menuju Main Hall");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("Instruksi ke seluruh Tim")
                .setView(box)
                .setPositiveButton("Kirim", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String t = e.getText().toString().trim();
                        if (t.length() == 0 || hub == null) return;
                        if (t.length() > 1000) t = t.substring(0, 1000);
                        hub.sendAnnouncement(t);
                        if (ble != null) ble.broadcastMeshAnnounce(myName, t);
                        addAnnouncement("Kamu", t);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void addAnnouncement(String from, String body) {
        LinearLayout c = column();
        c.setBackground(shape(AMBER_SOFT, 16, 0));
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        c.addView(text("INSTRUKSI · " + from + " · " + now(), 11, AMBER_TEXT, true));
        TextView t = text(body, 16, TEXT, true);
        t.setTextIsSelectable(true);
        c.addView(t, gapTop(fill(), 2));
        addChatView(c);
    }

    private static final String[] DIRECTIONS = {
            "utara", "timur laut", "timur", "tenggara", "selatan", "barat daya", "barat", "barat laut"};

    private String whereIs(Presence p) {
        if (p == null || !p.hasLocation) return "Lokasi belum tersedia";
        if (myLocation == null) {
            return String.format(Locale.US, "Lokasi %.5f, %.5f", p.lat, p.lon);
        }
        float[] r = new float[2];
        Location.distanceBetween(myLocation.getLatitude(), myLocation.getLongitude(), p.lat, p.lon, r);
        int dir = Math.round(((r[1] + 360) % 360) / 45f) % 8;
        return RadarView.formatDistance(r[0]) + " ke arah " + DIRECTIONS[dir];
    }

    private void refreshSos() {
        List<String> sos = new ArrayList<String>();
        boolean unsilenced = false;
        for (Map.Entry<String, Presence> e : presences.entrySet()) {
            if (e.getValue().status != Presence.SOS
                    || (!members.containsKey(e.getKey()) && !bleSosNames.contains(e.getKey()))) continue;
            sos.add(e.getKey());
            if (!sosSilenced.contains(e.getKey())) unsilenced = true;
        }
        if (sos.isEmpty()) {
            sosCard.setVisibility(View.GONE);
            stopAlarm();
            return;
        }
        String first = sos.get(0);
        Presence p = presences.get(first);
        sosAvatar.setImageBitmap(Avatars.circle(first, members.get(first), dp(44)));
        sosTitle.setText(sos.size() == 1 ? first + " butuh supporting/backup segera!"
                : first + " dan " + (sos.size() - 1) + " rekan Tim butuh supporting/backup!");
        String detail = whereIs(p);
        if (p.battery >= 0) detail += " · baterai " + p.battery + "%";
        sosDetail.setText(detail);
        sosSilence.setVisibility(unsilenced ? View.VISIBLE : View.GONE);
        sosCard.setVisibility(View.VISIBLE);
        if (unsilenced) startAlarm(); else stopAlarm();
    }

    private void startAlarm() {
        if (alarmOn) return;
        alarmOn = true;
        ui.post(alarmLoop);
    }

    private void stopAlarm() {
        if (!alarmOn) return;
        alarmOn = false;
        ui.removeCallbacks(alarmLoop);
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.cancel();
    }

    private void vibrate(long[] pattern) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) v.vibrate(pattern, -1);
    }

    private void beep(int stream, int tone, final int ms) {
        try {
            final ToneGenerator tg = new ToneGenerator(stream, 100);
            tg.startTone(tone, ms);
            ui.postDelayed(new Runnable() {
                @Override public void run() { tg.release(); }
            }, ms + 300);
        } catch (RuntimeException ignored) {
            // Some devices refuse tone generators; vibration still works.
        }
    }

    private boolean shareLocation() {
        return prefs.getBoolean("shareLocation", true);
    }

    private void setShareLocation(boolean on) {
        prefs.edit().putBoolean("shareLocation", on).apply();
        if (on) {
            startLocation();
            addSystemMessage("Lokasi dibagikan lagi ke anggota grup.");
        } else {
            if (locations != null && locationListener != null) locations.removeUpdates(locationListener);
            locations = null;
            myLocation = null;
            addSystemMessage("Lokasi tidak dibagikan. GPS dimatikan untuk hemat baterai.");
        }
        sendPresence();
        refreshAll();
        refreshCompass();
    }

    private void startLocation() {
        if (locations != null || !shareLocation()) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locations == null) return;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                boolean first = myLocation == null;
                myLocation = l;
                if (first) sendPresence();
                refreshSos();
                refreshCompass();
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        try {
            // GPS needs no internet; it only needs a view of the sky.
            Location last = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) myLocation = last;
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 5, locationListener);
        } catch (RuntimeException e) {
            addSystemMessage("GPS tidak tersedia di HP ini.");
        }
    }

    // ----------------------------------------------------------- offline map

    private File tileRoot() {
        return new File(getFilesDir(), "tiles");
    }

    private View buildMap() {
        LinearLayout page = column();
        page.setBackgroundColor(BG);
        page.setClickable(true);
        page.setPadding(dp(14), dp(8), dp(14), dp(12));
        LinearLayout head = row();
        LinearLayout titles = column();
        titles.addView(text("Peta offline", 20, TEXT, true));
        titles.addView(text("Posisi Tim yang membagikan lokasi", 13, SUB, false));
        head.addView(titles, grow());
        TextView close = text("Tutup", 14, ACCENT, true);
        close.setPadding(dp(14), dp(8), dp(14), dp(8));
        close.setBackground(pressable(shape(ACCENT_SOFT, 18, 0)));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mapPage.setVisibility(View.GONE); }
        });
        head.addView(close);
        page.addView(head, fill());

        FrameLayout box = new FrameLayout(this);
        box.setBackground(shape(CARD, 18, LINE));
        box.setClipToOutline(true);
        mapView = new MapView(this, tileRoot(), TEXT, SUB, ACCENT);
        box.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout zoom = column();
        zoom.addView(mapButton("+", new View.OnClickListener() {
            @Override public void onClick(View v) { mapView.zoomBy(1); }
        }));
        zoom.addView(mapButton("−", new View.OnClickListener() {
            @Override public void onClick(View v) { mapView.zoomBy(-1); }
        }), gapTop(lp(dp(42), dp(42)), 8));
        zoom.addView(mapButton("◎", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (myLocation != null) mapView.centerOn(myLocation.getLatitude(), myLocation.getLongitude());
                else toast("Posisimu belum diketahui. Tunggu GPS di tempat terbuka.");
            }
        }), gapTop(lp(dp(42), dp(42)), 8));
        FrameLayout.LayoutParams zl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.TOP);
        zl.setMargins(0, dp(10), dp(10), 0);
        box.addView(zoom, zl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        bl.topMargin = dp(10);
        page.addView(box, bl);

        LinearLayout foot = row();
        mapStatus = text("", 13, SUB, false);
        foot.addView(mapStatus, grow());
        mapDownload = text("Unduh peta sekitar", 14, Color.WHITE, true);
        mapDownload.setPadding(dp(14), dp(9), dp(14), dp(9));
        mapDownload.setBackground(pressable(shape(ACCENT, 18, 0)));
        mapDownload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askDownloadMap(); }
        });
        LinearLayout.LayoutParams dl = wrap();
        dl.leftMargin = dp(10);
        foot.addView(mapDownload, dl);
        page.addView(foot, gapTop(fill(), 10));
        page.setVisibility(View.GONE);
        return page;
    }

    private View mapButton(String label, View.OnClickListener l) {
        TextView b = text(label, 20, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pressable(ovalStroke(CARD, LINE)));
        b.setElevation(dp(2));
        b.setOnClickListener(l);
        b.setLayoutParams(lp(dp(42), dp(42)));
        return b;
    }

    private void showMap() {
        startLocation();
        mapPage.setVisibility(View.VISIBLE);
        refreshMap();
        refreshMapStatus();
    }

    private void refreshMap() {
        if (mapPage == null || mapPage.getVisibility() != View.VISIBLE) return;
        List<RadarView.Target> targets = new ArrayList<RadarView.Target>();
        for (Map.Entry<String, byte[]> e : members.entrySet()) {
            Presence p = presences.get(e.getKey());
            if (p == null || !p.hasLocation) continue;
            targets.add(new RadarView.Target(e.getKey(), Avatars.circle(e.getKey(), e.getValue(), dp(38)),
                    statusColor(p.status), p.lat, p.lon));
        }
        for (String n : bleSosNames) {
            if (members.containsKey(n)) continue;
            Presence p = presences.get(n);
            if (p == null || !p.hasLocation) continue;
            targets.add(new RadarView.Target(n, Avatars.circle(n, null, dp(38)),
                    statusColor(p.status), p.lat, p.lon));
        }
        mapView.setData(shareLocation() ? myLocation : null, targets);
    }

    private void refreshMapStatus() {
        if (mapDownloading) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final int n = Tiles.count(tileRoot());
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!mapDownloading) {
                            mapStatus.setText(n == 0 ? "Belum ada peta tersimpan. Unduh saat masih ada internet."
                                    : "Peta tersimpan: " + n + " petak. Bisa dipakai tanpa internet.");
                        }
                    }
                });
            }
        }, "tile-count").start();
    }

    private void askDownloadMap() {
        if (mapDownloading) return;
        final boolean here = myLocation != null;
        final double lat = here ? myLocation.getLatitude() : mapView.centerLat();
        final double lon = here ? myLocation.getLongitude() : mapView.centerLon();
        final List<int[]> plan = Tiles.plan(lat, lon);
        new AlertDialog.Builder(this)
                .setTitle("Unduh peta sekitar")
                .setMessage("Peta jalan radius ±" + (int) Tiles.RADIUS_KM + " km di sekitar "
                        + (here ? "posisimu" : "tengah peta") + " (" + plan.size() + " petak, sekitar "
                        + Math.max(1, plan.size() * 15 / 1024) + " MB).\n\n"
                        + "Butuh internet, jadi lakukan sekarang selagi ada sinyal. Setelah tersimpan, "
                        + "peta bisa dibuka tanpa internet. Data peta © OpenStreetMap contributors.")
                .setPositiveButton("Unduh", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { downloadMap(plan); }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void downloadMap(final List<int[]> plan) {
        mapDownloading = true;
        mapDownload.setEnabled(false);
        mapDownload.setAlpha(0.5f);
        new Thread(new Runnable() {
            @Override public void run() {
                final int failed = Tiles.download(tileRoot(), plan, new Tiles.Progress() {
                    @Override public boolean onTile(final int done, final int total) {
                        if (done % 5 == 0 || done == total) {
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    mapStatus.setText("Mengunduh peta " + done + "/" + total + "…");
                                    mapView.reload();
                                }
                            });
                        }
                        return !isFinishing();
                    }
                });
                ui.post(new Runnable() {
                    @Override public void run() {
                        mapDownloading = false;
                        mapDownload.setEnabled(true);
                        mapDownload.setAlpha(1f);
                        mapView.reload();
                        refreshMapStatus();
                        if (failed == plan.size()) toast("Peta gagal diunduh. Cek koneksi internet.");
                        else if (failed > 0) toast(failed + " petak gagal diunduh. Coba lagi saat sinyal lebih baik.");
                        else toast("Peta tersimpan untuk dipakai tanpa internet");
                    }
                });
            }
        }, "map-download").start();
    }

    // ----------------------------------------------------------- compass

    private View buildCompass() {
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(BG);
        page.setClickable(true);
        LinearLayout col = column();
        col.setPadding(dp(16), dp(10), dp(16), dp(16));
        LinearLayout head = row();
        LinearLayout titles = column();
        titles.addView(text("Kompas", 22, TEXT, true));
        titles.addView(text("Pegang HP mendatar. Bagian atas = arah kamu menghadap.", 13, SUB, false));
        head.addView(titles, grow());
        TextView close = text("Tutup", 14, ACCENT, true);
        close.setPadding(dp(14), dp(8), dp(14), dp(8));
        close.setBackground(pressable(shape(ACCENT_SOFT, 18, 0)));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideCompass(); }
        });
        head.addView(close);
        col.addView(head, fill());
        radar = new RadarView(this, ACCENT, LINE, SUB, TEXT);
        col.addView(radar, gapTop(fill(), 8));
        compassHint = text("", 13, AMBER_TEXT, false);
        compassHint.setBackground(shape(AMBER_SOFT, 12, 0));
        compassHint.setPadding(dp(12), dp(8), dp(12), dp(8));
        col.addView(compassHint, gapTop(fill(), 4));
        LinearLayout listCard = card();
        compassList = column();
        listCard.addView(compassList, fill());
        col.addView(listCard, gapTop(fill(), 12));
        page.addView(col);
        page.setVisibility(View.GONE);
        return page;
    }

    private void showCompass() {
        startLocation();
        compassPage.setVisibility(View.VISIBLE);
        startHeading();
        refreshCompass();
    }

    private void hideCompass() {
        compassPage.setVisibility(View.GONE);
        stopHeading();
    }

    private void refreshCompass() {
        refreshMap();
        if (compassPage.getVisibility() != View.VISIBLE) return;
        List<RadarView.Target> targets = new ArrayList<RadarView.Target>();
        compassList.removeAllViews();
        compassList.addView(text("Tim Lapangan", 16, TEXT, true));
        if (members.isEmpty()) {
            compassList.addView(text("Belum ada Tim tersambung.", 14, SUB, false), gapTop(fill(), 8));
        }
        for (Map.Entry<String, byte[]> e : members.entrySet()) {
            String n = e.getKey();
            Presence p = presences.get(n);
            int color = p == null ? LINE : statusColor(p.status);
            if (p != null && p.hasLocation) {
                targets.add(new RadarView.Target(n, Avatars.circle(n, e.getValue(), dp(36)),
                        color, p.lat, p.lon));
            }
            LinearLayout r = row();
            r.setPadding(0, dp(10), 0, 0);
            r.addView(avatar(n, e.getValue(), 36));
            LinearLayout c = column();
            c.setPadding(dp(12), 0, 0, 0);
            c.addView(text(n, 15, TEXT, true));
            c.addView(text(whereIs(p), 13, SUB, false));
            r.addView(c, grow());
            if (p != null) r.addView(text(Presence.label(p.status), 13, statusColor(p.status), true));
            compassList.addView(r, fill());
        }
        radar.setData(myLocation, targets);
        if (!shareLocation()) {
            compassHint.setText("Berbagi lokasi sedang mati. Nyalakan lewat foto profilmu di kanan atas.");
            compassHint.setVisibility(View.VISIBLE);
        } else if (myLocation == null) {
            compassHint.setText("Menunggu GPS. Nyalakan Lokasi dan berdiri di tempat terbuka; "
                    + "tanpa internet kunci pertama bisa 1–3 menit.");
            compassHint.setVisibility(View.VISIBLE);
        } else if (headingListener == null) {
            compassHint.setText("HP ini tidak punya sensor kompas. Utara selalu di atas.");
            compassHint.setVisibility(View.VISIBLE);
        } else {
            compassHint.setVisibility(View.GONE);
        }
    }

    private void startHeading() {
        if (headingListener != null) return;
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor rot = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rot == null) return;
        headingListener = new SensorEventListener() {
            private final float[] m = new float[9];
            private final float[] o = new float[3];
            @Override public void onSensorChanged(SensorEvent e) {
                SensorManager.getRotationMatrixFromVector(m, e.values);
                SensorManager.getOrientation(m, o);
                heading = (float) ((Math.toDegrees(o[0]) + 360) % 360);
                radar.setHeading(heading);
            }
            @Override public void onAccuracyChanged(Sensor s, int a) { }
        };
        sensors.registerListener(headingListener, rot, SensorManager.SENSOR_DELAY_UI);
    }

    private void stopHeading() {
        if (sensors != null && headingListener != null) sensors.unregisterListener(headingListener);
        headingListener = null;
    }

    // ----------------------------------------------------------- profile

    private void showProfileMenu() {
        final boolean sharing = shareLocation();
        int contacts = emergencyContacts().size();
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        labels.add("Ganti foto");
        actions.add(new Runnable() { @Override public void run() { pickPhoto(); } });
        labels.add("Ganti nama");
        actions.add(new Runnable() { @Override public void run() { askName(); } });
        labels.add(sharing ? "Bagikan lokasi: Nyala (ketuk untuk mematikan)" : "Bagikan lokasi: Mati (ketuk untuk menyalakan)");
        actions.add(new Runnable() { @Override public void run() { setShareLocation(!sharing); } });
        labels.add(contacts == 0 ? "Kontak PIC SMS: belum diatur" : "Kontak PIC SMS: " + contacts + " nomor");
        actions.add(new Runnable() { @Override public void run() { askEmergencyContacts(null); } });
        labels.add("Bagikan APK Offline (Tanpa Internet)");
        actions.add(new Runnable() { @Override public void run() { showApkShareDialog(); } });
        if (canBridge()) {
            labels.add("Jadi jembatan (percobaan)");
            actions.add(new Runnable() { @Override public void run() { askBridge(); } });
        } else if (bridgeTo != null) {
            labels.add("Berhenti jadi jembatan");
            actions.add(new Runnable() { @Override public void run() { disconnect(); } });
        }
        if (myPhoto != null) {
            labels.add("Hapus foto");
            actions.add(new Runnable() { @Override public void run() { saveProfile(myName, null); } });
        }
        new AlertDialog.Builder(this)
                .setTitle(myName)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { actions.get(which).run(); }
                })
                .show();
    }

    private void showApkShareDialog() {
        final String url = apkShare.start(this);
        String msg = url != null
                ? "Rekan tim di sekitar bisa mengunduh & menginstal aplikasi ini tanpa internet:\n\n"
                        + "1. Sambungkan ke Hotspot / Wi‑Fi Direct HP ini\n"
                        + "2. Buka browser dan ketik:\n   " + url + "\n\n"
                        + "Atau tekan Bagikan File APK untuk mengirim via Bluetooth / Quick Share."
                : "Tekan Bagikan File APK untuk mengirim aplikasi ini ke HP rekan tim via Bluetooth / Quick Share tanpa internet.";
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Bagikan APK Offline")
                .setMessage(msg)
                .setPositiveButton("Bagikan File APK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("application/vnd.android.package-archive");
                        i.putExtra(Intent.EXTRA_STREAM, CaptureProvider.APK);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(Intent.createChooser(i, "Bagikan APK Growth Internal"));
                        } catch (RuntimeException e) {
                            toast("Tidak ada aplikasi pembagi file");
                        }
                    }
                })
                .setNegativeButton("Tutup", null);
        if (url != null && hub != null && hub.hasPeers()) {
            b.setNeutralButton("Kirim Link ke Grup", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String text = "Unduh/Update APK Growth Internal Offline di browser: " + url;
                    hub.sendText(text);
                    addBubble(myName, text, true);
                }
            });
        }
        AlertDialog dlg = b.show();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                apkShare.stop();
            }
        });
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Pilih foto"), REQ_PHOTO);
        } catch (RuntimeException e) {
            toast("Tidak ada aplikasi galeri");
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_CAMERA && result == RESULT_OK) {
            sendPhoto(CaptureProvider.PHOTO);
            return;
        }
        if (req == REQ_SEND_PHOTO && result == RESULT_OK && data != null && data.getData() != null) {
            sendPhoto(data.getData());
            return;
        }
        if (req == REQ_VIDEO && result == RESULT_OK) {
            // Most camera apps honour EXTRA_OUTPUT; some only return their own uri.
            boolean wrote = CaptureProvider.videoFile(this).length() > 0;
            if (wrote) sendVideo(CaptureProvider.VIDEO);
            else if (data != null && data.getData() != null) sendVideo(data.getData());
            else toast("Video tidak tersimpan");
            return;
        }
        if (req == REQ_SEND_VIDEO && result == RESULT_OK && data != null && data.getData() != null) {
            sendVideo(data.getData());
            return;
        }
        if (req != REQ_PHOTO || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final byte[] avatar = Avatars.fromUri(getContentResolver(), uri);
                    ui.post(new Runnable() {
                        @Override public void run() { saveProfile(myName, avatar); }
                    });
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() { toast("Foto tidak bisa dibuka"); }
                    });
                }
            }
        }, "avatar-prepare").start();
    }

    private void askName() {
        final EditText e = new EditText(this);
        e.setText(myName);
        e.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("Nama yang terlihat oleh orang lain")
                .setView(box)
                .setPositiveButton("Simpan", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = e.getText().toString().trim();
                        if (n.length() == 0) return;
                        if (n.length() > 30) n = n.substring(0, 30);
                        saveProfile(n, myPhoto);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static final long AUTO_COOLDOWN_MS = 12000L;
    private final Map<String, Long> autoConnectCooldown = new HashMap<String, Long>();
    private long autoConnectPauseUntil;

    private void saveProfile(String name, byte[] photo) {
        myName = name;
        myPhoto = photo;
        prefs.edit()
                .putString("name", name)
                .putString("photo", photo == null ? null : Base64.encodeToString(photo, Base64.NO_WRAP))
                .apply();
        if (ble != null && neededPermissions().length == 0) {
            ble.updateAdvertise(myName, buildPresence(), activeGroupOwner(),
                    bt != null ? bt.l2capPsm() : 0);
        }
        if (aware != null && neededPermissions().length == 0) {
            aware.updatePublish(myName, hub != null ? hub.port() : Hub.PORT,
                    activeGroupOwner(), groupMemberCount());
        }
        refreshAll();
        if (hub != null) toast("Nama/foto baru terlihat oleh anggota saat terhubung lagi");
    }

    // ------------------------------------------------------- permissions

    private String[] neededPermissions() {
        List<String> p = new ArrayList<String>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            p.add("android.permission.BLUETOOTH_SCAN");
            p.add("android.permission.BLUETOOTH_CONNECT");
            p.add("android.permission.BLUETOOTH_ADVERTISE");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p.add("android.permission.NEARBY_WIFI_DEVICES");
            p.add("android.permission.POST_NOTIFICATIONS");
        }
        List<String> missing = new ArrayList<String>();
        for (String s : p) {
            if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) missing.add(s);
        }
        return missing.toArray(new String[0]);
    }

    private boolean ensurePermissions() {
        String[] missing = neededPermissions();
        if (missing.length == 0) return true;
        requestPermissions(missing, REQ_PERMS);
        return false;
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERMS) return;
        startLocation();
        String[] missing = neededPermissions();
        if (missing.length == 0) {
            startOfflineTransports();
            return;
        }
        addSystemMessage("Izin Lokasi/Perangkat sekitar dan Mikrofon diperlukan agar aplikasi "
                + "bisa menemukan HP lain dan mengirim suara.");
        for (String p : missing) {
            if (!shouldShowRequestPermissionRationale(p)) {
                askOpenSettings();
                break;
            }
        }
    }

    private void askOpenSettings() {
        new AlertDialog.Builder(this)
                .setTitle("Izin diperlukan")
                .setMessage("Izin Lokasi, Perangkat sekitar, atau Mikrofon dinonaktifkan. "
                        + "Buka Pengaturan Aplikasi untuk mengaktifkannya agar bisa mencari dan berbicara dengan Tim.")
                .setPositiveButton("Buka Pengaturan", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null));
                        try { startActivity(i); } catch (RuntimeException ignored) { }
                    }
                })
                .setNegativeButton("Nanti", null)
                .show();
    }

    // ----------------------------------------- multi-transport (Aware, LAN, BT, BLE Mesh)

    private void startOfflineTransports() {
        int port = hub != null ? hub.port() : Hub.PORT;
        boolean ownerActive = activeGroupOwner();
        int count = groupMemberCount();
        if (bt != null) bt.startServer();
        if (ble != null) {
            ble.startScan();
            ble.updateAdvertise(myName, buildPresence(), ownerActive, bt != null ? bt.l2capPsm() : 0);
        }
        if (aware != null) {
            aware.start(myName, port, ownerActive, count);
        }
        if (lan != null) {
            lan.announce(myName, port, ownerActive, count);
        }
        ui.removeCallbacks(presenceTick);
        ui.postDelayed(presenceTick, PRESENCE_EVERY_MS);
    }

    private Hub initHub(boolean ownerMode) {
        if (hub != null) return hub;
        hub = new Hub(myName, myPhoto, this).asOwner(ownerMode);
        isOwner = ownerMode;
        hub.sendPresence(buildPresence().encode());
        ui.removeCallbacks(presenceTick);
        ui.postDelayed(presenceTick, PRESENCE_EVERY_MS);
        updateKeepAlive();
        alertBanner.setVisibility(View.GONE);
        applyAudioRoute();
        return hub;
    }

    private Hub ensureActiveHub() {
        if (hub != null) return hub;
        Hub h = initHub(true);
        h.startServer(null);
        refreshAll();
        return h;
    }

    /**
     * Returns 1 to auto-join peer as client, 2 to auto-start owner hub for peer,
     * or 0 to do nothing. Resolves split-brain by preferring larger groups and
     * breaking ties deterministically by name.
     */
    static int autoConnectDecision(boolean myInGroup, int myMemberCount, String myName,
            boolean peerInGroup, int peerMemberCount, String peerName) {
        if (myName == null || peerName == null || myName.equals(peerName)) return 0;
        if (peerInGroup) {
            if (!myInGroup) return 1;
            if (myMemberCount <= 1) {
                if (peerMemberCount > 1 || myName.compareTo(peerName) > 0) return 1;
            }
            return 0;
        }
        if (!myInGroup) {
            return myName.compareTo(peerName) < 0 ? 2 : 0;
        }
        return 0;
    }

    private boolean canAutoConnect(String key, String peerName) {
        if (key == null || peerName == null || peerName.equals(myName)) return false;
        if (hub != null && hub.hasMember(peerName)) return false;
        long now = System.currentTimeMillis();
        if (now < autoConnectPauseUntil) return false;
        Long last = autoConnectCooldown.get(key);
        if (last != null && now - last < AUTO_COOLDOWN_MS) return false;
        autoConnectCooldown.put(key, now);
        return true;
    }

    private void onAwarePeer(Aware.Peer peer) {
        if (peer == null || peer.name.equals(myName)) return;
        awarePeers.put(peer.name, peer);
        refreshPeers();
        if (hub == null) {
            setStatus(totalDiscoveredPeers() + " perangkat ditemukan", AMBER);
        }
        if (hub != null && hub.hasMember(peer.name)) return;
        int action = autoConnectDecision(hub != null && hub.hasPeers(), groupMemberCount(), myName,
                peer.inGroup, peer.memberCount, peer.name);
        if (action == 1 && canAutoConnect("aware:" + peer.name, peer.name)) {
            connectAware(peer);
        } else if (action == 2 && canAutoConnect("aware-own:" + peer.name, peer.name)) {
            ensureActiveHub();
            sendPresence();
        } else if (!peer.inGroup && myName.compareTo(peer.name) > 0
                && canAutoConnect("aware:" + peer.name, peer.name)) {
            connectAware(peer);
        }
    }

    private void onAwareTargetReady(String peerName, java.net.InetAddress address, int port) {
        if (address == null) return;
        Hub h = ensureActiveHub();
        setStatus("Menyambung ke " + peerName + " (Wi‑Fi Aware)…", AMBER);
        h.connectPeer(address, port, null);
    }

    private void connectAware(Aware.Peer peer) {
        if (peer == null || aware == null || !ensurePermissions()) return;
        autoConnectPauseUntil = 0;
        ensureActiveHub();
        setStatus("Menyiapkan jalur Wi‑Fi Aware ke " + peer.name + "…", AMBER);
        aware.connect(peer);
    }

    private void onLanPeer(Lan.Peer peer) {
        if (peer == null || peer.name.equals(myName)) return;
        lanPeers.put(peer.name, peer);
        refreshPeers();
        if (hub == null) {
            setStatus(totalDiscoveredPeers() + " perangkat ditemukan", AMBER);
        }
        if (hub != null && hub.hasMember(peer.name)) return;
        if (awarePeers.containsKey(peer.name)) return;
        if (activeGroupOwner() && !peer.inGroup && lan != null) {
            lan.announce(myName, hub.port(), true, groupMemberCount());
            return;
        }
        int action = autoConnectDecision(hub != null && hub.hasPeers(), groupMemberCount(), myName,
                peer.inGroup, peer.memberCount, peer.name);
        if (action == 1 && canAutoConnect("lan:" + peer.name, peer.name)) {
            connectLan(peer);
        } else if (action == 2 && canAutoConnect("lan-own:" + peer.name, peer.name)) {
            connectLan(peer);
        }
    }

    private void onBtIncoming(final android.bluetooth.BluetoothSocket socket) {
        final Hub h = ensureActiveHub();
        sendPresence();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    h.addStreamLink(socket.getInputStream(), socket.getOutputStream(), socket, true);
                } catch (java.io.IOException e) {
                    try { socket.close(); } catch (java.io.IOException ignored) {}
                }
            }
        }, "bt-accept").start();
    }

    private void onBleBeacon(Ble.Beacon beacon, android.bluetooth.BluetoothDevice device) {
        if (beacon == null || beacon.name.equals(myName)) return;
        if (device != null && device.getAddress() != null) {
            btPeers.put(device.getAddress(), device);
            btNames.put(device.getAddress(), beacon.name);
            if (beacon.psm > 0) btPsms.put(device.getAddress(), beacon.psm);
            refreshPeers();
        }
        updateMeshPeerState(beacon.name, beacon.presence, beacon.rssi >= Ble.MIN_AUTO_RSSI);
        if (device != null && beacon.rssi >= Ble.MIN_AUTO_RSSI
                && !awarePeers.containsKey(beacon.name)
                && !lanPeers.containsKey(beacon.name)
                && (hub == null || !hub.hasMember(beacon.name))) {
            boolean preferConnect = beacon.inGroup || myName.compareTo(beacon.name) > 0
                    || (hub != null && !hub.hasPeers());
            if (preferConnect && canAutoConnect("bt:" + beacon.name, beacon.name)) {
                connectBtSilent(device, beacon.psm, beacon.name);
            }
        }
    }

    private void onBleMesh(Ble.MeshPacket pkt) {
        if (pkt == null || pkt.sender.equals(myName)) return;
        if (pkt.type == Ble.MESH_PRESENCE) {
            Presence p = Ble.unpackMeshPresence(pkt.body);
            if (p != null) updateMeshPeerState(pkt.sender, p, true);
        } else if (pkt.type == Ble.MESH_TEXT) {
            String text = new String(pkt.body, Hub.UTF8).trim();
            if (!text.isEmpty() && shouldAcceptMeshText(pkt.sender, text)) {
                updateMeshPeerState(pkt.sender, presences.get(pkt.sender), true);
                addBubble(pkt.sender, text, false);
                notifyAway(Alerts.CH_MESSAGES, pkt.sender, text);
            }
        } else if (pkt.type == Ble.MESH_ANNOUNCE) {
            String text = new String(pkt.body, Hub.UTF8).trim();
            if (!text.isEmpty() && shouldAcceptMeshText("ann:" + pkt.sender, text)) {
                updateMeshPeerState(pkt.sender, presences.get(pkt.sender), true);
                addAnnouncement(pkt.sender, text);
                notifyAway(Alerts.CH_MESSAGES, "Instruksi dari " + pkt.sender, text);
                vibrate(new long[] {0, 400});
                beep(AudioManager.STREAM_NOTIFICATION, ToneGenerator.TONE_PROP_ACK, 400);
            }
        }
    }

    private boolean shouldAcceptMeshText(String senderKey, String text) {
        long now = System.currentTimeMillis();
        String key = senderKey + ":" + text;
        Long prev = recentMeshTexts.get(key);
        recentMeshTexts.put(key, now);
        return prev == null || now - prev > 10000L;
    }

    private void updateMeshPeerState(String peerName, Presence p, boolean inRange) {
        if (peerName == null || peerName.equals(myName)) return;
        boolean paused = System.currentTimeMillis() < autoConnectPauseUntil;
        if (inRange && !paused) {
            ensureActiveHub();
            boolean isNew = !members.containsKey(peerName);
            if (isNew) {
                members.put(peerName, null);
                addSystemMessage(peerName + " terhubung (Mesh)");
                updateKeepAlive();
            }
            setStatus("Terhubung · " + (members.size() + 1) + " orang", ACCENT);
        }
        if (p != null) {
            Presence old = presences.put(peerName, p);
            boolean wasSos = old != null && old.status == Presence.SOS;
            if (p.status == Presence.SOS) {
                bleSosNames.add(peerName);
                if (!wasSos) {
                    sosSilenced.remove(peerName);
                    addSystemMessage(peerName + " mengirim Panggilan Prioritas");
                    notifyAway(Alerts.CH_SOS, "PRIORITAS: " + peerName + " butuh supporting/backup!",
                            whereIs(p));
                }
            } else if (wasSos) {
                bleSosNames.remove(peerName);
                addSystemMessage(peerName + " mengakhiri Panggilan Prioritas");
            }
        }
        refreshMembers();
        refreshSos();
        refreshCompass();
    }

    private void connectLan(Lan.Peer peer) {
        if (peer == null || !ensurePermissions()) return;
        autoConnectPauseUntil = 0;
        Hub h = ensureActiveHub();
        if (peer.inGroup || myName.compareTo(peer.name) > 0) {
            setStatus("Menyambung ke " + peer.name + " (Hotspot/Wi‑Fi Lokal)…", AMBER);
            h.connectPeer(peer.address, peer.port, null);
        } else {
            setStatus("Menunggu " + peer.name + " bergabung (Hotspot/Wi‑Fi Lokal)", AMBER);
            sendPresence();
        }
    }

    private void connectBt(final android.bluetooth.BluetoothDevice device, final int psm, final String name) {
        if (device == null || !ensurePermissions()) return;
        autoConnectPauseUntil = 0;
        ensureActiveHub();
        connectBtSilent(device, psm, name);
    }

    private void connectBtSilent(final android.bluetooth.BluetoothDevice device, final int psm, final String name) {
        if (device == null || bt == null || neededPermissions().length > 0) return;
        final Hub h = ensureActiveHub();
        bt.connect(device, psm, h, new Runnable() {
            @Override public void run() {
                ui.post(new Runnable() {
                    @Override public void run() {
                        setStatus("Terhubung · " + (members.size() + 1) + " orang", ACCENT);
                    }
                });
            }
        }, null);
    }

    private int totalDiscoveredPeers() {
        return awarePeers.size() + lanPeers.size() + peers.size() + btPeers.size();
    }

    // ------------------------------------------------------ Wi-Fi Direct

    private void discover() {
        autoConnectPauseUntil = 0;
        autoConnectCooldown.clear();
        discover(0);
    }

    private void discover(final int attempt) {
        if (!ensurePermissions()) return;
        startOfflineTransports();
        if (p2p == null) {
            setStatus("Mencari perangkat via Wi‑Fi Aware, Hotspot & Bluetooth Mesh", AMBER);
            return;
        }
        setStatus("Mencari perangkat di sekitar", AMBER);
        p2p.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { }
            @Override public void onFailure(int reason) {
                if (reason == WifiP2pManager.BUSY && attempt < 2) {
                    // Usually a scan or invitation still running; clear it and try again.
                    p2p.stopPeerDiscovery(channel, null);
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { discover(attempt + 1); }
                    }, 1500);
                    return;
                }
                if (awarePeers.isEmpty() && lanPeers.isEmpty() && btPeers.isEmpty()) {
                    setStatus("Pencarian gagal: " + p2pReason(reason), DANGER);
                }
            }
        });
    }

    private void connect(WifiP2pDevice d) {
        connect(d, 0);
    }

    private void connect(final WifiP2pDevice d, final int attempt) {
        if (p2p == null || !ensurePermissions()) return;
        WifiP2pConfig c = new WifiP2pConfig();
        c.deviceAddress = d.deviceAddress;
        c.wps.setup = WpsInfo.PBC;
        setStatus("Menghubungi " + d.deviceName + ", tunggu diterima", AMBER);
        p2p.connect(channel, c, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { }
            @Override public void onFailure(int reason) {
                if (reason == WifiP2pManager.BUSY && attempt < 2) {
                    // An earlier invitation (often to another phone) is still pending or a
                    // scan is running. Cancel both, then try this phone again.
                    setStatus("Wi‑Fi Direct sibuk, mencoba lagi…", AMBER);
                    p2p.cancelConnect(channel, null);
                    p2p.stopPeerDiscovery(channel, null);
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { connect(d, attempt + 1); }
                    }, 1500);
                    return;
                }
                setStatus("Gagal menghubungi " + d.deviceName + ": " + p2pReason(reason), DANGER);
            }
        });
    }

    /** Plain words for WifiP2pManager failure codes. */
    static String p2pReason(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY:
                return "Wi‑Fi Direct sedang sibuk. Tunggu sebentar lalu coba lagi, "
                        + "atau matikan-nyalakan Wi‑Fi";
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "HP ini tidak mendukung Wi‑Fi Direct";
            case WifiP2pManager.ERROR:
                return "Wi‑Fi Direct error. Pastikan Wi‑Fi dan Lokasi menyala";
            default:
                return "kode " + reason;
        }
    }

    private void disconnect() {
        autoConnectPauseUntil = System.currentTimeMillis() + 15000L;
        p2pGroupActive = false;
        stopHub();
        stopBridge();
        upstreamInfo = null;
        refreshAll();
        sendPresence();
        if (p2p != null) {
            p2p.cancelConnect(channel, null);
            p2p.removeGroup(channel, null);
        }
        addSystemMessage("Kamu keluar dari grup.");
    }

    private void onP2pEvent(Intent i) {
        if (i == null) return;
        String action = i.getAction();
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            int st = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (st != WifiP2pManager.WIFI_P2P_STATE_ENABLED && lanPeers.isEmpty() && btPeers.isEmpty()) {
                setStatus("Wi‑Fi mati. Nyalakan Wi‑Fi atau Bluetooth (tanpa internet)", DANGER);
            }
        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            if (neededPermissions().length > 0) return;
            p2p.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                @Override public void onPeersAvailable(WifiP2pDeviceList list) {
                    peers.clear();
                    peers.addAll(list.getDeviceList());
                    refreshPeers();
                    if (hub == null && totalDiscoveredPeers() > 0) {
                        setStatus(totalDiscoveredPeers() + " perangkat ditemukan", AMBER);
                    }
                }
            });
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            p2p.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                @Override public void onConnectionInfoAvailable(WifiP2pInfo info) { onConnection(info); }
            });
        }
    }

    private static String statusText(int s) {
        switch (s) {
            case WifiP2pDevice.CONNECTED: return "Terhubung";
            case WifiP2pDevice.INVITED: return "Menunggu diterima";
            case WifiP2pDevice.FAILED: return "Gagal";
            case WifiP2pDevice.AVAILABLE: return "Tersedia";
            default: return "Tidak tersedia";
        }
    }

    private void onConnection(WifiP2pInfo info) {
        if (info != null && info.groupFormed) {
            p2pGroupActive = true;
            if (hub != null) return;
            hub = initHub(info.isGroupOwner);
            if (info.isGroupOwner) {
                hub.startServer(info.groupOwnerAddress);
                java.net.InetAddress lanIp = Lan.localIPv4();
                if (lanIp != null && !lanIp.equals(info.groupOwnerAddress)) {
                    hub.startServer(lanIp);
                }
                sendPresence();
                shareJoinInfo(hub, info.groupOwnerAddress);
                if (bridgeTo != null) joinUplink();
            } else {
                setStatus("Menyiapkan saluran", AMBER);
                hub.startClient(info.groupOwnerAddress);
            }
            refreshAll();
            if (info.isGroupOwner) setStatus("Menunggu anggota bergabung", AMBER);
        } else if (hub != null && p2pGroupActive) {
            p2pGroupActive = false;
            boolean hadMembers = !members.isEmpty();
            stopHub();
            refreshAll();
            if (hadMembers) {
                alertLost("Koneksi grup terputus");
                notifyAway(Alerts.CH_MESSAGES, "Koneksi grup terputus", "Buka aplikasi untuk menyambung lagi.");
            }
        }
    }

    // ------------------------------------------------------------ multihop

    /**
     * As group owner, also listens on the group's IPv6 link-local address and tells
     * direct members how a bridge can reach this group.
     */
    private void shareJoinInfo(final Hub h, final java.net.InetAddress groupAddress) {
        p2p.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
            @Override public void onGroupInfoAvailable(final android.net.wifi.p2p.WifiP2pGroup g) {
                if (g == null || g.getPassphrase() == null) return;
                new Thread(new Runnable() {
                    @Override public void run() {
                        java.net.Inet6Address local = null;
                        for (int i = 0; i < 10 && local == null; i++) {
                            local = Bridge.linkLocalOf(groupAddress);
                            if (local == null) {
                                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                            }
                        }
                        if (local == null || h != hub) return;
                        h.startServer(local);
                        h.setJoinInfo(Bridge.encode(g.getNetworkName(), g.getPassphrase(),
                                Bridge.withoutScope(local)));
                    }
                }, "join-info").start();
            }
        });
    }

    private boolean canBridge() {
        return hub != null && !isOwner && bridgeTo == null && Bridge.decode(upstreamInfo) != null;
    }

    private void askBridge() {
        new AlertDialog.Builder(this)
                .setTitle("Jadi jembatan (percobaan)")
                .setMessage("HP ini akan membuat grup baru untuk HP yang lebih jauh, sambil tetap "
                        + "tersambung ke grup sekarang lewat Wi‑Fi. Semua anggota di kedua sisi "
                        + "saling mendengar dan melihat chat.\n\n"
                        + "Letakkan HP ini di tengah, di antara grup dan HP yang jauh. "
                        + "Android mungkin meminta izin menyambung ke Wi‑Fi \"DIRECT-…\"; pilih Sambungkan.")
                .setPositiveButton("Mulai", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { startBridge(); }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Leaves the owner's Wi-Fi Direct group and forms our own; onConnection then adds the uplink. */
    private void startBridge() {
        final Bridge.Info info = Bridge.decode(upstreamInfo);
        if (info == null || p2p == null) return;
        bridgeTo = info;
        stopHub();
        refreshAll();
        addSystemMessage("Menyiapkan jembatan…");
        setStatus("Menyiapkan jembatan", AMBER);
        p2p.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { createBridgeGroup(); }
            @Override public void onFailure(int reason) { createBridgeGroup(); }
        });
    }

    private void createBridgeGroup() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (bridgeTo == null) return;
                p2p.createGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { }
                    @Override public void onFailure(int reason) {
                        bridgeTo = null;
                        setStatus("Gagal membuat grup jembatan (kode " + reason + ")", DANGER);
                    }
                });
            }
        }, 1000);
    }

    private void joinUplink() {
        if (bridge == null) bridge = new Bridge(this);
        bridge.join(bridgeTo, new Bridge.Listener() {
            @Override public void onNetwork(final android.net.Network n, final java.net.InetAddress target) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        uplinkNetwork = n;
                        uplinkTarget = target;
                        connectUplink();
                    }
                });
            }

            @Override public void onFailed(final String reason) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (bridgeTo == null) return;
                        addSystemMessage("Jembatan gagal: " + reason + ". Grup di HP ini tetap jalan.");
                    }
                });
            }
        });
    }

    private void connectUplink() {
        if (hub == null || hub.isBridged() || uplinkNetwork == null || bridgeTo == null) return;
        hub.startUplink(uplinkTarget, Hub.PORT, uplinkNetwork.getSocketFactory());
    }

    private void stopBridge() {
        bridgeTo = null;
        uplinkNetwork = null;
        uplinkTarget = null;
        if (bridge != null) bridge.leave();
    }

    @Override public void onJoinInfo(final byte[] info) {
        ui.post(new Runnable() {
            @Override public void run() { upstreamInfo = info; }
        });
    }

    @Override public void onUplink(final boolean connected) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (bridgeTo == null) return;
                if (connected) {
                    addSystemMessage("Jembatan tersambung. HP yang lebih jauh bisa bergabung lewat HP ini.");
                } else {
                    addSystemMessage("Jembatan terputus dari grup utama. Mencoba lagi…");
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { connectUplink(); }
                    }, 3000);
                }
            }
        });
    }

    private void stopHub() {
        if (callMode) callMode = false;
        stopProximity();
        stopTalking();
        if (hub != null) {
            hub.stop();
            hub = null;
        }
        if (ble != null) ble.setConnectedMode(false);
        ui.removeCallbacks(presenceTick);
        KeepAliveService.stop(this);
        members.clear();
        talkers.clear();
        presences.clear();
        bleSosNames.clear();
        sosSilenced.clear();
        stopAlarm();
        audio.setMode(AudioManager.MODE_NORMAL);
        audio.setSpeakerphoneOn(false);
    }

    // ------------------------------------------------------- talk & chat

    private void applyAudioRoute() {
        if (hub == null) return;
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audio.setSpeakerphoneOn(speakerOn && !(callMode && nearEar));
    }

    private void startProximity() {
        if (proximityListener != null) return;
        if (sensors == null) sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensors == null) return;
        final Sensor prox = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (prox == null) return;
        proximityListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                if (e == null || e.values == null || e.values.length == 0) return;
                boolean near = e.values[0] < prox.getMaximumRange();
                if (near != nearEar) {
                    nearEar = near;
                    applyAudioRoute();
                }
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        sensors.registerListener(proximityListener, prox, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void stopProximity() {
        nearEar = false;
        if (sensors != null && proximityListener != null) {
            sensors.unregisterListener(proximityListener);
            proximityListener = null;
        }
    }

    private boolean ready() {
        if (hub == null || (!hub.hasPeers() && members.isEmpty())) {
            toast("Belum terhubung ke HP lain");
            return false;
        }
        return true;
    }

    private void sendText() {
        String t = input.getText().toString().trim();
        if (t.length() == 0 || !ready()) return;
        if (t.length() > 2000) t = t.substring(0, 2000);
        hub.sendText(t);
        if (ble != null) ble.broadcastMeshText(myName, t);
        addBubble(myName, t, true);
        input.setText("");
    }

    private void startTalking() {
        if (!ready()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions();
            return;
        }
        if (voice.isCapturing()) return;
        final Hub h = hub;
        try {
            voice.startCapture(new Voice.Sink() {
                @Override public void onPcm(byte[] pcm) { h.sendAudio(pcm); }
            });
        } catch (RuntimeException e) {
            toast(e.getMessage());
            return;
        }
        h.sendTalkState(true);
        refreshControls();
    }

    private void stopTalking() {
        if (voice.isCapturing()) {
            voice.stopCapture();
            if (hub != null) hub.sendTalkState(false);
        }
        refreshControls();
    }

    private void toggleCallMode() {
        if (!callMode) {
            if (!ready()) return;
            callMode = true;
            startTalking();
            if (!voice.isCapturing()) {
                callMode = false;
            } else {
                startProximity();
                addSystemMessage("Mode telepon aktif: mikrofon terus menyala.");
            }
        } else {
            callMode = false;
            stopProximity();
            applyAudioRoute();
            stopTalking();
            addSystemMessage("Mode telepon ditutup.");
        }
        refreshControls();
    }

    // --------------------------------------------- Hub callbacks (bg thread)

    @Override public void onStatus(final String message) {
        ui.post(new Runnable() {
            @Override public void run() { if (hub != null) setStatus(message, AMBER); }
        });
    }

    @Override public void onMemberJoined(final String name, final byte[] photo) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (hub == null) return;
                boolean isNew = !members.containsKey(name);
                bleSosNames.remove(name);
                members.put(name, photo);
                if (ble != null) ble.setConnectedMode(true);
                refreshMembers();
                refreshSos();
                setStatus("Terhubung · " + (members.size() + 1) + " orang", ACCENT);
                if (isNew) addSystemMessage(name + " bergabung");
                if (isOwner) sendPresence();
                updateKeepAlive();
            }
        });
    }

    @Override public void onMemberLeft(final String name) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (hub == null) return;
                members.remove(name);
                talkers.remove(name);
                presences.remove(name);
                voice.drop(name);
                if (ble != null) ble.setConnectedMode(!members.isEmpty());
                refreshCompass();
                refreshMembers();
                refreshTalkers();
                refreshSos();
                alertLost(name + " terputus dari grup");
                notifyAway(Alerts.CH_MESSAGES, name + " terputus dari grup",
                        "Mungkin keluar jangkauan atau baterai habis.");
                if (isOwner) sendPresence();
                updateKeepAlive();
                if (members.isEmpty()) setStatus("Tidak ada anggota tersambung", DANGER);
                else setStatus("Terhubung · " + (members.size() + 1) + " orang", ACCENT);
            }
        });
    }

    @Override public void onImage(final String from, final byte[] jpeg) {
        final Bitmap thumb = Photos.decode(jpeg, 720);
        ui.post(new Runnable() {
            @Override public void run() {
                addImageBubble(from, jpeg, thumb, false);
                notifyAway(Alerts.CH_MESSAGES, from, "Mengirim foto");
            }
        });
    }

    @Override public void onVoiceNote(final String from, final byte[] adpcm) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (adpcm == null || !shouldAcceptMeshText("vn:" + from,
                        Integer.toString(java.util.Arrays.hashCode(adpcm)))) {
                    return;
                }
                addVoiceNoteBubble(from, adpcm, false);
                notifyAway(Alerts.CH_MESSAGES, from, "Mengirim pesan suara");
            }
        });
    }

    @Override public void onVideoIncoming(final String from) {
        ui.post(new Runnable() {
            @Override public void run() { addSystemMessage(from + " mengirim video…"); }
        });
    }

    @Override public void onVideo(final String from, final byte[] clip) {
        Runnable save = new Runnable() {
            @Override public void run() {
                final java.io.File f;
                try {
                    f = saveClip(clip);
                } catch (java.io.IOException e) {
                    return;
                }
                final Bitmap thumb = videoThumb(f);
                ui.post(new Runnable() {
                    @Override public void run() {
                        addVideoBubble(from, f, thumb, false);
                        notifyAway(Alerts.CH_MESSAGES, from, "Mengirim video");
                    }
                });
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) save.run();
        else new Thread(save, "video-save").start();
    }

    @Override public void onText(final String from, final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (!shouldAcceptMeshText(from, text)) return;
                addBubble(from, text, false);
                notifyAway(Alerts.CH_MESSAGES, from, text);
            }
        });
    }

    @Override public void onPresence(final String from, byte[] payload) {
        final Presence p = Presence.decode(payload);
        ui.post(new Runnable() {
            @Override public void run() {
                if (hub == null) return;
                Presence old = presences.put(from, p);
                boolean wasSos = old != null && old.status == Presence.SOS;
                if (p.status == Presence.SOS && !wasSos) {
                    sosSilenced.remove(from);
                    addSystemMessage(from + " mengirim Panggilan Prioritas");
                    notifyAway(Alerts.CH_SOS, "PRIORITAS: " + from + " butuh supporting/backup!", whereIs(p));
                } else if (wasSos && p.status != Presence.SOS) {
                    addSystemMessage(from + " mengakhiri Panggilan Prioritas");
                }
                refreshMembers();
                refreshSos();
                refreshCompass();
            }
        });
    }

    @Override public void onAnnouncement(final String from, final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (!shouldAcceptMeshText("ann:" + from, text)) return;
                addAnnouncement(from, text);
                notifyAway(Alerts.CH_MESSAGES, "Instruksi dari " + from, text);
                vibrate(new long[] {0, 400});
                beep(AudioManager.STREAM_NOTIFICATION, ToneGenerator.TONE_PROP_ACK, 400);
            }
        });
    }

    @Override public void onRejected(final String reason) {
        ui.post(new Runnable() {
            @Override public void run() {
                alertLost(reason);
                disconnect();
            }
        });
    }

    @Override public void onAudio(String from, byte[] pcm) {
        voice.play(from, pcm);
    }

    @Override public void onTalkState(final String from, final boolean talking) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (talking) talkers.add(from); else talkers.remove(from);
                refreshTalkers();
                refreshMembers();
            }
        });
    }
}
