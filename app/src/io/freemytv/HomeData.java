package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional crypto prices (Binance, every 5 min) and weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Neither source needs a key. Location and currency come from config.properties.
 */
final class HomeData {
    /**
     * Local change: instead of crypto, ruble rates (Bank of Russia, via cbr-xml-daily.ru) and the Moscow Exchange
     * (index and shares, MOEX ISS). Neither needs a key. The first 4 switched on are shown (the card has 4 rows).
     */
    static final String[] COINS = {"USD", "EUR", "CNY", "IMOEX", "SBER", "GAZP", "LKOH", "YDEX"};
    static final String[] COIN_NAMES = {"Доллар", "Евро", "Юань", "Индекс МосБиржи", "Сбербанк", "Газпром", "Лукойл", "Яндекс"};
    /** On until the user changes them: the three currencies and the index. */
    static final int DEFAULT_ON = 4, MAX_SHOWN = 4;
    private static final int INDEX = 3;
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        // Local change: Open-Meteo is unreachable from Russia; wttr.in is the backup to met.no (often too slow from there).
        return "https://wttr.in/" + Prefs.latitude() + "," + Prefs.longitude() + "?format=j1";
    }

    /** The main source: MET Norway (no key; asks for an identifying User-Agent, which get() sends). */
    private static String metUrl() {
        return "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + round4(Prefs.latitude()) + "&lon=" + round4(Prefs.longitude());
    }

    /** met.no refuses more than 4 decimals. */
    private static String round4(String v) {
        return String.format(Locale.US, "%.4f", Double.parseDouble(v));
    }
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 30 * 60_000L;
    interface Listener { void onData(); }

    static int coinCount() {
        return Prefs.quotes() ? active().length : 0;
    }

    private static volatile int[] activeCoins;
    private static volatile int activeVersion = -1;

    /** Indexes in COINS of what the user put in the MAX_SHOWN places (Settings), in that order, without repeats. */
    static int[] active() {
        int[] a = activeCoins;
        if (a != null && activeVersion == Prefs.version) return a;
        int n = 0;
        int[] tmp = new int[MAX_SHOWN];
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int j = 0; j < MAX_SHOWN; j++) {
            int i = Prefs.slot(j);
            if (i >= 0 && i < COINS.length && seen.add(i)) tmp[n++] = i;
        }
        a = java.util.Arrays.copyOf(tmp, n);
        activeCoins = a;
        activeVersion = Prefs.version;
        return a;
    }

    /** Settings changed (city, coins): fetch again now. */
    void settingsChanged() {
        pricesAt = 0;
        weatherAt = 0;
        pricesFailedAt = 0;
        weatherFailedAt = 0;
    }

    static String coinName(int i) {
        return COIN_NAMES[i];
    }

    /** All three answers in one object (what gets cached): {"cbr": …, "index": …, "shares": …}. */
    private static String getPrices() throws Exception {
        JSONObject all = new JSONObject();
        all.put("cbr", new JSONObject(get("https://www.cbr-xml-daily.ru/daily_json.js")));
        all.put("index", new JSONObject(get("https://iss.moex.com/iss/engines/stock/markets/index/securities/IMOEX.json"
                + "?iss.meta=off&iss.only=marketdata&marketdata.columns=SECID,CURRENTVALUE,LASTCHANGEPRC")));
        StringBuilder shares = new StringBuilder();
        for (int i = INDEX + 1; i < COINS.length; i++) shares.append(shares.length() == 0 ? "" : ",").append(COINS[i]);
        all.put("shares", new JSONObject(get("https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities.json"
                + "?iss.meta=off&iss.only=marketdata&securities=" + shares + "&marketdata.columns=SECID,LAST,LCURRENTPRICE,LASTTOPREVPRICE")));
        return all.toString();
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = new double[COINS.length];
    /** Change since the previous day (%), in the same order. */
    final double[] changes = new double[COINS.length];
    {
        java.util.Arrays.fill(prices, Double.NaN);
        java.util.Arrays.fill(changes, Double.NaN);
    }
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;
    private volatile long pricesFailedAt, weatherFailedAt;

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            if (Prefs.quotes()) {
                pricesAt = cache.getLong("pricesAt", 0);
                String p = cache.getString("prices", null);
                if (p != null) parsePrices(p);   // resets pricesAt if the cache is from the crypto version
            }
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                weatherAt = cache.getLong("weatherAt", 0);
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > PRICES_EVERY || now < pricesAt) && !failedRecently(pricesFailedAt, now)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body = getPrices();
                    parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    pricesFailedAt = System.currentTimeMillis();
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String body;
                    try {
                        body = get(metUrl());
                    } catch (IOException e) {
                        AppLog.w("weather: met.no " + e.getMessage() + ", trying wttr.in");
                        body = get(weatherUrl());
                    }
                    weather = parseWeather(body);
                    weatherAt = System.currentTimeMillis();
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", weatherPlace()).apply();
                    notifyListeners();
                } catch (Exception e) {
                    weatherFailedAt = System.currentTimeMillis();
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 2 * 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private void parsePrices(String body) throws Exception {
        if (!body.startsWith("{")) {   // Binance's list, cached by the crypto version: fetch again
            pricesAt = 0;
            return;
        }
        JSONObject all = new JSONObject(body);
        // Bank of Russia: today's official rate and the previous one (per Nominal units: 1 for these three).
        JSONObject valutes = all.getJSONObject("cbr").getJSONObject("Valute");
        for (int i = 0; i < INDEX; i++) {
            JSONObject v = valutes.optJSONObject(COINS[i]);
            if (v == null) continue;
            double nominal = v.optDouble("Nominal", 1), now = v.getDouble("Value") / nominal, before = v.optDouble("Previous", Double.NaN) / nominal;
            prices[i] = now;
            changes[i] = before > 0 ? (now - before) / before * 100 : Double.NaN;
        }
        // MOEX ISS: rows of [SECID, price, change %]; outside trading hours LAST is empty and LCURRENTPRICE has the price.
        moex(all.getJSONObject("index"), 1, -1, 2);
        moex(all.getJSONObject("shares"), 1, 2, 3);
    }

    private void moex(JSONObject o, int price, int fallback, int change) throws Exception {
        JSONArray rows = o.getJSONObject("marketdata").getJSONArray("data");
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.getJSONArray(r);
            int i = java.util.Arrays.asList(COINS).indexOf(row.getString(0));
            if (i < 0) continue;
            double p = row.isNull(price) && fallback >= 0 ? row.optDouble(fallback, Double.NaN) : row.optDouble(price, Double.NaN);
            if (!Double.isNaN(p)) prices[i] = p;
            changes[i] = row.optDouble(change, Double.NaN);
        }
    }

    /** The value as written in the card: "80,54 ₽", "6 125 ₽", and the index without the ruble sign ("2 464,06"). */
    static String quoteText(int i, double v) {
        if (i != INDEX) return brl(v);
        if (Double.isNaN(v)) return "—";
        NumberFormat f = NumberFormat.getNumberInstance(locale());
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(v);
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        if (o.has("current_condition")) return parseWttr(o);
        if (o.has("properties")) return parseMet(o);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    /** wttr.in's j1 answer: times are the place's own (the TV is in the same zone), hourly every 3 hours. */
    private static Weather parseWttr(JSONObject o) throws Exception {
        TimeZone zone = TimeZone.getDefault();
        Weather w = new Weather();
        JSONObject current = o.getJSONArray("current_condition").getJSONObject(0);
        w.temp = current.getDouble("temp_C");
        w.code = wmo(current.getInt("weatherCode"));
        JSONArray days = o.getJSONArray("weather");
        JSONObject today = days.getJSONObject(0);
        w.max = today.getDouble("maxtempC");
        w.min = today.getDouble("mintempC");
        String date = today.getString("date");
        JSONObject sun = today.getJSONArray("astronomy").getJSONObject(0);
        w.sunrise = wttrTime(date, sun.getString("sunrise"), zone);
        w.sunset = wttrTime(date, sun.getString("sunset"), zone);
        long now = System.currentTimeMillis();
        w.isDay = now >= w.sunrise && now < w.sunset;
        for (int d = 0; d < days.length() && w.hours.size() < 6; d++) {
            JSONObject day = days.getJSONObject(d);
            String dayDate = day.getString("date");
            JSONObject daySun = day.getJSONArray("astronomy").getJSONObject(0);
            long rise = wttrTime(dayDate, daySun.getString("sunrise"), zone), set = wttrTime(dayDate, daySun.getString("sunset"), zone);
            JSONArray hourly = day.getJSONArray("hourly");
            for (int i = 0; i < hourly.length() && w.hours.size() < 6; i++) {
                JSONObject h = hourly.getJSONObject(i);
                int hhmm = h.getInt("time");   // "0", "300" … "2100"
                long at = localTime(dayDate + String.format(Locale.US, "T%02d:%02d", hhmm / 100, hhmm % 100), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(h.getDouble("tempC"));
                w.hourCode.add(wmo(h.getInt("weatherCode")));
                w.hourDay.add(at >= rise && at < set);
            }
        }
        return w;
    }

    /** met.no: hourly timeseries in UTC; today's high and low from its hours, sunrise and sunset worked out here. */
    private static Weather parseMet(JSONObject o) throws Exception {
        JSONArray series = o.getJSONObject("properties").getJSONArray("timeseries");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        double lat = Double.parseDouble(Prefs.latitude()), lon = Double.parseDouble(Prefs.longitude());
        long now = System.currentTimeMillis();
        long[] sun = sunTimes(lat, lon, now);
        Weather w = new Weather();
        w.sunrise = sun[0];
        w.sunset = sun[1];
        w.isDay = now >= w.sunrise && now < w.sunset;
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(now);
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        long dayEnd = end.getTimeInMillis();
        for (int i = 0; i < series.length(); i++) {
            JSONObject t = series.getJSONObject(i);
            long at = iso.parse(t.getString("time")).getTime();
            JSONObject data = t.getJSONObject("data");
            double temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
            JSONObject next = data.optJSONObject("next_1_hours");
            if (next == null) next = data.optJSONObject("next_6_hours");
            int code = next == null ? -1 : metCode(next.getJSONObject("summary").optString("symbol_code"));
            if (Double.isNaN(w.temp) && at > now - 3600_000L) {   // the hour we're in
                w.temp = temp;
                w.code = code;
            }
            if (at > now - 3600_000L && at <= dayEnd) {
                w.max = Double.isNaN(w.max) ? temp : Math.max(w.max, temp);
                w.min = Double.isNaN(w.min) ? temp : Math.min(w.min, temp);
            }
            if (at > now && w.hours.size() < 6 && code >= 0) {
                long[] s = sunTimes(lat, lon, at);
                w.hours.add(at);
                w.hourTemp.add(temp);
                w.hourCode.add(code);
                w.hourDay.add(at >= s[0] && at < s[1]);
            }
        }
        if (Double.isNaN(w.temp)) throw new IOException("met.no: no current hour");
        return w;
    }

    /** met.no's symbol ("lightrainshowers_day") → WMO code. */
    private static int metCode(String symbol) {
        String s = symbol.replaceAll("_(day|night|polartwilight)$", "");
        if (s.contains("thunder")) return 95;
        switch (s) {
            case "clearsky": return 0;
            case "fair": return 1;
            case "partlycloudy": return 2;
            case "cloudy": return 3;
            case "fog": return 45;
            case "lightrain": return 61;
            case "rain": return 63;
            case "heavyrain": return 65;
            case "lightrainshowers": return 80;
            case "rainshowers": return 81;
            case "heavyrainshowers": return 82;
            case "lightsleet": case "lightsleetshowers": return 66;
            case "sleet": case "heavysleet": case "sleetshowers": case "heavysleetshowers": return 67;
            case "lightsnow": return 71;
            case "snow": return 73;
            case "heavysnow": return 75;
            case "lightsnowshowers": case "snowshowers": return 85;
            case "heavysnowshowers": return 86;
            default: return 3;
        }
    }

    /** Sunrise and sunset (epoch ms) of the local day containing `when`, by the standard sunrise equation. */
    private static long[] sunTimes(double lat, double lon, long when) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(when);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 12, 0, 0);
        double jd = utc.getTimeInMillis() / 86400000.0 + 2440587.5;
        double n = Math.ceil(jd - 2451545.0 + 0.0008);
        double jStar = n - lon / 360.0;
        double m = (357.5291 + 0.98560028 * jStar) % 360;
        double mr = Math.toRadians(m);
        double c = 1.9148 * Math.sin(mr) + 0.02 * Math.sin(2 * mr) + 0.0003 * Math.sin(3 * mr);
        double lambda = Math.toRadians((m + c + 180 + 102.9372) % 360);
        double transit = 2451545.0 + jStar + 0.0053 * Math.sin(mr) - 0.0069 * Math.sin(2 * lambda);
        double decl = Math.asin(Math.sin(lambda) * Math.sin(Math.toRadians(23.4397)));
        double phi = Math.toRadians(lat);
        double cosW = (Math.sin(Math.toRadians(-0.833)) - Math.sin(phi) * Math.sin(decl)) / (Math.cos(phi) * Math.cos(decl));
        cosW = Math.max(-1, Math.min(1, cosW));   // polar day or night: the sun stays up or down all day
        double w0 = Math.toDegrees(Math.acos(cosW));
        return new long[] {(long) ((transit - w0 / 360 - 2440587.5) * 86400000.0), (long) ((transit + w0 / 360 - 2440587.5) * 86400000.0)};
    }

    /** "2026-10-23" + "06:58 AM" → that moment. */
    private static long wttrTime(String date, String time, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US);
        f.setTimeZone(zone);
        return f.parse(date + " " + time.trim()).getTime();
    }

    /** wttr.in's (WorldWeatherOnline) condition codes → the WMO codes the icons go by. */
    private static int wmo(int code) {
        switch (code) {
            case 113: return 0;                                   // clear
            case 116: return 2;                                   // partly cloudy
            case 119: case 122: return 3;                         // cloudy, overcast
            case 143: case 248: return 45;                        // mist, fog
            case 260: return 48;                                  // freezing fog
            case 263: case 266: return 51;                        // drizzle
            case 281: case 284: return 56;                        // freezing drizzle
            case 176: case 293: case 296: return 61;              // light rain
            case 299: case 302: return 63;                        // rain
            case 305: case 308: return 65;                        // heavy rain
            case 311: case 182: case 185: case 317: return 66;    // freezing rain, sleet
            case 314: case 320: return 67;
            case 179: case 323: case 326: return 71;              // light snow
            case 329: case 332: return 73;                        // snow
            case 227: case 230: case 335: case 338: return 75;    // heavy snow, blizzard
            case 350: case 374: case 377: return 77;              // ice pellets
            case 353: return 80;                                  // rain showers
            case 356: return 81;
            case 359: return 82;
            case 362: case 365: case 368: return 85;              // snow showers
            case 371: return 86;
            case 200: case 386: case 389: return 95;              // thunder
            case 392: case 395: return 96;                        // thunder with snow
            default: return 3;
        }
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static List<String[]> findCity(String name) throws Exception {
        String lang = Ui.language.replaceAll("-.*", "");   // Open-Meteo takes the language without the country
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);   // wttr.in can take several seconds
        c.setRequestProperty("User-Agent", "FreeMyTV github.com/simbasjungles/free-my-tv");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
    static String price(double v) {
        return compact(v);
    }

    /** 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        // Rubles: 80,54 · 291,9 · 2464 · 6126 (no "k": these are all small enough to read whole)
        if (Double.isNaN(v)) return "—";
        if (v >= 1000) return String.format(locale(), "%.0f", v);
        if (v >= 100) return String.format(locale(), "%.1f", v);
        return String.format(locale(), "%.2f", v);
    }

    /**
     * $108,234 · $185.20 · R$ 581.234 · 74 264 € (cents only below a thousand), in the configured currency, with the
     * symbol and spaces where the language puts them.
     */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        return Ui.once("brl", v, () -> {
            DecimalFormat f = (DecimalFormat) NumberFormat.getCurrencyInstance(locale());
            DecimalFormatSymbols s = f.getDecimalFormatSymbols();
            s.setCurrencySymbol(symbol.trim());   // our symbols: ICU would write "US$" outside the US
            f.setDecimalFormatSymbols(s);
            f.setMinimumFractionDigits(v >= 1000 ? 0 : 2);
            f.setMaximumFractionDigits(v >= 1000 ? 0 : 2);
            return f.format(v);
        });
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return day ? Ui.tr("Clear") : Ui.tr("night|Clear");
        if (code == 1) return day ? Ui.tr("Mostly Sunny") : Ui.tr("Mostly Clear");
        if (code == 2) return Ui.tr("Partly Cloudy");
        if (code == 3) return Ui.tr("Cloudy");
        if (code == 45 || code == 48) return Ui.tr("Fog");
        if (code >= 51 && code <= 57) return Ui.tr("Drizzle");
        if (code == 65 || code == 67 || code == 82) return Ui.tr("Heavy Rain");
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Ui.tr("Snow");
        if (code >= 95) return Ui.tr("Thunderstorms");
        return Ui.tr("Rain");
    }

    /** "H:24°  L:15°" (in Portuguese, "Máx. 24°  Mín. 15°"), with the given space between the two. */
    static String highLow(Weather wx, String space) {
        return Ui.trf("H:{max}", "max", degrees(wx.max)) + space + Ui.trf("L:{min}", "min", degrees(wx.min));
    }

    private static String currencySymbol() {
        return "₽";   // everything here is in rubles
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign); "+1,2 %" in the languages that space the percent sign. */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return Ui.once("change", v, () -> {
            NumberFormat f = NumberFormat.getPercentInstance(locale());
            f.setMinimumFractionDigits(1);
            f.setMaximumFractionDigits(1);
            return (v >= 0 ? "+" : "−") + f.format(Math.abs(v) / 100);
        });
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> Ui.datePattern("format|h a").format(at));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        return minutes < 60 ? Ui.trf("{n} min ago", "n", minutes) : Ui.trf("{n} h ago", "n", minutes / 60);
    }
}
