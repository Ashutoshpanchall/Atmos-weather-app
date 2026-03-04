package com.example.atmos;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * ShadowFox Android Internship — Weather App
 *
 * ═══════════════════════════════════════════════════════════
 *  APIS USED:
 *   1. OpenWeatherMap  → temperature, wind, humidity, forecast
 *      Key required → paste into OWM_API_KEY below
 *      Sign up: https://openweathermap.org/api
 *
 *   2. Open-Meteo      → UV Index (FREE, no key needed)
 *      https://open-meteo.com/en/docs
 *
 *   3. OpenAQ          → Air Quality / AQI (FREE, no key needed)
 *      https://docs.openaq.org/
 * ═══════════════════════════════════════════════════════════
 */
public class MainActivity extends AppCompatActivity {

    // ── API Keys & Base URLs ─────────────────────────────────────
    private static final String OWM_API_KEY  = "c56724fdd1d74c68edcd6ac522c91b1b";
    private static final String OWM_BASE_URL = "https://api.openweathermap.org/data/2.5/";
    private static final String OPENAQ_URL   = "https://api.openaq.org/v2/latest";

    // ── Tap-to-website URLs ──────────────────────────────────────
    private static final String SOURCE_TEMP     = "https://openweathermap.org/";
    private static final String SOURCE_AQI      = "https://openaq.org/";
    private static final String SOURCE_UV       = "https://open-meteo.com/";
    private static final String SOURCE_WIND     = "https://openweathermap.org/";
    private static final String SOURCE_HUMIDITY = "https://openweathermap.org/";

    private static final int LOCATION_PERMISSION_REQUEST = 101;

    // ── Threading ────────────────────────────────────────────────
    private final ExecutorService executor  = Executors.newFixedThreadPool(3);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // ── State ────────────────────────────────────────────────────
    private double currentLat   = 0;
    private double currentLon   = 0;
    private long   sunriseEpoch = 0;
    private long   sunsetEpoch  = 0;

    // ── Header views ────────────────────────────────────────────
    private ImageView imgBackground;
    private TextView  tvLocation, tvTemperature, tvCondition, tvRealFeel;
    private TextView  tvSunrise, tvSunset;

    // ── Condition card value TextViews (unique IDs per card) ─────
    private TextView tvAqiValue, tvUvValue, tvWindValue, tvHumidityValue;

    // ── Clickable card root views ────────────────────────────────
    private CardView cardAqi, cardUv, cardWind, cardHumidity;

    // ── RecyclerViews ────────────────────────────────────────────
    private RecyclerView rvHourly, rvDaily;
    private HourlyAdapter hourlyAdapter;
    private DailyAdapter  dailyAdapter;

    // ── Data models ──────────────────────────────────────────────
    static class HourlyItem {
        String time, temp;
        int    iconRes;
        HourlyItem(String time, int iconRes, String temp) {
            this.time = time; this.iconRes = iconRes; this.temp = temp;
        }
    }

    static class DailyItem {
        String day, high, low;
        int    iconRes;
        DailyItem(String day, int iconRes, String high, String low) {
            this.day = day; this.iconRes = iconRes; this.high = high; this.low = low;
        }
    }

    // ════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full-screen immersive (content goes behind status bar)
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        setContentView(R.layout.activity_main);
        bindViews();
        setupAdapters();
        setupClickListeners();
        requestLocationAndFetch();
    }

    // ── Bind every view by its unique ID ────────────────────────
    private void bindViews() {
        // Background & overlay
        imgBackground = findViewById(R.id.imgBackground);

        // Header
        tvLocation    = findViewById(R.id.tvLocation);
        tvTemperature = findViewById(R.id.tvTemperature);
        tvCondition   = findViewById(R.id.tvCondition);
        tvRealFeel    = findViewById(R.id.tvRealFeel);
        tvSunrise     = findViewById(R.id.tvSunrise);
        tvSunset      = findViewById(R.id.tvSunset);

        // Condition card roots (for click listeners)
        cardAqi      = findViewById(R.id.cardAqi);
        cardUv       = findViewById(R.id.cardUv);
        cardWind     = findViewById(R.id.cardWind);
        cardHumidity = findViewById(R.id.cardHumidity);

        // Condition card value labels (unique IDs — no ambiguity)
        tvAqiValue      = findViewById(R.id.tvAqiValue);
        tvUvValue       = findViewById(R.id.tvUvValue);
        tvWindValue     = findViewById(R.id.tvWindValue);
        tvHumidityValue = findViewById(R.id.tvHumidityValue);

        // RecyclerViews
        rvHourly = findViewById(R.id.rvHourlyForecast);
        rvDaily  = findViewById(R.id.rvDailyForecast);
    }

    // ── Setup adapters ───────────────────────────────────────────
    private void setupAdapters() {
        hourlyAdapter = new HourlyAdapter(new ArrayList<>());
        rvHourly.setAdapter(hourlyAdapter);
        rvHourly.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));

        dailyAdapter = new DailyAdapter(new ArrayList<>());
        rvDaily.setAdapter(dailyAdapter);
        rvDaily.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
    }

    // ── Click listeners ──────────────────────────────────────────
    private void setupClickListeners() {

        tvTemperature.setOnClickListener(v -> openUrl(SOURCE_TEMP));
        cardAqi.setOnClickListener(v -> openUrl(SOURCE_AQI));
        cardUv.setOnClickListener(v -> openUrl(SOURCE_UV));
        cardWind.setOnClickListener(v -> openUrl(SOURCE_WIND));
        cardHumidity.setOnClickListener(v -> openUrl(SOURCE_HUMIDITY));

        ImageButton btnRefresh = findViewById(R.id.btnRefresh);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> requestLocationAndFetch());
        }

        ImageButton btnLocation = findViewById(R.id.btnLocationChange);
        if (btnLocation != null) {
            btnLocation.setOnClickListener(v -> showLocationInputDialog());
        }

        // ✅ SETTINGS BUTTON
        ImageView btnSettings = findViewById(R.id.btnSettings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showAppInfo());
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open browser", Toast.LENGTH_SHORT).show();
        }
    }

    // ════════════════════════════════════════════════════════════
    // LOCATION
    // ════════════════════════════════════════════════════════════
    private void requestLocationAndFetch() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
            return;
        }
        fetchLastKnownLocation();
    }

    private void fetchLastKnownLocation() {
        executor.execute(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) return;

                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                Location loc = null;

                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
                if (loc == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }

                if (loc != null) {
                    currentLat = loc.getLatitude();
                    currentLon = loc.getLongitude();
                    resolveLocationName(currentLat, currentLon);
                    fetchWeather(currentLat, currentLon);
                    fetchAirQuality(currentLat, currentLon);
                } else {
                    mainHandler.post(() -> Toast.makeText(this,
                            "Location unavailable. Enter city manually.",
                            Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void resolveLocationName(double lat, double lon) {
        try {
            Geocoder gc = new Geocoder(this, Locale.getDefault());
            List<Address> addresses = gc.getFromLocation(lat, lon, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address a = addresses.get(0);
                String city  = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
                String state = a.getAdminArea();
                String label = (city  != null ? city  : "")
                        + (state != null ? ", " + state : "");
                mainHandler.post(() -> tvLocation.setText(
                        label.trim().isEmpty() ? "Unknown Location" : label));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ════════════════════════════════════════════════════════════
    // WEATHER — OpenWeatherMap
    // ════════════════════════════════════════════════════════════
    private void fetchWeather(double lat, double lon) {
        executor.execute(() -> {
            // Current weather
            String currentJson = httpGet(OWM_BASE_URL
                    + "weather?lat=" + lat + "&lon=" + lon
                    + "&appid=" + OWM_API_KEY + "&units=metric");
            if (currentJson != null) parseCurrentWeather(currentJson);

            // 5-day / 3-hour forecast
            String forecastJson = httpGet(OWM_BASE_URL
                    + "forecast?lat=" + lat + "&lon=" + lon
                    + "&appid=" + OWM_API_KEY + "&units=metric");
            if (forecastJson != null) parseForecast(forecastJson);
        });
    }

    private void parseCurrentWeather(String json) {
        try {
            JSONObject root = new JSONObject(json);

            // Main fields
            JSONObject main  = root.getJSONObject("main");
            double temp      = main.getDouble("temp");
            double feelsLike = main.getDouble("feels_like");
            int    humidity  = main.getInt("humidity");

            // Wind
            double windKmh = root.getJSONObject("wind").getDouble("speed") * 3.6;

            // Condition
            String description = root.getJSONArray("weather")
                    .getJSONObject(0).getString("description");
            description = Character.toUpperCase(description.charAt(0))
                    + description.substring(1);

            // Sunrise / sunset
            JSONObject sys = root.getJSONObject("sys");
            sunriseEpoch   = sys.getLong("sunrise") * 1000L;
            sunsetEpoch    = sys.getLong("sunset")  * 1000L;

            // Fetch UV separately (Open-Meteo, free, no key)
            fetchUvIndex(currentLat, currentLon);

            // Format display strings
            SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.getDefault());
            final String tempStr      = Math.round(temp) + "°";
            final String feelStr      = "RealFeel® " + Math.round(feelsLike) + "°";
            final String condStr      = description;
            final String sunriseStr   = sdf.format(new Date(sunriseEpoch));
            final String sunsetStr    = sdf.format(new Date(sunsetEpoch));
            final String windStr      = Math.round(windKmh) + " km/h";
            final String humStr       = humidity + "%";
            final boolean isDay       = isItDaytime(sunriseEpoch, sunsetEpoch);

            mainHandler.post(() -> {
                tvTemperature.setText(tempStr);
                tvRealFeel.setText(feelStr);
                tvCondition.setText(condStr);
                tvSunrise.setText(sunriseStr);
                tvSunset.setText(sunsetStr);

                // Background switches based on real sunrise/sunset data
                imgBackground.setImageResource(
                        isDay ? R.drawable.bg_day : R.drawable.bg_night);

                // Update Wind and Humidity card values directly
                tvWindValue.setText(windStr);
                tvHumidityValue.setText(humStr);
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void parseForecast(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray  list = root.getJSONArray("list");

            List<HourlyItem> hourlyItems = new ArrayList<>();
            List<DailyItem>  dailyItems  = new ArrayList<>();

            SimpleDateFormat hourFmt = new SimpleDateFormat("h a", Locale.getDefault());
            SimpleDateFormat dayFmt  = new SimpleDateFormat("EEE", Locale.getDefault());
            SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

            // ───────── HOURLY (next 24h) ─────────
            for (int i = 0; i < Math.min(list.length(), 8); i++) {
                JSONObject entry = list.getJSONObject(i);
                long   dt   = entry.getLong("dt") * 1000L;
                double temp = entry.getJSONObject("main").getDouble("temp");
                String cond = entry.getJSONArray("weather")
                        .getJSONObject(0)
                        .getString("main");

                hourlyItems.add(new HourlyItem(
                        hourFmt.format(new Date(dt)),
                        iconResForCondition(cond),
                        Math.round(temp) + "°"
                ));
            }

            // ───────── DAILY GROUPING ─────────
            List<DailyItem> allDays = new ArrayList<>();

            String lastDate = "";
            float  dayHigh  = Float.MIN_VALUE;
            float  dayLow   = Float.MAX_VALUE;
            String dayIcon  = "";
            String dayName  = "";

            String todayDate = dateFmt.format(new Date());

            for (int i = 0; i < list.length(); i++) {

                JSONObject entry  = list.getJSONObject(i);
                long       dt     = entry.getLong("dt") * 1000L;
                String     dateKey= dateFmt.format(new Date(dt));
                float      temp   = (float) entry.getJSONObject("main").getDouble("temp");
                String     cond   = entry.getJSONArray("weather")
                        .getJSONObject(0)
                        .getString("main");

                if (!dateKey.equals(lastDate)) {

                    if (!lastDate.isEmpty()) {
                        allDays.add(new DailyItem(
                                dayName,
                                iconResForCondition(dayIcon),
                                Math.round(dayHigh) + "°",
                                Math.round(dayLow)  + "°"
                        ));
                    }

                    lastDate = dateKey;
                    dayHigh  = temp;
                    dayLow   = temp;
                    dayIcon  = cond;
                    dayName  = dayFmt.format(new Date(dt));

                } else {

                    if (temp > dayHigh) {
                        dayHigh = temp;
                        dayIcon = cond;
                    }

                    if (temp < dayLow) {
                        dayLow = temp;
                    }
                }
            }

            // Add last grouped day
            if (!lastDate.isEmpty()) {
                allDays.add(new DailyItem(
                        dayName,
                        iconResForCondition(dayIcon),
                        Math.round(dayHigh) + "°",
                        Math.round(dayLow)  + "°"
                ));
            }

            // ───────── REMOVE TODAY ─────────
            for (DailyItem item : allDays) {
                if (dailyItems.size() == 6) break;

                // Compare using day name won't be reliable,
                // so skip first item (which is today)
                if (!item.day.equals(
                        new SimpleDateFormat("EEE", Locale.getDefault())
                                .format(new Date()))) {

                    dailyItems.add(item);
                }
            }

            mainHandler.post(() -> {
                hourlyAdapter.updateData(hourlyItems);
                dailyAdapter.updateData(dailyItems);
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ════════════════════════════════════════════════════════════
    // UV INDEX — Open-Meteo (no API key needed)
    // ════════════════════════════════════════════════════════════
    private void fetchUvIndex(double lat, double lon) {
        executor.execute(() -> {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon
                    + "&daily=uv_index_max&timezone=auto&forecast_days=1";
            String resp = httpGet(url);
            if (resp == null) return;
            try {
                double uv = new JSONObject(resp)
                        .getJSONObject("daily")
                        .getJSONArray("uv_index_max")
                        .getDouble(0);
                String label = uvLabel(uv);
                mainHandler.post(() -> tvUvValue.setText(label));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // ════════════════════════════════════════════════════════════
    // AIR QUALITY — OpenAQ (no API key needed)
    // ════════════════════════════════════════════════════════════
    private void fetchAirQuality(double lat, double lon) {

        executor.execute(() -> {

            String url = OWM_BASE_URL +
                    "air_pollution?lat=" + lat +
                    "&lon=" + lon +
                    "&appid=" + OWM_API_KEY;

            String resp = httpGet(url);

            if (resp == null) {
                mainHandler.post(() -> tvAqiValue.setText("N/A"));
                return;
            }

            try {
                JSONObject root = new JSONObject(resp);
                JSONArray list = root.getJSONArray("list");

                if (list.length() == 0) {
                    mainHandler.post(() -> tvAqiValue.setText("N/A"));
                    return;
                }

                JSONObject components = list.getJSONObject(0)
                        .getJSONObject("components");

                double pm25 = components.getDouble("pm2_5");

                int realAqi = calculateUS_AQI(pm25);
                String category = aqiCategory(realAqi);

                mainHandler.post(() -> {

                    tvAqiValue.setText(realAqi + " (" + category + ")");

                    int color;

                    if (realAqi <= 50)
                        color = 0xFF4CAF50;      // Green
                    else if (realAqi <= 100)
                        color = 0xFFFFC107;      // Yellow
                    else if (realAqi <= 150)
                        color = 0xFFFF9800;      // Orange
                    else if (realAqi <= 200)
                        color = 0xFFF44336;      // Red
                    else if (realAqi <= 300)
                        color = 0xFF9C27B0;      // Purple
                    else
                        color = 0xFF7E0023;      // Maroon

                    // Change badge background color
                    if (tvAqiValue.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
                        android.graphics.drawable.GradientDrawable bg =
                                (android.graphics.drawable.GradientDrawable) tvAqiValue.getBackground();
                        bg.setColor(color);
                    }

                    tvAqiValue.setTextColor(0xFFFFFFFF);
                });

            } catch (Exception e) {
                mainHandler.post(() -> tvAqiValue.setText("N/A"));
            }
        });
    }
    // US EPA AQI calculation from PM2.5 concentration
    private int calculateUS_AQI(double pm25) {

        double c = pm25;

        if (c <= 12.0)
            return (int) ((50.0 / 12.0) * c);

        else if (c <= 35.4)
            return (int) (((100 - 51) / (35.4 - 12.1)) * (c - 12.1) + 51);

        else if (c <= 55.4)
            return (int) (((150 - 101) / (55.4 - 35.5)) * (c - 35.5) + 101);

        else if (c <= 150.4)
            return (int) (((200 - 151) / (150.4 - 55.5)) * (c - 55.5) + 151);

        else if (c <= 250.4)
            return (int) (((300 - 201) / (250.4 - 150.5)) * (c - 150.5) + 201);

        else if (c <= 350.4)
            return (int) (((400 - 301) / (350.4 - 250.5)) * (c - 250.5) + 301);

        else
            return 500;
    }
    private String aqiCategory(int aqi) {

        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Unhealthy (Sensitive)";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very Unhealthy";
        return "Hazardous";
    }

    // ════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════

    /**
     * Returns true if the current time is between sunrise and sunset.
     * Falls back to hour-based check when API data is unavailable (offline).
     */
    private boolean isItDaytime(long sunriseMs, long sunsetMs) {
        long now = System.currentTimeMillis();
        if (sunriseMs == 0 && sunsetMs == 0) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return hour >= 6 && hour < 19;
        }
        return now >= sunriseMs && now < sunsetMs;
    }

    private int iconResForCondition(String condition) {
        if (condition == null) return R.drawable.ic_weather_sunny;
        switch (condition.toLowerCase()) {
            case "rain":
            case "drizzle":
            case "thunderstorm": return R.drawable.ic_weather_rainy;
            case "clouds":
            case "snow":
            case "mist":
            case "fog":
            case "haze":         return R.drawable.ic_weather_cloudy;
            default:             return R.drawable.ic_weather_sunny;
        }
    }

    /** PM2.5 µg/m³ → category label (US AQI approximate) */
    private String aqiLabel(double pm25) {
        if (pm25 <= 12)  return "Good";
        if (pm25 <= 35)  return "Moderate";
        if (pm25 <= 55)  return "Unhealthy*";
        if (pm25 <= 150) return "Unhealthy";
        return "Hazardous";
    }

    private String uvLabel(double uv) {
        if (uv < 3)  return "Low";
        if (uv < 6)  return "Moderate";
        if (uv < 8)  return "High";
        if (uv < 11) return "Very High";
        return "Extreme";
    }

    /** Simple blocking HTTP GET — must be called on a background thread only. */
    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.connect();
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Manual city search dialog ────────────────────────────────
    private void showLocationInputDialog() {
        EditText et = new EditText(this);
        et.setHint("Enter city name…");
        et.setPadding(40, 20, 40, 20);
        new AlertDialog.Builder(this)
                .setTitle("Change Location")
                .setView(et)
                .setPositiveButton("Search", (d, w) -> {
                    String city = et.getText().toString().trim();
                    if (!city.isEmpty()) geocodeCity(city);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void geocodeCity(String city) {
        executor.execute(() -> {
            try {
                Geocoder gc = new Geocoder(this, Locale.getDefault());
                List<Address> results = gc.getFromLocationName(city, 1);
                if (results != null && !results.isEmpty()) {
                    Address a = results.get(0);
                    currentLat = a.getLatitude();
                    currentLon = a.getLongitude();
                    String label = (a.getLocality() != null ? a.getLocality() : city)
                            + (a.getAdminArea() != null ? ", " + a.getAdminArea() : "");
                    mainHandler.post(() -> tvLocation.setText(label));
                    fetchWeather(currentLat, currentLon);
                    fetchAirQuality(currentLat, currentLon);
                } else {
                    mainHandler.post(() ->
                            Toast.makeText(this, "City not found", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchLastKnownLocation();
            } else {
                Toast.makeText(this,
                        "Location denied. Enter city manually.",
                        Toast.LENGTH_LONG).show();
                tvLocation.setText("Unknown Location");
            }
        }
    }
    // ───────────────────────────────────────────────────────────
// SETTINGS / ABOUT DIALOG
// ───────────────────────────────────────────────────────────
    private void showAppInfo() {

        String message =
                "Weather data sources:\n\n" +
                        "• OpenWeatherMap API\n" +
                        "• Open-Meteo API\n" +
                        "• OpenAQ API\n\n" +
                        "Data updates in real-time using live API responses.\n\n" +
                        "Developed by\n" +
                        "Ashutosh Panchal";

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("About Atmos")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }
    @Override

    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ════════════════════════════════════════════════════════════
    // RECYCLERVIEW ADAPTERS
    // ════════════════════════════════════════════════════════════

    static class HourlyAdapter extends RecyclerView.Adapter<HourlyAdapter.VH> {
        private List<HourlyItem> data;
        HourlyAdapter(List<HourlyItem> data) { this.data = data; }

        void updateData(List<HourlyItem> newData) {
            data = newData;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hourly_forecast, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            HourlyItem item = data.get(pos);
            h.tvTime.setText(item.time);
            h.ivIcon.setImageResource(item.iconRes);
            h.tvTemp.setText(item.temp);
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView  tvTime, tvTemp;
            ImageView ivIcon;
            VH(@NonNull View v) {
                super(v);
                tvTime = v.findViewById(R.id.tvHourTime);
                ivIcon = v.findViewById(R.id.ivHourIcon);
                tvTemp = v.findViewById(R.id.tvHourTemp);
            }
        }
    }

    static class DailyAdapter extends RecyclerView.Adapter<DailyAdapter.VH> {
        private List<DailyItem> data;
        DailyAdapter(List<DailyItem> data) { this.data = data; }

        void updateData(List<DailyItem> newData) {
            data = newData;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_daily_forecast, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            DailyItem item = data.get(pos);
            h.tvDay.setText(item.day);
            h.ivIcon.setImageResource(item.iconRes);
            h.tvHigh.setText(item.high);
            h.tvLow.setText(item.low);
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView  tvDay, tvHigh, tvLow;
            ImageView ivIcon;
            VH(@NonNull View v) {
                super(v);
                tvDay  = v.findViewById(R.id.tvDayName);
                ivIcon = v.findViewById(R.id.ivDayIcon);
                tvHigh = v.findViewById(R.id.tvDayTempHigh);
                tvLow  = v.findViewById(R.id.tvDayTempLow);
            }
        }
    }
}