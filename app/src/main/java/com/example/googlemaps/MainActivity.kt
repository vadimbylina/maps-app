package com.example.googlemaps

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TabHost
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import java.util.concurrent.TimeUnit
import com.google.android.gms.maps.model.*
import kotlin.math.*


class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback



    private var isTracking = false
    private var isPaused = false

    private val pathSegments = mutableListOf<MutableList<LatLng>>()
    private val polylines = mutableListOf<Polyline>()

    private var totalDistance = 0.0

    private lateinit var distanceTextView: TextView
    private lateinit var textTimer: TextView
    private lateinit var startTrackingButton: Button
    private lateinit var pauseButton: Button
    private lateinit var timerTextView: TextView

    private var handler = Handler(Looper.getMainLooper())
    private var elapsedTime: Long = 0
    private var running = false

    private var runnable = object : Runnable {
        override fun run() {
            if (running) {
                elapsedTime++
                updateTimerText()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        distanceTextView = findViewById(R.id.tvDistance)
        startTrackingButton = findViewById(R.id.btnStartTracking)
        pauseButton = findViewById(R.id.btnPause)

        timerTextView = findViewById(R.id.textTimer) // Инициализация textTimer

        pauseButton.visibility = View.GONE
        distanceTextView.visibility = View.GONE
        timerTextView.visibility = View.GONE

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupLocationCallback()
        setupButtons()
        setupMap()
        val tabHost = findViewById<TabHost>(android.R.id.tabhost)
        tabHost.setup()

        var tabSpec = tabHost.newTabSpec("Tab 1")
        tabSpec.setContent(R.id.tab1)
        tabSpec.setIndicator("Тренировка")
        tabHost.addTab(tabSpec)


        tabSpec = tabHost.newTabSpec("Tab 2")
        tabSpec.setContent(R.id.tab2)
        tabSpec.setIndicator("Статистика")
        tabHost.addTab(tabSpec)
        tabHost.setOnTabChangedListener { tabId -> when (tabId) {
            "Tab 1" -> {
                distanceTextView.visibility = View.GONE
                timerTextView.visibility = View.GONE
                startTrackingButton.visibility = View.VISIBLE
                pauseButton.visibility = View.VISIBLE
            }
            "Tab 2" -> {
                distanceTextView.visibility = View.VISIBLE
                timerTextView.visibility = View.VISIBLE
                startTrackingButton.visibility = View.GONE
                pauseButton.visibility = View.GONE
            }
        } }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (isTracking && !isPaused) {
                    val locations = locationResult.locations
                    if (locations.isNotEmpty()) {
                        val latestLocation = locations.last()
                        val currentLatLng = LatLng(latestLocation.latitude, latestLocation.longitude)

                        if (pathSegments.isEmpty()) {
                            pathSegments.add(mutableListOf())
                        }

                        val currentSegment = pathSegments.last()
                        if (currentSegment.isNotEmpty()) {
                            val lastPoint = currentSegment.last()
                            val distanceBetween = calculateDistance(lastPoint, currentLatLng)
                            totalDistance += distanceBetween
                            updateDistanceText()
                        }

                        currentSegment.add(currentLatLng)
                        updatePolylines()
                    }
                }
            }
        }
    }

    private fun setupButtons() {
        startTrackingButton.setOnClickListener {
            if (!isTracking) {
                startTracking()
                startTimer()
            } else {
                stopTracking()
                resetTimer()
            }
        }

        pauseButton.setOnClickListener {
            if (isPaused) {
                resumeTracking()
                startTimer()
            } else {
                pauseTracking()
                stopTimer()
            }
        }
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun startTracking() {
        if (checkLocationPermission()) {
            isTracking = true
            isPaused = false
            startLocationUpdates()
            startTrackingButton.text = "Стоп"
            pauseButton.visibility = View.VISIBLE

            pathSegments.add(mutableListOf())

            val serviceIntent = Intent(this, LocationTrackingService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            requestLocationPermission()
        }
    }

    private fun pauseTracking() {
        isPaused = true
        stopLocationUpdates()
        pauseButton.text = "Продолжить"
    }

    private fun resumeTracking() {
        if (checkLocationPermission()) {
            isPaused = false
            startLocationUpdates()
            pauseButton.text = "Пауза"

            pathSegments.add(mutableListOf())
        } else {
            requestLocationPermission()
        }
    }

    private fun stopTracking() {
        isTracking = false
        isPaused = false
        stopLocationUpdates()
        startTrackingButton.text = "Старт"
        pauseButton.visibility = View.GONE
        resetTracking()

        val serviceIntent = Intent(this, LocationTrackingService::class.java)
        stopService(serviceIntent)
    }

    private fun startLocationUpdates() {
        if (checkLocationPermission()) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build()

            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    private fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun updatePolylines() {
        for (polyline in polylines) {
            polyline.remove()
        }
        polylines.clear()

        for (segment in pathSegments) {
            if (segment.size > 1) {
                val polylineOptions = PolylineOptions()
                    .addAll(segment)
                    .color(Color.BLUE)
                    .width(10f)
                val polyline = mMap.addPolyline(polylineOptions)
                polylines.add(polyline)
            }
        }
    }

    private fun updateDistanceText() {
        distanceTextView.text = "Пройденное расстояние: %.2f м".format(totalDistance)
    }

    private fun resetTracking() {
        for (polyline in polylines) {
            polyline.remove()
        }
        polylines.clear()
        pathSegments.clear()
        totalDistance = 0.0
        distanceTextView.text = "Пройденное расстояние: 0.00 м"
    }

    private fun calculateDistance(point1: LatLng, point2: LatLng): Double {
        val radius = 6371000.0

        val latDiff = Math.toRadians(point2.latitude - point1.latitude)
        val lngDiff = Math.toRadians(point2.longitude - point1.longitude)

        val a = sin(latDiff / 2).pow(2) +
                cos(Math.toRadians(point1.latitude)) *
                cos(Math.toRadians(point2.latitude)) *
                sin(lngDiff / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return radius * c
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        if (checkLocationPermission()) {
            try {
                mMap.isMyLocationEnabled = true
                moveCameraToCurrentLocation()
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        } else {
            requestLocationPermission()
        }
    }

    private fun moveCameraToCurrentLocation() {
        if (checkLocationPermission()) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        val currentLatLng = LatLng(location.latitude, location.longitude)
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                    }
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                if (checkLocationPermission()) {
                    try {
                        mMap.isMyLocationEnabled = true
                        moveCameraToCurrentLocation()
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                    }
                }
            } else {
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startTimer() {
        if (!running) {
            running = true
            handler.post(runnable)
        }
    }

    private fun stopTimer() {
        running = false
        handler.removeCallbacks(runnable)
    }

    private fun resetTimer() {
        stopTimer()
        elapsedTime = 0
        updateTimerText()
    }

    private fun updateTimerText() {
        val hours = TimeUnit.SECONDS.toHours(elapsedTime)
        val minutes = TimeUnit.SECONDS.toMinutes(elapsedTime) % 60
        val seconds = elapsedTime % 60

        val timeString = String.format("Времени прошло: %02d:%02d:%02d", hours, minutes, seconds)
        timerTextView.text = timeString
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }
}
