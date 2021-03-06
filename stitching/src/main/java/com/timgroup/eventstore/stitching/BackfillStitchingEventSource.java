package com.timgroup.eventstore.stitching;

import com.timgroup.eventstore.api.EventCategoryReader;
import com.timgroup.eventstore.api.EventReader;
import com.timgroup.eventstore.api.EventSource;
import com.timgroup.eventstore.api.EventStreamReader;
import com.timgroup.eventstore.api.EventStreamWriter;
import com.timgroup.eventstore.api.Position;
import com.timgroup.eventstore.api.PositionCodec;
import com.timgroup.tucker.info.Component;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class BackfillStitchingEventSource implements EventSource {

    private final EventSource backfill;
    private final EventSource live;
    private final PositionCodec positionCodec;
    private final BackfillStitchingEventReader eventReader;

    public BackfillStitchingEventSource(EventSource backfill, EventSource live, Position liveCutoffStartPosition) {
        this.backfill = backfill;
        this.live = live;
        this.positionCodec = StitchedPosition.codec(backfill.positionCodec(), live.positionCodec());
        this.eventReader = new BackfillStitchingEventReader(backfill, live, liveCutoffStartPosition);
    }

    @Nonnull
    @Override
    public EventReader readAll() {
        return this.eventReader;
    }

    @Nonnull
    @Override
    public EventCategoryReader readCategory() {
        return this.eventReader;
    }

    @Nonnull
    @Override
    public EventStreamReader readStream() {
        throw new UnsupportedOperationException("stitched store does not support reading streams");
    }

    @Nonnull
    @Override
    public EventStreamWriter writeStream() {
        throw new UnsupportedOperationException("stitched store does not support writing");
    }

    @Nonnull
    @Override
    public PositionCodec positionCodec() {
        return positionCodec;
    }

    @Nonnull
    @Override
    public Collection<Component> monitoring() {
        List<Component> result = new ArrayList<>();
        result.addAll(backfill.monitoring());
        result.addAll(live.monitoring());
        return result;
    }

    @Override
    public String toString() {
        return "BackfillStitchingEventSource{" +
                "backfill=" + backfill +
                ", live=" + live +
                ", positionCodec=" + positionCodec +
                ", eventReader=" + eventReader +
                '}';
    }
}
