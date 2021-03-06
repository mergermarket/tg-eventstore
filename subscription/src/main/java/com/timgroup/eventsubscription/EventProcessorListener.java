package com.timgroup.eventsubscription;

import com.timgroup.eventstore.api.Position;

public interface EventProcessorListener {
    void eventProcessingFailed(Position position, Exception e);

    void eventProcessed(Position position);

    void eventDeserializationFailed(Position position, Exception e);

    void eventDeserialized(Position position);
}
