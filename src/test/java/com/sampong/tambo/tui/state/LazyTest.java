package com.sampong.tambo.tui.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LazyTest {

    private final Lazy<List<String>> lazy = new Lazy<>(List.of());

    @Test
    void startsOnThePlaceholderAndUnloaded() {
        assertThat(lazy.value()).isEmpty();
        assertThat(lazy.everLoaded()).isFalse();
        assertThat(lazy.failed()).isFalse();
    }

    @Test
    void onlyTheFirstClaimStartsALoad() {
        int token = lazy.claim();

        assertThat(token).isNotEqualTo(Lazy.NO_LOAD);
        assertThat(lazy.claim()).as("already in flight").isEqualTo(Lazy.NO_LOAD);

        lazy.publish(token, List.of("node"));

        assertThat(lazy.value()).containsExactly("node");
        assertThat(lazy.everLoaded()).isTrue();
        assertThat(lazy.claim()).as("fresh").isEqualTo(Lazy.NO_LOAD);
    }

    @Test
    void invalidateKeepsTheValueOnScreenWhileReloading() {
        lazy.publish(lazy.claim(), List.of("node"));

        lazy.invalidate();

        assertThat(lazy.value()).containsExactly("node");
        assertThat(lazy.claim()).isNotEqualTo(Lazy.NO_LOAD);
    }

    @Test
    void aLoadInvalidatedMidFlightIsDropped() {
        int stale = lazy.claim();
        lazy.invalidate();
        int fresh = lazy.claim();

        lazy.publish(stale, List.of("stale"));
        assertThat(lazy.everLoaded()).as("stale result ignored").isFalse();

        lazy.publish(fresh, List.of("fresh"));
        assertThat(lazy.value()).containsExactly("fresh");
    }

    @Test
    void aFailureStopsRetriesUntilInvalidated() {
        lazy.fail(lazy.claim());

        assertThat(lazy.failed()).isTrue();
        assertThat(lazy.claim()).as("no retry every frame").isEqualTo(Lazy.NO_LOAD);

        lazy.invalidate();

        assertThat(lazy.failed()).isFalse();
        assertThat(lazy.claim()).isNotEqualTo(Lazy.NO_LOAD);
    }

    @Test
    void retryIfFailedClearsOnlyTheFailure() {
        lazy.fail(lazy.claim());

        lazy.retryIfFailed();

        assertThat(lazy.claim()).isNotEqualTo(Lazy.NO_LOAD);
    }
}
