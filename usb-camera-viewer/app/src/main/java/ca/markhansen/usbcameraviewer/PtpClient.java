package ca.markhansen.usbcameraviewer;

import android.hardware.usb.*;
import java.io.*;
import java.nio.*;
import java.util.*;

final class PtpClient implements Closeable {
    static final int TYPE_COMMAND=1, TYPE_DATA=2, TYPE_RESPONSE=3;
    static final int OP_OPEN_SESSION=0x1002, OP_GET_OBJECT_HANDLES=0x1007,
            OP_GET_OBJECT_INFO=0x1008, OP_GET_OBJECT=0x1009;
    static final int RC_OK=0x2001, FORMAT_JPEG=0x3801;

    static final class ObjectInfo {
        final long size; final int format; final String filename;
        ObjectInfo(long size,int format,String filename){this.size=size;this.format=format;this.filename=filename;}
    }

    private final UsbDeviceConnection connection;
    private final UsbInterface intf;
    private final UsbEndpoint in, out;
    private int transaction=1;

    PtpClient(UsbDeviceConnection c, UsbInterface i) throws IOException {
        connection=c; intf=i;
        UsbEndpoint bi=null, bo=null;
        for(int n=0;n<i.getEndpointCount();n++){
            UsbEndpoint e=i.getEndpoint(n);
            if(e.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK){
                if(e.getDirection()==UsbConstants.USB_DIR_IN) bi=e; else bo=e;
            }
        }
        if(bi==null||bo==null) throw new IOException("Camera bulk USB endpoints not found");
        in=bi; out=bo;
        if(!connection.claimInterface(intf,true)) throw new IOException("Could not claim camera USB interface");
        Response r=command(OP_OPEN_SESSION,new long[]{1},null);
        if(r.code!=RC_OK && r.code!=0x201E) throw new IOException("PTP OpenSession failed: 0x"+Integer.toHexString(r.code));
    }

    synchronized Set<Integer> getObjectHandles() throws IOException {
        Response r=command(OP_GET_OBJECT_HANDLES,new long[]{0xFFFFFFFFL,0,0},null);
        if(r.code!=RC_OK || r.data==null) throw new IOException("GetObjectHandles failed: 0x"+Integer.toHexString(r.code));
        ByteBuffer b=ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN);
        if(b.remaining()<4) return Collections.emptySet();
        long count=Integer.toUnsignedLong(b.getInt());
        Set<Integer> out=new LinkedHashSet<>();
        for(long n=0;n<count && b.remaining()>=4;n++) out.add(b.getInt());
        return out;
    }

    synchronized ObjectInfo getObjectInfo(int handle) throws IOException {
        Response r=command(OP_GET_OBJECT_INFO,new long[]{Integer.toUnsignedLong(handle)},null);
        if(r.code!=RC_OK || r.data==null || r.data.length<53) throw new IOException("GetObjectInfo failed");
        ByteBuffer b=ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN);
        b.getInt();
        int format=Short.toUnsignedInt(b.getShort());
        b.getShort();
        long size=Integer.toUnsignedLong(b.getInt());
        b.position(Math.min(52,b.limit()));
        String filename="IMG_"+Integer.toUnsignedString(handle)+".jpg";
        if(b.remaining()>0){
            int chars=Byte.toUnsignedInt(b.get());
            if(chars>0 && b.remaining()>=chars*2){
                StringBuilder s=new StringBuilder();
                for(int i=0;i<chars;i++){
                    char ch=b.getChar();
                    if(ch!=0) s.append(ch);
                }
                if(s.length()>0) filename=s.toString();
            }
        }
        return new ObjectInfo(size,format,filename);
    }

    synchronized void downloadObject(int handle, File target) throws IOException {
        int tx=transaction++;
        writeCommand(OP_GET_OBJECT,tx,new long[]{Integer.toUnsignedLong(handle)});
        byte[] first=new byte[64*1024];
        int n=connection.bulkTransfer(in,first,first.length,10000);
        if(n<12) throw new IOException("No image data from camera");
        ByteBuffer h=ByteBuffer.wrap(first,0,12).order(ByteOrder.LITTLE_ENDIAN);
        long total=Integer.toUnsignedLong(h.getInt());
        int type=Short.toUnsignedInt(h.getShort());
        int code=Short.toUnsignedInt(h.getShort());
        int returnedTx=h.getInt();
        if(type!=TYPE_DATA || code!=OP_GET_OBJECT || returnedTx!=tx) throw new IOException("Unexpected PTP image container");
        long remaining=total-12;
        target.getParentFile().mkdirs();
        try(FileOutputStream fos=new FileOutputStream(target)){
            int payload=Math.min(n-12,(int)Math.min(Integer.MAX_VALUE,remaining));
            if(payload>0){fos.write(first,12,payload);remaining-=payload;}
            byte[] buf=new byte[64*1024];
            while(remaining>0){
                int want=(int)Math.min(buf.length,remaining);
                int got=connection.bulkTransfer(in,buf,want,15000);
                if(got<=0) throw new IOException("Image transfer stopped");
                fos.write(buf,0,got); remaining-=got;
            }
        } catch(IOException e){ target.delete(); throw e; }
        Container resp=readContainer(10000);
        if(resp.type!=TYPE_RESPONSE || resp.code!=RC_OK) throw new IOException("GetObject response: 0x"+Integer.toHexString(resp.code));
    }

    private Response command(int op,long[] params, byte[] outgoing) throws IOException {
        int tx=transaction++;
        writeCommand(op,tx,params);
        if(outgoing!=null) throw new IOException("Outgoing PTP data not implemented");
        Container first=readContainer(10000);
        byte[] data=null;
        Container resp=first;
        if(first.type==TYPE_DATA){
            data=first.payload;
            resp=readContainer(10000);
        }
        if(resp.type!=TYPE_RESPONSE || resp.transaction!=tx) throw new IOException("Bad PTP response");
        return new Response(resp.code,data);
    }

    private void writeCommand(int op,int tx,long[] params) throws IOException {
        int len=12+(params==null?0:params.length*4);
        ByteBuffer b=ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(len).putShort((short)TYPE_COMMAND).putShort((short)op).putInt(tx);
        if(params!=null) for(long p:params)b.putInt((int)p);
        byte[] a=b.array();
        int sent=connection.bulkTransfer(out,a,a.length,5000);
        if(sent!=a.length) throw new IOException("USB command write failed");
    }

    private Container readContainer(int timeout) throws IOException {
        byte[] first=new byte[64*1024];
        int n=connection.bulkTransfer(in,first,first.length,timeout);
        if(n<12) throw new IOException("USB read timed out");
        ByteBuffer h=ByteBuffer.wrap(first,0,12).order(ByteOrder.LITTLE_ENDIAN);
        long total=Integer.toUnsignedLong(h.getInt());
        int type=Short.toUnsignedInt(h.getShort());
        int code=Short.toUnsignedInt(h.getShort());
        int tx=h.getInt();
        if(total<12 || total>128L*1024*1024) throw new IOException("Invalid PTP packet length");
        int dataLen=(int)total-12;
        byte[] payload=new byte[dataLen];
        int copied=Math.min(dataLen,n-12);
        if(copied>0) System.arraycopy(first,12,payload,0,copied);
        int pos=copied;
        while(pos<dataLen){
            byte[] chunk=new byte[Math.min(64*1024,dataLen-pos)];
            int got=connection.bulkTransfer(in,chunk,chunk.length,timeout);
            if(got<=0) throw new IOException("USB data read stopped");
            System.arraycopy(chunk,0,payload,pos,got); pos+=got;
        }
        return new Container(type,code,tx,payload);
    }

    @Override public void close(){
        try{ connection.releaseInterface(intf); }catch(Exception ignored){}
        try{ connection.close(); }catch(Exception ignored){}
    }

    private static final class Response { final int code; final byte[] data; Response(int c,byte[] d){code=c;data=d;} }
    private static final class Container {
        final int type,code,transaction; final byte[] payload;
        Container(int t,int c,int x,byte[] p){type=t;code=c;transaction=x;payload=p;}
    }
}
