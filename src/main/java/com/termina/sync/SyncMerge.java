package com.termina.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * Three-way merge of one synced file: what this computer last synced ({@code base}), what it holds now
 * ({@code mine}) and what the repository holds ({@code theirs}). Pure.
 *
 * <p>The merge unit is the entry — one command's binding — so two computers that each rebound a
 * different command both keep their work. An entry changed differently on both sides is a conflict and
 * this computer's version wins; the other stays in the repository's history. With no base (the first
 * sync with a repository) nothing looks removed, so both sides' entries are kept.
 */
public final class SyncMerge {

    private SyncMerge() {}

    /**
     * The outcome for one file.
     *
     * @param text the merged file in its repository form, or null when it no longer has any entries
     * @param received entries this computer takes from the repository (added, changed or removed there)
     * @param sent entries the repository takes from this computer
     * @param conflicts entries changed differently on both sides; this computer's version was kept
     * @param merged the merged entries, which {@code text} renders
     */
    public record FileResult(
            String path,
            @Nullable String text,
            Map<String, String> merged,
            List<String> received,
            List<String> sent,
            List<String> conflicts,
            int mineEntries,
            int theirEntries) {}

    /** {@code raw} as the repository stores it, so the same bindings are the same bytes everywhere. */
    public static @Nullable String normalize(String path, @Nullable String raw) {
        return raw == null ? null : KeybindingsFile.text(path, KeybindingsFile.entries(raw));
    }

    public static FileResult merge(String path, @Nullable String base, @Nullable String mine, @Nullable String theirs) {
        Map<String, String> b = KeybindingsFile.entries(base);
        Map<String, String> m = KeybindingsFile.entries(mine);
        Map<String, String> t = KeybindingsFile.entries(theirs);

        List<String> received = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<String, String> merged = new LinkedHashMap<>();
        TreeSet<String> names = new TreeSet<>(m.keySet());
        names.addAll(t.keySet());
        names.addAll(b.keySet());
        for (String name : names) {
            String bv = b.get(name);
            String mv = m.get(name);
            String tv = t.get(name);
            String result;
            if (Objects.equals(mv, tv)) {
                result = mv;
            } else if (Objects.equals(mv, bv)) {
                result = tv; // only the repository changed it
                received.add(name);
            } else {
                result = mv; // this computer changed it; on a tie it also wins
                sent.add(name);
                if (!Objects.equals(tv, bv)) conflicts.add(name);
            }
            if (result != null) merged.put(name, result);
        }
        return new FileResult(
                path,
                KeybindingsFile.text(path, merged),
                merged,
                List.copyOf(received),
                List.copyOf(sent),
                List.copyOf(conflicts),
                m.size(),
                t.size());
    }
}
