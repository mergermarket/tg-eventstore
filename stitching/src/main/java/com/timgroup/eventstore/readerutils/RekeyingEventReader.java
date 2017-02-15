package com.timgroup.eventstore.readerutils;

import com.timgroup.eventstore.api.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.timgroup.eventstore.api.EventRecord.eventRecord;
import static java.lang.Long.MAX_VALUE;
import static java.lang.Long.parseLong;
import static java.util.stream.StreamSupport.stream;

public final class RekeyingEventReader implements EventReader {

    private final EventReader underlying;
    private final PositionCodec underlyingPositionCodec;
    private final StreamId newStreamId;

    private RekeyingEventReader(EventReader underlying, PositionCodec underlyingPositionCodec, StreamId newStreamId) {
        this.underlying = underlying;
        this.underlyingPositionCodec = underlyingPositionCodec;
        this.newStreamId = newStreamId;
    }

    public static RekeyingEventReader rekeying(EventReader underlying, PositionCodec underlyingPositionCodec, StreamId newKey) {
        return new RekeyingEventReader(underlying, underlyingPositionCodec, newKey);
    }

    @Override
    public final Stream<ResolvedEvent> readAllForwards(Position positionExclusive) {
        RekeyedStreamPosition rekeyedEventPosition = (RekeyedStreamPosition)positionExclusive;

        Stream<ResolvedEvent> events = underlying.readAllForwards(rekeyedEventPosition.underlyingPosition);
        return stream(new RekeyingSpliterator(newStreamId, rekeyedEventPosition.eventNumber, events.iterator()), false)
                .onClose(events::close);
    }

    @Override
    public final Position emptyStorePosition() {
        return new RekeyedStreamPosition(underlying.emptyStorePosition(), -1L);
    }

    public PositionCodec positionCodec() {
        return new RekeyedStreamPositionCodec(underlyingPositionCodec);
    }

    private static final class RekeyingSpliterator implements Spliterator<ResolvedEvent> {
        private final StreamId newStreamId;
        private final Iterator<ResolvedEvent> events;

        private long eventNumber;

        public RekeyingSpliterator(StreamId newStreamId, long lastEventNumber, Iterator<ResolvedEvent> events) {
            this.newStreamId = newStreamId;
            this.eventNumber = lastEventNumber;
            this.events = events;
        }

        @Override
        public boolean tryAdvance(Consumer<? super ResolvedEvent> action) {
            if (events.hasNext()) {
                ResolvedEvent event = events.next();
                eventNumber++;
                action.accept(new ResolvedEvent(
                        new RekeyedStreamPosition(event.position(), eventNumber),
                        eventRecord(
                                event.eventRecord().timestamp(),
                                newStreamId,
                                eventNumber,
                                event.eventRecord().eventType(),
                                event.eventRecord().data(),
                                event.eventRecord().metadata()
                        )
                ));
                return true;
            }
            return false;
        }

        @Override
        public Spliterator<ResolvedEvent> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            return MAX_VALUE;
        }

        @Override
        public int characteristics() {
            return ORDERED | NONNULL | DISTINCT;
        }
    }

    private static final class RekeyedStreamPosition implements Position {
        private final Position underlyingPosition;
        private final long eventNumber;

        public RekeyedStreamPosition(Position position, long eventNumber) {
            this.underlyingPosition = position;
            this.eventNumber = eventNumber;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RekeyedStreamPosition that = (RekeyedStreamPosition) o;
            return eventNumber == that.eventNumber &&
                    Objects.equals(underlyingPosition, that.underlyingPosition);
        }

        @Override
        public int hashCode() {
            return Objects.hash(underlyingPosition, eventNumber);
        }

        @Override
        public String toString() {
            return "RekeyedStreamPosition{" +
                    "underlyingPosition=" + underlyingPosition +
                    ", eventNumber=" + eventNumber +
                    '}';
        }
    }

    private static final class RekeyedStreamPositionCodec implements PositionCodec {
        private static final String REKEY_SEPARATOR = ":";
        private static final Pattern REKEY_PATTERN = Pattern.compile(Pattern.quote(REKEY_SEPARATOR));

        private final PositionCodec underlyingPositionCodec;

        public RekeyedStreamPositionCodec(PositionCodec underlyingPositionCodec) {
            this.underlyingPositionCodec = underlyingPositionCodec;
        }

        @Override
        public String serializePosition(Position position) {
            RekeyedStreamPosition rekeyedPosition = (RekeyedStreamPosition) position;
            return String.valueOf(rekeyedPosition.eventNumber)
                    + REKEY_SEPARATOR
                    + underlyingPositionCodec.serializePosition(rekeyedPosition.underlyingPosition);
        }

        @Override
        public Position deserializePosition(String string) {
            String[] data = REKEY_PATTERN.split(string, 2);
            return new RekeyedStreamPosition(
                    underlyingPositionCodec.deserializePosition(data[1]),
                    parseLong(data[0])
            );
        }
    }
}