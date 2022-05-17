// MeasurementProvider

package com.github.mattiadellepiane.gnssraw;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.github.mattiadellepiane.gnssraw.data.SharedData;
import com.github.mattiadellepiane.gnssraw.listeners.MeasurementListener;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MeasurementProvider {

    private List<MeasurementListener> listeners;
    private LocationManager locationManager;
    private SensorMeasurements sensorMeasurements;

    private MainActivity context;
    private LocationListener locationListener = newLocationListener();
    private final GnssMeasurementsEvent.Callback gnssMeasurementsEventListener = newGnssMeasurementsEventCallback();
    private final GnssNavigationMessage.Callback gnssNavigationMessageListener = newGnssNavigationMessageCallback();
    private static final long LOCATION_RATE_GPS_MS = TimeUnit.SECONDS.toMillis(1L);
    private static final long LOCATION_RATE_NETWORK_MS = TimeUnit.SECONDS.toMillis(60L);

    public MeasurementProvider(MainActivity context, SensorMeasurements sensorMeasurements, MeasurementListener... listeners) {
        this.context =  context;
        this.listeners = Arrays.asList(listeners);
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.sensorMeasurements = sensorMeasurements;
    }

    private String getDebugTag(){
        return context.getString(R.string.debug_tag);
    }

    private LocationListener newLocationListener() {
        return new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {

                for (MeasurementListener listener : listeners) {
                    listener.onLocationChanged(location);
                }
            }
        };
    }

    private GnssMeasurementsEvent.Callback newGnssMeasurementsEventCallback() {
        return new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                for (MeasurementListener logger : listeners) {
                    logger.onGnssMeasurementsReceived(event);
                }
            }
        };
    }

    private GnssNavigationMessage.Callback newGnssNavigationMessageCallback() {
        return new GnssNavigationMessage.Callback() {
            @Override
            public void onGnssNavigationMessageReceived(GnssNavigationMessage event) {
                for (MeasurementListener logger : listeners) {
                    logger.onGnssNavigationMessageReceived(event);
                }
            }
        };
    }

    @SuppressLint("MissingPermission")
    public void registerLocation() {
        boolean isGpsProviderEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (isGpsProviderEnabled) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(context, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                return;
            }
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_RATE_NETWORK_MS,
                    0.0f,
                    locationListener);
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_RATE_GPS_MS,
                    0.0f,
                    locationListener);
        }
    }

    public void unregisterLocation() {
        locationManager.removeUpdates(locationListener);
    }

    @SuppressLint("MissingPermission")
    public void registerAll() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        registerLocation();
        ExecutorService ex = Executors.newFixedThreadPool(2);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            GnssMeasurementRequest.Builder requestBuilder = new GnssMeasurementRequest.Builder().setFullTracking(SharedData.getInstance().getFullTracking());
            locationManager.registerGnssMeasurementsCallback(requestBuilder.build(), SharedData.getInstance().getContext().getMainExecutor(), gnssMeasurementsEventListener);
        }
        else{
            locationManager.registerGnssMeasurementsCallback(gnssMeasurementsEventListener);
        }
        locationManager.registerGnssNavigationMessageCallback(gnssNavigationMessageListener);
        startLogging();
    }

    public void unRegisterAll(){
        unregisterLocation();
        locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsEventListener);
        locationManager.unregisterGnssNavigationMessageCallback(gnssNavigationMessageListener);
        stopLogging();
    }

    private void startLogging() {
        for (MeasurementListener logger : listeners) {
            logger.startLogging();
        }
    }

    private void stopLogging(){
        for (MeasurementListener logger : listeners) {
            logger.stopLogging();
        }
    }
}

// MeasurementListener

package com.github.mattiadellepiane.gnssraw.listeners;

import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.Location;
import android.os.Build;

import com.github.mattiadellepiane.gnssraw.R;
import com.github.mattiadellepiane.gnssraw.data.SharedData;

import java.util.Locale;

public abstract class MeasurementListener {

    private static final String COMMENT_START = "# ";
    private static final String VERSION_TAG = "Version: ";
    private static final String CURRENT_TIME_MILLIS = "%CURRENT_TIME_MILLIS%"; //placeholder


    protected abstract void initResources();
    protected abstract void releaseResources();
    protected abstract void write(String s);

    public final void startLogging(){
        initResources();
        //write(getHeader());
    }

    public final void stopLogging(){
        releaseResources();
    }

    public void onLocationChanged(Location location) {
        String locationStream =
                String.format(
                        Locale.US,
                        "Fix,%s,%f,%f,%f,%f,%f,%d",
                        location.getProvider(),
                        location.getLatitude(),
                        location.getLongitude(),
                        location.getAltitude(),
                        location.getSpeed(),
                        location.getAccuracy(),
                        location.getTime());
        write(locationStream);
    }

    public void onGnssMeasurementsReceived(GnssMeasurementsEvent event){
        GnssClock gnssClock = event.getClock();
        String clockInfo = getClockInfo(gnssClock);
        StringBuilder sb = new StringBuilder("NB," + event.getMeasurements().size());
        for (GnssMeasurement measurement : event.getMeasurements()) {
            sb.append(",")
                    .append(clockInfo.replace(CURRENT_TIME_MILLIS, String.valueOf(System.currentTimeMillis())))
                    .append(getMeasurementInfo(measurement));
        }
        sb.append(",FB");
        write(sb.toString());
    }

    public void onGnssNavigationMessageReceived(GnssNavigationMessage event){
        ...
    }

    private String getClockInfo(GnssClock clock){
        String clockInfo = String.format(
                "Raw,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                CURRENT_TIME_MILLIS,
                clock.getTimeNanos(),
                clock.hasLeapSecond() ? clock.getLeapSecond() : "",
                clock.hasTimeUncertaintyNanos() ? clock.getTimeUncertaintyNanos() : "",
                clock.getFullBiasNanos(),
                clock.hasBiasNanos() ? clock.getBiasNanos() : "",
                clock.hasBiasUncertaintyNanos() ? clock.getBiasUncertaintyNanos() : "",
                clock.hasDriftNanosPerSecond() ? clock.getDriftNanosPerSecond() : "",
                clock.hasDriftUncertaintyNanosPerSecond()
                        ? clock.getDriftUncertaintyNanosPerSecond()
                        : "",
                clock.getHardwareClockDiscontinuityCount() + ",");
        return clockInfo;
    }

    private String getMeasurementInfo(GnssMeasurement measurement){
        String measurementInfo = String.format(
                "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                measurement.getSvid(),
                measurement.getTimeOffsetNanos(),
                measurement.getState(),
                measurement.getReceivedSvTimeNanos(),
                measurement.getReceivedSvTimeUncertaintyNanos(),
                measurement.getCn0DbHz(),
                measurement.getPseudorangeRateMetersPerSecond(),
                measurement.getPseudorangeRateUncertaintyMetersPerSecond(),
                measurement.getAccumulatedDeltaRangeState(),
                measurement.getAccumulatedDeltaRangeMeters(),
                measurement.getAccumulatedDeltaRangeUncertaintyMeters(),
                measurement.hasCarrierFrequencyHz() ? measurement.getCarrierFrequencyHz() : "",
                measurement.hasCarrierCycles() ? measurement.getCarrierCycles() : "",
                measurement.hasCarrierPhase() ? measurement.getCarrierPhase() : "",
                measurement.hasCarrierPhaseUncertainty()
                        ? measurement.getCarrierPhaseUncertainty()
                        : "",
                measurement.getMultipathIndicator(),
                measurement.hasSnrInDb() ? measurement.getSnrInDb() : "",
                measurement.getConstellationType(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && measurement.hasAutomaticGainControlLevelDb()
                        ? measurement.getAutomaticGainControlLevelDb()
                        : "");

        return measurementInfo;
    }

    private String getHeader(){
        StringBuilder header = new StringBuilder();
        header.append(COMMENT_START)
                .append("\n")
                .append(COMMENT_START)
                .append("Header Description:")
                .append("\n")
                .append(COMMENT_START)
                .append("\n")
                .append(COMMENT_START)
                .append(VERSION_TAG);
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        String fileVersion =
                SharedData.getInstance().getContext().getString(R.string.app_version)
                        + " Platform: "
                        + Build.VERSION.RELEASE
                        + " "
                        + "Manufacturer: "
                        + manufacturer
                        + " "
                        + "Model: "
                        + model;
        header.append(fileVersion)
                .append("\n")
                .append(COMMENT_START)
                .append("\n")
                .append(COMMENT_START)
                .append("Raw,UTCTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,"
                        + "BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,"
                        + "HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,"
                        + "ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,"
                        + "PseudorangeRateUncertaintyMetersPerSecond,"
                        + "AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,"
                        + "AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,"
                        + "CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,"
                        + "ConstellationType,AgcDb");
        header.append("\n")
                .append(COMMENT_START)
                .append("\n")
                .append(COMMENT_START)
                .append("Fix,Provider,Latitude,Longitude,Altitude,Speed,Accuracy,(UTC)TimeInMs")
                .append("\n")
                .append(COMMENT_START)
                .append("\n");
                return header.toString();
    }

}


package com.github.mattiadellepiane.gnssraw.listeners;
import android.util.Log;

import com.github.mattiadellepiane.gnssraw.R;
import com.github.mattiadellepiane.gnssraw.data.SharedData;

import org.apache.commons.codec.binary.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerCommunication extends MeasurementListener {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private ExecutorService executor;
    private boolean headerSent = false;
    private int port = 5088;

    public ServerCommunication(){
        executor = Executors.newSingleThreadExecutor();
        SharedData.getInstance().setServerCommunication(this);
    }

    private String getDebugTag(){
        return SharedData.getInstance().getContext().getString(R.string.debug_tag);
    }

    @Override
    protected void initResources() {
        if(SharedData.getInstance().isServerEnabled()) {
            executor.execute(() -> {
                try {
                    socket = new Socket(SharedData.getInstance().getServerAddress(), SharedData.getInstance().getServerPort());
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
    }

    @Override
    protected void releaseResources() {
        executor.execute(()->{
            if(out != null)
                out.close();
            try {
                if(in != null)
                    in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    protected void write(String s){
        if(SharedData.getInstance().isListeningForMeasurements() && SharedData.getInstance().isServerEnabled()) {
            executor.execute(() -> {
                if(out != null) {
                    if(!headerSent) {
                        headerSent = true;
                    }else{
                    }
                    out.println(s);
                }
            });
        }
    }

    public boolean isReachable() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(SharedData.getInstance().getServerAddress(), SharedData.getInstance().getServerPort()),3000);
        } catch (IOException e) {
            return false;
        }finally {
            try {
                s.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    public void getLocation(){
        new Thread(()-> {
            Socket s;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(SharedData.getInstance().getServerAddress(), port), 3000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                String input = null;
                if ((input = in.readLine()) != null) {
                    String[] params = input.split("\\s+");
                    double lat = Double.parseDouble(params[2]);
                    double lng = Double.parseDouble(params[3]);
                    double quota = Double.parseDouble(params[4]);
                    int qfix = Integer.parseInt(params[5]);
                    int nsat = Integer.parseInt(params[6]);
                    double sdn = Double.parseDouble(params[7]);
                    double sde = Double.parseDouble(params[8]);
                    double sdu = Double.parseDouble(params[9]);
                    if (SharedData.getInstance().getMapsFragment() != null) {
                        SharedData.getInstance().getMapsFragment().update(lat, lng, qfix);
                    }
                }
                out.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }).start();
    }
}