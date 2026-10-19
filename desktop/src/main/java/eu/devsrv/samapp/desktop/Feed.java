package eu.devsrv.samapp.desktop;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** football-data.org matches and tables, Google News headlines. Port of the Android app's Football.java (no Android classes, own tiny JSON parser). */
final class Feed {
    static final String API = "https://api.football-data.org/v4";
    static final String[][] LEAGUES = { // code, name, bbc.co.uk/sport/football/<slug>
            {"PL", "Premier League", "premier-league"}, {"ELC", "Championship", "championship"},
            {"PD", "La Liga", "spanish-la-liga"}, {"BL1", "Bundesliga", "german-bundesliga"},
            {"SA", "Serie A", "italian-serie-a"}, {"FL1", "Ligue 1", "french-ligue-one"},
            {"CL", "Champions League", "champions-league"}, {"DED", "Eredivisie", "dutch-eredivisie"},
            {"PPL", "Primeira Liga", "portuguese-primeira-liga"}, {"BSA", "Brasileirão", "brazilian-league"},
    };
    static final long DAY = 86_400_000L;
    /** Score server (Cloudflare Worker that holds the API key), from FOOTBALL_API_URL or the server.properties baked in at build time; null if none. */
    private static final String PROXY = loadProxy();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.ALWAYS).build();

    static final class Match {
        String league, id, home, away, hg = "0", ag = "0", state = "pre", status = "", homeLogo = "", awayLogo = "";
        long when;

        String key() { return league + "|" + id; }
    }

    static final class Row {
        int pos, played, gf, ga, pts;
        String team = "", crest = "";
    }

    /** A band of league positions with what it means (Champions League, relegation ...). */
    static final class Zone {
        final int from, to, rgb;
        final String label;

        Zone(int from, int to, int rgb, String label) {
            this.from = from;
            this.to = to;
            this.rgb = rgb;
            this.label = label;
        }
    }

    private static final int BLUE = 0x4F7DFF, ORANGE = 0xFF9A1F, GREEN = 0x3DDC84, TEAL = 0x2EC4B6, PURPLE = 0xB388FF, RED = 0xFF5A5F;

    /** The usual qualification, promotion and relegation places for a competition. Cup winners and league changes can shift the European places. */
    static List<Zone> zones(String code) {
        List<Zone> z = new ArrayList<>();
        switch (code) {
            case "PL":
                z.add(new Zone(1, 4, BLUE, "Champions League")); z.add(new Zone(5, 5, ORANGE, "Europa League"));
                z.add(new Zone(6, 6, GREEN, "Conference League")); z.add(new Zone(18, 20, RED, "Relegation"));
                break;
            case "ELC":
                z.add(new Zone(1, 2, GREEN, "Promotion")); z.add(new Zone(3, 6, TEAL, "Promotion play-offs")); z.add(new Zone(22, 24, RED, "Relegation"));
                break;
            case "PD": case "SA":
                z.add(new Zone(1, 4, BLUE, "Champions League")); z.add(new Zone(5, 6, ORANGE, "Europa League"));
                z.add(new Zone(7, 7, GREEN, "Conference League")); z.add(new Zone(18, 20, RED, "Relegation"));
                break;
            case "BL1":
                z.add(new Zone(1, 4, BLUE, "Champions League")); z.add(new Zone(5, 6, ORANGE, "Europa League")); z.add(new Zone(7, 7, GREEN, "Conference League"));
                z.add(new Zone(16, 16, PURPLE, "Relegation play-off")); z.add(new Zone(17, 18, RED, "Relegation"));
                break;
            case "FL1":
                z.add(new Zone(1, 3, BLUE, "Champions League")); z.add(new Zone(4, 4, ORANGE, "Europa League")); z.add(new Zone(5, 5, GREEN, "Conference League"));
                z.add(new Zone(16, 16, PURPLE, "Relegation play-off")); z.add(new Zone(17, 18, RED, "Relegation"));
                break;
            case "DED":
                z.add(new Zone(1, 2, BLUE, "Champions League qualifying")); z.add(new Zone(3, 3, ORANGE, "Europa League qualifying"));
                z.add(new Zone(4, 4, GREEN, "Conference League qualifying")); z.add(new Zone(16, 16, PURPLE, "Relegation play-off")); z.add(new Zone(17, 18, RED, "Relegation"));
                break;
            case "PPL":
                z.add(new Zone(1, 2, BLUE, "Champions League")); z.add(new Zone(3, 3, ORANGE, "Europa League"));
                z.add(new Zone(4, 4, GREEN, "Conference League qualifying")); z.add(new Zone(16, 16, PURPLE, "Relegation play-off")); z.add(new Zone(17, 18, RED, "Relegation"));
                break;
            case "BSA":
                z.add(new Zone(1, 4, BLUE, "Copa Libertadores")); z.add(new Zone(5, 6, ORANGE, "Libertadores qualifying"));
                z.add(new Zone(7, 12, GREEN, "Copa Sudamericana")); z.add(new Zone(17, 20, RED, "Relegation"));
                break;
            case "CL":
                z.add(new Zone(1, 8, GREEN, "Round of 16")); z.add(new Zone(9, 24, TEAL, "Knockout play-offs")); z.add(new Zone(25, 36, RED, "Eliminated"));
                break;
            default:
                break;
        }
        return z;
    }

    static Zone zoneFor(List<Zone> zones, int pos) {
        for (Zone z : zones) if (pos >= z.from && pos <= z.to) return z;
        return null;
    }

    static final class News {
        String title = "", link = "", source = "", ago = "";
    }

    private static String loadProxy() {
        String u = System.getenv("FOOTBALL_API_URL");
        if (u == null || u.isBlank()) {
            try (java.io.InputStream in = Feed.class.getResourceAsStream("/server.properties")) {
                java.util.Properties p = new java.util.Properties();
                if (in != null) p.load(in);
                u = p.getProperty("url", "");
            } catch (Exception e) {
                u = "";
            }
        }
        u = u.trim();
        return u.isEmpty() || u.startsWith("@") ? null : u.replaceAll("/+$", "");
    }

    static boolean hasProxy() { return PROXY != null; }

    /** Support page (Buy Me a Coffee etc.) baked in at build time; empty = no Support button. */
    static String supportUrl() {
        try (java.io.InputStream in = Feed.class.getResourceAsStream("/server.properties")) {
            java.util.Properties p = new java.util.Properties();
            if (in != null) p.load(in);
            String u = p.getProperty("support", "").trim();
            return u.startsWith("@") ? "" : u;
        } catch (Exception e) {
            return "";
        }
    }

    static String proxy() { return PROXY; }

    /** JSON request to the score server (accounts). Returns the parsed object; throws with the server's error message on 4xx/5xx. */
    static Map<String, Object> send(String method, String path, Object body, String token) throws Exception {
        if (PROXY == null) throw new java.io.IOException("Accounts need the score server");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(PROXY + path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json").header("User-Agent", "FootballTrackerDesktop");
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> o = Json.obj(Json.parse(r.body().isBlank() ? "{}" : r.body()));
        if (r.statusCode() >= 400) throw new java.io.IOException(Json.str(o, "error").isEmpty() ? "Error " + r.statusCode() : Json.str(o, "error"));
        return o;
    }

    /** A developer key (env FOOTBALL_DATA_KEY or the saved one) talks to football-data.org directly; otherwise everything goes through the proxy. */
    static String base() {
        return Store.apiKey().isEmpty() && PROXY != null ? PROXY + "/v4" : API;
    }

    // ---- http

    static String getString(String url, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "Mozilla/5.0 FootballTrackerDiag");
        if (token != null) b.header("X-Auth-Token", token);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() >= 400) { // 429 = over 10 requests/min, 403 = key can't see that competition
            String body = r.body();
            throw new java.io.IOException("HTTP " + r.statusCode() + " " + url + " " + body.substring(0, Math.min(200, body.length())));
        }
        return r.body();
    }

    static byte[] getBytes(String url) throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "Mozilla/5.0 FootballTrackerDiag").build();
        return HTTP.send(rq, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private static String api(String url) throws Exception {
        String key = Store.apiKey();
        if (key.isEmpty() && PROXY == null) throw new java.io.IOException("no score server or API key configured");
        return getString(url, key.isEmpty() ? null : key);
    }

    // ---- matches

    private static String dash(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString();
    }

    static String ymd(long millis) {
        return dash(millis).replace("-", "");
    }

    static String url(String competitions, long from, long to) {
        return base() + "/matches?dateFrom=" + dash(from) + "&dateTo=" + dash(to) + (competitions == null ? "" : "&competitions=" + competitions);
    }

    static List<Match> fetchLeagues(List<String> leagues, int back, int fwd) throws Exception {
        long now = System.currentTimeMillis();
        return range(String.join(",", leagues), now - back * DAY, now + (fwd + 1) * DAY);
    }

    static List<String> allLeagueCodes() {
        List<String> out = new ArrayList<>();
        for (String[] l : LEAGUES) out.add(l[0]);
        return out;
    }

    /** Current data for a followed match (one request per match day, shared through cache), or null if the feed doesn't list it. */
    static Match lookup(Match f, Map<String, List<Match>> cache) throws Exception {
        List<Match> day = cache.get(ymd(f.when));
        if (day == null) cache.put(ymd(f.when), day = range(null, f.when - DAY, f.when + DAY));
        for (Match d : day) if (d.id.equals(f.id)) return d;
        return null;
    }

    static List<Match> range(String competitions, long from, long to) throws Exception {
        Map<String, Object> root = Json.obj(Json.parse(api(url(competitions, from, to))));
        List<Match> out = new ArrayList<>();
        for (Object o : Json.list(root.get("matches"))) {
            Map<String, Object> e = Json.obj(o), h = Json.obj(e.get("homeTeam")), a = Json.obj(e.get("awayTeam"));
            Match m = new Match();
            m.home = teamName(h);
            m.away = teamName(a);
            if (m.home.isEmpty() || m.away.isEmpty()) continue; // knockout tie with teams still to be decided
            m.league = Json.str(Json.obj(e.get("competition")), "code");
            m.id = String.valueOf(((Double) e.get("id")).longValue());
            m.when = Instant.parse(Json.str(e, "utcDate")).toEpochMilli();
            String st = Json.str(e, "status");
            switch (st) {
                case "IN_PLAY": case "PAUSED": case "LIVE":
                    m.state = "in";
                    m.status = st.equals("PAUSED") ? "HT" : e.get("minute") == null ? "Live" : ((Double) e.get("minute")).intValue() + "'";
                    break;
                case "FINISHED": case "AWARDED":
                    m.state = "post";
                    m.status = "FT";
                    break;
                case "POSTPONED": case "CANCELLED": case "SUSPENDED":
                    m.state = "post";
                    m.status = st.charAt(0) + st.substring(1).toLowerCase(Locale.ROOT);
                    break;
                default: // SCHEDULED, TIMED
                    m.state = "pre";
                    m.status = clock(m.when);
            }
            Map<String, Object> ft = Json.obj(Json.obj(e.get("score")).get("fullTime")); // current score while live
            if (ft.get("home") instanceof Double) m.hg = String.valueOf(((Double) ft.get("home")).intValue());
            if (ft.get("away") instanceof Double) m.ag = String.valueOf(((Double) ft.get("away")).intValue());
            m.homeLogo = logo(h, m.league);
            m.awayLogo = logo(a, m.league);
            out.add(m);
        }
        return out;
    }

    static List<Row> standings(String code) throws Exception {
        List<Object> groups = Json.list(Json.obj(Json.parse(api(base() + "/competitions/" + code + "/standings"))).get("standings"));
        Map<String, Object> pick = Json.obj(groups.get(0));
        for (Object g : groups) if ("TOTAL".equals(Json.str(Json.obj(g), "type"))) { pick = Json.obj(g); break; }
        List<Row> out = new ArrayList<>();
        for (Object o : Json.list(pick.get("table"))) {
            Map<String, Object> t = Json.obj(o), team = Json.obj(t.get("team"));
            Row r = new Row();
            r.pos = ((Double) t.get("position")).intValue();
            r.team = teamName(team);
            r.crest = logo(team, code);
            r.played = ((Double) t.get("playedGames")).intValue();
            r.gf = ((Double) t.get("goalsFor")).intValue();
            r.ga = ((Double) t.get("goalsAgainst")).intValue();
            r.pts = ((Double) t.get("points")).intValue();
            out.add(r);
        }
        return out;
    }

    private static String teamName(Map<String, Object> t) {
        String n = Json.str(t, "shortName");
        return n.isEmpty() ? Json.str(t, "name") : n;
    }

    static String clock(long when) {
        return DateTimeFormatter.ofPattern("HH:mm").format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(when), ZoneId.systemDefault()));
    }

    /** Badge URL. football-data's own image host is unreliable, so with a score server the badge comes from it (matched by team name). */
    static String logo(Map<String, Object> team, String comp) {
        if (PROXY != null) {
            return PROXY + "/logo?name=" + URLEncoder.encode(Json.str(team, "name"), StandardCharsets.UTF_8)
                    + "&short=" + URLEncoder.encode(teamName(team), StandardCharsets.UTF_8) + "&comp=" + URLEncoder.encode(comp, StandardCharsets.UTF_8);
        }
        String url = Json.str(team, "crest");
        return url.endsWith(".svg") ? "https://wsrv.nl/?url=" + url + "&w=96&h=96&output=png" : url; // ImageIO can't read SVG
    }

    static String leagueName(String code) {
        for (String[] l : LEAGUES) if (l[0].equals(code)) return l[1];
        return code;
    }

    static String bbcUrl(String code) {
        for (String[] l : LEAGUES) if (l[0].equals(code)) return "https://www.bbc.co.uk/sport/football/" + l[2] + "/scores-fixtures";
        return "https://www.bbc.co.uk/sport/football/scores-fixtures";
    }

    // ---- news

    static List<News> news(String team) throws Exception {
        String xml = getString("https://news.google.com/rss/search?q=" + URLEncoder.encode(team + " football", StandardCharsets.UTF_8) + "&hl=en-GB&gl=GB&ceid=GB:en", null);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList items = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getElementsByTagName("item");
        List<News> out = new ArrayList<>();
        for (int i = 0; i < items.getLength() && out.size() < 40; i++) {
            Element e = (Element) items.item(i);
            News n = new News();
            n.title = text(e, "title");
            n.link = text(e, "link");
            n.source = text(e, "source");
            if (!n.source.isEmpty() && n.title.endsWith(" - " + n.source)) n.title = n.title.substring(0, n.title.length() - n.source.length() - 3);
            try {
                long mins = Math.max(0, (System.currentTimeMillis() - ZonedDateTime.parse(text(e, "pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()) / 60_000);
                n.ago = mins < 60 ? mins + "m ago" : mins < 1440 ? mins / 60 + "h ago" : mins / 1440 + "d ago";
            } catch (Exception ignored) { }
            out.add(n);
        }
        return out;
    }

    private static String text(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? "" : l.item(0).getTextContent();
    }

    // ---- minimal JSON (the JDK has none)

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) { return new Json(s).value(); }

        /** Maps, lists, strings, numbers and booleans to JSON text. */
        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb);
            return sb.toString();
        }

        private static void write(Object o, StringBuilder sb) {
            if (o == null) sb.append("null");
            else if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    write(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            } else if (o instanceof Iterable) {
                sb.append('[');
                boolean first = true;
                for (Object x : (Iterable<?>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    write(x, sb);
                }
                sb.append(']');
            } else if (o instanceof Number || o instanceof Boolean) sb.append(o);
            else {
                sb.append('"');
                for (char c : String.valueOf(o).toCharArray()) {
                    if (c == '"' || c == '\\') sb.append('\\').append(c);
                    else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
                sb.append('"');
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }

        @SuppressWarnings("unchecked")
        static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }

        static String str(Map<String, Object> m, String k) { return m.get(k) instanceof String ? (String) m.get(k) : ""; }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                for (;;) {
                    ws();
                    String k = string();
                    ws();
                    i++; // ':'
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                for (;;) {
                    l.add(value());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }

        private String string() {
            i++;
            StringBuilder b = new StringBuilder();
            for (;;) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }
    }
}
