package com.timgroup.eventstore.shovelling;

import com.google.common.collect.Lists;
import com.timgroup.clocks.testing.ManualClock;
import com.timgroup.eventstore.api.EventRecord;
import com.timgroup.eventstore.api.NewEvent;
import com.timgroup.eventstore.api.ResolvedEvent;
import com.timgroup.eventstore.api.StreamId;
import com.timgroup.eventstore.common.IdempotentEventStreamWriter;
import com.timgroup.eventstore.memory.InMemoryEventSource;
import com.timgroup.eventstore.memory.JavaInMemoryEventStore;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.timgroup.eventstore.api.EventRecordMatcher.anEventRecord;
import static com.timgroup.eventstore.api.NewEvent.newEvent;
import static com.timgroup.eventstore.api.StreamId.streamId;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public final class EventShovelTest {

    private final ManualClock clock = new ManualClock(Instant.parse("2009-04-12T22:12:32Z"), ZoneId.of("UTC"));

    private final JavaInMemoryEventStore inputReader = new JavaInMemoryEventStore(clock);
    private final InMemoryEventSource inputSource = new InMemoryEventSource(inputReader);

    private final InMemoryEventSource outputSource = new InMemoryEventSource(new JavaInMemoryEventStore(clock));


    private final EventShovel underTest = new EventShovel(inputSource.readAll(), inputSource.positionCodec(), outputSource);

    @Test public void
    it_shovels_all_events() throws Exception {
        inputEventArrived(streamId("david", "tom"), newEvent("CoolenessAdded", new byte[0], new byte[0]));
        inputEventArrived(streamId("david", "tom"), newEvent("CoolenessChanged", new byte[0], new byte[0]));
        inputEventArrived(streamId("foo", "bar"), newEvent("CoolenessRemoved", new byte[0], new byte[0]));

        underTest.shovelAllNewlyAvailableEvents();

        List<EventRecord> shovelledEvents = outputSource.readAll().readAllForwards().map(ResolvedEvent::eventRecord).collect(Collectors.toList());

        assertThat(shovelledEvents, contains(
                anEventRecord(
                        clock.instant(),
                        StreamId.streamId("david", "tom"),
                        0L,
                        "CoolenessAdded",
                        new byte[0],
                        "{\"shovel_position\":\"1\"}".getBytes(UTF_8)
                ),
                anEventRecord(
                        clock.instant(),
                        StreamId.streamId("david", "tom"),
                        1L,
                        "CoolenessChanged",
                        new byte[0],
                        "{\"shovel_position\":\"2\"}".getBytes(UTF_8)
                ),
                anEventRecord(
                        clock.instant(),
                        StreamId.streamId("foo", "bar"),
                        0L,
                        "CoolenessRemoved",
                        new byte[0],
                        "{\"shovel_position\":\"3\"}".getBytes(UTF_8)
                )
        ));
    }

    @Test public void
    it_shovels_only_events_that_it_has_not_previously_shovelled() throws Exception {
        inputEventArrived(streamId("previous", "event"), newEvent("CoolenessRemoved", new byte[0], new byte[0]));
        underTest.shovelAllNewlyAvailableEvents();

        inputEventArrived(streamId("david", "tom"), newEvent("CoolenessAdded", new byte[0], new byte[0]));
        underTest.shovelAllNewlyAvailableEvents();

        List<EventRecord> shovelledEvents = outputSource.readAll().readAllForwards().skip(1).map(ResolvedEvent::eventRecord).collect(Collectors.toList());

        assertThat(shovelledEvents, contains(
                anEventRecord(
                        clock.instant(),
                        StreamId.streamId("david", "tom"),
                        0L,
                        "CoolenessAdded",
                        new byte[0],
                        "{\"shovel_position\":\"2\"}".getBytes(UTF_8)
                )
        ));
    }

    @Test public void
    a_new_shovel_shovels_only_events_that_it_has_not_previously_shovelled() throws Exception {
        inputEventArrived(streamId("david", "tom"), newEvent("CoolenessRemoved", new byte[0], new byte[0]));
        inputEventArrived(streamId("another", "stream"), newEvent("TooMuchCoolness", new byte[0], new byte[0]));
        underTest.shovelAllNewlyAvailableEvents();

        inputEventArrived(streamId("david", "tom"), newEvent("CoolenessAdded", new byte[0], new byte[0]));
        new EventShovel(inputSource.readAll(), inputSource.positionCodec(), outputSource).shovelAllNewlyAvailableEvents();

        List<EventRecord> shovelledEvents = outputSource.readAll().readAllForwards().map(ResolvedEvent::eventRecord).collect(Collectors.toList());

        assertThat(shovelledEvents, contains(
                anEventRecord(
                        clock.instant(),
                        StreamId.streamId("david", "tom"),
                        0L,
                        "CoolenessRemoved",
                        new byte[0],
                        "{\"shovel_position\":\"1\"}".getBytes(UTF_8)
                ), anEventRecord(
                        clock.instant(),
                        StreamId.streamId("another", "stream"),
                        0L,
                        "TooMuchCoolness",
                        new byte[0],
                        "{\"shovel_position\":\"2\"}".getBytes(UTF_8)
                ), anEventRecord(
                        clock.instant(),
                        StreamId.streamId("david", "tom"),
                        1L,
                        "CoolenessAdded",
                        new byte[0],
                        "{\"shovel_position\":\"3\"}".getBytes(UTF_8)
                )
        ));
    }

    @Test public void
    maintains_metadata_from_upstream() throws Exception {
        String originalMetadata = "{\"effective_timestamp\":\"2015-02-12T04:23:34Z\"}";
        inputEventArrived(streamId("david", "tom"), newEvent("CoolenessAdded", new byte[0], originalMetadata.getBytes(UTF_8)));

        underTest.shovelAllNewlyAvailableEvents();

        List<EventRecord> shovelledEvents = outputSource.readAll().readAllForwards().map(ResolvedEvent::eventRecord).collect(Collectors.toList());

        assertThat(shovelledEvents, contains(
                anEventRecord(
                        clock.instant(),
                        StreamId.streamId("david", "tom"),
                        0L,
                        "CoolenessAdded",
                        new byte[0],
                        "{\"effective_timestamp\":\"2015-02-12T04:23:34Z\",\"shovel_position\":\"1\"}".getBytes(UTF_8)
                )
        ));
    }

    @Test public void
    it_does_optimistic_locking() throws Exception {
        inputEventArrived(streamId("david", "tom"), newEvent("CoolenessAdded", new byte[0], new byte[0]));
        inputEventArrived(streamId("foo", "bar"), newEvent("CoolenessRemoved", new byte[0], new byte[0]));
        inputEventArrived(streamId("david", "tom"), newEvent("CoolenessChanged", new byte[0], new byte[0]));

        List<Long> expectedVersionsSeen = new ArrayList<>();
        InMemoryEventSource contendedOutputSource = new InMemoryEventSource(new JavaInMemoryEventStore(clock) {
            @Override
            public void write(StreamId streamId, Collection<NewEvent> events, long expectedVersion) {
                if (!events.isEmpty()) {
                    super.write(streamId, events, expectedVersion);
                    expectedVersionsSeen.add(expectedVersion);
                }
            }
        });
        new EventShovel(inputSource.readAll(), inputSource.positionCodec(), contendedOutputSource).shovelAllNewlyAvailableEvents();

        assertThat(expectedVersionsSeen, contains(-1L, -1L, 0L));
    }

    @Test public void
    batching_works() throws Exception {
        inputEventArrived(streamId("david", "tom"),
                            newEvent("CoolenessAdded", new byte[0], new byte[0]),
                            newEvent("CoolenessRemoved", new byte[0], new byte[0]),
                            newEvent("CoolenessChanged", new byte[0], new byte[0]));

        List<Integer> batchSizes = new ArrayList<>();
        InMemoryEventSource rememberingBatchsizesEventStore = new InMemoryEventSource(new JavaInMemoryEventStore(clock) {
            @Override
            public void write(StreamId streamId, Collection<NewEvent> events, long expectedVersion) {
                batchSizes.add(events.size());
            }
        });

        new EventShovel(2, inputSource.readAll(), inputSource.positionCodec(), rememberingBatchsizesEventStore).shovelAllNewlyAvailableEvents();

        assertThat(batchSizes, contains(2, 1));
    }

    @Test public void
    handles_contended_out_of_order_writes_using_idempotent_writes() throws Exception {
        inputEventArrived(streamId("bar", "tom"), newEvent("CoolenessAdded", "{\"data\":1}".getBytes(), "{\"md\":1}".getBytes()));
        inputEventArrived(streamId("foo", "david"), newEvent("CoolenessRemoved", "{\"data\":2}".getBytes(), "{\"md\":2}".getBytes()));
        inputEventArrived(streamId("bar", "tom"), newEvent("CoolenessChanged", "{\"data\":3}".getBytes(), "{\"md\":3}".getBytes()));

        // pretend another shovel shovelled these events, but reverse the order of events 1 and 2, and remove event 3.
        InMemoryEventSource pretendOutputSource = new InMemoryEventSource(new JavaInMemoryEventStore(clock));
        new EventShovel(inputSource.readAll(), inputSource.positionCodec(), pretendOutputSource).shovelAllNewlyAvailableEvents();
        InMemoryEventSource outputSource = new InMemoryEventSource(new JavaInMemoryEventStore(clock));
        ResolvedEvent eventFromOtherShovel1 = pretendOutputSource.readCategory().readCategoryForwards("foo").iterator().next();
        outputSource.writeStream().write(
                eventFromOtherShovel1.eventRecord().streamId(),
                Collections.singleton(newEvent(
                        eventFromOtherShovel1.eventRecord().eventType(),
                        eventFromOtherShovel1.eventRecord().data(),
                        eventFromOtherShovel1.eventRecord().metadata()
                )
                )
        );
        ResolvedEvent eventFromOtherShovel2 = pretendOutputSource.readCategory().readCategoryForwards("bar").iterator().next();
        outputSource.writeStream().write(
                eventFromOtherShovel2.eventRecord().streamId(),
                Collections.singleton(newEvent(
                        eventFromOtherShovel2.eventRecord().eventType(),
                        eventFromOtherShovel2.eventRecord().data(),
                        eventFromOtherShovel2.eventRecord().metadata()
                        )
                )
        );

        // test our shovel against the out-of-order mess
        new EventShovel(inputSource.readAll(), inputSource.positionCodec(), outputSource).shovelAllNewlyAvailableEvents();

        List<EventRecord> shovelledEvents = outputSource.readAll().readAllForwards().map(ResolvedEvent::eventRecord).collect(Collectors.toList());
        assertThat(shovelledEvents, contains(
                anEventRecord(
                        clock.instant(),
                        StreamId.streamId("foo", "david"),
                        0L,
                        "CoolenessRemoved",
                        "{\"data\":2}".getBytes(),
                        "{\"md\":2,\"shovel_position\":\"2\"}".getBytes(UTF_8)
                ), anEventRecord(
                        clock.instant(),
                        StreamId.streamId("bar", "tom"),
                        0L,
                        "CoolenessAdded",
                        "{\"data\":1}".getBytes(),
                        "{\"md\":1,\"shovel_position\":\"1\"}".getBytes(UTF_8)
                ), anEventRecord(
                        clock.instant(),
                        StreamId.streamId("bar", "tom"),
                        1L,
                        "CoolenessChanged",
                        "{\"data\":3}".getBytes(),
                        "{\"md\":3,\"shovel_position\":\"3\"}".getBytes(UTF_8)
                )
        ));
    }

    @Test public void
    fails_idempotent_writes_if_event_data_changes() throws Exception {
        inputEventArrived(streamId("bar", "tom"), newEvent("CoolenessAdded", "{\"data\":1}".getBytes(), "{\"md\":1}".getBytes()));
        inputEventArrived(streamId("foo", "david"), newEvent("CoolenessRemoved", "{\"data\":2}".getBytes(), "{\"md\":2}".getBytes()));
        inputEventArrived(streamId("bar", "tom"), newEvent("CoolenessChanged", "{\"data\":3}".getBytes(), "{\"md\":3}".getBytes()));

        // pretend another shovel shovelled these events, but reverse the order of events 1 and 2, and remove event 3.
        InMemoryEventSource pretendOutputSource = new InMemoryEventSource(new JavaInMemoryEventStore(clock));
        new EventShovel(inputSource.readAll(), inputSource.positionCodec(), pretendOutputSource).shovelAllNewlyAvailableEvents();
        InMemoryEventSource outputSource = new InMemoryEventSource(new JavaInMemoryEventStore(clock));
        ResolvedEvent eventFromOtherShovel1 = pretendOutputSource.readCategory().readCategoryForwards("foo").iterator().next();
        outputSource.writeStream().write(
                eventFromOtherShovel1.eventRecord().streamId(),
                Collections.singleton(newEvent(
                        eventFromOtherShovel1.eventRecord().eventType(),
                        "bad data".getBytes(),
                        eventFromOtherShovel1.eventRecord().metadata()
                        )
                )
        );
        ResolvedEvent eventFromOtherShovel2 = pretendOutputSource.readCategory().readCategoryForwards("bar").iterator().next();
        outputSource.writeStream().write(
                eventFromOtherShovel2.eventRecord().streamId(),
                Collections.singleton(newEvent(
                        eventFromOtherShovel2.eventRecord().eventType(),
                        eventFromOtherShovel2.eventRecord().data(),
                        eventFromOtherShovel2.eventRecord().metadata()
                        )
                )
        );

        EventShovel eventShovel = new EventShovel(inputSource.readAll(), inputSource.positionCodec(), outputSource);

        try {
            eventShovel.shovelAllNewlyAvailableEvents();
            Assert.fail("expected an IncompatibleNewEventException");
        } catch (IdempotentEventStreamWriter.IncompatibleNewEventException e) {
            //pass
        }
    }

    @Test public void
    fails_idempotent_writes_if_event_metadata_changes() throws Exception {
        inputEventArrived(streamId("bar", "tom"), newEvent("CoolenessAdded", "{\"data\":1}".getBytes(), "{\"md\":1}".getBytes()));
        inputEventArrived(streamId("foo", "david"), newEvent("CoolenessRemoved", "{\"data\":2}".getBytes(), "{\"md\":2}".getBytes()));
        inputEventArrived(streamId("bar", "tom"), newEvent("CoolenessChanged", "{\"data\":3}".getBytes(), "{\"md\":3}".getBytes()));

        // pretend another shovel shovelled these events, but reverse the order of events 1 and 2, and remove event 3.
        InMemoryEventSource pretendOutputSource = new InMemoryEventSource(new JavaInMemoryEventStore(clock));
        new EventShovel(inputSource.readAll(), inputSource.positionCodec(), pretendOutputSource).shovelAllNewlyAvailableEvents();
        InMemoryEventSource outputSource = new InMemoryEventSource(new JavaInMemoryEventStore(clock));
        ResolvedEvent eventFromOtherShovel1 = pretendOutputSource.readCategory().readCategoryForwards("foo").iterator().next();
        outputSource.writeStream().write(
                eventFromOtherShovel1.eventRecord().streamId(),
                Collections.singleton(newEvent(
                        eventFromOtherShovel1.eventRecord().eventType(),
                        eventFromOtherShovel1.eventRecord().data(),
                        "{\"md\":\"bad metadata\",\"shovel_position\":\"2\"}".getBytes(UTF_8)
                        )
                )
        );
        ResolvedEvent eventFromOtherShovel2 = pretendOutputSource.readCategory().readCategoryForwards("bar").iterator().next();
        outputSource.writeStream().write(
                eventFromOtherShovel2.eventRecord().streamId(),
                Collections.singleton(newEvent(
                        eventFromOtherShovel2.eventRecord().eventType(),
                        eventFromOtherShovel2.eventRecord().data(),
                        eventFromOtherShovel2.eventRecord().metadata()
                        )
                )
        );

        EventShovel eventShovel = new EventShovel(inputSource.readAll(), inputSource.positionCodec(), outputSource);

        try {
            eventShovel.shovelAllNewlyAvailableEvents();
            Assert.fail("expected an IncompatibleNewEventException");
        } catch (IdempotentEventStreamWriter.IncompatibleNewEventException e) {
            //pass
        }
    }


    private void inputEventArrived(StreamId streamId, NewEvent... events) {
        inputReader.write(streamId, Lists.newArrayList(events));
    }

}