package br.com.rac.print;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.*;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** USB CDC transport for the MP-4200 TH, VID 0B1B PID 0003. */
final class BematechUsb implements AutoCloseable {
    static final String PERMISSION = "br.com.rac.print.USB_PERMISSION";
    private final UsbDeviceConnection connection;
    private final UsbInterface control, data;
    private final UsbEndpoint out, in;

    static UsbManager manager(Context c) { return (UsbManager)c.getSystemService(Context.USB_SERVICE); }
    static UsbDevice device(Context c) {
        UsbManager m=manager(c);
        if(m==null) return null;
        for(UsbDevice d:m.getDeviceList().values())
            if(d.getVendorId()==0x0b1b && d.getProductId()==3) return d;
        return null;
    }
    static boolean ready(Context c) {
        UsbDevice d=device(c); return d!=null && manager(c).hasPermission(d);
    }
    static void request(Context c, UsbDevice d) {
        Intent intent=new Intent(PERMISSION).setPackage(c.getPackageName());
        manager(c).requestPermission(d, PendingIntent.getBroadcast(c, 71, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
    }
    BematechUsb(Context c) throws Exception {
        UsbDevice d=device(c);
        if(d==null) throw new Exception("Conecte a Bematech pelo cabo OTG.");
        if(!ready(c)) throw new Exception("Abra RAC Print e autorize o USB primeiro.");
        UsbInterface ci=null,di=null; UsbEndpoint eo=null,ei=null;
        for(int i=0;i<d.getInterfaceCount();i++){
            UsbInterface f=d.getInterface(i);
            if(f.getInterfaceClass()==UsbConstants.USB_CLASS_COMM) ci=f;
            if(f.getInterfaceClass()==UsbConstants.USB_CLASS_CDC_DATA){
                UsbEndpoint o=null,n=null;
                for(int k=0;k<f.getEndpointCount();k++){
                    UsbEndpoint e=f.getEndpoint(k);
                    if(e.getType()!=UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if(e.getDirection()==UsbConstants.USB_DIR_OUT) o=e; else n=e;
                }
                if(o!=null && n!=null){di=f;eo=o;ei=n;}
            }
        }
        if(ci==null || di==null) throw new Exception("Interfaces CDC não encontradas.");
        control=ci;data=di;out=eo;in=ei;
        connection=manager(c).openDevice(d);
        if(connection==null) throw new Exception("USB ocupado. Feche Serial USB Terminal.");
        try {
            if(!connection.claimInterface(control,true) || !connection.claimInterface(data,true))
                throw new Exception("Não foi possível abrir interfaces USB.");
            byte[] line={ (byte)0x80,0x25,0,0,0,0,8 }; // 9600 8N1
            connection.controlTransfer(0x21,0x20,0,control.getId(),line,line.length,1000);
            connection.controlTransfer(0x21,0x22,3,control.getId(),null,0,1000);
        } catch(Exception e){close();throw e;}
    }
    private void send(byte[] b) throws Exception {
        int offset=0;
        while(offset<b.length){
            if(Thread.currentThread().isInterrupted()) throw new Exception("Impressão interrompida.");
            int n=connection.bulkTransfer(out,b,offset,Math.min(1024,b.length-offset),5000);
            if(n<=0) throw new Exception("Falha USB; confira o papel antes de repetir.");
            offset+=n;
        }
    }
    private int mode() throws Exception {
        send(new byte[]{0x1d,(byte)0xf9,0x43,0});
        byte[] reply=new byte[64];
        int n=connection.bulkTransfer(in,reply,reply.length,700);
        return n==1 && (reply[0]==0 || reply[0]==1) ? reply[0] : -1;
    }
    private void cut(int mode) throws Exception {
        send(new byte[]{10,10,10});
        if(mode==1) send(new byte[]{0x1d,0x56,1});
        else if(mode==0) send(new byte[]{0x1b,0x6d});
        // Unknown command mode: feed only; avoid an incorrect cut command.
    }
    static synchronized void test(Context c) throws Exception {
        try(BematechUsb u=new BematechUsb(c)){
            u.send("RODRIGUES ACAI E CIA\nTESTE USB CDC - RAC PRINT\n\n\n".getBytes(StandardCharsets.US_ASCII));
        }
    }
    static synchronized void print(Context c,byte[] pdf,int copies,int width,BooleanSupplier stop) throws Exception {
        if(copies<1 || copies>3 || (width!=384 && width!=576)) throw new Exception("Configuração inválida.");
        File temp=File.createTempFile("rac-usb-", ".pdf",c.getCacheDir());
        try {
            try(FileOutputStream f=new FileOutputStream(temp)){f.write(pdf);}
            try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer renderer=new PdfRenderer(fd); BematechUsb u=new BematechUsb(c)){
                int mode=u.mode();
                u.send(new byte[]{0x1b,0x40});
                if(mode==1) u.send(new byte[]{0x1d,0x57,(byte)(width&255),(byte)(width>>8)});
                for(int copy=0;copy<copies;copy++){
                    for(int p=0;p<renderer.getPageCount();p++){
                        try(PdfRenderer.Page page=renderer.openPage(p)){
                            float scale=(float)width/page.getWidth();
                            int height=(int)Math.ceil(page.getHeight()*scale);
                            if(height<=0 || height>60000) throw new Exception("Página muito longa.");
                            for(int y=0;y<height;y+=128){
                                if(stop.getAsBoolean()) throw new Exception("Impressão cancelada.");
                                int rows=Math.min(128,height-y), stride=width/8;
                                Bitmap bitmap=Bitmap.createBitmap(width,rows,Bitmap.Config.ARGB_8888);
                                try{
                                    bitmap.eraseColor(Color.WHITE);
                                    Matrix matrix=new Matrix(); matrix.setScale(scale,scale);matrix.postTranslate(0,-y);
                                    page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                                    byte[] raster=new byte[8+stride*rows];
                                    raster[0]=0x1d;raster[1]=0x76;raster[2]=0x30;
                                    raster[4]=(byte)stride;raster[5]=(byte)(stride>>8);
                                    raster[6]=(byte)rows;raster[7]=(byte)(rows>>8);
                                    int[] pixels=new int[width]; 
                                    for(int row=0;row<rows;row++){
                                        bitmap.getPixels(pixels,0,width,0,row,width,1);
                                        for(int x=0;x<width;x++){
                                            int px=pixels[x];
                                            if((Color.red(px)*299+Color.green(px)*587+Color.blue(px)*114)<160000)
                                                raster[8+row*stride+x/8]|=(byte)(0x80>>(x%8));
                                        }
                                    }
                                    u.send(raster);
                                } finally {bitmap.recycle();}
                            }
                        }
                    }
                    u.cut(mode);
                }
            }
        } finally {temp.delete();}
    }
    @Override public void close(){
        try{connection.releaseInterface(data);}catch(Exception ignored){}
        try{connection.releaseInterface(control);}catch(Exception ignored){}
        connection.close();
    }
}
