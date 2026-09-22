package ca.markhansen.usbcameraviewer;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class CameraUsbService extends Service {
    public static final String ACTION_STATE="ca.markhansen.usbcameraviewer.STATE";
    public static final String ACTION_IMAGE="ca.markhansen.usbcameraviewer.IMAGE";
    public static final String EXTRA_TEXT="text", EXTRA_PATH="path";
    private static final String ACTION_USB_PERMISSION="ca.markhansen.usbcameraviewer.USB_PERMISSION";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean running=true;
    private PtpClient ptp;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        Notification n=new Notification.Builder(this,"camera_link")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("USB Camera Viewer")
                .setContentText("Ready for camera")
                .setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=34) startForeground(7,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(7,n);

        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"UsbCameraViewer:Capture");
        wakeLock.acquire();

        IntentFilter f=new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(usbReceiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver,f);
        connectFirstCamera();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel("camera_link","Camera connection",NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private final BroadcastReceiver usbReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(ACTION_USB_PERMISSION.equals(i.getAction())){
                UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && d!=null) openDevice(d);
                else state("USB permission denied");
            } else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())){
                state("Camera disconnected");
                closePtp();
            }
        }
    };

    private void connectFirstCamera(){
        UsbManager m=(UsbManager)getSystemService(USB_SERVICE);
        UsbDevice selected=null;
        for(UsbDevice d:m.getDeviceList().values()) if(findStillImageInterface(d)!=null){selected=d;break;}
        if(selected==null){state("Plug in camera with USB-C, turn it on, then tap Connect");return;}
        if(m.hasPermission(selected)) openDevice(selected);
        else {
            PendingIntent pi=PendingIntent.getBroadcast(this,0,new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
            m.requestPermission(selected,pi);
            state("Waiting for USB permission…");
        }
    }

    private UsbInterface findStillImageInterface(UsbDevice d){
        for(int i=0;i<d.getInterfaceCount();i++){
            UsbInterface x=d.getInterface(i);
            if(x.getInterfaceClass()==UsbConstants.USB_CLASS_STILL_IMAGE) return x;
        }
        return null;
    }

    private void openDevice(UsbDevice d){
        worker.submit(()->{
            closePtp();
            try{
                UsbManager m=(UsbManager)getSystemService(USB_SERVICE);
                UsbDeviceConnection c=m.openDevice(d);
                UsbInterface x=findStillImageInterface(d);
                if(c==null||x==null) throw new IOException("Could not open camera");
                ptp=new PtpClient(c,x);
                state("Connected: "+(d.getProductName()!=null?d.getProductName():"USB camera")+" • watching for new photos");
                Set<Integer> known=ptp.getObjectHandles();
                while(running && ptp!=null){
                    try{
                        Thread.sleep(1400);
                        Set<Integer> now=ptp.getObjectHandles();
                        for(Integer h:now){
                            if(known.contains(h)) continue;
                            known.add(h);
                            captureHandle(h);
                        }
                        known.retainAll(now);
                    }catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                    catch(Exception e){state("Camera link retry: "+e.getMessage());Thread.sleep(1800);}
                }
            }catch(Exception e){state("Connection error: "+e.getMessage());closePtp();}
        });
    }

    private void captureHandle(int h){
        try{
            PtpClient.ObjectInfo info=ptp.getObjectInfo(h);
            if(info.format!=PtpClient.FORMAT_JPEG) return;
            if(info.size<=0 || info.size>100L*1024*1024){state("Skipped unusually large image");return;}
            File dir=new File(getCacheDir(),"camera_session");
            String safe=info.filename.replaceAll("[^A-Za-z0-9._-]","_");
            if(!safe.toLowerCase(Locale.US).endsWith(".jpg") && !safe.toLowerCase(Locale.US).endsWith(".jpeg")) safe+=".jpg";
            File f=new File(dir,System.currentTimeMillis()+"_"+safe);
            state("Receiving "+info.filename+"…");
            ptp.downloadObject(h,f);
            Intent out=new Intent(ACTION_IMAGE).setPackage(getPackageName()).putExtra(EXTRA_PATH,f.getAbsolutePath());
            sendBroadcast(out);
            state("Captured "+info.filename+" • cached only");
        }catch(Exception e){state("Photo receive failed: "+e.getMessage());}
    }

    private void state(String s){
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()).putExtra(EXTRA_TEXT,s));
        Notification n=new Notification.Builder(this,"camera_link").setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("USB Camera Viewer").setContentText(s).setOngoing(true).build();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(7,n);
    }

    private void closePtp(){ PtpClient p=ptp; ptp=null; if(p!=null) try{p.close();}catch(Exception ignored){} }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && "connect".equals(intent.getAction())) connectFirstCamera();
        return START_STICKY;
    }

    @Override public void onDestroy(){
        running=false; closePtp(); worker.shutdownNow();
        try{unregisterReceiver(usbReceiver);}catch(Exception ignored){}
        if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
