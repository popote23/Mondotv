package ma.mondotv.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static final String UA = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/110 Mobile Safari/537.36";
    // Ordre de découverte : Maroc, pays arabes, puis grands pays, puis le reste du monde
    static final String[] FIRST = {"MA", "DZ", "TN", "LY", "MR", "EG", "SA", "AE", "QA", "KW", "BH", "OM", "IQ", "SY", "LB", "JO", "PS", "YE", "SD", "SO", "DJ", "KM", "FR", "UK", "US", "ES", "TR", "CA", "BE", "CH", "DE", "IT"};
    static final String[] CATS = {"Toutes catégories", "Actualités", "Général", "Films", "Séries", "Musique", "Sport", "Enfants", "Documentaires", "Religion", "Divertissement", "Autres"};
    static final String[][] FILMS = {
            {"V:ar", "Films arabes", "mediatype:movies AND (language:Arabic OR language:ara OR subject:arabic)"},
            {"V:eg", "Films égyptiens", "mediatype:movies AND (subject:egypt* OR title:egypt* OR subject:egyptian OR title:مصر*)"},
            {"V:ma", "Films marocains", "mediatype:movies AND (subject:morocc* OR title:morocc* OR subject:maroc* OR title:maroc*)"},
            {"V:fr", "Films français", "mediatype:movies AND (language:French OR language:fre OR language:fra)"},
            {"V:en", "Films anglais (domaine public)", "mediatype:movies AND collection:feature_films"}};
    static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    static final Pattern RES = Pattern.compile("\\((\\d{3,4})[pi]\\)");
    static final Pattern ADULT = Pattern.compile("(?i)\\b(xxx|porn|adult|erotic|playboy)\\b");

    static class Src { String url, ref, ua; int q; }
    static class Ch { String name, logo, cc, vid; int q, ci; boolean vod; List<Src> s = new ArrayList<>(); }

    final List<Ch> all = new ArrayList<>(), vod = new ArrayList<>(), shown = new ArrayList<>();
    final List<String> found = new ArrayList<>(), keys = new ArrayList<>(), labels = new ArrayList<>();
    final Map<String, Integer> cnt = new HashMap<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService pool = Executors.newFixedThreadPool(12);
    String sec = "MA", query = "";
    int cat = 0, cur = 0, si = 0;
    boolean films = false, playing = false, dead = false;
    Ch pc;
    ListView secLv, chLv;
    ArrayAdapter<String> secAd;
    BaseAdapter chAd;
    TextView status, osd;
    EditText search;
    Spinner catSp;
    FrameLayout root;
    LinearLayout col;
    PlayerView pv;
    ExoPlayer player;
    final Runnable hideOsd = new Runnable() { public void run() { osd.setVisibility(View.GONE); } };

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                if (!playing || pc == null) return;
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return;
                }
                si++; start(); // bascule automatique vers le flux alternatif
            }
        });

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF10131A);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hdr = new LinearLayout(this);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(8), dp(4), dp(8), dp(4));
        Button b1 = new Button(this); b1.setText("Chaînes");
        Button b2 = new Button(this); b2.setText("Films");
        search = new EditText(this);
        search.setHint("Rechercher dans toutes les chaînes…");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFF7A8594);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        catSp = new Spinner(this);
        catSp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, CATS));
        hdr.addView(b1); hdr.addView(b2);
        hdr.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        hdr.addView(catSp);

        status = new TextView(this);
        status.setTextColor(0xFF9AA4B2);
        status.setPadding(dp(12), 0, dp(12), dp(4));
        status.setText("MondoTV — recherche des chaînes…");

        secAd = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels);
        secLv = new ListView(this); secLv.setAdapter(secAd); styleLv(secLv);
        chAd = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = row();
                Ch c = shown.get(i);
                ((TextView) v.findViewById(2)).setText(c.name);
                String m = c.vod ? "Film • Internet Archive" : CATS[c.ci] + (c.q > 0 ? " • " + c.q + "p" : "") + (c.s.size() > 1 ? " • " + c.s.size() + " flux" : "");
                ((TextView) v.findViewById(3)).setText(m);
                ImageView iv = (ImageView) v.findViewById(1);
                if (c.logo != null && c.logo.startsWith("http")) Glide.with(MainActivity.this).load(c.logo).into(iv);
                else Glide.with(MainActivity.this).clear(iv);
                return v;
            }
        };
        chLv = new ListView(this); chLv.setAdapter(chAd); styleLv(chLv);

        LinearLayout body = new LinearLayout(this);
        body.addView(secLv, new LinearLayout.LayoutParams(0, -1, 3));
        body.addView(chLv, new LinearLayout.LayoutParams(0, -1, 7));
        col.addView(hdr); col.addView(status);
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(col);

        pv = new PlayerView(this);
        pv.setPlayer(player);
        pv.setBackgroundColor(Color.BLACK);
        pv.setVisibility(View.GONE);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));
        osd = new TextView(this);
        osd.setTextSize(22); osd.setTextColor(Color.WHITE); osd.setBackgroundColor(0xAA000000);
        osd.setPadding(dp(16), dp(8), dp(16), dp(8)); osd.setVisibility(View.GONE);
        root.addView(osd, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        setContentView(root);

        b1.setOnClickListener(v -> { films = false; catSp.setVisibility(View.VISIBLE); sec = found.isEmpty() ? "MA" : found.get(0); rebuild(); refresh(); });
        b2.setOnClickListener(v -> { films = true; catSp.setVisibility(View.GONE); sec = FILMS[0][0]; rebuild(); loadVod(sec); });
        secLv.setOnItemClickListener((p, v, i, id) -> {
            sec = keys.get(i);
            search.setText("");
            if (films) loadVod(sec); else refresh();
        });
        chLv.setOnItemClickListener((p, v, i, id) -> open(shown.get(i)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString().trim(); refresh(); }
            public void afterTextChanged(Editable e) {}
        });
        catSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int i, long id) { cat = i; refresh(); }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        discover();
    }

    void styleLv(ListView lv) {
        lv.setSelector(new ColorDrawable(0x663F51B5));
        lv.setDrawSelectorOnTop(true);
        lv.setDivider(new ColorDrawable(0x22FFFFFF));
        lv.setDividerHeight(1);
    }

    View row() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(8), dp(12), dp(8));
        ImageView iv = new ImageView(this);
        iv.setId(1);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        r.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(40)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), 0, 0, 0);
        TextView n = new TextView(this); n.setId(2); n.setTextSize(18); n.setTextColor(Color.WHITE); n.setSingleLine(true);
        TextView m = new TextView(this); m.setId(3); m.setTextSize(13); m.setTextColor(0xFF9AA4B2); m.setSingleLine(true);
        t.addView(n); t.addView(m);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }

    // ---------- Liste ----------
    void rebuild() {
        keys.clear(); labels.clear();
        if (films) { for (String[] f : FILMS) { keys.add(f[0]); labels.add(f[1]); } }
        else for (String cc : found) { keys.add(cc); labels.add(nm(cc) + " (" + cnt.get(cc) + ")"); }
        secAd.notifyDataSetChanged();
    }

    void refresh() {
        shown.clear();
        String q = query.toLowerCase(Locale.ROOT);
        for (Ch c : films ? vod : all) {
            if (q.isEmpty()) { if (!films && !c.cc.equals(sec)) continue; }
            else if (!c.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (!films && cat > 0 && c.ci != cat) continue;
            shown.add(c);
        }
        if (!films) Collections.sort(shown, (a, b) -> a.ci != b.ci ? a.ci - b.ci : (a.q != b.q ? b.q - a.q : a.name.compareToIgnoreCase(b.name)));
        chAd.notifyDataSetChanged();
    }

    void say(final String s) { ui.post(() -> status.setText(s)); }

    String nm(String cc) {
        if (cc.equals("INT")) return "International";
        return new Locale("", cc.equals("UK") ? "GB" : cc).getDisplayCountry(Locale.FRENCH);
    }

    static int ci(String g) {
        g = g == null ? "" : g.split(";")[0].trim().toLowerCase(Locale.ROOT);
        switch (g) {
            case "news": case "business": case "weather": case "legislative": return 1;
            case "general": case "public": case "classic": return 2;
            case "movies": return 3;
            case "series": return 4;
            case "music": return 5;
            case "sports": case "outdoor": return 6;
            case "kids": case "animation": return 7;
            case "documentary": case "science": case "culture": case "education": case "travel": return 8;
            case "religious": return 9;
            case "entertainment": case "comedy": case "lifestyle": case "family": case "relax": case "cooking": return 10;
            default: return 11;
        }
    }

    // ---------- Découverte intelligente ----------
    void discover() {
        new Thread(() -> {
            List<String> codes = new ArrayList<>(Arrays.asList(FIRST));
            for (String s : Locale.getISOCountries()) { String u = s.equals("GB") ? "UK" : s; if (!codes.contains(u)) codes.add(u); }
            codes.add("INT");
            int got = 0, done = 0;
            for (final String cc : codes) {
                if (dead) return;
                String name = nm(cc);
                say("Recherche : " + name + " • " + all.size() + " chaînes actives");
                String f = cc.toLowerCase(Locale.ROOT) + ".m3u";
                String t = get("https://iptv-org.github.io/iptv/countries/" + f);
                if (t == null) t = get("https://raw.githubusercontent.com/iptv-org/iptv/master/streams/" + f);
                if (t == null || !t.contains("#EXTINF")) continue;
                got++; done++;
                say("Vérification des flux : " + name + " (" + done + " pays) • " + all.size() + " chaînes actives");
                final List<Ch> ok = verify(parse(t, cc));
                ui.post(() -> {
                    if (ok.isEmpty()) return;
                    all.addAll(ok); found.add(cc); cnt.put(cc, ok.size());
                    if (!films) { rebuild(); refresh(); }
                });
            }
            say(got == 0 ? "Aucune connexion Internet ?" : "Recherche terminée • " + all.size() + " chaînes actives dans " + done + " pays");
        }).start();
    }

    List<Ch> parse(String txt, String cc) {
        Map<String, Ch> m = new LinkedHashMap<>();
        String inf = null, ref = null, ua = null;
        for (String l : txt.split("\n")) {
            l = l.trim();
            if (l.startsWith("#EXTINF")) { inf = l; ref = null; ua = null; }
            else if (l.startsWith("#EXTVLCOPT:http-referrer=")) ref = l.substring(l.indexOf('=') + 1);
            else if (l.startsWith("#EXTVLCOPT:http-user-agent=")) ua = l.substring(l.indexOf('=') + 1);
            else if (!l.isEmpty() && !l.startsWith("#") && inf != null) {
                if (l.startsWith("http")) {
                    Map<String, String> at = new HashMap<>();
                    Matcher mt = ATTR.matcher(inf);
                    while (mt.find()) at.put(mt.group(1), mt.group(2));
                    String title = inf.substring(inf.lastIndexOf(',') + 1).trim();
                    String grp = at.get("group-title"); if (grp == null) grp = "";
                    if (!ADULT.matcher(title + " " + grp).find()) {
                        Matcher rm = RES.matcher(title);
                        int q = rm.find() ? Integer.parseInt(rm.group(1)) : 0;
                        String name = title.replaceAll("\\s*\\(\\d{3,4}[pi]\\)", "").replaceAll("\\s*\\[[^\\]]*\\]", "").trim();
                        String key = name.toLowerCase(Locale.ROOT);
                        Ch c = m.get(key);
                        if (c == null) { c = new Ch(); c.name = name; c.logo = at.get("tvg-logo"); c.ci = ci(grp); c.cc = cc; m.put(key, c); }
                        c.q = Math.max(c.q, q);
                        Src s = new Src(); s.url = l; s.ref = ref; s.ua = ua; s.q = q;
                        c.s.add(s);
                    }
                }
                inf = null;
            }
        }
        List<Ch> out = new ArrayList<>(m.values());
        for (Ch c : out) Collections.sort(c.s, (a, b) -> b.q - a.q);
        return out;
    }

    // Teste chaque flux en parallèle : on ne garde que ceux qui répondent vraiment
    List<Ch> verify(List<Ch> in) {
        List<Callable<Ch>> jobs = new ArrayList<>();
        for (final Ch c : in) jobs.add(() -> {
            List<Src> ok = new ArrayList<>();
            for (Src s : c.s) { if (dead) break; if (alive(s)) ok.add(s); if (ok.size() >= 3) break; }
            c.s = ok;
            return ok.isEmpty() ? null : c;
        });
        List<Ch> out = new ArrayList<>();
        try {
            for (Future<Ch> f : pool.invokeAll(jobs)) {
                try { Ch c = f.get(); if (c != null) out.add(c); } catch (Exception e) { }
            }
        } catch (Exception e) { }
        return out;
    }

    boolean alive(Src s) {
        String u = s.url;
        try {
            for (int hop = 0; hop < 4; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(6000); c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", s.ua != null ? s.ua : UA);
                if (s.ref != null) c.setRequestProperty("Referer", s.ref);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String l = c.getHeaderField("Location"); c.disconnect();
                    if (l == null) return false;
                    u = new URL(new URL(u), l).toString();
                    continue;
                }
                if (code != 200) { c.disconnect(); return false; }
                String ct = c.getContentType();
                InputStream in = c.getInputStream();
                byte[] bf = new byte[1024];
                int n = in.read(bf);
                in.close(); c.disconnect();
                if (n <= 0) return false;
                String h = new String(bf, 0, n, "UTF-8");
                if (u.contains(".m3u8") || h.startsWith("#EXTM3U")) return h.contains("#EXTM3U");
                return ct == null || !ct.toLowerCase(Locale.ROOT).contains("text/html");
            }
        } catch (Exception e) { }
        return false;
    }

    String get(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    // ---------- Films gratuits (Internet Archive) ----------
    void loadVod(final String key) {
        vod.clear(); shown.clear(); chAd.notifyDataSetChanged();
        say("Chargement des films…");
        String q = "";
        for (String[] f : FILMS) if (f[0].equals(key)) q = f[2];
        final String qq = q;
        new Thread(() -> {
            String j = get("https://archive.org/advancedsearch.php?q=" + Uri.encode(qq) + "&fl%5B%5D=identifier&fl%5B%5D=title&sort%5B%5D=downloads+desc&rows=150&output=json");
            final List<Ch> l = new ArrayList<>();
            try {
                JSONArray d = new JSONObject(j).getJSONObject("response").getJSONArray("docs");
                for (int i = 0; i < d.length(); i++) {
                    JSONObject o = d.getJSONObject(i);
                    Object t = o.opt("title");
                    Ch c = new Ch();
                    c.vod = true; c.cc = "V"; c.vid = o.getString("identifier");
                    c.name = t instanceof JSONArray ? ((JSONArray) t).optString(0) : String.valueOf(t);
                    c.logo = "https://archive.org/services/img/" + c.vid;
                    l.add(c);
                }
            } catch (Exception e) { }
            ui.post(() -> {
                if (!films || !key.equals(sec)) return;
                vod.addAll(l); refresh();
                status.setText(l.isEmpty() ? "Aucun film trouvé (connexion ?)" : l.size() + " films gratuits • Internet Archive");
            });
        }).start();
    }

    // ---------- Lecture ----------
    void open(final Ch c) {
        if (c.vod && c.s.isEmpty()) {
            say("Préparation du film…");
            new Thread(() -> {
                String j = get("https://archive.org/metadata/" + c.vid);
                String best = null; boolean h264 = false;
                try {
                    JSONArray fs = new JSONObject(j).getJSONArray("files");
                    for (int k = 0; k < fs.length(); k++) {
                        JSONObject o = fs.getJSONObject(k);
                        String n = o.optString("name"), lo = n.toLowerCase(Locale.ROOT);
                        if (lo.endsWith(".mp4") || lo.endsWith(".m4v")) {
                            boolean h = o.optString("format").contains("264");
                            if (best == null || (h && !h264)) { best = n; h264 = h; }
                        }
                    }
                } catch (Exception e) { }
                if (best != null) {
                    Src s = new Src(); s.url = "https://archive.org/download/" + c.vid + "/" + Uri.encode(best, "/"); c.s.add(s);
                }
                ui.post(() -> {
                    if (c.s.isEmpty()) toast("Film non lisible : " + c.name); else play(c);
                    status.setText("Films gratuits • Internet Archive");
                });
            }).start();
        } else play(c);
    }

    void play(Ch c) {
        pc = c; cur = shown.indexOf(c); si = 0; playing = true;
        col.setVisibility(View.GONE);
        pv.setUseController(c.vod);
        pv.setVisibility(View.VISIBLE);
        pv.requestFocus();
        osd(c.name + (c.q > 0 ? "  •  " + c.q + "p" : ""));
        start();
    }

    void start() {
        if (pc == null) return;
        if (si >= pc.s.size()) { toast("Flux indisponible : " + pc.name); return; }
        Src s = pc.s.get(si);
        DefaultHttpDataSource.Factory f = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setUserAgent(s.ua != null ? s.ua : UA);
        if (s.ref != null) { Map<String, String> h = new HashMap<>(); h.put("Referer", s.ref); f.setDefaultRequestProperties(h); }
        player.setMediaSource(new DefaultMediaSourceFactory(f).createMediaSource(MediaItem.fromUri(s.url)));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    void zap(int d) {
        if (shown.isEmpty()) return;
        int n = cur + d;
        if (n < 0) n = shown.size() - 1;
        if (n >= shown.size()) n = 0;
        play(shown.get(n));
    }

    void stop() {
        playing = false;
        player.stop();
        pv.setVisibility(View.GONE); osd.setVisibility(View.GONE);
        col.setVisibility(View.VISIBLE);
        chLv.requestFocus();
        chLv.setSelection(Math.max(cur, 0));
    }

    void osd(String s) {
        osd.setText(s); osd.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOsd); ui.postDelayed(hideOsd, 3500);
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (playing && pc != null && !pc.vod && e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_PAGE_UP) { zap(-1); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN) { zap(1); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onBackPressed() {
        if (playing) stop(); else super.onBackPressed();
    }

    @Override protected void onStop() { super.onStop(); player.setPlayWhenReady(false); }

    @Override protected void onDestroy() {
        dead = true; pool.shutdownNow(); player.release();
        super.onDestroy();
    }
}
