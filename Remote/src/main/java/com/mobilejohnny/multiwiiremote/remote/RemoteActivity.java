package com.mobilejohnny.multiwiiremote.remote;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.apache.http.auth.NTUserPrincipal;

import java.util.LinkedList;
import java.util.List;


public abstract class RemoteActivity extends ActionBarActivity {

    // Sensor objects
    static SensorManager mSensorManager;
    static Sensor accelerometer;
    static Sensor magnetometer;
    static Sensor gravity;

    // Bluetooth manager
    static Bluetooth tBlue;
    static boolean sensorAvailable = false;
    static float azimuth       = 0.0f;
    static float pitch         = 0.0f;
    static float roll          = 0.0f;

    //Time control variables
    static int cycle = 0;   //Activity
    static int lastCycle = 0;
    static int cycleAcc = 0;  //Gravity
    static int lastCycleAcc = 0;
    static int cycleMag = 0;  //MagneticField
    static int lastCycleMag = 0;
    static int cycleGPS = 0;  //GlobalPosition
    static int lastCycleGPS = 0;

    private static final String MSP_HEADER = "$M<";
    private static final byte[] MSP_HEADER_BYTE = MSP_HEADER.getBytes();
    private static final int headerLength = MSP_HEADER_BYTE.length;

    static final int minRC = 1000, maxRC = 2000;
    //, medRC = 1500;
    protected static int medRollRC = 1500,medPitchRC = 1500,medYawRC = 1500;

    static int servo[] = new int[8],
            rcThrottle = minRC, rcRoll = medRollRC, rcPitch = medPitchRC, rcYaw =medYawRC,
            rcAUX1=minRC, rcAUX2=minRC, rcAUX3=minRC, rcAUX4=minRC;

    private static final int

            MSP_SET_RAW_RC           =200;



    private android.hardware.SensorEventListener sensorEventListener;
    private long lastSend;

    //Sensor;
    static float rotationX=0, rotationY=0, rotationZ=0;
    static float minX=-1, maxX=1, minY=-1, maxY=1;
    static int horizonInstrSize = 100;

    private Switch switchArm;
    private TextView txtAUX1;
    private TextView txtFPS;
    private TextView txtRoll;
    private TextView txtPitch;
    private TextView txtThrottle;
    protected int fps;
    private boolean unlock;
    private BroadcastReceiver BTReceiver;
    protected View decorView;
    private TCP tcp;
    private UDP udp;
    private int port = 8080;
    private String device_name = null;
    private String host = "192.168.0.142";
    private int connect_type = CONNECT_UDP;
    private static final int CONNECT_BLUETOOTH = 0;
    private static final int CONNECT_TCP = 1;
    private static final int CONNECT_UDP = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        decorView = getWindow().getDecorView();
        decorView.setKeepScreenOn(true);

        if(getRequestedOrientation()!=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            return;

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);

        SharedPreferences preference = PreferenceManager.getDefaultSharedPreferences(this);
        connect_type = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(this).getString("connection_type", "-1"));
        device_name =  preference.getString("device_name","");
        host  = preference.getString("host","");
        port = Integer.parseInt(preference.getString("port", "0"));
        medPitchRC = Integer.parseInt(preference.getString("middle_pitch", "1500"));
        medRollRC = Integer.parseInt(preference.getString("middle_roll", "1500"));

        BTReceiver = new BlueToothReceiver(this);

        this.registerReceiver(BTReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));
        this.registerReceiver(BTReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED));
        this.registerReceiver(BTReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));

        initSensor();

        arm(false);

        tBlue = new Bluetooth(this,device_name);
        tcp = new TCP();
        udp = new UDP();
        connect();
        Log.d("RemoteActivity","onCreate");
    }

    protected abstract void updateUI() ;


    @Override
    protected void onDestroy() {
        arm(false);
        updateRCPayload();
        sendRCPayload();

        exitSensor();
        if (tBlue!= null)
            tBlue.close();
        if(tcp!=null)
            tcp.close();
        if(udp!=null)
            udp.close();
        if(BTReceiver!= null)
        unregisterReceiver(BTReceiver);

        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement

        if(id == R.id.action_settings)
        {
            Intent intent =new Intent(this,SettingsActivity.class);
            startActivity(intent);
            return true;
        }


        return super.onOptionsItemSelected(item);
    }

    public void initSensor()
    {
        //Initiate instances
        sensorEventListener = new android.hardware.SensorEventListener(){

            private static final int azimutBuffer = 100;
            private float[] azimuts = new float [azimutBuffer];

            private float[] mGravity;
            private float[] mGeomagnetic;
            private float I[] = new float[16];
            private float R[] = new float[16];
            // Orientation values
            private float orientation[] = new float[3];
            private long millis;

            private  int az=0;

            private void addAzimut(float azimut){
                //for (az=azimutBuffer-1;az>0;az--)
                // azimuts[az] = azimuts[az-1];
                azimuts[az++] = azimut;
                if (az == azimutBuffer)
                    az = 0;
            }

            @Override
            public void onSensorChanged(SensorEvent event) {
                millis = System.currentTimeMillis();
                //if (event.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) return;
                switch (event.sensor.getType()) {
                    case Sensor.TYPE_MAGNETIC_FIELD:
                        //mGeomagnetic = event.values.clone();
                        mGeomagnetic = lowPass( event.values.clone(), mGeomagnetic );
                        //mGeomagnetic = lowPass( event.values.clone(), mGeomagnetic );
                        //exponentialSmoothing( mGeomagnetic.clone(), mGeomagnetic, 0.5 );//
//                        cycleMag = millis - lastCycleMag;
//                        lastCycleMag = millis;
                        break;
                    case Sensor.TYPE_ACCELEROMETER:
                        //case Sensor.TYPE_GRAVITY:
                        //mGravity = event.values.clone();
                        mGravity = lowPass( event.values.clone(), mGravity );
                        //exponentialSmoothing( mGravity.clone(), mGravity, 0.2 );
//                        cycleAcc = millis - lastCycleAcc;
//                        lastCycleAcc = millis;
                        break;
                }

                if (mGravity != null && mGeomagnetic != null) {
                    //exponentialSmoothing( mGravity.clone(), mGravity, 0.2 );
                    //exponentialSmoothing( mGeomagnetic.clone(), mGeomagnetic, 0.5 );


                    I = new float[16];
                    R = new float[16];
                    if (SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic))
                    { // Got rotation matrix!
                        SensorManager.getOrientation(R, orientation);
                        //azimuth = orientation[0];
                        azimuth =  (float)Math.toDegrees(orientation[0]);
                        addAzimut(azimuth);
                        //Math.toDegrees(azimuthInRadians)+360)%360;
                        roll    =  -orientation[2];
                        pitch   = -orientation[1];
                        rotationX =  -orientation[2];
                        rotationY =  -orientation[1];
                    }
                }

                if (millis-lastSend > 50)
                    if (tBlue!= null) {
                        calculateRCValues();
                        updateRCPayload();
                        sendRCPayload();

                        updateUI();
                        updateRCPayload();



                        lastSend = millis;
                    }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int i) {

            }


            /*
           * time smoothing constant for low-pass filter 0 \u2264 alpha \u2264 1 ; a smaller
             * value basically means more smoothing See:
             * http://en.wikipedia.org/wiki/Low-pass_filter#Discrete-time_realization
             */
            private static final float ALPHA = 0.05f;

            /**
             * @see
             *      ://en.wikipedia.org/wiki/Low-pass_filter#Algorithmic_implementation
             * @see
             *      ://developer.android.com/reference/android/hardware/SensorEvent.html
             *      #values
             */


            protected float[] lowPass(float[] input, float[] output)
            {
                if (output == null)
                    return input;

                int inputLength = input.length;
                for (int i = 0; i < inputLength; i++)
                {
                    output[i] = output[i] + ALPHA * (input[i] - output[i]);
                }
                return output;
            }
        };
        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        accelerometer  = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        magnetometer   = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        sensorAvailable = true;

        mSensorManager.registerListener(sensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(sensorEventListener, magnetometer, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(sensorEventListener, gravity, SensorManager.SENSOR_DELAY_FASTEST);
    }

    public void exitSensor()
    {
        if (sensorAvailable) mSensorManager.unregisterListener(sensorEventListener);
    }

    public void calculateRCValues() {
//        if (overControl) { //((mouseY > minlineY) && (mouseY < maxlineY))
//            rcThrottle =  parseInt(map(mouseY, minlineY, maxlineY, maxRC, minRC));
//        }
        if(unlock)
        {
            rcRoll =  parseInt(map(rotationY, minY, maxY, minRC, maxRC));
            rcPitch =  parseInt(map(rotationX, minX, maxX, maxRC, minRC));
            rcAUX2 = minRC;
//        rcYaw = parseInt( map((rotationX, minX, maxX, minRC, maxRC));
//            rcPitch = 3000 - rcPitch;
        }
        else
        {
            rcRoll = medRollRC;
            rcPitch = medPitchRC;
            rcYaw = medYawRC;
            rcAUX2 = maxRC;
        }

        rcThrottle = constrain(rcThrottle, minRC, maxRC);
        rcRoll = constrain(rcRoll, minRC, maxRC);
        rcPitch = constrain(rcPitch, minRC, maxRC);
        rcYaw= constrain(rcYaw, minRC, maxRC);
        //rcYaw=rotationZ*100;

    }

    protected int constrain(int val,int min,int max)
    {
        return Math.max(Math.min(val, max), min);

    }
    
    private char parseChar(int val)
    {
        return (char)val;
    }

    private int parseInt(float val)
    {
        return (int)val;
    }

    public static final float map(float var0, float var1, float var2, float var3, float var4) {
        return var3 + (var4 - var3) * ((var0 - var1) / (var2 - var1));
    }

    static Character[] payloadChar = new Character[16];

    public void updateRCPayload() {
        payloadChar[0] = parseChar(rcRoll & 0xFF); //strip the 'most significant bit' (MSB) and buffer\
        payloadChar[1] = parseChar(rcRoll >> 8 & 0xFF); //move the MSB to LSB, strip the MSB and buffer
        payloadChar[2] = parseChar(rcPitch & 0xFF);
        payloadChar[3] = parseChar(rcPitch >> 8 & 0xFF);
        payloadChar[4] = parseChar(rcYaw & 0xFF);
        payloadChar[5] = parseChar(rcYaw >> 8 & 0xFF);
        payloadChar[6] = parseChar(rcThrottle & 0xFF);
        payloadChar[7] = parseChar(rcThrottle >> 8 & 0xFF);

        //aux1
        payloadChar[8] = parseChar(rcAUX1 & 0xFF);
        payloadChar[9] = parseChar(rcAUX1 >> 8 & 0xFF);

        //aux2
        payloadChar[10] = parseChar(rcAUX2 & 0xFF);
        payloadChar[11] = parseChar(rcAUX2 >> 8 & 0xFF);

        //aux3
        payloadChar[12] = parseChar(rcAUX3 & 0xFF);
        payloadChar[13] = parseChar(rcAUX3 >> 8 & 0xFF);

        //aux4
        payloadChar[14] = parseChar(rcAUX4 & 0xFF);
        payloadChar[15] = parseChar(rcAUX4 >> 8 & 0xFF);
    }

    static private int irmsp_RC =0;
    static private final int mspLenght_RC = 22;
    static private int bRMSP_RC=0;
    private static List<Byte> msp_RC;
    public void sendRCPayload() {
        byte[] arr_RC;
        irmsp_RC =0;
        bRMSP_RC=0;
        arr_RC = new byte[mspLenght_RC];
        msp_RC = requestMSP(MSP_SET_RAW_RC, payloadChar );
        //msp_RC = requestMSPRC();

        try {
            for (bRMSP_RC=0;bRMSP_RC<mspLenght_RC;bRMSP_RC++) {
                arr_RC[irmsp_RC++] = msp_RC.get(bRMSP_RC);
            }
            send(arr_RC);
        }
        catch(NullPointerException ex) {
            //Log.e("","Warning: Packet not sended.");
        }
    }

    //send msp with payload
    private List<Byte> requestMSP (int msp, Character[] payload) {
        List<Byte> bf;
        int cList;
        byte checksumMSP;
        byte pl_size;
        int cMSP;
        int payloadLength;

        if (msp < 0) {
            return null;
        }
        bf = new LinkedList<Byte>();
        for (cList=0;cList<headerLength;cList++) {
            bf.add( MSP_HEADER_BYTE[cList] );
        }

        checksumMSP=0;
        pl_size = (byte)((payload != null ? parseInt(payload.length) : 0)&0xFF);
        bf.add(pl_size);
        checksumMSP ^= (pl_size&0xFF);

        bf.add((byte)(msp & 0xFF));
        checksumMSP ^= (msp&0xFF);

        if (payload != null) {
            payloadLength = payload.length;
            for (cMSP=0;cMSP<payloadLength;cMSP++) {
                bf.add((byte)(payload[cMSP]&0xFF));
                checksumMSP ^= (payload[cMSP]&0xFF);
            }
        }
        bf.add(checksumMSP);
        return (bf);
    }



    public void arm(boolean theValue) {
        if (theValue) {
            rcThrottle=1000;
            rcAUX1 = 2000;
        }
        else {
            rcAUX1 = 1000;
        }
    }

    String msg = "";
    public void connect() {
//        Toast.makeText(this,"connecting flone",Toast.LENGTH_SHORT).show();
        //Toast.makeText(getApplicationContext(), "calibrating flone", Toast.LENGTH_SHORT).show();
        //sendRequestMSP(requestMSP(MSP_ACC_CALIBRATION));
        final Handler handler = new Handler();
            new Thread(){
                @Override
                public void run() {
                    boolean result = false;
                    try {
                        if (connect_type == CONNECT_BLUETOOTH) {
                            result = tBlue.connect();
                            msg = device_name;
                        } else if (connect_type == CONNECT_TCP) {
                            result = tcp.connect(host, port);
                            msg = "TCP " + host + ":" + port;
                        } else if (connect_type == CONNECT_UDP) {
                            result = udp.connect(host, port);
                            msg = "UDP " + host + ":" + port + "";
                        }
                        if(result)
                            msg += " Connected";
                        else
                            msg+= " Connect Failed";
                    }
                    catch(Exception e)
                    {
                        Log.e("MWC Remote","Connect Error",e);
                    }

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(RemoteActivity.this, msg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }.start();
    }

    public void send(byte[] data)
    {
        if(connect_type==CONNECT_BLUETOOTH)
        {
            tBlue.write(data);
        }
        else if(connect_type==CONNECT_TCP)
        {
            tcp.send(data);
        }
        else if(connect_type==CONNECT_UDP)
        {
            udp.send(data);
        }
    }

    protected void unLock()
    {
        unlock = true;
    }

    protected void lock()
    {
        unlock = false;
    }


}
