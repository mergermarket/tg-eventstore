package com.timgroup.eventstore.merging;

import com.timgroup.eventstore.api.EventReader;
import com.timgroup.eventstore.api.EventSource;
import com.timgroup.eventstore.api.Position;
import com.timgroup.eventstore.api.PositionCodec;
import com.timgroup.eventstore.api.ResolvedEvent;

import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public final class NamedReaderWithCodec {
    final String name;
    final Function<Position, Stream<ResolvedEvent>> reader;
    final PositionCodec codec;
    final Position startingPosition;

    public NamedReaderWithCodec(String name, EventReader reader, PositionCodec codec) {
        this(name, reader, codec, reader.emptyStorePosition());
    }

    public NamedReaderWithCodec(String name, EventReader reader, PositionCodec codec, Position startingPosition) {
        this(name, reader::readAllForwards, codec, startingPosition);
    }

    public NamedReaderWithCodec(String name, Function<Position, Stream<ResolvedEvent>> reader, PositionCodec codec, Position startingPosition) {
        checkNotNull(name, "name cannot be null");

        final String candidateName = name.trim();
        checkArgument(!candidateName.isEmpty(), "name cannot be empty");

        this.name = candidateName.intern();
        this.reader = reader;
        this.codec = codec;
        this.startingPosition = startingPosition;
    }

    public static NamedReaderWithCodec fromEventSource(String name, EventSource source) {
        return new NamedReaderWithCodec(name, source.readAll(), source.positionCodec());
    }

    @Override
    public String toString() {
        return "NamedReaderWithCodec{" +
                "name='" + name + '\'' +
                ", reader=" + reader +
                '}';
    }
}
