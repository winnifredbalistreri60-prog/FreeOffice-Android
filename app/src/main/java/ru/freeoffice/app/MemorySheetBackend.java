package ru.freeoffice.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Locale;

/** Read-only windows over already imported ODS/CSV/TSV data; never an export source. */
final class MemorySheetBackend implements SheetViewer.Backend {
 private final JSONArray sheets;
 MemorySheetBackend(JSONArray sheets) { this.sheets=sheets; }
 private static JSONObject ok() throws Exception { return new JSONObject().put("ok",true); }
 JSONObject metadata() throws Exception {
  JSONArray out=new JSONArray();
  for(int i=0;i<sheets.length();i++) {
   JSONObject sh=sheets.optJSONObject(i); JSONArray rows=sh==null?null:sh.optJSONArray("rows");
   int cols=1; if(rows!=null)for(int r=0;r<rows.length();r++){JSONArray row=rows.optJSONArray(r);if(row!=null)cols=Math.max(cols,row.length());}
   out.put(new JSONObject().put("name",sh==null?"Лист":sh.optString("name","Лист")).put("rows",rows==null?1:Math.max(1,rows.length())).put("cols",cols));
  }
  return ok().put("session","memory").put("sheets",out).put("warning","Просмотр импортированных значений; оформление и пересчёт формул не поддерживаются.");
 }
 private static String cut(String s,int max) { if(s.length()<=max)return s;int end=max;if(end>0&&Character.isHighSurrogate(s.charAt(end-1)))end--;return s.substring(0,end); }
 @Override public JSONObject call(JSONObject request) throws Exception {
  int index=request.optInt("sheet",0);JSONObject sh=sheets.optJSONObject(index);
  if(sh==null)throw new IOException("Лист отсутствует");JSONArray data=sh.optJSONArray("rows");if(data==null)data=new JSONArray();
  int totalRows=Math.max(1,data.length()),totalCols=1;
  for(int r=0;r<data.length();r++){JSONArray row=data.optJSONArray(r);if(row!=null)totalCols=Math.max(totalCols,row.length());}
  String op=request.optString("op");int row=Math.max(0,request.optInt("row")),col=Math.max(0,request.optInt("col"));
  if(op.equals("sheet_view_page")) {
   row=Math.min(row,totalRows-1);col=Math.min(col,totalCols-1);
   int nr=Math.min(60,Math.max(1,request.optInt("rows",40))),nc=Math.min(16,Math.max(1,request.optInt("cols",10)));
   JSONArray result=new JSONArray();boolean truncated=false;
   for(int r=row;r<Math.min(totalRows,row+nr);r++) {JSONArray cells=new JSONArray(),source=data.optJSONArray(r);for(int c=col;c<Math.min(totalCols,col+nc);c++){String value=source==null?"":source.optString(c,"");truncated|=value.length()>4096;cells.put(cut(value,4096));}result.put(cells);}
   return ok().put("row",row).put("col",col).put("rows",result).put("totalRows",totalRows).put("totalCols",totalCols).put("truncated",truncated);
  }
  if(op.equals("sheet_view_cell")) {
   JSONArray source=data.optJSONArray(row);String value=source==null?"":source.optString(col,"");
   return ok().put("text",cut(value,100000)).put("truncated",value.length()>100000);
  }
  if(op.equals("sheet_view_find")) {
   String query=request.optString("query").toLowerCase(Locale.ROOT);if(query.isEmpty()||query.length()>256)throw new IOException("Введите от 1 до 256 символов");
   int afterRow=request.optInt("afterRow",-1),afterCol=request.optInt("afterCol",-1);
   for(int r=Math.max(0,afterRow);r<data.length();r++){JSONArray cells=data.optJSONArray(r);if(cells==null)continue;for(int c=(r==afterRow?afterCol+1:0);c<cells.length();c++)if(cells.optString(c,"").toLowerCase(Locale.ROOT).contains(query))return ok().put("found",true).put("row",r).put("col",c);}
   return ok().put("found",false);
  }
  throw new IOException("Неизвестная команда просмотра");
 }
}
