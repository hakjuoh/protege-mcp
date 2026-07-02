package io.github.hakjuoh.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The default competency-question convention: a {@code cqs/} folder of {@code *.rq} SPARQL files next to
 * the ontology document — the shape ROBOT / CI pipelines consume, so CQs authored here are portable
 * outside Protégé. Metadata a bare {@code .rq} can't hold lives in leading {@code # key: value} header
 * comments:
 *
 * <pre>
 *   # id: CQ-3
 *   # text: Does every measurement process have a participant?
 *   # type: Validating
 *   # expected: nonEmpty        (default when absent → nonEmpty)
 *   # include_inferred: true
 *   # tags: a, b
 *
 *   SELECT ... WHERE { ... }
 * </pre>
 *
 * <p>An {@code id}/{@code text} fall back to the filename for a headerless ROBOT file. Values awkward in a
 * comment (an EXACT_ROWS declared set) are encoded compactly on the header line; a project needing richer
 * fidelity uses {@code sidecar-manifest}. Reads are bounded to the resolved folder.
 */
final class RobotSparqlDirStore implements CqStore {

    @Override
    public String conventionId() {
        return Cq.CONV_ROBOT;
    }

    @Override
    public boolean isWritable(CqContext ctx) {
        return ctx.documentFolder() != null;
    }

    @Override
    public boolean detect(CqContext ctx) {
        File dir = ctx.cqsDir();
        return dir != null && dir.isDirectory() && rqFiles(dir).length > 0;
    }

    @Override
    public LoadResult load(CqContext ctx) {
        LoadResult result = new LoadResult();
        File dir = ctx.cqsDir();
        if (dir == null || !dir.isDirectory()) {
            return result;
        }
        for (File f : rqFiles(dir)) {
            try {
                CompetencyQuestion cq = readCq(f);
                cq.convention = conventionId();
                CompetencyQuestion.validate(cq);
                result.add(cq);
            } catch (RuntimeException | IOException e) {
                result.skip(f.getName(), e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage());
            }
        }
        return result;
    }

    @Override
    public void upsert(CqContext ctx, CompetencyQuestion cq) {
        File dir = ctx.cqsDir();
        if (dir == null) {
            throw new ToolArgException("The active ontology has no saved file document, so a cqs/ folder "
                    + "cannot be located. Save it first, or use convention=ontology-annotations.");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new ToolArgException("Could not create the cqs/ folder: " + dir);
        }
        File target = targetFile(dir, cq.id);
        if (!SidecarPaths.isWithin(dir, target)) {
            throw new ToolArgException("Refusing to write outside the cqs/ folder: " + target);
        }
        try {
            Files.write(target.toPath(), render(cq).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ToolArgException("Could not write " + target.getName() + ": " + e.getMessage());
        }
    }

    /**
     * The file to (over)write for {@code id}, honouring "replace by id": if an existing {@code .rq} already
     * carries this effective id (via its {@code # id:} header, which can differ from the filename stem), it
     * is reused so an update replaces it in place. Otherwise a fresh {@code <sanitizeId>.rq} is used —
     * suffixed to a free name when that stem is already taken by a DIFFERENT id (two ids can sanitise to the
     * same stem, e.g. "CQ 1" and "CQ/1"), so a distinct CQ is never silently clobbered.
     */
    private static File targetFile(File dir, String id) {
        for (File f : rqFiles(dir)) {
            String fileId;
            try {
                fileId = readCq(f).id;
            } catch (RuntimeException | IOException e) {
                fileId = stripRq(f.getName());
            }
            if (id.equals(fileId)) {
                return f;   // replace the existing file that owns this id
            }
        }
        String stem = SidecarPaths.sanitizeId(id);
        File candidate = new File(dir, stem + ".rq");
        for (int n = 2; candidate.exists(); n++) {
            candidate = new File(dir, stem + "-" + n + ".rq");
        }
        return candidate;
    }

    @Override
    public boolean remove(CqContext ctx, String id) {
        File dir = ctx.cqsDir();
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        boolean removed = false;
        for (File f : rqFiles(dir)) {
            String fileId;
            try {
                fileId = readCq(f).id;
            } catch (RuntimeException | IOException e) {
                fileId = stripRq(f.getName());
            }
            if (id.equals(fileId) && f.delete()) {
                removed = true;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ file <-> model

    /** Render a CQ to {@code .rq} text: header comments, a blank line, then the query body. */
    static String render(CompetencyQuestion cq) {
        StringBuilder sb = new StringBuilder();
        sb.append("# id: ").append(cq.id).append('\n');
        if (cq.text != null) {
            sb.append("# text: ").append(oneLine(cq.text)).append('\n');
        }
        if (cq.type != null) {
            sb.append("# type: ").append(oneLine(cq.type)).append('\n');
        }
        sb.append("# expected: ").append(cq.expected.toHeaderString()).append('\n');
        sb.append("# include_inferred: ").append(cq.includeInferred).append('\n');
        if (cq.tags != null && !cq.tags.isEmpty()) {
            sb.append("# tags: ").append(String.join(", ", cq.tags)).append('\n');
        }
        sb.append('\n').append(cq.query.trim()).append('\n');
        return sb.toString();
    }

    /** Parse a {@code .rq} file into a CQ: read leading {@code # key: value} headers, then the body. */
    static CompetencyQuestion readCq(File f) throws IOException {
        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        CompetencyQuestion cq = new CompetencyQuestion();
        cq.id = stripRq(f.getName());
        cq.text = null;
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                applyHeader(cq, trimmed.substring(1).trim());
            } else {
                body.append(line).append('\n');
            }
        }
        cq.query = body.toString().trim();
        return cq;
    }

    private static void applyHeader(CompetencyQuestion cq, String header) {
        int colon = header.indexOf(':');
        if (colon < 0) {
            return;
        }
        String key = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = header.substring(colon + 1).trim();
        switch (key) {
            case "id":
                if (!value.isEmpty()) {
                    cq.id = value;
                }
                break;
            case "text":
                cq.text = value;
                break;
            case "type":
                cq.type = value;
                break;
            case "expected":
                cq.expected = Expectation.fromHeaderString(value);
                break;
            case "include_inferred":
                cq.includeInferred = Boolean.parseBoolean(value);
                break;
            case "tags":
                for (String t : value.split(",")) {
                    if (!t.trim().isEmpty()) {
                        cq.tags.add(t.trim());
                    }
                }
                break;
            default:
                break;   // unknown header (e.g. cqs-format) is ignored, not fatal
        }
    }

    private static File[] rqFiles(File dir) {
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".rq"));
        if (files == null) {
            return new File[0];
        }
        // Deterministic order so list/run/remove are stable run-to-run.
        List<File> sorted = new ArrayList<>(java.util.Arrays.asList(files));
        sorted.sort(java.util.Comparator.comparing(File::getName));
        return sorted.toArray(new File[0]);
    }

    private static String stripRq(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".rq")
                ? name.substring(0, name.length() - 3) : name;
    }

    private static String oneLine(String s) {
        return s.replace("\r", " ").replace("\n", " ").trim();
    }
}
