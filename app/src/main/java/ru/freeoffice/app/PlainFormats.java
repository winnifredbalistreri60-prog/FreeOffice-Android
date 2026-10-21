package ru.freeoffice.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Strict, bounded import/export helpers for plain text and delimited text. */
public final class PlainFormats {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_CHARS = 2_000_000;
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_COLS = 512;
    private static final int MAX_CELLS = 500_000;

    private PlainFormats() {}

    public static JSONObject open(File file, String extension, String encoding, String delimiter) throws Exception {
        String ext = norm(extension);
        if (!ext.equals("txt") && !ext.equals("csv") && !ext.equals("tsv")) throw error("Неподдерживаемый формат файла");
        if (file == null || !file.isFile()) throw error("Файл не найден");
        byte[] bytes = readBounded(file);
        Decoded decoded = decode(bytes, encoding);
        String text = decoded.text;
        if (text.length() > MAX_CHARS) throw error("Текст превышает лимит 2 000 000 символов");
        String newline = detectNewline(text);
        JSONObject result = new JSONObject().put("ok", true).put("encoding", decoded.encoding)
                .put("bom", decoded.bom).put("newline", newline).put("warning", decoded.warning);
        if (ext.equals("txt")) {
            result.put("text", text).put("delimiter", delimiter == null ? "" : delimiter);
            return result;
        }
        // T8: binary/NUL input is rejected after decoding, so UTF-16 text is unaffected.
        rejectNul(text);
        // T9: optional leading "sep=<c>" directive (Excel convention) is not a data row.
        // decode() already removes exactly one byte-order marker. A second U+FEFF is cell data, not another BOM.
        Character directive = null;
        int lineOffset = 0;
        if (text.startsWith("sep=") && text.length() >= 5) {
            char c = text.charAt(4);
            int end = 5;
            boolean lineEnd = end == text.length() || text.charAt(end) == '\n' || text.charAt(end) == '\r';
            if (lineEnd && c != '"' && c != '\r' && c != '\n') {
                directive = c;
                if (end < text.length() && text.charAt(end) == '\r') end++;
                if (end < text.length() && text.charAt(end) == '\n') end++;
                text = text.substring(end);
                lineOffset = 1;
            }
        }
        String requestedDelimiter = norm(delimiter == null ? "auto" : delimiter);
        char sep = directive != null && requestedDelimiter.equals("auto") ? directive : resolveDelimiter(text, delimiter, ext);
        List<List<String>> parsed = parse(text, sep, lineOffset);
        // T23: rows of different width are reported, data is left unchanged.
        String ragged = raggedWarning(parsed);
        if (directive != null) result.put("sepDirective", directive == '\t' ? "tab" : String.valueOf(directive));
        if (!ragged.isEmpty()) {
            String prev = decoded.warning == null ? "" : decoded.warning;
            result.put("warning", prev.isEmpty() ? ragged : prev + " " + ragged);
        }
        JSONArray rows = new JSONArray();
        for (List<String> row : parsed) {
            JSONArray cells = new JSONArray();
            for (String cell : row) cells.put(cell);
            rows.put(cells);
        }
        JSONArray sheets = new JSONArray().put(new JSONObject().put("name", "Лист1").put("rows", rows));
        result.put("sheets", sheets).put("delimiter", sep == '\t' ? "tab" : String.valueOf(sep));
        return result;
    }

    public static void saveText(File target, String text, String encoding, String newline, boolean bom) throws Exception {
        if (text == null) text = "";
        if (text.length() > MAX_CHARS) throw error("Текст превышает лимит 2 000 000 символов");
        String nl = newlineString(newline);
        String normalized = normalizeNewlines(text, nl);
        Charset charset = charsetForSave(encoding);
        write(target, normalized, charset, bom);
    }

    public static void saveDelimited(File target, JSONArray sheets, int sheetIndex, String extension,
                                     String encoding, String delimiter, String newline, boolean bom,
                                     boolean neutralizeFormulas) throws Exception {
        String ext = norm(extension);
        if (!ext.equals("csv") && !ext.equals("tsv")) throw error("Для экспорта таблицы укажите CSV или TSV");
        char sep = ext.equals("tsv") ? '\t' : resolveOutputDelimiter(delimiter, ext);
        String nl = newlineString(newline);
        if (sheets == null || sheetIndex < 0 || sheetIndex >= sheets.length()) throw error("Лист не найден");
        JSONObject sheet = sheets.optJSONObject(sheetIndex);
        if (sheet == null) throw error("Некорректные данные листа");
        JSONArray rows = sheet.optJSONArray("rows");
        if (rows == null) throw error("В листе отсутствуют строки");
        if (rows.length() > MAX_ROWS) throw error("Экспорт превышает лимит 20 000 строк");
        StringBuilder out = new StringBuilder();
        long cellsCount = 0;
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.optJSONArray(r);
            if (row == null) throw error("Некорректная строка " + (r + 1));
            if (row.length() > MAX_COLS) throw error("Строка превышает лимит 512 столбцов");
            int columns = Math.max(1, row.length()); // JSON zero-column rows mean one empty field.
            cellsCount += columns;
            if (cellsCount > MAX_CELLS) throw error("Экспорт превышает лимит 500 000 ячеек");
            for (int c = 0; c < columns; c++) {
                if (c > 0) out.append(sep);
                Object value = row.length() == 0 ? "" : row.opt(c);
                String field = value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
                if (neutralizeFormulas && hasFormulaPrefix(field)) field = "'" + field;
                appendField(out, field, sep, nl, columns == 1 && field.isEmpty());
                if (out.length() > MAX_CHARS) throw error("Экспорт превышает лимит 2 000 000 символов");
            }
            if (r + 1 < rows.length()) out.append(nl);
        }
        write(target, out.toString(), charsetForSave(encoding), bom);
    }

    private static byte[] readBounded(File file) throws Exception {
        if (file.length() > MAX_BYTES) throw error("Файл превышает лимит 32 МБ");
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n; int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) throw error("Файл превышает лимит 32 МБ");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static final class Decoded {
        final String text, encoding, warning; final boolean bom;
        Decoded(String t, String e, String w, boolean b) { text=t; encoding=e; warning=w; bom=b; }
    }

    private static Decoded decode(byte[] bytes, String requested) throws Exception {
        String req = norm(requested == null ? "auto" : requested);
        int offset = 0; String bomCharset = null;
        if (starts(bytes, 0xEF, 0xBB, 0xBF)) { bomCharset="UTF-8"; offset=3; }
        else if (starts(bytes, 0xFF, 0xFE)) { bomCharset="UTF-16LE"; offset=2; }
        else if (starts(bytes, 0xFE, 0xFF)) { bomCharset="UTF-16BE"; offset=2; }
        String chosen;
        if (!req.equals("auto")) {
            chosen = canonicalEncoding(requested);
            if (bomCharset != null && !chosen.equals(bomCharset)) throw error("Кодировка BOM не совпадает с выбранной кодировкой");
        } else if (bomCharset != null) chosen=bomCharset;
        else chosen="UTF-8";
        try {
            String s = decodeStrict(bytes, offset, chosen);
            return new Decoded(s, chosen, "", bomCharset != null);
        } catch (CharacterCodingException ex) {
            if (!req.equals("auto") || bomCharset != null) throw error("Файл содержит недопустимую последовательность для кодировки " + chosen);
            try {
                String s=decodeStrict(bytes, 0, "windows-1251");
                return new Decoded(s, "windows-1251", "UTF-8 не распознан; использована кодировка Windows-1251.", false);
            } catch (CharacterCodingException impossible) { throw error("Не удалось декодировать файл"); }
        }
    }

    private static String decodeStrict(byte[] b, int off, String name) throws CharacterCodingException {
        CharsetDecoder d=Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        return d.decode(ByteBuffer.wrap(b, off, b.length-off)).toString();
    }

    private enum FieldState { START, UNQUOTED, QUOTED, AFTER_QUOTE }

    /**
     * RFC 4180-style logical records, also used with semicolon and tab separators.
     * CRLF, LF and CR end records only outside quotes; inside quotes they remain
     * exact field content. Whitespace is significant and values are always strings.
     */
    private static void rejectNul(String text) throws Exception {
        int i = text.indexOf('\0');
        if (i < 0) return;
        int[] lc = lineColumn(text, i, 0);
        throw error("Файл похож на двоичный: содержит нулевой символ (NUL), строка " + lc[0] + ", столбец " + lc[1] + ". Откройте текстовый CSV/TSV или выберите правильную кодировку.");
    }

    /** 1-based physical line and column of position pos; CRLF, CR and LF each end a line. */
    private static int[] lineColumn(String text, int pos, int lineOffset) {
        int line = 1 + lineOffset, col = 1;
        for (int i = 0; i < pos && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') { if (i + 1 < pos && text.charAt(i + 1) == '\n') i++; line++; col = 1; }
            else if (c == '\n') { line++; col = 1; }
            else col++;
        }
        return new int[]{line, col};
    }

    private static Exception quoteError(String message, String text, int pos, int lineOffset) {
        int[] lc = lineColumn(text, pos, lineOffset);
        return error(message + " (строка " + lc[0] + ", столбец " + lc[1] + ")");
    }

    private static String raggedWarning(List<List<String>> rows) {
        if (rows.size() < 2) return "";
        int width = rows.get(0).size(), count = 0, first = -1;
        for (int r = 1; r < rows.size(); r++) {
            if (rows.get(r).size() != width) { count++; if (first < 0) first = r + 1; }
        }
        if (count == 0) return "";
        return "Строки разной длины: " + count + " (первая — запись " + first + "; в первой записи столбцов: " + width + "). Данные не изменены, недостающие ячейки показаны пустыми.";
    }

    private static List<List<String>> parse(String text, char sep, int lineOffset) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        FieldState state = FieldState.START;
        int cells = 0;
        int quoteStart = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean eof = i == text.length();
            char ch = eof ? '\0' : text.charAt(i);

            if (state == FieldState.QUOTED) {
                if (eof) throw quoteError("Незакрытая кавычка в файле", text, quoteStart, lineOffset);
                if (ch == '"') state = FieldState.AFTER_QUOTE;
                else field.append(ch);
                continue;
            }
            if (state == FieldState.AFTER_QUOTE && !eof && ch == '"') {
                // A doubled quote is one literal quote, not a field boundary.
                field.append('"');
                state = FieldState.QUOTED;
                continue;
            }

            boolean endRecord = !eof && (ch == '\r' || ch == '\n');
            if (eof || ch == sep || endRecord) {
                // Empty input retains one empty cell. A terminal newline does
                // not invent a record; a trailing separator DOES add a field.
                if (eof && i > 0 && row.isEmpty() && state == FieldState.START) break;
                row.add(field.toString());
                checkCellLimits(row.size(), ++cells);
                // Every boundary clears the buffer, including record endings.
                // Otherwise the last cell leaks into the next record's first.
                field.setLength(0);
                state = FieldState.START;
                if (eof || endRecord) {
                    addRow(rows, row);
                    row = new ArrayList<>();
                    if (!eof && ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
                if (eof) break;
                continue;
            }
            if (state == FieldState.AFTER_QUOTE) throw quoteError("Лишние символы после закрывающей кавычки", text, i, lineOffset);
            if (ch == '"') {
                if (state != FieldState.START) throw quoteError("Кавычка внутри некавыченного поля", text, i, lineOffset);
                state = FieldState.QUOTED;
                quoteStart = i;
            } else {
                field.append(ch);
                state = FieldState.UNQUOTED;
            }
        }
        return rows;
    }

    private static void checkCellLimits(int columns, int total) throws Exception {
        if (columns > MAX_COLS) throw error("Строка превышает лимит 512 столбцов");
        if (total > MAX_CELLS) throw error("Таблица превышает лимит 500 000 ячеек");
    }
    private static void addRow(List<List<String>> rows, List<String> row) throws Exception {
        if (rows.size()+1>MAX_ROWS) throw error("Таблица превышает лимит 20 000 строк");
        rows.add(row);
    }

    private static char resolveDelimiter(String text, String requested, String ext) throws Exception {
        String d=norm(requested == null ? "auto" : requested);
        if (!d.equals("auto")) return parseDelimiter(d);
        int[] counts=new int[3]; int checked=0, fields=1; boolean quote=false, fieldStart=true; char[] candidates={',',';','\t'};
        for(int i=0;i<text.length()&&checked<20;i++) {
            char c=text.charAt(i);
            if(quote) { if(c=='"'&&i+1<text.length()&&text.charAt(i+1)=='"')i++; else if(c=='"')quote=false; continue; }
            if(fieldStart&&c=='"'){quote=true;fieldStart=false;continue;}
            if(c=='\r'||c=='\n'){ if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++; checked++; fields=1; fieldStart=true; continue; }
            if(c==',')counts[0]++; if(c==';')counts[1]++; if(c=='\t')counts[2]++;
            if(c==','||c==';'||c=='\t'){fields++;fieldStart=true;}else fieldStart=false;
        }
        int best=-1, bestCount=0; for(int j=0;j<3;j++)if(counts[j]>bestCount){best=j;bestCount=counts[j];}
        if(best<0) return ext.equals("tsv")?'\t':',';
        return candidates[best];
    }
    private static char parseDelimiter(String d) throws Exception {
        if(d.equals("tab")||d.equals("\\t"))return '\t';
        if(d.equals(","))return ','; if(d.equals(";"))return ';';
        throw error("Разделитель должен быть auto, запятой, точкой с запятой или tab");
    }
    private static char resolveOutputDelimiter(String d,String ext) throws Exception {
        if(d==null||norm(d).equals("auto")) return ext.equals("tsv")?'\t':',';
        String value = d; // A one-character separator can itself be whitespace; never trim data.
        if (value.length() == 1 && value.charAt(0) != '"' && value.charAt(0) != '\r' && value.charAt(0) != '\n' && value.charAt(0) != '\0') return value.charAt(0);
        return parseDelimiter(norm(d));
    }
    private static void appendField(StringBuilder out,String field,char sep,String nl,boolean forceEmptyQuote) {
        boolean quote=forceEmptyQuote||field.indexOf(sep)>=0||field.indexOf('"')>=0||field.indexOf('\r')>=0||field.indexOf('\n')>=0;
        if(quote)out.append('"');
        for(int i=0;i<field.length();i++){char c=field.charAt(i);if(c=='"')out.append("\"\"");else out.append(c);}
        if(quote)out.append('"');
    }
    private static boolean hasFormulaPrefix(String s) {
        int i=0; while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;
        return i<s.length()&&(s.charAt(i)=='='||s.charAt(i)=='+'||s.charAt(i)=='-'||s.charAt(i)=='−'||s.charAt(i)=='@');
    }
    private static void write(File target,String text,Charset charset,boolean bom) throws Exception {
        if(target==null)throw error("Не указан файл для сохранения");
        if (bom && charset.name().equalsIgnoreCase("windows-1251")) throw error("BOM не поддерживается для Windows-1251; отключите параметр BOM или выберите Unicode-кодировку");
        CharsetEncoder enc=charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer data;
        try { data=enc.encode(CharBuffer.wrap(text)); } catch(CharacterCodingException e){throw error("Текст содержит символы, недоступные в кодировке " + charset.name());}
        try(FileOutputStream out=new FileOutputStream(target)) {
            if(bom) { if(charset.equals(StandardCharsets.UTF_8))out.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF}); else if(charset.name().equalsIgnoreCase("UTF-16LE"))out.write(new byte[]{(byte)0xFF,(byte)0xFE}); else if(charset.name().equalsIgnoreCase("UTF-16BE"))out.write(new byte[]{(byte)0xFE,(byte)0xFF}); }
            byte[] bytes=new byte[data.remaining()];data.get(bytes);out.write(bytes);out.flush();
        }
    }
    private static Charset charsetForSave(String encoding) throws Exception { return Charset.forName(canonicalEncoding(encoding)); }
    private static String canonicalEncoding(String value) throws Exception {
        String n=norm(value==null?"auto":value);
        if(n.equals("auto"))return "UTF-8";
        if(n.equals("utf-8")||n.equals("utf8"))return "UTF-8";
        if(n.equals("windows-1251")||n.equals("cp1251"))return "windows-1251";
        if(n.equals("utf-16le"))return "UTF-16LE"; if(n.equals("utf-16be"))return "UTF-16BE";
        throw error("Неподдерживаемая кодировка");
    }
    private static String detectNewline(String s) { for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\r')return i+1<s.length()&&s.charAt(i+1)=='\n'?"CRLF":"CR";if(c=='\n')return "LF";}return "LF"; }
    private static String newlineString(String s) throws Exception { if("CRLF".equalsIgnoreCase(s))return "\r\n";if("LF".equalsIgnoreCase(s))return "\n";if("CR".equalsIgnoreCase(s))return "\r";throw error("Перевод строки должен быть CRLF, LF или CR"); }
    private static String normalizeNewlines(String s,String nl) { StringBuilder b=new StringBuilder(s.length());for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\r'){if(i+1<s.length()&&s.charAt(i+1)=='\n')i++;b.append(nl);}else if(c=='\n')b.append(nl);else b.append(c);}return b.toString(); }
    private static boolean starts(byte[] b,int... values){if(b.length<values.length)return false;for(int i=0;i<values.length;i++)if((b[i]&255)!=values[i])return false;return true;}
    private static String norm(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT);}
    private static Exception error(String message){return new Exception(message);}
}
