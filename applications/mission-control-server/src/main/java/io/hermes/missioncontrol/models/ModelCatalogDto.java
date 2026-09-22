package io.hermes.missioncontrol.models;

import java.util.List;

/** Matches the frontend model catalog. */
public record ModelCatalogDto(
    String provider,
    List<String> models,
    /** Where this list came from, so a page can say which one an operator is looking at:
     *  {@code catalog} — stored from the provider's own API, by the background refresh or by
     *  an earlier live read;
     *  {@code live} — fetched from the provider just now, with a caller-supplied key;
     *  {@code config} — the curated {@code mc.models} list this application shipped with. */
    String source) {
}
