package br.com.rac.print;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Editor de etiquetas com texto vetorial, QR, codigo de barras e imagem. */
public class ProEditorActivity extends Activity {
    private static final int PICK_IMAGE = 201, EXPORT_PDF = 202;
    private static final float DESIGN_W = 420f, DESIGN_H = 620f;
    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayDeque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditorCanvas preview;
    private EditText content;
    private SeekBar fontSize;
    private Spinner paper, height, copies;
    private TextView info, selectedInfo, status;
    private boolean syncing = false, snap = false, grid = false;
    private int selected = -1;
    private byte[] toExport;
    private final int purple = Color.rgb(89, 52, 156);

    static final class Item {
        String type = "text", value = "", font = "sans", uri = "";
        float x = 25, y = 45, size = 28, w = 250, h = 100;
        boolean bold = false, center = false;
        transient Bitmap bitmap;
        transient BitMatrix matrix;
        transient String matrixKey;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("RAC Print Pro — Editor");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = column();
        page.setPadding(dp(12), dp(12), dp(12), dp(22));
        page.setBackgroundColor(Color.rgb(247, 247, 250));
        scroll.addView(page);
        setContentView(scroll);

        TextView title = text("Editor de etiquetas", 25, true);
        page.addView(title);
        page.addView(text("Arraste elementos no papel. Toque para editar.", 13, false));
        LinearLayout sizes = horizontal();
        paper = spinner(new String[]{"58 mm — maquininha", "80 mm — cupom"});
        height = spinner(new String[]{"40 mm", "60 mm", "80 mm", "120 mm", "160 mm"});
        sizes.addView(paper, new LinearLayout.LayoutParams(0, dp(48), 1f));
        sizes.addView(height, new LinearLayout.LayoutParams(0, dp(48), 1f));
        page.addView(sizes);
        SharedPreferences prefs = getSharedPreferences("rac_print_settings", MODE_PRIVATE);
        paper.setSelection(prefs.getInt("paper_mm", 80) == 58 ? 0 : 1);
        height.setSelection(2);
        android.widget.AdapterView.OnItemSelectedListener resize = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (preview != null) { preview.invalidate(); updatePaperInfo(); }
                prefs.edit().putInt("paper_mm", paperMm()).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        };
        paper.setOnItemSelectedListener(resize);
        height.setOnItemSelectedListener(resize);
        info = text("", 12, false);
        page.addView(info);
        preview = new EditorCanvas();
        preview.setBackgroundColor(Color.rgb(225, 224, 232));
        page.addView(preview, new LinearLayout.LayoutParams(-1, dp(430)));
        updatePaperInfo();

        LinearLayout tool1 = horizontal();
        tool1.addView(button("+ Texto", () -> add("text", "Novo texto")), weight());
        tool1.addView(button("+ QR Code", () -> add("qr", "https://rodriguesacaiecia.web.app")), weight());
        tool1.addView(button("+ Barras", () -> add("barcode", "123456789")), weight());
        page.addView(tool1);
        LinearLayout tool2 = horizontal();
        tool2.addView(button("+ Imagem", () -> pickImage()), weight());
        tool2.addView(button("+ Moldura", () -> add("box", "")), weight());
        tool2.addView(button("+ Linha", () -> add("line", "")), weight());
        page.addView(tool2);
        LinearLayout tool3 = horizontal();
        tool3.addView(button("Desfazer", () -> historyUndo()), weight());
        tool3.addView(button("Refazer", () -> historyRedo()), weight());
        tool3.addView(button("Excluir", () -> remove()), weight());
        page.addView(tool3);

        selectedInfo = text("Selecione um elemento no papel", 15, true);
        selectedInfo.setPadding(0, dp(12), 0, 0);
        page.addView(selectedInfo);
        content = new EditText(this);
        content.setHint("Texto, endereço, QR ou código de barras");
        content.setMinLines(2);
        content.setMaxLines(5);
        page.addView(content);
        content.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!syncing && active() != null) {
                    active().value = s.toString();
                    active().matrix = null;
                    preview.invalidate();
                }
            }
            public void afterTextChanged(Editable e) {}
        });
        page.addView(text("Tamanho / largura do elemento", 12, false));
        fontSize = new SeekBar(this);
        fontSize.setMax(85); fontSize.setProgress(28);
        page.addView(fontSize);
        fontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                if (fromUser && active() != null) {
                    if ("text".equals(active().type)) active().size = Math.max(11, progress);
                    else active().w = Math.max(55, progress * 5f);
                    preview.invalidate();
                }
            }
            public void onStartTrackingTouch(SeekBar b) { remember(); }
            public void onStopTrackingTouch(SeekBar b) {}
        });
        LinearLayout format = horizontal();
        format.addView(button("Negrito", () -> { if (active()!=null){remember();active().bold=!active().bold;preview.invalidate();} }), weight());
        format.addView(button("Centralizar", () -> { if(active()!=null){remember();active().center=!active().center;preview.invalidate();} }), weight());
        format.addView(button("Fonte", () -> { if(active()!=null){remember(); active().font="sans".equals(active().font)?"serif":("serif".equals(active().font)?"mono":"sans");preview.invalidate();} }), weight());
        page.addView(format);
        LinearLayout edit2 = horizontal();
        edit2.addView(button("Pra frente", () -> moveLayer(1)), weight());
        edit2.addView(button("Pra trás", () -> moveLayer(-1)), weight());
        edit2.addView(button("Grade: ligar/desligar", () -> {grid=!grid;snap=grid;preview.invalidate();}), weight());
        page.addView(edit2);
        page.addView(text("Modelos prontos", 16, true));
        LinearLayout templates1 = horizontal();
        templates1.addView(button("Endereço", () -> template("endereco")), weight());
        templates1.addView(button("Comanda", () -> template("comanda")), weight());
        templates1.addView(button("Preço", () -> template("preco")), weight());
        page.addView(templates1);
        LinearLayout templates2 = horizontal();
        templates2.addView(button("Validade", () -> template("validade")), weight());
        templates2.addView(button("Salvar modelo", () -> saveTemplate()), weight());
        templates2.addView(button("Abrir modelo", () -> openTemplate()), weight());
        page.addView(templates2);
        page.addView(text("Cópias", 13, false));
        copies = spinner(new String[]{"1", "2", "3"});
        page.addView(copies);
        LinearLayout actions = horizontal();
        actions.addView(button("SALVAR PDF", () -> exportPdf()), weight());
        actions.addView(button("IMPRIMIR", () -> sendPrint()), weight());
        page.addView(actions);
        status = text("Impressão pela rede local, usando o receptor Windows já instalado.", 12, false);
        page.addView(status);
        templateInternal("endereco");
        undo.clear();
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, dp(48), 1f); }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(value);t.setTextSize(sp);
        t.setTextColor(Color.rgb(42,34,58)); if(bold)t.setTypeface(null, Typeface.BOLD);t.setPadding(dp(3),dp(6),dp(3),dp(6));return t;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setTextSize(11); b.setTextColor(purple); b.setOnClickListener(v -> action.run()); return b;
    }
    private Spinner spinner(String[] options) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options)); return s;
    }
    private int paperMm(){return paper != null && paper.getSelectedItemPosition()==0 ? 58 : 80;}
    private int heightMm(){int[] h={40,60,80,120,160};return h[height==null?2:Math.min(4,height.getSelectedItemPosition())];}
    private void updatePaperInfo(){if(info!=null)info.setText("Bobina "+paperMm()+" mm · comprimento "+heightMm()+" mm · PDF vetorial");}
    private Item active(){return selected>=0&&selected<items.size()?items.get(selected):null;}
    private void selection(int index) {
        selected=index;
        syncing=true;
        Item i=active();
        content.setText(i==null?"":i.value);
        selectedInfo.setText(i==null?"Selecione um elemento no papel":"Selecionado: "+i.type+" (arraste para posicionar)");
        fontSize.setProgress(i==null?28:(int)("text".equals(i.type)?i.size:Math.min(85,i.w/5)));
        content.setEnabled(i!=null && !("box".equals(i.type)||"line".equals(i.type)||"image".equals(i.type)));
        syncing=false;preview.invalidate();
    }
    private Item create(String type,String value,float x,float y,float size,float w,float h){
        Item i=new Item();i.type=type;i.value=value;i.x=x;i.y=y;i.size=size;i.w=w;i.h=h;return i;
    }
    private void add(String type,String value) {
        remember();Item i=create(type,value,25+items.size()*9,35+items.size()*32,28,300,95);
        if("qr".equals(type)) {i.w=140;i.h=140;} if("barcode".equals(type)) {i.w=280;i.h=90;}
        if("box".equals(type)) {i.w=370;i.h=140;} if("line".equals(type)) {i.w=370;i.h=3;}
        items.add(i); selection(items.size()-1);
    }
    private void remove(){if(active()==null)return;remember();items.remove(selected);selection(-1);}
    private void moveLayer(int direction) {
        if(active()==null)return;int next=selected+direction;if(next<0||next>=items.size())return;
        remember();Item i=items.remove(selected);items.add(next,i);selection(next);
    }
    private void template(String id) {
        new AlertDialog.Builder(this).setTitle("Substituir os elementos?")
                .setMessage("O modelo atual pode ser salvo antes de trocar.")
                .setPositiveButton("Substituir",(d,w)->{remember();templateInternal(id);})
                .setNegativeButton("Cancelar",null).show();
    }
    private void templateInternal(String id) {
        items.clear();
        if("comanda".equals(id)){
            Item a=create("text","RODRIGUES AÇAÍ E CIA",20,30,34,380,75);a.bold=true;a.center=true;items.add(a);
            items.add(create("line","",20,103,20,380,4));
            items.add(create("text","PEDIDO #001\nCLIENTE: __________________\n\n1x AÇAÍ 700 ML\n  Leite condensado\n  Leite em pó\n\nOBS: ___________________\n\nTOTAL: R$ 00,00",22,126,26,376,420));
            items.add(create("line","",20,570,20,380,4));
        }else if("preco".equals(id)) {
            Item a=create("text","OFERTA ESPECIAL",18,70,30,385,80);a.bold=true;a.center=true;items.add(a);
            Item b=create("text","R$ 19,90",16,235,65,388,90);b.bold=true;b.center=true;items.add(b);
            items.add(create("box","",10,20,25,400,550));
        }else if("validade".equals(id)){
            Item a=create("text","IDENTIFICAÇÃO / VALIDADE",20,45,32,380,80);a.bold=true;items.add(a);
            items.add(create("text","Produto: __________________\n\nFabricação: ___/___/_____\n\nValidade: ___/___/_____\n\nLote: ____________________",23,145,26,380,310));
            items.add(create("box","",10,12,24,400,545));
        }else{
            Item a=create("text","ETIQUETA DE ENTREGA",20,30,32,385,70);a.bold=true;a.center=true;items.add(a);
            items.add(create("line","",18,107,18,380,4));
            items.add(create("text","CLIENTE: __________________\n\nRUA: ______________________\n\nNÚMERO: ______  COMPL: _____\n\nBAIRRO: ___________________\n\nREFERÊNCIA: _______________",19,140,25,387,370));
        }
        selection(items.isEmpty()?-1:0);
    }
    private void pickImage(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_IMAGE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(request==PICK_IMAGE && data.getData()!=null){
            try { getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(Exception ignored){}
            remember();Item i=create("image","",35,90,25,300,220);i.uri=data.getData().toString();i.bitmap=decode(i.uri);
            if(i.bitmap==null){toast("Não consegui abrir a imagem.");return;}
            items.add(i);selection(items.size()-1);
        }else if(request==EXPORT_PDF && data.getData()!=null && toExport!=null) {
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                if(out==null)throw new Exception("Sem acesso ao destino.");out.write(toExport);toast("PDF salvo!");
            }catch(Exception e){toast("Erro ao salvar: "+e.getMessage());}finally{toExport=null;}
        }
    }
    private Bitmap decode(String uri){
        try(InputStream stream=getContentResolver().openInputStream(Uri.parse(uri))){
            BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;return BitmapFactory.decodeStream(stream,null,o);
        }catch(Exception e){return null;}
    }
    private void remember(){
        undo.push(serialize().toString());if(undo.size()>30)undo.removeLast();redo.clear();
    }
    private void historyUndo(){if(undo.isEmpty()){toast("Nada para desfazer.");return;}redo.push(serialize().toString());restore(undo.pop());}
    private void historyRedo(){if(redo.isEmpty()){toast("Nada para refazer.");return;}undo.push(serialize().toString());restore(redo.pop());}
    private JSONObject serialize(){
        JSONObject doc=new JSONObject();
        try{doc.put("paper",paperMm());doc.put("height",heightMm());JSONArray arr=new JSONArray();
            for(Item i:items){JSONObject j=new JSONObject();j.put("type",i.type);j.put("value",i.value);j.put("font",i.font);j.put("uri",i.uri);
                j.put("x",i.x);j.put("y",i.y);j.put("w",i.w);j.put("h",i.h);j.put("size",i.size);j.put("bold",i.bold);j.put("center",i.center);arr.put(j);}
            doc.put("items",arr);
        }catch(Exception ignored){}return doc;
    }
    private void restore(String json){
        try{
            JSONObject d=new JSONObject(json);
            paper.setSelection(d.optInt("paper",80)==58?0:1);
            int[] heights={40,60,80,120,160};for(int k=0;k<heights.length;k++)if(heights[k]==d.optInt("height",80))height.setSelection(k);
            items.clear();JSONArray arr=d.getJSONArray("items");
            for(int k=0;k<arr.length();k++){JSONObject j=arr.getJSONObject(k);Item i=new Item();
                i.type=j.optString("type","text");i.value=j.optString("value","");i.font=j.optString("font","sans");i.uri=j.optString("uri","");
                i.x=(float)j.optDouble("x",20);i.y=(float)j.optDouble("y",40);i.w=(float)j.optDouble("w",250);i.h=(float)j.optDouble("h",70);
                i.size=(float)j.optDouble("size",28);i.bold=j.optBoolean("bold");i.center=j.optBoolean("center");
                if("image".equals(i.type)&&!i.uri.isEmpty())i.bitmap=decode(i.uri);
                items.add(i);
            }selection(items.isEmpty()?-1:0);
        }catch(Exception e){toast("Modelo inválido.");}
    }
    private void saveTemplate(){
        EditText input=new EditText(this);input.setHint("Nome do modelo");
        new AlertDialog.Builder(this).setTitle("Salvar modelo no celular").setView(input)
                .setPositiveButton("Salvar",(d,w)->{
                    String name=input.getText().toString().trim();if(name.isEmpty()){toast("Digite o nome.");return;}
                    getSharedPreferences("rac_print_models",MODE_PRIVATE).edit().putString(name,serialize().toString()).apply();
                    toast("Modelo salvo: "+name);
                }).setNegativeButton("Cancelar",null).show();
    }
    private void openTemplate(){
        SharedPreferences prefs=getSharedPreferences("rac_print_models",MODE_PRIVATE);
        String[] names=prefs.getAll().keySet().toArray(new String[0]);
        if(names.length==0){toast("Ainda não há modelos salvos.");return;}
        java.util.Arrays.sort(names);
        new AlertDialog.Builder(this).setTitle("Meus modelos").setItems(names,(d,which)->{
            remember();restore(prefs.getString(names[which],"{}"));
        }).setNegativeButton("Cancelar",null).show();
    }
    private BitMatrix matrix(Item i){
        if(i.matrix!=null&&i.value.equals(i.matrixKey))return i.matrix;
        try{
            if(i.value.trim().isEmpty())return null;
            i.matrix=new MultiFormatWriter().encode(i.value, "qr".equals(i.type)?BarcodeFormat.QR_CODE:BarcodeFormat.CODE_128,128,128,
                    java.util.Collections.singletonMap(com.google.zxing.EncodeHintType.MARGIN,0));
            i.matrixKey=i.value;return i.matrix;
        }catch(Exception ignored){return null;}
    }
    private void drawContent(Canvas c,boolean guide){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);
        for(int index=0;index<items.size();index++){
            Item i=items.get(index);
            if("text".equals(i.type)){
                Typeface base="serif".equals(i.font)?Typeface.SERIF:("mono".equals(i.font)?Typeface.MONOSPACE:Typeface.DEFAULT);
                p.setTypeface(Typeface.create(base,i.bold?Typeface.BOLD:Typeface.NORMAL));p.setStyle(Paint.Style.FILL);p.setTextSize(i.size);
                float y=i.y+i.size;
                for(String paragraph:i.value.split("\\n",-1)){
                    String remaining=paragraph;
                    do{
                        int chars=remaining.isEmpty()?0:p.breakText(remaining,true,Math.max(30,i.w),null);
                        if(chars==0 && !remaining.isEmpty())chars=1;
                        String line=remaining.substring(0,chars);
                        if(i.center)c.drawText(line,i.x+Math.max(0,(i.w-p.measureText(line))/2),y,p);
                        else c.drawText(line,i.x,y,p);
                        remaining=remaining.substring(chars);y+=i.size*1.22f;
                    }while(!remaining.isEmpty()&&y<i.y+Math.max(i.h,60));
                    if(y>DESIGN_H+60)break;
                }
            }else if("box".equals(i.type)){
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);
                c.drawRoundRect(new RectF(i.x,i.y,i.x+i.w,i.y+i.h),6,6,p);p.setStyle(Paint.Style.FILL);
            }else if("line".equals(i.type)){
                p.setStrokeWidth(3);c.drawLine(i.x,i.y,i.x+i.w,i.y,p);
            }else if("image".equals(i.type)){
                if(i.bitmap!=null){
                    c.drawBitmap(i.bitmap,null,new RectF(i.x,i.y,i.x+i.w,i.y+i.h),
                            new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
                }
            }else if("qr".equals(i.type)||"barcode".equals(i.type)){
                BitMatrix m=matrix(i);
                if(m!=null){
                    int mw=m.getWidth(),mh=m.getHeight();
                    float vh="qr".equals(i.type)?i.w:i.h;
                    float sx=i.w/mw,sy=vh/mh;
                    for(int yy=0;yy<mh;yy++)for(int xx=0;xx<mw;xx++)if(m.get(xx,yy)){
                        c.drawRect(i.x+xx*sx,i.y+yy*sy,i.x+(xx+1)*sx+.1f,i.y+(yy+1)*sy+.1f,p);
                    }
                }
            }
            if(guide && index==selected){
                p.setColor(purple);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);
                float boxH="qr".equals(i.type)?i.w:i.h;
                c.drawRect(i.x-5,i.y-5,i.x+i.w+5,i.y+boxH+5,p);
                p.setStyle(Paint.Style.FILL);p.setColor(Color.BLACK);
            }
        }
    }
    private final class EditorCanvas extends View{
        private float left,top,rw,rh,downX,downY,oldX,oldY;
        EditorCanvas(){super(ProEditorActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float boxW=getWidth()-dp(24),boxH=getHeight()-dp(24);
            float ratio=(float)paperMm()/heightMm();
            rw=Math.min(boxW,boxH*ratio);rh=rw/ratio;
            left=(getWidth()-rw)/2f;top=(getHeight()-rh)/2f;
            Paint paperPaint=new Paint(Paint.ANTI_ALIAS_FLAG);paperPaint.setColor(Color.WHITE);
            c.drawRect(left,top,left+rw,top+rh,paperPaint);
            c.save();c.translate(left,top);c.clipRect(0,0,rw,rh);c.scale(rw/DESIGN_W,rh/DESIGN_H);
            if(grid){Paint gp=new Paint();gp.setColor(0xFFEAE4F2);gp.setStrokeWidth(1);
                for(int x=0;x<DESIGN_W;x+=35)c.drawLine(x,0,x,DESIGN_H,gp);
                for(int y=0;y<DESIGN_H;y+=35)c.drawLine(0,y,DESIGN_W,y,gp);}
            drawContent(c,true);c.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(rw<=0||rh<=0)return false;
            float x=(event.getX()-left)*DESIGN_W/rw,y=(event.getY()-top)*DESIGN_H/rh;
            if(event.getAction()==MotionEvent.ACTION_DOWN){
                for(int k=items.size()-1;k>=0;k--){
                    Item i=items.get(k);
                    if(x>=i.x-8&&x<=i.x+i.w+8&&y>=i.y-10&&y<=i.y+Math.max(36,i.h)+10){
                        selection(k);remember();downX=x;downY=y;oldX=i.x;oldY=i.y;return true;
                    }
                }
                selection(-1);return true;
            }
            if(event.getAction()==MotionEvent.ACTION_MOVE&&active()!=null){
                active().x=Math.max(0,Math.min(DESIGN_W-active().w,oldX+x-downX));
                active().y=Math.max(0,Math.min(DESIGN_H-20,oldY+y-downY));
                if(snap){active().x=Math.round(active().x/7)*7;active().y=Math.round(active().y/7)*7;}
                invalidate();return true;
            }
            return true;
        }
    }
    private byte[] makePdf() throws Exception {
        PdfDocument doc=new PdfDocument();
        try{
            // Compatibilidade com receptor Windows v0.2.1: pagina conserva largura 80 mm
            // e o conteudo de 58 mm ocupa apenas 58/80 da largura, evitando corte
            // quando o driver do PC ainda esta configurado como 80 mm.
            int pageWidth=576;
            float contentWidth=pageWidth*paperMm()/80f;
            int pageHeight=(int)Math.ceil(pageWidth*heightMm()/80f);
            PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(pageWidth,Math.max(100,pageHeight),1).create());
            Canvas c=page.getCanvas();c.drawColor(Color.WHITE);c.save();
            c.clipRect(0,0,contentWidth,pageHeight);
            c.scale(contentWidth/DESIGN_W,pageHeight/DESIGN_H);
            drawContent(c,false);c.restore();doc.finishPage(page);
            ByteArrayOutputStream out=new ByteArrayOutputStream();doc.writeTo(out);
            return out.toByteArray();
        }finally{doc.close();}
    }
    private void exportPdf(){
        try{
            toExport=makePdf();
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.setType("application/pdf");i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_TITLE,"RAC-Print-Pro-Etiqueta.pdf");startActivityForResult(i,EXPORT_PDF);
        }catch(Exception ex){toast("Erro no PDF: "+ex.getMessage());}
    }
    private void sendPrint(){
        String host=getSharedPreferences("rac_print_settings",MODE_PRIVATE).getString("ip","");
        String secret=getSharedPreferences("rac_print_settings",MODE_PRIVATE).getString("token","");
        final String base;
        try{base=RacPrintBridgeClient.address(host,secret);}catch(Exception e){toast("Configure o IP e a chave na tela inicial.");return;}
        final byte[] pdf;
        try{pdf=makePdf();}catch(Exception e){toast("Erro no PDF: "+e.getMessage());return;}
        int count=copies.getSelectedItemPosition()+1;
        status.setText("Enviando à impressora...");
        worker.execute(()->{
            try{
                JSONObject accepted=RacPrintBridgeClient.request(base+"/print",secret,pdf,count);
                String id=accepted.getString("id");String result="Recebido pelo PC. Verifique o papel.";
                for(int k=0;k<18;k++){
                    Thread.sleep(650);
                    JSONObject response=RacPrintBridgeClient.request(base+"/jobs/"+id,secret,null,1);
                    if("spooled".equals(response.optString("status"))){result="Enviado ao spooler do Windows ✓";break;}
                    if("error".equals(response.optString("status"))){result="Erro: "+response.optString("error");break;}
                }
                final String s=result;runOnUiThread(()->status.setText(s));
            }catch(Exception e){runOnUiThread(()->status.setText("Falha: "+e.getMessage()));}
        });
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
