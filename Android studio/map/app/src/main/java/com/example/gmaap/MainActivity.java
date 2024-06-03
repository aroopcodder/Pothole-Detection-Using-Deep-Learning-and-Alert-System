package com.example.gmaap;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.opencsv.CSVReader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final String TAG = "MainActivity";
    private static final String CHANNEL_ID = "pothole_notification_channel";
    private GoogleMap gMap;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private final List<LatLng> markerPositions = new ArrayList<>();
    private final String CSV_URL = "https://drive.google.com/uc?export=download&id=1mi5Wvu6ocBawaRGQEMQbUIVETd-ma7dl"; // Direct download link
    private boolean isNotificationShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.id_map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        requestLocationPermission();
    }

    private void requestLocationPermission() {
        ActivityResultLauncher<String> requestPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                    if (isGranted) {
                        enableMyLocation();
                    } else {
                        Log.e(TAG, "Location permission not granted");
                    }
                });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED) {
            enableMyLocation();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private void enableMyLocation() {
        if (gMap != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            gMap.setMyLocationEnabled(true);
            startLocationUpdates();
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        gMap = googleMap;
        enableMyLocation();
        fetchCsvAndPinMarkers();
    }

    private void fetchCsvAndPinMarkers() {
        new Thread(() -> {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder().url(CSV_URL).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String csvData = response.body().string();
                    Log.d(TAG, "CSV Data fetched successfully");
                    parseCsvAndPinMarkers(csvData);
                } else {
                    Log.e(TAG, "Failed to download CSV file.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching CSV file", e);
            }
        }).start();
    }

    private void parseCsvAndPinMarkers(String csvData) {
        try (CSVReader reader = new CSVReader(new StringReader(csvData))) {
            List<String[]> rows = reader.readAll();
            Log.d(TAG, "CSV Data parsed successfully");
            runOnUiThread(() -> {
                for (String[] row : rows) {
                    try {
                        double latitude = Double.parseDouble(row[0]);
                        double longitude = Double.parseDouble(row[1]);
                        LatLng position = new LatLng(latitude, longitude);
                        markerPositions.add(position);
                        gMap.addMarker(new MarkerOptions().position(position).title("Pothole Marker"));
                        Log.d(TAG, "Marker added at: " + latitude + ", " + longitude);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Invalid coordinates in CSV file", e);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error parsing CSV file", e);
        }
    }

    private void startLocationUpdates() {
        LocationRequest locationRequest = LocationRequest.create()
                .setInterval(10000) // Update interval in milliseconds
                .setFastestInterval(5000) // Fastest update interval in milliseconds
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    LatLng userLocation = new LatLng(location.getLatitude(), location.getLongitude());
                    checkProximityAndUpdateNotification(userLocation);
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

    private void checkProximityAndUpdateNotification(LatLng userLocation) {
        for (LatLng markerPosition : markerPositions) {
            float[] results = new float[1];
            Location.distanceBetween(userLocation.latitude, userLocation.longitude,
                    markerPosition.latitude, markerPosition.longitude, results);
            float distance = results[0];

            if (distance <= 100 && !isNotificationShown) {
                showNotification("Pothole Ahead");
                isNotificationShown = true;
                break;
            } else if (distance > 100 && isNotificationShown) {
                dismissNotification();
                isNotificationShown = false;
            }
        }
    }

    private void showNotification(String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Warning")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1, builder.build());
    }

    private void dismissNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(1);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Pothole Notification";
            String description = "Channel for Pothole Notifications";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}
