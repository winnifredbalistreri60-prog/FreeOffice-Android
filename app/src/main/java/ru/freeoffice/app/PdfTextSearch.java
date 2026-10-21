package ru.freeoffice.app;

import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native text-layer search for a single PDF page (Android 15 / API 35+). */
public final class PdfTextSearch {
    private static final int MAX_MATCHES = 1000;
    private static final int MAX_RECTS_PER_MATCH = 256;

    private PdfTextSearch() {
    }

    /** Returns whether the platform provides PdfRenderer page text/search APIs. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 35;
    }

    /**
     * Searches the page's native text layer. Bounds are copied in native PDF point
     * coordinates; no OCR or fallback search is performed.
     *
     * @throws UnsupportedOperationException on API levels below 35
     */
    public static Result searchPage(PdfRenderer.Page page, String query) {
        if (!isSupported()) {
            throw new UnsupportedOperationException("Native PDF text search requires Android API 35+");
        }
        if (page == null) {
            throw new NullPointerException("page");
        }
        if (query == null) {
            throw new NullPointerException("query");
        }
        return Api35.searchPage(page, query);
    }

    public static final class Result {
        public final boolean hasText;
        public final List<List<RectF>> matches;
        /** True if native matches or their rectangles were capped to bound memory use. */
        public final boolean truncated;

        public Result(boolean hasText, List<List<RectF>> matches) {
            this(hasText, matches, false);
        }

        public Result(boolean hasText, List<List<RectF>> matches, boolean truncated) {
            this.hasText = hasText;
            this.matches = immutableCopy(matches);
            this.truncated = truncated;
        }

        private static List<List<RectF>> immutableCopy(List<List<RectF>> source) {
            if (source == null || source.isEmpty()) {
                return Collections.emptyList();
            }
            List<List<RectF>> outer = new ArrayList<>(source.size());
            for (List<RectF> match : source) {
                List<RectF> inner = new ArrayList<>();
                if (match != null) {
                    for (RectF rect : match) {
                        if (rect != null) {
                            inner.add(new RectF(rect));
                        }
                    }
                }
                outer.add(Collections.unmodifiableList(inner));
            }
            return Collections.unmodifiableList(outer);
        }
    }

    @android.annotation.TargetApi(35)
    private static final class Api35 {
        private Api35() {
        }

        static Result searchPage(PdfRenderer.Page page, String query) {
            boolean hasText = false;
            for (android.graphics.pdf.content.PdfPageTextContent content : page.getTextContents()) {
                String text = content.getText();
                if (text != null && !text.isEmpty()) {
                    hasText = true;
                    break;
                }
            }

            List<android.graphics.pdf.models.PageMatchBounds> nativeMatches = page.searchText(query);
            List<List<RectF>> matches = new ArrayList<>();
            boolean truncated = false;
            for (android.graphics.pdf.models.PageMatchBounds nativeMatch : nativeMatches) {
                if (matches.size() >= MAX_MATCHES) {
                    truncated = true;
                    break;
                }
                List<RectF> bounds = nativeMatch.getBounds();
                List<RectF> copied = new ArrayList<>();
                if (bounds != null) {
                    for (RectF rect : bounds) {
                        if (copied.size() >= MAX_RECTS_PER_MATCH) {
                            truncated = true;
                            break;
                        }
                        if (rect != null) {
                            copied.add(new RectF(rect));
                        }
                    }
                }
                matches.add(copied);
            }
            return new Result(hasText, matches, truncated);
        }
    }
}
