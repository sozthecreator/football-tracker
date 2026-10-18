package eu.devsrv.samapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Headlines about one team (Google News RSS, no key). Tapping one opens the article in the in-app browser. */
public class NewsActivity extends Activity {
    private static final class Item { String title = "", link = "", source = "", ago = ""; }

    private final List<Item> items = new ArrayList<>();
    private TextView status;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String team = getIntent().getStringExtra("team");
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setOnApplyWindowInsetsListener((v, insets) -> { // targetSdk 37 draws edge-to-edge
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });

        TextView title = new TextView(this);
        title.setText(team + " news");
        title.setTextColor(getColor(R.color.text));
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), 0);
        root.addView(title);

        status = new TextView(this);
        status.setText("Loading…");
        status.setTextColor(getColor(R.color.muted));
        status.setTextSize(12);
        status.setPadding((int) (16 * d), 0, (int) (16 * d), (int) (8 * d));
        root.addView(status);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, (int) (16 * d));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                Item it = items.get(i);
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_news, parent, false);
                ((TextView) v.findViewById(R.id.headline)).setText(it.title);
                ((TextView) v.findViewById(R.id.meta)).setText(it.source.isEmpty() ? it.ago : it.source + " · " + it.ago);
                v.findViewById(R.id.card).setOnClickListener(x ->
                        startActivity(new Intent(NewsActivity.this, WebActivity.class).putExtra("url", it.link)));
                return v;
            }
        };
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        new Thread(() -> load(team)).start();
    }

    private void load(String team) {
        try {
            String q = URLEncoder.encode(team + " football", "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://news.google.com/rss/search?q=" + q + "&hl=en-GB&gl=GB&ceid=GB:en").openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            List<Item> out = new ArrayList<>();
            try (InputStream in = c.getInputStream()) {
                XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
                p.setInput(in, "UTF-8");
                SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                Item cur = null;
                for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT && out.size() < 40; e = p.next()) {
                    String n = p.getName();
                    if (e == XmlPullParser.START_TAG && n.equals("item")) cur = new Item();
                    else if (e == XmlPullParser.END_TAG && n.equals("item") && cur != null) { out.add(cur); cur = null; }
                    else if (e == XmlPullParser.START_TAG && cur != null) {
                        switch (n) {
                            case "title": cur.title = p.nextText(); break;
                            case "link": cur.link = p.nextText(); break;
                            case "source": cur.source = p.nextText(); break;
                            case "pubDate": cur.ago = ago(rfc.parse(p.nextText()).getTime()); break;
                            default: break;
                        }
                    }
                }
            }
            for (Item it : out) { // titles end with " - Source"
                if (!it.source.isEmpty() && it.title.endsWith(" - " + it.source)) it.title = it.title.substring(0, it.title.length() - it.source.length() - 3);
            }
            runOnUiThread(() -> {
                items.clear();
                items.addAll(out);
                adapter.notifyDataSetChanged();
                status.setText(out.isEmpty() ? "No news found" : "Latest headlines · tap to read");
            });
        } catch (Exception e) {
            runOnUiThread(() -> status.setText("Couldn't load news: " + e.getMessage()));
        }
    }

    private static String ago(long when) {
        long m = Math.max(0, (System.currentTimeMillis() - when) / 60_000);
        return m < 60 ? m + "m ago" : m < 1440 ? m / 60 + "h ago" : m / 1440 + "d ago";
    }
}
