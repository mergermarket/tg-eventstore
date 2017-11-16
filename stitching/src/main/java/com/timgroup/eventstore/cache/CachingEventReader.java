package com.timgroup.eventstore.cache;

import com.timgroup.eventstore.api.EventReader;
import com.timgroup.eventstore.api.EventRecord;
import com.timgroup.eventstore.api.Position;
import com.timgroup.eventstore.api.PositionCodec;
import com.timgroup.eventstore.api.ResolvedEvent;
import com.timgroup.eventstore.api.StreamId;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static java.lang.Long.MAX_VALUE;
import static java.util.stream.Stream.concat;
import static java.util.stream.StreamSupport.stream;

/**
 * Reads events from an underylying event reader and then stores in locally
 * on the file system. Note that the files are compressed via GZip.
 */
public class CachingEventReader implements EventReader {
    private final EventReader underlying;
    private final PositionCodec positionCodec;
    private final Path cacheDirectory;

    private static String cacheFileName = "cache.gz";

    public CachingEventReader(EventReader underlying, PositionCodec positionCodec, Path cacheDirectory) {
        this.underlying = underlying;
        this.positionCodec = positionCodec;
        this.cacheDirectory = cacheDirectory;
    }

    @Override
    public Stream<ResolvedEvent> readAllForwards() {
        Stream<ResolvedEvent> cachedEvents = readAllCachedEvents();
        AtomicReference<Position> lastPosition = new AtomicReference(underlying.emptyStorePosition());

        Spliterator<ResolvedEvent> wrappedSpliterator = new WriteToCacheSpliterator(lastPosition::get);
        return concat(cachedEvents
                        .peek(resolvedEvent -> lastPosition.set(resolvedEvent.position())),
                stream(wrappedSpliterator, false));
    }

    private void writeEvent(DataOutputStream output, ResolvedEvent resolvedEvent) {
        try {
            output.writeUTF(positionCodec.serializePosition(resolvedEvent.position()));
            EventRecord eventRecord = resolvedEvent.eventRecord();
            output.writeLong(eventRecord.timestamp().toEpochMilli());
            StreamId streamId = eventRecord.streamId();
            output.writeUTF(streamId.category());
            output.writeUTF(streamId.id());
            output.writeLong(eventRecord.eventNumber());
            output.writeUTF(eventRecord.eventType());
            output.writeInt(eventRecord.data().length);
            output.write(eventRecord.data());
            output.writeInt(eventRecord.metadata().length);
            output.write(eventRecord.metadata());
        } catch (IOException e) {
            e.printStackTrace(); // todo
        }
    }

    private Stream<ResolvedEvent> readAllCachedEvents() {
        Path cachePath = cacheDirectory.resolve(cacheFileName);
        File cacheFile = cachePath.toFile();
        Optional<Path> cachedFiles = cacheFile.exists() ? Optional.of(cacheFile.toPath()) : Optional.empty();
        return stream(new ReadCacheSpliterator(cachedFiles), false);
    }

    @Override
    public Stream<ResolvedEvent> readAllForwards(Position positionExclusive) {
        if (positionExclusive.equals(emptyStorePosition())) {
            return readAllForwards();
        } else {
            return underlying.readAllForwards(positionExclusive);
        }
    }

    @Override
    public Position emptyStorePosition() {
        return underlying.emptyStorePosition();
    }

    private class WriteToCacheSpliterator implements Spliterator<ResolvedEvent> {
        private final Supplier<Position> lastPosition;
        private DataOutputStream output;
        private File tmpOutputFile;
        private Spliterator<ResolvedEvent> underlyingSpliterator;

        public WriteToCacheSpliterator(Supplier<Position> lastPosition) {
            this.lastPosition = lastPosition;
        }

        @Override
        public boolean tryAdvance(Consumer<? super ResolvedEvent> action) {
            if (underlyingSpliterator == null) {
                try {
                    tmpOutputFile = File.createTempFile("cache", ".inprogess", cacheDirectory.toFile());
                    output = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmpOutputFile)));
                    underlyingSpliterator = underlying.readAllForwards(lastPosition.get()).spliterator();
                } catch (IOException e) {
                    e.printStackTrace(); // todo
                }
            }
            boolean advanced = underlyingSpliterator.tryAdvance(event -> {
                writeEvent(output, event);
                action.accept(event);
            });

            if (!advanced) {
                try {
                    output.close();
                    File dest = cacheDirectory.resolve(cacheFileName).toFile();
                    if (!dest.exists()) {
                        tmpOutputFile.renameTo(dest); // todo don't ignore rename
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    // todo
                }
            }

            return advanced;
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

    private class ReadCacheSpliterator implements Spliterator<ResolvedEvent> {
        private final Optional<Path> cachedFiles;
        private DataInputStream current;

        public ReadCacheSpliterator(Optional<Path> cachedFiles) {
            this.cachedFiles = cachedFiles;
            current = null;
        }

        @Override
        public boolean tryAdvance(Consumer<? super ResolvedEvent> action) {
            if (current == null && cachedFiles.isPresent()) {
                cachedFiles.ifPresent(path -> {
                    try {
                        current = new DataInputStream(new GZIPInputStream(new FileInputStream(path.toFile())));
                    } catch (FileNotFoundException e) {
                        e.printStackTrace(); // todo
                    } catch (IOException e) {
                        e.printStackTrace(); // todo
                    }
                });
            }
            if (current != null) {
                return readNextEvent(action);
            } else {
                return false;
            }
        }

        private boolean readNextEvent(Consumer<? super ResolvedEvent> action) {
            try {
                ResolvedEvent resolvedEvent = new ResolvedEvent(positionCodec.deserializePosition(current.readUTF()),
                        EventRecord.eventRecord(Instant.ofEpochMilli(current.readLong()),
                                StreamId.streamId(current.readUTF(), current.readUTF()),
                                current.readLong(),
                                current.readUTF(),
                                readByteArray(current),
                                readByteArray(current)));
                action.accept(resolvedEvent);
                return true;
            } catch (EOFException e) {
                // todo: this should only be OK if throw from first read (position reading)
                return false;
            } catch (IOException e) {
                e.printStackTrace(); // todo
                return false;
            }
        }

        private byte[] readByteArray(DataInputStream current) throws IOException {
            int size = current.readInt();
            byte[] buffer = new byte[size];
            current.readFully(buffer);
            return buffer;
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
}
