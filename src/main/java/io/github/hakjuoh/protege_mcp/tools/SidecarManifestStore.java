package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full-fidelity competency-question convention: a single {@code <basename>-cqs.json} manifest next to
 * the ontology document holding every model field plus a {@code version}. JSON (not YAML) because
 * {@code jackson-databind} is a direct dependency. Unknown keys — top-level and per-question — are
 * preserved on round-trip (a mutating call re-reads and edits the parsed tree in place), and a manifest
 * whose {@code version} is newer than {@link Cq#MANIFEST_VERSION} is refused with a clear message rather
 * than silently truncated. Writes are atomic (temp → rename), UTF-8 with LF newlines.
 */
final class SidecarManifestStore implements CqStore {

    private static final String QUESTIONS = "questions";
    private static final String VERSION = "version";

    @Override
    public String conventionId() {
        return Cq.CONV_MANIFEST;
    }

    @Override
    public boolean isWritable(CqContext ctx) {
        return ctx.documentFolder() != null;
    }

    @Override
    public boolean detect(CqContext ctx) {
        File f = ctx.manifestFile();
        return f != null && f.isFile();
    }

    @Override
    public LoadResult load(CqContext ctx) {
        LoadResult result = new LoadResult();
        File f = ctx.manifestFile();
        if (f == null || !f.isFile()) {
            return result;
        }
        Map<String, Object> root;
        try {
            root = readRoot(f);
        } catch (RuntimeException | IOException e) {
            result.skip(f.getName(), "unreadable manifest: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return result;
        }
        if (root == null) {
            result.skip(f.getName(), "manifest is not a JSON object");
            return result;
        }
        int version = intOr(root.get(VERSION), 1);
        if (version > Cq.MANIFEST_VERSION) {
            result.skip(f.getName(), "manifest version " + version + " is newer than this build supports "
                    + "(" + Cq.MANIFEST_VERSION + ") — upgrade protege-mcp to read it");
            return result;
        }
        for (Map<String, Object> entry : questions(root)) {
            String source = f.getName() + "#" + entry.get("id");
            try {
                CompetencyQuestion cq = CompetencyQuestion.fromStorageJson(entry);
                cq.convention = conventionId();
                result.add(cq);
            } catch (RuntimeException e) {
                result.skip(source, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        return result;
    }

    @Override
    public void upsert(CqContext ctx, CompetencyQuestion cq) {
        File f = requireFile(ctx);
        Map<String, Object> root = readOrNew(f);
        List<Map<String, Object>> questions = questions(root);
        Map<String, Object> newEntry = cq.toStorageJson();
        boolean replaced = false;
        for (int i = 0; i < questions.size(); i++) {
            if (cq.id.equals(String.valueOf(questions.get(i).get("id")))) {
                // Merge over the old entry so unknown per-question keys survive the round-trip.
                Map<String, Object> merged = new LinkedHashMap<>(questions.get(i));
                merged.putAll(newEntry);
                questions.set(i, merged);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            questions.add(newEntry);
        }
        root.put(QUESTIONS, questions);
        write(f, root);
    }

    @Override
    public boolean remove(CqContext ctx, String id) {
        File f = ctx.manifestFile();
        if (f == null || !f.isFile()) {
            return false;
        }
        Map<String, Object> root = readOrNew(f);
        List<Map<String, Object>> questions = questions(root);
        boolean removed = questions.removeIf(q -> id.equals(String.valueOf(q.get("id"))));
        if (removed) {
            root.put(QUESTIONS, questions);
            write(f, root);
        }
        return removed;
    }

    // ------------------------------------------------------------------ manifest IO

    private File requireFile(CqContext ctx) {
        File f = ctx.manifestFile();
        if (f == null) {
            throw new ToolArgException("The active ontology has no saved file document, so a manifest "
                    + "cannot be located. Save it first, or use convention=ontology-annotations.");
        }
        return f;
    }

    private Map<String, Object> readOrNew(File f) {
        if (f.isFile()) {
            Map<String, Object> root;
            try {
                root = readRoot(f);
            } catch (RuntimeException | IOException e) {
                throw new ToolArgException("Could not read the existing manifest " + f.getName() + ": "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            if (root == null) {
                throw new ToolArgException("The existing manifest " + f.getName() + " is not a JSON object.");
            }
            int version = intOr(root.get(VERSION), 1);
            if (version > Cq.MANIFEST_VERSION) {
                throw new ToolArgException("The existing manifest is version " + version + ", newer than "
                        + "this build supports (" + Cq.MANIFEST_VERSION + "). Upgrade protege-mcp before "
                        + "editing it.");
            }
            root.put(VERSION, Cq.MANIFEST_VERSION);
            return root;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(VERSION, Cq.MANIFEST_VERSION);
        root.put(QUESTIONS, new ArrayList<Map<String, Object>>());
        return root;
    }

    private Map<String, Object> readRoot(File f) throws IOException {
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return Cq.JSON.readMapOrNull(json);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> questions(Map<String, Object> root) {
        Object q = root.get(QUESTIONS);
        List<Map<String, Object>> out = new ArrayList<>();
        if (q instanceof List) {
            for (Object entry : (List<Object>) q) {
                if (entry instanceof Map) {
                    out.add((Map<String, Object>) entry);
                }
            }
        }
        return out;
    }

    /** Atomic write: pretty JSON to a temp file, then move into place (UTF-8, LF via the mapper). */
    private void write(File f, Map<String, Object> root) {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new ToolArgException("Could not create the manifest folder: " + dir);
        }
        String json = Cq.JSON.writePretty(root) + "\n";
        File tmp = null;
        try {
            tmp = File.createTempFile(f.getName(), ".tmp", dir);
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tmp = null;   // moved into place — nothing to clean up
        } catch (IOException e) {
            throw new ToolArgException("Could not write the manifest " + f.getName() + ": "
                    + e.getMessage());
        } finally {
            if (tmp != null && tmp.exists()) {
                tmp.delete();   // best-effort: don't leak the temp file if write/move failed
            }
        }
    }

    private static int intOr(Object v, int def) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
