package ru.freeoffice.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Bounded, offline text import/export helpers. Presentation imports extract text only; they do not render slides. */
public final class ModernFormats {
    private ModernFormats() {}
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_XML_EVENTS = 250000, MAX_DEPTH = 64;
    private static final int MAX_ROWS = 20000, MAX_COLS = 512, MAX_CELLS = 500000;
    private static final int MAX_SLIDES = 1000, MAX_TEXT = 2 * 1024 * 1024;
    private static final String WARN_TEXT = "Text extraction only; formatting, layout, images, charts and other visual content are not rendered. Formula values are not calculated; imported display strings are literal.";

    public static JSONObject open(File file, String extension) throws Exception {
        String ext = norm(extension);
        if ("csv".equals(ext) || "tsv".equals(ext)) return delimited(file, ext);
        if (!"odt".equals(ext) && !"ods".equals(ext) && !"odp".equals(ext) && !"pptx".equals(ext)) throw new Exception("Unsupported format: " + ext);
        JSONObject out = new JSONObject(); out.put("ok", true);
        if ("odt".equals(ext)) { out.put("paragraphs", parseOdt(zipEntry(file, "content.xml"))); out.put("warning", WARN_TEXT); }
        else if ("ods".equals(ext)) { out.put("sheets", parseOds(zipEntry(file, "content.xml"))); out.put("warning", WARN_TEXT); }
        else if ("odp".equals(ext)) { out.put("slides", parseOdp(zipEntry(file, "content.xml"))); out.put("warning", WARN_TEXT); }
        else { JSONObject pptx=parsePptx(file); out.put("slides", pptx.getJSONArray("slides")); out.put("fallbackOrder", pptx.getBoolean("fallbackOrder")); out.put("warning", WARN_TEXT + (pptx.getBoolean("fallbackOrder") ? " Порядок слайдов выведен из номеров файлов: порядок презентации недоступен." : "")); }
        return out;
    }

    public static void save(File target, String extension, JSONObject snapshot) throws Exception {
        String ext = norm(extension);
        if ("csv".equals(ext) || "tsv".equals(ext)) { saveDelimited(target, ext, snapshot); return; }
        if (!"odt".equals(ext) && !"ods".equals(ext)) throw new Exception("Export is supported only for ODT, ODS, CSV and TSV");
        File parent = target.getAbsoluteFile().getParentFile(); if (parent != null && !parent.exists() && !parent.mkdirs()) throw new Exception("Cannot create output directory");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(target))) {
            String mime = "odt".equals(ext) ? "application/vnd.oasis.opendocument.text" : "application/vnd.oasis.opendocument.spreadsheet";
            ZipEntry me = new ZipEntry("mimetype"); me.setMethod(ZipEntry.STORED); byte[] mb = mime.getBytes(StandardCharsets.US_ASCII); me.setSize(mb.length); me.setCompressedSize(mb.length); java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(mb); me.setCrc(crc.getValue()); z.putNextEntry(me); z.write(mb); z.closeEntry();
            put(z, "META-INF/manifest.xml", manifest(ext, mime));
            put(z, "content.xml", "odt".equals(ext) ? makeOdt(snapshot) : makeOds(snapshot));
        }
    }
    private static String norm(String s) { return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceFirst("^\\.", ""); }
    private static void put(ZipOutputStream z, String name, String text) throws Exception { z.putNextEntry(new ZipEntry(name)); z.write(text.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }

    private static byte[] zipEntry(File f, String name) throws Exception {
        try (ZipFile zip = new ZipFile(f)) {
            ZipEntry e = zip.getEntry(name); if (e == null) throw new Exception("Missing ZIP member: " + name);
            if (e.getSize() > MAX_BYTES) throw new Exception("ZIP member exceeds 32 MiB limit: " + name);
            try (InputStream in = zip.getInputStream(e)) { return capped(in, MAX_BYTES); }
        }
    }
    private static byte[] capped(InputStream in, int max) throws Exception { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n=in.read(buf))!=-1) { if (b.size() > max-n) throw new Exception("Decompressed/input data exceeds 32 MiB limit"); b.write(buf,0,n); } return b.toByteArray(); }
    private static JSONObject parsePptx(File file) throws Exception {
        JSONArray slides=new JSONArray(); boolean fallbackPptxWarning=false; long total=0,totalText=0; List<String> names=new ArrayList<>();
        try(ZipFile z=new ZipFile(file)) {
            byte[] pres=zipEntry(file,"ppt/presentation.xml"), relData=zipEntry(file,"ppt/_rels/presentation.xml.rels");
            total=(long)pres.length+relData.length;if(total>MAX_BYTES)throw new Exception("Превышен лимит распакованных данных PPTX");
            Map<String,String> rels=new HashMap<>(); XmlPullParser rp=parser(relData); int ev,depth=0,count=0;
            while((ev=rp.next())!=XmlPullParser.END_DOCUMENT){if(++count>MAX_XML_EVENTS)throw new Exception("Превышен лимит XML-событий");if(ev==XmlPullParser.START_TAG){if(++depth>MAX_DEPTH)throw new Exception("Превышена вложенность XML");if("Relationship".equals(rp.getName())){String id=rp.getAttributeValue(null,"Id"),target=rp.getAttributeValue(null,"Target"),mode=rp.getAttributeValue(null,"TargetMode");if(id!=null&&target!=null&&!("External".equalsIgnoreCase(mode)))rels.put(id,target);}}else if(ev==XmlPullParser.END_TAG)depth--;}
            XmlPullParser pp=parser(pres);depth=0;count=0;boolean list=false;
            while((ev=pp.next())!=XmlPullParser.END_DOCUMENT){if(++count>MAX_XML_EVENTS)throw new Exception("Превышен лимит XML-событий");if(ev==XmlPullParser.START_TAG){if(++depth>MAX_DEPTH)throw new Exception("Превышена вложенность XML");if("sldIdLst".equals(pp.getName()))list=true;else if(list&&"sldId".equals(pp.getName())){String id=pp.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");String target=rels.get(id);if(target==null)throw new Exception("Некорректная связь слайда PPTX");names.add(resolveTarget(target));if(names.size()>MAX_SLIDES)throw new Exception("Превышен лимит слайдов (1000)");}}else if(ev==XmlPullParser.END_TAG){if("sldIdLst".equals(pp.getName()))list=false;depth--;}}
            if(names.isEmpty()){Enumeration<? extends ZipEntry> es=z.entries();while(es.hasMoreElements()){String n=es.nextElement().getName();if(n.matches("ppt/slides/slide[0-9]+\\.xml"))names.add(n);}names.sort((x,y)->Integer.compare(slideIndex(x),slideIndex(y)));fallbackPptxWarning=true;if(names.size()>MAX_SLIDES)throw new Exception("Превышен лимит слайдов (1000)");}
            for(String n:names){ZipEntry e=z.getEntry(n);if(e==null)throw new Exception("Отсутствует XML слайда PPTX");byte[] data;try(InputStream in=z.getInputStream(e)){data=capped(in,(int)(MAX_BYTES-total));}total+=data.length;String text=pptxParagraphText(data);totalText+=text.length();if(totalText>MAX_TEXT)throw new Exception("Превышен лимит текста PPTX");slides.put(slide(text));}
        }JSONObject result=new JSONObject(); result.put("slides",slides); result.put("fallbackOrder",fallbackPptxWarning); return result;
    }
    private static String pptxParagraphText(byte[] data) throws Exception {
        XmlPullParser p = parser(data);
        StringBuilder all = new StringBuilder();
        StringBuilder paragraph = null;
        int event, depth = 0, count = 0, paraDepth = -1, textDepth = -1;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (++count > MAX_XML_EVENTS) throw new Exception("Превышен лимит XML-событий");
            if (event == XmlPullParser.START_TAG) {
                if (++depth > MAX_DEPTH) throw new Exception("Превышена вложенность XML");
                if (paragraph == null && "p".equals(p.getName()) && isDrawingNamespace(p.getNamespace())) {
                    paragraph = new StringBuilder(); paraDepth = depth;
                } else if (paragraph != null && "t".equals(p.getName()) && isDrawingNamespace(p.getNamespace())) textDepth = depth;
                else if (paragraph != null && "br".equals(p.getName()) && isDrawingNamespace(p.getNamespace())) appendBounded(paragraph, "\n");
            } else if (event == XmlPullParser.TEXT && paragraph != null && textDepth >= 0) {
                appendBounded(paragraph, p.getText());
            } else if (event == XmlPullParser.END_TAG) {
                if (depth == textDepth) textDepth = -1;
                if (paragraph != null && depth == paraDepth && "p".equals(p.getName()) && isDrawingNamespace(p.getNamespace())) {
                    if (all.length() > 0) appendBounded(all, "\n");
                    appendBounded(all, paragraph.toString()); paragraph = null; paraDepth = -1;
                }
                depth--;
            }
        }
        return all.toString();
    }
    private static boolean isDrawingNamespace(String ns){return "http://schemas.openxmlformats.org/drawingml/2006/main".equals(ns)||"http://purl.oclc.org/ooxml/drawingml/main".equals(ns);}
    private static int slideIndex(String name){int a=name.lastIndexOf("slide")+5,b=name.lastIndexOf('.');try{return Integer.parseInt(name.substring(a,b));}catch(Exception e){return Integer.MAX_VALUE;}}
    private static String resolveTarget(String target)throws Exception{if(target.startsWith("/")||target.indexOf('\\')>=0||target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*"))throw new Exception("Небезопасный путь слайда PPTX");java.util.ArrayDeque<String> parts=new java.util.ArrayDeque<>();for(String part:("ppt/"+target).split("/")){if(part.isEmpty()||".".equals(part))continue;if("..".equals(part)){if(parts.isEmpty())throw new Exception("Путь слайда выходит за пределы архива");parts.removeLast();}else parts.addLast(part);}StringBuilder b=new StringBuilder();for(String part:parts){if(b.length()>0)b.append('/');b.append(part);}String r=b.toString();if(!r.startsWith("ppt/"))throw new Exception("Небезопасный путь слайда PPTX");return r;}
    private static JSONObject slide(String text)throws Exception{JSONObject o=new JSONObject();String t=text.trim();int ix=t.indexOf('\n');o.put("title",ix<0?t:t.substring(0,ix));o.put("text",ix<0?"":t.substring(ix+1));return o;}

    private static JSONArray parseOdt(byte[] data)throws Exception {
        JSONArray result=new JSONArray();XmlPullParser p=parser(data);
        int event,events=0,paraDepth=-1,skipDepth=-1,noteDepth=-1,noteBodyParagraphs=0;
        long totalText=0;StringBuilder para=null;
        while((event=p.next())!=XmlPullParser.END_DOCUMENT){
            if(++events>MAX_XML_EVENTS)throw new Exception("Превышен лимит XML-событий");
            int depth=p.getDepth();String n=p.getName(),ns=p.getNamespace();
            if(event==XmlPullParser.START_TAG){
                if(depth>MAX_DEPTH)throw new Exception("Превышена вложенность XML");
                if(skipDepth>=0)continue;
                if(ODF_OFFICE.equals(ns)&&"annotation".equals(n)){skipDepth=depth;continue;}
                if(!ODF_TEXT.equals(ns))continue;
                if(("p".equals(n)||"h".equals(n))&&para==null){para=new StringBuilder();paraDepth=depth;}
                else if(para!=null){
                    if("note".equals(n)){if(noteDepth>=0)throw new Exception("Вложенные сноски ODT не поддерживаются");noteDepth=depth;noteBodyParagraphs=0;appendBounded(para," [");}
                    else if("note-body".equals(n)&&noteDepth>=0)appendBounded(para,": ");
                    else if(("p".equals(n)||"h".equals(n))&&noteDepth>=0){if(noteBodyParagraphs++>0)appendBounded(para,"; ");}
                    else if("line-break".equals(n))appendBounded(para,"\n");
                    else if("tab".equals(n))appendBounded(para,"\t");
                    else if("s".equals(n))appendOdfSpaces(para,p);
                }
            }else if(event==XmlPullParser.TEXT){
                if(skipDepth<0&&para!=null)appendBounded(para,p.getText());
            }else if(event==XmlPullParser.END_TAG){
                if(skipDepth>=0){if(depth==skipDepth)skipDepth=-1;continue;}
                if(depth==noteDepth&&ODF_TEXT.equals(ns)&&"note".equals(n)){appendBounded(para,"]");noteDepth=-1;}
                if(depth==paraDepth&&ODF_TEXT.equals(ns)&&("p".equals(n)||"h".equals(n))){
                    totalText+=para.length();if(totalText>MAX_TEXT)throw new Exception("Превышен лимит текста ODT");
                    if(result.length()>=MAX_ROWS)throw new Exception("Превышен лимит абзацев ODT");
                    JSONObject paragraph=new JSONObject();paragraph.put("text",para.toString());result.put(paragraph);para=null;paraDepth=-1;
                }
            }
        }
        return result;
    }
    private static void appendBounded(StringBuilder b,String text)throws Exception{if(text.length()>MAX_TEXT-b.length())throw new Exception("Превышен лимит текста");b.append(text);}
    private static final String ODF_TABLE="urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String ODF_OFFICE="urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String ODF_TEXT="urn:oasis:names:tc:opendocument:xmlns:text:1.0";

    private static JSONArray parseOds(byte[] data)throws Exception {
        JSONArray sheets=new JSONArray(),rows=null,row=null;
        JSONObject sheet=null;
        XmlPullParser p=parser(data);
        int ev,events=0,tableDepth=-1,rowDepth=-1,cellDepth=-1,paragraphDepth=-1,skipDepth=-1;
        int repeatRow=1,repeatCell=1,paragraphs=0;
        long logicalRow=0,logicalCol=0,totalCells=0,totalText=0,rowText=0;
        StringBuilder display=null;
        String cached="";
        boolean covered=false;
        while((ev=p.next())!=XmlPullParser.END_DOCUMENT){
            if(++events>3_000_000)throw new Exception("Превышен лимит XML-событий");
            int depth=p.getDepth();String n=p.getName(),ns=p.getNamespace();
            if(ev==XmlPullParser.START_TAG){
                if(depth>MAX_DEPTH)throw new Exception("Превышена вложенность XML");
                if(skipDepth>=0)continue;
                if(ODF_OFFICE.equals(ns)&&"annotation".equals(n)){skipDepth=depth;continue;}
                if(ODF_TABLE.equals(ns)&&"table".equals(n)){
                    if(sheet!=null)throw new Exception("Вложенные таблицы ODS пока не поддерживаются");
                    if(sheets.length()>=1000)throw new Exception("Превышен лимит листов");
                    sheet=new JSONObject();rows=new JSONArray();sheet.put("rows",rows);
                    String name=p.getAttributeValue(ODF_TABLE,"name");
                    sheet.put("name",name==null?"Лист"+(sheets.length()+1):name);
                    tableDepth=depth;logicalRow=0;
                }else if(sheet!=null&&ODF_TABLE.equals(ns)&&"table-row".equals(n)){
                    if(row!=null)throw new Exception("Некорректная вложенность строк ODS");
                    repeatRow=repeat(p,"number-rows-repeated",Integer.MAX_VALUE);
                    row=new JSONArray();rowDepth=depth;logicalCol=0;rowText=0;
                }else if(row!=null&&ODF_TABLE.equals(ns)&&("table-cell".equals(n)||"covered-table-cell".equals(n))){
                    if(cellDepth>=0||depth!=rowDepth+1)throw new Exception("Некорректная вложенность ячеек ODS");
                    repeatCell=repeat(p,"number-columns-repeated",Integer.MAX_VALUE);
                    cellDepth=depth;covered="covered-table-cell".equals(n);display=new StringBuilder();
                    paragraphDepth=-1;paragraphs=0;cached="";
                    if(!covered){
                        String type=p.getAttributeValue(ODF_OFFICE,"value-type");
                        String key="date".equals(type)?"date-value":"time".equals(type)?"time-value":"boolean".equals(type)?"boolean-value":"string".equals(type)?"string-value":"value";
                        String value=p.getAttributeValue(ODF_OFFICE,key);cached=value==null?"":value;
                    }
                }else if(display!=null&&!covered&&ODF_TEXT.equals(ns)){
                    if("p".equals(n)||"h".equals(n)){
                        if(paragraphDepth<0){if(paragraphs++>0)appendBounded(display,"\n");paragraphDepth=depth;}
                    }else if(paragraphDepth>=0&&"line-break".equals(n))appendBounded(display,"\n");
                    else if(paragraphDepth>=0&&"tab".equals(n))appendBounded(display,"\t");
                    else if(paragraphDepth>=0&&"s".equals(n))appendOdfSpaces(display,p);
                }
            }else if(ev==XmlPullParser.TEXT){
                if(skipDepth<0&&display!=null&&!covered&&paragraphDepth>=0)appendBounded(display,p.getText());
            }else if(ev==XmlPullParser.END_TAG){
                if(skipDepth>=0){if(depth==skipDepth)skipDepth=-1;continue;}
                if(depth==paragraphDepth)paragraphDepth=-1;
                if(cellDepth==depth&&ODF_TABLE.equals(ns)&&("table-cell".equals(n)||"covered-table-cell".equals(n))){
                    String value=covered?"":(paragraphs>0?display.toString():cached);
                    if(!value.isEmpty()){
                        long endCol=logicalCol+repeatCell,endRow=logicalRow+repeatRow;
                        if(endCol>MAX_COLS||endRow>MAX_ROWS)throw new Exception("Непустая ячейка ODS вне поддерживаемых координат (20 000 строк × 512 столбцов)");
                        // Count empty interior positions as cells, but never materialize an empty tail.
                        if(totalCells+endCol*repeatRow>MAX_CELLS)throw new Exception("Превышен лимит ячеек ODS (500 000)");
                        long addedText=(long)value.length()*repeatCell;
                        if(totalText+(rowText+addedText)*repeatRow>MAX_TEXT)throw new Exception("Превышен лимит текста ODS");
                        while(row.length()<logicalCol)row.put("");
                        for(int i=0;i<repeatCell;i++)row.put(value);
                        rowText+=addedText;
                    }
                    logicalCol=Math.min((long)MAX_COLS+1,logicalCol+repeatCell);
                    display=null;cached="";cellDepth=-1;paragraphDepth=-1;
                }else if(depth==rowDepth&&ODF_TABLE.equals(ns)&&"table-row".equals(n)){
                    if(row.length()>0){
                        long endRow=logicalRow+repeatRow;
                        if(endRow>MAX_ROWS)throw new Exception("Непустая строка ODS вне поддерживаемых координат");
                        long added=(long)row.length()*repeatRow;
                        if(totalCells+added>MAX_CELLS)throw new Exception("Превышен лимит ячеек ODS (500 000)");
                        while(rows.length()<logicalRow)rows.put(new JSONArray());
                        for(int i=0;i<repeatRow;i++){
                            JSONArray copy=new JSONArray();for(int c=0;c<row.length();c++)copy.put(row.optString(c,""));rows.put(copy);
                        }
                        totalCells+=added;totalText+=rowText*repeatRow;
                    }
                    logicalRow=Math.min((long)MAX_ROWS+1,logicalRow+repeatRow);
                    row=null;rowDepth=-1;
                }else if(depth==tableDepth&&ODF_TABLE.equals(ns)&&"table".equals(n)){
                    sheets.put(sheet);sheet=null;rows=null;tableDepth=-1;
                }
            }
        }
        return sheets;
    }
    private static void appendOdfSpaces(StringBuilder display,XmlPullParser p)throws Exception{
        String count=p.getAttributeValue(ODF_TEXT,"c");int spaces=1;
        try{if(count!=null)spaces=Integer.parseInt(count);}catch(NumberFormatException e){throw new Exception("Некорректное число пробелов ODF");}
        if(spaces<1||spaces>MAX_TEXT-display.length())throw new Exception("Превышен лимит текста ODF");
        for(int i=0;i<spaces;i++)display.append(' ');
    }
    private static int repeat(XmlPullParser p,String local,int max)throws Exception{String v=p.getAttributeValue("urn:oasis:names:tc:opendocument:xmlns:table:1.0",local);if(v==null)return 1;try{long x=Long.parseLong(v);if(x<1)throw new Exception("Invalid repeated row/cell count");return (int)Math.min(x,max);}catch(NumberFormatException e){throw new Exception("Invalid repeated row/cell count");}}
    private static JSONArray parseOdp(byte[] data)throws Exception{JSONArray out=new JSONArray();XmlPullParser p=parser(data);int e,d=0,count=0;boolean page=false,inPara=false;StringBuilder para=null,pageText=null;while((e=p.next())!=XmlPullParser.END_DOCUMENT){if(++count>MAX_XML_EVENTS)throw new Exception("Превышен лимит XML-событий");if(e==XmlPullParser.START_TAG){if(++d>MAX_DEPTH)throw new Exception("Превышена вложенность XML");String n=p.getName();if("page".equals(n)){page=true;pageText=new StringBuilder();}else if(page&&"p".equals(n)){inPara=true;para=new StringBuilder();}else if(inPara&&"line-break".equals(n))para.append('\n');}else if(e==XmlPullParser.TEXT&&inPara)para.append(p.getText());else if(e==XmlPullParser.END_TAG){String n=p.getName();if("p".equals(n)&&inPara){if(pageText.length()>0)pageText.append('\n');pageText.append(para);inPara=false;}else if("page".equals(n)&&page){out.put(slide(pageText.toString()));if(out.length()>MAX_SLIDES)throw new Exception("Превышен лимит слайдов (1000)");page=false;}d--;}}return out;}
    private static JSONArray xmlText(byte[] data,String tag)throws Exception{JSONArray a=new JSONArray();XmlPullParser p=parser(data);int e,d=0,count=0;StringBuilder s=null;while((e=p.next())!=XmlPullParser.END_DOCUMENT){if(++count>MAX_XML_EVENTS)throw new Exception("XML event limit exceeded");if(e==XmlPullParser.START_TAG){if(++d>MAX_DEPTH)throw new Exception("XML nesting limit exceeded");if(tag.equals(p.getName()))s=new StringBuilder();}else if(e==XmlPullParser.TEXT&&s!=null){s.append(p.getText());if(s.length()>MAX_TEXT)throw new Exception("Превышен лимит текста");}else if(e==XmlPullParser.END_TAG){if(tag.equals(p.getName())&&s!=null){a.put(s.toString());s=null;if(a.length()>MAX_ROWS)throw new Exception("Text block limit exceeded");}d--;}}return a;}
    private static XmlPullParser parser(byte[] data)throws Exception{String raw=new String(data,StandardCharsets.ISO_8859_1);if(Pattern.compile("(?is)<!\\s*(DOCTYPE|ENTITY)\\b").matcher(raw).find())throw new Exception("DOCTYPE и ENTITY запрещены");XmlPullParserFactory f=XmlPullParserFactory.newInstance();f.setNamespaceAware(true);XmlPullParser p=f.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);p.setInput(new ByteArrayInputStream(data),"UTF-8");return p;}

    private static JSONObject delimited(File file,String ext)throws Exception {byte[] b;try(InputStream in=new FileInputStream(file)){b=capped(in,MAX_BYTES);}String s=decode(b);if(s.startsWith("\uFEFF"))s=s.substring(1);char delim="tsv".equals(ext)?'\t':detect(s);JSONArray rows=parseDelimited(s,delim);JSONObject sh=new JSONObject();sh.put("name","Sheet1");sh.put("rows",rows);JSONArray ss=new JSONArray();ss.put(sh);JSONObject o=new JSONObject();o.put("ok",true);o.put("sheets",ss);o.put("warning",WARN_TEXT);return o;}
    private static String decode(byte[] b)throws Exception{int off=(b.length>=3&&(b[0]&255)==239&&(b[1]&255)==187&&(b[2]&255)==191)?3:0;try{return Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b,off,b.length-off)).toString();}catch(CharacterCodingException e){try{return Charset.forName("windows-1251").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}catch(CharacterCodingException x){throw new Exception("Input is neither valid UTF-8 nor Windows-1251",x);}}}
    private static char detect(String s){int comma=0,semi=0;boolean q=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'){if(q&&i+1<s.length()&&s.charAt(i+1)=='"'){i++;continue;}q=!q;}else if(!q&&c==',' )comma++;else if(!q&&c==';')semi++;}return semi>comma?';':',';}
    private static JSONArray parseDelimited(String s,char d)throws Exception{JSONArray rows=new JSONArray();JSONArray row=new JSONArray();StringBuilder c=new StringBuilder();boolean q=false,after=false;int cells=0;for(int i=0;i<s.length();i++){char x=s.charAt(i);if(q){if(x=='"'){if(i+1<s.length()&&s.charAt(i+1)=='"'){c.append('"');i++;}else{q=false;after=true;}}else c.append(x);}else if(x==d){if(row.length()>=MAX_COLS)throw new Exception("Превышен лимит столбцов");row.put(c.toString());c.setLength(0);after=false;if(++cells>MAX_CELLS)throw new Exception("Cell limit exceeded");}else if(x=='\r'||x=='\n'){if(x=='\r'&&i+1<s.length()&&s.charAt(i+1)=='\n')i++;if(row.length()>=MAX_COLS)throw new Exception("Превышен лимит столбцов");row.put(c.toString());c.setLength(0);rows.put(row);row=new JSONArray();after=false;if(++cells>MAX_CELLS)throw new Exception("Cell limit exceeded");if(rows.length()>MAX_ROWS)throw new Exception("Row limit exceeded");}else if(x=='"'&&c.length()==0&&!after)q=true;else{if(after)throw new Exception("Invalid character after quoted CSV field");c.append(x);}}if(q)throw new Exception("Unterminated quoted CSV field");if(c.length()>0||row.length()>0||s.endsWith(String.valueOf(d))){if(row.length()>=MAX_COLS)throw new Exception("Превышен лимит столбцов");row.put(c.toString());rows.put(row);if(++cells>MAX_CELLS)throw new Exception("Превышен лимит ячеек");}if(rows.length()>MAX_ROWS)throw new Exception("Превышен лимит строк");return rows;}
    private static void saveDelimited(File f,String ext,JSONObject snap)throws Exception {JSONArray sheets=snap.optJSONArray("sheets");if(sheets==null||sheets.length()==0)throw new Exception("Snapshot has no sheets");JSONObject sh=sheets.optJSONObject(0);JSONArray rows=sh==null?null:sh.optJSONArray("rows");if(rows==null)throw new Exception("First sheet has no rows");char d="tsv".equals(ext)?'\t':',';try(OutputStream out=new FileOutputStream(f)){for(int r=0;r<rows.length();r++){JSONArray row=rows.optJSONArray(r);if(row!=null)for(int c=0;c<row.length();c++){if(c>0)out.write(d);String v=row.optString(c,"");boolean quote=v.indexOf(d)>=0||v.indexOf('"')>=0||v.indexOf('\r')>=0||v.indexOf('\n')>=0;if(quote)out.write('"');String escaped=quote?v.replace("\"","\"\""):v;out.write(escaped.getBytes(StandardCharsets.UTF_8));if(quote)out.write('"');}out.write('\r');out.write('\n');}}}


    private static String makeOdt(JSONObject s)throws Exception{JSONArray a=s.optJSONArray("paragraphs");if(a==null)throw new Exception("Snapshot has no paragraphs");StringBuilder b=new StringBuilder("<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" office:version=\"1.2\"><office:body><office:text>");for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);String text=p==null?a.optString(i,""):p.optString("text","");b.append("<text:p>").append(xml(text)).append("</text:p>");if(i>MAX_ROWS)throw new Exception("Paragraph limit exceeded");}return b.append("</office:text></office:body></office:document-content>").toString();}
    private static String makeOds(JSONObject s)throws Exception{JSONArray a=s.optJSONArray("sheets");if(a==null)throw new Exception("Snapshot has no sheets");StringBuilder b=new StringBuilder("<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" office:version=\"1.2\"><office:body><office:spreadsheet>");for(int si=0;si<a.length();si++){JSONObject sh=a.optJSONObject(si);if(sh==null)continue;JSONArray rows=sh.optJSONArray("rows");if(rows==null)continue;if(si>=1000)throw new Exception("Sheet limit exceeded");b.append("<table:table table:name=\"").append(xml(sh.optString("name","Sheet"+(si+1)))).append("\">");for(int r=0;r<rows.length();r++){JSONArray row=rows.optJSONArray(r);b.append("<table:table-row>");if(row!=null)for(int c=0;c<row.length();c++){if(c>=MAX_COLS)throw new Exception("Column limit exceeded");String v=row.optString(c,"");b.append("<table:table-cell office:value-type=\"string\"><text:p>").append(xml(v)).append("</text:p></table:table-cell>");}b.append("</table:table-row>");if(r>=MAX_ROWS)throw new Exception("Row limit exceeded");}b.append("</table:table>");}return b.append("</office:spreadsheet></office:body></office:document-content>").toString();}
    private static String manifest(String ext,String mime){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.2\"><manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\""+mime+"\"/><manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/></manifest:manifest>";}
    private static String xml(String s){StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '&':b.append("&amp;");break;case '<':b.append("&lt;");break;case '>':b.append("&gt;");break;case '\"':b.append("&quot;");break;case '\'':b.append("&apos;");break;default:if(c=='\t'||c=='\n'||c=='\r'||c>=0x20)b.append(c);}}return b.toString();}
}
