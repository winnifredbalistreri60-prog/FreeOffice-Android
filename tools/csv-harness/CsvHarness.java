package ru.freeoffice.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Standalone JVM harness for PlainFormats CSV/TSV (0.2.4). Usage: CsvHarness <samplesDir> <fixturesDir> */
public final class CsvHarness {
    static int pass = 0, fail = 0;
    static File tmp;

    static void check(String id, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + id + (detail.isEmpty() ? "" : " — " + detail));
    }
    static File write(String name, byte[] b) throws Exception { File f = new File(tmp, name); Files.write(f.toPath(), b); return f; }
    static File write(String name, String s, Charset cs, byte[] bom) throws Exception {
        byte[] body = s.getBytes(cs); byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length); System.arraycopy(body, 0, all, bom.length, body.length);
        return write(name, all);
    }
    static JSONObject open(File f, String ext) throws Exception { return PlainFormats.open(f, ext, "auto", "auto"); }
    static String err(File f, String ext) { try { open(f, ext); return null; } catch (Exception e) { return e.getMessage(); } }
    static JSONArray rows(JSONObject o) { return o.getJSONArray("sheets").getJSONObject(0).getJSONArray("rows"); }

    /** open -> saveDelimited (same encoding/newline/bom/delimiter) -> reopen; rows must be identical. */
    static boolean roundTrip(File f, String ext) throws Exception {
        JSONObject a = open(f, ext);
        File out = new File(tmp, "rt-" + f.getName());
        PlainFormats.saveDelimited(out, a.getJSONArray("sheets"), 0, ext, a.getString("encoding"), a.getString("delimiter"),
                a.getString("newline"), a.getBoolean("bom"), false);
        JSONObject b = open(out, ext);
        return rows(a).similar(rows(b));
    }

    public static void main(String[] args) throws Exception {
        File samples = new File(args[0]), fx = new File(args[1]);
        tmp = Files.createTempDirectory("csvh").toFile();
        byte[] none = new byte[0];
        byte[] u16le = {(byte)0xFF,(byte)0xFE}, u16be = {(byte)0xFE,(byte)0xFF}, u8 = {(byte)0xEF,(byte)0xBB,(byte)0xBF};
        String base = new String(Files.readAllBytes(new File(samples, "original-sample_ru.csv").toPath()), StandardCharsets.UTF_8);
        JSONArray baseRows = rows(open(new File(samples, "original-sample_ru.csv"), "csv"));

        // Base samples
        check("BASE-CSV parse", baseRows.length() == 3 && baseRows.getJSONArray(1).getString(2).equals("запятая, внутри")
                && baseRows.getJSONArray(2).getString(2).equals("две \"кавычки\""), baseRows.toString());
        check("BASE-CSV open→save→reopen", roundTrip(new File(samples, "original-sample_ru.csv"), "csv"), "");
        JSONArray tsvRows = rows(open(new File(samples, "original-sample_ru.tsv"), "tsv"));
        check("BASE-TSV parse", tsvRows.length() >= 2, tsvRows.toString());
        check("BASE-TSV open→save→reopen", roundTrip(new File(samples, "original-sample_ru.tsv"), "tsv"), "");

        // T8
        String e = err(write("t8_nul.csv", new byte[]{'a', ',', 'b', '\n', 'c', 0, 'd', ',', 'e', '\n'}), "csv");
        check("T8 NUL rejected with line/column", e != null && e.contains("NUL") && e.contains("строка 2") && e.contains("столбец 2"), String.valueOf(e));
        e = err(write("t8_bin.csv", new byte[]{(byte)0x89,'P','N','G',0x0D,0x0A,0x1A,0x0A,0,0,0,0x0D,'I','H','D','R'}), "csv");
        check("T8 binary (PNG header) rejected", e != null, String.valueOf(e));
        e = err(write("t8_nul.tsv", new byte[]{'a', '\t', 0, '\n'}), "tsv");
        check("T8 NUL rejected in TSV", e != null && e.contains("NUL"), String.valueOf(e));
        JSONObject le = open(write("t8_u16le.csv", base, StandardCharsets.UTF_16LE, u16le), "csv");
        check("T8 control UTF-16LE+BOM accepted, same rows", "UTF-16LE".equals(le.getString("encoding")) && rows(le).similar(baseRows), "");
        JSONObject be = open(write("t8_u16be.csv", base, StandardCharsets.UTF_16BE, u16be), "csv");
        check("T8 control UTF-16BE+BOM accepted, same rows", rows(be).similar(baseRows), "");
        check("T8 control UTF-16LE open→save→reopen", roundTrip(new File(tmp, "t8_u16le.csv"), "csv"), "");

        // T9
        for (String nl : new String[]{"\n", "\r\n"}) {
            String tag = nl.equals("\n") ? "LF" : "CRLF";
            String body = "sep=;" + nl + "a;b,c;d" + nl + "1;2,5;3" + nl;
            JSONObject o = open(write("t9_" + tag + ".csv", body, StandardCharsets.UTF_8, none), "csv");
            JSONArray r = rows(o);
            check("T9 sep=; " + tag, ";".equals(o.getString("delimiter")) && r.length() == 2 && r.getJSONArray(0).length() == 3
                    && r.getJSONArray(0).getString(0).equals("a") && r.getJSONArray(1).getString(1).equals("2,5"), r.toString());
            JSONObject ob = open(write("t9_bom_" + tag + ".csv", body, StandardCharsets.UTF_8, u8), "csv");
            check("T9 BOM + sep=; " + tag, ";".equals(ob.getString("delimiter")) && rows(ob).similar(r), "");
        }
        JSONObject tab = open(write("t9_tab.csv", "sep=\t\nx\ty\n", StandardCharsets.UTF_8, none), "csv");
        check("T9 sep=<tab>", "tab".equals(tab.getString("delimiter")) && rows(tab).length() == 1, rows(tab).toString());
        JSONObject only = open(write("t9_data.csv", "sep,x\n1,2\n", StandardCharsets.UTF_8, none), "csv");
        check("T9 ordinary 'sep' header untouched", rows(only).getJSONArray(0).getString(0).equals("sep"), "");
        e = err(write("t9_err.csv", "sep=;\na;\"b\nc;d\n", StandardCharsets.UTF_8, none), "csv");
        check("T9 line numbers count the directive line", e != null && e.contains("строка 2"), String.valueOf(e));

        // T10 / T27
        e = err(write("t10_unclosed.csv", "a,b\n1,\"open\n2,3\n", StandardCharsets.UTF_8, none), "csv");
        check("T10 unclosed quote has line/col", e != null && e.contains("строка 2, столбец 3"), String.valueOf(e));
        e = err(write("t10_after.csv", "a,b\n\"x\"y,2\n", StandardCharsets.UTF_8, none), "csv");
        check("T10 chars after closing quote has line/col", e != null && e.contains("строка 2, столбец 4"), String.valueOf(e));
        e = err(write("t10_inside.csv", "a,b\nx\"y,2\n", StandardCharsets.UTF_8, none), "csv");
        check("T10 quote inside unquoted field has line/col", e != null && e.contains("строка 2, столбец 2"), String.valueOf(e));
        e = err(write("t10_crlf.csv", "a,b\r\nc,d\r\nx\"y,2\r\n", StandardCharsets.UTF_8, none), "csv");
        check("T10 CRLF counted as one line", e != null && e.contains("строка 3, столбец 2"), String.valueOf(e));
        e = err(new File(fx, "t27_space_quotes.csv"), "csv");
        check("T27 fixture: space before quote → line/col", e != null && e.contains("строка 2, столбец 8"), String.valueOf(e));

        // T23
        JSONObject rg = open(new File(fx, "t23_ragged.csv"), "csv");
        JSONArray rr = rows(rg);
        String w = rg.optString("warning");
        check("T23 ragged warning (count + first record)", w.contains("Строки разной длины: 2") && w.contains("запись 2"), w);
        check("T23 data unchanged", rr.getJSONArray(1).length() == 2 && rr.getJSONArray(2).length() == 4
                && rr.getJSONArray(2).getString(3).equals("две \"кавычки\""), rr.toString());
        check("T23 no warning for rectangular base", open(new File(samples, "original-sample_ru.csv"), "csv").optString("warning").isEmpty(), "");

        // T12–T29 fixtures
        String[] same = {"t12_crlf.csv", "t13_cr.csv", "t14_mixed.csv", "t15_bom.csv", "t22_no_final_nl.csv"};
        for (String n : same) check(n.substring(0, 3).toUpperCase() + " " + n + " rows == base", rows(open(new File(fx, n), "csv")).similar(baseRows), "");
        JSONObject cp = open(new File(fx, "t16_cp1251.csv"), "csv");
        check("T16 cp1251 fallback + warning", "windows-1251".equals(cp.getString("encoding")) && rows(cp).getJSONArray(1).getString(0).equals("Ёж"), rows(cp).toString());
        JSONObject sc = open(new File(fx, "t17_semicolon.csv"), "csv");
        check("T17 semicolon detected, 1,5 kept", ";".equals(sc.getString("delimiter")) && rows(sc).getJSONArray(1).getString(1).equals("1,5"), "");
        check("T18 quoted newline in last field", rows(open(new File(fx, "t18_quoted_nl_last.csv"), "csv")).toString().contains("\\n"), "");
        check("T19 quoted newline", rows(open(new File(fx, "t19_quoted_nl_first.csv"), "csv")).length() == 3, rows(open(new File(fx, "t19_quoted_nl_first.csv"), "csv")).toString());
        JSONArray t20 = rows(open(new File(fx, "t20_trailing_delim.csv"), "csv"));
        check("T20 trailing delimiter adds empty field", t20.getJSONArray(0).length() == 4 && t20.getJSONArray(0).getString(3).isEmpty(), t20.toString());
        JSONArray t21 = rows(open(new File(fx, "t21_empty_last.csv"), "csv"));
        check("T21 empty last field kept", t21.length() == 3 && t21.getJSONArray(2).length() == 3 && t21.getJSONArray(2).getString(2).isEmpty(), t21.toString());
        JSONArray t24 = rows(open(new File(fx, "t24_long_field.csv"), "csv"));
        check("T24 long field intact", t24.getJSONArray(1).getString(1).length() > 900000, "len=" + t24.getJSONArray(1).getString(1).length());
        check("T25 header only", rows(open(new File(fx, "t25_header_only.csv"), "csv")).length() == 1, "");
        JSONArray t26 = rows(open(new File(fx, "t26_empty.csv"), "csv"));
        check("T26 empty file → one empty cell", t26.length() == 1, t26.toString());
        JSONArray t28 = rows(open(new File(fx, "t28_tsv_quotes.tsv"), "tsv"));
        check("T28 TSV quoted tab", t28.getJSONArray(1).getString(2).contains("\t"), t28.toString());
        JSONArray t29 = rows(open(new File(fx, "t29_injection.csv"), "csv"));
        check("T29 formulas/leading zeros kept as strings", t29.getJSONArray(1).getString(0).equals("=1+1") && t29.getJSONArray(3).getString(1).equals("007"), t29.toString());
        for (String n : new String[]{"t12_crlf.csv","t13_cr.csv","t14_mixed.csv","t15_bom.csv","t16_cp1251.csv","t17_semicolon.csv","t18_quoted_nl_last.csv","t19_quoted_nl_first.csv","t20_trailing_delim.csv","t21_empty_last.csv","t22_no_final_nl.csv","t23_ragged.csv","t24_long_field.csv","t25_header_only.csv","t29_injection.csv"})
            check("RT " + n + " open→save→reopen", roundTrip(new File(fx, n), "csv"), "");
        check("RT t28_tsv_quotes.tsv open→save→reopen", roundTrip(new File(fx, "t28_tsv_quotes.tsv"), "tsv"), "");

        System.out.println("TOTAL pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
