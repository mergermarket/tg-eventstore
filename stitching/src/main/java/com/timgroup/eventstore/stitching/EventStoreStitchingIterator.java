package com.timgroup.eventstore.stitching;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import org.joda.time.DateTime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static java.util.stream.Collectors.toList;

public final class EventStoreStitchingIterator implements Iterator<EventInIdentifiedStream> {
    private final Clock clock;
    private final Duration delay;
    private final List<PeekingIterator<EventInIdentifiedStream>> underlying;

    private Long cutOffTime;
    private PeekingIterator<EventInIdentifiedStream> iteratorWhoseHeadIsNext;

    public EventStoreStitchingIterator(Clock clock, Duration delay, List<Iterator<EventInIdentifiedStream>> iterators) {
        this.clock = clock;
        this.delay = delay;
        this.underlying = iterators.stream().map(Iterators::peekingIterator).collect(toList());
    }

    @Override
    public boolean hasNext() {
        return getIteratorWhoseHeadIsNext() != null;
    }

    @Override
    public EventInIdentifiedStream next() {
        Iterator<EventInIdentifiedStream> candidate = getIteratorWhoseHeadIsNext();
        if (candidate == null) {
            throw new NoSuchElementException();
        }
        iteratorWhoseHeadIsNext = null;
        return candidate.next();
    }

    private Iterator<EventInIdentifiedStream> getIteratorWhoseHeadIsNext() {
        if (iteratorWhoseHeadIsNext != null) {
            return iteratorWhoseHeadIsNext;
        }
        Iterator<PeekingIterator<EventInIdentifiedStream>> streams = underlying.iterator();
        while (streams.hasNext()) {
            PeekingIterator<EventInIdentifiedStream> eventStream = streams.next();

            Instant potentialCutoffTime = clock.instant();
            if (eventStream.hasNext()) {
                EventInIdentifiedStream candidate = eventStream.peek();
                DateTime candidateTimestamp = candidate.event.effectiveTimestamp();
                if (cutOffTime != null && candidateTimestamp.isAfter(cutOffTime)) {
                    streams.remove();
                } else if (iteratorWhoseHeadIsNext == null || candidateTimestamp.isBefore(iteratorWhoseHeadIsNext.peek().event.effectiveTimestamp())) {
                    iteratorWhoseHeadIsNext = eventStream;
                }
            } else {
                streams.remove();
                if (this.cutOffTime == null) {
                    this.cutOffTime = potentialCutoffTime.minus(delay).toEpochMilli();
                }
            }
        }

        if (iteratorWhoseHeadIsNext != null) {
            long cutoff = this.cutOffTime == null ? clock.instant().minus(delay).toEpochMilli() : this.cutOffTime;
            if (iteratorWhoseHeadIsNext.peek().event.effectiveTimestamp().isAfter(cutoff)) {
                underlying.clear();
                return null;
            }
        }
        return iteratorWhoseHeadIsNext;
    }
}