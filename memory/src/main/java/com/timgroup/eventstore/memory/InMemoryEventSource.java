package com.timgroup.eventstore.memory;

import com.timgroup.eventstore.api.EventCategoryReader;
import com.timgroup.eventstore.api.EventReader;
import com.timgroup.eventstore.api.EventSource;
import com.timgroup.eventstore.api.EventStreamReader;
import com.timgroup.eventstore.api.EventStreamWriter;
import com.timgroup.eventstore.api.PositionCodec;
import com.timgroup.tucker.info.Component;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;

import static java.util.Objects.requireNonNull;

public class InMemoryEventSource implements EventSource {
    private final JavaInMemoryEventStore eventStore;

    public InMemoryEventSource(JavaInMemoryEventStore eventStore) {
        this.eventStore = requireNonNull(eventStore);
    }

    @Override
    @Nonnull
    public EventReader readAll() {
        return eventStore;
    }

    @Override
    @Nonnull
    public EventCategoryReader readCategory() {
        return eventStore;
    }

    @Override
    @Nonnull
    public EventStreamReader readStream() {
        return eventStore;
    }

    @Override
    @Nonnull
    public EventStreamWriter writeStream() {
        return eventStore;
    }

    @Override
    @Nonnull
    public PositionCodec positionCodec() {
        return JavaInMemoryEventStore.CODEC;
    }

    @Override
    @Nonnull
    public Collection<Component> monitoring() {
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "InMemoryEventSource{" +
                "eventStore=" + eventStore +
                '}';
    }
}
