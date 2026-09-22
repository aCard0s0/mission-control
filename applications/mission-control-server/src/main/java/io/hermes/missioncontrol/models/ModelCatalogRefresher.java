package io.hermes.missioncontrol.models;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-reads the keyless providers' model lists twice a day — and the keyed ones' too, for
 * every provider a saved credential holds a key for.
 *
 * <p>The picker used to offer a list authored into {@code application.yml}. Every model a
 * vendor shipped after this app's last release was therefore missing from it, and nothing
 * short of an operator editing an environment variable would bring it back — which is a
 * strange thing to ask of someone who only wanted to pick a model.
 *
 * <p>Twelve hours because a vendor announcing a model is a thing that happens on a
 * weekday afternoon and matters by the next morning; nothing here needs to notice inside
 * the hour, and three keyless endpoints polled twice a day is not traffic worth pacing.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: the next read is measured from the end of
 * the last, so a provider that hangs until its timeout cannot stack a second run on top of
 * the first. The first read runs shortly after boot rather than immediately — the app is
 * answering requests by then, and an outbound call in the startup path would make a slow
 * provider look like a slow deployment.
 *
 * <p>Disabled by {@code mc.model-catalog.refresh: false}, which the test profile sets: a
 * context test must not reach the internet, and a suite that did would fail on an
 * aeroplane.
 */
@Component
@ConditionalOnProperty(name = "mc.model-catalog.refresh", havingValue = "true", matchIfMissing = true)
public class ModelCatalogRefresher {

  private static final Logger log = LoggerFactory.getLogger(ModelCatalogRefresher.class);

  private final ModelCatalogService catalog;

  public ModelCatalogRefresher(ModelCatalogService catalog) {
    this.catalog = catalog;
  }

  // Duration strings rather than the numeric form: @Scheduled shares one timeUnit across
  // both values, so `initialDelay = 30, fixedDelay = 12, HOURS` would wait thirty hours.
  @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT12H")
  public void refresh() {
    List<String> refreshed = catalog.refreshAll();
    if (refreshed.isEmpty()) {
      // every one of them failing at once is a network story, not a provider story
      log.warn("model catalog refresh updated nothing — every provider was unreadable");
    } else {
      log.info("model catalog refresh updated {}", refreshed);
    }
  }
}
