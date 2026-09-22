package ca.markhansen.usbcameraviewer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.*;
import java.util.*;

public class MainActivity extends Activity {
    private TextView status,count,name;
    private ImageView preview;
    private File latest;
    private File sessionDir;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        sessionDir=new File(getCacheDir(),"camera_session");
        sessionDir.mkdirs();
        buildUi();
        requestNotifications();
        register();
        startCameraService("connect");
        loadLatest();
    }

    private void buildUi(){
        int pad=dp(18);
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(pad,pad,pad,pad);
        root.setBackgroundColor(Color.rgb(9,12,16)); scroll.addView(root);

        TextView title=text("USB CAMERA VIEWER",28,true); root.addView(title);
        TextView sub=text("Nikon tether viewer • temporary cache",14,false); sub.setTextColor(Color.rgb(150,165,175)); root.addView(sub);

        status=text("Starting…",15,true); status.setPadding(0,dp(18),0,dp(12)); root.addView(status);

        preview=new ImageView(this); preview.setBackgroundColor(Color.rgb(22,27,33)); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview,new LinearLayout.LayoutParams(-1,dp(470)));

        name=text("No capture yet",17,true); name.setPadding(0,dp(14),0,dp(4)); root.addView(name);
        count=text("",13,false); count.setTextColor(Color.LTGRAY); root.addView(count);

        Button connect=button("CONNECT / RECONNECT CAMERA");
        connect.setOnClickListener(v->startCameraService("connect")); root.addView(connect);

        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button save=button("SAVE PHOTO"); Button clear=button("CLEAR CACHE");
        row.addView(save,new LinearLayout.LayoutParams(0,dp(58),1));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(58),1); cp.setMargins(dp(10),0,0,0);
        row.addView(clear,cp); root.addView(row);

        TextView note=text("How it works: connect the camera by USB-C, leave the app running, and shoot normally. New JPEGs are copied into this session cache for viewing. The foreground camera link is designed to stay alive when the phone screen turns off. Nothing is saved permanently unless you tap Save Photo.",13,false);
        note.setTextColor(Color.rgb(155,165,174)); note.setPadding(0,dp(18),0,dp(30)); root.addView(note);

        save.setOnClickListener(v->saveLatest());
        clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Clear temporary cache?")
                .setMessage("This deletes only USB Camera Viewer cached copies. It does not touch the camera card.")
                .setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->clearCache()).show());
        setContentView(scroll);
    }

    private TextView text(String s,int sp,boolean bold){
        TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.WHITE);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t;
    }
    private Button button(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private void requestNotifications(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);
    }

    private void startCameraService(String action){
        Intent i=new Intent(this,CameraUsbService.class).setAction(action);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    }

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            if(CameraUsbService.ACTION_STATE.equals(i.getAction())) status.setText(i.getStringExtra(CameraUsbService.EXTRA_TEXT));
            else if(CameraUsbService.ACTION_IMAGE.equals(i.getAction())){
                String p=i.getStringExtra(CameraUsbService.EXTRA_PATH);
                if(p!=null){latest=new File(p); show(latest);}
            }
        }
    };

    private void register(){
        IntentFilter f=new IntentFilter(); f.addAction(CameraUsbService.ACTION_STATE); f.addAction(CameraUsbService.ACTION_IMAGE);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f);
    }

    private void loadLatest(){
        File[] fs=sessionDir.listFiles((d,n)->n.toLowerCase(Locale.US).endsWith(".jpg")||n.toLowerCase(Locale.US).endsWith(".jpeg"));
        if(fs!=null && fs.length>0){Arrays.sort(fs,Comparator.comparingLong(File::lastModified)); latest=fs[fs.length-1]; show(latest);}
        updateCount();
    }

    private void show(File f){
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true; BitmapFactory.decodeFile(f.getAbsolutePath(),bounds);
        int sample=1; while(bounds.outWidth/sample>1800 || bounds.outHeight/sample>1800) sample*=2;
        BitmapFactory.Options o=new BitmapFactory.Options(); o.inSampleSize=sample;
        preview.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath(),o));
        name.setText(f.getName()); updateCount();
    }

    private void updateCount(){
        File[] fs=sessionDir.listFiles(); int n=fs==null?0:fs.length;
        count.setText(n+" photo"+(n==1?"":"s")+" in temporary session cache");
    }

    private void saveLatest(){
        if(latest==null||!latest.exists()){Toast.makeText(this,"No cached photo yet",Toast.LENGTH_SHORT).show();return;}
        String outName="USB_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".jpg";
        try{
            ContentValues v=new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME,outName); v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            if(Build.VERSION.SDK_INT>=29) v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/USB Camera Viewer");
            Uri u=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
            if(u==null) throw new IOException("Could not create saved image");
            try(InputStream in=new FileInputStream(latest); OutputStream out=getContentResolver().openOutputStream(u)){
                byte[] buf=new byte[64*1024]; int n; while((n=in.read(buf))>0) out.write(buf,0,n);
            }
            Toast.makeText(this,"Saved to Pictures/USB Camera Viewer",Toast.LENGTH_LONG).show();
        }catch(Exception e){Toast.makeText(this,"Save failed: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void clearCache(){
        File[] fs=sessionDir.listFiles(); if(fs!=null)for(File f:fs)f.delete();
        latest=null; preview.setImageDrawable(null); name.setText("No capture yet"); updateCount();
    }

    @Override protected void onDestroy(){try{unregisterReceiver(receiver);}catch(Exception ignored){} super.onDestroy();}
}
