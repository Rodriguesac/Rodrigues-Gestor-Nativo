package com.rodriguesac.etiquetas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PURPLE = Color.rgb(90, 29, 128);
    private static final int DARK = Color.rgb(42, 30, 50);
    private static final int PALE = Color.rgb(248, 245, 251);
    private static final int SAVE_PDF = 111;
    private SharedPreferences prefs;
    private EditText name, note, qty;
    private Switch uppercase, bold, centered, border, oneLine;
    private RadioButton paper58, paper80;
    private SeekBar font;
    private TextView fontInfo;
    private LinearLayout chipHolder;
    private Preview preview;
    private Label jobForSave;
    private final ArrayList<String> saved = new ArrayList<>();

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int size, int color, boolean heavy) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (heavy) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private GradientDrawable background(int color, boolean outline) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        if (outline) g.setStroke(dp(1), Color.rgb(227, 218, 235));
        return g;
    }
    private LinearLayout col() { LinearLayout l=new LinearLayout(this); l.setOrientation(1); return l; }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height))); }
    private LinearLayout card(LinearLayout root,String title) {
        LinearLayout l=col();
        l.setPadding(dp(14),dp(14),dp(14),dp(14));
        l.setBackground(background(Color.WHITE,true));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(12);
        root.addView(l,p);
        l.addView(text(title,16,DARK,true));
        space(l,12);
        return l;
    }
    private EditText field(LinearLayout parent,String hint,String value,boolean multi) {
        EditText e=new EditText(this);
        e.setTextSize(17);
        e.setTextColor(DARK);
        e.setHint(hint);
        e.setSingleLine(!multi);
        if (multi) e.setMinLines(2);
        e.setBackground(background(PALE,true));
        e.setPadding(dp(12),dp(10),dp(12),dp(10));
        e.setText(value);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=dp(12);
        parent.addView(e,lp);
        return e;
    }
    private Button button(String label, boolean primary) {
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : PURPLE);
        b.setBackground(background(primary?PURPLE:PALE,!primary));
        return b;
    }
    private Switch toggle(LinearLayout l,String title,boolean value) {
        Switch sw=new Switch(this);
        sw.setText(title); sw.setTextColor(DARK); sw.setTextSize(14); sw.setChecked(value);
        sw.setPadding(0,dp(4),0,dp(4));
        l.addView(sw);
        return sw;
    }
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(PURPLE);
        prefs=getSharedPreferences("etiquetas",MODE_PRIVATE);
        try {
            JSONArray a=new JSONArray(prefs.getString("saved","[]"));
            for (int i=0;i<a.length();i++) if (!a.optString(i).isEmpty()) saved.add(a.optString(i));
        } catch(Exception ignored) {}
        ScrollView sc=new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(PALE);
        LinearLayout root=col();
        root.setPadding(dp(16),dp(20),dp(16),dp(26));
        sc.addView(root);
        setContentView(sc);
        root.addView(text("RAC ETIQUETAS",25,PURPLE,true));
        root.addView(text("Editor e impressão térmica sem internet",13,Color.DKGRAY,false));
        space(root,18);
        LinearLayout box=card(root,"Modelos rápidos");
        HorizontalScrollView h=new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        chipHolder=new LinearLayout(this);
        chipHolder.setOrientation(0);
        h.addView(chipHolder); box.addView(h);

        box=card(root,"Editar etiqueta");
        name=field(box,"Nome ou texto",prefs.getString("name","PEDIDO DA FABÍOLA"),false);
        box.addView(text("Linha menor (opcional)",13,DARK,false));
        space(box,5);
        note=field(box,"Ex.: RETIRAR NO BALCÃO",prefs.getString("note",""),true);
        Button save=button("★ Salvar modelo",false);
        box.addView(save);
        save.setOnClickListener(v->saveTemplate());

        box=card(root,"Papel e estilo");
        RadioGroup rg=new RadioGroup(this); rg.setOrientation(0);
        paper58=new RadioButton(this); paper58.setText("58 mm"); paper58.setId(View.generateViewId());
        paper80=new RadioButton(this); paper80.setText("80 mm"); paper80.setId(View.generateViewId());
        rg.addView(paper58); rg.addView(paper80); box.addView(rg);
        if (prefs.getInt("paper",80)==58) paper58.setChecked(true); else paper80.setChecked(true);
        uppercase=toggle(box,"CAIXA ALTA",prefs.getBoolean("upper",true));
        bold=toggle(box,"Negrito",prefs.getBoolean("bold",true));
        centered=toggle(box,"Centralizar",prefs.getBoolean("center",true));
        border=toggle(box,"Borda",prefs.getBoolean("border",false));
        oneLine=toggle(box,"Texto em uma linha",prefs.getBoolean("single",true));
        box.addView(text("Tamanho da letra",14,DARK,false));
        fontInfo=text("",13,PURPLE,true); box.addView(fontInfo);
        font=new SeekBar(this); font.setMax(28);
        font.setProgress(Math.min(28,Math.max(0,prefs.getInt("font",24)-14)));
        box.addView(font);
        box.addView(text("Número de cópias (até 50)",14,DARK,false));
        qty=field(box,"1",prefs.getString("qty","1"),false);
        qty.setInputType(InputType.TYPE_CLASS_NUMBER);
        qty.setSelectAllOnFocus(true);

        box=card(root,"Prévia e impressão");
        preview=new Preview(this);
        box.addView(preview,new LinearLayout.LayoutParams(-1,dp(155)));
        space(box,10);
        Button print=button("IMPRIMIR ETIQUETAS",true);
        box.addView(print,new LinearLayout.LayoutParams(-1,dp(55)));
        print.setOnClickListener(v->printLabels());
        space(box,10);
        Button pdf=button("Salvar PDF no celular",false);
        box.addView(pdf,new LinearLayout.LayoutParams(-1,dp(49)));
        pdf.setOnClickListener(v->saveAsPdf());
        space(box,10);
        box.addView(text("Na impressão, selecione RAC Print. A impressora e o computador precisam estar ligados e configurados.",12,Color.GRAY,false));

        TextWatcher watcher=new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {refresh();}
            @Override public void afterTextChanged(Editable e) {}
        };
        name.addTextChangedListener(watcher); note.addTextChangedListener(watcher); qty.addTextChangedListener(watcher);
        CompoundButton.OnCheckedChangeListener switches=(v,checked)->refresh();
        uppercase.setOnCheckedChangeListener(switches);bold.setOnCheckedChangeListener(switches);
        centered.setOnCheckedChangeListener(switches);border.setOnCheckedChangeListener(switches);
        oneLine.setOnCheckedChangeListener(switches);
        rg.setOnCheckedChangeListener((g,id)->refresh());
        font.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b,int p,boolean fromUser){refresh();}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        rebuildChips();
        refresh();
    }
    private void rebuildChips() {
        chipHolder.removeAllViews();
        String[] base={"PEDIDO DA FABÍOLA","PEDIDO DO FRANCISCO","PEDIDO DE","RETIRADA","ENTREGA","CASADINHO"};
        for(String s:base) chip(s,false);
        for(String s:saved) chip(s,true);
    }
    private void chip(String label,boolean removable) {
        Button b=button(label,false);b.setTextSize(12);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(49));lp.rightMargin=dp(8);
        chipHolder.addView(b,lp);
        b.setOnClickListener(v->{name.setText(label);name.setSelection(name.length());note.setText("");});
        if (removable) b.setOnLongClickListener(v->{
            new AlertDialog.Builder(this).setTitle("Excluir modelo?")
            .setMessage(label).setNegativeButton("Cancelar",null)
            .setPositiveButton("Excluir",(dlg,which)->{
                saved.remove(label);saveTemplates();rebuildChips();
            }).show();return true;
        });
    }
    private void saveTemplate() {
        String value=name.getText().toString().trim();
        if (value.isEmpty()) {Toast.makeText(this,"Escreva um texto",Toast.LENGTH_SHORT).show();return;}
        if (uppercase.isChecked()) value=value.toUpperCase(new Locale("pt","BR"));
        if (!saved.contains(value)) {
            if (saved.size()>=15) saved.remove(0);
            saved.add(value);saveTemplates();rebuildChips();
        }
        Toast.makeText(this,"Modelo salvo no celular",Toast.LENGTH_SHORT).show();
    }
    private void saveTemplates() {
        JSONArray a=new JSONArray();for(String s:saved)a.put(s);
        prefs.edit().putString("saved",a.toString()).apply();
    }
    private static class Label {
        String title,sub;boolean upper,bold,center,border,single;int paper,size,count;
    }
    private Label state() {
        Label l=new Label();
        l.title=name.getText().toString().trim();if(l.title.isEmpty())l.title="PEDIDO";
        l.sub=note.getText().toString().trim();
        l.upper=uppercase.isChecked();l.bold=bold.isChecked();l.center=centered.isChecked();
        l.border=border.isChecked();l.single=oneLine.isChecked();
        l.paper=paper58.isChecked()?58:80;l.size=14+font.getProgress();
        try{l.count=Integer.parseInt(qty.getText().toString());}catch(Exception ex){l.count=1;}
        l.count=Math.max(1,Math.min(50,l.count));
        if(l.upper){Locale p=new Locale("pt","BR");l.title=l.title.toUpperCase(p);l.sub=l.sub.toUpperCase(p);}
        return l;
    }
    private void refresh() {
        if(preview==null||name==null||qty==null)return;
        Label l=state();
        fontInfo.setText(l.size+" pt");
        preview.invalidate();
        prefs.edit().putString("name",name.getText().toString())
        .putString("note",note.getText().toString())
        .putString("qty",qty.getText().toString())
        .putInt("paper",l.paper).putInt("font",l.size)
        .putBoolean("upper",l.upper).putBoolean("bold",l.bold)
        .putBoolean("center",l.center).putBoolean("border",l.border)
        .putBoolean("single",l.single).apply();
    }
    private int width(Label l){return Math.round(l.paper*72f/25.4f);}
    private int height(Label l){return l.sub.isEmpty()?82:108;}
    private void drawLabel(Canvas canvas,Label l,int w,int h){
        canvas.drawColor(Color.WHITE);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        if(l.border){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1f);
            canvas.drawRoundRect(5,5,w-5,h-5,3,3,p);p.setStyle(Paint.Style.FILL);}
        p.setTypeface(Typeface.create(Typeface.DEFAULT,l.bold?Typeface.BOLD:Typeface.NORMAL));
        float size=l.size; p.setTextSize(size);
        while(p.measureText(l.title)>w-24 && size>7.5f){size-=0.5f;p.setTextSize(size);}
        p.setTextAlign(l.center?Paint.Align.CENTER:Paint.Align.LEFT);
        float x=l.center?w/2f:12f;
        Paint.FontMetrics m=p.getFontMetrics();
        float y=h/2f-(m.ascent+m.descent)/2f-(l.sub.isEmpty()?0:12);
        canvas.drawText(l.title,x,y,p);
        if(!l.sub.isEmpty()){
            p.setTextSize(10.5f);p.setTypeface(Typeface.DEFAULT_BOLD);
            while(p.measureText(l.sub)>w-24 && p.getTextSize()>6.5f)p.setTextSize(p.getTextSize()-0.5f);
            canvas.drawText(l.sub,x,h-23,p);
        }
    }
    private PdfDocument createPdf(Label l){
        PdfDocument pdf=new PdfDocument();
        int w=width(l),h=height(l);
        for(int i=0;i<l.count;i++){
            PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(w,h,i+1).create());
            drawLabel(page.getCanvas(),l,w,h);
            pdf.finishPage(page);
        }
        return pdf;
    }
    private class Preview extends View {
        Preview(Context c){super(c);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            canvas.drawColor(PALE);
            Label l=state();int w=width(l),h=height(l);
            float scale=Math.min((getWidth()-dp(24))/(float)w,(getHeight()-dp(20))/(float)h);
            float left=(getWidth()-w*scale)/2f,top=(getHeight()-h*scale)/2f;
            Paint back=new Paint(Paint.ANTI_ALIAS_FLAG);back.setColor(Color.rgb(224,215,230));
            canvas.drawRoundRect(left-3,top-3,left+w*scale+3,top+h*scale+3,6,6,back);
            canvas.save();canvas.translate(left,top);canvas.scale(scale,scale);
            drawLabel(canvas,l,w,h);canvas.restore();
        }
    }
    private void printLabels(){
        final Label l=state();
        PrintManager mgr=(PrintManager)getSystemService(Context.PRINT_SERVICE);
        if(mgr==null){Toast.makeText(this,"Impressão não disponível",Toast.LENGTH_LONG).show();return;}
        PrintDocumentAdapter adapter=new PrintDocumentAdapter(){
            @Override public void onLayout(PrintAttributes oldA,PrintAttributes newA,
                CancellationSignal signal,LayoutResultCallback callback,Bundle b){
                if(signal.isCanceled()){callback.onLayoutCancelled();return;}
                PrintDocumentInfo info=new PrintDocumentInfo.Builder("RAC-Etiquetas.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(l.count).build();
                callback.onLayoutFinished(info,!newA.equals(oldA));
            }
            @Override public void onWrite(PageRange[] pages,ParcelFileDescriptor fd,
                CancellationSignal signal,WriteResultCallback callback){
                if(signal.isCanceled()){callback.onWriteCancelled();return;}
                PdfDocument doc=createPdf(l);
                try(FileOutputStream stream=new FileOutputStream(fd.getFileDescriptor())){
                    doc.writeTo(stream);stream.flush();
                    callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                }catch(Exception ex){callback.onWriteFailed(ex.getMessage());}
                finally {doc.close();}
            }
        };
        mgr.print("RAC Etiquetas",adapter,new PrintAttributes.Builder()
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build());
    }
    private void saveAsPdf(){
        jobForSave=state();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");i.putExtra(Intent.EXTRA_TITLE,"RAC-Etiquetas.pdf");
        startActivityForResult(i,SAVE_PDF);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=SAVE_PDF||result!=RESULT_OK||data==null||data.getData()==null||jobForSave==null)return;
        Uri uri=data.getData();
        PdfDocument doc=createPdf(jobForSave);
        try(OutputStream out=getContentResolver().openOutputStream(uri)){
            if(out==null)throw new IllegalStateException("Arquivo indisponível");
            doc.writeTo(out);out.flush();
            Toast.makeText(this,"PDF salvo no celular",Toast.LENGTH_LONG).show();
        }catch(Exception ex){Toast.makeText(this,"Erro ao salvar: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
        finally {doc.close();}
    }
}
